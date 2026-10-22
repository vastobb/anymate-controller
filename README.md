<div align="center">

<img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="96" alt="AnyMate Controller logo" />

# AnyMate Controller

**The brain box for the [AnyMate](https://github.com/vastobb/anymate) animation studio — as an Android app.**

An AI director, a Python runtime, a terminal, video encoding and a full
animation design system, all on the phone, all talking to AnyMate over MCP.

</div>

---

## What it does

| | |
|---|---|
| **AI director** | Chat with any OpenAI-compatible endpoint (OpenAI, OpenRouter, Groq, Ollama, LM Studio…). The model gets every tool below and can see the phone's canvas as it draws. |
| **Resilient tool calls** | Some gateways reject the standard tool-result format with a 404 ("Function … not found for account …"). The controller detects that, flattens the tool turns and retries, then falls back to plain text — a bad gateway costs a retry, not the conversation. **Test endpoint** in Settings isolates URL / key / model problems. |
| **Model auto-fetch** | Enter the endpoint and API key, press **Fetch models**. The list comes from `GET /models` and one tap selects a model. |
| **Chat attachments** | Attach an image, a video (frames are sampled on device) or the live canvas to any message for vision models. |
| **Image → art** | `anim_trace_image` turns a picture into vector strokes on the phone (Sobel edges → contours → simplified polylines), or places it as a bitmap. `detail` controls how many lines survive. |
| **Video → animation** | `anim_video_to_animation` samples a video into frames and rebuilds it on the phone as a bitmap sequence or as traced line art, then `anim_edit_animation` retimes, reverses, holds, tints, flips or tweens it. |
| **Phone tools** | Draw, animate, layer, play, snapshot, sprite-sheet and export on the AnyMate app — one undo step per batch, live on the artist's canvas. |
| **Python** | A compact Python 3 subset that runs on device: arithmetic, strings and f-strings, lists/dicts/sets, slicing, comprehensions, functions, classes of builtins and the `math`, `random`, `time` and `os` modules. |
| **Terminal** | `ls`, `cat`, `cd`, `mkdir`, `rm`, `cp`, `mv`, `grep`, `find`, `tree`, `wc`, `head`, `tail`, `du`, `stat` — with pipes (`a | b`), redirection (`> file`) and quoting, sandboxed to a workspace folder. |
| **Video** | Animated GIF and H.264 MP4 encoding, plus probing and frame extraction — the ffmpeg-style jobs an animation workflow needs, done on the phone. |
| **Animation design** | Palettes from a base colour, easing curves, timing plans (on-ones/on-twos/on-threes), storyboards, sprite sheets and generated PNGs. |
| **Files** | Browse, read and write the workspace from the app. |

## Install

Download the APK from the [releases](https://github.com/vastobb/anymate-controller/releases) and
sideload it, or let CI build it for you:

```bash
git clone https://github.com/vastobb/anymate-controller.git
cd anymate-controller
./gradlew :app:assembleDebug
```

## Connect it to AnyMate

1. On the phone, open **AnyMate → Settings → MCP server** and copy the URL and token.
2. In the Controller, open **Settings**, paste them in and press **Save & connect**.
3. The **AnyMate canvas** pane on the right shows the phone's live canvas.

Over USB instead of Wi-Fi:

```bash
adb reverse tcp:8420 tcp:8420
# MCP URL: http://127.0.0.1:8420/mcp
```

## Use it

* **Director** — chat. Try *"make a 60 fps walk cycle on the phone and play it"*.
* **Console** — a terminal. `help`, `tree`, `py print(6*7)`, `mcp anymate_get_state {}`.
* **Files** — the workspace, readable from the app.
* **Settings** — phone address, model endpoint/key, **Fetch models**, **Test endpoint**, and the full tool list.

Attachments live under the composer: **Image**, **Video**, **Canvas**. A video is sampled
on device and sent as a handful of representative frames, so vision models can read it.

## Motion presets

The controller carries the motion systems that used to live in the phone app —
`bounce`, `walk`, `spin`, `pulse`, `fireworks`, `wave`, `orbit`, `kinetic-type`.
Each one paints a complete loop on the phone **live, in small batches**, so the
artist watches the animation build instead of seeing it appear all at once.

## Optional desktop runner

`desktop/` holds the original Python controller: the same toolbox for a
workstation, where real ffmpeg and a real Python live. It speaks MCP too, so
Claude or Cursor can drive the phone *and* the desktop from one place.

```bash
cd desktop
python -m pip install -e ".[dev]"
anymate-controller serve
```

## Development

```bash
./gradlew :app:testDebugUnitTest     # on-device Python + shell unit tests
cd desktop && python tests/smoke.py   # dependency-free desktop check
```

## License

MIT
