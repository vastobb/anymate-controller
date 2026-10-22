"""Drawing and animation design tools that run entirely in Python.

Palettes, easing curves, timing plans, sprite sheets, PNG generators and SVG
helpers — the parts of an animation pipeline that do not need ffmpeg.
"""

from __future__ import annotations

import colorsys
import json
import math
from pathlib import Path
from typing import Any, Dict, List, Sequence, Tuple

from .pngio import Image, read_png, write_png
from .registry import ToolRegistry, ToolResult
from .workspace import WorkspaceError, ensure_parent, resolve

EASINGS = {
    "linear": lambda t: t,
    "ease-in": lambda t: t * t,
    "ease-out": lambda t: 1 - (1 - t) ** 2,
    "ease-in-out": lambda t: t * t * (3 - 2 * t),
    "sine": lambda t: 0.5 - 0.5 * math.cos(math.pi * t),
    "expo-in": lambda t: 0.0 if t == 0 else 2 ** (10 * (t - 1)),
    "expo-out": lambda t: 1.0 if t == 1 else 1 - 2 ** (-10 * t),
    "back-out": lambda t: 1 + 2.70158 * (t - 1) ** 3 + 1.70158 * (t - 1) ** 2,
    "bounce": lambda t: _bounce(t),
    "elastic-out": lambda t: _elastic(t),
}


def _bounce(t: float) -> float:
    n, d = 7.5625, 2.75
    if t < 1 / d:
        return n * t * t
    if t < 2 / d:
        t -= 1.5 / d
        return n * t * t + 0.75
    if t < 2.5 / d:
        t -= 2.25 / d
        return n * t * t + 0.9375
    t -= 2.625 / d
    return n * t * t + 0.984375


def _elastic(t: float) -> float:
    if t in (0.0, 1.0):
        return t
    return 2 ** (-10 * t) * math.sin((t * 10 - 0.75) * (2 * math.pi / 3)) + 1


def hex_to_rgb(value: str) -> Tuple[int, int, int]:
    text = value.strip().lstrip("#")
    if len(text) == 3:
        text = "".join(ch * 2 for ch in text)
    if len(text) == 6:
        text += "ff"
    if len(text) != 8:
        raise ValueError(f"'{value}' is not a hex colour.")
    return tuple(int(text[i : i + 2], 16) for i in (0, 2, 4, 6))  # type: ignore[return-value]


def rgb_to_hex(rgb: Sequence[float], floats: bool = False) -> str:
    """Format an RGB triple as #RRGGBB. Pass floats=True for 0..1 values."""

    parts = list(rgb[:3])
    if floats:
        parts = [p * 255.0 for p in parts]
    r, g, b = (max(0, min(255, int(round(c)))) for c in parts)
    return f"#{r:02X}{g:02X}{b:02X}"


def _harmony(base: str, scheme: str, count: int) -> List[str]:
    r, g, b = hex_to_rgb(base)[:3]
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    colors: List[str] = []
    if scheme == "complementary":
        offsets = [0.0, 0.5, 0.52, 0.48, 0.0, 0.5]
    elif scheme == "triadic":
        offsets = [0.0, 1 / 3, 2 / 3, 0.0, 1 / 3, 2 / 3]
    elif scheme == "analogous":
        offsets = [0.0, 0.08, -0.08, 0.16, -0.16, 0.0]
    elif scheme == "monochrome":
        offsets = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0]
    else:  # split complementary
        offsets = [0.0, 0.42, 0.58, 0.05, 0.42, 0.58]
    for index in range(count):
        hue = (h + offsets[index % len(offsets)]) % 1.0
        if index == 0:
            light, sat = l, s
        else:
            light = max(0.10, min(0.90, l + (0.10 if index % 3 == 1 else (-0.08 if index % 3 == 2 else 0.0))))
            sat = max(0.08, min(s, s + (0.04 if index % 3 == 2 else 0.0)))
        colors.append(rgb_to_hex(colorsys.hls_to_rgb(hue, light, sat), floats=True))
    return colors


def register(registry: ToolRegistry, config) -> None:
    """Register the drawing and animation design tools."""

    workspace = config.workspace_path

    # ---- palette ---------------------------------------------------------
    def anim_palette(
        base: str = "#7C3AED",
        scheme: str = "split",
        count: int = 6,
        background: str = "#F4F5F7",
    ) -> ToolResult:
        try:
            colors = _harmony(base, scheme.lower(), max(2, min(12, count)))
        except ValueError as exc:
            return ToolResult(text=str(exc), ok=False)
        payload = {"base": base, "scheme": scheme, "background": background, "colors": colors}
        return ToolResult(
            text=json.dumps(payload, indent=2),
            data=payload,
        )

    registry.register(
        "anim_palette",
        "Build a harmonious colour palette from a base colour. Schemes: split, complementary, "
        "triadic, analogous, monochrome. Also returns a matching background colour.",
        {
            "type": "object",
            "properties": {
                "base": {"type": "string", "description": "Base colour as #RRGGBB."},
                "scheme": {
                    "type": "string",
                    "enum": ["split", "complementary", "triadic", "analogous", "monochrome"],
                },
                "count": {"type": "integer", "description": "How many colours to return."},
                "background": {"type": "string", "description": "Background colour for the scene."},
            },
        },
        anim_palette,
        group="animation",
    )

    def anim_palette_from_image(path: str, count: int = 6) -> ToolResult:
        try:
            target = resolve(workspace, path)
        except WorkspaceError as exc:
            return ToolResult(text=str(exc), ok=False)
        if not target.exists():
            return ToolResult(text=f"'{path}' does not exist.", ok=False)
        try:
            image = read_png(target)
        except Exception as exc:  # noqa: BLE001
            return ToolResult(text=f"Could not read the PNG: {exc}", ok=False)
        buckets: Dict[Tuple[int, int, int], int] = {}
        step = max(1, min(image.width, image.height) // 120)
        for y in range(0, image.height, step):
            for x in range(0, image.width, step):
                r, g, b, a = image.pixel(x, y)
                if a < 32:
                    continue
                key = ((r // 32) * 32, (g // 32) * 32, (b // 32) * 32)
                buckets[key] = buckets.get(key, 0) + 1
        ordered = sorted(buckets.items(), key=lambda item: item[1], reverse=True)[: max(2, count)]
        colors = [rgb_to_hex(key) for key, _ in ordered]
        payload = {"file": str(target), "colors": colors}
        return ToolResult(text=json.dumps(payload, indent=2), data=payload)

    registry.register(
        "anim_palette_from_image",
        "Extract the dominant colours from a PNG so an agent can match the art style.",
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "PNG inside the workspace."},
                "count": {"type": "integer", "description": "How many colours to return."},
            },
            "required": ["path"],
        },
        anim_palette_from_image,
        group="animation",
    )

    # ---- timing ----------------------------------------------------------
    def anim_easing(easing: str = "ease-in-out", count: int = 12) -> ToolResult:
        curve = EASINGS.get(easing.lower())
        if curve is None:
            return ToolResult(
                text=f"Unknown easing '{easing}'. Known curves: {', '.join(sorted(EASINGS))}.",
                ok=False,
            )
        values = [round(curve(i / max(1, count - 1)), 4) for i in range(max(2, min(120, count)))]
        payload = {"easing": easing, "values": values}
        return ToolResult(text=json.dumps(payload), data=payload)

    registry.register(
        "anim_easing",
        "Sample an easing curve. Use the values to place keyframes or to time a move.",
        {
            "type": "object",
            "properties": {
                "easing": {"type": "string", "description": "Curve name."},
                "count": {"type": "integer", "description": "How many samples."},
            },
        },
        anim_easing,
        group="animation",
    )

    def anim_timing(
        fps: int = 24,
        seconds: float = 2.0,
        style: str = "on-twos",
        hold_frames: int = 2,
    ) -> ToolResult:
        total = max(1, int(round(fps * max(0.1, seconds))))
        per_second = max(1, int(fps))
        drawings = max(1, total // max(1, hold_frames if style == "on-twos" else 1))
        payload = {
            "fps": fps,
            "seconds": seconds,
            "total_frames": total,
            "style": style,
            "unique_drawings": drawings,
            "holds_per_drawing": hold_frames if style == "on-twos" else 1,
            "frame_duration_ms": round(1000 / max(1, fps), 3),
        }
        return ToolResult(text=json.dumps(payload, indent=2), data=payload)

    registry.register(
        "anim_timing",
        "Turn a duration into a frame plan: total frames, unique drawings and holds "
        "(on-ones, on-twos, on-threes).",
        {
            "type": "object",
            "properties": {
                "fps": {"type": "integer", "description": "Frames per second."},
                "seconds": {"type": "number", "description": "Length of the shot."},
                "style": {"type": "string", "enum": ["on-ones", "on-twos", "on-threes"]},
                "hold_frames": {"type": "integer", "description": "Frames to hold each drawing."},
            },
        },
        anim_timing,
        group="animation",
    )

    def anim_storyboard(
        beats: str = "",
        fps: int = 12,
        style: str = "simple",
    ) -> ToolResult:
        lines = [line.strip() for line in beats.splitlines() if line.strip()]
        if not lines:
            lines = ["Establishing wide", "Subject enters", "Action peaks", "Resolve"]
        frames_per_beat = max(1, int(fps))
        plan = []
        for index, beat in enumerate(lines):
            plan.append(
                {
                    "beat": index + 1,
                    "description": beat,
                    "frames": frames_per_beat,
                    "frame_range": f"{index * frames_per_beat}-{(index + 1) * frames_per_beat - 1}",
                }
            )
        payload = {"fps": fps, "style": style, "beats": plan}
        return ToolResult(text=json.dumps(payload, indent=2), data=payload)

    registry.register(
        "anim_storyboard",
        "Turn a list of beats into a frame-by-frame storyboard plan.",
        {
            "type": "object",
            "properties": {
                "beats": {"type": "string", "description": "One beat per line."},
                "fps": {"type": "integer", "description": "Frames per second for the plan."},
                "style": {"type": "string", "description": "Visual style hint."},
            },
        },
        anim_storyboard,
        group="animation",
    )

    # ---- raster ----------------------------------------------------------
    def anim_sprite_sheet(
        frame_paths: List[str],
        output_path: str = "sheet.png",
        columns: int = 0,
        cell_width: int = 0,
        cell_height: int = 0,
    ) -> ToolResult:
        if not frame_paths:
            return ToolResult(text="Provide the frame images to pack.", ok=False)
        images: List[Image] = []
        for raw in frame_paths:
            try:
                path = resolve(workspace, raw)
            except WorkspaceError as exc:
                return ToolResult(text=str(exc), ok=False)
            if not path.exists():
                return ToolResult(text=f"'{raw}' does not exist.", ok=False)
            try:
                images.append(read_png(path))
            except Exception as exc:  # noqa: BLE001
                return ToolResult(text=f"Could not read '{raw}': {exc}", ok=False)
        if not images:
            return ToolResult(text="No frames to pack.", ok=False)
        cell_w = cell_width or max(image.width for image in images)
        cell_h = cell_height or max(image.height for image in images)
        cols = columns or int(math.ceil(math.sqrt(len(images))))
        rows = int(math.ceil(len(images) / cols))
        sheet = Image(cols * cell_w, rows * cell_h, bytearray(cols * cell_w * rows * cell_h * 4))
        for index, image in enumerate(images):
            column = index % cols
            row = index // cols
            for y in range(image.height):
                for x in range(image.width):
                    sheet.set_pixel(
                        column * cell_w + x,
                        row * cell_h + y,
                        image.pixel(x, y),
                    )
        target = resolve(workspace, output_path)
        ensure_parent(target)
        write_png(target, sheet)
        payload = {
            "output": str(target),
            "frames": len(images),
            "columns": cols,
            "rows": rows,
            "cell": [cell_w, cell_h],
            "size": [sheet.width, sheet.height],
        }
        return ToolResult(text=json.dumps(payload, indent=2), data=payload)

    registry.register(
        "anim_sprite_sheet",
        "Pack PNG frames into a sprite sheet. Pure Python — no Pillow required.",
        {
            "type": "object",
            "properties": {
                "frame_paths": {"type": "array", "items": {"type": "string"}, "description": "Frame images."},
                "output_path": {"type": "string", "description": "Destination PNG."},
                "columns": {"type": "integer", "description": "Columns in the sheet."},
                "cell_width": {"type": "integer", "description": "Cell width in pixels."},
                "cell_height": {"type": "integer", "description": "Cell height in pixels."},
            },
            "required": ["frame_paths", "output_path"],
        },
        anim_sprite_sheet,
        group="animation",
    )

    def anim_png(
        output_path: str = "frame.png",
        width: int = 900,
        height: int = 900,
        color: str = "#F4F5F7",
        accent: str = "#7C3AED",
        shape: str = "ellipse",
    ) -> ToolResult:
        try:
            base = hex_to_rgb(color)[:3]
            accent_rgb = hex_to_rgb(accent)[:3]
        except ValueError as exc:
            return ToolResult(text=str(exc), ok=False)
        width = max(8, min(2048, width))
        height = max(8, min(2048, height))
        image = Image(width, height, bytearray(width * height * 4))
        cx, cy = width / 2, height / 2
        radius = min(width, height) * 0.28
        for y in range(height):
            for x in range(width):
                r, g, b = base
                if shape == "ellipse":
                    if ((x - cx) ** 2) / (radius * radius) + ((y - cy) ** 2) / (radius * radius) <= 1:
                        r, g, b = accent_rgb
                elif shape == "gradient":
                    t = x / max(1, width - 1)
                    r = int(base[0] * (1 - t) + accent_rgb[0] * t)
                    g = int(base[1] * (1 - t) + accent_rgb[1] * t)
                    b = int(base[2] * (1 - t) + accent_rgb[2] * t)
                image.set_pixel(x, y, (r, g, b, 255))
        target = resolve(workspace, output_path)
        ensure_parent(target)
        write_png(target, image)
        payload = {"output": str(target), "size": [width, height], "shape": shape}
        return ToolResult(text=json.dumps(payload, indent=2), data=payload)

    registry.register(
        "anim_png",
        "Generate a simple PNG frame (flat colour, ellipse or gradient) — handy for "
        "backgrounds, test cards and placeholder art.",
        {
            "type": "object",
            "properties": {
                "output_path": {"type": "string", "description": "Destination PNG."},
                "width": {"type": "integer", "description": "Width in pixels."},
                "height": {"type": "integer", "description": "Height in pixels."},
                "color": {"type": "string", "description": "Background colour."},
                "accent": {"type": "string", "description": "Accent colour."},
                "shape": {"type": "string", "enum": ["flat", "ellipse", "gradient"]},
            },
        },
        anim_png,
        group="animation",
    )

    def anim_svg(
        shape: str = "circle",
        size: int = 400,
        color: str = "#7C3AED",
        background: str = "none",
        stroke_width: int = 0,
    ) -> ToolResult:
        size = max(16, min(4096, size))
        stroke = f' stroke="{color}" stroke-width="{stroke_width}"' if stroke_width else ""
        fill = color if stroke_width else "none"
        if shape == "square":
            body = f'<rect x="{size * 0.15:.0f}" y="{size * 0.15:.0f}" width="{size * 0.7:.0f}" height="{size * 0.7:.0f}" rx="{size * 0.06:.0f}" fill="{fill}"{stroke}/>'
        elif shape == "triangle":
            body = (
                f'<polygon points="{size * 0.5:.0f},{size * 0.15:.0f} {size * 0.85:.0f},{size * 0.82:.0f} '
                f'{size * 0.15:.0f},{size * 0.82:.0f}" fill="{fill}"{stroke}/>'
            )
        else:
            body = f'<circle cx="{size / 2:.0f}" cy="{size / 2:.0f}" r="{size * 0.35:.0f}" fill="{fill}"{stroke}/>'
        svg = (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
            f'viewBox="0 0 {size} {size}"><rect width="{size}" height="{size}" fill="{background}"/>{body}</svg>'
        )
        return ToolResult(text=svg, data={"svg": svg, "shape": shape, "size": size})

    registry.register(
        "anim_svg",
        "Produce a small SVG for a shape — useful as a reference or as vector source art.",
        {
            "type": "object",
            "properties": {
                "shape": {"type": "string", "enum": ["circle", "square", "triangle"]},
                "size": {"type": "integer", "description": "Canvas size in pixels."},
                "color": {"type": "string", "description": "Fill or stroke colour."},
                "background": {"type": "string", "description": "Background colour or 'none'."},
                "stroke_width": {"type": "integer", "description": "Stroke width."},
            },
        },
        anim_svg,
        group="animation",
    )
