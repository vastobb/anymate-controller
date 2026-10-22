package com.anymate.controller.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Small helpers so tool arguments can be read without unwrapping primitives. */

fun JsonElement?.textOr(default: String = ""): String =
    (this as? JsonPrimitive)?.content ?: default

fun JsonElement?.intOr(default: Int = 0): Int =
    (this as? JsonPrimitive)?.content?.toIntOrNull() ?: default

fun JsonElement?.doubleOr(default: Double = 0.0): Double =
    (this as? JsonPrimitive)?.content?.toDoubleOrNull() ?: default

fun JsonElement?.boolOr(default: Boolean = false): Boolean =
    (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default
