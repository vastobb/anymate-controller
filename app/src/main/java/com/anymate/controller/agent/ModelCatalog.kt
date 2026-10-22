package com.anymate.controller.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the model list from an OpenAI-compatible endpoint so the user never
 * has to type a model name by hand.
 */
object ModelCatalog {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val candidates = modelUrls(baseUrl)
        var lastError = "Could not reach the model endpoint."
        for (url in candidates) {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("Accept", "application/json")
                    if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                }
                val status = connection.responseCode
                val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (status !in 200..299) {
                    lastError = "HTTP $status from $url"
                    continue
                }
                val models = parseModels(text)
                if (models.isNotEmpty()) return@withContext models
                lastError = "No models found at $url"
            } catch (t: Throwable) {
                lastError = t.message ?: lastError
            }
        }
        throw IllegalStateException(lastError)
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
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val data = root["data"]?.jsonArray ?: root["models"]?.jsonArray ?: return emptyList()
        val models = LinkedHashSet<String>()
        for (element in data) {
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull
                ?: obj["model"]?.jsonPrimitive?.contentOrNull
            if (!id.isNullOrBlank()) models.add(id)
        }
        return models.sorted()
    }
}
