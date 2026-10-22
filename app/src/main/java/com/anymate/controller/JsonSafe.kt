package com.anymate.controller

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * OpenAI-compatible providers are liberal with `null`: `"function_call": null`,
 * `"tool_calls": null`, `"error": null`, `"content": null`. Casting those to
 * JsonObject/JsonArray throws a serialization exception ("Element class
 * kotlinx.serialization.json.JsonNull ... is not a JsonObject"), so every
 * untrusted field goes through these helpers instead.
 */

internal fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arrOrNull(): JsonArray? = this as? JsonArray

internal fun JsonElement?.strOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.contentOrNull
}

/** Assistant content may be a string, null, or an array of typed parts. */
internal fun JsonObject.contentText(): String {
    val element = this["content"] ?: return ""
    return when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonArray -> element.mapNotNull { part ->
            val obj = part.objOrNull() ?: return@mapNotNull null
            obj["text"].strOrNull()
        }.joinToString("\n")
        else -> ""
    }
}

/** Best-effort error message from the many provider error shapes. */
internal fun providerErrorText(root: JsonObject?): String? {
    if (root == null) return null
    val error = root["error"]
    return root["detail"].strOrNull()
        ?: error?.objOrNull()?.get("message").strOrNull()
        ?: error?.strOrNull()
        ?: root["message"].strOrNull()
        ?: root["title"].strOrNull()
}
