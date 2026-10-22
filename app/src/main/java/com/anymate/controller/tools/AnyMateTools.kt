package com.anymate.controller.tools

import com.anymate.controller.mcp.McpClient
import com.anymate.controller.mcp.McpException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Proxy tools for the AnyMate app. Every mutation lands on the artist's
 * canvas immediately, and each batch is a single undo step on the phone.
 */
object AnyMateTools {

    fun register(
        registry: ToolRegistry,
        client: () -> McpClient,
        workspace: java.io.File
    ) {
        fun call(tool: String, arguments: JsonObject = JsonObject(emptyMap())): ToolResult = try {
            val result = client().callTool(tool, arguments)
            ToolResult(text = result.text, image = result.image, mime = result.mime, ok = !result.isError)
        } catch (t: McpException) {
            ToolResult(text = "AnyMate is unreachable: ${t.message}", ok = false)
        } catch (t: Throwable) {
            ToolResult(text = "$tool failed: ${t.message}", ok = false)
        }

        registry.register(
            "anymate_status",
            "Check that the AnyMate app is reachable over MCP and list the tools it exposes.",
            ToolRegistry.obj(),
            "phone"
        ) { _ ->
            try {
                val health = client().health()
                val tools = client().listTools()
                ToolResult(
                    text = "AnyMate is reachable.\n$health\nDevice tools: ${tools.size}\n" +
                        tools.joinToString("\n") { "• ${it.name} — ${it.description.take(90)}" }
                )
            } catch (t: Throwable) {
                ToolResult(
                    text = "AnyMate is not reachable: ${t.message}\nCheck Settings → phone in the controller.",
                    ok = false
                )
            }
        }

        registry.register(
            "anymate_state",
            "Read the live project state from the phone: canvas size, fps, frames, layers and tool settings.",
            ToolRegistry.obj(),
            "phone"
        ) { _ -> call("anymate_get_state") }

        registry.register(
            "anymate_draw",
            "Draw on the phone. Shapes, strokes and text are sent as ONE undoable batch.",
            ToolRegistry.obj(
                "shapes" to ToolRegistry.arr(
                    "Shapes: shape (line/rect/ellipse/arrow), x1, y1, x2, y2, fill, stroke, width.",
                    ToolRegistry.obj()
                ),
                "strokes" to ToolRegistry.arr(
                    "Strokes: points [[x,y],...], color, width, tool.",
                    ToolRegistry.obj()
                ),
                "text" to ToolRegistry.arr(
                    "Text blocks: text, x, y, size, color.",
                    ToolRegistry.obj()
                ),
                "frame" to ToolRegistry.int("Frame index; -1 = current"),
                "layer" to ToolRegistry.int("Layer index; -1 = current")
            ),
            "phone"
        ) { args ->
            val calls = buildJsonArray {
                points(args["shapes"]) { obj ->
                    add(
                        buildJsonObject {
                            put("name", "anymate_draw_shape")
                            put(
                                "args",
                                buildJsonObject {
                                    put("shape", obj["shape"]?.toString()?.trim('"') ?: "ellipse")
                                    put("x1", obj["x1"]?.toDoubleOrNull() ?: 0.0)
                                    put("y1", obj["y1"]?.toDoubleOrNull() ?: 0.0)
                                    put("x2", obj["x2"]?.toDoubleOrNull() ?: 0.0)
                                    put("y2", obj["y2"]?.toDoubleOrNull() ?: 0.0)
                                    put("width", obj["width"]?.toDoubleOrNull() ?: 4.0)
                                    obj["fill"]?.let { put("fill_color", it.toString().trim('"')) }
                                    obj["stroke"]?.let { put("stroke_color", it.toString().trim('"')) }
                                    putFrameLayer(this, args)
                                }
                            )
                        }
                    )
                }
                points(args["strokes"]) { obj ->
                    add(
                        buildJsonObject {
                            put("name", "anymate_draw_stroke")
                            put(
                                "args",
                                buildJsonObject {
                                    put("points", obj["points"] ?: JsonArray(emptyList()))
                                    put("color", obj["color"]?.toString()?.trim('"') ?: "#7C3AED")
                                    put("width", obj["width"]?.toDoubleOrNull() ?: 4.0)
                                    put("tool", obj["tool"]?.toString()?.trim('"') ?: "pen")
                                    putFrameLayer(this, args)
                                }
                            )
                        }
                    )
                }
                points(args["text"]) { obj ->
                    add(
                        buildJsonObject {
                            put("name", "anymate_draw_text")
                            put(
                                "args",
                                buildJsonObject {
                                    put("text", obj["text"]?.toString()?.trim('"') ?: "")
                                    put("x", obj["x"]?.toDoubleOrNull() ?: 0.0)
                                    put("y", obj["y"]?.toDoubleOrNull() ?: 0.0)
                                    put("size", obj["size"]?.toDoubleOrNull() ?: 64.0)
                                    put("color", obj["color"]?.toString()?.trim('"') ?: "#111827")
                                    putFrameLayer(this, args)
                                }
                            )
                        }
                    )
                }
            }
            if (calls.isEmpty()) {
                ToolResult(text = "Nothing to draw — provide shapes, strokes or text.", ok = false)
            } else {
                call("anymate_batch", buildJsonObject { put("calls", calls) })
            }
        }

        registry.register(
            "anymate_frames",
            "Manage the phone timeline: add, duplicate, delete, reorder, clear or list frames.",
            ToolRegistry.obj(
                "action" to ToolRegistry.str("add | duplicate | delete | reorder | clear | list"),
                "index" to ToolRegistry.int("Frame index"),
                "count" to ToolRegistry.int("How many frames, or the destination index for reorder"),
                "copy" to ToolRegistry.bool("Copy the current frame when adding")
            ),
            "phone"
        ) { args ->
            val action = args["action"]?.toString()?.trim('"')?.lowercase() ?: "add"
            val index = args["index"]?.toIntOrNull() ?: -1
            when (action) {
                "add" -> {
                    val count = (args["count"]?.toIntOrNull() ?: 1).coerceIn(1, 200)
                    var last: ToolResult? = null
                    repeat(count) {
                        last = call(
                            "anymate_add_frame",
                            buildJsonObject {
                                if (index >= 0) put("index", index)
                                put("copy", args["copy"]?.toBoolean() ?: false)
                            }
                        )
                    }
                    last ?: ToolResult(text = "No frames added.")
                }
                "duplicate" -> call("anymate_duplicate_frame", buildJsonObject { if (index >= 0) put("index", index) })
                "delete" -> call("anymate_delete_frame", buildJsonObject { if (index >= 0) put("index", index) })
                "reorder" -> call(
                    "anymate_reorder_frame",
                    buildJsonObject {
                        put("from", index.coerceAtLeast(0))
                        put("to", args["count"]?.toIntOrNull() ?: index)
                    }
                )
                "clear" -> call("anymate_clear_frame", buildJsonObject { if (index >= 0) put("index", index) })
                "list" -> call("anymate_list_frames")
                else -> ToolResult(text = "Unknown action '$action'.", ok = false)
            }
        }

        registry.register(
            "anymate_playback",
            "Control playback on the phone: play, pause, seek, fps, loop or onion skin.",
            ToolRegistry.obj(
                "action" to ToolRegistry.str("play | pause | seek | fps | loop | onion"),
                "index" to ToolRegistry.int("Frame to seek to"),
                "fps" to ToolRegistry.int("Frames per second"),
                "enabled" to ToolRegistry.bool("Enable loop / onion skin")
            ),
            "phone"
        ) { args ->
            val action = args["action"]?.toString()?.trim('"')?.lowercase() ?: "play"
            when (action) {
                "play" -> call("anymate_play")
                "pause" -> call("anymate_pause")
                "seek" -> call("anymate_seek", buildJsonObject { put("index", args["index"]?.toIntOrNull() ?: 0) })
                "fps" -> call("anymate_set_fps", buildJsonObject { put("fps", args["fps"]?.toIntOrNull() ?: 24) })
                "loop" -> call("anymate_set_loop", buildJsonObject { put("enabled", args["enabled"]?.toBoolean() ?: true) })
                "onion" -> call("anymate_set_onion_skin", buildJsonObject { put("enabled", args["enabled"]?.toBoolean() ?: true) })
                else -> ToolResult(text = "Unknown action '$action'.", ok = false)
            }
        }

        registry.register(
            "anymate_layers",
            "Manage layers on the phone: list, add, delete, rename, opacity, visibility or merge.",
            ToolRegistry.obj(
                "action" to ToolRegistry.str("list | add | delete | set | merge"),
                "index" to ToolRegistry.int("Layer index"),
                "name" to ToolRegistry.str("Layer name"),
                "visible" to ToolRegistry.bool("Visibility"),
                "opacity" to ToolRegistry.num("Opacity 0..1")
            ),
            "phone"
        ) { args ->
            val action = args["action"]?.toString()?.trim('"')?.lowercase() ?: "list"
            val index = args["index"]?.toIntOrNull() ?: -1
            when (action) {
                "list" -> call("anymate_get_state")
                "add" -> call("anymate_add_layer", buildJsonObject { args["name"]?.let { put("name", it.toString().trim('"')) } })
                "delete" -> call("anymate_delete_layer", buildJsonObject { if (index >= 0) put("index", index) })
                "merge" -> call("anymate_merge_layers", buildJsonObject { if (index >= 0) put("frame", index) })
                "set" -> call(
                    "anymate_set_layer",
                    buildJsonObject {
                        if (index >= 0) put("index", index)
                        args["name"]?.let { put("name", it.toString().trim('"')) }
                        args["visible"]?.let { put("visible", it.toBoolean()) }
                        args["opacity"]?.let { put("opacity", it.toDouble()) }
                    }
                )
                else -> ToolResult(text = "Unknown action '$action'.", ok = false)
            }
        }

        registry.register(
            "anymate_style",
            "Set the active colour, tool, brush size or shape on the phone.",
            ToolRegistry.obj(
                "color" to ToolRegistry.str("Hex colour"),
                "tool" to ToolRegistry.str("pen | pencil | brush | eraser | shape"),
                "size" to ToolRegistry.num("Brush size in pixels"),
                "opacity" to ToolRegistry.num("Brush opacity"),
                "shape" to ToolRegistry.str("line | rect | ellipse | arrow")
            ),
            "phone"
        ) { args ->
            val calls = buildJsonArray {
                args["color"]?.let {
                    add(
                        buildJsonObject {
                            put("name", "anymate_set_color")
                            put("args", buildJsonObject { put("color", it.toString().trim('"')) })
                        }
                    )
                }
                args["tool"]?.let { tool ->
                    add(
                        buildJsonObject {
                            put("name", "anymate_set_tool")
                            put(
                                "args",
                                buildJsonObject {
                                    put("tool", tool.toString().trim('"'))
                                    args["size"]?.let { put("size", it.toDouble()) }
                                    args["opacity"]?.let { put("opacity", it.toDouble()) }
                                    args["shape"]?.let { put("shape", it.toString().trim('"')) }
                                }
                            )
                        }
                    )
                }
            }
            if (calls.isEmpty()) {
                ToolResult(text = "Provide a colour or a tool.", ok = false)
            } else {
                call("anymate_batch", buildJsonObject { put("calls", calls) })
            }
        }

        registry.register(
            "anymate_snapshot",
            "Take a PNG snapshot of the phone's canvas so you can see exactly what the artist sees.",
            ToolRegistry.obj(
                "frame" to ToolRegistry.int("Frame index; -1 = current"),
                "max_size" to ToolRegistry.int("Longest edge in pixels")
            ),
            "phone"
        ) { args ->
            val arguments = buildJsonObject {
                args["frame"]?.let { if ((it.toIntOrNull() ?: -1) >= 0) put("frame", it.toInt()) }
                put("max_size", args["max_size"]?.toIntOrNull() ?: 768)
            }
            call("anymate_snapshot", arguments)
        }

        registry.register(
            "anymate_sprite_sheet",
            "Render every frame on the phone into one contact sheet image.",
            ToolRegistry.obj(
                "columns" to ToolRegistry.int("Frames per row"),
                "cell_size" to ToolRegistry.int("Cell size in pixels")
            ),
            "phone"
        ) { args ->
            call(
                "anymate_render_sprite_sheet",
                buildJsonObject {
                    put("columns", args["columns"]?.toIntOrNull() ?: 6)
                    put("cell_size", args["cell_size"]?.toIntOrNull() ?: 180)
                }
            )
        }

        registry.register(
            "anymate_export",
            "Export the animation on the phone as a GIF, a PNG or a PNG sequence ZIP.",
            ToolRegistry.obj(
                "kind" to ToolRegistry.str("gif | png | zip"),
                "max_size" to ToolRegistry.int("GIF size in pixels")
            ),
            "phone"
        ) { args ->
            val kind = args["kind"]?.toString()?.trim('"')?.lowercase() ?: "gif"
            val tool = when (kind) {
                "png" -> "anymate_export_png"
                "zip" -> "anymate_export_frames"
                else -> "anymate_export_gif"
            }
            call(tool, buildJsonObject { if (kind == "gif") put("max_size", args["max_size"]?.toIntOrNull() ?: 720) })
        }

        registry.register(
            "anymate_motion_preset",
            "Generate a complete looping animation on the phone using a built-in motion system. " +
                "The frames are painted live so the artist watches the animation build. " +
                "Presets: ${Presets.names().joinToString(", ")}.",
            ToolRegistry.obj(
                "preset" to ToolRegistry.str("Motion system"),
                "frames" to ToolRegistry.int("How many frames to create"),
                "fps" to ToolRegistry.int("Playback frame rate"),
                "palette" to ToolRegistry.strArray("Hex colours"),
                "message" to ToolRegistry.str("Text for the kinetic-type preset"),
                "pace" to ToolRegistry.bool("Paint in chunks so the artist sees it build")
            ),
            "phone"
        ) { args ->
            val preset = args["preset"]?.toString()?.trim('"') ?: "bounce"
            val frames = (args["frames"]?.toIntOrNull() ?: 48).coerceIn(4, 240)
            val fps = (args["fps"]?.toIntOrNull() ?: 24).coerceIn(1, 60)
            val palette = (args["palette"] as? JsonArray)?.mapNotNull { it.toString().trim('"') } ?: emptyList()
            val message = args["message"]?.toString()?.trim('"') ?: "ANYMATE"
            val state = call("anymate_get_state")
            val width = Regex("\"width\"\\s*:\\s*([0-9.]+)").find(state.text)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 900.0
            val height = Regex("\"height\"\\s*:\\s*([0-9.]+)").find(state.text)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 900.0
            val built = try {
                Presets.build(preset, width, height, frames, palette, message)
            } catch (t: Throwable) {
                return@register ToolResult(text = t.message ?: "Unknown preset.", ok = false)
            }
            val calls = buildJsonArray {
                add(
                    buildJsonObject {
                        put("name", "anymate_set_fps")
                        put("args", buildJsonObject { put("fps", fps) })
                    }
                )
                built.forEach { frame ->
                    add(
                        buildJsonObject {
                            put("name", "anymate_add_frame")
                            put("args", buildJsonObject { put("copy", false) })
                        }
                    )
                    frame.forEach { element ->
                        add(
                            buildJsonObject {
                                put("name", "anymate_draw_shape")
                                put("args", element)
                            }
                        )
                    }
                }
            }
            val pace = args["pace"]?.toBoolean() ?: true
            val chunk = 6
            var sent = 0
            var index = 0
            while (index < calls.size) {
                val slice = JsonArray(calls.toList().subList(index, minOf(index + chunk, calls.size)))
                val result = call("anymate_batch", buildJsonObject { put("calls", slice) })
                if (!result.ok) return@register result
                sent += slice.size
                index += chunk
                if (pace && index < calls.size) Thread.sleep(90)
            }
            call("anymate_play")
            ToolResult(
                text = "Built '$preset' — $frames frames at $fps fps on the phone ($sent tool calls). Playback is running on the canvas."
            )
        }
    }

    private fun points(value: Any?, action: (JsonObject) -> Unit) {
        val array = value as? JsonArray ?: return
        array.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            action(obj)
        }
    }

    private fun putFrameLayer(builder: kotlinx.serialization.json.JsonObjectBuilder, args: JsonObject) {
        args["frame"]?.toIntOrNull()?.let { if (it >= 0) builder.put("frame", it) }
        args["layer"]?.toIntOrNull()?.let { if (it >= 0) builder.put("layer", it) }
    }
}
