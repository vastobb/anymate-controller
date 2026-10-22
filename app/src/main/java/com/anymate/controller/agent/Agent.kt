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

/** An image (or a video frame) that can be attached to a chat message. */
data class Attachment(
    val bytes: ByteArray,
    val mime: String = "image/png",
    val label: String = "image"
)

/**
 * OpenAI-compatible tool-calling agent.
 *
 * Providers disagree about how tool results are echoed back, so the loop is
 * defensive: when a gateway rejects a tool turn ("Function '…': Not found",
 * 404, unknown tool id) the conversation is sanitised and retried as plain
 * text, and finally retried without tools at all. The model still gets the
 * tool output, it just is not sent through the provider's tool protocol.
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
            if (status !in 200..299) {
                throw AgentException(status, text)
            }
            json.parseToJsonElement(text).jsonObject
        } finally {
            connection.disconnect()
        }
    }

    suspend fun run(
        prompt: String,
        attachments: List<Attachment> = emptyList(),
        history: List<JsonObject> = emptyList(),
        onEvent: (AgentEvent) -> Unit = {}
    ): AgentReply {
        val messages = ArrayList<JsonObject>()
        messages.add(buildJsonObject { put("role", "system"); put("content", SYSTEM_PROMPT) })
        messages.addAll(history)
        messages.add(userMessage(prompt, attachments))

        val calls = ArrayList<String>()
        var steps = 0
        var sanitized = false
        var droppedTools = false

        while (steps < maxSteps) {
            steps++
            onEvent(AgentEvent("thinking", "step $steps"))
            val payload = buildJsonObject {
                put("model", model)
                put("messages", JsonArray(messages))
                if (!droppedTools) {
                    put("tools", registry.openaiTools())
                    put("tool_choice", "auto")
                }
                put("temperature", 0.6)
                put("max_tokens", 4096)
            }

            val response = try {
                post(payload)
            } catch (error: AgentException) {
                if (!sanitized && isRecoverable(error)) {
                    // The provider does not like our tool protocol: flatten the
                    // tool turns into plain text and try again.
                    sanitized = true
                    messages.replaceAll { flattenToolTurns(it) }
                    continue
                }
                if (!droppedTools && isRecoverable(error)) {
                    // Still unhappy: continue without tools at all.
                    droppedTools = true
                    sanitized = true
                    messages.replaceAll { flattenToolTurns(it) }
                    continue
                }
                return AgentReply(steps = steps, toolCalls = calls, error = describe(error))
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
            val legacyCall = message["function_call"]?.jsonObject

            if (toolCalls.isEmpty() && legacyCall == null) {
                return AgentReply(text = content.trim().ifBlank { "Done." }, steps = steps, toolCalls = calls)
            }

            messages.add(
                buildJsonObject {
                    put("role", "assistant")
                    put("content", content)
                    if (toolCalls.isNotEmpty()) put("tool_calls", toolCalls)
                    if (legacyCall != null) put("function_call", legacyCall)
                }
            )

            val pending = ArrayList<Pair<String, String>>() // id -> name
            if (toolCalls.isNotEmpty()) {
                for (element in toolCalls) {
                    val call = element.jsonObject
                    val function = call["function"]?.jsonObject ?: JsonObject(emptyMap())
                    val id = call["id"]?.jsonPrimitive?.contentOrNull ?: "call_${calls.size}"
                    val name = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    pending.add(id to name)
                }
            } else if (legacyCall != null) {
                pending.add("call_legacy" to legacyCall["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }

            for ((id, rawName) in pending) {
                val name = resolveToolName(rawName, pending)
                if (name == null) {
                    messages.add(
                        buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", id)
                            put("content", "That tool is not available.")
                        }
                    )
                    continue
                }
                val rawArguments = pending.first { it.first == id }.let {
                    toolCalls.firstOrNull { element ->
                        element.jsonObject["id"]?.jsonPrimitive?.contentOrNull == id
                    }?.jsonObject?.get("function")?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull
                } ?: "{}"
                val arguments = runCatching { json.parseToJsonElement(rawArguments).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
                onEvent(AgentEvent("tool", name))
                val result = registry.call(name, arguments)
                calls.add(name)
                messages.add(
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", id)
                        put("name", name)
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

    /** Some gateways swap the tool name for an id; map it back when we can. */
    private fun resolveToolName(rawName: String, pending: List<Pair<String, String>>): String? {
        if (rawName.isNotBlank() && registry.get(rawName) != null) return rawName
        val byId = pending.firstOrNull { it.first == rawName }?.second
        if (byId != null && registry.get(byId) != null) return byId
        // A single tool call with an opaque id: fall back to the only known tool.
        if (pending.size == 1) {
            val candidate = pending[0].second
            if (registry.get(candidate) != null) return candidate
        }
        return null
    }

    private fun userMessage(prompt: String, attachments: List<Attachment>): JsonObject {
        if (attachments.isEmpty()) {
            return buildJsonObject {
                put("role", "user")
                put("content", prompt)
            }
        }
        return buildJsonObject {
            put("role", "user")
            put(
                "content",
                buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", prompt) })
                    for (attachment in attachments) {
                        add(
                            buildJsonObject {
                                put("type", "image_url")
                                put(
                                    "image_url",
                                    buildJsonObject {
                                        put(
                                            "url",
                                            "data:${attachment.mime};base64," +
                                                Base64.encodeToString(attachment.bytes, Base64.NO_WRAP)
                                        )
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }
    }

    /** Turn tool protocol messages into plain text so a picky gateway accepts them. */
    private fun flattenToolTurns(message: JsonObject): JsonObject {
        val role = message["role"]?.jsonPrimitive?.contentOrNull
        if (role == "tool") {
            val content = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return buildJsonObject {
                put("role", "user")
                put("content", "[tool output]\n$content")
            }
        }
        if (role == "assistant" && message.containsKey("tool_calls")) {
            val content = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return buildJsonObject {
                put("role", "assistant")
                put("content", content.ifBlank { "(tool call)" })
            }
        }
        return message
    }

    private fun isRecoverable(error: AgentException): Boolean {
        val text = (error.body + " " + error.message).lowercase()
        return error.status == 404 || error.status == 400 &&
            (text.contains("not found") || text.contains("function") || text.contains("tool"))
    }

    private fun describe(error: AgentException): String {
        val body = runCatching { json.parseToJsonElement(error.body).jsonObject } .getOrNull()
        val detail = body?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: body?.get("detail")?.jsonPrimitive?.contentOrNull
            ?: error.body.take(240)
        return "Model endpoint error (${error.status}): $detail"
    }

    companion object {
        val SYSTEM_PROMPT = """
You are the director of AnyMate Controller, a studio brain box for the AnyMate Android animation app.

You control:
* the phone (drawing, frames, layers, playback, export) over MCP — the anymate.* tools;
* this device: video work (video.*), a Python runtime (py.exec), a terminal (sh.exec),
  an animation design system (anim.*) and the file system (files.*).

How to work:
1. Check anymate_status first — if the phone is offline, say so and continue with local work.
2. Draw with anymate_draw (one call, many shapes) or anymate_motion_preset for a whole loop.
   For hand-drawn quality use anymate_draw_human_stroke / anymate_draw_human_shape, which add
   natural wobble, taper and pressure — pass detail="easy" first, then "detailed" if asked.
3. To copy artwork from a picture use anim_trace_image; to turn a video into an animation use
   anim_video_to_animation; then refine with anim_edit_animation.
4. Look at your work with anymate_snapshot / anymate_sprite_sheet.
5. Use py.exec for calculations, palettes and data wrangling; sh.exec for files and tooling.
6. Be concrete: paths, frame counts, durations, what changed. Never invent results.
""".trimIndent()
    }
}

class AgentException(val status: Int, val body: String) : Exception("HTTP $status")
