"""Round-trip the controller's MCP client against a fake AnyMate server."""

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from anymate_controller.mcp_client import McpClient, McpError

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
]


class FakeHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # noqa: A003
        return

    def _json(self, payload, status=200):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Mcp-Session-Id", "session-1")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        if self.path == "/health":
            self._json({"status": "ok", "frames": 3})
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
            else:
                result = {"content": [{"type": "text", "text": "state ok"}]}
        else:
            result = {}
        self._json({"jsonrpc": "2.0", "id": request_id, "result": result})


@pytest.fixture()
def server():
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), FakeHandler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()


def test_initialize_and_list_tools(server):
    client = McpClient(f"{server}/mcp")
    result = client.initialize()
    assert result["serverInfo"]["name"] == "anymate"
    assert client.session_id == "session-1"
    tools = client.list_tools()
    assert {tool["name"] for tool in tools} == {"anymate_get_state", "anymate_snapshot"}


def test_call_tool_text_and_image(server):
    client = McpClient(f"{server}/mcp")
    client.initialize()
    assert "state ok" in client.call_tool_text("anymate_get_state")
    assert client.call_tool_image("anymate_snapshot") == b"hi"


def test_health(server):
    client = McpClient(f"{server}/mcp")
    assert client.health()["status"] == "ok"


def test_unreachable_server_raises():
    client = McpClient("http://127.0.0.1:9/mcp", timeout=1.0)
    with pytest.raises(McpError):
        client.initialize()
