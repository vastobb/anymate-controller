package com.anymate.controller.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Colour, timing and raster helpers that need no network and no ffmpeg. */
object AnimTools {

    private val EASINGS: Map<String, (Float) -> Float> = mapOf(
        "linear" to { it },
        "ease-in" to { it * it },
        "ease-out" to { 1f - (1f - it) * (1f - it) },
        "ease-in-out" to { it * it * (3f - 2f * it) },
        "sine" to { 0.5f - 0.5f * Math.cos((Math.PI.toFloat() * it).toDouble()).toFloat() },
        "expo-in" to { if (it == 0f) 0f else Math.pow(2.0, 10.0 * (it - 1f).toDouble()).toFloat() },
        "expo-out" to { if (it == 1f) 1f else 1f - Math.pow(2.0, -10.0 * it.toDouble()).toFloat() },
        "back-out" to { 1f + 2.70158f * Math.pow((it - 1f).toDouble(), 3.0).toFloat() + 1.70158f * (it - 1f) * (it - 1f) },
        "bounce" to { bounce(it) },
        "elastic-out" to { elastic(it) }
    )

    private fun bounce(t: Float): Float {
        val n = 7.5625f
        val d = 2.75f
        var v = t
        return when {
            v < 1f / d -> n * v * v
            v < 2f / d -> {
                v -= 1.5f / d
                n * v * v + 0.75f
            }
            v < 2.5f / d -> {
                v -= 2.25f / d
                n * v * v + 0.9375f
            }
            else -> {
                v -= 2.625f / d
                n * v * v + 0.984375f
            }
        }
    }

    private fun elastic(t: Float): Float {
        if (t == 0f || t == 1f) return t
        val c = (2f * Math.PI.toFloat()) / 3f
        return (Math.pow(2.0, (-10f * t).toDouble()).toFloat() *
                Math.sin((((t * 10f) - 0.75f) * c).toDouble()).toFloat()) + 1f
    }

    fun parseColor(value: String?, fallback: Int = Color.WHITE): Int {
        val text = value?.trim()?.removePrefix("#") ?: return fallback
        val expanded = if (text.length == 3) text.map { "$it$it" }.joinToString("") else text
        return try {
            when (expanded.length) {
                6 -> Color.rgb(
                    expanded.substring(0, 2).toInt(16),
                    expanded.substring(2, 4).toInt(16),
                    expanded.substring(4, 6).toInt(16)
                )
                8 -> Color.argb(
                    expanded.substring(0, 2).toInt(16),
                    expanded.substring(2, 4).toInt(16),
                    expanded.substring(4, 6).toInt(16),
                    expanded.substring(6, 8).toInt(16)
                )
                else -> fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    fun palette(base: String, scheme: String, count: Int): List<String> {
        val rgb = parseColor(base, Color.rgb(124, 58, 237))
        val hsv = FloatArray(3)
        Color.colorToHSV(rgb, hsv)
        val hue = hsv[0]
        val light = hsv[1]
        val sat = hsv[2]
        val offsets = when (scheme.lowercase()) {
            "complementary" -> listOf(0f, 0.5f, 0.52f, 0.48f, 0f, 0.5f)
            "triadic" -> listOf(0f, 1f / 3f, 2f / 3f, 0f, 1f / 3f, 2f / 3f)
            "analogous" -> listOf(0f, 0.08f, -0.08f, 0.16f, -0.16f, 0f)
            "monochrome" -> listOf(0f, 0f, 0f, 0f, 0f, 0f)
            else -> listOf(0f, 0.42f, 0.58f, 0.05f, 0.42f, 0.58f)
        }
        return (0 until count.coerceIn(2, 12)).map { index ->
            val h = (hue + offsets[index % offsets.size] + 360f) % 360f
            val l = if (index == 0) light else (light + when (index % 3) {
                1 -> 0.10f
                2 -> -0.08f
                else -> 0f
            }).coerceIn(0.10f, 0.90f)
            val s = if (index == 0) sat else (sat + if (index % 3 == 2) 0.04f else 0f).coerceIn(0.08f, 1f)
            val out = FloatArray(3)
            hex(Color.HSVToColor(floatArrayOf(h, s, l)))
        }
    }

    fun easing(name: String, count: Int): List<Float>? {
        val curve = EASINGS[name.lowercase()] ?: return null
        val steps = count.coerceIn(2, 120)
        return (0 until steps).map { curve(it.toFloat() / (steps - 1)) }
    }

    fun timing(fps: Int, seconds: Double, style: String, hold: Int): JsonObject {
        val total = (fps * seconds.coerceAtLeast(0.1)).toInt().coerceAtLeast(1)
        val holds = if (style == "on-twos") hold.coerceAtLeast(1) else 1
        return buildJsonObject {
            put("fps", fps)
            put("seconds", seconds)
            put("total_frames", total)
            put("style", style)
            put("unique_drawings", (total / holds).coerceAtLeast(1))
            put("holds_per_drawing", holds)
            put("frame_duration_ms", (1000f / fps.coerceAtLeast(1)))
        }
    }

    fun storyboard(beats: List<String>, fps: Int): JsonObject = buildJsonObject {
        put("fps", fps)
        put(
            "beats",
            JsonArray(
                beats.mapIndexed { index, beat ->
                    buildJsonObject {
                        put("beat", index + 1)
                        put("description", beat)
                        put("frames", fps)
                        put("frame_range", "${index * fps}-${(index + 1) * fps - 1}")
                    }
                }
            )
        )
    }

    /** Packs PNG frames into a sprite sheet. */
    fun spriteSheet(
        workspace: File,
        framePaths: List<String>,
        output: String,
        columns: Int
    ): ToolResult {
        val frames = framePaths.mapNotNull { path ->
            val file = File(path).let { if (it.isAbsolute) it else File(workspace, path) }
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        }
        if (frames.isEmpty()) return ToolResult(text = "No readable frames were found.", ok = false)
        val cellW = frames.maxOf { it.width }
        val cellH = frames.maxOf { it.height }
        val cols = columns.coerceIn(1, 12)
        val rows = ((frames.size + cols - 1) / cols).coerceAtLeast(1)
        val sheet = Bitmap.createBitmap(cellW * cols, cellH * rows, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        frames.forEachIndexed { index, bitmap ->
            val column = index % cols
            val row = index / cols
            canvas.drawBitmap(bitmap, (column * cellW).toFloat(), (row * cellH).toFloat(), null)
        }
        val target = File(output).let { if (it.isAbsolute) it else File(workspace, output) }
        target.parentFile?.mkdirs()
        target.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.recycle()
        frames.forEach { it.recycle() }
        return ToolResult(
            text = "Sprite sheet written: ${target.name} (${cols}×$rows cells, ${cellW}×$cellH px).",
            data = buildJsonObject {
                put("output", target.absolutePath)
                put("frames", frames.size)
                put("columns", cols)
                put("rows", rows)
            }
        )
    }

    /** Generates a simple frame: flat colour, ellipse or gradient. */
    fun generatePng(
        workspace: File,
        output: String,
        width: Int,
        height: Int,
        background: String,
        accent: String,
        shape: String
    ): ToolResult {
        val w = width.coerceIn(8, 2048)
        val h = height.coerceIn(8, 2048)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val base = parseColor(background, Color.WHITE)
        val accentColor = parseColor(accent, Color.rgb(124, 58, 237))
        canvas.drawColor(base)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) * 0.28f
        when (shape.lowercase()) {
            "ellipse" -> {
                paint.color = accentColor
                canvas.drawCircle(cx, cy, radius, paint)
            }
            "gradient" -> {
                paint.shader = android.graphics.LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(), base, accentColor, android.graphics.Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
        }
        val target = File(output).let { if (it.isAbsolute) it else File(workspace, output) }
        target.parentFile?.mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return ToolResult(
            text = "Wrote ${target.name} (${w}×$h).",
            data = buildJsonObject { put("output", target.absolutePath) }
        )
    }
}
