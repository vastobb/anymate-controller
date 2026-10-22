"""Command line entry point for anymate-controller."""

from __future__ import annotations

import argparse
import asyncio
import json
import shutil
import sys
from typing import List

from . import __version__
from .agent import Agent
from .config import Config, load_config
from .mcp_client import McpClient, McpError
from .mcp_server import MCPServer
from .tools import anymate_tools, draw_tools, ffmpeg_tools, shell_tools
from .tools.registry import ToolRegistry
from .webapp import WebApp


def build_registry(config: Config) -> ToolRegistry:
    """Create the full toolbox."""

    registry = ToolRegistry()
    shell_tools.register(registry, config)
    ffmpeg_tools.register(registry, config)
    draw_tools.register(registry, config)
    anymate_tools.register(registry, config)
    return registry


def resources_for(config: Config) -> List[dict]:
    return [
        {
            "uri": "anymate://preview/current",
            "name": "AnyMate canvas preview",
            "description": "Live PNG snapshot of the phone's canvas.",
            "mimeType": "image/png",
        },
        {
            "uri": "anymate://status",
            "name": "AnyMate status",
            "description": "Connection health for the phone.",
            "mimeType": "application/json",
        },
    ]


# ---- commands ---------------------------------------------------------------


def cmd_serve(args: argparse.Namespace) -> int:
    config = load_config()
    registry = build_registry(config)
    app = WebApp(config, registry)
    server = MCPServer(registry, resources_for(config))
    if args.mcp_port:
        server.serve_http(host=config.host, port=args.mcp_port)
        print(f"MCP (HTTP) → http://{config.host}:{args.mcp_port}/mcp")
    app.serve(host=args.host, port=args.port)
    return 0


def cmd_mcp(args: argparse.Namespace) -> int:
    config = load_config()
    registry = build_registry(config)
    server = MCPServer(registry, resources_for(config))
    if args.http:
        httpd = server.serve_http(host=config.host, port=args.port)
        print(f"MCP (HTTP) → http://{config.host}:{args.port}/mcp")
        try:
            while True:
                httpd._BaseServer__is_shut_down.wait(1)  # type: ignore[attr-defined]
        except KeyboardInterrupt:
            pass
        return 0
    server.serve_stdio()
    return 0


def cmd_chat(args: argparse.Namespace) -> int:
    config = load_config()
    registry = build_registry(config)
    agent = Agent(config, registry)

    def on_event(event: dict) -> None:
        if event.get("type") == "tool":
            print(f"  → {event.get('name')}", file=sys.stderr)

    reply = asyncio.run(agent.run(args.prompt, on_event=on_event))
    if reply.error:
        print(f"error: {reply.error}", file=sys.stderr)
        return 1
    print(reply.text)
    if reply.tool_calls:
        print(f"\n({len(reply.tool_calls)} tool calls)", file=sys.stderr)
    return 0


def cmd_doctor(args: argparse.Namespace) -> int:
    config = load_config()
    registry = build_registry(config)
    print(f"anymate-controller {__version__}")
    print(f"workspace      : {config.workspace_path}")
    print(f"python         : {config.python}")
    print(f"ffmpeg         : {shutil.which(config.ffmpeg_binary) or 'not found'}")
    print(f"ffprobe        : {shutil.which(config.ffprobe_binary) or 'not found'}")
    print(f"model          : {config.model} @ {config.base_url}")
    print(f"key configured : {'yes' if config.api_key else 'no'}")
    print(f"tools          : {len(registry.tools)}")
    for group, items in registry.groups().items():
        print(f"  {group:10s} {', '.join(t.name for t in items)}")
    try:
        client = McpClient(config.anymate_url, config.anymate_token, config.anymate_timeout)
        health = client.health()
        print(f"anymate        : reachable → {json.dumps(health)}")
    except McpError as exc:
        print(f"anymate        : unreachable → {exc}")
    return 0


def cmd_config(args: argparse.Namespace) -> int:
    config = load_config()
    if args.set:
        key, _, value = args.set.partition("=")
        if not hasattr(config, key):
            print(f"Unknown setting '{key}'.", file=sys.stderr)
            return 1
        current = getattr(config, key)
        if isinstance(current, bool):
            value_value = value.lower() in ("1", "true", "yes", "on")
        elif isinstance(current, int):
            value_value = int(value)
        elif isinstance(current, float):
            value_value = float(value)
        else:
            value_value = value
        setattr(config, key, value_value)
        path = config.save()
        print(f"Saved {key} = {value_value} → {path}")
        return 0
    print(json.dumps(config.to_dict(), indent=2))
    return 0


def cmd_tools(args: argparse.Namespace) -> int:
    config = load_config()
    registry = build_registry(config)
    if args.group:
        items = registry.groups().get(args.group, [])
    else:
        items = list(registry.tools.values())
    for tool in items:
        print(f"{tool.name:26s} [{tool.group}] {tool.description[:90]}")
    return 0


def cmd_run_tool(args: argparse.Namespace) -> int:
    config = load_config()
    registry = build_registry(config)
    try:
        arguments = json.loads(args.json) if args.json else {}
    except ValueError as exc:
        print(f"Invalid JSON: {exc}", file=sys.stderr)
        return 1
    loop = asyncio.new_event_loop()
    try:
        result = loop.run_until_complete(registry.call(args.name, arguments))
    finally:
        loop.close()
    print(result.text)
    return 0 if result.ok else 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="anymate-controller",
        description="AI agent, ffmpeg, python and terminal tooling for the AnyMate studio.",
    )
    parser.add_argument("--version", action="version", version=f"anymate-controller {__version__}")
    sub = parser.add_subparsers(dest="command", required=True)

    serve = sub.add_parser("serve", help="Start the web UI (and optionally the HTTP MCP server).")
    serve.add_argument("--host", default=None)
    serve.add_argument("--port", type=int, default=None)
    serve.add_argument("--mcp-port", type=int, default=0, help="Also serve MCP over HTTP on this port.")
    serve.set_defaults(func=cmd_serve)

    mcp = sub.add_parser("mcp", help="Run the MCP server (stdio by default).")
    mcp.add_argument("--http", action="store_true", help="Serve over HTTP instead of stdio.")
    mcp.add_argument("--host", default="127.0.0.1")
    mcp.add_argument("--port", type=int, default=8788)
    mcp.set_defaults(func=cmd_mcp)

    chat = sub.add_parser("chat", help="Send one prompt to the AI agent.")
    chat.add_argument("prompt", help="What should the agent do?")
    chat.set_defaults(func=cmd_chat)

    doctor = sub.add_parser("doctor", help="Check ffmpeg, python and the phone connection.")
    doctor.set_defaults(func=cmd_doctor)

    config = sub.add_parser("config", help="Show or update the configuration.")
    config.add_argument("--set", help="key=value")
    config.set_defaults(func=cmd_config)

    tools = sub.add_parser("tools", help="List every available tool.")
    tools.add_argument("--group", default="")
    tools.set_defaults(func=cmd_tools)

    run_tool = sub.add_parser("call", help="Run one tool directly.")
    run_tool.add_argument("name")
    run_tool.add_argument("--json", default="{}", help="Arguments as JSON.")
    run_tool.set_defaults(func=cmd_run_tool)

    return parser


def main(argv: List[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return int(args.func(args) or 0)


if __name__ == "__main__":
    raise SystemExit(main())
