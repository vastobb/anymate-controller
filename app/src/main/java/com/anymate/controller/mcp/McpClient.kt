package com.anymate.controller.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

data class McpToolInfo(val name: String, val description: String, val schema: JsonObject?)

data class McpToolResult(
    val text: String = "",
    val image: ByteArray? = null,
    val mime: String = "image/png",
    val isError: Boolean = false
)

/**
 * Streamable HTTP MCP client. The controller talks to the AnyMate app in the
 * same way any other MCP client would.
 */
class McpClient(
    private val url: String,
    private val token: String = "",
    private val timeoutMs: Int = 30_000
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var sessionId: String? = null
    private var requestId = 0

    val connected: Boolean get() = sessionId != null

    // ---- plumbing ----------------------------------------------------------
    private fun post(payload: JsonObject): JsonObject? {
        val target = URL(url)
        val connection = (target.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            val status = connection.responseCode
            connection.headerFields["Mcp-Session-Id"]?.firstOrNull()?.let { sessionId = it }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (status !in 200..299) {
                throw McpException("HTTP $status: ${text.take(300)}")
            }
            parseBody(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseBody(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        val body = if (raw.contains("data:")) {
            raw.lines().filter { it.trimStart().startsWith("data:") }
                .map { it.trimStart().removePrefix("data:").trim() }
                .lastOrNull() ?: return null
        } else raw
        return runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    private fun nextId(): Int {
        requestId += 1
        return requestId
    }

    private fun request(method: String, params: JsonObject? = null): JsonObject {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", nextId())
            put("method", method)
            params?.let { put("params", it) }
        }
        val response = post(payload) ?: throw McpException("Empty response for $method")
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
            throw McpException("$method failed: $message")
        }
        return response["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    // ---- protocol ----------------------------------------------------------
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        val result = request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", "2025-06-18")
                put("capabilities", buildJsonObject { })
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "anymate-controller")
                        put("version", "1.0.0")
                    }
                )
            }
        )
        sessionId = "controller"
        result["serverInfo"] != null
    }

    suspend fun listTools(): List<McpToolInfo> = withContext(Dispatchers.IO) {
        val result = request("tools/list")
        result["tools"]?.jsonArray?.map { element ->
            val obj = element.jsonObject
            McpToolInfo(
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                schema = obj["inputSchema"] as? JsonObject
            )
        } ?: emptyList()
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpToolResult = withContext(Dispatchers.IO) {
        val result = request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            }
        )
        val content = result["content"]?.jsonArray ?: JsonArray(emptyList())
        val text = content.mapNotNull { part ->
            val obj = part.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                obj["text"]?.jsonPrimitive?.contentOrNull
            } else null
        }.joinToString("\n")
        val image = content.firstOrNull { part ->
            part.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "image"
        }?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull?.let { encoded ->
            runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
        }
        val isError = result["isError"]?.jsonPrimitive?.contentOrNull == "true"
        McpToolResult(text = text, image = image, isError = isError)
    }

    suspend fun snapshotPng(): ByteArray? = withContext(Dispatchers.IO) {
        val result = request("resources/read", buildJsonObject { put("uri", "anymate://preview/current") })
        result["contents"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("blob")?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    }

    suspend fun health(): String = withContext(Dispatchers.IO) {
        val base = url.trimEnd('/').removeSuffix("/mcp")
        val connection = (URL("$base/health").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val text = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            text
        } finally {
            connection.disconnect()
        }
    }

    fun disconnect() {
        sessionId = null
    }
}

class McpException(message: String) : Exception(message)
