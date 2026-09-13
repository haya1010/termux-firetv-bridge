import express from "express";
import http from "node:http";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
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

const app = express();
app.use(express.json());
app.use(express.static(publicDir, { extensions: ["html"] }));
app.get("/health", (_req, res) => res.type("text").send("ok"));
app.get("/status", (_req, res) => res.json({
  sender: peers.sender?.readyState === WebSocket.OPEN,
  receiver: peers.receiver?.readyState === WebSocket.OPEN
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
const peers = { sender: null, receiver: null };

function send(ws, data) {
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(data));
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
  send(ws, { type: "status", value: "connected", role: ws.role });
  if (ws.role === "receiver") send(peers.sender, { type: "peer", value: "receiver-ready" });
  if (ws.role === "sender" && peers.receiver) send(ws, { type: "peer", value: "receiver-ready" });

  ws.on("message", raw => {
    try {
      const message = JSON.parse(raw.toString());
      message.from = ws.role;
      const other = ws.role === "sender" ? peers.receiver : peers.sender;
      send(other, message);
    } catch {
      send(ws, { type: "error", value: "invalid-json" });
    }
  });
  ws.on("close", () => {
    if (peers[ws.role] === ws) peers[ws.role] = null;
    const other = ws.role === "sender" ? peers.receiver : peers.sender;
    send(other, { type: "peer", value: `${ws.role}-closed` });
  });
});

server.listen(port, "0.0.0.0", () => {
  console.log(`Bridge ready: http://0.0.0.0:${port}`);
});
