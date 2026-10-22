"""The controller's own MCP server.

Exposes the controller toolbox (ffmpeg, python, terminal, animation design and
the AnyMate device tools) to any MCP client — Claude Desktop, Cursor, VS Code
or another agent — over stdio or HTTP.
"""

from __future__ import annotations

import asyncio
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Dict, List, Optional

from .tools.registry import ToolRegistry

PROTOCOL_VERSION = "2025-06-18"
SERVER_NAME = "anymate-controller"
SERVER_VERSION = "1.0.0"

INSTRUCTIONS = """
anymate-controller is a workstation brain box for the AnyMate Android studio.

It bridges three worlds:
* AnyMate — a 2D animation studio on a phone. Draw with anymate_draw, build
  timelines with anymate_frames, and watch the result with anymate_snapshot.
* ffmpeg — probe, convert, extract frames, build GIFs and join clips.
* Python and the terminal — the machine's own scripting power.

Guidelines:
* Check anymate_status first; if the phone is offline, keep working on files and
  ffmpeg, and tell the user the device is unreachable.
* Use anymate_motion_preset for quick motion studies, then refine with
  anymate_draw and anymate_batch.
* Every AnyMate mutation is visible to the artist immediately, so batch work
  and keep each sequence to a single undo step.
* Return concise, concrete results: file paths, frame counts, what changed.
""".strip()


class MCPServer:
    """JSON-RPC dispatch shared by the stdio and HTTP transports."""

    def __init__(self, registry: ToolRegistry, resources: Optional[List[Dict[str, Any]]] = None) -> None:
        self.registry = registry
        self.resources = resources or []
        self._lock = threading.Lock()

    # ---- protocol ----------------------------------------------------------
    def initialize(self, params: Dict[str, Any]) -> Dict[str, Any]:
        requested = params.get("protocolVersion")
        versions = ["2025-06-18", "2025-03-26", "2024-11-05"]
        version = requested if requested in versions else PROTOCOL_VERSION
        return {
            "protocolVersion": version,
            "capabilities": {
                "tools": {"listChanged": False},
                "resources": {"subscribe": False, "listChanged": False},
                "prompts": {"listChanged": False},
            },
            "serverInfo": {"name": SERVER_NAME, "title": "AnyMate Controller", "version": SERVER_VERSION},
            "instructions": INSTRUCTIONS,
        }

    def list_tools(self) -> Dict[str, Any]:
        return {"tools": self.registry.mcp_tools()}

    def list_resources(self) -> Dict[str, Any]:
        return {"resources": self.resources}

    def list_prompts(self) -> Dict[str, Any]:
        return {
            "prompts": [
                {
                    "name": "direct-an-animation",
                    "title": "Direct an animation in AnyMate",
                    "description": "Plan and build a looping animation on the phone, then play it.",
                    "arguments": [
                        {"name": "idea", "description": "What should move?", "required": True}
                    ],
                },
                {
                    "name": "render-with-ffmpeg",
                    "title": "Render with ffmpeg",
                    "description": "Turn frames or footage into a polished video and GIF.",
                },
            ]
        }

    def get_prompt(self, params: Dict[str, Any]) -> Dict[str, Any]:
        name = params.get("name", "")
        arguments = params.get("arguments", {}) or {}
        if name == "direct-an-animation":
            idea = arguments.get("idea", "a paper plane looping through clouds")
            text = (
                f"Build a looping animation in AnyMate from this idea: {idea}.\n"
                "Start with anymate_status and anymate_get_state, pick a frame rate, "
                "draw with anymate_draw, then finish with anymate_play and report the frame range."
            )
        elif name == "render-with-ffmpeg":
            text = (
                "Render the current work: probe the media, extract frames, then encode an mp4 "
                "and an optimised GIF. Report the output paths."
            )
        else:
            raise KeyError(f"Unknown prompt: {name}")
        return {
            "description": "AnyMate Controller prompt",
            "messages": [{"role": "user", "content": {"type": "text", "text": text}}],
        }

    async def call_tool(self, params: Dict[str, Any]) -> Dict[str, Any]:
        name = params.get("name", "")
        arguments = params.get("arguments", {}) or {}
        result = await self.registry.call(name, arguments)
        content: List[Dict[str, Any]] = []
        if result.image:
            import base64

            content.append(
                {
                    "type": "image",
                    "data": base64.b64encode(result.image).decode("ascii"),
                    "mimeType": result.mime,
                }
            )
        content.insert(0, {"type": "text", "text": result.text})
        payload: Dict[str, Any] = {"content": content, "isError": not result.ok}
        if result.data is not None:
            payload["structuredContent"] = result.data
        return payload

    async def handle(self, message: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        method = message.get("method")
        params = message.get("params", {}) or {}
        request_id = message.get("id")

        if method is None:
            return None
        if request_id is None:
            # Notification: acknowledge silently.
            return None

        try:
            with self._lock:
                if method == "initialize":
                    result = self.initialize(params)
                elif method == "ping":
                    result = {}
                elif method == "tools/list":
                    result = self.list_tools()
                elif method == "tools/call":
                    result = await self.call_tool(params)
                elif method == "resources/list":
                    result = self.list_resources()
                elif method == "prompts/list":
                    result = self.list_prompts()
                elif method == "prompts/get":
                    result = self.get_prompt(params)
                elif method == "logging/setLevel":
                    result = {}
                else:
                    return {
                        "jsonrpc": "2.0",
                        "id": request_id,
                        "error": {"code": -32601, "message": f"Method not found: {method}"},
                    }
        except KeyError as exc:
            return {"jsonrpc": "2.0", "id": request_id, "error": {"code": -32602, "message": str(exc)}}
        except Exception as exc:  # noqa: BLE001
            return {
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {"code": -32603, "message": f"Internal error: {exc}"},
            }
        return {"jsonrpc": "2.0", "id": request_id, "result": result}

    # ---- transports --------------------------------------------------------
    def serve_stdio(self) -> None:
        """Serve MCP over stdin/stdout (the Claude Desktop transport)."""

        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)

        def pump() -> None:
            for line in sys.stdin:
                line = line.strip()
                if not line:
                    continue
                try:
                    message = json.loads(line)
                except ValueError:
                    continue
                if isinstance(message, list):
                    responses = []
                    for item in message:
                        response = loop.run_until_complete(self.handle(item))
                        if response:
                            responses.append(response)
                    if responses:
                        sys.stdout.write(json.dumps(responses) + "\n")
                        sys.stdout.flush()
                    continue
                response = loop.run_until_complete(self.handle(message))
                if response:
                    sys.stdout.write(json.dumps(response) + "\n")
                    sys.stdout.flush()

        thread = threading.Thread(target=pump, daemon=True)
        thread.start()
        thread.join()

    def serve_http(self, host: str = "127.0.0.1", port: int = 8788) -> ThreadingHTTPServer:
        """Serve MCP over Streamable HTTP."""

        server = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, fmt: str, *args: Any) -> None:  # noqa: A003
                return

            def _send(self, status: int, body: bytes, content_type: str) -> None:
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(body)

            def do_OPTIONS(self) -> None:  # noqa: N802
                self.send_response(204)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
                self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                self.end_headers()

            def do_GET(self) -> None:  # noqa: N802
                if self.path.rstrip("/") in ("", "/health"):
                    payload = json.dumps(
                        {"name": SERVER_NAME, "status": "ok", "tools": len(server.registry.tools)}
                    ).encode("utf-8")
                    self._send(200, payload, "application/json")
                elif self.path.rstrip("/") == "/tools":
                    payload = json.dumps(server.registry.mcp_tools(), indent=2).encode("utf-8")
                    self._send(200, payload, "application/json")
                else:
                    self._send(404, b"Not found", "text/plain")

            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length", "0") or 0)
                raw = self.rfile.read(length).decode("utf-8") if length else "{}"
                try:
                    message = json.loads(raw)
                except ValueError:
                    self._send(400, b'{"error":"Parse error"}', "application/json")
                    return
                if isinstance(message, list):
                    loop = asyncio.new_event_loop()
                    try:
                        responses = [
                            response
                            for response in [loop.run_until_complete(server.handle(item)) for item in message]
                            if response
                        ]
                    finally:
                        loop.close()
                    payload = json.dumps(responses).encode("utf-8")
                else:
                    loop = asyncio.new_event_loop()
                    try:
                        response = loop.run_until_complete(server.handle(message))
                    finally:
                        loop.close()
                    payload = json.dumps(response or {}).encode("utf-8")
                self._send(200, payload, "application/json")

        httpd = ThreadingHTTPServer((host, port), Handler)
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        return httpd
