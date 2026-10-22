"""Terminal, file and Python tools — the controller's hands on the machine."""

from __future__ import annotations

import os
import shlex
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Dict, List, Optional

from .registry import ToolRegistry, ToolResult
from .workspace import WorkspaceError, ensure_parent, list_tree, resolve, tail_file


def _run(
    command: List[str],
    cwd: Path,
    timeout: float,
    env: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    started = os.times()
    try:
        completed = subprocess.run(
            command,
            cwd=str(cwd),
            capture_output=True,
            text=True,
            timeout=timeout,
            env=env,
            check=False,
        )
        return {
            "command": " ".join(shlex.quote(part) for part in command),
            "returncode": completed.returncode,
            "stdout": completed.stdout[-20000:],
            "stderr": completed.stderr[-20000:],
            "duration": round(os.times().elapsed - started.elapsed, 3),
            "timed_out": False,
        }
    except subprocess.TimeoutExpired:
        return {
            "command": " ".join(shlex.quote(part) for part in command),
            "returncode": 124,
            "stdout": "",
            "stderr": f"Timed out after {timeout:g}s.",
            "duration": timeout,
            "timed_out": True,
        }
    except FileNotFoundError as exc:
        return {
            "command": command[0] if command else "",
            "returncode": 127,
            "stdout": "",
            "stderr": str(exc),
            "duration": 0.0,
            "timed_out": False,
        }


def register(registry: ToolRegistry, config) -> None:
    """Register shell, file and Python tools."""

    workspace = config.workspace_path

    # ---- terminal --------------------------------------------------------
    def shell_run(
        command: str,
        cwd: str = "",
        timeout: float = 0.0,
        stdin: str = "",
    ) -> ToolResult:
        if not config.allow_shell:
            return ToolResult(text="Shell access is disabled in the config.", ok=False)
        if not command.strip():
            return ToolResult(text="A command is required.", ok=False)
        try:
            argv = shlex.split(command)
        except ValueError as exc:
            return ToolResult(text=f"Could not parse the command: {exc}", ok=False)
        if not argv:
            return ToolResult(text="A command is required.", ok=False)
        try:
            target = resolve(workspace, cwd) if cwd else workspace
        except WorkspaceError as exc:
            return ToolResult(text=str(exc), ok=False)
        if target.is_file():
            target = target.parent
        limit = timeout or config.command_timeout
        result = _run(argv, target, limit)
        return ToolResult(
            text=(
                f"$ {result['command']}\n"
                f"exit {result['returncode']} in {result['duration']}s\n"
                f"{result['stdout']}"
                f"{('[stderr] ' + result['stderr']) if result['stderr'] else ''}"
            ).strip(),
            data=result,
            ok=result["returncode"] == 0,
        )

    registry.register(
        "shell_run",
        "Run a shell command in the workspace. Use it for git, file utilities, "
        "installing packages and inspecting the machine. Returns stdout and stderr.",
        {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "Command line to run."},
                "cwd": {"type": "string", "description": "Working directory inside the workspace."},
                "timeout": {"type": "number", "description": "Timeout in seconds."},
                "stdin": {"type": "string", "description": "Optional text piped to stdin."},
            },
            "required": ["command"],
        },
        shell_run,
        group="terminal",
        dangerous=True,
    )

    def shell_list(pattern: str = "*", cwd: str = "") -> ToolResult:
        try:
            target = resolve(workspace, cwd) if cwd else workspace
        except WorkspaceError as exc:
            return ToolResult(text=str(exc), ok=False)
        entries = list_tree(target, pattern or "*")
        return ToolResult(
            text="\n".join(entries) if entries else "(empty)",
            data={"files": entries, "root": str(target)},
        )

    registry.register(
        "shell_list",
        "List files in the workspace, optionally filtered by a glob such as '*.png'.",
        {
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Glob pattern."},
                "cwd": {"type": "string", "description": "Directory inside the workspace."},
            },
        },
        shell_list,
        group="terminal",
    )

    def shell_read(path: str, max_bytes: int = 200000) -> ToolResult:
        try:
            target = resolve(workspace, path)
        except WorkspaceError as exc:
            return ToolResult(text=str(exc), ok=False)
        if not target.exists():
            return ToolResult(text=f"'{path}' does not exist.", ok=False)
        if target.is_dir():
            return ToolResult(text=f"'{path}' is a directory.", ok=False)
        data = target.read_bytes()[:max_bytes]
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            return ToolResult(
                text=f"Binary file ({len(data)} bytes).",
                data={"bytes": len(data)},
            )
        return ToolResult(text=text, data={"bytes": len(data), "path": str(target)})

    registry.register(
        "shell_read",
        "Read a text file from the workspace.",
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path inside the workspace."},
                "max_bytes": {"type": "integer", "description": "Truncate after this many bytes."},
            },
            "required": ["path"],
        },
        shell_read,
        group="terminal",
    )

    def shell_write(path: str, content: str, append: bool = False) -> ToolResult:
        try:
            target = resolve(workspace, path)
        except WorkspaceError as exc:
            return ToolResult(text=str(exc), ok=False)
        ensure_parent(target)
        mode = "a" if append else "w"
        with target.open(mode, encoding="utf-8") as handle:
            handle.write(content)
        return ToolResult(
            text=f"Wrote {len(content)} characters to {target.name}.",
            data={"path": str(target), "bytes": target.stat().st_size},
        )

    registry.register(
        "shell_write",
        "Write a text file in the workspace. Set append to add to an existing file.",
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path inside the workspace."},
                "content": {"type": "string", "description": "File contents."},
                "append": {"type": "boolean", "description": "Append instead of overwrite."},
            },
            "required": ["path", "content"],
        },
        shell_write,
        group="terminal",
    )

    def shell_tail(path: str, lines: int = 80) -> ToolResult:
        try:
            target = resolve(workspace, path)
        except WorkspaceError as exc:
            return ToolResult(text=str(exc), ok=False)
        try:
            text = tail_file(target, lines)
        except WorkspaceError as exc:
            return ToolResult(text=str(exc), ok=False)
        return ToolResult(text=text)

    registry.register(
        "shell_tail",
        "Show the last lines of a text file — handy for build or render logs.",
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path inside the workspace."},
                "lines": {"type": "integer", "description": "How many lines to show."},
            },
            "required": ["path"],
        },
        shell_tail,
        group="terminal",
    )

    # ---- python ----------------------------------------------------------
    def python_run(code: str, timeout: float = 0.0) -> ToolResult:
        if not config.allow_python:
            return ToolResult(text="Python execution is disabled in the config.", ok=False)
        if not code.strip():
            return ToolResult(text="Some Python code is required.", ok=False)
        limit = timeout or config.command_timeout
        with tempfile.NamedTemporaryFile(
            "w", suffix=".py", dir=str(workspace), delete=False, encoding="utf-8"
        ) as handle:
            handle.write(code)
            script = Path(handle.name)
        try:
            result = _run([config.python, str(script)], workspace, limit)
        finally:
            try:
                script.unlink()
            except OSError:
                pass
        return ToolResult(
            text=(
                f"python exit {result['returncode']} in {result['duration']}s\n"
                f"{result['stdout']}"
                f"{('[stderr] ' + result['stderr']) if result['stderr'] else ''}"
            ).strip(),
            data=result,
            ok=result["returncode"] == 0,
        )

    registry.register(
        "python_run",
        "Execute a Python 3 snippet inside the workspace and return stdout/stderr. "
        "The snippet runs in a temporary file, so `__file__` is not meaningful — "
        "use relative paths or the workspace directory instead.",
        {
            "type": "object",
            "properties": {
                "code": {"type": "string", "description": "Python source to execute."},
                "timeout": {"type": "number", "description": "Timeout in seconds."},
            },
            "required": ["code"],
        },
        python_run,
        group="python",
        dangerous=True,
    )

    def python_version() -> ToolResult:
        result = _run([config.python, "--version"], workspace, 20.0)
        return ToolResult(
            text=(result["stdout"] or result["stderr"]).strip() or "python unavailable",
            data=result,
            ok=result["returncode"] == 0,
        )

    registry.register(
        "python_version",
        "Report the Python interpreter used by the controller.",
        {"type": "object", "properties": {}},
        python_version,
        group="python",
    )

    def python_packages() -> ToolResult:
        result = _run([config.python, "-m", "pip", "list", "--format=freeze"], workspace, 60.0)
        packages = [line for line in result["stdout"].splitlines() if "==" in line]
        return ToolResult(
            text="\n".join(packages) or "(no packages reported)",
            data={"packages": packages},
        )

    registry.register(
        "python_packages",
        "List the Python packages available to the controller interpreter.",
        {"type": "object", "properties": {}},
        python_packages,
        group="python",
    )
