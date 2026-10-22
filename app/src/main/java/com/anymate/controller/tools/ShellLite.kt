package com.anymate.controller.tools

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ShellResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0
) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * A small POSIX-ish shell that works entirely inside the app's workspace.
 * Supports pipelines, redirection and the file commands an agent actually
 * reaches for — no native binary required.
 */
object ShellLite {

    private val commands = listOf(
        "help", "ls", "cd", "pwd", "cat", "head", "tail", "echo", "mkdir", "rm",
        "cp", "mv", "touch", "wc", "grep", "find", "tree", "date", "whoami",
        "env", "which", "clear", "stat", "du"
    )

    fun run(workspace: File, commandLine: String): ShellResult {
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) return ShellResult()
        val stages = splitPipes(trimmed)
        var stdin = ""
        var stderr = ""
        var exit = 0
        for ((index, stage) in stages.withIndex()) {
            val isLast = index == stages.lastIndex
            val parsed = parseStage(stage, isLast)
            val result = execute(workspace, parsed.command, parsed.args, stdin)
            stdin = result.stdout
            if (result.stderr.isNotBlank()) stderr = stderr + result.stderr + "\n"
            exit = result.exitCode
            val redirect = parsed.redirect
            if (redirect != null) {
                val target = safePath(workspace, redirect)
                if (target == null) {
                    stderr = stderr + "refusing to write outside the workspace\n"
                    exit = 1
                } else {
                    target.parentFile?.mkdirs()
                    val content = if (parsed.append) {
                        val existing = if (target.exists()) target.readText() else ""
                        existing + stdin
                    } else {
                        stdin
                    }
                    target.writeText(content)
                    stdin = ""
                }
            }
            if (exit != 0) break
        }
        return ShellResult(stdout = stdin, stderr = stderr.trimEnd(), exitCode = exit)
    }

    // ---- parsing ----------------------------------------------------------
    private fun splitPipes(command: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var quote = ' '
        for (char in command) {
            if (quote != ' ') {
                current.append(char)
                if (char == quote) quote = ' '
                continue
            }
            when (char) {
                '"', '\'' -> {
                    current.append(char)
                    quote = char
                }
                '|' -> {
                    parts.add(current.toString())
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        parts.add(current.toString())
        val result = ArrayList<String>()
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) result.add(trimmed)
        }
        return result
    }

    private class Stage(
        val command: String,
        val args: List<String>,
        val redirect: String?,
        val append: Boolean
    )

    private fun parseStage(stage: String, allowRedirect: Boolean): Stage {
        var text = stage
        var redirect: String? = null
        var append = false
        if (allowRedirect) {
            val index = text.indexOf(">")
            if (index >= 0) {
                append = text.getOrNull(index + 1) == '>'
                val skip = if (append) 2 else 1
                val target = text.substring(index + skip).trim().trim('"')
                redirect = target
                text = text.substring(0, index).trim()
            }
        }
        val tokens = tokenize(text)
        val command = if (tokens.isEmpty()) "" else tokens[0]
        val args = ArrayList<String>()
        for (index in 1 until tokens.size) args.add(tokens[index])
        return Stage(command, args, redirect, append)
    }

    private fun tokenize(text: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var quote = ' '
        for (char in text) {
            if (quote != ' ') {
                if (char == quote) {
                    quote = ' '
                } else {
                    current.append(char)
                }
                continue
            }
            when (char) {
                '"', '\'' -> quote = char
                ' ', '\t' -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private fun safePath(workspace: File, raw: String): File? {
        val candidate = File(raw)
        val resolved = if (candidate.isAbsolute) candidate.canonicalFile
        else File(workspace, raw).canonicalFile
        val root = workspace.canonicalFile
        return if (isInside(root, resolved)) resolved else null
    }

    private fun isInside(root: File, candidate: File): Boolean {
        var current: File? = candidate
        while (current != null) {
            if (current == root) return true
            current = current.parentFile
        }
        return false
    }

    // ---- execution --------------------------------------------------------
    private fun execute(workspace: File, command: String, args: List<String>, stdin: String): ShellResult {
        return when (command) {
            "help" -> help()
            "ls" -> listFiles(workspace, args)
            "cd" -> changeDirectory(workspace, args)
            "pwd" -> ShellResult(stdout = workspace.canonicalPath + "\n")
            "cat" -> cat(workspace, args, stdin)
            "head" -> head(workspace, args, stdin)
            "tail" -> tail(workspace, args, stdin)
            "echo" -> ShellResult(stdout = args.joinToString(" ") + "\n")
            "mkdir" -> makeDirectory(workspace, args)
            "rm" -> remove(workspace, args)
            "cp" -> copyOrMove(workspace, args, false)
            "mv" -> copyOrMove(workspace, args, true)
            "touch" -> touch(workspace, args)
            "wc" -> wordCount(workspace, args, stdin)
            "grep" -> grep(workspace, args, stdin)
            "find" -> find(workspace, args)
            "tree" -> tree(workspace)
            "date" -> ShellResult(stdout = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "\n")
            "whoami" -> ShellResult(stdout = "artist\n")
            "env" -> ShellResult(stdout = "workspace=" + workspace.canonicalPath + "\n")
            "which" -> ShellResult(stdout = whichCommand(args))
            "clear" -> ShellResult(stdout = "")
            "stat" -> fileStat(workspace, args)
            "du" -> ShellResult(stdout = diskUsage(workspace, args))
            "python", "py" -> PythonLite.run(workspace, args.joinToString(" "))
            "mcp" -> ShellResult(stderr = "Call phone tools from the Chat tab, or use: mcp <tool> <json>\n")
            else -> ShellResult(stderr = "unknown command: " + command + "\n", exitCode = 127)
        }
    }

    private fun help(): ShellResult {
        val lines = ArrayList<String>()
        lines.add("AnyMate Controller shell - commands:")
        lines.add(commands.joinToString("  "))
        lines.add("")
        lines.add("Pipes (a | b), redirection (> file, >> file) and quoting are supported.")
        lines.add("python <code>   run a Python snippet")
        lines.add("mcp <tool> <json>   call a tool on the phone")
        return ShellResult(stdout = lines.joinToString("\n") + "\n")
    }

    private fun whichCommand(args: List<String>): String {
        val wanted = args.firstOrNull()
        val found = commands.filter { it == wanted }
        return found.joinToString("\n") + "\n"
    }

    private fun listFiles(workspace: File, args: List<String>): ShellResult {
        val long = args.contains("-l")
        val raw = args.firstOrNull { !it.startsWith("-") } ?: "."
        val path = safePath(workspace, raw)
        if (path == null) return ShellResult(stderr = "bad path\n", exitCode = 1)
        if (!path.exists()) return ShellResult(stderr = "no such path: " + path.name + "\n", exitCode = 1)
        val entries: List<File> = if (path.isDirectory) {
            val list = path.listFiles()
            if (list == null) emptyList() else list.sortedBy { it.name }
        } else {
            listOf(path)
        }
        if (entries.isEmpty()) return ShellResult(stdout = "")
        val rendered = ArrayList<String>()
        for (entry in entries) {
            if (long) {
                val size = if (entry.isDirectory) "-" else entry.length().toString()
                val marker = if (entry.isDirectory) "d" else "-"
                rendered.add(size + "\t" + marker + " " + entry.name)
            } else {
                rendered.add(entry.name + if (entry.isDirectory) "/" else "")
            }
        }
        return ShellResult(stdout = rendered.joinToString("\n") + "\n")
    }

    private fun changeDirectory(workspace: File, args: List<String>): ShellResult {
        val raw = args.firstOrNull() ?: "."
        val path = safePath(workspace, raw)
            ?: return ShellResult(stderr = "path outside the workspace\n", exitCode = 1)
        return ShellResult(stdout = path.absolutePath + "\n")
    }

    private fun readFiles(workspace: File, files: List<String>): String {
        val builder = StringBuilder()
        for (name in files) {
            val path = safePath(workspace, name) ?: continue
            if (!path.exists() || !path.isFile) continue
            builder.append(path.readText())
        }
        return builder.toString()
    }

    private fun cat(workspace: File, args: List<String>, stdin: String): ShellResult {
        if (args.isEmpty()) return ShellResult(stdout = stdin)
        val builder = StringBuilder()
        for (name in args) {
            val path = safePath(workspace, name)
            if (path == null) return ShellResult(stderr = "bad path: " + name + "\n", exitCode = 1)
            if (!path.exists()) return ShellResult(stderr = "no such file: " + name + "\n", exitCode = 1)
            builder.append(path.readText())
        }
        return ShellResult(stdout = builder.toString())
    }

    private fun head(workspace: File, args: List<String>, stdin: String): ShellResult {
        val count = args.firstOrNull { it.toIntOrNull() != null }?.toInt() ?: 10
        val files = args.filter { it.toIntOrNull() == null }
        val text = if (files.isEmpty()) stdin else readFiles(workspace, files)
        val lines = text.split("\n")
        val result = lines.take(count.coerceAtLeast(0)).joinToString("\n")
        return ShellResult(stdout = result + "\n")
    }

    private fun tail(workspace: File, args: List<String>, stdin: String): ShellResult {
        val count = args.firstOrNull { it.toIntOrNull() != null }?.toInt() ?: 10
        val files = args.filter { it.toIntOrNull() == null }
        val text = if (files.isEmpty()) stdin else readFiles(workspace, files)
        val lines = text.split("\n")
        val result = lines.takeLast(count.coerceAtLeast(0)).joinToString("\n")
        return ShellResult(stdout = result + "\n")
    }

    private fun makeDirectory(workspace: File, args: List<String>): ShellResult {
        for (name in args.filter { !it.startsWith("-") }) {
            val path = safePath(workspace, name)
                ?: return ShellResult(stderr = "bad path\n", exitCode = 1)
            if (!path.mkdirs() && !path.exists()) {
                return ShellResult(stderr = "cannot create " + name + "\n", exitCode = 1)
            }
        }
        return ShellResult()
    }

    private fun remove(workspace: File, args: List<String>): ShellResult {
        val recursive = args.contains("-r") || args.contains("-rf")
        for (name in args.filter { !it.startsWith("-") }) {
            val path = safePath(workspace, name)
                ?: return ShellResult(stderr = "bad path\n", exitCode = 1)
            if (!path.exists()) continue
            val removed = if (path.isDirectory && recursive) path.deleteRecursively() else path.delete()
            if (!removed) return ShellResult(stderr = "cannot remove " + name + "\n", exitCode = 1)
        }
        return ShellResult()
    }

    private fun copyOrMove(workspace: File, args: List<String>, move: Boolean): ShellResult {
        if (args.size < 2) {
            val usage = if (move) "mv source target" else "cp source target"
            return ShellResult(stderr = "usage: " + usage + "\n", exitCode = 1)
        }
        val source = safePath(workspace, args[0])
            ?: return ShellResult(stderr = "bad source\n", exitCode = 1)
        var target = safePath(workspace, args[1])
            ?: return ShellResult(stderr = "bad target\n", exitCode = 1)
        if (!source.exists()) return ShellResult(stderr = "no such file: " + args[0] + "\n", exitCode = 1)
        if (target.isDirectory) target = File(target, source.name)
        target.parentFile?.mkdirs()
        if (move) {
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
        } else {
            source.copyTo(target, overwrite = true)
        }
        return ShellResult(stdout = source.name + " -> " + target.name + "\n")
    }

    private fun touch(workspace: File, args: List<String>): ShellResult {
        for (name in args.filter { !it.startsWith("-") }) {
            val path = safePath(workspace, name) ?: continue
            path.parentFile?.mkdirs()
            if (!path.exists()) path.writeText("")
        }
        return ShellResult()
    }

    private fun wordCount(workspace: File, args: List<String>, stdin: String): ShellResult {
        val files = args.filter { !it.startsWith("-") }
        val text = if (files.isEmpty()) stdin else readFiles(workspace, files)
        val lines = if (text.isEmpty()) 0 else text.trimEnd().split("\n").size
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        return ShellResult(stdout = lines.toString() + " " + words + " " + text.length + "\n")
    }

    private fun grep(workspace: File, args: List<String>, stdin: String): ShellResult {
        val pattern = args.firstOrNull { !it.startsWith("-") }
            ?: return ShellResult(stderr = "usage: grep pattern [file]\n", exitCode = 1)
        val ignoreCase = args.contains("-i")
        val files = args.filter { it != pattern && !it.startsWith("-") }
        val text = if (files.isEmpty()) stdin else readFiles(workspace, files)
        val regex = try {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (t: Throwable) {
            return ShellResult(stderr = "bad pattern: " + pattern + "\n", exitCode = 2)
        }
        val hits = ArrayList<String>()
        for (line in text.split("\n")) {
            if (regex.containsMatchIn(line)) hits.add(line)
        }
        if (hits.isEmpty()) return ShellResult(stdout = "")
        return ShellResult(stdout = hits.joinToString("\n") + "\n")
    }

    private fun find(workspace: File, args: List<String>): ShellResult {
        val pattern = args.firstOrNull { !it.startsWith("-") } ?: "."
        val glob = args.getOrNull(1) ?: "*"
        val base = safePath(workspace, pattern)
            ?: return ShellResult(stderr = "bad path\n", exitCode = 1)
        val needle = glob.replace("*", "")
        val matches = ArrayList<String>()
        base.walkTopDown().forEach { file ->
            if (file.isFile && (needle.isEmpty() || file.name.contains(needle))) {
                matches.add(file.relativeTo(workspace).path)
            }
        }
        if (matches.isEmpty()) return ShellResult(stdout = "")
        return ShellResult(stdout = matches.joinToString("\n") + "\n")
    }

    private fun tree(workspace: File): ShellResult {
        val builder = StringBuilder()
        builder.append(workspace.name).append("\n")
        walkTree(workspace, "", builder)
        return ShellResult(stdout = builder.toString())
    }

    private fun walkTree(directory: File, prefix: String, builder: StringBuilder) {
        val list = directory.listFiles()
        val children = if (list == null) emptyList() else list.sortedBy { it.name }
        for (index in children.indices) {
            val child = children[index]
            val last = index == children.lastIndex
            builder.append(prefix)
            if (last) builder.append("    ") else builder.append("|   ")
            builder.append(if (last) "|-- " else "|-- ")
            builder.append(child.name).append("\n")
            if (child.isDirectory) walkTree(child, prefix + if (last) "    " else "|   ", builder)
        }
    }

    private fun fileStat(workspace: File, args: List<String>): ShellResult {
        val raw = args.firstOrNull() ?: "."
        val path = safePath(workspace, raw)
            ?: return ShellResult(stderr = "bad path\n", exitCode = 1)
        if (!path.exists()) return ShellResult(stderr = "no such path\n", exitCode = 1)
        val size = if (path.isDirectory) "-" else path.length().toString()
        val modified = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(path.lastModified()))
        val text = "name      " + path.name + "\n" +
            "type      " + (if (path.isDirectory) "directory" else "file") + "\n" +
            "size      " + size + "\n" +
            "modified  " + modified + "\n"
        return ShellResult(stdout = text)
    }

    private fun diskUsage(workspace: File, args: List<String>): String {
        val raw = args.firstOrNull() ?: "."
        val path = safePath(workspace, raw) ?: return "0 KB\n"
        val bytes = if (path.isFile) path.length() else path.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return (bytes / 1024).toString() + " KB\t" + path.name + "\n"
    }
}
