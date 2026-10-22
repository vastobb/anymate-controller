"""Configuration for anymate-controller.

Everything can be set with environment variables (handy for CI) or in
``~/.anymate-controller/config.json``.
"""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict

CONFIG_DIR = Path(os.environ.get("ANYMATE_CONTROLLER_HOME", Path.home() / ".anymate-controller"))
CONFIG_PATH = CONFIG_DIR / "config.json"


@dataclass
class Config:
    """Runtime configuration."""

    # AnyMate device (the MCP server inside the Android app).
    anymate_url: str = "http://127.0.0.1:8420/mcp"
    anymate_token: str = ""
    anymate_health_url: str = ""
    anymate_timeout: float = 30.0

    # AI agent (any OpenAI-compatible endpoint).
    model: str = "gpt-4o-mini"
    base_url: str = "https://api.openai.com/v1"
    api_key: str = ""
    agent_max_steps: int = 24
    agent_temperature: float = 0.6

    # Local tooling.
    workspace: str = ""
    python_executable: str = ""
    ffmpeg_binary: str = "ffmpeg"
    ffprobe_binary: str = "ffprobe"
    command_timeout: float = 120.0
    allow_shell: bool = True
    allow_python: bool = True

    # Web UI.
    host: str = "127.0.0.1"
    port: int = 8787

    extra: Dict[str, Any] = field(default_factory=dict)

    # ---- derived -----------------------------------------------------------
    @property
    def workspace_path(self) -> Path:
        path = Path(self.workspace).expanduser() if self.workspace else (CONFIG_DIR / "workspace")
        path.mkdir(parents=True, exist_ok=True)
        return path

    @property
    def python(self) -> str:
        if self.python_executable:
            return self.python_executable
        import sys

        return sys.executable or "python3"

    def health_url(self) -> str:
        if self.anymate_health_url:
            return self.anymate_health_url
        if self.anymate_url.rstrip("/").endswith("/mcp"):
            return self.anymate_url.rstrip("/")[: -len("/mcp")] + "/health"
        return self.anymate_url.rstrip("/") + "/health"

    # ---- io ----------------------------------------------------------------
    def to_dict(self) -> Dict[str, Any]:
        data = asdict(self)
        data.pop("extra", None)
        return data

    def save(self) -> Path:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        CONFIG_PATH.write_text(json.dumps(self.to_dict(), indent=2), encoding="utf-8")
        return CONFIG_PATH

    @classmethod
    def load(cls) -> "Config":
        config = cls()
        if CONFIG_PATH.exists():
            try:
                data = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                data = {}
            for key, value in data.items():
                if hasattr(config, key) and value is not None:
                    setattr(config, key, value)
        config.apply_environment()
        return config

    def apply_environment(self) -> "Config":
        mapping = {
            "ANYMATE_URL": "anymate_url",
            "ANYMATE_TOKEN": "anymate_token",
            "ANYMATE_HEALTH_URL": "anymate_health_url",
            "ANYMATE_TIMEOUT": "anymate_timeout",
            "ANYMATE_MODEL": "model",
            "ANYMATE_BASE_URL": "base_url",
            "ANYMATE_API_KEY": "api_key",
            "ANYMATE_WORKSPACE": "workspace",
            "ANYMATE_CONTROLLER_HOST": "host",
            "ANYMATE_CONTROLLER_PORT": "port",
            "ANYMATE_CONTROLLER_PYTHON": "python_executable",
            "ANYMATE_CONTROLLER_FFMPEG": "ffmpeg_binary",
            "ANYMATE_CONTROLLER_FFPROBE": "ffprobe_binary",
            "ANYMATE_CONTROLLER_TIMEOUT": "command_timeout",
        }
        for env_name, attribute in mapping.items():
            raw = os.environ.get(env_name)
            if raw is None or raw == "":
                continue
            current = getattr(self, attribute)
            if isinstance(current, bool):
                setattr(self, attribute, raw.lower() in ("1", "true", "yes", "on"))
            elif isinstance(current, int):
                setattr(self, attribute, int(raw))
            elif isinstance(current, float):
                setattr(self, attribute, float(raw))
            else:
                setattr(self, attribute, raw)
        return self


def load_config() -> Config:
    return Config.load()
