package com.anymate.controller.agent

import com.anymate.controller.arrOrNull
import com.anymate.controller.objOrNull
import com.anymate.controller.providerErrorText
import com.anymate.controller.strOrNull

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/** A single model that answered, with the probes it took to find it. */
data class WorkingModel(val model: String, val probes: Int, val skipped: Int)

/**
 * Endpoint helpers: model discovery, model probing and human-readable errors
 * for the provider quirks that show up in the wild (NVIDIA's account gate,
 * bad keys, retired models).
 */
object ModelCatalog {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Reply(val status: Int, val text: String)

    fun isNvidia(url: String): Boolean =
        url.contains("integrate.api.nvidia.com") || url.contains("api.nvidia.com")

    suspend fun fetch(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val candidates = modelUrls(baseUrl)
        var lastError = "Could not reach the model endpoint."
        for (url in candidates) {
            try {
                val reply = request("GET", url, apiKey, null)
                if (reply.status !in 200..299) {
                    lastError = explain(reply.status, url, reply.text, "")
                    continue
                }
                val models = parseModels(reply.text)
                if (models.isNotEmpty()) return@withContext models
                lastError = "No models found at $url"
            } catch (t: Throwable) {
                lastError = t.message ?: lastError
            }
        }
        throw IllegalStateException(lastError)
    }

    /** The chat-completions URL for a base URL (or a full endpoint). */
    fun chatUrl(endpoint: String): String {
        var base = endpoint.trim().trimEnd('/')
        if (base.endsWith("/chat/completions")) return base
        // People often paste the docs URL they copied the key from.
        if (base.endsWith("/models")) base = base.removeSuffix("/models")
        if (base.endsWith("/responses")) base = base.removeSuffix("/responses")
        if (base.endsWith("/completions")) base = base.removeSuffix("/completions")
        if ("/" !in base.substringAfter("://", "")) return "$base/v1/chat/completions"
        return "$base/chat/completions"
    }

    /** One tiny completion, no tools — isolates endpoint/auth/model problems. */
    suspend fun testChat(endpoint: String, apiKey: String, model: String): String = withContext(Dispatchers.IO) {
        val url = chatUrl(endpoint)
        val reply = request("POST", url, apiKey, chatPayload(model, "Reply with the single word: ready", 16))
        if (reply.status !in 200..299) {
            throw IllegalStateException(explain(reply.status, url, reply.text, model))
        }
        val content = contentOf(reply.text)
        "Endpoint OK at $url\nmodel: $model\nreply: ${content?.take(60) ?: "(empty)"}"
    }

    /**
     * Tries models until one actually answers. NVIDIA in particular lists a
     * full catalog on /v1/models but returns 404 "Function … not found for
     * account" for models the org is not entitled to.
     */
    suspend fun findWorking(
        endpoint: String,
        apiKey: String,
        candidates: List<String>,
        maxProbes: Int = 15,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): WorkingModel = withContext(Dispatchers.IO) {
        val url = chatUrl(endpoint)
        val ordered = orderCandidates(endpoint, candidates)
        var skipped = 0
        var probes = 0
        var lastError: String? = null
        for (model in ordered) {
            if (probes >= maxProbes) break
            probes++
            onProgress(probes, minOf(maxProbes, ordered.size), model)
            val reply = try {
                request("POST", url, apiKey, chatPayload(model, "Hi", 1), readTimeout = 25_000)
            } catch (t: Throwable) {
                lastError = t.message
                continue
            }
            if (reply.status in 200..299) return@withContext WorkingModel(model, probes, skipped)
            // Auth failures affect every model — stop immediately.
            if (reply.status == 401 || reply.status == 403) {
                throw IllegalStateException(explain(reply.status, url, reply.text, model))
            }
            lastError = explain(reply.status, url, reply.text, model)
            skipped++
        }
        throw IllegalStateException(
            lastError ?: "No model answered after $probes probes. Check the endpoint and key."
        )
    }

    /** Turns a provider error body into something a person can act on. */
    fun explain(status: Int, url: String, body: String, model: String): String {
        val parsed = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val detail = providerErrorText(parsed) ?: body.take(240)
        val label = if (model.isBlank()) "(no model)" else model
        return when {
            isNvidia(url) && (detail.contains("not found for account", true) ||
                detail.contains("function", true) && detail.contains("not found", true)) ->
                "NVIDIA NIM: model \"$label\" is not enabled for this NVIDIA account.\n" +
                    "NVIDIA lists it on /v1/models, but the org needs the \"Public API Endpoints\" " +
                    "permission before chat works. Some keys also only unlock the model page they were " +
                    "created on.\n" +
                    "Try: press \"Find working model\" (it probes the catalog), create a key on the " +
                    "model's build.nvidia.com page, or email help@build.nvidia.com with this detail:\n$detail"

            status == 401 || status == 403 ->
                "The endpoint rejected the API key (HTTP $status).\n$detail"

            status == 404 ->
                "HTTP 404 at $url\nmodel: $label\n$detail"

            status == 429 ->
                "Rate limited (HTTP 429). Wait a moment, or try a smaller model.\n$detail"

            status in 500..599 ->
                "The provider is failing (HTTP $status). Try again in a moment.\n$detail"

            else -> "HTTP $status — $detail"
        }
    }

    private fun orderCandidates(endpoint: String, candidates: List<String>): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val preferred = preferredModels(endpoint)
        val usable = candidates.filterNot { id ->
            val lower = id.lowercase()
            listOf("embed", "rerank", "retriev", "guard", "moderat", "whisper", "tts", "asr", "parse")
                .any { lower.contains(it) }
        }.ifEmpty { candidates }
        val ordered = LinkedHashSet<String>()
        preferred.filter { it in usable }.forEach { ordered.add(it) }
        usable.sortedWith(
            compareByDescending<String> { candidate ->
                val lower = candidate.lowercase()
                when {
                    lower.contains("instruct") -> 3
                    lower.contains("chat") -> 2
                    lower.contains("nemotron") -> 1
                    else -> 0
                }
            }.thenBy { it.length }
        ).forEach { ordered.add(it) }
        return ordered.toList()
    }

    /** Models that have been hosted for a long time and usually accept new keys. */
    private fun preferredModels(endpoint: String): List<String> = when {
        isNvidia(endpoint) -> listOf(
            "meta/llama-3.1-8b-instruct",
            "meta/llama-3.3-70b-instruct",
            "nvidia/llama-3.1-nemotron-70b-instruct",
            "mistralai/mistral-7b-instruct-v0.3",
            "google/gemma-2-9b-it"
        )
        endpoint.contains("openrouter.ai") -> listOf(
            "openai/gpt-4o-mini",
            "anthropic/claude-3.5-sonnet",
            "meta-llama/llama-3.3-70b-instruct"
        )
        else -> emptyList()
    }

    private fun chatPayload(model: String, prompt: String, maxTokens: Int): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", prompt)
            })
        })
    }

    private fun contentOf(text: String): String? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val message = root["choices"]?.arrOrNull()?.firstOrNull()?.objOrNull()
            ?.get("message")?.objOrNull() ?: return null
        return com.anymate.controller.contentText(message)
    }

    private fun request(
        method: String,
        url: String,
        apiKey: String,
        body: JsonObject?,
        readTimeout: Int = 60_000
    ): Reply {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            this.readTimeout = readTimeout
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Reply(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun modelUrls(baseUrl: String): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return emptyList()
        val urls = LinkedHashSet<String>()
        if (base.endsWith("/chat/completions")) {
            val root = base.removeSuffix("/chat/completions")
            urls.add("$root/models")
        } else {
            urls.add("$base/models")
            if ("/" !in base.substringAfter("://", "")) urls.add("$base/v1/models")
        }
        return urls.toList()
    }

    private fun parseModels(text: String): List<String> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val data = root["data"]?.arrOrNull() ?: root["models"]?.arrOrNull() ?: return emptyList()
        val models = LinkedHashSet<String>()
        for (element in data) {
            val obj = element.objOrNull() ?: continue
            val id = obj["id"].strOrNull()
                ?: obj["name"].strOrNull()
                ?: obj["model"].strOrNull()
            if (!id.isNullOrBlank()) models.add(id)
        }
        return models.sorted()
    }
}
