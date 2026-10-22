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
            val (command, args, redirect, append) = parseStage(stage, isLast)
            val result = execute(workspace, command, args, stdin)
            stdin = result.stdout
            if (result.stderr.isNotBlank()) stderr += result.stderr + "\n"
            exit = result.exitCode
            if (redirect != null) {
                val target = safePath(workspace, redirect)
                if (target != null) {
                    target.parentFile?.mkdirs()
                    val content = if (append) {
                        (if (target.exists()) target.readText() else "") + stdin
                    } else {
                        stdin
                    }
                    target.writeText(content)
                    stdin = ""
                } else {
                    stderr += "refusing to write outside the workspace\n"
                    exit = 1
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
        var quote: Char? = null
        for (char in command) {
            when {
                quote != null -> {
                    current.append(char)
                    if (char == quote) quote = null
                }
                char == '"' || char == '\'' -> {
                    current.append(char)
                    quote = char
                }
                char == '|' -> {
                    parts.add(current.toString())
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        parts.add(current.toString())
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private data class Stage(
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
                val target = text.substring(index + if (append) 2 else 1).trim()
                redirect = target.removeSurrounding("\"")
                text = text.substring(0, index).trim()
            }
        }
        val tokens = tokenize(text)
        val command = tokens.firstOrNull().orEmpty()
        val args = tokens.drop(1)
        return Stage(command, args, redirect, append)
    }

    private fun tokenize(text: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (char in text) {
            when {
                quote != null -> {
                    if (char == quote) quote = null else current.append(char)
                }
                char == '"' || char == '\'' -> quote = char
                char.isWhitespace() -> {
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
        return if (resolved == root || root in resolved.parents) resolved else null
    }

    // ---- execution --------------------------------------------------------
    private fun execute(workspace: File, command: String, args: List<String>, stdin: String): ShellResult {
        return when (command) {
            "help" -> ShellResult(
                stdout = buildString {
                    appendLine("AnyMate Controller shell — commands:")
                    appendLine(commands.joinToString("  "))
                    appendLine()
                    appendLine("Pipes (a | b), redirection (> file, >> file) and quoting are supported.")
                    appendLine("python <code>  → run a Python snippet")
                    appendLine("mcp <tool> <json> → call a tool on the phone")
                }
            )
            "ls" -> ls(workspace, args)
            "cd" -> {
                val target = safePath(workspace, args.firstOrNull() ?: ".")
                if (target == null) ShellResult(stderr = "path outside the workspace", exitCode = 1)
                else ShellResult(stdout = target.absolutePath + "\n")
            }
            "pwd" -> ShellResult(stdout = workspace.canonicalPath + "\n")
            "cat" -> cat(workspace, args, stdin)
            "head" -> head(workspace, args, stdin)
            "tail" -> tail(workspace, args, stdin)
            "echo" -> ShellResult(stdout = (args.joinToString(" ") + "\n"))
            "mkdir" -> mkdir(workspace, args)
            "rm" -> remove(workspace, args)
            "cp" -> copyMove(workspace, args, move = false)
            "mv" -> copyMove(workspace, args, move = true)
            "touch" -> touch(workspace, args)
            "wc" -> wc(workspace, args, stdin)
            "grep" -> grep(workspace, args, stdin)
            "find" -> find(workspace, args)
            "tree" -> tree(workspace)
            "date" -> ShellResult(stdout = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "\n")
            "whoami" -> ShellResult(stdout = "artist\n")
            "env" -> ShellResult(stdout = "workspace=${workspace.canonicalPath}\n")
            "which" -> ShellResult(
                stdout = commands.filter { it == args.firstOrNull() }.joinToString("\n") + "\n"
            )
            "clear" -> ShellResult(stdout = "\u000c")
            "stat" -> stat(workspace, args)
            "du" -> ShellResult(stdout = du(workspace, args))
            "python", "py" -> PythonLite.run(workspace, args.joinToString(" "))
            "mcp" -> ShellResult(stderr = "Use the Console tab to call phone tools, or the Chat tab.\n")
            else -> ShellResult(stderr = "unknown command: $command\n", exitCode = 127)
        }
    }

    private fun ls(workspace: File, args: List<String>): ShellResult {
        val long = args.contains("-l")
        val path = safePath(workspace, args.firstOrNull { !it.startsWith("-") } ?: ".") ?: return ShellResult(stderr = "bad path", exitCode = 1)
        if (!path.exists()) return ShellResult(stderr = "no such path: ${path.name}\n", exitCode = 1)
        val entries = if (path.isDirectory) path.listFiles()?.toList().orEmpty().sortedBy { it.name }
        else listOf(path)
        if (entries.isEmpty()) return ShellResult(stdout = "")
        return ShellResult(
            stdout = entries.joinToString("\n") { entry ->
                if (long) {
                    val size = if (entry.isDirectory) "-" else entry.length().toString()
                    "$size\t${if (entry.isDirectory) "d" else "-"} ${entry.name}"
                } else {
                    entry.name + if (entry.isDirectory) "/" else ""
                }
            } + "\n"
        )
    }

    private fun cat(workspace: File, args: List<String>, stdin: String): ShellResult {
        if (args.isEmpty()) return ShellResult(stdout = stdin)
        val builder = StringBuilder()
        for (arg in args) {
            val file = safePath(workspace, arg) ?: return ShellResult(stderr = "bad path: $arg\n", exitCode = 1)
            if (!file.exists()) return ShellResult(stderr = "no such file: $arg\n", exitCode = 1)
            builder.append(file.readText())
        }
        return ShellResult(stdout = builder.toString())
    }

    private fun head(workspace: File, args: List<String>, stdin: String): ShellResult {
        val count = args.firstOrNull { it.toIntOrNull() != null }?.toInt() ?: 10
        val files = args.filter { it.toIntOrNull() == null }
        val text = if (files.isEmpty()) stdin else files.mapNotNull { safePath(workspace, it)?.takeIf { f -> f.exists() }?.readText() }.joinToString("\n")
        return ShellResult(stdout = text.lines().take(count).joinToString("\n").trimEnd('\n') + "\n")
    }

    private fun tail(workspace: File, args: List<String>, stdin: String): ShellResult {
        val count = args.firstOrNull { it.toIntOrNull() != null }?.toInt() ?: 10
        val files = args.filter { it.toIntOrNull() == null }
        val text = if (files.isEmpty()) stdin else files.mapNotNull { safePath(workspace, it)?.takeIf { f -> f.exists() }?.readText() }.joinToString("\n")
        val lines = text.lines()
        val start = (lines.size - count).coerceAtLeast(0)
        return ShellResult(stdout = lines.subList(start, lines.size).joinToString("\n").trimEnd('\n') + "\n")
    }

    private fun mkdir(workspace: File, args: List<String]): ShellResult {
        for (arg in args.filter { !it.startsWith("-") }) {
            val target = safePath(workspace, arg) ?: return ShellResult(stderr = "bad path\n", exitCode = 1)
            if (!target.mkdirs() && !target.exists()) return ShellResult(stderr = "cannot create $arg\n", exitCode = 1)
        }
        return ShellResult()
    }

    private fun remove(workspace: File, args: List<String>): ShellResult {
        val recursive = args.contains("-r") || args.contains("-rf")
        for (arg in args.filter { !it.startsWith("-") }) {
            val target = safePath(workspace, arg) ?: return ShellResult(stderr = "bad path\n", exitCode = 1)
            if (!target.exists()) continue
            val ok = if (target.isDirectory && recursive) target.deleteRecursively() else target.delete()
            if (!ok) return ShellResult(stderr = "cannot remove $arg\n", exitCode = 1)
        }
        return ShellResult()
    }

    private fun copyMove(workspace: File, args: List<String>, move: Boolean): ShellResult {
        if (args.size < 2) return ShellResult(stderr = "usage: ${if (move) "mv" else "cp"} source target\n", exitCode = 1)
        val source = safePath(workspace, args[0]) ?: return ShellResult(stderr = "bad source\n", exitCode = 1)
        var target = safePath(workspace, args[1]) ?: return ShellResult(stderr = "bad target\n", exitCode = 1)
        if (!source.exists()) return ShellResult(stderr = "no such file: ${args[0]}\n", exitCode = 1)
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
        return ShellResult(stdout = "${source.name} → ${target.name}\n")
    }

    private fun touch(workspace: File, args: List<String>): ShellResult {
        for (arg in args.filter { !it.startsWith("-") }) {
            val target = safePath(workspace, arg) ?: continue
            target.parentFile?.mkdirs()
            if (!target.exists()) target.writeText("")
        }
        return ShellResult()
    }

    private fun wc(workspace: File, args: List<String>, stdin: String): ShellResult {
        val files = args.filter { !it.startsWith("-") }
        val text = if (files.isEmpty()) stdin else files.mapNotNull { safePath(workspace, it)?.takeIf { f -> f.exists() }?.readText() }.joinToString("\n")
        val lines = if (text.isEmpty()) 0 else text.trimEnd('\n').lines().size
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val chars = text.length
        return ShellResult(stdout = "$lines $words $chars\n")
    }

    private fun grep(workspace: File, args: List<String>, stdin: String): ShellResult {
        val pattern = args.firstOrNull { !it.startsWith("-") } ?: return ShellResult(stderr = "usage: grep pattern [file]\n", exitCode = 1)
        val ignoreCase = args.contains("-i")
        val files = args.filter { it != pattern && !it.startsWith("-") }
        val text = if (files.isEmpty()) stdin else files.mapNotNull { safePath(workspace, it)?.takeIf { f -> f.exists() }?.readText() }.joinToString("\n")
        val regex = try {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (t: Throwable) {
            return ShellResult(stderr = "bad pattern: $pattern\n", exitCode = 2)
        }
        val hits = text.lines().filter { regex.containsMatchIn(it) }
        return ShellResult(stdout = hits.joinToString("\n").trimEnd('\n') + if (hits.isNotEmpty()) "\n" else "")
    }

    private fun find(workspace: File, args: List<String>): ShellResult {
        val pattern = args.firstOrNull { !it.startsWith("-") } ?: "."
        val glob = args.getOrNull(1) ?: "*"
        val base = safePath(workspace, pattern) ?: return ShellResult(stderr = "bad path\n", exitCode = 1)
        val matches = base.walkTopDown()
            .filter { it.isFile && it.name.contains(glob.replace("*", "")) }
            .map { it.relativeTo(workspace).path }
            .toList()
        return ShellResult(stdout = matches.joinToString("\n").trimEnd('\n') + if (matches.isNotEmpty()) "\n" else "")
    }

    private fun tree(workspace: File): ShellResult {
        val builder = StringBuilder(workspace.name + "\n")
        fun walk(directory: File, prefix: String) {
            val children = directory.listFiles()?.toList()?.sortedBy { it.name }.orEmpty()
            children.forEachIndexed { index, child ->
                val last = index == children.lastIndex
                builder.append(prefix).append(if (last) "└── " else "├── ").append(child.name).append('\n')
                if (child.isDirectory) walk(child, prefix + if (last) "    " else "│   ")
            }
        }
        walk(workspace, "")
        return ShellResult(stdout = builder.toString())
    }

    private fun stat(workspace: File, args: List<String>): ShellResult {
        val target = safePath(workspace, args.firstOrNull() ?: ".") ?: return ShellResult(stderr = "bad path\n", exitCode = 1)
        if (!target.exists()) return ShellResult(stderr = "no such path\n", exitCode = 1)
        return ShellResult(
            stdout = buildString {
                appendLine("name      ${target.name}")
                appendLine("type      ${if (target.isDirectory) "directory" else "file"}")
                appendLine("size      ${if (target.isDirectory) "-" else target.length()}")
                appendLine("modified  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(target.lastModified()))}")
            }
        )
    }

    private fun du(workspace: File, args: List<String>): ShellResult {
        val target = safePath(workspace, args.firstOrNull() ?: ".") ?: return ShellResult(stderr = "bad path\n", exitCode = 1)
        val bytes = if (target.isFile) target.length() else target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return ShellResult(stdout = "${bytes / 1024} KB\t${target.name}\n")
    }
}
