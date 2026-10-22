import math
from pathlib import Path

import pytest

from anymate_controller.config import Config
from anymate_controller.tools import draw_tools
from anymate_controller.tools.pngio import Image, read_png, write_png
from anymate_controller.tools.registry import ToolRegistry


@pytest.fixture()
def registry(tmp_path: Path) -> ToolRegistry:
    config = Config(workspace=str(tmp_path))
    registry = ToolRegistry()
    draw_tools.register(registry, config)
    return registry


def call(registry: ToolRegistry, name: str, **kwargs):
    import asyncio

    loop = asyncio.new_event_loop()
    try:
        return loop.run_until_complete(registry.call(name, kwargs))
    finally:
        loop.close()


def test_png_roundtrip(tmp_path: Path):
    image = Image(4, 3, bytearray(4 * 3 * 4))
    for y in range(3):
        for x in range(4):
            image.set_pixel(x, y, (x * 60 % 256, y * 80 % 256, 128, 255))
    path = write_png(tmp_path / "sample.png", image)
    decoded = read_png(path)
    assert decoded.width == 4 and decoded.height == 3
    assert decoded.pixel(1, 1) == (60, 80, 128, 255)


def test_palette_is_a_harmony(registry: ToolRegistry):
    result = call(registry, "anim_palette", base="#7C3AED", scheme="split", count=5)
    assert result.ok
    colors = result.data["colors"]
    assert len(colors) == 5
    assert all(c.startswith("#") and len(c) == 7 for c in colors)


def test_easing_curves_are_monotonic(registry: ToolRegistry):
    result = call(registry, "anim_easing", easing="ease-in-out", count=8)
    assert result.ok
    values = result.data["values"]
    assert values == sorted(values)
    assert values[0] == pytest.approx(0.0)
    assert values[-1] == pytest.approx(1.0)


def test_unknown_easing_is_reported(registry: ToolRegistry):
    result = call(registry, "anim_easing", easing="nope")
    assert not result.ok


def test_sprite_sheet_packs_frames(registry: ToolRegistry, tmp_path: Path):
    for index in range(4):
        image = Image(8, 8, bytearray(8 * 8 * 4))
        for y in range(8):
            for x in range(8):
                image.set_pixel(x, y, (index * 40 % 256, 90, 200, 255))
        write_png(tmp_path / f"frame{index}.png", image)
    result = call(
        registry,
        "anim_sprite_sheet",
        frame_paths=[f"frame{i}.png" for i in range(4)],
        output_path="sheet.png",
        columns=2,
    )
    assert result.ok
    sheet = read_png(tmp_path / "sheet.png")
    assert sheet.width == 16 and sheet.height == 16


def test_timing_plan(registry: ToolRegistry):
    result = call(registry, "anim_timing", fps=24, seconds=2, style="on-twos")
    assert result.data["total_frames"] == 48
    assert result.data["unique_drawings"] == 24
    assert result.data["frame_duration_ms"] == pytest.approx(41.7, abs=0.1)


def test_storyboard_beats(registry: ToolRegistry):
    result = call(registry, "anim_storyboard", beats="wide\nclose\npull out", fps=8)
    assert len(result.data["beats"]) == 3
    assert result.data["beats"][0]["frame_range"] == "0-7"
