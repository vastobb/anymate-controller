import json

import pytest

from anymate_controller.tools.registry import ToolRegistry, ToolResult


def test_register_and_call():
    registry = ToolRegistry()

    def handler(value: int = 1) -> ToolResult:
        return ToolResult(text=f"value={value}", data={"value": value})

    registry.register("demo", "A demo tool", {"type": "object", "properties": {}}, handler)
    assert "demo" in registry.tools
    assert registry.get("demo").group == "general"
    assert registry.openai_tools()[0]["function"]["name"] == "demo"


def test_call_returns_result():
    registry = ToolRegistry()
    registry.register("echo", "Echo", {}, lambda text="": ToolResult(text=text))
    result = _run(registry.call("echo", {"text": "hello"}))
    assert result.text == "hello"
    assert result.ok


def test_unknown_tool_is_an_error():
    registry = ToolRegistry()
    result = _run(registry.call("nope", {}))
    assert not result.ok
    assert "Unknown tool" in result.text


def test_bad_arguments_do_not_raise():
    registry = ToolRegistry()
    registry.register("boom", "Boom", {}, lambda: ToolResult(text="never"))
    result = _run(registry.call("boom", {"unexpected": 1}))
    assert not result.ok
    assert "Bad arguments" in result.text


def test_handler_exception_is_captured():
    registry = ToolRegistry()

    def explode():
        raise RuntimeError("kaboom")

    registry.register("explode", "Explode", {}, explode)
    result = _run(registry.call("explode", {}))
    assert not result.ok
    assert "kaboom" in result.text


def test_mcp_and_openai_schemas():
    registry = ToolRegistry()
    registry.register("demo", "A demo tool", {"type": "object", "properties": {}}, lambda: ToolResult(text="ok"))
    mcp = registry.mcp_tools()[0]
    assert mcp["name"] == "demo"
    assert mcp["inputSchema"]["type"] == "object"
    openai = registry.openai_tools()[0]
    assert openai["type"] == "function"
    assert openai["function"]["description"] == "A demo tool"
    assert json.dumps(openai)


def _run(coroutine):
    import asyncio

    loop = asyncio.new_event_loop()
    try:
        return loop.run_until_complete(coroutine)
    finally:
        loop.close()
