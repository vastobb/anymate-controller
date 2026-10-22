package com.anymate.controller.tools

import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A compact Python 3 subset that runs entirely on the device.
 *
 * Supported: expressions, arithmetic and comparisons, booleans, strings
 * (including f-strings), lists / tuples / dicts / sets, indexing and slicing,
 * attribute access, calls with keyword arguments, list comprehensions,
 * if / elif / else, while, for, def with defaults and return, break, continue,
 * import (math, random, time, os), and a useful set of builtins and methods.
 *
 * It is intentionally small and predictable — enough for an agent to crunch
 * numbers, build palettes, shape data and write files, with no interpreter to
 * install and nothing to leave the phone.
 */
object PythonLite {

    // ---- public API --------------------------------------------------------
    fun run(workspace: File, code: String): ShellResult {
        return try {
            val tokens = Lexer(code).tokenize()
            val program = Parser(tokens).parseProgram()
            val interpreter = Interpreter(workspace)
            val output = StringBuilder()
            interpreter.output = output
            interpreter.execBlock(program, interpreter.globals)
            ShellResult(stdout = output.toString())
        } catch (t: Throwable) {
            ShellResult(
                stdout = "",
                stderr = (t.message ?: t::class.simpleName ?: "error").trimEnd() + "\n",
                exitCode = 1
            )
        }
    }

    // ---- lexer -------------------------------------------------------------
    private enum class T { NUMBER, STRING, FSTRING, NAME, KEYWORD, OP, NEWLINE, INDENT, DEDENT, EOF }

    private data class Tok(
        val type: T,
        val text: String,
        val num: Double? = null,
        val str: String? = null,
        val line: Int = 0
    )

    private val keywords = setOf(
        "if", "elif", "else", "while", "for", "in", "def", "return", "break",
        "continue", "pass", "import", "from", "and", "or", "not", "True", "False", "None"
    )

    private class Lexer(private val source: String) {
        private var src = ""
        private var pos = 0
        private var line = 1
        private val indents = ArrayDeque<Int>()
        private val tokens = ArrayList<Tok>()

        fun tokenize(): List<Tok> {
            indents.add(0)
            val lines = source.split("\n")
            var depth = 0
            var buffer: String? = null
            var bufferLine = 1

            for ((index, raw) in lines.withIndex()) {
                val currentLine = index + 1
                if (buffer != null) {
                    buffer += "\n" + raw
                    depth += bracketDelta(raw)
                    if (depth > 0) continue
                    scan(buffer!!, bufferLine)
                    buffer = null
                    depth = 0
                    continue
                }
                val indent = leadingColumns(raw)
                val content = raw.trim()
                if (content.isEmpty() || content.startsWith("#")) continue
                depth = bracketDelta(raw)
                if (depth > 0) {
                    buffer = raw
                    bufferLine = currentLine
                    continue
                }
                if (indent > indents.last()) {
                    indents.add(indent)
                    tokens.add(Tok(T.INDENT, "", line = currentLine))
                } else {
                    while (indent < indents.last()) {
                        indents.removeLast()
                        tokens.add(Tok(T.DEDENT, "", line = currentLine))
                    }
                }
                scan(content, currentLine)
            }
            if (buffer != null) scan(buffer!!, bufferLine)
            if (tokens.isNotEmpty() && tokens.last().type != T.NEWLINE) {
                tokens.add(Tok(T.NEWLINE, "", line = line))
            }
            while (indents.size > 1) {
                indents.removeLast()
                tokens.add(Tok(T.DEDENT, "", line = line))
            }
            tokens.add(Tok(T.EOF, "", line = line))
            return tokens
        }

        private fun leadingColumns(raw: String): Int {
            var columns = 0
            for (char in raw) {
                when (char) {
                    ' ' -> columns += 1
                    '\t' -> columns += 4
                    else -> break
                }
            }
            return columns
        }

        private fun bracketDelta(text: String): Int {
            var delta = 0
            var inString: Char? = null
            for (char in text) {
                if (inString != null) {
                    if (char == inString) inString = null
                    continue
                }
                when (char) {
                    '"', '\'' -> inString = char
                    '(', '[', '{' -> delta++
                    ')', ']', '}' -> delta--
                    '#' -> break
                }
            }
            return delta
        }

        /** Scans one logical line and appends a NEWLINE token. */
        private fun scan(text: String, lineNo: Int) {
            src = text
            pos = 0
            line = lineNo
            while (pos < src.length) {
                val char = src[pos]
                when {
                    char == '\n' -> {
                        pos++
                    }
                    char == ' ' || char == '\t' || char == '\r' -> pos++
                    char == '#' -> pos = src.length
                    else -> readToken()
                }
            }
            tokens.add(Tok(T.NEWLINE, "", line = line))
        }

        private fun readToken() {
            val char = src[pos]
            when {
                char.isDigit() -> readNumber()
                char == '"' || char == '\'' -> readString()
                char.isLetter() || char == '_' -> readName()
                else -> readOperator()
            }
        }

        private fun readNumber() {
            val start = pos
            if (src[pos] == '0' && pos + 1 < src.length && (src[pos + 1] == 'x' || src[pos + 1] == 'X')) {
                pos += 2
                while (pos < src.length && src[pos].isHexDigit()) pos++
                val text = src.substring(start, pos)
                tokens.add(Tok(T.NUMBER, text, num = text.substring(2).toLong(16).toDouble(), line = line))
                return
            }
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            val text = src.substring(start, pos)
            tokens.add(Tok(T.NUMBER, text, num = text.toDouble(), line = line))
        }

        private fun readString() {
            val quote = src[pos]
            val triple = src.startsWith("$quote$quote$quote")
            val start = pos
            pos += if (triple) 3 else 1
            val builder = StringBuilder()
            while (pos < src.length) {
                val c = src[pos]
                if (!triple && c == quote) {
                    pos++
                    tokens.add(Tok(T.STRING, src.substring(start, pos), str = builder.toString(), line = line))
                    return
                }
                if (triple && src.startsWith("$quote$quote$quote", pos)) {
                    pos += 3
                    tokens.add(Tok(T.STRING, src.substring(start, pos), str = builder.toString(), line = line))
                    return
                }
                if (c == '\\' && pos + 1 < src.length) {
                    pos++
                    when (val esc = src[pos]) {
                        'n' -> builder.append('\n')
                        't' -> builder.append('\t')
                        'r' -> builder.append('\r')
                        '\\' -> builder.append('\\')
                        '"' -> builder.append('"')
                        '\'' -> builder.append('\'')
                        else -> {
                            builder.append('\\')
                            builder.append(esc)
                        }
                    }
                    pos++
                    continue
                }
                builder.append(c)
                pos++
            }
            throw PyError("unterminated string")
        }

        private fun readName() {
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val text = src.substring(start, pos)
            if (text == "f" && pos < src.length && (src[pos] == '"' || src[pos] == '\'')) {
                val quote = src[pos]
                val triple = src.startsWith("$quote$quote$quote")
                pos += if (triple) 3 else 1
                val builder = StringBuilder()
                while (pos < src.length) {
                    val c = src[pos]
                    if (!triple && c == quote) {
                        pos++
                        break
                    }
                    if (triple && src.startsWith("$quote$quote$quote", pos)) {
                        pos += 3
                        break
                    }
                    if (c == '\\' && pos + 1 < src.length) {
                        pos++
                        builder.append(src[pos])
                        pos++
                        continue
                    }
                    builder.append(c)
                    pos++
                }
                tokens.add(Tok(T.FSTRING, text, str = builder.toString(), line = line))
                return
            }
            tokens.add(Tok(if (text in keywords) T.KEYWORD else T.NAME, text, line = line))
        }

        private fun readOperator() {
            val two = if (pos + 1 < src.length) src.substring(pos, pos + 2) else ""
            val three = if (pos + 2 < src.length) src.substring(pos, pos + 3) else ""
            val op = when {
                three == "**=" -> three
                two in listOf("//=", "**", "//", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "->") -> two
                src[pos].toString() in listOf(
                    "+", "-", "*", "/", "%", "=", "<", ">", "(", ")", "[", "]", "{", "}", ",", ":", ".", ";", "|", "&", "^", "~"
                ) -> src[pos].toString()
                else -> throw PyError("unexpected character '${src[pos]}' (line $line)")
            }
            pos += op.length
            if (op == ";") {
                tokens.add(Tok(T.NEWLINE, "", line = line))
                return
            }
            tokens.add(Tok(T.OP, op, line = line))
        }
    }

    // ---- AST ---------------------------------------------------------------
    private sealed interface Expr
    private data class Num(val value: Double) : Expr
    private data class StrLit(val value: String) : Expr
    private data class FStr(val parts: List<Any>) : Expr
    private data class NameExpr(val id: String) : Expr
    private data class BoolLit(val value: Boolean) : Expr
    private data class NoneLit : Expr
    private data class ListLit(val items: List<Expr>) : Expr
    private data class TupleLit(val items: List<Expr>) : Expr
    private data class DictLit(val entries: List<Pair<Expr, Expr>>) : Expr
    private data class SetLit(val items: List<Expr>) : Expr
    private data class BinExpr(val op: String, val left: Expr, val right: Expr) : Expr
    private data class UnaryExpr(val op: String, val operand: Expr) : Expr
    private data class CondExpr(val test: Expr, val then: Expr, val other: Expr?) : Expr
    private data class CallExpr(val callee: Expr, val args: List<Expr>, val kwargs: List<Pair<String, Expr>>) : Expr
    private data class AttrExpr(val target: Expr, val name: String) : Expr
    private data class IndexExpr(val target: Expr, val index: Expr) : Expr
    private data class SliceExpr(val target: Expr, val low: Expr?, val high: Expr?) : Expr
    private data class CompExpr(val element: Expr, val variable: String, val iterable: Expr, val condition: Expr?) : Expr

    private sealed interface Stmt
    private data class ExprStmt(val expression: Expr) : Stmt
    private data class AssignStmt(val targets: List<Expr>, val value: Expr) : Stmt
    private data class AugAssignStmt(val target: Expr, val op: String, val value: Expr) : Stmt
    private data class IfStmt(val branches: List<Pair<Expr, List<Stmt>>>, val elseBody: List<Stmt>?) : Stmt
    private data class WhileStmt(val test: Expr, val body: List<Stmt>) : Stmt
    private data class ForStmt(val variable: String, val iterable: Expr, val body: List<Stmt>) : Stmt
    private data class DefStmt(
        val name: String,
        val params: List<String>,
        val defaults: List<Expr>,
        val body: List<Stmt>
    ) : Stmt
    private data class ReturnStmt(val value: Expr?) : Stmt
    private data object BreakStmt : Stmt
    private data object ContinueStmt : Stmt
    private data object PassStmt : Stmt
    private data class ImportStmt(val module: String, val alias: String?) : Stmt

    // ---- parser ------------------------------------------------------------
    private class Parser(private val tokens: List<Tok>) {
        private var index = 0

        fun parseProgram(): List<Stmt> {
            val statements = ArrayList<Stmt>()
            while (peek().type != T.EOF) {
                statements.add(parseStatement())
            }
            return statements
        }

        private fun peek(): Tok = tokens[index]
        private fun peekNext(): Tok = tokens.getOrElse(index + 1) { Tok(T.EOF, "") }
        private fun next(): Tok = tokens[index++]
        private fun check(text: String): Boolean = peek().text == text && (peek().type == T.OP || peek().type == T.KEYWORD)
        private fun accept(text: String): Boolean {
            if (check(text)) {
                index++
                return true
            }
            return false
        }
        private fun expect(text: String) {
            val token = next()
            if (token.text != text) {
                throw PyError("expected '$text' but found '${token.text}' (line ${token.line})")
            }
        }
        private fun expectNewline() {
            if (peek().type == T.NEWLINE) next()
        }

        private fun parseStatement(): Stmt {
            val token = peek()
            return when {
                token.type == T.KEYWORD && token.text == "if" -> parseIf()
                token.type == T.KEYWORD && token.text == "while" -> parseWhile()
                token.type == T.KEYWORD && token.text == "for" -> parseFor()
                token.type == T.KEYWORD && token.text == "def" -> parseDef()
                token.type == T.KEYWORD && token.text == "return" -> {
                    next()
                    val value = if (peek().type == T.NEWLINE || peek().type == T.EOF) null else parseExpression()
                    expectNewline()
                    ReturnStmt(value)
                }
                token.type == T.KEYWORD && token.text == "break" -> {
                    next(); expectNewline(); BreakStmt
                }
                token.type == T.KEYWORD && token.text == "continue" -> {
                    next(); expectNewline(); ContinueStmt
                }
                token.type == T.KEYWORD && token.text == "pass" -> {
                    next(); expectNewline(); PassStmt
                }
                token.type == T.KEYWORD && (token.text == "import" || token.text == "from") -> parseImport()
                else -> parseSimpleStatement()
            }
        }

        private fun parseImport(): Stmt {
            next()
            if (peek().text == "import") next()
            val module = next().text
            var alias: String? = null
            if (accept("as")) alias = next().text
            expectNewline()
            return ImportStmt(module, alias)
        }

        private fun parseSimpleStatement(): Stmt {
            val first = parseExpression()
            if (peek().type == T.OP && peek().text == "=") {
                val targets = ArrayList<Expr>()
                targets.add(first)
                while (accept("=")) {
                    targets.add(parseExpression())
                }
                expectNewline()
                return AssignStmt(targets, targets.last())
            }
            val augmented = listOf("+=", "-=", "*=", "/=", "//=", "**=", "%=")
            if (peek().type == T.OP && peek().text in augmented) {
                val op = next().text.dropLast(1)
                val value = parseExpression()
                expectNewline()
                return AugAssignStmt(first, op, value)
            }
            expectNewline()
            return ExprStmt(first)
        }

        private fun parseIf(): Stmt {
            val branches = ArrayList<Pair<Expr, List<Stmt>>>()
            next()
            branches.add(parseExpression() to parseBlock())
            var elseBody: List<Stmt>? = null
            while (true) {
                if (peek().type == T.KEYWORD && peek().text == "elif") {
                    next()
                    branches.add(parseExpression() to parseBlock())
                    continue
                }
                if (peek().type == T.KEYWORD && peek().text == "else") {
                    next()
                    elseBody = parseBlock()
                }
                break
            }
            return IfStmt(branches, elseBody)
        }

        private fun parseWhile(): Stmt {
            next()
            val test = parseExpression()
            expect(":")
            return WhileStmt(test, parseSuite())
        }

        private fun parseFor(): Stmt {
            next()
            val variable = next().text
            expect("in")
            val iterable = parseExpression()
            expect(":")
            return ForStmt(variable, iterable, parseSuite())
        }

        private fun parseDef(): Stmt {
            next()
            val name = next().text
            expect("(")
            val params = ArrayList<String>()
            val defaults = ArrayList<Expr>()
            while (peek().text != ")") {
                params.add(next().text)
                if (accept("=")) defaults.add(parseExpression())
                if (!accept(",")) break
            }
            expect(")")
            expect(":")
            return DefStmt(name, params, defaults, parseSuite())
        }

        /** A block after `if`/`while`/`for` — either inline or an indented suite. */
        private fun parseBlock(): List<Stmt> {
            expect(":")
            if (peek().type == T.NEWLINE) {
                next()
                if (peek().type != T.INDENT) throw PyError("expected an indented block")
                next()
                val statements = ArrayList<Stmt>()
                while (peek().type != T.DEDENT && peek().type != T.EOF) {
                    statements.add(parseStatement())
                }
                if (peek().type == T.DEDENT) next()
                return statements
            }
            return listOf(parseSimpleStatement())
        }

        private fun parseSuite(): List<Stmt> = parseBlock()

        // ---- expressions ---------------------------------------------------
        private fun parseExpression(): Expr = parseOr()

        private fun parseOr(): Expr {
            var left = parseAnd()
            while (peek().type == T.KEYWORD && peek().text == "or") {
                next()
                left = BinExpr("or", left, parseAnd())
            }
            return left
        }

        private fun parseAnd(): Expr {
            var left = parseNot()
            while (peek().type == T.KEYWORD && peek().text == "and") {
                next()
                left = BinExpr("and", left, parseNot())
            }
            return left
        }

        private fun parseNot(): Expr {
            if (peek().type == T.KEYWORD && peek().text == "not") {
                next()
                return UnaryExpr("not", parseNot())
            }
            return parseComparison()
        }

        private fun parseComparison(): Expr {
            var left = parseAdditive()
            while (peek().type == T.OP && peek().text in listOf("==", "!=", "<", ">", "<=", ">=")) {
                val op = next().text
                left = BinExpr(op, left, parseAdditive())
            }
            return left
        }

        private fun parseAdditive(): Expr {
            var left = parseMultiplicative()
            while (peek().type == T.OP && (peek().text == "+" || peek().text == "-")) {
                val op = next().text
                left = BinExpr(op, left, parseMultiplicative())
            }
            return left
        }

        private fun parseMultiplicative(): Expr {
            var left = parseUnary()
            while (peek().type == T.OP && peek().text in listOf("*", "/", "//", "%")) {
                val op = next().text
                left = BinExpr(op, left, parseUnary())
            }
            return left
        }

        private fun parseUnary(): Expr {
            if (peek().type == T.OP && (peek().text == "-" || peek().text == "+")) {
                val op = next().text
                return UnaryExpr(op, parseUnary())
            }
            return parsePower()
        }

        private fun parsePower(): Expr {
            val base = parsePostfix()
            if (peek().type == T.OP && peek().text == "**") {
                next()
                return BinExpr("**", base, parseUnary())
            }
            return base
        }

        private fun parsePostfix(): Expr {
            var expr = parsePrimary()
            while (true) {
                when {
                    peek().type == T.OP && peek().text == "(" -> {
                        next()
                        val args = ArrayList<Expr>()
                        val kwargs = ArrayList<Pair<String, Expr>>()
                        while (peek().text != ")") {
                            if (peek().type == T.NAME && peekNext().text == "=") {
                                val key = next().text
                                next()
                                kwargs.add(key to parseExpression())
                            } else {
                                args.add(parseExpression())
                            }
                            if (!accept(",")) break
                        }
                        expect(")")
                        expr = CallExpr(expr, args, kwargs)
                    }
                    peek().type == T.OP && peek().text == "[" -> {
                        next()
                        val low: Expr? = if (peek().text == ":") null else parseExpression()
                        if (accept(":")) {
                            val high = if (peek().text == "]") null else parseExpression()
                            expect("]")
                            expr = SliceExpr(expr, low, high)
                        } else {
                            expect("]")
                            expr = IndexExpr(expr, low!!)
                        }
                    }
                    peek().type == T.OP && peek().text == "." -> {
                        next()
                        expr = AttrExpr(expr, next().text)
                    }
                    else -> return expr
                }
            }
        }

        private fun parsePrimary(): Expr {
            val token = peek()
            val result: Expr = when {
                token.type == T.NUMBER -> {
                    next(); Num(token.num ?: 0.0)
                }
                token.type == T.STRING -> {
                    next(); StrLit(token.str.orEmpty())
                }
                token.type == T.FSTRING -> {
                    next(); FStr(splitFString(token.str.orEmpty()))
                }
                token.type == T.KEYWORD && token.text == "True" -> {
                    next(); BoolLit(true)
                }
                token.type == T.KEYWORD && token.text == "False" -> {
                    next(); BoolLit(false)
                }
                token.type == T.KEYWORD && token.text == "None" -> {
                    next(); NoneLit
                }
                token.type == T.NAME -> {
                    next(); NameExpr(token.text)
                }
                token.type == T.OP && token.text == "(" -> {
                    next()
                    if (peek().text == ")") {
                        next()
                        return TupleLit(emptyList())
                    } else {
                        val first = parseExpression()
                        if (peek().type == T.OP && peek().text == ",") {
                            val items = ArrayList<Expr>()
                            items.add(first)
                            while (accept(",")) {
                                if (peek().text == ")") break
                                items.add(parseExpression())
                            }
                            expect(")")
                            TupleLit(items)
                        } else {
                            expect(")")
                            first
                        }
                    }
                }
                token.type == T.OP && token.text == "[" -> {
                    next()
                    if (peek().text == "]") {
                        next()
                        return ListLit(emptyList())
                    }
                    val first = parseExpression()
                    if (peek().type == T.OP && peek().text == "for") {
                        next()
                        val variable = next().text
                        expect("in")
                        val iterable = parseExpression()
                        val condition = if (accept("if")) parseExpression() else null
                        expect("]")
                        CompExpr(first, variable, iterable, condition)
                    } else {
                        val items = ArrayList<Expr>()
                        items.add(first)
                        while (accept(",")) {
                            if (peek().text == "]") break
                            items.add(parseExpression())
                        }
                        expect("]")
                        ListLit(items)
                    }
                }
                token.type == T.OP && token.text == "{" -> {
                    next()
                    if (peek().text == "}") {
                        next()
                        return DictLit(emptyList())
                    }
                    val key = parseExpression()
                    if (peek().type == T.OP && peek().text == ":") {
                        next()
                        val value = parseExpression()
                        val entries = ArrayList<Pair<Expr, Expr>>()
                        entries.add(key to value)
                        while (accept(",")) {
                            if (peek().text == "}") break
                            val k = parseExpression()
                            expect(":")
                            val v = parseExpression()
                            entries.add(k to v)
                        }
                        expect("}")
                        DictLit(entries)
                    } else {
                        val items = ArrayList<Expr>()
                        items.add(key)
                        while (accept(",")) {
                            if (peek().text == "}") break
                            items.add(parseExpression())
                        }
                        expect("}")
                        SetLit(items)
                    }
                }
                else -> throw PyError("unexpected token '${token.text}' (line ${token.line})")
            }
            return result
        }

        private fun splitFString(raw: String): List<Any> {
            val parts = ArrayList<Any>()
            val builder = StringBuilder()
            var index = 0
            while (index < raw.length) {
                val char = raw[index]
                if (char == '{' && index + 1 < raw.length && raw[index + 1] == '{') {
                    builder.append('{')
                    index += 2
                    continue
                }
                if (char == '}' && index + 1 < raw.length && raw[index + 1] == '}') {
                    builder.append('}')
                    index += 2
                    continue
                }
                if (char == '{') {
                    if (builder.isNotEmpty()) {
                        parts.add(builder.toString())
                        builder.clear()
                    }
                    var depth = 1
                    var cursor = index + 1
                    while (cursor < raw.length && depth > 0) {
                        when (raw[cursor]) {
                            '{' -> depth++
                            '}' -> depth--
                        }
                        if (depth > 0) cursor++
                    }
                    val expression = raw.substring(index + 1, cursor)
                    parts.add(Parser(Lexer(expression).tokenize()).parseExpression())
                    index = cursor + 1
                    continue
                }
                builder.append(char)
                index++
            }
            if (builder.isNotEmpty()) parts.add(builder.toString())
            return parts
        }
    }

    // ---- runtime -----------------------------------------------------------
    private class PyFunction(
        val name: String,
        val params: List<String>,
        val defaults: List<Expr>,
        val body: List<Stmt>,
        val closure: Env
    )

    private class Builtin(val name: String, val fn: (List<Any?>, Env) -> Any?)

    private class PyModule(val name: String, val members: Map<String, Any?>)

    private class Env(val parent: Env? = null) {
        val values = HashMap<String, Any?>()

        fun get(name: String): Any? {
            var scope: Env? = this
            while (scope != null) {
                if (scope.values.containsKey(name)) return scope.values[name]
                scope = scope.parent
            }
            throw PyError("name '$name' is not defined")
        }

        fun set(name: String, value: Any?) {
            var scope: Env? = this
            while (scope != null) {
                if (scope.values.containsKey(name)) {
                    scope.values[name] = value
                    return
                }
                scope = scope.parent
            }
            values[name] = value
        }

        fun define(name: String, value: Any?) {
            values[name] = value
        }
    }

    private class BreakSignal : RuntimeException(null, null, false, false)
    private class ContinueSignal : RuntimeException(null, null, false, false)
    private class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)

    private class Interpreter(private val workspace: File) {
        var output: StringBuilder = StringBuilder()
        val globals = Env()

        // ---- statements ---------------------------------------------------
        fun execBlock(statements: List<Stmt>, env: Env) {
            for (statement in statements) exec(statement, env)
        }

        private fun exec(statement: Stmt, env: Env) {
            when (statement) {
                is ExprStmt -> eval(statement.expression, env)
                is AssignStmt -> {
                    val value = eval(statement.value, env)
                    for (target in statement.targets) assign(target, value, env)
                }
                is AugAssignStmt -> {
                    val current = eval(statement.target, env)
                    val value = binary(statement.op, current, eval(statement.value, env))
                    assign(statement.target, value, env)
                }
                is IfStmt -> {
                    var executed = false
                    for ((test, body) in statement.branches) {
                        if (truthy(eval(test, env))) {
                            execBlock(body, env)
                            executed = true
                            break
                        }
                    }
                    if (!executed) statement.elseBody?.let { execBlock(it, env) }
                }
                is WhileStmt -> {
                    while (truthy(eval(statement.test, env))) {
                        try {
                            execBlock(statement.body, env)
                        } catch (_: BreakSignal) {
                            break
                        } catch (_: ContinueSignal) {
                            continue
                        }
                    }
                }
                is ForStmt -> {
                    for (item in iterate(eval(statement.iterable, env))) {
                        env.set(statement.variable, item)
                        try {
                            execBlock(statement.body, env)
                        } catch (_: BreakSignal) {
                            break
                        } catch (_: ContinueSignal) {
                            continue
                        }
                    }
                }
                is DefStmt -> env.define(
                    statement.name,
                    PyFunction(statement.name, statement.params, statement.defaults, statement.body, env)
                )
                is ReturnStmt -> throw ReturnSignal(statement.value?.let { eval(it, env) })
                BreakStmt -> throw BreakSignal()
                ContinueStmt -> throw ContinueSignal()
                PassStmt -> Unit
                is ImportStmt -> env.define(statement.alias ?: statement.module, importModule(statement.module))
            }
        }

        private fun assign(target: Expr, value: Any?, env: Env) {
            when (target) {
                is NameExpr -> env.set(target.id, value)
                is IndexExpr -> {
                    val container = eval(target.target, env)
                    val key = eval(target.index, env)
                    when (container) {
                        is MutableList<*> -> {
                            val list = container as MutableList<Any?>
                            val position = asNumber(key).toInt()
                            while (list.size <= position) list.add(null)
                            list[position] = value
                        }
                        is MutableMap<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            (container as MutableMap<Any?, Any?>)[key] = value
                        }
                        else -> throw PyError("cannot assign to this object")
                    }
                }
                else -> throw PyError("invalid assignment target")
            }
        }

        // ---- expressions ---------------------------------------------------
        fun eval(expression: Expr, env: Env): Any? = when (expression) {
            is Num -> expression.value
            is StrLit -> expression.value
            is BoolLit -> expression.value
            is NoneLit -> null
            is FStr -> expression.parts.joinToString("") { part ->
                if (part is String) part else pyStr(eval(part as Expr, env))
            }
            is NameExpr -> env.get(expression.id)
            is ListLit -> expression.items.map { eval(it, env) }.toMutableList()
            is TupleLit -> expression.items.map { eval(it, env) }.toMutableList()
            is SetLit -> expression.items.map { eval(it, env) }.toMutableSet()
            is DictLit -> {
                val map = LinkedHashMap<Any?, Any?>()
                expression.entries.forEach { (key, value) -> map[eval(key, env)] = eval(value, env) }
                map
            }
            is UnaryExpr -> {
                val value = eval(expression.operand, env)
                when (expression.op) {
                    "not" -> !truthy(value)
                    "-" -> -asNumber(value)
                    "+" -> asNumber(value)
                    else -> throw PyError("unknown unary operator ${expression.op}")
                }
            }
            is BinExpr -> when (expression.op) {
                "and" -> {
                    val left = eval(expression.left, env)
                    if (!truthy(left)) false else truthy(eval(expression.right, env))
                }
                "or" -> {
                    val left = eval(expression.left, env)
                    if (truthy(left)) true else truthy(eval(expression.right, env))
                }
                else -> binary(expression.op, eval(expression.left, env), eval(expression.right, env))
            }
            is CondExpr -> if (truthy(eval(expression.test, env))) {
                eval(expression.then, env)
            } else {
                expression.other?.let { eval(it, env) }
            }
            is CompExpr -> {
                val result = ArrayList<Any?>()
                for (item in iterate(eval(expression.iterable, env))) {
                    env.set(expression.variable, item)
                    if (expression.condition == null || truthy(eval(expression.condition, env))) {
                        result.add(eval(expression.element, env))
                    }
                }
                result
            }
            is IndexExpr -> index(eval(expression.target, env), eval(expression.index, env))
            is SliceExpr -> slice(
                eval(expression.target, env),
                expression.low?.let { eval(it, env) },
                expression.high?.let { eval(it, env) }
            )
            is AttrExpr -> attribute(eval(expression.target, env), expression.name)
            is CallExpr -> call(expression, env)
        }

        private fun call(expression: CallExpr, env: Env): Any? {
            val callee = eval(expression.callee, env)
            val args = expression.args.map { eval(it, env) }
            val kwargs = expression.kwargs.map { (key, value) -> key to eval(value, env) }
            return when (callee) {
                is Builtin -> callee.fn(args, env)
                is PyFunction -> {
                    val scope = Env(callee.closure)
                    callee.params.forEachIndexed { index, param ->
                        val value = when {
                            index < args.size -> args[index]
                            else -> {
                                val defaultIndex = index - (callee.params.size - callee.defaults.size)
                                if (defaultIndex in callee.defaults.indices) {
                                    eval(callee.defaults[defaultIndex], callee.closure)
                                } else {
                                    throw PyError("${callee.name}() missing argument '$param'")
                                }
                            }
                        }
                        scope.define(param, value)
                    }
                    kwargs.forEach { (key, value) -> scope.define(key, value) }
                    try {
                        execBlock(callee.body, scope)
                    } catch (signal: ReturnSignal) {
                        signal.value
                    }
                }
                else -> throw PyError("this value is not callable")
            }
        }

        private fun attribute(target: Any?, name: String): Any? {
            if (target is PyModule) {
                if (target.members.containsKey(name)) return target.members[name]
                throw PyError("module '${target.name}' has no attribute '$name'")
            }
            return method(target, name)
        }

        private fun index(container: Any?, key: Any?): Any? = when (container) {
            is List<*> -> {
                val list = container as List<Any?>
                val position = asNumber(key).toInt()
                if (position < 0) list.getOrNull(list.size + position) else list.getOrNull(position)
            }
            is Map<*, *> -> container[key]
            is String -> container.getOrNull(asNumber(key).toInt())?.toString()
            else -> throw PyError("this object is not indexable")
        }

        private fun slice(container: Any?, low: Any?, high: Any?): Any? = when (container) {
            is List<*> -> {
                val list = container as List<Any?>
                val size = list.size
                var start = if (low == null) 0 else asNumber(low).toInt()
                var end = if (high == null) size else asNumber(high).toInt()
                if (start < 0) start += size
                if (end < 0) end += size
                list.subList(start.coerceIn(0, size), end.coerceIn(0, size)).toMutableList()
            }
            is String -> {
                val size = container.length
                var start = if (low == null) 0 else asNumber(low).toInt()
                var end = if (high == null) size else asNumber(high).toInt()
                if (start < 0) start += size
                if (end < 0) end += size
                container.substring(start.coerceIn(0, size), end.coerceIn(0, size))
            }
            else -> throw PyError("this object cannot be sliced")
        }

        // ---- operators -----------------------------------------------------
        private fun binary(op: String, left: Any?, right: Any?): Any? = when (op) {
            "+" -> when {
                left is String || right is String -> pyStr(left) + pyStr(right)
                left is List<*> && right is List<*> -> {
                    val merged = (left as List<Any?>).toMutableList()
                    merged.addAll(right as List<Any?>)
                    merged
                }
                else -> asNumber(left) + asNumber(right)
            }
            "-" -> when {
                left is List<*> && right is List<*> -> {
                    val merged = (left as List<Any?>).toMutableList()
                    merged.removeAll(right as List<Any?>)
                    merged
                }
                else -> asNumber(left) - asNumber(right)
            }
            "*" -> when {
                left is String && right is Number -> left.repeat(asNumber(right).toInt().coerceAtMost(10_000))
                right is String && left is Number -> right.repeat(asNumber(left).toInt().coerceAtMost(10_000))
                left is List<*> && right is Number -> {
                    val merged = (left as List<Any?>).toMutableList()
                    repeat(asNumber(right).toInt().coerceAtMost(1000)) { merged.addAll(left) }
                    merged
                }
                else -> asNumber(left) * asNumber(right)
            }
            "/" -> {
                val divisor = asNumber(right)
                if (divisor == 0.0) throw PyError("division by zero")
                asNumber(left) / divisor
            }
            "//" -> {
                val divisor = asNumber(right)
                if (divisor == 0.0) throw PyError("division by zero")
                floor(asNumber(left) / divisor)
            }
            "%" -> {
                val divisor = asNumber(right)
                if (divisor == 0.0) throw PyError("modulo by zero")
                asNumber(left) - divisor * floor(asNumber(left) / divisor)
            }
            "**" -> asNumber(left).pow(asNumber(right))
            "==" -> pyEquals(left, right)
            "!=" -> !pyEquals(left, right)
            "<" -> compare(left, right) < 0
            ">" -> compare(left, right) > 0
            "<=" -> compare(left, right) <= 0
            ">=" -> compare(left, right) >= 0
            else -> throw PyError("unknown operator $op")
        }

        private fun compare(left: Any?, right: Any?): Int {
            if (left is Number && right is Number) {
                return left.toDouble().compareTo(right.toDouble())
            }
            if (left is String && right is String) return left.compareTo(right)
            throw PyError("cannot compare these values")
        }

        // ---- modules --------------------------------------------------------
        private fun importModule(name: String): PyModule = when (name) {
            "math" -> PyModule(
                "math",
                mapOf(
                    "pi" to Math.PI,
                    "e" to Math.E,
                    "sqrt" to Builtin("sqrt") { args, _ -> sqrt(asNumber(args[0])) },
                    "floor" to Builtin("floor") { args, _ -> floor(asNumber(args[0])) },
                    "ceil" to Builtin("ceil") { args, _ -> ceil(asNumber(args[0])) },
                    "fabs" to Builtin("fabs") { args, _ -> abs(asNumber(args[0])) },
                    "sin" to Builtin("sin") { args, _ -> Math.sin(asNumber(args[0])) },
                    "cos" to Builtin("cos") { args, _ -> Math.cos(asNumber(args[0])) },
                    "tan" to Builtin("tan") { args, _ -> Math.tan(asNumber(args[0])) },
                    "atan2" to Builtin("atan2") { args, _ -> Math.atan2(asNumber(args[0]), asNumber(args[1])) },
                    "log" to Builtin("log") { args, _ -> Math.log(asNumber(args[0])) },
                    "log10" to Builtin("log10") { args, _ -> Math.log10(asNumber(args[0])) },
                    "exp" to Builtin("exp") { args, _ -> Math.exp(asNumber(args[0])) },
                    "pow" to Builtin("pow") { args, _ -> asNumber(args[0]).pow(asNumber(args[1])) },
                    "hypot" to Builtin("hypot") { args, _ -> Math.hypot(asNumber(args[0]), asNumber(args[1])) },
                    "degrees" to Builtin("degrees") { args, _ -> Math.toDegrees(asNumber(args[0])) },
                    "radians" to Builtin("radians") { args, _ -> Math.toRadians(asNumber(args[0])) },
                    "trunc" to Builtin("trunc") { args, _ -> asNumber(args[0]).toInt().toDouble() },
                    "gcd" to Builtin("gcd") { args, _ ->
                        var a = asNumber(args[0]).toInt()
                        var b = asNumber(args[1]).toInt()
                        while (b != 0) {
                            val temp = b
                            b = a % b
                            a = temp
                        }
                        a.toDouble()
                    }
                )
            )
            "random" -> PyModule(
                "random",
                mapOf(
                    "random" to Builtin("random") { _, _ -> Math.random() },
                    "randint" to Builtin("randint") { args, _ ->
                        val low = asNumber(args[0]).toInt()
                        val high = asNumber(args[1]).toInt()
                        if (high < low) throw PyError("randint() needs low <= high")
                        (low..high).random()
                    },
                    "uniform" to Builtin("uniform") { args, _ ->
                        val low = asNumber(args[0])
                        val high = asNumber(args[1])
                        low + Math.random() * (high - low)
                    },
                    "choice" to Builtin("choice") { args, _ ->
                        val items = iterate(args[0])
                        if (items.isEmpty()) throw PyError("choice() on an empty sequence")
                        items.random()
                    },
                    "shuffle" to Builtin("shuffle") { args, _ ->
                        val list = args[0] as? MutableList<Any?> ?: throw PyError("shuffle() needs a list")
                        list.shuffle()
                        null
                    },
                    "seed" to Builtin("seed") { _, _ -> null }
                )
            )
            "time" -> PyModule(
                "time",
                mapOf(
                    "time" to Builtin("time") { _, _ -> System.currentTimeMillis() / 1000.0 },
                    "monotonic" to Builtin("monotonic") { _, _ -> System.nanoTime() / 1_000_000_000.0 },
                    "sleep" to Builtin("sleep") { args, _ ->
                        Thread.sleep((asNumber(args[0]).coerceIn(0.0, 2.0) * 1000).toLong())
                        null
                    }
                )
            )
            "os" -> PyModule(
                "os",
                mapOf(
                    "getcwd" to Builtin("getcwd") { _, _ -> workspace.canonicalPath },
                    "listdir" to Builtin("listdir") { args, _ ->
                        resolveDir(args.firstOrNull()).listFiles()?.map { it.name }?.sorted() ?: emptyList<String>()
                    },
                    "makedirs" to Builtin("makedirs") { args, _ ->
                        resolveDir(args.firstOrNull()).mkdirs()
                        null
                    },
                    "remove" to Builtin("remove") { args, _ ->
                        resolveFile(args[0]).delete()
                        null
                    },
                    "path" to PyModule(
                        "path",
                        mapOf(
                            "join" to Builtin("join") { args, _ ->
                                args.joinToString("/") { pyStr(it) }.replace("//", "/")
                            },
                            "exists" to Builtin("exists") { args, _ -> resolveFile(args[0]).exists() },
                            "isdir" to Builtin("isdir") { args, _ -> resolveFile(args[0]).isDirectory },
                            "isfile" to Builtin("isfile") { args, _ -> resolveFile(args[0]).isFile },
                            "basename" to Builtin("basename") { args, _ -> File(pyStr(args[0])).name },
                            "dirname" to Builtin("dirname") { args, _ -> File(pyStr(args[0])).parent.orEmpty() },
                            "splitext" to Builtin("splitext") { args, _ ->
                                val name = File(pyStr(args[0])).name
                                val dot = name.lastIndexOf('.')
                                if (dot <= 0) listOf(name, "") else listOf(name.substring(0, dot), name.substring(dot))
                            },
                            "abspath" to Builtin("abspath") { args, _ -> resolveFile(args[0]).absolutePath }
                        )
                    )
                )
            )
            else -> throw PyError("no module named '$name' (available: math, random, time, os)")
        }

        private fun resolveDir(raw: Any?): File {
            val name = raw?.let { pyStr(it) } ?: "."
            val file = File(name)
            return if (file.isAbsolute) file else File(workspace, name)
        }

        private fun resolveFile(raw: Any?): File = resolveDir(raw)

        // ---- methods --------------------------------------------------------
        private fun method(target: Any?, name: String): Any? = when {
            target is String -> stringMethod(target, name)
            target is List<*> -> listMethod(target as MutableList<Any?>, name)
            target is Map<*, *> -> mapMethod(target as MutableMap<Any?, Any?>, name)
            target is Set<*> -> setMethod(target as MutableSet<Any?>, name)
            else -> throw PyError("this object has no method '$name'")
        }

        private fun stringMethod(value: String, name: String): Any? = when (name) {
            "upper" -> Builtin("upper") { _, _ -> value.uppercase() }
            "lower" -> Builtin("lower") { _, _ -> value.lowercase() }
            "title" -> Builtin("title") { _, _ -> value.split(" ").joinToString(" ") { it.capitalize() } }
            "capitalize" -> Builtin("capitalize") { _, _ -> value.replaceFirstChar { it.uppercase() } }
            "strip" -> Builtin("strip") { _, _ -> value.trim() }
            "lstrip" -> Builtin("lstrip") { _, _ -> value.trimStart() }
            "rstrip" -> Builtin("rstrip") { _, _ -> value.trimEnd() }
            "split" -> Builtin("split") { args, _ ->
                val separator = args.firstOrNull()?.let { pyStr(it) }
                if (separator == null) value.split(Regex("\\s+")).filter { it.isNotEmpty() }
                else value.split(separator).toMutableList()
            }
            "join" -> Builtin("join") { args, _ ->
                iterate(args[0]).map { pyStr(it) }.joinToString(separator = pyStr(args[1]))
            }
            "replace" -> Builtin("replace") { args, _ -> value.replace(pyStr(args[0]), pyStr(args[1])) }
            "startswith" -> Builtin("startswith") { args, _ -> value.startsWith(pyStr(args[0])) }
            "endswith" -> Builtin("endswith") { args, _ -> value.endsWith(pyStr(args[0])) }
            "find" -> Builtin("find") { args, _ -> value.indexOf(pyStr(args[0])) }
            "index" -> Builtin("index") { args, _ ->
                val at = value.indexOf(pyStr(args[0]))
                if (at < 0) throw PyError("substring not found") else at
            }
            "count" -> Builtin("count") { args, _ ->
                val needle = pyStr(args[0])
                if (needle.isEmpty()) 0 else value.windowed(needle.length).count { it == needle }
            }
            "isdigit" -> Builtin("isdigit") { _, _ -> value.isNotEmpty() && value.all { it.isDigit() } }
            "isalpha" -> Builtin("isalpha") { _, _ -> value.isNotEmpty() && value.all { it.isLetter() } }
            "isspace" -> Builtin("isspace") { _, _ -> value.isNotEmpty() && value.all { it.isWhitespace() } }
            "format" -> Builtin("format") { args, _ -> formatString(value, args) }
            "zfill" -> Builtin("zfill") { args, _ -> value.padStart(asNumber(args[0]).toInt(), '0') }
            else -> throw PyError("string has no method '$name'")
        }

        private fun formatString(template: String, args: List<Any?>): String {
            val builder = StringBuilder()
            var index = 0
            var next = 0
            while (index < template.length) {
                val char = template[index]
                if (char == '{' && index + 1 < template.length && template[index + 1] == '{') {
                    builder.append('{')
                    index += 2
                    continue
                }
                if (char == '}' && index + 1 < template.length && template[index + 1] == '}') {
                    builder.append('}')
                    index += 2
                    continue
                }
                if (char == '{') {
                    val end = template.indexOf('}', index)
                    if (end < 0) {
                        builder.append(char)
                        index++
                        continue
                    }
                    val key = template.substring(index + 1, end)
                    val value = when {
                        key.isEmpty() -> if (next < args.size) args[next++] else null
                        else -> args.firstOrNull { pyStr(it) == key }
                    }
                    builder.append(pyStr(value))
                    index = end + 1
                    continue
                }
                builder.append(char)
                index++
            }
            return builder.toString()
        }

        private fun listMethod(list: MutableList<Any?>, name: String): Any? = when (name) {
            "append" -> Builtin("append") { args, _ -> list.add(args[0]); null }
            "extend" -> Builtin("extend") { args, _ -> list.addAll(iterate(args[0])); null }
            "insert" -> Builtin("insert") { args, _ -> list.add(asNumber(args[0]).toInt(), args[1]); null }
            "pop" -> Builtin("pop") { args, _ ->
                if (list.isEmpty()) throw PyError("pop() from an empty list")
                if (args.isEmpty()) list.removeAt(list.lastIndex) else list.removeAt(asNumber(args[0]).toInt())
            }
            "remove" -> Builtin("remove") { args, _ ->
                if (!list.remove(args[0])) throw PyError("value not in list")
                null
            }
            "index" -> Builtin("index") { args, _ ->
                val at = list.indexOf(args[0])
                if (at < 0) throw PyError("value not in list") else at
            }
            "count" -> Builtin("count") { args, _ -> list.count { pyEquals(it, args[0]) } }
            "sort" -> Builtin("sort") { _, _ -> list.sortWith { a, b -> compare(a, b) }; null }
            "reverse" -> Builtin("reverse") { _, _ -> list.reverse(); null }
            "clear" -> Builtin("clear") { _, _ -> list.clear(); null }
            else -> throw PyError("list has no method '$name'")
        }

        private fun mapMethod(map: MutableMap<Any?, Any?>, name: String): Any? = when (name) {
            "keys" -> Builtin("keys") { _, _ -> map.keys.toMutableList() }
            "values" -> Builtin("values") { _, _ -> map.values.toMutableList() }
            "items" -> Builtin("items") { _, _ -> map.entries.map { listOf(it.key, it.value) }.toMutableList() }
            "get" -> Builtin("get") { args, _ -> map[args[0]] ?: args.getOrNull(1) }
            "pop" -> Builtin("pop") { args, _ ->
                val key = args[0]
                if (!map.containsKey(key)) throw PyError("key not found")
                map.remove(key)
            }
            "update" -> Builtin("update") { args, _ ->
                @Suppress("UNCHECKED_CAST")
                (args[0] as? Map<Any?, Any?>)?.forEach { (key, value) -> map[key] = value }
                null
            }
            "clear" -> Builtin("clear") { _, _ -> map.clear(); null }
            else -> throw PyError("dict has no method '$name'")
        }

        private fun setMethod(set: MutableSet<Any?>, name: String): Any? = when (name) {
            "add" -> Builtin("add") { args, _ -> set.add(args[0]); null }
            "remove" -> Builtin("remove") { args, _ -> set.remove(args[0]); null }
            "discard" -> Builtin("discard") { args, _ -> set.remove(args[0]); null }
            "clear" -> Builtin("clear") { _, _ -> set.clear(); null }
            else -> throw PyError("set has no method '$name'")
        }

        // ---- builtins -------------------------------------------------------
        private fun installBuiltins(env: Env) {
            val builtins = linkedMapOf(
                "print" to Builtin("print") { args, _ ->
                    output.append(args.joinToString(" ") { pyStr(it) }).append('\n')
                    null
                },
                "len" to Builtin("len") { args, _ ->
                    when (val value = args[0]) {
                        is String -> value.length
                        is List<*> -> value.size
                        is Map<*, *> -> value.size
                        is Set<*> -> value.size
                        else -> throw PyError("object has no length")
                    }
                },
                "range" to Builtin("range") { args, _ ->
                    val start: Double
                    val end: Double
                    val step: Double
                    when (args.size) {
                        1 -> {
                            start = 0.0; end = asNumber(args[0]); step = 1.0
                        }
                        2 -> {
                            start = asNumber(args[0]); end = asNumber(args[1]); step = 1.0
                        }
                        else -> {
                            start = asNumber(args[0]); end = asNumber(args[1]); step = asNumber(args[2])
                        }
                    }
                    if (step == 0.0) throw PyError("range() step must not be zero")
                    val values = ArrayList<Any?>()
                    var current = start
                    var guard = 0
                    while ((step > 0 && current < end) || (step < 0 && current > end)) {
                        values.add(current)
                        current += step
                        if (++guard > 1_000_000) break
                    }
                    values
                },
                "int" to Builtin("int") { args, _ ->
                    when (val value = args[0]) {
                        is Number -> value.toDouble().toInt().toDouble()
                        is String -> value.trim().toDoubleOrNull()?.toInt()?.toDouble()
                            ?: throw PyError("invalid literal for int()")
                        else -> throw PyError("int() needs a number or string")
                    }
                },
                "float" to Builtin("float") { args, _ -> asNumber(args[0]) },
                "str" to Builtin("str") { args, _ -> pyStr(args[0]) },
                "bool" to Builtin("bool") { args, _ -> truthy(args[0]) },
                "list" to Builtin("list") { args, _ -> iterate(args[0]).toMutableList() },
                "tuple" to Builtin("tuple") { args, _ -> iterate(args[0]).toMutableList() },
                "dict" to Builtin("dict") { args, _ ->
                    if (args.isEmpty()) {
                        LinkedHashMap<Any?, Any?>()
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        args[0] as? Map<Any?, Any?> ?: LinkedHashMap()
                    }
                },
                "set" to Builtin("set") { args, _ -> iterate(args[0]).toMutableSet() },
                "sum" to Builtin("sum") { args, _ -> iterate(args[0]).sumOf { asNumber(it) } },
                "min" to Builtin("min") { args, _ ->
                    val items = iterate(args[0])
                    if (items.isEmpty()) throw PyError("min() of an empty sequence")
                    items.minWithOrNull { a, b -> compare(a, b) }!!
                },
                "max" to Builtin("max") { args, _ ->
                    val items = iterate(args[0])
                    if (items.isEmpty()) throw PyError("max() of an empty sequence")
                    items.maxWithOrNull { a, b -> compare(a, b) }!!
                },
                "abs" to Builtin("abs") { args, _ -> abs(asNumber(args[0])) },
                "round" to Builtin("round") { args, _ ->
                    val value = asNumber(args[0])
                    val digits = if (args.size > 1) asNumber(args[1]).toInt() else 0
                    val factor = 10.0.pow(digits)
                    (value * factor).toInt().toDouble() / factor
                },
                "sorted" to Builtin("sorted") { args, _ ->
                    val items = iterate(args[0]).toMutableList()
                    items.sortWith { a, b -> compare(a, b) }
                    items
                },
                "reversed" to Builtin("reversed") { args, _ -> iterate(args[0]).reversed().toMutableList() },
                "enumerate" to Builtin("enumerate") { args, _ ->
                    iterate(args[0]).mapIndexed { index, item -> listOf(index.toDouble(), item) }.toMutableList()
                },
                "zip" to Builtin("zip") { args, _ ->
                    val lists = args.map { iterate(it) }
                    val size = lists.minOfOrNull { it.size } ?: 0
                    (0 until size).map { index -> lists.map { it[index] } }.toMutableList()
                },
                "any" to Builtin("any") { args, _ -> iterate(args[0]).any { truthy(it) } },
                "all" to Builtin("all") { args, _ -> iterate(args[0]).all { truthy(it) } },
                "type" to Builtin("type") { args, _ ->
                    when (args[0]) {
                        is Number -> "int"
                        is String -> "str"
                        is Boolean -> "bool"
                        is List<*> -> "list"
                        is Map<*, *> -> "dict"
                        is Set<*> -> "set"
                        null -> "NoneType"
                        else -> "object"
                    }
                },
                "isinstance" to Builtin("isinstance") { args, _ ->
                    val name = pyStr(args[1])
                    val actual = when (args[0]) {
                        is Number -> "int"
                        is String -> "str"
                        is Boolean -> "bool"
                        is List<*> -> "list"
                        is Map<*, *> -> "dict"
                        null -> "NoneType"
                        else -> "object"
                    }
                    actual == name || (name == "float" && actual == "int")
                },
                "ord" to Builtin("ord") { args, _ -> pyStr(args[0]).first().code.toDouble() },
                "chr" to Builtin("chr") { args, _ -> asNumber(args[0]).toInt().toChar().toString() },
                "repr" to Builtin("repr") { args, _ -> "'" + pyStr(args[0]).replace("'", "\\'") + "'" },
                "help" to Builtin("help") { _, _ ->
                    "AnyMate Python runtime — arithmetic, strings, lists, dicts, loops, functions, comprehensions, math/random/time/os."
                }
            )
            builtins.forEach { (name, value) -> env.define(name, value) }
        }

        init {
            installBuiltins(globals)
        }

        // ---- value helpers --------------------------------------------------
        companion object {
            fun truthy(value: Any?): Boolean = when (value) {
                null -> false
                is Boolean -> value
                is Number -> value.toDouble() != 0.0
                is String -> value.isNotEmpty()
                is List<*> -> value.isNotEmpty()
                is Map<*, *> -> value.isNotEmpty()
                is Set<*> -> value.isNotEmpty()
                else -> true
            }

            fun asNumber(value: Any?): Double = when (value) {
                is Number -> value.toDouble()
                is Boolean -> if (value) 1.0 else 0.0
                is String -> value.trim().toDoubleOrNull()
                    ?: throw PyError("cannot convert '${value.take(24)}' to a number")
                null -> 0.0
                else -> throw PyError("this value is not a number")
            }

            fun pyStr(value: Any?): String = when (value) {
                null -> "None"
                is String -> value
                is Double -> if (value == floor(value) && abs(value) < 1e15) {
                    value.toLong().toString()
                } else {
                    value.toString()
                }
                is Float -> pyStr(value.toDouble())
                is Boolean -> if (value) "True" else "False"
                is List<*> -> value.joinToString(", ", "[", "]") { pyStr(it) }
                is Set<*> -> value.joinToString(", ", "{", "}") { pyStr(it) }
                is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { "${pyStr(it.key)}: ${pyStr(it.value)}" }
                else -> value.toString()
            }

            fun pyEquals(left: Any?, right: Any?): Boolean = when {
                left is Number && right is Number -> left.toDouble() == right.toDouble()
                else -> left == right
            }

            fun iterate(value: Any?): List<Any?> = when (value) {
                is List<*> -> value
                is Set<*> -> value.toList()
                is String -> value.map { it.toString() }
                is Map<*, *> -> value.entries.map { listOf(it.key, it.value) }
                is Number -> {
                    val count = value.toInt().coerceIn(0, 100_000)
                    (0 until count).map { it.toDouble() }
                }
                null -> emptyList()
                else -> throw PyError("this value is not iterable")
            }
        }
    }

    private class PyError(message: String) : RuntimeException(message)
}
