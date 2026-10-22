package com.anymate.controller.agent

import com.anymate.controller.arrOrNull
import com.anymate.controller.contentText
import com.anymate.controller.objOrNull
import com.anymate.controller.providerErrorText
import com.anymate.controller.strOrNull

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
    val label: String = "image",
    val path: String? = null
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

    private fun chatUrl(): String = ModelCatalog.chatUrl(endpoint)

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
            json.parseToJsonElement(text) as? JsonObject
                ?: throw AgentException(status, "The endpoint returned a non-object JSON response: ${text.take(200)}")
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
                // Reasoning models (Nemotron 3, o-series, DeepSeek-R1…) spend
                // tokens before they answer; 4096 cut them off mid-thought.
                put("max_tokens", 8192)
            }

            val response = try {
                post(payload)
            } catch (error: AgentException) {
                if (!sanitized && isRecoverable(error)) {
                    // The provider does not like our tool protocol: flatten the
                    // tool turns into plain text and try again.
                    onEvent(AgentEvent("notice", "Endpoint rejected the tool protocol — retrying in plain text."))
                    sanitized = true
                    for (i in messages.indices) messages[i] = flattenToolTurns(messages[i])
                    continue
                }
                if (!droppedTools && isRecoverable(error)) {
                    // Still unhappy: continue without tools at all.
                    onEvent(AgentEvent("notice", "Tools unavailable on this endpoint — answering without them."))
                    droppedTools = true
                    sanitized = true
                    for (i in messages.indices) messages[i] = flattenToolTurns(messages[i])
                    continue
                }
                return AgentReply(steps = steps, toolCalls = calls, error = describe(error))
            }

            if (response["error"] != null && response["error"] !is kotlinx.serialization.json.JsonNull) {
                val message = providerErrorText(response) ?: response["error"].toString()
                return AgentReply(steps = steps, toolCalls = calls, error = "Model error: $message")
            }
            val choices = response["choices"]?.arrOrNull() ?: JsonArray(emptyList())
            if (choices.isEmpty()) {
                return AgentReply(steps = steps, toolCalls = calls, error = "The model returned no choices.")
            }
            val message = choices.getOrNull(0)?.objOrNull()?.get("message")?.objOrNull()
                ?: JsonObject(emptyMap())
            val content = message.contentText()
            val reasoning = message["reasoning_content"].strOrNull()
                ?: message["reasoning"].strOrNull()
            val toolCalls = message["tool_calls"]?.arrOrNull() ?: JsonArray(emptyList())
            val legacyCall = message["function_call"]?.objOrNull()
            val finish = choices.getOrNull(0)?.objOrNull()?.get("finish_reason").strOrNull()

            if (toolCalls.isEmpty() && legacyCall == null) {
                val answer = content.trim()
                if (answer.isEmpty() && !reasoning.isNullOrBlank()) {
                    return AgentReply(
                        text = "The model returned only internal reasoning and no answer.\n" +
                            "It is a reasoning model and probably hit the token limit — try again, " +
                            "or pick a non-reasoning model.\n\n" +
                            reasoning.trim().take(700),
                        steps = steps,
                        toolCalls = calls
                    )
                }
                if (answer.isEmpty() && finish == "length") {
                    return AgentReply(
                        text = "The model hit the token limit before answering. Try a shorter request " +
                            "or a non-reasoning model.",
                        steps = steps,
                        toolCalls = calls
                    )
                }
                return AgentReply(text = answer.ifBlank { "Done." }, steps = steps, toolCalls = calls)
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
                    val call = element.objOrNull() ?: continue
                    val function = call["function"]?.objOrNull() ?: JsonObject(emptyMap())
                    val id = call["id"].strOrNull() ?: "call_${calls.size}"
                    val name = function["name"].strOrNull().orEmpty()
                    pending.add(id to name)
                }
            } else if (legacyCall != null) {
                pending.add("call_legacy" to legacyCall["name"].strOrNull().orEmpty())
            }

            if (pending.isEmpty()) {
                return AgentReply(
                    text = content.trim().ifBlank { "The model returned an unreadable tool call." },
                    steps = steps,
                    toolCalls = calls
                )
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
                val rawArguments = if (legacyCall != null && id == "call_legacy") {
                    legacyCall["arguments"].strOrNull()
                } else {
                    toolCalls.firstOrNull { element ->
                        element.objOrNull()?.get("id").strOrNull() == id
                    }?.objOrNull()?.get("function")?.objOrNull()?.get("arguments").strOrNull()
                } ?: "{}"
                val arguments = runCatching {
                    json.parseToJsonElement(rawArguments) as? JsonObject
                }.getOrNull() ?: JsonObject(emptyMap())
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
        val role = message["role"].strOrNull()
        if (role == "tool") {
            val content = message["content"].strOrNull().orEmpty()
            return buildJsonObject {
                put("role", "user")
                put("content", "[tool output]\n$content")
            }
        }
        if (role == "assistant" && message.containsKey("tool_calls")) {
            val content = message.contentText()
            return buildJsonObject {
                put("role", "assistant")
                put("content", content.ifBlank { "(tool call)" })
            }
        }
        return message
    }

    private fun isRecoverable(error: AgentException): Boolean {
        val text = (error.body + " " + error.message).lowercase()
        // NVIDIA's "Function …: Not found for account …" is a model-entitlement
        // problem, not a tool-protocol problem: retrying only wastes time.
        if (text.contains("not found for account")) return false
        if (text.contains("model") && text.contains("not found") && text.contains("account")) return false
        return error.status == 404 || error.status == 400 &&
            (text.contains("not found") || text.contains("function") || text.contains("tool"))
    }

    private fun describe(error: AgentException): String =
        ModelCatalog.explain(error.status, chatUrl(), error.body, model)

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
   For hand-drawn quality use anymate_human_draw (kind=stroke|shape|figure), which adds natural
   wobble, taper and overshoot — pass detail="easy" first, then "detailed" to refine.
3. To copy artwork from a picture use anim_trace_image; to turn a video into an animation use
   anim_video_to_animation; then refine with anim_edit_animation.
4. Look at your work with anymate_snapshot / anymate_sprite_sheet.
5. Use py.exec for calculations, palettes and data wrangling; sh.exec for files and tooling.
6. Any tool the phone exposes can be reached with anymate_tool when no shortcut exists.
7. Be concrete: paths, frame counts, durations, what changed. Never invent results.
""".trimIndent()
    }
}

class AgentException(val status: Int, val body: String) : Exception("HTTP $status")
