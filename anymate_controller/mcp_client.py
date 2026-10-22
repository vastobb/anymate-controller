"""A minimal MCP client for the AnyMate server inside the Android app.

Speaks JSON-RPC over Streamable HTTP. No SDK required — the app's server is
deliberately simple, and keeping the client dependency-free means the
controller installs anywhere with a Python 3 interpreter.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional

PROTOCOL_VERSION = "2025-06-18"


class McpError(RuntimeError):
    """Raised when the MCP server cannot be reached or returns an error."""


class McpClient:
    """Talks to one MCP endpoint."""

    def __init__(self, url: str, token: str = "", timeout: float = 30.0) -> None:
        self.url = url
        self.token = token
        self.timeout = timeout
        self.session_id: Optional[str] = None
        self.server_info: Dict[str, Any] = {}
        self._request_id = 0

    # ---- plumbing ----------------------------------------------------------
    def _next_id(self) -> int:
        self._request_id += 1
        return self._request_id

    def _headers(self) -> Dict[str, str]:
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        return headers

    def _post(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(self.url, data=body, headers=self._headers(), method="POST")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                session = response.headers.get("Mcp-Session-Id")
                if session:
                    self.session_id = session
                raw = response.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise McpError(f"HTTP {exc.code} from {self.url}: {detail[:400]}") from exc
        except urllib.error.URLError as exc:
            raise McpError(f"Cannot reach {self.url}: {exc.reason}") from exc

        if not raw.strip():
            return {}
        if "data:" in raw and raw.lstrip().startswith(("event:", "data:")):
            chunks = [
                line[len("data:") :].strip()
                for line in raw.splitlines()
                if line.startswith("data:")
            ]
            raw = chunks[-1] if chunks else "{}"
        try:
            return json.loads(raw)
        except ValueError as exc:
            raise McpError(f"Unexpected response from {self.url}: {raw[:200]}") from exc

    def request(self, method: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        payload: Dict[str, Any] = {"jsonrpc": "2.0", "id": self._next_id(), "method": method}
        if params is not None:
            payload["params"] = params
        response = self._post(payload)
        if "error" in response:
            error = response["error"]
            raise McpError(f"{method} failed: {error.get('message', error)}")
        return response.get("result", {})

    def notify(self, method: str, params: Optional[Dict[str, Any]] = None) -> None:
        payload: Dict[str, Any] = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            payload["params"] = params
        try:
            self._post(payload)
        except McpError:
            pass  # notifications never block

    # ---- protocol ----------------------------------------------------------
    def initialize(self) -> Dict[str, Any]:
        result = self.request(
            "initialize",
            {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "anymate-controller", "version": "1.0.0"},
            },
        )
        self.server_info = result.get("serverInfo", {})
        self.notify("notifications/initialized")
        return result

    def list_tools(self) -> List[Dict[str, Any]]:
        result = self.request("tools/list")
        return list(result.get("tools", []))

    def call_tool(self, name: str, arguments: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        return self.request("tools/call", {"name": name, "arguments": arguments or {}})

    def call_tool_text(self, name: str, arguments: Optional[Dict[str, Any]] = None) -> str:
        """Call a tool and flatten its text content."""

        result = self.call_tool(name, arguments)
        parts: List[str] = []
        for content in result.get("content", []):
            if content.get("type") == "text":
                parts.append(content.get("text", ""))
        return "\n".join(parts)

    def call_tool_image(self, name: str, arguments: Optional[Dict[str, Any]] = None) -> Optional[bytes]:
        """Call a tool and return the first image it produced."""

        import base64

        result = self.call_tool(name, arguments)
        for content in result.get("content", []):
            if content.get("type") == "image" and content.get("data"):
                return base64.b64decode(content["data"])
        return None

    def read_resource(self, uri: str) -> Dict[str, Any]:
        return self.request("resources/read", {"uri": uri})

    def list_resources(self) -> List[Dict[str, Any]]:
        return list(self.request("resources/list").get("resources", []))

    def ping(self) -> bool:
        try:
            self.request("ping")
            return True
        except McpError:
            return False

    # ---- convenience -------------------------------------------------------
    def call_batch(self, calls: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Run several tool calls in one request (AnyMate treats it as one undo step)."""

        results: List[Dict[str, Any]] = []
        for call in calls:
            results.append(self.call_tool(call["name"], call.get("args", {})))
        return results

    def snapshot_png(self) -> Optional[bytes]:
        """Fetch the live canvas as PNG bytes via the resource endpoint."""

        try:
            result = self.read_resource("anymate://preview/current")
        except McpError:
            return None
        for content in result.get("contents", []):
            blob = content.get("blob")
            if blob:
                import base64

                return base64.b64decode(blob)
        return None

    def health(self) -> Dict[str, Any]:
        """Call the app's /health endpoint (no MCP handshake needed)."""

        base = self.url.rstrip("/")
        if base.endswith("/mcp"):
            base = base[: -len("/mcp")]
        request = urllib.request.Request(base + "/health", headers=self._headers())
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return json.loads(response.read().decode("utf-8", errors="replace"))
        except Exception as exc:  # noqa: BLE001
            raise McpError(f"Cannot reach the AnyMate health endpoint: {exc}") from exc
