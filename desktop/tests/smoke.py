"""Dependency-free smoke test for anymate-controller.

Run with ``python3 tests/smoke.py``. The pytest suite in this folder covers the
same ground in more detail; this script exists so the controller can be
verified on a machine that has nothing but Python.
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from anymate_controller import presets  # noqa: E402
from anymate_controller.agent import Agent  # noqa: E402
from anymate_controller.config import Config  # noqa: E402
from anymate_controller.mcp_client import McpClient  # noqa: E402
from anymate_controller.mcp_server import MCPServer  # noqa: E402
from anymate_controller.tools import anymate_tools, draw_tools, ffmpeg_tools, shell_tools  # noqa: E402
from anymate_controller.tools.pngio import read_png, write_png, Image  # noqa: E402
from anymate_controller.tools.registry import ToolRegistry  # noqa: E402

FAILURES = []


def check(label: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"  ok   {label}")
    else:
        print(f"  FAIL {label} {detail}")
        FAILURES.append(label)


def run(coroutine):
    loop = asyncio.new_event_loop()
    try:
        return loop.run_until_complete(coroutine)
    finally:
        loop.close()


def build_registry(workspace: Path) -> ToolRegistry:
    config = Config(workspace=str(workspace))
    registry = ToolRegistry()
    shell_tools.register(registry, config)
    ffmpeg_tools.register(registry, config)
    draw_tools.register(registry, config)
    anymate_tools.register(registry, config)
    return registry


# ---- fake device -----------------------------------------------------------

TOOLS = [
    {
        "name": "anymate_get_state",
        "description": "state",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "anymate_snapshot",
        "description": "snapshot",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "anymate_batch",
        "description": "batch",
        "inputSchema": {"type": "object", "properties": {}},
    },
]


class FakeDevice(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # noqa: A003
        return

    def _json(self, payload, status=200):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        if self.path == "/health":
            self._json({"status": "ok", "frames": 4, "fps": 24, "width": 900, "height": 900})
        else:
            self._json({"error": "not found"}, 404)

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", "0") or 0)
        message = json.loads(self.rfile.read(length) or b"{}")
        method = message.get("method")
        request_id = message.get("id")
        if method == "initialize":
            result = {
                "protocolVersion": "2025-06-18",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "anymate", "version": "1.0.0"},
            }
        elif method == "tools/list":
            result = {"tools": TOOLS}
        elif method == "tools/call":
            name = message["params"]["name"]
            if name == "anymate_snapshot":
                result = {
                    "content": [
                        {"type": "text", "text": "snapshot"},
                        {"type": "image", "data": "aGk=", "mimeType": "image/png"},
                    ]
                }
            elif name == "anymate_get_state":
                result = {
                    "content": [
                        {
                            "type": "text",
                            "text": 'Live AnyMate project.\n{"width": 900, "height": 900, "frames": 4}',
                        }
                    ]
                }
            else:
                calls = message["params"].get("arguments", {}).get("calls", [])
                result = {
                    "content": [
                        {"type": "text", "text": f"Batch complete: {len(calls)} calls."}
                    ]
                }
        else:
            result = {}
        self._json({"jsonrpc": "2.0", "id": request_id, "result": result})


def main() -> int:
    workspace = Path(tempfile.mkdtemp(prefix="anymate-controller-"))
    registry = build_registry(workspace)
    print("registry")
    check("tools registered", len(registry.tools) > 20, str(len(registry.tools)))
    groups = registry.groups()
    for group in ("terminal", "python", "ffmpeg", "animation", "anymate"):
        check(f"group {group}", group in groups)

    print("animation tools")
    palette = run(registry.call("anim_palette", {"base": "#7C3AED", "count": 5}))
    check("palette", palette.ok and len(palette.data["colors"]) == 5)
    check(
        "palette keeps the base colour",
        palette.data["colors"][0].upper() == "#7C3AED",
        str(palette.data["colors"]),
    )
    check(
        "palette is not monochrome noise",
        len(set(palette.data["colors"])) >= 3,
        str(palette.data["colors"]),
    )
    easing = run(registry.call("anim_easing", {"easing": "ease-in-out", "count": 8}))
    check("easing", easing.ok and easing.data["values"] == sorted(easing.data["values"]))
    timing = run(registry.call("anim_timing", {"fps": 24, "seconds": 2}))
    check("timing", timing.data["total_frames"] == 48)
    storyboard = run(registry.call("anim_storyboard", {"beats": "wide\nclose", "fps": 8}))
    check("storyboard", len(storyboard.data["beats"]) == 2)
    svg = run(registry.call("anim_svg", {"shape": "circle", "size": 200}))
    check("svg", "<svg" in svg.text)
    png = run(registry.call("anim_png", {"output_path": "card.png", "width": 32, "height": 24}))
    check("png written", png.ok and (workspace / "card.png").exists())
    image = read_png(workspace / "card.png")
    check("png readable", image.width == 32 and image.height == 24)

    print("raster pipeline")
    for index in range(3):
        frame = Image(8, 8, bytearray(8 * 8 * 4))
        for y in range(8):
            for x in range(8):
                frame.set_pixel(x, y, (index * 60 % 256, 100, 200, 255))
        write_png(workspace / f"f{index}.png", frame)
    sheet = run(
        registry.call(
            "anim_sprite_sheet",
            {"frame_paths": [f"f{i}.png" for i in range(3)], "output_path": "sheet.png", "columns": 2},
        )
    )
    check("sprite sheet", sheet.ok and read_png(workspace / "sheet.png").width == 16)

    print("terminal + python")
    written = run(registry.call("shell_write", {"path": "notes.txt", "content": "hello"}))
    check("shell write", written.ok)
    read_back = run(registry.call("shell_read", {"path": "notes.txt"}))
    check("shell read", read_back.text.strip() == "hello")
    listed = run(registry.call("shell_list", {"pattern": "*.txt"}))
    check("shell list", "notes.txt" in listed.data["files"])
    python = run(registry.call("python_run", {"code": "print(6*7)"}))
    check("python runs", python.ok and "42" in python.text, python.text[:120])
    shell = run(registry.call("shell_run", {"command": "echo controller"}))
    check("shell runs", shell.ok and "controller" in shell.text)

    print("presets")
    for name in presets.PRESETS:
        frames = presets.build(name, 900, 900, 6)
        check(f"preset {name}", len(frames) == 6)

    print("mcp client")
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), FakeDevice)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_address[1]}"
    client = McpClient(f"{base}/mcp")
    init = client.initialize()
    check("initialize", init["serverInfo"]["name"] == "anymate")
    check("tools/list", len(client.list_tools()) == 3)
    check("call text", "Live AnyMate" in client.call_tool_text("anymate_get_state"))
    check("call image", client.call_tool_image("anymate_snapshot") == b"hi")
    check("health", client.health()["status"] == "ok")

    print("controller mcp server")
    server = MCPServer(registry)
    request = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": "anim_palette", "arguments": {"base": "#22D3EE", "count": 3}},
    }
    response = run(server.handle(request))
    check("mcp tool call", response["result"]["content"][0]["type"] == "text")
    listed = run(server.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}))
    check("mcp tools list", len(listed["result"]["tools"]) == len(registry.tools))
    http_server = server.serve_http(host="127.0.0.1", port=0)
    check("mcp http bound", http_server.server_address[1] > 0)
    http_server.shutdown()

    print("agent")
    agent = Agent(Config(), registry)
    check("agent endpoint", agent.endpoint().endswith("/chat/completions"), agent.endpoint())

    httpd.shutdown()
    print()
    if FAILURES:
        print(f"{len(FAILURES)} failure(s): {', '.join(FAILURES)}")
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
