package com.anymate.controller.agent

import android.util.Base64
import com.anymate.controller.tools.ToolRegistry
import com.anymate.controller.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

data class AgentReply(
    val text: String = "",
    val steps: Int = 0,
    val toolCalls: List<String> = emptyList(),
    val error: String? = null
)

data class AgentEvent(val kind: String, val label: String)

/**
 * OpenAI-compatible tool-calling agent. Works with OpenAI, OpenRouter, Groq,
 * Ollama, LM Studio and anything else that speaks /chat/completions.
 */
class Agent(
    private val endpoint: String,
    private val model: String,
    private val apiKey: String,
    private val registry: ToolRegistry,
    private val sendImages: Boolean = false,
    private val maxSteps: Int = 16
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun chatUrl(): String {
        val base = endpoint.trim().trimEnd('/')
        if (base.endsWith("/chat/completions")) return base
        if ("/" !in base.substringAfter("://", "")) return "$base/v1/chat/completions"
        return "$base/chat/completions"
    }

    private suspend fun post(payload: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val connection = (URL(chatUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("HTTP $status: ${text.take(300)}")
            json.parseToJsonElement(text).jsonObject
        } finally {
            connection.disconnect()
        }
    }

    suspend fun run(
        prompt: String,
        history: List<JsonObject> = emptyList(),
        onEvent: (AgentEvent) -> Unit = {}
    ): AgentReply {
        val messages = ArrayList<JsonObject>()
        messages.add(buildJsonObject { put("role", "system"); put("content", SYSTEM_PROMPT) })
        messages.addAll(history)
        messages.add(buildJsonObject { put("role", "user"); put("content", prompt) })

        val calls = ArrayList<String>()
        var steps = 0
        while (steps < maxSteps) {
            steps++
            onEvent(AgentEvent("thinking", "step $steps"))
            val payload = buildJsonObject {
                put("model", model)
                put("messages", JsonArray(messages))
                put("tools", registry.openaiTools())
                put("tool_choice", "auto")
                put("temperature", 0.6)
                put("max_tokens", 4096)
            }
            val response = try {
                post(payload)
            } catch (t: Throwable) {
                return AgentReply(steps = steps, toolCalls = calls, error = t.message ?: "model error")
            }
            response["error"]?.let { error ->
                val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
                return AgentReply(steps = steps, toolCalls = calls, error = "Model error: $message")
            }
            val choices = response["choices"]?.jsonArray ?: JsonArray(emptyList())
            if (choices.isEmpty()) {
                return AgentReply(steps = steps, toolCalls = calls, error = "The model returned no choices.")
            }
            val message = choices[0].jsonObject["message"]?.jsonObject ?: JsonObject(emptyMap())
            val content = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val toolCalls = message["tool_calls"]?.jsonArray ?: JsonArray(emptyList())

            if (toolCalls.isEmpty()) {
                return AgentReply(
                    text = content.trim().ifBlank { "Done." },
                    steps = steps,
                    toolCalls = calls
                )
            }

            messages.add(
                buildJsonObject {
                    put("role", "assistant")
                    put("content", content)
                    put("tool_calls", toolCalls)
                }
            )

            for (element in toolCalls) {
                val call = element.jsonObject
                val function = call["function"]?.jsonObject ?: JsonObject(emptyMap())
                val name = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val rawArguments = function["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                val arguments = runCatching { json.parseToJsonElement(rawArguments).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
                onEvent(AgentEvent("tool", name))
                val result = registry.call(name, arguments)
                calls.add(name)
                messages.add(
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", call["id"]?.jsonPrimitive?.contentOrNull ?: "call_${calls.size}")
                        put("content", result.text.take(6000).ifBlank { "$name finished." })
                    }
                )
                if (result.image != null && sendImages) {
                    val encoded = Base64.encodeToString(result.image, Base64.NO_WRAP)
                    messages.add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", "Visual result of $name:")
                                        }
                                    )
                                    add(
                                        buildJsonObject {
                                            put("type", "image_url")
                                            put(
                                                "image_url",
                                                buildJsonObject { put("url", "data:${result.mime};base64,$encoded") }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            }
        }
        return AgentReply(
            text = "I reached the step limit. The work so far is done — ask me to continue.",
            steps = steps,
            toolCalls = calls
        )
    }

    companion object {
        val SYSTEM_PROMPT = """
You are the director of AnyMate Controller, a studio brain box for the AnyMate Android animation app.

You control:
* the phone (drawing, frames, layers, playback, export) over MCP — the anymate.* tools;
* this device: ffmpeg-style video work (video.*), a Python runtime (py.exec), a terminal (sh.exec),
  an animation design system (anim.*) and the file system (files.*).

How to work:
1. Check anymate_status first — if the phone is offline, say so and continue with local work.
2. Draw with anymate_draw (one call, many shapes) or anymate_motion_preset for a whole loop.
   Every phone mutation is visible to the artist immediately, so keep sequences to one undo step.
3. Look at your work with anymate_snapshot / anymate_sprite_sheet.
4. Use py.exec for calculations, palettes and data wrangling; sh.exec for files and tooling;
   video.gif / video.mp4 to render; anim.* for timing, easing and colour.
5. Be concrete: paths, frame counts, durations, what changed. Never invent results.
""".trimIndent()
    }
}
