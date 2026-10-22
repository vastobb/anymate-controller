"""The AI agent that lives in the controller.

Talks to any OpenAI-compatible chat-completions endpoint and hands it the
controller toolbox: ffmpeg, python, the terminal, the animation design tools
and the AnyMate device. Images produced by tools (canvas snapshots, sprite
sheets) are fed back to the model so it can actually look at its work.
"""

from __future__ import annotations

import asyncio
import base64
import json
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from .tools.registry import ToolRegistry, ToolResult

SYSTEM_PROMPT = """
You are the director of AnyMate Controller — a workstation brain box for the
AnyMate Android animation studio.

You drive three things at once:
* AnyMate on a phone (draw, animate, play, export) over MCP.
* ffmpeg on this machine (probe, convert, extract frames, build GIFs, join clips).
* Python and the terminal on this machine.

How to work:
1. Call anymate_status first to see whether the phone is reachable.
2. For drawing, prefer anymate_draw (one call, many shapes) or
   anymate_motion_preset for a complete loop. Keep every sequence to one
   undo step on the phone.
3. Look at your work with anymate_snapshot or anymate_sprite_sheet. The images
   come back to you as pictures — use them to check composition and timing.
4. For files and video, use the ffmpeg and python tools, and verify the result
   with ffprobe or by reading the output file.
5. Be concrete: report file paths, frame counts, durations and what changed.
6. Never invent tool results. If a tool fails, say so and try another route.

The user's request may be about drawing, about a video pipeline, or about both.
"""


@dataclass
class AgentReply:
    """The result of one agent turn."""

    text: str
    steps: int = 0
    tool_calls: List[Dict[str, Any]] = field(default_factory=list)
    images: List[bytes] = field(default_factory=list)
    error: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "text": self.text,
            "steps": self.steps,
            "tool_calls": self.tool_calls,
            "error": self.error,
        }


class AgentError(RuntimeError):
    """Raised when the model endpoint cannot be reached."""


class Agent:
    """An OpenAI-compatible tool-calling agent."""

    def __init__(self, config, registry: ToolRegistry) -> None:
        self.config = config
        self.registry = registry

    # ---- plumbing ----------------------------------------------------------
    def endpoint(self) -> str:
        base = self.config.base_url.strip().rstrip("/")
        if base.endswith("/chat/completions"):
            return base
        if "/" not in base.split("://", 1)[-1]:
            base = f"{base}/v1"
        return f"{base}/chat/completions"

    def _post(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        body = json.dumps(payload).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.config.api_key:
            headers["Authorization"] = f"Bearer {self.config.api_key}"
        request = urllib.request.Request(self.endpoint(), data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                return json.loads(response.read().decode("utf-8", errors="replace"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise AgentError(f"HTTP {exc.code} from the model endpoint: {detail[:400]}") from exc
        except urllib.error.URLError as exc:
            raise AgentError(f"Cannot reach the model endpoint: {exc.reason}") from exc
        except ValueError as exc:
            raise AgentError(f"The model endpoint returned invalid JSON: {exc}") from exc

    # ---- conversation ------------------------------------------------------
    async def run(
        self,
        prompt: str,
        history: Optional[List[Dict[str, Any]]] = None,
        on_event: Optional[Any] = None,
    ) -> AgentReply:
        """Run one agent turn, looping until the model stops asking for tools."""

        messages: List[Dict[str, Any]] = [{"role": "system", "content": SYSTEM_PROMPT}]
        for message in history or []:
            messages.append(message)
        messages.append({"role": "user", "content": prompt})

        tool_calls: List[Dict[str, Any]] = []
        images: List[bytes] = []
        steps = 0
        max_steps = max(1, self.config.agent_max_steps)
        image_budget = 2

        while steps < max_steps:
            steps += 1
            if on_event:
                on_event({"type": "thinking", "step": steps})
            payload = {
                "model": self.config.model,
                "messages": messages,
                "tools": self.registry.openai_tools(),
                "tool_choice": "auto",
                "temperature": self.config.agent_temperature,
                "max_tokens": 4096,
            }
            try:
                response = await asyncio.to_thread(self._post, payload)
            except AgentError as exc:
                return AgentReply(text="", steps=steps, tool_calls=tool_calls, error=str(exc))
            if "error" in response:
                message = response["error"].get("message", json.dumps(response["error"]))
                return AgentReply(text="", steps=steps, tool_calls=tool_calls, error=f"Model error: {message}")

            choices = response.get("choices") or []
            if not choices:
                return AgentReply(text="", steps=steps, tool_calls=tool_calls, error="The model returned no choices.")
            message = choices[0].get("message", {}) or {}
            content = message.get("content") or ""
            calls = message.get("tool_calls") or []

            if not calls:
                return AgentReply(
                    text=content.strip() or "Done.",
                    steps=steps,
                    tool_calls=tool_calls,
                    images=images,
                )

            assistant_message: Dict[str, Any] = {"role": "assistant", "content": content}
            assistant_message["tool_calls"] = calls
            messages.append(assistant_message)

            for call in calls:
                function = call.get("function", {}) or {}
                name = function.get("name", "")
                raw_arguments = function.get("arguments") or "{}"
                try:
                    arguments = json.loads(raw_arguments) if isinstance(raw_arguments, str) else raw_arguments
                except ValueError:
                    arguments = {}
                call_id = call.get("id") or f"call_{steps}_{len(tool_calls)}"
                if on_event:
                    on_event({"type": "tool", "name": name, "arguments": arguments})
                result = await self.registry.call(name, arguments)
                tool_calls.append({"name": name, "arguments": arguments, "result": result.text[:2000]})
                if result.image:
                    images.append(result.image)
                messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": call_id,
                        "content": result.text[:8000] or f"{name} finished.",
                    }
                )
                if result.image and image_budget > 0:
                    image_budget -= 1
                    encoded = base64.b64encode(result.image).decode("ascii")
                    messages.append(
                        {
                            "role": "user",
                            "content": [
                                {
                                    "type": "text",
                                    "text": f"Visual result of {name}. Check it before continuing.",
                                },
                                {
                                    "type": "image_url",
                                    "image_url": {"url": f"data:{result.mime};base64,{encoded}"},
                                },
                            ],
                        }
                    )
        return AgentReply(
            text="I reached the step limit. The work so far is saved — ask me to continue.",
            steps=steps,
            tool_calls=tool_calls,
            images=images,
        )
