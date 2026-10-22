"""Tool registry shared by the MCP server, the AI agent and the web UI."""

from __future__ import annotations

import inspect
import json
import traceback
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional


@dataclass
class ToolResult:
    """What a tool returns."""

    text: str = ""
    data: Optional[Dict[str, Any]] = None
    image: Optional[bytes] = None
    mime: str = "image/png"
    ok: bool = True

    def as_dict(self) -> Dict[str, Any]:
        payload: Dict[str, Any] = {"text": self.text, "ok": self.ok}
        if self.data is not None:
            payload["data"] = self.data
        return payload

    def to_json(self) -> str:
        return json.dumps(self.as_dict(), ensure_ascii=False)


@dataclass
class Tool:
    """A single callable exposed to models and MCP clients."""

    name: str
    description: str
    schema: Dict[str, Any]
    handler: Callable[..., Any]
    group: str = "general"
    dangerous: bool = False

    def openai_schema(self) -> Dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.schema,
            },
        }


class ToolRegistry:
    """Holds every tool the controller can run."""

    def __init__(self) -> None:
        self._tools: Dict[str, Tool] = {}

    # ---- registration ------------------------------------------------------
    def register(
        self,
        name: str,
        description: str,
        schema: Dict[str, Any],
        handler: Callable[..., Any],
        group: str = "general",
        dangerous: bool = False,
    ) -> Tool:
        tool = Tool(
            name=name,
            description=description,
            schema=schema,
            handler=handler,
            group=group,
            dangerous=dangerous,
        )
        self._tools[name] = tool
        return tool

    def tool(
        self,
        name: str,
        description: str,
        schema: Optional[Dict[str, Any]] = None,
        group: str = "general",
        dangerous: bool = False,
    ):
        """Decorator form of :meth:`register`."""

        def decorator(func: Callable[..., Any]) -> Callable[..., Any]:
            self.register(
                name=name,
                description=description,
                schema=schema or {"type": "object", "properties": {}},
                handler=func,
                group=group,
                dangerous=dangerous,
            )
            return func

        return decorator

    # ---- access ------------------------------------------------------------
    @property
    def tools(self) -> Dict[str, Tool]:
        return self._tools

    def get(self, name: str) -> Optional[Tool]:
        return self._tools.get(name)

    def groups(self) -> Dict[str, List[Tool]]:
        grouped: Dict[str, List[Tool]] = {}
        for tool in self._tools.values():
            grouped.setdefault(tool.group, []).append(tool)
        return grouped

    def openai_tools(self) -> List[Dict[str, Any]]:
        return [tool.openai_schema() for tool in self._tools.values()]

    def mcp_tools(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": tool.name,
                "description": tool.description,
                "inputSchema": tool.schema,
            }
            for tool in self._tools.values()
        ]

    # ---- execution ---------------------------------------------------------
    async def call(self, name: str, arguments: Optional[Dict[str, Any]] = None) -> ToolResult:
        tool = self.get(name)
        if tool is None:
            return ToolResult(
                text=f"Unknown tool: {name}. Call tools/list to see every tool.",
                ok=False,
            )
        kwargs = dict(arguments or {})
        try:
            outcome = tool.handler(**kwargs)
            if inspect.isawaitable(outcome):
                outcome = await outcome
            if isinstance(outcome, ToolResult):
                return outcome
            if isinstance(outcome, str):
                return ToolResult(text=outcome)
            return ToolResult(text=json.dumps(outcome, ensure_ascii=False, default=str))
        except TypeError as exc:
            return ToolResult(
                text=f"Bad arguments for {name}: {exc}. Expected {json.dumps(tool.schema)}",
                ok=False,
            )
        except Exception as exc:  # noqa: BLE001 - tools must never crash the server
            detail = traceback.format_exc(limit=3)
            return ToolResult(text=f"{name} failed: {exc}\n{detail}", ok=False)
