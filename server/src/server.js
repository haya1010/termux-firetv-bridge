import express from "express";
import http from "node:http";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocketServer, WebSocket } from "ws";

const here = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.resolve(here, "../public");
const port = Number(process.env.PORT || 8080);
const token = process.env.BRIDGE_TOKEN || "";
const fireTvSerial = process.env.FIRETV_SERIAL || "192.168.0.42:5555";
const adb = process.env.ADB_PATH || "adb";
const run = promisify(execFile);
const apiKey = process.env.OPENAI_API_KEY || (() => {
  try { return fs.readFileSync(path.join(os.homedir(), ".config/openai/key"), "utf8").trim(); }
  catch { return ""; }
})();

const app = express();
app.use(express.json());
app.use(express.static(publicDir, { extensions: ["html"] }));
app.get("/health", (_req, res) => res.type("text").send("ok"));
app.get("/status", (_req, res) => res.json({
  sender: peers.sender?.readyState === WebSocket.OPEN,
  receiver: peers.receiver?.readyState === WebSocket.OPEN,
  voice: peers.voice?.readyState === WebSocket.OPEN,
  realtime: openai?.readyState === WebSocket.OPEN
}));

let waking = null;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function wakeFireTv() {
  // `connect` is harmless when already connected and recovers after Wi-Fi sleep.
  await run(adb, ["connect", fireTvSerial], { timeout: 10_000 });
  await run(adb, ["-s", fireTvSerial, "shell", "input", "keyevent", "224"], { timeout: 10_000 });
  await delay(1_200);
  await run(adb, [
    "-s", fireTvSerial, "shell", "am", "start", "-n",
    "dev.termux.firetvreceiver/.MainActivity", "--es", "server_url",
    "ws://192.168.0.10:8080/ws"
  ], { timeout: 10_000 });
  return { ok: true, device: fireTvSerial };
}

app.post("/wake", async (req, res) => {
  if (token && req.get("authorization") !== `Bearer ${token}`) {
    return res.status(401).json({ ok: false, error: "unauthorized" });
  }
  try {
    waking ??= wakeFireTv().finally(() => { waking = null; });
    res.json(await waking);
  } catch (error) {
    console.error("Fire TV wake failed", error);
    res.status(500).json({ ok: false, error: String(error.message || error) });
  }
});

const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true });
const peers = { sender: null, receiver: null, voice: null };
let openai = null;
let voiceGateTimer = null;

function gatePhoneMic(muted) {
  send(peers.voice, { type: "voice-gate", muted });
  if (voiceGateTimer) clearTimeout(voiceGateTimer);
  voiceGateTimer = null;
}

function releasePhoneMicAfterPlayback() {
  if (voiceGateTimer) clearTimeout(voiceGateTimer);
  voiceGateTimer = setTimeout(() => gatePhoneMic(false), 1200);
}

function send(ws, data) {
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(data));
}

function connectOpenAI() {
  if (!apiKey || openai?.readyState === WebSocket.OPEN || openai?.readyState === WebSocket.CONNECTING) return;
  openai = new WebSocket("wss://api.openai.com/v1/realtime?model=gpt-realtime", {
    headers: { Authorization: `Bearer ${apiKey}` }
  });
  openai.on("open", () => openai.send(JSON.stringify({
    type: "session.update",
    session: {
      type: "realtime", model: "gpt-realtime", output_modalities: ["audio"],
      instructions: "あなたはちひろ。日本語で親しみやすく簡潔に会話してください。",
      audio: {
        input: { format: { type: "audio/pcm", rate: 24000 }, turn_detection: { type: "server_vad" } },
        output: { format: { type: "audio/pcm", rate: 24000 }, voice: "marin" }
      }
    }
  })));
  openai.on("message", raw => {
    const event = JSON.parse(raw.toString());
    if (["response.output_audio.delta", "response.audio.delta"].includes(event.type)) {
      gatePhoneMic(true);
      send(peers.receiver, { type: "realtime-audio", sampleRate: 24000, value: event.delta });
      send(peers.voice, { type: "echo-reference", sampleRate: 24000, value: event.delta });
      releasePhoneMicAfterPlayback();
    } else if (["response.output_audio.done", "response.audio.done"].includes(event.type)) {
      releasePhoneMicAfterPlayback();
    } else if (["response.output_audio_transcript.delta", "response.audio_transcript.delta"].includes(event.type))
      send(peers.receiver, { type: "realtime-transcript", value: event.delta });
    else if (event.type === "error") send(peers.receiver, { type: "realtime-error", value: event.error?.message || "Realtime error" });
  });
  openai.on("close", () => { openai = null; send(peers.receiver, { type: "realtime-status", value: "切断" }); });
  openai.on("error", error => send(peers.receiver, { type: "realtime-error", value: error.message }));
}

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (url.pathname !== "/ws") return socket.destroy();
  if (token && url.searchParams.get("token") !== token) {
    socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
    return socket.destroy();
  }
  const role = url.searchParams.get("role");
  if (!(role in peers)) return socket.destroy();
  wss.handleUpgrade(req, socket, head, ws => {
    ws.role = role;
    wss.emit("connection", ws);
  });
});

wss.on("connection", ws => {
  const old = peers[ws.role];
  if (old && old !== ws) old.close(4000, "replaced");
  peers[ws.role] = ws;
  if (ws.role === "voice") connectOpenAI();
  send(ws, { type: "status", value: "connected", role: ws.role });
  if (ws.role === "receiver") send(peers.sender, { type: "peer", value: "receiver-ready" });
  if (ws.role === "sender" && peers.receiver) send(ws, { type: "peer", value: "receiver-ready" });

  ws.on("message", raw => {
    try {
      const message = JSON.parse(raw.toString());
      message.from = ws.role;
      if (ws.role === "voice" && message.type === "voice-audio") {
        connectOpenAI();
        if (openai?.readyState === WebSocket.OPEN)
          openai.send(JSON.stringify({ type: "input_audio_buffer.append", audio: message.value }));
        return;
      }
      const other = ws.role === "sender" ? peers.receiver : peers.sender;
      send(other, message);
    } catch {
      send(ws, { type: "error", value: "invalid-json" });
    }
  });
  ws.on("close", () => {
    if (peers[ws.role] === ws) peers[ws.role] = null;
    if (ws.role === "voice" && openai) {
      openai.close(1000, "phone voice session ended");
      openai = null;
      gatePhoneMic(false);
    }
    const other = ws.role === "sender" ? peers.receiver : peers.sender;
    send(other, { type: "peer", value: `${ws.role}-closed` });
  });
});

server.listen(port, "0.0.0.0", () => {
  console.log(`Bridge ready: http://0.0.0.0:${port}`);
});
