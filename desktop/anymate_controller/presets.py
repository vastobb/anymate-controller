"""Motion systems — the animation brain that used to live inside the app.

Each preset returns a list of frames; a frame is a list of AnyMate tool calls.
The controller ships them to the phone with ``anymate_batch`` so the artist
watches the animation build live, and the whole sequence stays one undo step.
"""

from __future__ import annotations

import math
from typing import Any, Dict, List, Sequence, Tuple

Frame = List[Dict[str, Any]]
Call = Dict[str, Any]

DEFAULT_PALETTE = ["#7C3AED", "#22D3EE", "#F97316", "#111827", "#F4F5F7"]


def _shape(
    kind: str,
    x1: float,
    y1: float,
    x2: float,
    y2: float,
    fill: str = "",
    stroke: str = "",
    width: float = 3.0,
    layer: int = 0,
) -> Call:
    args: Dict[str, Any] = {
        "shape": kind,
        "x1": round(x1, 2),
        "y1": round(y1, 2),
        "x2": round(x2, 2),
        "y2": round(y2, 2),
        "width": width,
        "layer": layer,
    }
    if fill:
        args["fill_color"] = fill
    if stroke:
        args["stroke_color"] = stroke
    return {"tool": "anymate_draw_shape", "args": args}


def _stroke(
    points: Sequence[Tuple[float, float]],
    color: str,
    width: float = 4.0,
    tool: str = "pen",
    layer: int = 0,
) -> Call:
    return {
        "tool": "anymate_draw_stroke",
        "args": {
            "points": [[round(x, 2), round(y, 2)] for x, y in points],
            "color": color,
            "width": width,
            "tool": tool,
            "layer": layer,
        },
    }


def _text(value: str, x: float, y: float, size: float, color: str, layer: int = 2) -> Call:
    return {
        "tool": "anymate_draw_text",
        "args": {
            "text": value,
            "x": round(x, 2),
            "y": round(y, 2),
            "size": size,
            "color": color,
            "layer": layer,
        },
    }


def _background(width: float, height: float, color: str) -> Call:
    return _shape("rect", 0, 0, width, height, fill=color, stroke=color, width=1, layer=0)


# ---- presets ---------------------------------------------------------------


def bounce(width: float, height: float, frames: int, palette: List[str]) -> List[Frame]:
    """A ball that falls, squashes and rebounds — the classic loop."""

    accent, accent2, ink, paper = palette[0], palette[1], palette[2], palette[3]
    radius = min(width, height) * 0.085
    ground = height * 0.78
    out: List[Frame] = []
    for index in range(frames):
        t = index / frames
        phase = (t * 2.0) % 1.0
        height_norm = math.sin(phase * math.pi)
        squash = 1.0 - height_norm
        x = width * 0.5 + math.sin(t * 2 * math.pi) * width * 0.2
        rx = radius * (1 + 0.16 * squash**3)
        ry = radius * (1 - 0.13 * squash**3)
        cy = ground - ry - height_norm * height * 0.34
        frame: Frame = [_background(width, height, paper)]
        frame.append(
            _shape(
                "ellipse",
                x - rx * 1.3,
                ground,
                x + rx * 1.3,
                ground + radius * 0.2,
                fill=_alpha(ink, 0.18),
                layer=0,
            )
        )
        for trail in range(1, 4):
            prev_t = (t - trail * 0.012) % 1.0
            prev_phase = (prev_t * 2.0) % 1.0
            prev_h = math.sin(prev_phase * math.pi)
            prev_x = width * 0.5 + math.sin(prev_t * 2 * math.pi) * width * 0.2
            prev_y = ground - radius - prev_h * height * 0.34
            size = radius * (0.22 - trail * 0.04)
            frame.append(
                _shape(
                    "ellipse",
                    prev_x - size,
                    prev_y - size,
                    prev_x + size,
                    prev_y + size,
                    fill=accent2,
                    layer=0,
                )
            )
        frame.append(_shape("ellipse", x - rx, cy - ry, x + rx, cy + ry, fill=accent, layer=1))
        frame.append(
            _shape(
                "ellipse",
                x - rx * 0.55,
                cy - ry * 0.55,
                x - rx * 0.05,
                cy - ry * 0.15,
                fill="#FFFFFF",
                layer=1,
            )
        )
        if height_norm < 0.2:
            ring = radius * (1.4 + (0.2 - height_norm) * 5.0)
            frame.append(
                _shape("ellipse", x - ring, ground - ring * 0.3, x + ring, ground + ring * 0.3, stroke=accent2, width=3, layer=0)
            )
        out.append(frame)
    return out


def walk(width: float, height: float, frames: int, palette: List[str]) -> List[Frame]:
    """A two-step walk cycle with a bobbing torso and swinging arms."""

    accent, accent2, ink, paper = palette[0], palette[1], palette[2], palette[3]
    ground = height * 0.82
    hip_y = height * 0.60
    shoulder_y = height * 0.45
    head_r = width * 0.042
    bone = max(3.0, width * 0.008)
    out: List[Frame] = []
    for index in range(frames):
        t = index / frames
        phase = t * 2 * math.pi * 2
        bob = abs(math.sin(phase)) * height * 0.012
        hip = hip_y - bob
        shoulder = shoulder_y - bob
        head_y = shoulder - head_r * 1.5
        frame: Frame = [_background(width, height, paper)]
        frame.append(_shape("ellipse", width * 0.34, ground, width * 0.66, ground + height * 0.012, fill=_alpha(ink, 0.15), layer=0))
        for leg_phase, colour in ((phase, ink), (phase + math.pi, accent)):
            foot_x = width * 0.5 + math.sin(leg_phase) * width * 0.13
            lift = max(0.0, math.sin(leg_phase + math.pi * 0.35)) * height * 0.055
            foot_y = ground - lift
            hip_x = width * 0.5 + (width * 0.012 if math.sin(leg_phase) >= 0 else -width * 0.012)
            knee_x = (hip_x + foot_x) / 2 + math.cos(leg_phase) * width * 0.028
            knee_y = (hip + foot_y) / 2 - height * 0.018
            frame.append(_stroke([(hip_x, hip), (knee_x, knee_y)], colour, bone, layer=1))
            frame.append(_stroke([(knee_x, knee_y), (foot_x, foot_y)], colour, bone, layer=1))
            frame.append(_stroke([(foot_x - width * 0.016, foot_y), (foot_x + width * 0.022, foot_y)], colour, bone, layer=1))
        arm_phase = phase + math.pi
        hand_x = width * 0.5 - math.sin(arm_phase) * width * 0.12
        hand_y = shoulder + height * 0.13 + math.cos(arm_phase) * height * 0.018
        elbow_x = (width * 0.5 + hand_x) / 2 - math.cos(arm_phase) * width * 0.02
        elbow_y = (shoulder + hand_y) / 2 + height * 0.01
        frame.append(_stroke([(width * 0.5 + width * 0.014, shoulder + height * 0.012), (elbow_x, elbow_y)], ink, bone * 0.9, layer=1))
        frame.append(_stroke([(elbow_x, elbow_y), (hand_x, hand_y)], ink, bone * 0.9, layer=1))
        frame.append(_stroke([(width * 0.5, hip), (width * 0.5, shoulder)], accent, bone * 1.7, layer=1))
        frame.append(_shape("ellipse", width * 0.5 - head_r, head_y - head_r, width * 0.5 + head_r, head_y + head_r, fill=palette[1], layer=1))
        out.append(frame)
    return out


def spin(width: float, height: float, frames: int, palette: List[str]) -> List[Frame]:
    """Rotating geometry with counter-rotating rings."""

    accent, accent2, ink, paper = palette[0], palette[1], palette[2], palette[3]
    cx, cy = width * 0.5, height * 0.5
    radius = min(width, height) * 0.26
    out: List[Frame] = []
    for index in range(frames):
        t = index / frames
        frame: Frame = [_background(width, height, paper)]
        for ring, colour in ((1.0, accent2), (0.78, accent), (0.56, ink)):
            points = []
            sides = 3 if ring == 1.0 else (4 if ring == 0.78 else 6)
            rotation = t * 2 * math.pi * (1 if ring != 0.78 else -2)
            for step in range(sides):
                angle = rotation + step * 2 * math.pi / sides
                points.append((cx + math.cos(angle) * radius * ring, cy + math.sin(angle) * radius * ring))
            frame.append(
                {
                    "tool": "anymate_draw_polygon",
                    "args": {
                        "points": [[round(x, 2), round(y, 2)] for x, y in points],
                        "closed": True,
                        "stroke_color": colour,
                        "fill_color": accent if ring == 1.0 else "",
                        "width": 4,
                        "layer": 1,
                    },
                }
            )
        frame.append(_shape("ellipse", cx - radius * 0.1, cy - radius * 0.1, cx + radius * 0.1, cy + radius * 0.1, fill=paper, layer=1))
        out.append(frame)
    return out


def pulse(width: float, height: float, frames: int, palette: List[str]) -> List[Frame]:
    """Concentric rings breathing out of the centre."""

    accent, accent2, ink, paper = palette[0], palette[1], palette[2], palette[3]
    cx, cy = width * 0.5, height * 0.5
    base = min(width, height) * 0.1
    maximum = min(width, height) * 0.42
    out: List[Frame] = []
    for index in range(frames):
        t = index / frames
        frame: Frame = [_background(width, height, paper)]
        for ring in range(5):
            phase = (t + ring / 5.0) % 1.0
            radius = base + (maximum - base) * phase
            frame.append(
                _shape(
                    "ellipse",
                    cx - radius,
                    cy - radius,
                    cx + radius,
                    cy + radius,
                    stroke=accent2 if ring % 2 else accent,
                    width=2 + 4 * (1 - phase),
                    layer=0,
                )
            )
        beat = base * (1 + 0.12 * math.sin(t * 2 * math.pi * 2) ** 2)
        frame.append(_shape("ellipse", cx - beat, cy - beat, cx + beat, cy + beat, fill=accent, layer=1))
        out.append(frame)
    return out


def fireworks(width: float, height: float, frames: int, palette: List[str]) -> List[Frame]:
    """Bursting particles on a night sky."""

    accent, accent2, ink, _paper = palette[0], palette[1], palette[2], palette[3]
    night = "#0B0A14"
    centres = [(width * 0.27, height * 0.34, 0.0), (width * 0.73, height * 0.27, 0.34), (width * 0.5, height * 0.58, 0.66)]
    out: List[Frame] = []
    for index in range(frames):
        t = index / frames
        frame: Frame = [_background(width, height, night)]
        for star in range(24):
            sx = (star * 137.5) % width
            sy = (star * 79.3) % (height * 0.7)
            size = 1.6 + (star % 3)
            frame.append(_shape("ellipse", sx - size, sy - size, sx + size, sy + size, fill="#FFFFFF", layer=0))
        for burst, (bx, by, offset) in enumerate(centres):
            age = (t + offset) % 1.0
            if age >= 0.82:
                continue
            normalised = age / 0.82
            colour = (accent, accent2, palette[3])[burst]
            for particle in range(18):
                angle = particle * (2 * math.pi / 18) + burst * 0.6
                speed = (0.14 + 0.10 * ((particle * 7) % 5) / 4.0) * min(width, height)
                radius = normalised * speed
                tail = max(0.0, radius - speed * 0.08)
                frame.append(
                    _stroke(
                        [
                            (bx + math.cos(angle) * tail, by + math.sin(angle) * tail),
                            (bx + math.cos(angle) * radius, by + math.sin(angle) * radius),
                        ],
                        colour,
                        2.2 + 1.4 * (1 - normalised),
                        layer=1,
                    )
                )
        out.append(frame)
    return out


def wave(width: float, height: float, frames: int, palette: List[str]) -> List[Frame]:
    """A flowing gradient ribbon."""

    accent, accent2, ink, paper = palette[0], palette[1], palette[2], palette[3]
    out: List[Frame] = []
    segments = 36
    for index in range(frames):
        t = index / frames
        frame: Frame = [_background(width, height, paper)]
        points = []
        for step in range(segments + 1):
            fraction = step / segments
            y = height * 0.52 + math.sin(fraction * 2 * math.pi * 1.6 + t * 2 * math.pi) * height * 0.1
            points.append((fraction * width, y))
        for step in range(segments):
            x1, y1 = points[step]
            x2, y2 = points[step + 1]
            blend = step / segments
            colour = _mix_hex(accent, accent2, blend)
            frame.append(_stroke([(x1, y1), (x2, y2)], colour, 7, layer=1))
        out.append(frame)
    return out


def orbit(width: float, height: float, frames: int, palette: List[str]) -> List[Frame]:
    """Planets circling a warm core."""

    accent, accent2, ink, paper = palette[0], palette[1], palette[2], palette[3]
    cx, cy = width * 0.5, height * 0.5
    base = min(width, height)
    out: List[Frame] = []
    for index in range(frames):
        t = index / frames
        frame: Frame = [_background(width, height, paper)]
        for orbit, colour in ((0.18, accent2), (0.29, palette[3]), (0.39, accent)):
            radius = base * orbit
            frame.append(
                _shape("ellipse", cx - radius, cy - radius * 0.62, cx + radius, cy + radius * 0.62, stroke=colour, width=2, layer=0)
            )
            angle = t * 2 * math.pi * (1.1 + orbit * 4) + orbit * 12
            px = cx + math.cos(angle) * radius
            py = cy + math.sin(angle) * radius * 0.62
            size = base * 0.016
            frame.append(_shape("ellipse", px - size, py - size, px + size, py + size, fill=colour, layer=1))
        core = base * 0.095
        frame.append(_shape("ellipse", cx - core, cy - core, cx + core, cy + core, fill=palette[3], layer=1))
        out.append(frame)
    return out


def kinetic_type(
    width: float, height: float, frames: int, palette: List[str], message: str = "ANYMATE"
) -> List[Frame]:
    """Type that writes itself, letter by letter."""

    accent, accent2, ink, paper = palette[0], palette[1], palette[2], palette[3]
    out: List[Frame] = []
    for index in range(frames):
        t = index / frames
        visible = max(1, int((t * 1.25 + 0.05) * len(message)))
        text = message[:visible]
        size = min(height * 0.16, width * 0.9 / max(1, len(message) * 0.62))
        x = (width - size * 0.62 * len(message)) / 2
        frame: Frame = [_background(width, height, paper)]
        frame.append(_text(text, x, height * 0.53, size, ink, layer=1))
        frame.append(
            _stroke(
                [(x, height * 0.53 + size * 0.22), (x + size * 0.62 * visible, height * 0.53 + size * 0.22)],
                accent2,
                4,
                layer=1,
            )
        )
        out.append(frame)
    return out


PRESETS = {
    "bounce": bounce,
    "walk": walk,
    "spin": spin,
    "pulse": pulse,
    "fireworks": fireworks,
    "wave": wave,
    "orbit": orbit,
    "kinetic-type": kinetic_type,
}


def _alpha(colour: str, alpha: float) -> str:
    """Return the colour with an alpha byte applied (#RRGGBBAA)."""

    text = colour.lstrip("#")
    if len(text) == 3:
        text = "".join(ch * 2 for ch in text)
    if len(text) == 6:
        text += "ff"
    value = int(text[6:8], 16) if len(text) >= 8 else 255
    new_alpha = int(max(0.0, min(1.0, alpha)) * 255)
    return "#" + text[:6] + f"{min(value, new_alpha):02X}"


def _mix_hex(a: str, b: str, fraction: float) -> str:
    def channels(value: str) -> Tuple[int, int, int]:
        text = value.lstrip("#")
        return tuple(int(text[i : i + 2], 16) for i in (0, 2, 4))  # type: ignore[return-value]

    ca, cb = channels(a), channels(b)
    f = max(0.0, min(1.0, fraction))
    mixed = tuple(int(ca[i] * (1 - f) + cb[i] * f) for i in range(3))
    return "#{:02X}{:02X}{:02X}".format(*mixed)


def build(
    preset: str,
    width: float,
    height: float,
    frames: int,
    palette: Sequence[str] = (),
    message: str = "ANYMATE",
) -> List[Frame]:
    """Build the frames for a preset."""

    key = preset.strip().lower().replace(" ", "-")
    builder = PRESETS.get(key)
    if builder is None:
        raise KeyError(f"Unknown preset '{preset}'. Known presets: {', '.join(sorted(PRESETS))}.")
    colours = list(palette) if palette else list(DEFAULT_PALETTE)
    while len(colours) < 4:
        colours.append(DEFAULT_PALETTE[len(colours) % len(DEFAULT_PALETTE)])
    if builder is kinetic_type:
        return builder(width, height, frames, colours, message)
    return builder(width, height, frames, colours)
