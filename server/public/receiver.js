const video = document.querySelector("#screen");
const status = document.querySelector("#status");
const params = new URLSearchParams(location.search);
const token = params.get("token") || "";
const protocol = location.protocol === "https:" ? "wss:" : "ws:";
const wsUrl = `${protocol}//${location.host}/ws?role=receiver&token=${encodeURIComponent(token)}`;
let ws;
let pc;

function setStatus(value) { status.textContent = value; }
function send(message) { if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(message)); }

function makePeer() {
  pc?.close();
  pc = new RTCPeerConnection({ iceServers: [] });
  pc.ontrack = event => { video.srcObject = event.streams[0]; video.play().catch(() => {}); };
  pc.onicecandidate = event => event.candidate && send({ type: "ice", candidate: event.candidate });
  pc.onconnectionstatechange = () => setStatus(`WebRTC: ${pc.connectionState}`);
}

async function onMessage(message) {
  if (message.type === "offer") {
    makePeer();
    await pc.setRemoteDescription(message.sdp);
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    send({ type: "answer", sdp: pc.localDescription });
  } else if (message.type === "ice" && pc) {
    await pc.addIceCandidate(message.candidate).catch(console.warn);
  } else if (message.type === "audio-level") {
    window.chihiro.setMouth(message.value);
  } else if (message.type === "peer") {
    setStatus(message.value);
  }
}

function connect() {
  ws = new WebSocket(wsUrl);
  ws.onopen = () => { setStatus("Androidを待っています"); };
  ws.onmessage = e => { try { onMessage(JSON.parse(e.data)); } catch (err) { console.error(err); } };
  ws.onclose = () => { setStatus("再接続中…"); setTimeout(connect, 1500); };
}

const keyMap = { ArrowUp:"up", ArrowDown:"down", ArrowLeft:"left", ArrowRight:"right", Enter:"select", Escape:"back", BrowserBack:"back", Backspace:"back" };
window.addEventListener("keydown", event => {
  const action = keyMap[event.key];
  if (!action) return;
  event.preventDefault();
  send({ type: "remote", action });
});
document.querySelector("#back").onclick = () => send({ type:"remote", action:"back" });
connect();
