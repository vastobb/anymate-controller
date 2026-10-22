package com.anymate.controller.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Workspace file tools — everything stays inside the controller's workspace. */
object FileTools {

    fun register(registry: ToolRegistry, workspace: File) {
        registry.register(
            "files_list",
            "List the files in the controller workspace.",
            ToolRegistry.obj(
                "pattern" to ToolRegistry.str("Glob filter such as '*.png'"),
                "directory" to ToolRegistry.str("Directory relative to the workspace")
            ),
            "files"
        ) { args ->
            val pattern = args["pattern"]?.toString()?.trim('"') ?: "*"
            val directory = args["directory"]?.toString()?.trim('"') ?: "."
            val root = File(workspace, directory)
            if (!root.exists()) {
                ToolResult(text = "'$directory' does not exist.", ok = false)
            } else {
                val files = root.walkTopDown()
                    .filter { it.isFile && (pattern == "*" || it.name.contains(pattern.removePrefix("*").removeSuffix("*"))) }
                    .map { it.relativeTo(workspace).path }
                    .sorted()
                    .take(400)
                ToolResult(
                    text = files.joinToString("\n").ifBlank { "(empty)"},
                    data = buildJsonObject {
                        put("count", files.size)
                        put("root", workspace.absolutePath)
                    }
                )
            }
        }

        registry.register(
            "files_read",
            "Read a text file from the workspace.",
            ToolRegistry.obj("path" to ToolRegistry.str("File path")),
            "files"
        ) { args ->
            val path = args["path"]?.toString()?.trim('"') ?: ""
            val file = File(workspace, path)
            if (!file.exists() || !file.isFile) {
                ToolResult(text = "'$path' does not exist.", ok = false)
            } else {
                val text = file.readText()
                ToolResult(
                    text = text.take(40_000),
                    data = buildJsonObject {
                        put("path", file.absolutePath)
                        put("bytes", file.length())
                    }
                )
            }
        }

        registry.register(
            "files_write",
            "Write a text file into the workspace.",
            ToolRegistry.obj(
                "path" to ToolRegistry.str("File path"),
                "content" to ToolRegistry.str("File contents"),
                "append" to ToolRegistry.bool("Append instead of overwrite")
            ),
            "files"
        ) { args ->
            val path = args["path"]?.toString()?.trim('"') ?: ""
            val content = args["content"]?.toString()?.trim('"') ?: ""
            val append = args["append"]?.toBoolean() ?: false
            val file = File(workspace, path)
            file.parentFile?.mkdirs()
            if (append) file.appendText(content) else file.writeText(content)
            ToolResult(
                text = "Wrote ${file.length()} bytes to ${file.name}.",
                data = buildJsonObject { put("path", file.absolutePath) }
            )
        }
    }
}
