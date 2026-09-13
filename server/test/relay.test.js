import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { WebSocket } from "ws";

const port = 18080;
const url = `ws://127.0.0.1:${port}/ws`;

function startServer() {
  const child = spawn(process.execPath, ["src/server.js"], { env: { ...process.env, PORT: String(port) }, stdio: ["ignore", "pipe", "inherit"] });
  return new Promise((resolve, reject) => {
    child.once("error", reject);
    child.stdout.on("data", data => { if (data.toString().includes("Bridge ready")) resolve(child); });
  });
}

function next(ws, wanted) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`timeout: ${wanted}`)), 2000);
    const listener = raw => {
      const value = JSON.parse(raw.toString());
      if (value.type !== wanted) return;
      clearTimeout(timer); ws.off("message", listener); resolve(value);
    };
    ws.on("message", listener);
  });
}

test("receiver remote commands are relayed to sender", async () => {
  const server = await startServer();
  try {
    const receiver = new WebSocket(`${url}?role=receiver`);
    await new Promise(resolve => receiver.once("open", resolve));
    const sender = new WebSocket(`${url}?role=sender`);
    await new Promise(resolve => sender.once("open", resolve));
    const relayed = next(sender, "remote");
    receiver.send(JSON.stringify({ type: "remote", action: "down" }));
    assert.equal((await relayed).action, "down");
    sender.close(); receiver.close();
  } finally {
    server.kill("SIGTERM");
  }
});
