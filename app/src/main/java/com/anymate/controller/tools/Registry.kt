package com.anymate.controller.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ToolResult(
    val text: String = "",
    val image: ByteArray? = null,
    val mime: String = "image/png",
    val ok: Boolean = true,
    val data: JsonObject? = null
)

data class ToolDef(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val group: String,
    val handler: suspend (JsonObject) -> ToolResult
)

/** The controller toolbox: local tools plus everything the phone exposes. */
class ToolRegistry {
    private val tools = linkedMapOf<String, ToolDef>()

    fun register(
        name: String,
        description: String,
        schema: JsonObject,
        group: String,
        handler: suspend (JsonObject) -> ToolResult
    ) {
        tools[name] = ToolDef(name, description, schema, group, handler)
    }

    fun get(name: String): ToolDef? = tools[name]

    fun all(): List<ToolDef> = tools.values.toList()

    fun groups(): Map<String, List<ToolDef>> = tools.values.groupBy { it.group }

    fun openaiTools(): JsonArray = buildJsonArray {
        tools.values.forEach { tool ->
            add(
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.schema)
                        }
                    )
                }
            )
        }
    }

    suspend fun call(name: String, arguments: JsonObject): ToolResult {
        val tool = tools[name] ?: return ToolResult(
            text = "Unknown tool: $name. Use tools/list to see everything.",
            ok = false
        )
        return runCatching { tool.handler(arguments) }
            .getOrElse { error ->
                ToolResult(text = "$name failed: ${error.message}", ok = false)
            }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun obj(vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(props.toMap()))
        }

        fun str(desc: String, default: String? = null): JsonObject = buildJsonObject {
            put("type", "string")
            put("description", desc)
            default?.let { put("default", it) }
        }

        fun num(desc: String, min: Double? = null, max: Double? = null): JsonObject = buildJsonObject {
            put("type", "number")
            put("description", desc)
            min?.let { put("minimum", it) }
            max?.let { put("maximum", it) }
        }

        fun int(desc: String, min: Double? = null, max: Double? = null): JsonObject = buildJsonObject {
            put("type", "integer")
            put("description", desc)
            min?.let { put("minimum", it) }
            max?.let { put("maximum", it) }
        }

        fun bool(desc: String): JsonObject = buildJsonObject {
            put("type", "boolean")
            put("description", desc)
        }

        fun arr(desc: String, items: JsonObject): JsonObject = buildJsonObject {
            put("type", "array")
            put("description", desc)
            put("items", items)
        }

        fun strArray(desc: String): JsonObject = arr(desc, str("value"))
    }
}
