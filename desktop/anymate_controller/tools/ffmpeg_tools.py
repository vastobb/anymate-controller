"""ffmpeg and ffprobe tools — render, probe, trim, convert and composite."""

from __future__ import annotations

import json
import os
import shlex
import shutil
import subprocess
from pathlib import Path
from typing import Any, Dict, List, Optional

from .registry import ToolRegistry, ToolResult
from .workspace import WorkspaceError, ensure_parent, resolve


def _run(command: List[str], cwd: Path, timeout: float) -> Dict[str, Any]:
    try:
        completed = subprocess.run(
            command,
            cwd=str(cwd),
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        return {
            "command": " ".join(shlex.quote(part) for part in command),
            "returncode": completed.returncode,
            "stdout": completed.stdout[-20000:],
            "stderr": completed.stderr[-20000:],
        }
    except subprocess.TimeoutExpired:
        return {
            "command": " ".join(shlex.quote(part) for part in command),
            "returncode": 124,
            "stdout": "",
            "stderr": f"Timed out after {timeout:g}s.",
        }
    except FileNotFoundError as exc:
        return {
            "command": command[0] if command else "",
            "returncode": 127,
            "stdout": "",
            "stderr": str(exc),
        }


def _resolve(workspace: Path, raw: str) -> Path:
    try:
        return resolve(workspace, raw)
    except WorkspaceError:
        # Allow absolute paths for media that lives outside the workspace.
        path = Path(os.path.expanduser(raw)).resolve()
        return path


def register(registry: ToolRegistry, config) -> None:
    """Register ffmpeg tools."""

    workspace = config.workspace_path

    def ffmpeg_available() -> ToolResult:
        ffmpeg = shutil.which(config.ffmpeg_binary)
        ffprobe = shutil.which(config.ffprobe_binary)
        if not ffmpeg:
            return ToolResult(
                text=(
                    "ffmpeg was not found on PATH. Install it with "
                    "'brew install ffmpeg', 'apt install ffmpeg' or from ffmpeg.org."
                ),
                data={"ffmpeg": None, "ffprobe": None},
                ok=False,
            )
        version = _run([ffmpeg, "-version"], workspace, 20.0)
        first = (version["stdout"] or version["stderr"]).splitlines()[:1]
        return ToolResult(
            text=(first[0] if first else "ffmpeg available") + f"\nffprobe: {ffprobe or 'missing'}",
            data={"ffmpeg": ffmpeg, "ffprobe": ffprobe},
        )

    registry.register(
        "ffmpeg_available",
        "Check whether ffmpeg and ffprobe are installed and report their paths.",
        {"type": "object", "properties": {}},
        ffmpeg_available,
        group="ffmpeg",
    )

    def ffmpeg_run(args: str, timeout: float = 0.0) -> ToolResult:
        if not args.strip():
            return ToolResult(text="ffmpeg arguments are required.", ok=False)
        try:
            argv = shlex.split(args)
        except ValueError as exc:
            return ToolResult(text=f"Could not parse the arguments: {exc}", ok=False)
        command = [config.ffmpeg_binary, "-hide_banner", "-y", *argv]
        result = _run(command, workspace, timeout or config.command_timeout)
        return ToolResult(
            text=(
                f"$ {result['command']}\n"
                f"exit {result['returncode']}\n"
                f"{result['stderr'][-4000:]}"
            ).strip(),
            data=result,
            ok=result["returncode"] == 0,
        )

    registry.register(
        "ffmpeg_run",
        "Run ffmpeg with raw arguments (without the leading 'ffmpeg'). "
        "Use it for anything the convenience tools do not cover.",
        {
            "type": "object",
            "properties": {
                "args": {"type": "string", "description": "ffmpeg arguments, e.g. '-i in.mov -vf scale=640:-1 out.mp4'"},
                "timeout": {"type": "number", "description": "Timeout in seconds."},
            },
            "required": ["args"],
        },
        ffmpeg_run,
        group="ffmpeg",
        dangerous=True,
    )

    def ffmpeg_probe(path: str) -> ToolResult:
        target = _resolve(workspace, path)
        if not target.exists():
            return ToolResult(text=f"'{path}' does not exist.", ok=False)
        result = _run(
            [
                config.ffprobe_binary,
                "-v",
                "quiet",
                "-print_format",
                "json",
                "-show_format",
                "-show_streams",
                str(target),
            ],
            workspace,
            60.0,
        )
        if result["returncode"] != 0:
            return ToolResult(text=result["stderr"] or "ffprobe failed.", ok=False)
        try:
            data = json.loads(result["stdout"])
        except ValueError:
            return ToolResult(text=result["stdout"][:4000])
        summary: Dict[str, Any] = {"format": data.get("format", {})}
        streams = []
        for stream in data.get("streams", []):
            streams.append(
                {
                    "index": stream.get("index"),
                    "type": stream.get("codec_type"),
                    "codec": stream.get("codec_name"),
                    "width": stream.get("width"),
                    "height": stream.get("height"),
                    "fps": stream.get("avg_frame_rate"),
                    "duration": stream.get("duration"),
                    "bitrate": stream.get("bit_rate"),
                }
            )
        summary["streams"] = streams
        return ToolResult(text=json.dumps(summary, indent=2), data=summary)

    registry.register(
        "ffmpeg_probe",
        "Inspect a media file: container, duration, streams, codecs, resolution and frame rate.",
        {
            "type": "object",
            "properties": {"path": {"type": "string", "description": "Media file to inspect."}},
            "required": ["path"],
        },
        ffmpeg_probe,
        group="ffmpeg",
    )

    def ffmpeg_convert(
        input_path: str,
        output_path: str,
        width: int = 0,
        height: int = 0,
        fps: int = 0,
        crf: int = 20,
        preset: str = "medium",
        extra: str = "",
    ) -> ToolResult:
        source = _resolve(workspace, input_path)
        target = _resolve(workspace, output_path)
        if not source.exists():
            return ToolResult(text=f"'{input_path}' does not exist.", ok=False)
        ensure_parent(target)
        filters: List[str] = []
        if width or height:
            filters.append(f"scale={width or -1}:{height or -1}")
        args = ["-i", str(source)]
        if filters:
            args += ["-vf", ",".join(filters)]
        if fps:
            args += ["-r", str(fps)]
        if extra:
            args += shlex.split(extra)
        args += [
            "-c:v",
            "libx264",
            "-preset",
            preset,
            "-crf",
            str(crf),
            "-pix_fmt",
            "yuv420p",
            str(target),
        ]
        result = _run([config.ffmpeg_binary, "-hide_banner", "-y", *args], workspace, config.command_timeout)
        return ToolResult(
            text=(
                f"Converted to {target.name} (exit {result['returncode']})\n"
                f"{result['stderr'][-3000:]}"
            ).strip(),
            data={"output": str(target), **result},
            ok=result["returncode"] == 0,
        )

    registry.register(
        "ffmpeg_convert",
        "Transcode a video or image sequence into an H.264 mp4, with optional scale, frame rate and quality.",
        {
            "type": "object",
            "properties": {
                "input_path": {"type": "string", "description": "Source media."},
                "output_path": {"type": "string", "description": "Destination file."},
                "width": {"type": "integer", "description": "Output width in pixels."},
                "height": {"type": "integer", "description": "Output height in pixels."},
                "fps": {"type": "integer", "description": "Output frame rate."},
                "crf": {"type": "integer", "description": "Quality: 0 (lossless) to 51 (worst)."},
                "preset": {"type": "string", "description": "x264 speed preset."},
                "extra": {"type": "string", "description": "Extra ffmpeg arguments."},
            },
            "required": ["input_path", "output_path"],
        },
        ffmpeg_convert,
        group="ffmpeg",
    )

    def ffmpeg_extract_frames(
        input_path: str,
        output_pattern: str = "frames/frame_%04d.png",
        fps: int = 0,
        start: str = "",
        duration: str = "",
    ) -> ToolResult:
        source = _resolve(workspace, input_path)
        if not source.exists():
            return ToolResult(text=f"'{input_path}' does not exist.", ok=False)
        target = _resolve(workspace, output_pattern)
        ensure_parent(target)
        args = ["-i", str(source)]
        if start:
            args += ["-ss", start]
        if duration:
            args += ["-t", duration]
        if fps:
            args += ["-vf", f"fps={fps}"]
        args += [str(target)]
        result = _run([config.ffmpeg_binary, "-hide_banner", "-y", *args], workspace, config.command_timeout)
        produced = sorted(str(p) for p in target.parent.glob(target.name.replace("%04d", "*")))
        return ToolResult(
            text=f"Extracted {len(produced)} frames to {target.parent.name}/\n"
            + "\n".join(produced[:40]),
            data={"files": produced, **result},
            ok=result["returncode"] == 0,
        )

    registry.register(
        "ffmpeg_extract_frames",
        "Extract a video into an image sequence so the frames can be drawn on, "
        "re-timed or exported as a sprite sheet.",
        {
            "type": "object",
            "properties": {
                "input_path": {"type": "string", "description": "Source video."},
                "output_pattern": {"type": "string", "description": "Output pattern with %04d."},
                "fps": {"type": "integer", "description": "Sample rate in frames per second."},
                "start": {"type": "string", "description": "Start time, e.g. 00:00:02."},
                "duration": {"type": "string", "description": "Duration to extract."},
            },
            "required": ["input_path"],
        },
        ffmpeg_extract_frames,
        group="ffmpeg",
    )

    def ffmpeg_make_gif(
        input_path: str = "",
        frame_pattern: str = "",
        output_path: str = "animation.gif",
        fps: int = 12,
        width: int = 640,
        colors: int = 128,
    ) -> ToolResult:
        target = _resolve(workspace, output_path)
        ensure_parent(target)
        args: List[str] = []
        if frame_pattern:
            pattern = _resolve(workspace, frame_pattern)
            args += ["-framerate", str(fps), "-i", str(pattern)]
        elif input_path:
            source = _resolve(workspace, input_path)
            if not source.exists():
                return ToolResult(text=f"'{input_path}' does not exist.", ok=False)
            args += ["-i", str(source)]
        else:
            return ToolResult(text="Provide either input_path or frame_pattern.", ok=False)
        palette = f"split[a][b];[a]palettegen=max_colors={colors}[p];[b][p]paletteuse"
        args += [
            "-vf",
            f"fps={fps},scale={width}:-1:flags=lanczos,{palette}",
            "-loop",
            "0",
            str(target),
        ]
        result = _run([config.ffmpeg_binary, "-hide_banner", "-y", *args], workspace, config.command_timeout)
        return ToolResult(
            text=f"Wrote {target.name} (exit {result['returncode']})",
            data={"output": str(target), **result},
            ok=result["returncode"] == 0,
        )

    registry.register(
        "ffmpeg_make_gif",
        "Build an optimised animated GIF from a video or an image sequence.",
        {
            "type": "object",
            "properties": {
                "input_path": {"type": "string", "description": "Source video."},
                "frame_pattern": {"type": "string", "description": "Image sequence pattern, e.g. frames/%04d.png."},
                "output_path": {"type": "string", "description": "Destination gif."},
                "fps": {"type": "integer", "description": "Frames per second."},
                "width": {"type": "integer", "description": "Gif width in pixels."},
                "colors": {"type": "integer", "description": "Palette size."},
            },
            "required": ["output_path"],
        },
        ffmpeg_make_gif,
        group="ffmpeg",
    )

    def ffmpeg_concat(clips: List[str], output_path: str = "joined.mp4") -> ToolResult:
        if not clips:
            return ToolResult(text="Provide at least one clip.", ok=False)
        target = _resolve(workspace, output_path)
        ensure_parent(target)
        list_file = target.parent / f"{target.stem}_concat.txt"
        entries = []
        for clip in clips:
            path = _resolve(workspace, clip)
            entries.append(f"file '{path.as_posix()}'")
        list_file.write_text("\n".join(entries), encoding="utf-8")
        result = _run(
            [
                config.ffmpeg_binary,
                "-hide_banner",
                "-y",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                str(list_file),
                "-c",
                "copy",
                str(target),
            ],
            workspace,
            config.command_timeout,
        )
        return ToolResult(
            text=f"Joined {len(clips)} clips into {target.name} (exit {result['returncode']})",
            data={"output": str(target), **result},
            ok=result["returncode"] == 0,
        )

    registry.register(
        "ffmpeg_concat",
        "Join several clips (same codec) into one file without re-encoding.",
        {
            "type": "object",
            "properties": {
                "clips": {"type": "array", "items": {"type": "string"}, "description": "Clip paths."},
                "output_path": {"type": "string", "description": "Destination file."},
            },
            "required": ["clips"],
        },
        ffmpeg_concat,
        group="ffmpeg",
    )
