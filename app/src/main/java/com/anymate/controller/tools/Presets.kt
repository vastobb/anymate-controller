package com.anymate.controller.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Motion systems that live in the controller. Each preset returns a list of
 * frames; a frame is a list of AnyMate tool calls. They are shipped to the
 * phone with `anymate_batch`, so the whole sequence is one undo step and the
 * artist watches the animation build.
 */
object Presets {

    private val defaultPalette = listOf("#7C3AED", "#22D3EE", "#F97316", "#111827", "#F4F5F7")

    fun names(): List<String> = listOf(
        "bounce", "walk", "spin", "pulse", "fireworks", "wave", "orbit", "kinetic-type"
    )

    fun build(
        preset: String,
        width: Double,
        height: Double,
        frames: Int,
        palette: List<String> = emptyList(),
        message: String = "ANYMATE"
    ): List<List<JsonObject>> {
        val colours = (palette.ifEmpty { defaultPalette } + defaultPalette).take(5)
        return when (preset.lowercase()) {
            "bounce" -> bounce(width, height, frames, colours)
            "walk" -> walk(width, height, frames, colours)
            "spin" -> spin(width, height, frames, colours)
            "pulse" -> pulse(width, height, frames, colours)
            "fireworks" -> fireworks(width, height, frames, colours)
            "wave" -> wave(width, height, frames, colours)
            "orbit" -> orbit(width, height, frames, colours)
            "kinetic-type" -> kineticType(width, height, frames, colours, message)
            else -> throw IllegalArgumentException(
                "Unknown preset '$preset'. Known presets: ${names().joinToString(", ")}."
            )
        }
    }

    // ---- element helpers --------------------------------------------------
    private fun shape(
        kind: String,
        x1: Double, y1: Double, x2: Double, y2: Double,
        fill: String = "", stroke: String = "", width: Double = 3.0, layer: Int = 0
    ): JsonObject = buildJsonObject {
        put("shape", kind)
        put("x1", x1); put("y1", y1); put("x2", x2); put("y2", y2)
        put("width", width)
        put("layer", layer)
        if (fill.isNotEmpty()) put("fill_color", fill)
        if (stroke.isNotEmpty()) put("stroke_color", stroke)
    }

    private fun stroke(
        points: List<Pair<Double, Double>>,
        color: String,
        width: Double = 4.0,
        layer: Int = 0
    ): JsonObject = buildJsonObject {
        put("points", JsonArray(points.map { listOf(it.first, it.second) }))
        put("color", color)
        put("width", width)
        put("tool", "pen")
        put("layer", layer)
    }

    private fun text(value: String, x: Double, y: Double, size: Double, color: String): JsonObject =
        buildJsonObject {
            put("text", value)
            put("x", x); put("y", y); put("size", size); put("color", color); put("layer", 2)
        }

    private fun background(width: Double, height: Double, color: String): JsonObject =
        shape("rect", 0.0, 0.0, width, height, fill = color, stroke = color, width = 1.0, layer = 0)

    private fun alpha(colour: String, value: Double): String {
        val hex = colour.removePrefix("#")
        val expanded = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
        val base = if (expanded.length == 6) expanded else "000000"
        val byte = (value.coerceIn(0.0, 1.0) * 255).toInt()
        return "#" + base + byte.toString(16).padStart(2, '0').uppercase()
    }

    // ---- presets -----------------------------------------------------------
    private fun bounce(w: Double, h: Double, frames: Int, palette: List<String>): List<List<JsonObject>> {
        val accent = palette[0]; val accent2 = palette[1]; val ink = palette[2]; val paper = palette[3]
        val radius = min(w, h) * 0.085
        val ground = h * 0.78
        return (0 until frames).map { index ->
            val t = index.toDouble() / frames
            val phase = (t * 2.0) % 1.0
            val heightNorm = sin(phase * PI)
            val squash = 1.0 - heightNorm
            val x = w * 0.5 + sin(t * 2 * PI) * w * 0.2
            val rx = radius * (1 + 0.16 * Math.pow(squash, 3.0))
            val ry = radius * (1 - 0.13 * Math.pow(squash, 3.0))
            val cy = ground - ry - heightNorm * h * 0.34
            val frame = mutableListOf(background(w, h, paper))
            frame.add(shape("ellipse", x - rx * 1.3, ground, x + rx * 1.3, ground + radius * 0.2, fill = alpha(ink, 0.18)))
            for (trail in 1..3) {
                val prevT = ((t - trail * 0.012) % 1.0 + 1.0) % 1.0
                val prevPhase = (prevT * 2.0) % 1.0
                val prevH = sin(prevPhase * PI)
                val prevX = w * 0.5 + sin(prevT * 2 * PI) * w * 0.2
                val prevY = ground - radius - prevH * h * 0.34
                val size = radius * (0.22 - trail * 0.04)
                frame.add(shape("ellipse", prevX - size, prevY - size, prevX + size, prevY + size, fill = alpha(accent2, 0.16)))
            }
            frame.add(shape("ellipse", x - rx, cy - ry, x + rx, cy + ry, fill = accent, layer = 1))
            frame.add(shape("ellipse", x - rx * 0.55, cy - ry * 0.55, x - rx * 0.05, cy - ry * 0.15, fill = "#FFFFFF", layer = 1))
            if (heightNorm < 0.2) {
                val ring = radius * (1.4 + (0.2 - heightNorm) * 5.0)
                frame.add(shape("ellipse", x - ring, ground - ring * 0.3, x + ring, ground + ring * 0.3, stroke = accent2, width = 3.0))
            }
            frame
        }
    }

    private fun walk(w: Double, h: Double, frames: Int, palette: List<String>): List<List<JsonObject>> {
        val accent = palette[0]; val accent2 = palette[1]; val ink = palette[2]; val paper = palette[3]
        val ground = h * 0.82
        val bone = max(3.0, w * 0.008)
        val headR = w * 0.042
        return (0 until frames).map { index ->
            val t = index.toDouble() / frames
            val phase = t * 2 * PI * 2
            val bob = abs(sin(phase)) * h * 0.012
            val hip = h * 0.60 - bob
            val shoulder = h * 0.45 - bob
            val headY = shoulder - headR * 1.5
            val frame = mutableListOf(background(w, h, paper))
            frame.add(shape("ellipse", w * 0.34, ground, w * 0.66, ground + h * 0.012, fill = alpha(ink, 0.15)))
            for ((legPhase, colour) in listOf(phase to ink, (phase + PI) to accent)) {
                val footX = w * 0.5 + sin(legPhase) * w * 0.13
                val lift = max(0.0, sin(legPhase + PI * 0.35)) * h * 0.055
                val footY = ground - lift
                val hipX = w * 0.5 + (if (sin(legPhase) >= 0) w * 0.012 else -w * 0.012)
                val kneeX = (hipX + footX) / 2 + cos(legPhase) * w * 0.028
                val kneeY = (hip + footY) / 2 - h * 0.018
                frame.add(stroke(listOf(hipX to hip, kneeX to kneeY), colour, bone, 1))
                frame.add(stroke(listOf(kneeX to kneeY, footX to footY), colour, bone, 1))
                frame.add(stroke(listOf((footX - w * 0.016) to footY, (footX + w * 0.022) to footY), colour, bone, 1))
            }
            val armPhase = phase + PI
            val handX = w * 0.5 - sin(armPhase) * w * 0.12
            val handY = shoulder + h * 0.13 + cos(armPhase) * h * 0.018
            val elbowX = (w * 0.5 + handX) / 2 - cos(armPhase) * w * 0.02
            val elbowY = (shoulder + handY) / 2 + h * 0.01
            frame.add(stroke(listOf((w * 0.5 + w * 0.014) to (shoulder + h * 0.012), elbowX to elbowY), ink, bone * 0.9, 1))
            frame.add(stroke(listOf(elbowX to elbowY, handX to handY), ink, bone * 0.9, 1))
            frame.add(stroke(listOf(w * 0.5 to hip, w * 0.5 to shoulder), accent, bone * 1.7, 1))
            frame.add(shape("ellipse", w * 0.5 - headR, headY - headR, w * 0.5 + headR, headY + headR, fill = accent2, layer = 1))
            frame
        }
    }

    private fun spin(w: Double, h: Double, frames: Int, palette: List<String>): List<List<JsonObject>> {
        val accent = palette[0]; val accent2 = palette[1]; val ink = palette[2]; val paper = palette[3]
        val cx = w * 0.5; val cy = h * 0.5
        val radius = min(w, h) * 0.26
        return (0 until frames).map { index ->
            val t = index.toDouble() / frames
            val frame = mutableListOf(background(w, h, paper))
            listOf(
                Triple(1.0, 3, t * 2 * PI),
                Triple(0.78, 4, -t * 4 * PI),
                Triple(0.56, 6, t * 6 * PI)
            ).forEach { (factor, sides, rotation) ->
                val points = (0 until sides).map { step ->
                    val angle = rotation + step * 2 * PI / sides
                    listOf(cx + cos(angle) * radius * factor, cy + sin(angle) * radius * factor)
                }
                frame.add(
                    buildJsonObject {
                        put("points", JsonArray(points))
                        put("closed", true)
                        put("stroke_color", if (sides == 3) accent else accent2)
                        if (sides == 3) put("fill_color", accent)
                        put("width", 4.0)
                        put("layer", 1)
                    }
                )
            }
            frame.add(shape("ellipse", cx - radius * 0.1, cy - radius * 0.1, cx + radius * 0.1, cy + radius * 0.1, fill = paper, layer = 1))
            frame
        }
    }

    private fun pulse(w: Double, h: Double, frames: Int, palette: List<String>): List<List<JsonObject>> {
        val accent = palette[0]; val accent2 = palette[1]; val ink = palette[2]; val paper = palette[3]
        val cx = w * 0.5; val cy = h * 0.5
        val base = min(w, h) * 0.1
        val maximum = min(w, h) * 0.42
        return (0 until frames).map { index ->
            val t = index.toDouble() / frames
            val frame = mutableListOf(background(w, h, paper))
            for (ring in 0 until 5) {
                val phase = (t + ring / 5.0) % 1.0
                val radius = base + (maximum - base) * phase
                frame.add(
                    shape(
                        "ellipse", cx - radius, cy - radius, cx + radius, cy + radius,
                        stroke = if (ring % 2 == 0) accent else accent2,
                        width = 2.0 + 4.0 * (1 - phase),
                        layer = 0
                    )
                )
            }
            val beat = base * (1 + 0.12 * Math.pow(sin(t * 2 * PI * 2), 2.0))
            frame.add(shape("ellipse", cx - beat, cy - beat, cx + beat, cy + beat, fill = accent, layer = 1))
            frame
        }
    }

    private fun fireworks(w: Double, h: Double, frames: Int, palette: List<String>): List<List<JsonObject>> {
        val accent = palette[0]; val accent2 = palette[1]; val ink = palette[2]
        val night = "#0B0A14"
        val centres = listOf(
            Triple(w * 0.27, h * 0.34, 0.0),
            Triple(w * 0.73, h * 0.27, 0.34),
            Triple(w * 0.5, h * 0.58, 0.66)
        )
        return (0 until frames).map { index ->
            val t = index.toDouble() / frames
            val frame = mutableListOf(background(w, h, night))
            for (star in 0 until 24) {
                val sx = (star * 137.5) % w
                val sy = (star * 79.3) % (h * 0.7)
                val size = 1.6 + (star % 3)
                frame.add(shape("ellipse", sx - size, sy - size, sx + size, sy + size, fill = "#FFFFFF"))
            }
            centres.forEachIndexed { burst, (bx, by, offset) ->
                val age = (t + offset) % 1.0
                if (age >= 0.82) return@forEachIndexed
                val normalised = age / 0.82
                val colour = listOf(accent, accent2, palette[3])[burst]
                for (particle in 0 until 18) {
                    val angle = particle * (2 * PI / 18) + burst * 0.6
                    val speed = (0.14 + 0.10 * ((particle * 7) % 5) / 4.0) * min(w, h)
                    val radius = normalised * speed
                    val tail = max(0.0, radius - speed * 0.08)
                    frame.add(
                        stroke(
                            listOf(
                                (bx + cos(angle) * tail) to (by + sin(angle) * tail),
                                (bx + cos(angle) * radius) to (by + sin(angle) * radius)
                            ),
                            colour, 2.2 + 1.4 * (1 - normalised), 1
                        )
                    )
                }
            }
            frame
        }
    }

    private fun wave(w: Double, h: Double, frames: Int, palette: List<String>): List<List<JsonObject>> {
        val accent = palette[0]; val accent2 = palette[1]; val ink = palette[2]; val paper = palette[3]
        val segments = 36
        return (0 until frames).map { index ->
            val t = index.toDouble() / frames
            val frame = mutableListOf(background(w, h, paper))
            fun yAt(fraction: Double): Double =
                h * 0.52 + sin(fraction * 2 * PI * 1.6 + t * 2 * PI) * h * 0.1
            for (step in 0 until segments) {
                val f1 = step.toDouble() / segments
                val f2 = (step + 1).toDouble() / segments
                frame.add(
                    stroke(
                        listOf(f1 * w to yAt(f1), f2 * w to yAt(f2)),
                        mix(accent, accent2, f1), 7.0, 1
                    )
                )
            }
            frame
        }
    }

    private fun orbit(w: Double, h: Double, frames: Int, palette: List<String>): List<List<JsonObject>> {
        val accent = palette[0]; val accent2 = palette[1]; val ink = palette[2]; val paper = palette[3]
        val cx = w * 0.5; val cy = h * 0.5
        val base = min(w, h)
        return (0 until frames).map { index ->
            val t = index.toDouble() / frames
            val frame = mutableListOf(background(w, h, paper))
            listOf(0.18 to accent2, 0.29 to palette[3], 0.39 to accent).forEachIndexed { i, (orbit, colour) ->
                val radius = base * orbit
                frame.add(shape("ellipse", cx - radius, cy - radius * 0.62, cx + radius, cy + radius * 0.62, stroke = colour, width = 2.0))
                val angle = t * 2 * PI * (1.1 + orbit * 4) + orbit * 12
                val px = cx + cos(angle) * radius
                val py = cy + sin(angle) * radius * 0.62
                val size = base * (0.016 - i * 0.002)
                frame.add(shape("ellipse", px - size, py - size, px + size, py + size, fill = colour, layer = 1))
            }
            val core = base * 0.095
            frame.add(shape("ellipse", cx - core, cy - core, cx + core, cy + core, fill = palette[3], layer = 1))
            frame
        }
    }

    private fun kineticType(
        w: Double, h: Double, frames: Int, palette: List<String>, message: String
    ): List<List<JsonObject>> {
        val accent = palette[0]; val accent2 = palette[1]; val ink = palette[2]; val paper = palette[3]
        val upper = message.uppercase().take(20)
        return (0 until frames).map { index ->
            val t = index.toDouble() / frames
            val visible = ((t * 1.25 + 0.05) * upper.length).toInt().coerceIn(1, upper.length)
            val typed = upper.take(visible)
            val size = min(h * 0.16, w * 0.9 / max(1.0, upper.length * 0.62))
            val x = (w - size * 0.62 * upper.length) / 2
            val frame = mutableListOf(background(w, h, paper))
            frame.add(text(typed, x, h * 0.53, size, ink))
            frame.add(stroke(listOf(x to (h * 0.53 + size * 0.22), (x + size * 0.62 * visible) to (h * 0.53 + size * 0.22)), accent2, 4.0, 1))
            frame
        }
    }

    private fun mix(a: String, b: String, fraction: Double): String {
        fun channels(value: String): Triple<Int, Int, Int> {
            val hex = value.removePrefix("#")
            return Triple(
                hex.substring(0, 2).toInt(16),
                hex.substring(2, 4).toInt(16),
                hex.substring(4, 6).toInt(16)
            )
        }
        val (r1, g1, b1) = channels(a)
        val (r2, g2, b2) = channels(b)
        val f = fraction.coerceIn(0.0, 1.0)
        return String.format(
            "#%02X%02X%02X",
            (r1 * (1 - f) + r2 * f).toInt(),
            (g1 * (1 - f) + g2 * f).toInt(),
            (b1 * (1 - f) + b2 * f).toInt()
        )
    }
}
