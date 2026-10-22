"""AnyMate device tools — the controller's hands on the phone.

Every drawing, frame and layer operation is proxied over MCP, so the agent
works directly on the artist's canvas.
"""

from __future__ import annotations

import json
import time
from typing import Any, Dict, List, Optional

from .. import presets
from ..mcp_client import McpClient, McpError
from .registry import ToolRegistry, ToolResult


class AnyMateBridge:
    """Lazily connects to the app and keeps the session alive."""

    def __init__(self, config) -> None:
        self.config = config
        self._client: Optional[McpClient] = None
        self._ready = False

    def client(self) -> McpClient:
        if self._client is None:
            client = McpClient(
                self.config.anymate_url,
                self.config.anymate_token,
                self.config.anymate_timeout,
            )
            client.initialize()
            self._client = client
            self._ready = True
        return self._client

    def connected(self) -> bool:
        try:
            self.client()
            return True
        except McpError:
            self._client = None
            return False

    def reset(self) -> None:
        self._client = None
        self._ready = False


def _as_points(raw: Any) -> List[List[float]]:
    points: List[List[float]] = []
    if not isinstance(raw, (list, tuple)):
        return points
    for item in raw:
        if isinstance(item, (list, tuple)) and len(item) >= 2:
            points.append([float(item[0]), float(item[1])])
        elif isinstance(item, dict) and "x" in item and "y" in item:
            points.append([float(item["x"]), float(item["y"])])
    return points


def register(registry: ToolRegistry, config) -> None:
    """Register the AnyMate device tools."""

    bridge = AnyMateBridge(config)

    def device_call(tool: str, arguments: Optional[Dict[str, Any]] = None) -> ToolResult:
        try:
            text = bridge.client().call_tool_text(tool, arguments or {})
        except McpError as exc:
            bridge.reset()
            return ToolResult(text=f"AnyMate is unreachable: {exc}", ok=False)
        if text.strip().lower().startswith("error"):
            return ToolResult(text=text, ok=False)
        return ToolResult(text=text or f"{tool} done.")

    # ---- status -----------------------------------------------------------
    def anymate_status() -> ToolResult:
        try:
            health = bridge.client().health()
        except McpError as exc:
            return ToolResult(
                text=(
                    f"AnyMate is not reachable at {config.anymate_url}.\n{exc}\n\n"
                    "Start the app, open Settings → MCP server and check the URL and token."
                ),
                ok=False,
            )
        try:
            tools = bridge.client().list_tools()
        except McpError:
            tools = []
        payload = {"health": health, "device_tools": len(tools), "tool_names": [t["name"] for t in tools]}
        return ToolResult(text=json.dumps(payload, indent=2), data=payload)

    registry.register(
        "anymate_status",
        "Check that the AnyMate app is reachable over MCP, and list the tools it exposes.",
        {"type": "object", "properties": {}},
        anymate_status,
        group="anymate",
    )

    def anymate_state() -> ToolResult:
        return device_call("anymate_get_state")

    registry.register(
        "anymate_state",
        "Read the live project state from the phone: canvas size, fps, frames, layers and tool settings.",
        {"type": "object", "properties": {}},
        anymate_state,
        group="anymate",
    )

    def anymate_draw(
        shapes: Optional[List[Dict[str, Any]]] = None,
        strokes: Optional[List[Dict[str, Any]]] = None,
        text: Optional[List[Dict[str, Any]]] = None,
        frame: int = -1,
        layer: int = -1,
    ) -> ToolResult:
        calls: List[Dict[str, Any]] = []
        for shape in shapes or []:
            args = {
                "shape": shape.get("shape", "ellipse"),
                "x1": float(shape.get("x1", 0)),
                "y1": float(shape.get("y1", 0)),
                "x2": float(shape.get("x2", 0)),
                "y2": float(shape.get("y2", 0)),
                "width": float(shape.get("width", 4)),
            }
            if shape.get("fill"):
                args["fill_color"] = shape["fill"]
            if shape.get("stroke"):
                args["stroke_color"] = shape["stroke"]
            if frame >= 0:
                args["frame"] = frame
            if layer >= 0:
                args["layer"] = layer
            calls.append({"name": "anymate_draw_shape", "args": args})
        for stroke in strokes or []:
            args = {
                "points": _as_points(stroke.get("points")),
                "color": stroke.get("color", "#7C3AED"),
                "width": float(stroke.get("width", 4)),
                "tool": stroke.get("tool", "pen"),
            }
            if frame >= 0:
                args["frame"] = frame
            if layer >= 0:
                args["layer"] = layer
            calls.append({"name": "anymate_draw_stroke", "args": args})
        for item in text or []:
            args = {
                "text": item.get("text", ""),
                "x": float(item.get("x", 0)),
                "y": float(item.get("y", 0)),
                "size": float(item.get("size", 64)),
                "color": item.get("color", "#111827"),
            }
            if frame >= 0:
                args["frame"] = frame
            if layer >= 0:
                args["layer"] = layer
            calls.append({"name": "anymate_draw_text", "args": args})
        if not calls:
            return ToolResult(text="Nothing to draw: provide shapes, strokes or text.", ok=False)
        return device_call("anymate_batch", {"calls": calls})

    registry.register(
        "anymate_draw",
        "Draw on the phone: shapes (line/rect/ellipse/arrow), strokes and text. "
        "All shapes, strokes and texts are sent as one undoable batch.",
        {
            "type": "object",
            "properties": {
                "shapes": {
                    "type": "array",
                    "description": "Shapes with shape, x1, y1, x2, y2, fill, stroke, width.",
                    "items": {"type": "object"},
                },
                "strokes": {
                    "type": "array",
                    "description": "Strokes with points [[x,y],...], color, width, tool.",
                    "items": {"type": "object"},
                },
                "text": {
                    "type": "array",
                    "description": "Text blocks with text, x, y, size, color.",
                    "items": {"type": "object"},
                },
                "frame": {"type": "integer", "description": "Target frame index (-1 = current)."},
                "layer": {"type": "integer", "description": "Target layer index (-1 = current)."},
            },
        },
        anymate_draw,
        group="anymate",
    )

    def anymate_frames(
        action: str = "add",
        index: int = -1,
        count: int = 1,
        copy: bool = False,
    ) -> ToolResult:
        if action == "add":
            results = []
            for _ in range(max(1, min(200, count))):
                results.append(device_call("anymate_add_frame", {"index": index if index >= 0 else None, "copy": copy}))
            return ToolResult(text="\n".join(r.text for r in results))
        if action == "duplicate":
            return device_call("anymate_duplicate_frame", {"index": index if index >= 0 else None})
        if action == "delete":
            return device_call("anymate_delete_frame", {"index": index if index >= 0 else None})
        if action == "reorder":
            return device_call("anymate_reorder_frame", {"from": index, "to": count})
        if action == "clear":
            return device_call("anymate_clear_frame", {"index": index if index >= 0 else None})
        if action == "list":
            return device_call("anymate_list_frames", {})
        return ToolResult(text=f"Unknown action '{action}'.", ok=False)

    registry.register(
        "anymate_frames",
        "Manage the timeline: add, duplicate, delete, reorder, clear or list frames.",
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["add", "duplicate", "delete", "reorder", "clear", "list"],
                },
                "index": {"type": "integer", "description": "Frame index."},
                "count": {"type": "integer", "description": "How many frames to add / destination index."},
                "copy": {"type": "boolean", "description": "Copy the current frame when adding."},
            },
            "required": ["action"],
        },
        anymate_frames,
        group="anymate",
    )

    def anymate_playback(action: str = "play", index: int = 0, fps: int = 0, loop: bool = True) -> ToolResult:
        if action == "play":
            return device_call("anymate_play", {})
        if action == "pause":
            return device_call("anymate_pause", {})
        if action == "seek":
            return device_call("anymate_seek", {"index": index})
        if action == "fps":
            return device_call("anymate_set_fps", {"fps": fps or 24})
        if action == "loop":
            return device_call("anymate_set_loop", {"enabled": loop})
        if action == "onion":
            return device_call("anymate_set_onion_skin", {"enabled": loop})
        return ToolResult(text=f"Unknown action '{action}'.", ok=False)

    registry.register(
        "anymate_playback",
        "Control playback on the phone: play, pause, seek, set fps, toggle loop or onion skin.",
        {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["play", "pause", "seek", "fps", "loop", "onion"]},
                "index": {"type": "integer", "description": "Frame to seek to."},
                "fps": {"type": "integer", "description": "Frames per second."},
                "loop": {"type": "boolean", "description": "Enable loop / onion skin."},
            },
            "required": ["action"],
        },
        anymate_playback,
        group="anymate",
    )

    def anymate_layers(action: str = "list", index: int = -1, name: str = "", visible: bool = True, opacity: float = 1.0) -> ToolResult:
        if action == "list":
            return device_call("anymate_get_state", {})
        if action == "add":
            return device_call("anymate_add_layer", {"name": name} if name else {})
        if action == "delete":
            return device_call("anymate_delete_layer", {"index": index if index >= 0 else None})
        if action == "set":
            args: Dict[str, Any] = {"index": index if index >= 0 else None, "visible": visible, "opacity": opacity}
            if name:
                args["name"] = name
            return device_call("anymate_set_layer", args)
        if action == "merge":
            return device_call("anymate_merge_layers", {"frame": index if index >= 0 else None})
        return ToolResult(text=f"Unknown action '{action}'.", ok=False)

    registry.register(
        "anymate_layers",
        "Manage layers on the phone: list, add, delete, set visibility/name/opacity, or merge layers.",
        {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["list", "add", "delete", "set", "merge"]},
                "index": {"type": "integer", "description": "Layer index."},
                "name": {"type": "string", "description": "Layer name."},
                "visible": {"type": "boolean", "description": "Layer visibility."},
                "opacity": {"type": "number", "description": "Layer opacity 0..1."},
            },
            "required": ["action"],
        },
        anymate_layers,
        group="anymate",
    )

    def anymate_style(color: str = "", tool: str = "", size: float = 0, opacity: float = 0, shape: str = "") -> ToolResult:
        calls: List[Dict[str, Any]] = []
        if color:
            calls.append({"name": "anymate_set_color", "args": {"color": color}})
        if tool:
            args: Dict[str, Any] = {"tool": tool}
            if size:
                args["size"] = size
            if opacity:
                args["opacity"] = opacity
            if shape:
                args["shape"] = shape
            calls.append({"name": "anymate_set_tool", "args": args})
        if not calls:
            return ToolResult(text="Provide a color or a tool.", ok=False)
        return device_call("anymate_batch", {"calls": calls})

    registry.register(
        "anymate_style",
        "Set the active colour, tool, brush size or shape on the phone.",
        {
            "type": "object",
            "properties": {
                "color": {"type": "string", "description": "Hex colour."},
                "tool": {"type": "string", "description": "pen, pencil, brush, eraser, shape…"},
                "size": {"type": "number", "description": "Brush size in pixels."},
                "opacity": {"type": "number", "description": "Brush opacity."},
                "shape": {"type": "string", "description": "line, rect, ellipse, arrow."},
            },
        },
        anymate_style,
        group="anymate",
    )

    def anymate_snapshot(frame: int = -1, max_size: int = 768) -> ToolResult:
        try:
            args: Dict[str, Any] = {"max_size": max_size}
            if frame >= 0:
                args["frame"] = frame
            image = bridge.client().call_tool_image("anymate_snapshot", args)
        except McpError as exc:
            bridge.reset()
            return ToolResult(text=f"AnyMate is unreachable: {exc}", ok=False)
        if not image:
            return ToolResult(text="The phone did not return an image.", ok=False)
        return ToolResult(
            text=f"Snapshot of frame {frame if frame >= 0 else 'current'} ({len(image)} bytes).",
            image=image,
        )

    registry.register(
        "anymate_snapshot",
        "Take a PNG snapshot of the phone's canvas so you can see exactly what the artist sees.",
        {
            "type": "object",
            "properties": {
                "frame": {"type": "integer", "description": "Frame index; -1 = current."},
                "max_size": {"type": "integer", "description": "Longest edge in pixels."},
            },
        },
        anymate_snapshot,
        group="anymate",
    )

    def anymate_sprite_sheet(columns: int = 6, cell_size: int = 180) -> ToolResult:
        try:
            image = bridge.client().call_tool_image(
                "anymate_render_sprite_sheet", {"columns": columns, "cell_size": cell_size}
            )
        except McpError as exc:
            bridge.reset()
            return ToolResult(text=f"AnyMate is unreachable: {exc}", ok=False)
        if not image:
            return ToolResult(text="The phone did not return a sprite sheet.", ok=False)
        return ToolResult(text=f"Sprite sheet ({len(image)} bytes).", image=image)

    registry.register(
        "anymate_sprite_sheet",
        "Render every frame on the phone into one contact sheet image — the fastest way to review an animation.",
        {
            "type": "object",
            "properties": {
                "columns": {"type": "integer", "description": "Frames per row."},
                "cell_size": {"type": "integer", "description": "Cell size in pixels."},
            },
        },
        anymate_sprite_sheet,
        group="anymate",
    )

    def anymate_export(kind: str = "gif", max_size: int = 720) -> ToolResult:
        tool = {"gif": "anymate_export_gif", "png": "anymate_export_png", "zip": "anymate_export_frames"}[kind]
        args = {"max_size": max_size} if kind == "gif" else {}
        return device_call(tool, args)

    registry.register(
        "anymate_export",
        "Export the animation on the phone as a GIF, a PNG or a PNG sequence ZIP.",
        {
            "type": "object",
            "properties": {
                "kind": {"type": "string", "enum": ["gif", "png", "zip"]},
                "max_size": {"type": "integer", "description": "GIF size in pixels."},
            },
        },
        anymate_export,
        group="anymate",
    )

    # ---- the motion director ---------------------------------------------
    def anymate_motion_preset(
        preset: str = "bounce",
        frames: int = 48,
        fps: int = 24,
        palette: Optional[List[str]] = None,
        message: str = "ANYMATE",
        pace: bool = True,
        chunk: int = 6,
        delay: float = 0.12,
    ) -> ToolResult:
        """Generate a complete animation on the phone, frame by frame, live."""

        try:
            client = bridge.client()
        except McpError as exc:
            return ToolResult(text=f"AnyMate is unreachable: {exc}", ok=False)
        state: Dict[str, Any] = {}
        try:
            raw_state = client.call_tool_text("anymate_get_state")
            brace = raw_state.find("{")
            if brace >= 0:
                state = json.loads(raw_state[brace:])
        except (McpError, ValueError):
            state = {}
        width = float(state.get("width", 900) or 900)
        height = float(state.get("height", 900) or 900)
        try:
            built = presets.build(
                preset,
                width,
                height,
                max(4, min(400, frames)),
                palette or (),
                message,
            )
        except KeyError as exc:
            return ToolResult(text=str(exc), ok=False)

        calls: List[Dict[str, Any]] = [{"name": "anymate_set_fps", "args": {"fps": fps}}]
        for frame in built:
            calls.append({"name": "anymate_add_frame", "args": {"copy": False}})
            for call in frame:
                calls.append(call)

        sent = 0
        errors: List[str] = []
        step = max(1, chunk)
        for start in range(0, len(calls), step):
            piece = calls[start : start + step]
            try:
                client.call_tool("anymate_batch", {"calls": piece})
            except McpError as exc:
                errors.append(str(exc))
                break
            sent += len(piece)
            if pace and start + step < len(calls):
                time.sleep(delay)
        try:
            client.call_tool("anymate_play", {})
        except McpError:
            pass
        summary = {
            "preset": preset,
            "frames": len(built),
            "fps": fps,
            "calls": sent,
            "canvas": [width, height],
        }
        text = (
            f"Built '{preset}' — {len(built)} frames at {fps} fps on the phone "
            f"({sent} tool calls). Playback is running on the canvas."
        )
        if errors:
            text += "\nErrors: " + "; ".join(errors[:3])
        return ToolResult(text=text, data=summary, ok=not errors)

    registry.register(
        "anymate_motion_preset",
        "Generate a complete looping animation on the phone using a built-in motion system. "
        "Presets: bounce, walk, spin, pulse, fireworks, wave, orbit, kinetic-type. "
        "The frames are painted live so the artist watches the animation build.",
        {
            "type": "object",
            "properties": {
                "preset": {
                    "type": "string",
                    "enum": sorted(presets.PRESETS.keys()),
                    "description": "Motion system to use.",
                },
                "frames": {"type": "integer", "description": "How many frames to create."},
                "fps": {"type": "integer", "description": "Playback frame rate."},
                "palette": {"type": "array", "items": {"type": "string"}, "description": "Hex colours."},
                "message": {"type": "string", "description": "Text for the kinetic-type preset."},
                "pace": {"type": "boolean", "description": "Paint in chunks so the artist sees it build."},
                "chunk": {"type": "integer", "description": "Tool calls per batch."},
                "delay": {"type": "number", "description": "Seconds between batches."},
            },
            "required": ["preset"],
        },
        anymate_motion_preset,
        group="anymate",
    )
