<div align="center">

# AnyMate Controller

**The brain box for the [AnyMate](https://github.com/vastobb/anymate) Android animation studio.**

An AI agent, ffmpeg, Python, a terminal and a full animation design system —
all in one local process that drives the phone over MCP and exposes the same
power to any other MCP client.

</div>

---

## Why a separate controller?

AnyMate is a *drawing instrument*: a canvas, brushes, layers, frames. It ships
an MCP server so an agent can drive it. The controller is the *studio around
the instrument*: it has a model, a shell, a Python interpreter, ffmpeg, and a
web console. Splitting them keeps the phone app fast, offline and focused,
while the heavy lifting happens on your machine.

```
┌────────────────────────┐        MCP (Streamable HTTP)        ┌──────────────────┐
│  AnyMate (Android)      │◀──────────────────────────────────▶│  anymate-controller │
│  canvas · tools · export│        http://phone:8420/mcp       │  agent · ffmpeg    │
└────────────────────────┘                                     │  python · shell    │
                                                               └──────────────────┘
                                                                     ▲
                                       MCP (stdio / HTTP)            │
                                       Claude, Cursor, VS Code ─────┘
```

## Install

```bash
git clone https://github.com/vastobb/anymate-controller.git
cd anymate-controller
python3 -m pip install -e .          # or: python3 -m anymate_controller
anymate-controller doctor
```

Python 3.9+ — **no third-party dependencies**. ffmpeg is optional but
recommended (the ffmpeg tools report a friendly message when it is missing).

## Connect to the phone

1. Open **AnyMate → Settings → MCP server** on the phone.
2. Copy the URL and the bearer token.
3. Point the controller at them:

```bash
anymate-controller config --set anymate_url=http://192.168.1.20:8420/mcp
anymate-controller config --set anymate_token=am_xxxxxxxx
anymate-controller doctor        # → anymate: reachable
```

Over USB instead of Wi-Fi:

```bash
adb reverse tcp:8420 tcp:8420
anymate-controller config --set anymate_url=http://127.0.0.1:8420/mcp
```

## Use it

```bash
# Web console: chat, tool console, live canvas preview
anymate-controller serve
# → http://127.0.0.1:8787

# One-shot agent run in the terminal
anymate-controller chat "make a bouncing ball loop on the phone at 60 fps and play it"

# Expose the whole toolbox over MCP (stdio — for Claude Desktop)
anymate-controller mcp

# …or over HTTP
anymate-controller mcp --http --port 8788

# Run a single tool by hand
anymate-controller call anim_timing --json '{"fps": 24, "seconds": 2}'

# List the toolbox
anymate-controller tools
```

### Claude Desktop / stdio clients

```json
{
  "mcpServers": {
    "anymate-controller": {
      "command": "anymate-controller",
      "args": ["mcp"],
      "env": {
        "ANYMATE_URL": "http://192.168.1.20:8420/mcp",
        "ANYMATE_TOKEN": "am_xxxxxxxx",
        "ANYMATE_API_KEY": "sk-…",
        "ANYMATE_MODEL": "gpt-4o-mini"
      }
    }
  }
}
```

## The toolbox

### AnyMate device (the phone)

| Tool | What it does |
|------|--------------|
| `anymate_status` | Is the phone reachable? Which tools does it expose? |
| `anymate_state` | Canvas size, fps, frames, layers, tool settings |
| `anymate_draw` | Shapes, strokes and text in one undoable batch |
| `anymate_frames` | Add, duplicate, delete, reorder, clear, list frames |
| `anymate_playback` | Play, pause, seek, fps, loop, onion skin |
| `anymate_layers` | Add, delete, rename, opacity, merge layers |
| `anymate_style` | Colour, tool, brush size, shape |
| `anymate_snapshot` | PNG of the live canvas — the model *sees* the art |
| `anymate_sprite_sheet` | Contact sheet of every frame in one image |
| `anymate_export` | GIF, PNG or PNG-sequence export on the device |
| `anymate_motion_preset` | Full looping animations: bounce, walk, spin, pulse, fireworks, wave, orbit, kinetic-type |

The motion presets are painted **live on the phone** in small batches, so the
artist watches the animation build instead of seeing it appear all at once.

### ffmpeg

`ffmpeg_available` · `ffmpeg_run` · `ffmpeg_probe` · `ffmpeg_convert` ·
`ffmpeg_extract_frames` · `ffmpeg_make_gif` · `ffmpeg_concat`

### Python and the terminal

`python_run` · `python_version` · `python_packages` ·
`shell_run` · `shell_list` · `shell_read` · `shell_write` · `shell_tail`

File tools are confined to `~/.anymate-controller/workspace` — nothing else on
the machine is reachable by accident.

### Animation design

`anim_palette` · `anim_palette_from_image` · `anim_easing` · `anim_timing` ·
`anim_storyboard` · `anim_sprite_sheet` · `anim_png` · `anim_svg`

The raster tools (`anim_png`, `anim_sprite_sheet`, `anim_palette_from_image`)
use a **built-in PNG codec** — no Pillow, no ImageMagick, nothing to install.

## The agent

Point it at any OpenAI-compatible endpoint:

```bash
anymate-controller config --set base_url=https://api.openai.com/v1
anymate-controller config --set model=gpt-4o-mini
anymate-controller config --set api_key=sk-...
```

Works with OpenAI, OpenRouter, Groq, Ollama (`http://localhost:11434/v1`),
LM Studio, vLLM and anything else that speaks `/chat/completions`.

The agent sees tool results *as images* when a tool returns a picture, so it
can look at the canvas it just drew and correct itself.

## Configuration

| Variable | Setting | Default |
|----------|---------|---------|
| `ANYMATE_URL` | `anymate_url` | `http://127.0.0.1:8420/mcp` |
| `ANYMATE_TOKEN` | `anymate_token` | empty |
| `ANYMATE_MODEL` | `model` | `gpt-4o-mini` |
| `ANYMATE_BASE_URL` | `base_url` | `https://api.openai.com/v1` |
| `ANYMATE_API_KEY` | `api_key` | empty |
| `ANYMATE_WORKSPACE` | `workspace` | `~/.anymate-controller/workspace` |
| `ANYMATE_CONTROLLER_PORT` | `port` | `8787` |
| `ANYMATE_CONTROLLER_TIMEOUT` | `command_timeout` | `120` |

Settings live in `~/.anymate-controller/config.json`.

## Development

```bash
python3 tests/smoke.py        # dependency-free end-to-end check
python3 -m pytest -q          # full suite (needs pytest)
```

## License

MIT
