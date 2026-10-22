import pytest

from anymate_controller import presets


def test_every_preset_builds_the_requested_frames():
    for name in presets.PRESETS:
        frames = presets.build(name, 900, 900, 8, ["#7C3AED", "#22D3EE", "#111827", "#F4F5F7"], "HI")
        assert len(frames) == 8, name
        for frame in frames:
            assert frame, f"{name} produced an empty frame"
            for call in frame:
                assert call["tool"].startswith("anymate_")
                assert "args" in call


def test_bounce_moves():
    frames = presets.build("bounce", 900, 900, 12)
    ys = []
    for frame in frames:
        for call in frame:
            if call["tool"] == "anymate_draw_shape" and call["args"].get("fill_color") == "#7C3AED":
                ys.append(call["args"]["y1"])
    assert len(ys) == 12
    assert max(ys) - min(ys) > 100  # the ball really travels


def test_unknown_preset_raises():
    with pytest.raises(KeyError):
        presets.build("teleport", 900, 900, 4)


def test_palette_default_is_used():
    frames = presets.build("walk", 900, 900, 4)
    assert len(frames) == 4
