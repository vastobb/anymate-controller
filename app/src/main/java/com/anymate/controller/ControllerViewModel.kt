package com.anymate.controller

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anymate.controller.agent.Agent
import com.anymate.controller.agent.AgentEvent
import com.anymate.controller.mcp.McpClient
import com.anymate.controller.tools.AnimTools
import com.anymate.controller.tools.AnyMateTools
import com.anymate.controller.tools.FileTools
import com.anymate.controller.tools.PythonLite
import com.anymate.controller.tools.ShellLite
import com.anymate.controller.tools.ToolRegistry
import com.anymate.controller.tools.ToolResult
import com.anymate.controller.tools.VideoTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class LineRole { USER, AGENT, TOOL, SYSTEM }

data class ChatLine(
    val id: Long,
    val role: LineRole,
    val text: String,
    val at: Long = System.currentTimeMillis()
)

class ControllerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("anymate.controller", Application.MODE_PRIVATE)

    val workspace: File = File(app.filesDir, "workspace").apply { mkdirs() }

    var anymateUrl by mutableStateOf(prefs.getString("anymate.url", "") ?: "")
    var anymateToken by mutableStateOf(prefs.getString("anymate.token", "") ?: "")
    var modelEndpoint by mutableStateOf(prefs.getString("model.endpoint", "https://api.openai.com/v1") ?: "")
    var modelName by mutableStateOf(prefs.getString("model.name", "gpt-4o-mini") ?: "")
    var apiKey by mutableStateOf(prefs.getString("model.key", "") ?: "")
    var sendImages by mutableStateOf(prefs.getBoolean("model.images", false))

    var status by mutableStateOf("Ready")
        private set
    var thinking by mutableStateOf(false)
        private set
    var connected by mutableStateOf(false)
        private set
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    var consoleText by mutableStateOf("")
        private set
    var files by mutableStateOf<List<String>>(emptyList())
        private set

    val chat = mutableStateListOf<ChatLine>()
    val registry = ToolRegistry()

    private var client: McpClient? = null
    private var agentJob: Job? = null
    private var previewJob: Job? = null
    private var chatId = 0L

    init {
        registerTools()
        chat.add(
            ChatLine(
                ++chatId,
                LineRole.AGENT,
                "AnyMate Controller is ready. Point me at the phone (Settings), then ask for an animation — " +
                    "or use the Console for Python, shell, video and animation tools right away."
            )
        )
        refreshFiles()
    }

    // ---- configuration -----------------------------------------------------
    fun saveSettings() {
        prefs.edit()
            .putString("anymate.url", anymateUrl)
            .putString("anymate.token", anymateToken)
            .putString("model.endpoint", modelEndpoint)
            .putString("model.name", modelName)
            .putString("model.key", apiKey)
            .putBoolean("model.images", sendImages)
            .apply()
        client = null
        connected = false
    }

    fun mcpClient(): McpClient {
        val existing = client
        if (existing != null) return existing
        val created = McpClient(anymateUrl.trim(), anymateToken.trim())
        client = created
        return created
    }

    fun connect() {
        viewModelScope.launch {
            status = "Connecting to AnyMate…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    mcpClient().initialize()
                    mcpClient().health()
                }
            }
            result.onSuccess {
                connected = true
                status = "AnyMate connected"
                chat.add(ChatLine(++chatId, LineRole.SYSTEM, "Connected to AnyMate."))
                startPreviewLoop()
            }.onFailure {
                connected = false
                status = "AnyMate offline"
                chat.add(ChatLine(++chatId, LineRole.SYSTEM, "Could not reach AnyMate: ${it.message}"))
            }
        }
    }

    // ---- chat -------------------------------------------------------------
    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || thinking) return
        chat.add(ChatLine(++chatId, LineRole.USER, prompt))
        thinking = true
        status = "Thinking…"
        agentJob = viewModelScope.launch {
            try {
                val agent = Agent(
                    endpoint = modelEndpoint,
                    model = modelName,
                    apiKey = apiKey,
                    registry = registry,
                    sendImages = sendImages
                )
                val reply = agent.run(prompt) { event: AgentEvent ->
                    when (event.kind) {
                        "tool" -> {
                            status = "Running ${event.label}"
                            chat.add(ChatLine(++chatId, LineRole.TOOL, "→ ${event.label}"))
                        }
                        else -> status = "Thinking…"
                    }
                }
                if (reply.error != null) {
                    chat.add(ChatLine(++chatId, LineRole.AGENT, reply.error, ))
                } else {
                    chat.add(ChatLine(++chatId, LineRole.AGENT, reply.text))
                }
            } catch (t: Throwable) {
                chat.add(ChatLine(++chatId, LineRole.AGENT, "Error: ${t.message}"))
            } finally {
                thinking = false
                status = if (connected) "AnyMate connected" else "Ready"
            }
        }
    }

    fun stopAgent() {
        agentJob?.cancel()
        agentJob = null
        thinking = false
        status = "Stopped"
    }

    // ---- console -----------------------------------------------------------
    fun runConsole(command: String) {
        val line = command.trim()
        if (line.isEmpty()) return
        consoleText += "\$ $line\n"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                when {
                    line.startsWith("py ") || line.startsWith("python ") ->
                        PythonLite.run(workspace, line.substringAfter(' '))
                    line.startsWith("mcp ") -> runMcpCommand(line.removePrefix("mcp ").trim())
                    else -> ShellLite.run(workspace, line)
                }
            }
            consoleText += result.stdout
            if (result.stderr.isNotBlank()) consoleText += result.stderr
            consoleText += "\n"
            refreshFiles()
        }
    }

    private suspend fun runMcpCommand(payload: String): ShellResult {
        val name = payload.substringBefore(' ').trim()
        val json = payload.substringAfter(' ', "").trim()
        return try {
            val arguments = if (json.isBlank()) {
                kotlinx.serialization.json.JsonObject(emptyMap())
            } else {
                kotlinx.serialization.json.Json.parseToJsonElement(json).let {
                    (it as? kotlinx.serialization.json.JsonObject) ?: kotlinx.serialization.json.JsonObject(emptyMap())
                }
            }
            val result = mcpClient().callTool(name, arguments)
            ShellResult(
                stdout = result.text + (result.image?.let { "\n[image ${it.size} bytes]" } ?: ""),
                stderr = if (result.isError) "tool reported an error\n" else "",
                exitCode = if (result.isError) 1 else 0
            )
        } catch (t: Throwable) {
            ShellResult(stderr = "${t.message}\n", exitCode = 1)
        }
    }

    fun clearConsole() {
        consoleText = ""
    }

    // ---- files -------------------------------------------------------------
    fun refreshFiles() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                workspace.walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(workspace).path }
                    .sorted()
                    .take(300)
            }
            files = list
        }
    }

    fun readFile(path: String) {
        runConsole("cat \"$path\"")
    }

    // ---- preview -----------------------------------------------------------
    private fun startPreviewLoop() {
        if (previewJob?.isActive == true) return
        previewJob = viewModelScope.launch {
            while (true) {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { mcpClient().snapshotPng() }.getOrNull()
                }
                if (bytes != null) {
                    val bitmap = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    if (bitmap != null) preview = bitmap.asImageBitmap()
                }
                delay(2200)
            }
        }
    }

    // ---- tools -------------------------------------------------------------
    private fun registerTools() {
        val registry = this.registry

        registry.register(
            "py_exec",
            "Run a Python 3 snippet in the built-in on-device Python runtime. " +
                "Supports arithmetic, strings, f-strings, lists, dicts, loops, functions, comprehensions, " +
                "and the math/random/time/os modules.",
            ToolRegistry.obj("code" to ToolRegistry.str("Python source")),
            "python"
        ) { args ->
            val code = args["code"]?.toString()?.trim('"') ?: ""
            val result = PythonLite.run(workspace, code)
            ToolResult(text = result.stdout, ok = result.exitCode == 0)
        }

        registry.register(
            "sh_exec",
            "Run a terminal command inside the controller workspace. Supports pipes, redirection " +
                "and the usual file commands.",
            ToolRegistry.obj("command" to ToolRegistry.str("Command line")),
            "terminal"
        ) { args ->
            val command = args["command"]?.toString()?.trim('"') ?: ""
            val result = ShellLite.run(workspace, command)
            ToolResult(
                text = (result.stdout + result.stderr).trimEnd().ifBlank { "(no output)" },
                ok = result.exitCode == 0
            )
        }

        registry.register(
            "anim_palette",
            "Build a harmonious colour palette from a base colour.",
            ToolRegistry.obj(
                "base" to ToolRegistry.str("Base colour as #RRGGBB"),
                "scheme" to ToolRegistry.str("split | complementary | triadic | analogous | monochrome"),
                "count" to ToolRegistry.int("How many colours")
            ),
            "animation"
        ) { args ->
            val colors = AnimTools.palette(
                args["base"]?.toString()?.trim('"') ?: "#7C3AED",
                args["scheme"]?.toString()?.trim('"') ?: "split",
                args["count"]?.toIntOrNull() ?: 6
            )
            ToolResult(text = colors.joinToString("\n"))
        }

        registry.register(
            "anim_easing",
            "Sample an easing curve for timing animation.",
            ToolRegistry.obj(
                "name" to ToolRegistry.str("linear | ease-in | ease-out | ease-in-out | sine | expo-in | expo-out | back-out | bounce | elastic-out"),
                "count" to ToolRegistry.int("How many samples")
            ),
            "animation"
        ) { args ->
            val name = args["name"]?.toString()?.trim('"') ?: "ease-in-out"
            val values = AnimTools.easing(name, args["count"]?.toIntOrNull() ?: 12)
                ?: return@register ToolResult(text = "Unknown easing '$name'.", ok = false)
            ToolResult(text = values.joinToString(", "))
        }

        registry.register(
            "anim_timing",
            "Turn a duration into a frame plan (on-ones, on-twos, on-threes).",
            ToolRegistry.obj(
                "fps" to ToolRegistry.int("Frames per second"),
                "seconds" to ToolRegistry.num("Length of the shot"),
                "style" to ToolRegistry.str("on-ones | on-twos | on-threes"),
                "hold" to ToolRegistry.int("Frames to hold each drawing")
            ),
            "animation"
        ) { args ->
            val plan = AnimTools.timing(
                args["fps"]?.toIntOrNull() ?: 24,
                args["seconds"]?.toDoubleOrNull() ?: 2.0,
                args["style"]?.toString()?.trim('"') ?: "on-twos",
                args["hold"]?.toIntOrNull() ?: 2
            )
            ToolResult(text = plan.toString())
        }

        registry.register(
            "anim_storyboard",
            "Turn a list of beats into a frame-by-frame storyboard plan.",
            ToolRegistry.obj(
                "beats" to ToolRegistry.str("One beat per line"),
                "fps" to ToolRegistry.int("Frames per second for the plan")
            ),
            "animation"
        ) { args ->
            val beats = args["beats"]?.toString()?.trim('"')?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
            val plan = AnimTools.storyboard(beats, args["fps"]?.toIntOrNull() ?: 12)
            ToolResult(text = plan.toString())
        }

        registry.register(
            "anim_sheet",
            "Pack PNG frames into a sprite sheet.",
            ToolRegistry.obj(
                "frame_paths" to ToolRegistry.strArray("Frame images in the workspace"),
                "output" to ToolRegistry.str("Output PNG"),
                "columns" to ToolRegistry.int("Frames per row")
            ),
            "animation"
        ) { args ->
            val paths = (args["frame_paths"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.toString().trim('"') } ?: emptyList()
            AnimTools.spriteSheet(
                workspace,
                paths,
                args["output"]?.toString()?.trim('"') ?: "sheet.png",
                args["columns"]?.toIntOrNull() ?: 6
            )
        }

        registry.register(
            "anim_png",
            "Generate a simple PNG frame (flat colour, ellipse or gradient).",
            ToolRegistry.obj(
                "output" to ToolRegistry.str("Output PNG"),
                "width" to ToolRegistry.int("Width in pixels"),
                "height" to ToolRegistry.int("Height in pixels"),
                "background" to ToolRegistry.str("Background colour"),
                "accent" to ToolRegistry.str("Accent colour"),
                "shape" to ToolRegistry.str("flat | ellipse | gradient")
            ),
            "animation"
        ) { args ->
            AnimTools.generatePng(
                workspace,
                args["output"]?.toString()?.trim('"') ?: "frame.png",
                args["width"]?.toIntOrNull() ?: 900,
                args["height"]?.toIntOrNull() ?: 900,
                args["background"]?.toString()?.trim('"') ?: "#F4F5F7",
                args["accent"]?.toString()?.trim('"') ?: "#7C3AED",
                args["shape"]?.toString()?.trim('"') ?: "flat"
            )
        }

        registry.register(
            "video_gif",
            "Build an animated GIF from PNG frames on the device.",
            ToolRegistry.obj(
                "frame_paths" to ToolRegistry.strArray("Frame images"),
                "output" to ToolRegistry.str("Output GIF"),
                "fps" to ToolRegistry.int("Frames per second"),
                "width" to ToolRegistry.int("Width in pixels")
            ),
            "video"
        ) { args ->
            val paths = (args["frame_paths"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.toString().trim('"') } ?: emptyList()
            VideoTools.gif(
                workspace,
                paths,
                args["output"]?.toString()?.trim('"') ?: "animation.gif",
                args["fps"]?.toIntOrNull() ?: 12,
                args["width"]?.toIntOrNull() ?: 640
            )
        }

        registry.register(
            "video_mp4",
            "Encode PNG frames into an H.264 MP4 on the device.",
            ToolRegistry.obj(
                "frame_paths" to ToolRegistry.strArray("Frame images"),
                "output" to ToolRegistry.str("Output MP4"),
                "fps" to ToolRegistry.int("Frames per second"),
                "bitrate" to ToolRegistry.int("Bitrate")
            ),
            "video"
        ) { args ->
            val paths = (args["frame_paths"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.toString().trim('"') } ?: emptyList()
            VideoTools.mp4(
                workspace,
                paths,
                args["output"]?.toString()?.trim('"') ?: "animation.mp4",
                args["fps"]?.toIntOrNull() ?: 30,
                args["bitrate"]?.toIntOrNull() ?: 4_000_000
            )
        }

        registry.register(
            "video_probe",
            "Inspect a video file on the device: size, duration, frame rate.",
            ToolRegistry.obj("path" to ToolRegistry.str("Video path")),
            "video"
        ) { args ->
            VideoTools.probe(args["path"]?.toString()?.trim('"') ?: "")
        }

        registry.register(
            "video_frames",
            "Extract frames from a video on the device.",
            ToolRegistry.obj(
                "path" to ToolRegistry.str("Video path"),
                "output_dir" to ToolRegistry.str("Where to write the frames"),
                "fps" to ToolRegistry.int("Frames per second to sample"),
                "count" to ToolRegistry.int("Maximum frames")
            ),
            "video"
        ) { args ->
            VideoTools.extractFrames(
                args["path"]?.toString()?.trim('"') ?: "",
                args["output_dir"]?.toString()?.trim('"') ?: "frames",
                args["fps"]?.toIntOrNull() ?: 12,
                args["count"]?.toIntOrNull() ?: 60
            )
        }

        FileTools.register(registry, workspace)
        AnyMateTools.register(registry, { mcpClient() }, workspace)
    }
}
