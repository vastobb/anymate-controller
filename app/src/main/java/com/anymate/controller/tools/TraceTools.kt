package com.anymate.controller.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.anymate.controller.mcp.McpClient
import com.anymate.controller.mcp.McpException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Copying artwork: a picture or a video can be turned into vector lines on the
 * phone, and an existing animation can be re-timed, flipped, tinted or
 * interpolated afterwards.
 */
object TraceTools {

    fun register(registry: ToolRegistry, client: () -> McpClient, workspace: File) {

        // ---- image -> art --------------------------------------------------
        registry.register(
            "anim_trace_image",
            "Copy an image into the phone's canvas as artwork. mode='sketch' traces the picture " +
                "into clean vector lines (a hand-drawn look); mode='flat' places the picture itself " +
                "as a single image element. detail='easy' traces only the strongest lines, " +
                "'detailed' keeps more of the drawing.",
            ToolRegistry.obj(
                "path" to ToolRegistry.str("Image in the workspace"),
                "mode" to ToolRegistry.str("sketch | flat"),
                "detail" to ToolRegistry.str("easy | medium | detailed"),
                "color" to ToolRegistry.str("Line colour as #RRGGBB"),
                "width" to ToolRegistry.num("Stroke width in project pixels"),
                "layer" to ToolRegistry.int("Target layer index"),
                "frame" to ToolRegistry.int("Target frame index; -1 = current")
            ),
            "animation"
        ) { args ->
            val path = args["path"].textOr()
            val file = File(path).let { if (it.isAbsolute) it else File(workspace, path) }
            if (!file.exists()) return@register ToolResult(text = "'$path' does not exist.", ok = false)
            val mode = args["mode"].textOr("sketch")
            val detail = args["detail"].textOr("medium")
            val color = args["color"].textOr("#111827")
            val width = args["width"].doubleOr(4.0)
            val layer = args["layer"].intOr(0)
            val frame = args["frame"].intOr(-1)
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                ?: return@register ToolResult(text = "Could not decode '$path'.", ok = false)
            try {
                if (mode == "flat") {
                    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }
                        .toByteArray()
                    val call = buildJsonObject {
                        put("name", "anymate_draw_bitmap")
                        put(
                            "args",
                            buildJsonObject {
                                put("png_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                                put("x", 0)
                                put("y", 0)
                                put("layer", layer)
                                if (frame >= 0) put("frame", frame)
                            }
                        )
                    }
                    val result = callPhone(client, buildJsonArray { add(call) })
                    ToolResult(
                        text = "Placed '$path' on the canvas (${bitmap.width}×${bitmap.height}).",
                        data = result
                    )
                } else {
                    val strokes = traceStrokes(bitmap, detail)
                    if (strokes.isEmpty()) {
                        ToolResult(text = "No lines were found in '$path'.", ok = false)
                    } else {
                        val calls = buildJsonArray {
                            for (stroke in strokes) {
                                add(
                                    buildJsonObject {
                                        put("name", "anymate_draw_stroke")
                                        put(
                                            "args",
                                            buildJsonObject {
                                                put(
                                                    "points",
                                                    JsonArray(stroke.map { point ->
                                                        buildJsonArray {
                                                            add(kotlinx.serialization.json.JsonPrimitive(point.x))
                                                            add(kotlinx.serialization.json.JsonPrimitive(point.y))
                                                        }
                                                    })
                                                )
                                                put("color", color)
                                                put("width", width)
                                                put("tool", "pen")
                                                put("layer", layer)
                                                if (frame >= 0) put("frame", frame)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                        val result = callPhone(client, calls)
                        ToolResult(
                            text = "Traced '$path' into ${strokes.size} vector strokes ($detail).",
                            data = result
                        )
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }

        // ---- video -> animation --------------------------------------------
        registry.register(
            "anim_video_to_animation",
            "Turn a video into an animation on the phone. Frames are sampled at the requested rate " +
                "and pushed as new frames; mode='flat' keeps the original pixels, mode='sketch' " +
                "re-draws every frame as vector lines you can then edit.",
            ToolRegistry.obj(
                "path" to ToolRegistry.str("Video in the workspace"),
                "fps" to ToolRegistry.int("Frames per second to sample"),
                "max_frames" to ToolRegistry.int("Maximum frames to create"),
                "mode" to ToolRegistry.str("flat | sketch"),
                "detail" to ToolRegistry.str("easy | medium | detailed"),
                "color" to ToolRegistry.str("Line colour for sketch mode"),
                "layer" to ToolRegistry.int("Target layer index")
            ),
            "animation"
        ) { args ->
            val path = args["path"].textOr()
            val file = File(path).let { if (it.isAbsolute) it else File(workspace, path) }
            if (!file.exists()) return@register ToolResult(text = "'$path' does not exist.", ok = false)
            val fps = args["fps"].intOr(12).coerceIn(1, 60)
            val maxFrames = args["max_frames"].intOr(48).coerceIn(1, 240)
            val mode = args["mode"].textOr("flat")
            val detail = args["detail"].textOr("medium")
            val color = args["color"].textOr("#111827")
            val layer = args["layer"].intOr(0)
            val outputDir = File(workspace, "frames_" + file.nameWithoutExtension)
            val extracted = VideoTools.extractFrameFiles(file, outputDir, fps, maxFrames)
            if (extracted.isEmpty()) {
                return@register ToolResult(text = "No frames could be extracted from '$path'.", ok = false)
            }
            val calls = buildJsonArray {
                for (frameFile in extracted) {
                    add(
                        buildJsonObject {
                            put("name", "anymate_add_frame")
                            put("args", buildJsonObject { put("copy", false) })
                        }
                    )
                    val bitmap = BitmapFactory.decodeFile(frameFile.absolutePath)
                    if (bitmap == null) continue
                    if (mode == "flat") {
                        val bytes = ByteArrayOutputStream()
                            .also { bitmap.compress(Bitmap.CompressFormat.PNG, 92, it) }.toByteArray()
                        add(
                            buildJsonObject {
                                put("name", "anymate_draw_bitmap")
                                put(
                                    "args",
                                    buildJsonObject {
                                        put("png_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                                        put("x", 0)
                                        put("y", 0)
                                        put("layer", layer)
                                    }
                                )
                            }
                        )
                    } else {
                        for (stroke in traceStrokes(bitmap, detail)) {
                            add(
                                buildJsonObject {
                                    put("name", "anymate_draw_stroke")
                                    put(
                                        "args",
                                        buildJsonObject {
                                            put(
                                                "points",
                                                JsonArray(stroke.map { point ->
                                                    buildJsonArray {
                                                        add(kotlinx.serialization.json.JsonPrimitive(point.x))
                                                        add(kotlinx.serialization.json.JsonPrimitive(point.y))
                                                    }
                                                })
                                            )
                                            put("color", color)
                                            put("width", 4.0)
                                            put("tool", "pen")
                                            put("layer", layer)
                                        }
                                    )
                                }
                            )
                        }
                    }
                    bitmap.recycle()
                }
            }
            val result = callPhone(client, calls)
            ToolResult(
                text = "Rebuilt '${file.name}' as ${extracted.size} frames on the phone ($mode).",
                data = result
            )
        }

        // ---- edit the animation --------------------------------------------
        registry.register(
            "anim_edit_animation",
            "Change the animation that is already on the phone: action='speed' (fps), 'reverse', " +
                "'hold' (repeat each frame N times), 'tint' (recolour every element), 'flip' " +
                "(mirror or flip a frame) or 'tween' (add in-between frames).",
            ToolRegistry.obj(
                "action" to ToolRegistry.str("speed | reverse | hold | tint | flip | tween"),
                "value" to ToolRegistry.num("Value for the action (fps, repeats, alpha)"),
                "color" to ToolRegistry.str("Tint colour for 'tint'"),
                "axis" to ToolRegistry.str("horizontal | vertical for 'flip'"),
                "count" to ToolRegistry.int("How many in-between frames for 'tween'")
            ),
            "animation"
        ) { args ->
            val action = args["action"].textOr().lowercase()
            val value = args["value"].doubleOr(0.0)
            val color = args["color"].textOr("#7C3AED")
            val axis = args["axis"].textOr("horizontal")
            val count = args["count"].intOr(2)
            val tool = when (action) {
                "speed" -> "anymate_set_fps"
                "reverse" -> "anymate_reverse_frames"
                "hold" -> "anymate_hold_frames"
                "tint" -> "anymate_recolor_frame"
                "flip" -> "anymate_transform_frame"
                "tween" -> "anymate_interpolate_frames"
                else -> return@register ToolResult(text = "Unknown action '$action'.", ok = false)
            }
            val arguments = buildJsonObject {
                when (action) {
                    "speed" -> put("fps", value.toInt().coerceIn(1, 60))
                    "hold" -> put("count", value.toInt().coerceIn(1, 12))
                    "tint" -> put("color", color)
                    "flip" -> put("axis", axis)
                    "tween" -> put("count", count.coerceIn(1, 12))
                }
            }
            val result = callPhone(client, buildJsonArray {
                add(
                    buildJsonObject {
                        put("name", tool)
                        put("args", arguments)
                    }
                )
            })
            ToolResult(text = "Applied '$action' to the animation on the phone.", data = result)
        }
    }

    // ---- helpers ----------------------------------------------------------
    private suspend fun callPhone(client: () -> McpClient, calls: JsonArray): JsonObject {
        val results = ArrayList<String>()
        var index = 0
        while (index < calls.size) {
            val end = min(calls.size, index + 30)
            val slice = JsonArray(calls.toList().subList(index, end))
            try {
                val response = client().callTool("anymate_batch", buildJsonObject { put("calls", slice) })
                results.add(response.text)
            } catch (t: McpException) {
                throw IllegalStateException("The phone rejected the batch: ${t.message}")
            }
            index = end
        }
        return buildJsonObject { put("results", JsonArray(results.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
    }

    private data class Pt(val x: Float, val y: Float)

    /**
     * Traces a picture into polylines: grayscale -> blur -> Sobel -> edge mask
     * -> contour walk -> Douglas-Peucker simplification.
     */
    fun traceStrokes(bitmap: Bitmap, detail: String): List<List<Pt>> {
        val maxWidth = when (detail.lowercase()) {
            "easy" -> 220
            "detailed" -> 420
            else -> 320
        }
        val scale = min(1f, maxWidth.toFloat() / bitmap.width)
        val w = max(8, (bitmap.width * scale).toInt())
        val h = max(8, (bitmap.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        scaled.recycle()

        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            gray[i] = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF))
        }
        val blurred = boxBlur(gray, w, h, 1)
        val magnitude = FloatArray(w * h)
        var maxMagnitude = 0f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx =
                    -blurred[(y - 1) * w + x - 1] - 2f * blurred[y * w + x - 1] - blurred[(y + 1) * w + x - 1] +
                        blurred[(y - 1) * w + x + 1] + 2f * blurred[y * w + x + 1] + blurred[(y + 1) * w + x + 1]
                val gy =
                    -blurred[(y - 1) * w + x - 1] - 2f * blurred[(y - 1) * w + x] - blurred[(y - 1) * w + x + 1] +
                        blurred[(y + 1) * w + x - 1] + 2f * blurred[(y + 1) * w + x] + blurred[(y + 1) * w + x + 1]
                val m = kotlin.math.sqrt(gx * gx + gy * gy)
                magnitude[y * w + x] = m
                if (m > maxMagnitude) maxMagnitude = m
            }
        }
        val threshold = maxMagnitude * when (detail.lowercase()) {
            "easy" -> 0.28f
            "detailed" -> 0.10f
            else -> 0.18f
        }
        val edges = BooleanArray(w * h)
        for (i in magnitude.indices) {
            edges[i] = magnitude[i] > threshold && magnitude[i] > 12f
        }

        val visited = BooleanArray(w * h)
        val strokes = ArrayList<List<Pt>>()
        val inverse = 1f / max(scale, 0.0001f)
        for (start in edges.indices) {
            if (!edges[start] || visited[start]) continue
            val contour = traceContour(edges, visited, w, h, start)
            if (contour.size < 3) continue
            val simplified = simplify(contour, when (detail.lowercase()) {
                "easy" -> 2.6f
                "detailed" -> 1.0f
                else -> 1.6f
            })
            if (simplified.size < 2) continue
            strokes.add(simplified.map { Pt(it.x * inverse, it.y * inverse) })
            if (strokes.size > 900) break
        }
        return strokes
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var count = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val ny = y + dy
                        val nx = x + dx
                        if (ny in 0 until h && nx in 0 until w) {
                            sum += src[ny * w + nx]
                            count++
                        }
                    }
                }
                out[y * w + x] = if (count == 0) src[y * w + x] else sum / count
            }
        }
        return out
    }

    private fun traceContour(
        edges: BooleanArray,
        visited: BooleanArray,
        w: Int,
        h: Int,
        start: Int
    ): MutableList<Pt> {
        val offsets = intArrayOf(1, w + 1, w, w - 1, -1, -w - 1, -w, -w + 1)
        val contour = ArrayList<Pt>()
        var current = start
        var direction = 0
        visited[start] = true
        contour.add(Pt((start % w).toFloat(), (start / w).toFloat()))
        var guard = 0
        while (guard++ < 20000) {
            var found = false
            for (step in 1..8) {
                val nextDirection = (direction + step) % 8
                val nx = current % w + offsets[nextDirection] % w
                val ny = current / w + offsets[nextDirection] / w
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val index = ny * w + nx
                if (!edges[index]) continue
                if (index == start) return contour
                current = index
                direction = nextDirection
                visited[index] = true
                contour.add(Pt(nx.toFloat(), ny.toFloat()))
                found = true
                break
            }
            if (!found) break
        }
        return contour
    }

    private fun simplify(points: List<Pt>, epsilon: Float): List<Pt> {
        if (points.size < 3) return points
        var maxDistance = 0f
        var index = 0
        val first = points.first()
        val last = points.last()
        for (i in 1 until points.size - 1) {
            val distance = distanceToSegment(points[i], first, last)
            if (distance > maxDistance) {
                maxDistance = distance
                index = i
            }
        }
        if (maxDistance > epsilon) {
            val left = simplify(points.subList(0, index + 1), epsilon)
            val right = simplify(points.subList(index, points.size - 1), epsilon)
            return left.dropLast(1) + right
        }
        return listOf(first, last)
    }

    private fun distanceToSegment(point: Pt, start: Pt, end: Pt): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return kotlin.math.hypot(point.x - start.x, point.y - start.y)
        var t = ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared
        t = t.coerceIn(0f, 1f)
        val px = start.x + t * dx
        val py = start.y + t * dy
        return kotlin.math.hypot(point.x - px, point.y - py)
    }
}
