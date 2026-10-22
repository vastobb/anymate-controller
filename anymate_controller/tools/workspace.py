"""Workspace helpers: every file tool is confined to the workspace folder."""

from __future__ import annotations

import os
import shutil
from pathlib import Path
from typing import Iterable, List


class WorkspaceError(RuntimeError):
    """Raised when a path escapes the workspace."""


def resolve(workspace: Path, raw: str) -> Path:
    """Resolve ``raw`` inside ``workspace`` and refuse to escape it."""

    if raw is None:
        raise WorkspaceError("A path is required.")
    candidate = Path(os.path.expanduser(str(raw)))
    if candidate.is_absolute():
        resolved = candidate.resolve()
    else:
        resolved = (workspace / candidate).resolve()
    root = workspace.resolve()
    if resolved != root and root not in resolved.parents:
        raise WorkspaceError(f"Path '{raw}' is outside the workspace ({root}).")
    return resolved


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def list_tree(root: Path, pattern: str = "*", limit: int = 400) -> List[str]:
    matches: Iterable[Path] = sorted(root.rglob(pattern))
    return [str(item.relative_to(root)) for item in matches[:limit]]


def tail_file(path: Path, lines: int = 80) -> str:
    if not path.exists():
        raise WorkspaceError(f"'{path}' does not exist.")
    content = path.read_text(encoding="utf-8", errors="replace").splitlines()
    return "\n".join(content[-lines:])


def copy_into_workspace(source: Path, workspace: Path) -> Path:
    ensure_parent(workspace)
    target = workspace / source.name
    shutil.copy2(source, target)
    return target
