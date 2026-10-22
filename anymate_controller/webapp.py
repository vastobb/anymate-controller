"""A small local web UI for the controller.

Chat with the agent, poke single tools, watch the phone's canvas live and
browse the workspace — all from the browser on your own machine.
"""

from __future__ import annotations

import json
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, List, Optional

from .agent import Agent
from .mcp_client import McpClient, McpError
from .tools.registry import ToolRegistry

PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>AnyMate Controller</title>
<style>
  :root {
    --ink-950:#0B0A10; --ink-900:#111017; --ink-850:#17141E; --ink-800:#1E1A28;
    --violet:#A78BFA; --cyan:#22D3EE; --text:#F4F1FA; --muted:#9C94AC;
    --line:rgba(255,255,255,.09); --ok:#34D399; --warn:#FBBF24;
  }
  * { box-sizing:border-box; }
  body { margin:0; background:radial-gradient(1200px 600px at 50% -10%,#241A3A 0%,var(--ink-950) 60%);
    color:var(--text); font:15px/1.55 ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif; }
  header { display:flex; align-items:center; gap:12px; padding:14px 22px; border-bottom:1px solid var(--line); }
  .mark { width:32px; height:32px; border-radius:10px; background:linear-gradient(135deg,#7C3AED,#22D3EE);
    display:grid; place-items:center; color:#0B0714; font-weight:700; }
  h1 { font-size:15px; margin:0; letter-spacing:.16em; }
  .tag { color:var(--muted); font-size:12px; }
  main { display:grid; grid-template-columns:minmax(0,1fr) 340px; gap:18px; padding:18px 22px 26px; max-width:1500px; margin:0 auto; }
  .panel { background:rgba(23,20,30,.78); border:1px solid var(--line); border-radius:18px; }
  .chat { display:flex; flex-direction:column; height:calc(100vh - 190px); min-height:520px; }
  #log { flex:1; overflow:auto; padding:18px; display:flex; flex-direction:column; gap:12px; }
  .msg { max-width:86%; padding:11px 14px; border-radius:14px; white-space:pre-wrap; }
  .msg.user { align-self:flex-end; background:linear-gradient(135deg,#4C2B8F,#33235E); }
  .msg.bot { align-self:flex-start; background:var(--ink-800); border:1px solid var(--line); }
  .msg.tool { align-self:flex-start; background:#141219; border:1px dashed var(--line); color:var(--muted); font-size:12px; font-family:ui-monospace,monospace; }
  .composer { display:flex; gap:10px; padding:14px; border-top:1px solid var(--line); }
  textarea { flex:1; resize:none; background:var(--ink-900); color:var(--text); border:1px solid var(--line);
    border-radius:14px; padding:12px 14px; font:inherit; min-height:48px; }
  button { background:linear-gradient(135deg,#7C3AED,#22D3EE); color:#0B0714; border:0; border-radius:12px;
    padding:11px 18px; font-weight:650; cursor:pointer; }
  button.ghost { background:var(--ink-800); color:var(--text); border:1px solid var(--line); }
  aside { display:flex; flex-direction:column; gap:18px; }
  .card { padding:14px; }
  .card h2 { margin:0 0 10px; font-size:11px; letter-spacing:.16em; color:var(--muted); text-transform:uppercase; }
  #preview { width:100%; border-radius:12px; border:1px solid var(--line); background:#0F0E14; display:block; }
  .row { display:flex; justify-content:space-between; padding:6px 0; font-size:12px; color:var(--muted); }
  .row b { color:var(--text); font-weight:550; }
  .dot { width:8px; height:8px; border-radius:50%; background:var(--muted); display:inline-block; margin-right:7px; }
  .dot.on { background:var(--ok); }
  .tool { padding:7px 0; border-bottom:1px solid var(--line); font-size:12px; }
  .tool code { color:var(--violet); font-size:11px; }
  .tool p { margin:2px 0 0; color:var(--muted); font-size:11px; }
  .scroll { max-height:260px; overflow:auto; }
  .quick { display:flex; flex-wrap:wrap; gap:8px; padding:0 18px 12px; }
  .quick button { background:var(--ink-800); color:var(--text); border:1px solid var(--line); font-weight:500; padding:7px 12px; font-size:12px; }
  @media (max-width:980px){ main{ grid-template-columns:1fr; } .chat{ height:auto; } }
</style>
</head>
<body>
<header>
  <div class="mark">✦</div>
  <div><h1>ANYMATE CONTROLLER</h1><div class="tag" id="status">connecting…</div></div>
</header>
<main>
  <section class="panel chat">
    <div id="log"></div>
    <div class="quick" id="quick"></div>
    <form class="composer" id="composer">
      <textarea id="input" placeholder="Direct the animation, render a video, run some Python…"></textarea>
      <button type="submit">Send</button>
      <button type="button" class="ghost" id="stop">Stop</button>
    </form>
  </section>
  <aside>
    <div class="panel card">
      <h2>AnyMate canvas</h2>
      <img id="preview" alt="Live canvas" />
      <div class="row"><span>Endpoint</span><b id="endpoint">—</b></div>
      <div class="row"><span>Tools</span><b id="toolcount">—</b></div>
    </div>
    <div class="panel card">
      <h2>Toolbox</h2>
      <div class="scroll" id="tools"></div>
    </div>
  </aside>
</main>
<script>
const log = document.getElementById('log');
function add(text, cls){ const d=document.createElement('div'); d.className='msg '+cls; d.textContent=text; log.appendChild(d); log.scrollTop=log.scrollHeight; return d; }
async function status(){
  const r = await fetch('/api/status'); const s = await r.json();
  document.getElementById('status').innerHTML = '<span class="dot '+(s.anymate.ok?'on':'')+'"></span>' + (s.anymate.ok ? 'AnyMate connected' : 'AnyMate offline');
  document.getElementById('endpoint').textContent = s.anymate.url;
  document.getElementById('toolcount').textContent = s.tools.length;
  document.getElementById('tools').innerHTML = s.tools.map(t=>'<div class="tool"><code>'+t.name+'</code><p>'+t.description+'</p></div>').join('');
  document.getElementById('quick').innerHTML = s.quick.map(q=>'<button type="button" onclick="ask('+JSON.stringify(q.prompt)+')">'+q.label+'</button>').join('');
  const img=document.getElementById('preview'); img.src='/api/preview?t='+Date.now();
}
function ask(text){ document.getElementById('input').value=text; document.getElementById('composer').requestSubmit(); }
document.getElementById('composer').addEventListener('submit', async (e)=>{
  e.preventDefault();
  const input=document.getElementById('input'); const text=input.value.trim(); if(!text) return;
  input.value=''; add(text,'user'); const pending=add('Working…','bot');
  const r=await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({prompt:text})});
  const data=await r.json(); pending.textContent=data.text||data.error||'No answer.';
  status();
});
document.getElementById('stop').addEventListener('click', async ()=>{ await fetch('/api/stop',{method:'POST'}); });
setInterval(status, 4000); status();
</script>
</body>
</html>
"""


class WebApp:
    """A tiny threaded web application around the controller."""

    def __init__(self, config, registry: ToolRegistry, agent: Optional[Agent] = None) -> None:
        self.config = config
        self.registry = registry
        self.agent = agent or Agent(config, registry)
        self._stop = threading.Event()
        self._client: Optional[McpClient] = None

    # ---- helpers -----------------------------------------------------------
    def client(self) -> McpClient:
        if self._client is None:
            client = McpClient(
                self.config.anymate_url, self.config.anymate_token, self.config.anymate_timeout
            )
            client.initialize()
            self._client = client
        return self._client

    def status_payload(self) -> Dict[str, Any]:
        anymate: Dict[str, Any] = {"ok": False, "url": self.config.anymate_url}
        try:
            health = self.client().health()
            anymate.update({"ok": True, "health": health})
        except McpError as exc:
            anymate["error"] = str(exc)
            self._client = None
        return {
            "anymate": anymate,
            "tools": [
                {"name": tool.name, "description": tool.description, "group": tool.group}
                for tool in self.registry.tools.values()
            ],
            "quick": [
                {
                    "label": "Bouncing ball",
                    "prompt": "Make a bouncing ball loop on the phone at 30 fps and play it.",
                },
                {
                    "label": "Fireworks",
                    "prompt": "Build a fireworks loop in AnyMate and then export a GIF.",
                },
                {
                    "label": "Probe a video",
                    "prompt": "Probe the media files in the workspace with ffprobe and summarise them.",
                },
                {
                    "label": "Python quickstart",
                    "prompt": "Run a Python snippet that writes a 4x4 colour palette PNG into the workspace.",
                },
            ],
            "workspace": str(self.config.workspace_path),
        }

    def preview_png(self) -> Optional[bytes]:
        try:
            return self.client().snapshot_png()
        except McpError:
            self._client = None
            return None

    # ---- http --------------------------------------------------------------
    def serve(self, host: Optional[str] = None, port: Optional[int] = None) -> None:
        app = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, fmt: str, *args: Any) -> None:  # noqa: A003
                return

            def _json(self, status: int, payload: Dict[str, Any]) -> None:
                body = json.dumps(payload).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _bytes(self, status: int, body: bytes, content_type: str) -> None:
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self) -> None:  # noqa: N802
                path = self.path.split("?", 1)[0]
                if path in ("/", "/index.html"):
                    self._bytes(200, PAGE.encode("utf-8"), "text/html; charset=utf-8")
                elif path == "/api/status":
                    self._json(200, app.status_payload())
                elif path == "/api/preview":
                    image = app.preview_png()
                    if image:
                        self._bytes(200, image, "image/png")
                    else:
                        self._bytes(404, b"", "text/plain")
                else:
                    self._json(404, {"error": "Not found"})

            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length", "0") or 0)
                raw = self.rfile.read(length).decode("utf-8") if length else "{}"
                try:
                    payload = json.loads(raw or "{}")
                except ValueError:
                    self._json(400, {"error": "Invalid JSON"})
                    return
                path = self.path.split("?", 1)[0]
                if path == "/api/chat":
                    prompt = payload.get("prompt", "")
                    if not prompt:
                        self._json(400, {"error": "prompt is required"})
                        return
                    events: List[Dict[str, Any]] = []

                    def on_event(event: Dict[str, Any]) -> None:
                        events.append(event)

                    import asyncio

                    loop = asyncio.new_event_loop()
                    try:
                        reply = loop.run_until_complete(app.agent.run(prompt, on_event=on_event))
                    finally:
                        loop.close()
                    self._json(
                        200,
                        {
                            "text": reply.text,
                            "error": reply.error,
                            "steps": reply.steps,
                            "tool_calls": reply.tool_calls,
                            "events": events,
                        },
                    )
                elif path == "/api/tool":
                    name = payload.get("name", "")
                    arguments = payload.get("arguments", {}) or {}
                    loop = asyncio.new_event_loop()
                    try:
                        result = loop.run_until_complete(app.registry.call(name, arguments))
                    finally:
                        loop.close()
                    self._json(200, result.as_dict())
                elif path == "/api/stop":
                    app._stop.set()
                    self._json(200, {"ok": True})
                else:
                    self._json(404, {"error": "Not found"})

        httpd = ThreadingHTTPServer((host or self.config.host, port or self.config.port), Handler)
        print(f"AnyMate Controller → http://{host or self.config.host}:{port or self.config.port}")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            httpd.server_close()
