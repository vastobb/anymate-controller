package com.anymate.controller.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PythonLiteTest {

    private val workspace: File = File(System.getProperty("java.io.tmpdir"), "anymate-py-test").apply {
        mkdirs()
    }

    private fun run(code: String) = PythonLite.run(workspace, code)

    @Test
    fun arithmeticFollowsPrecedence() {
        assertEquals("14\n", run("print(2 + 3 * 4)").stdout)
        assertEquals("1.5\n", run("print(3 / 2)").stdout)
        assertEquals("2\n", run("print(7 // 3)").stdout)
        assertEquals("1\n", run("print(7 % 3)").stdout)
        assertEquals("8\n", run("print(2 ** 3)").stdout)
    }

    @Test
    fun stringsAndFormatting() {
        assertEquals("HELLO\n", run("print('hello'.upper())").stdout)
        assertEquals("['a', 'b']\n", run("print('a,b'.split(','))").stdout)
        assertEquals("hi anymate\n", run("name = 'anymate'\nprint(f'hi {name}')").stdout)
        assertEquals("3\n", run("print(len('abc'))").stdout)
    }

    @Test
    fun variablesAndAugmentedAssignment() {
        assertEquals("6\n", run("x = 2\nx += 4\nprint(x)").stdout)
        assertEquals("5\n", run("x = 1\nx = x * 5\nprint(x)").stdout)
    }

    @Test
    fun loopsWithIndentation() {
        val forLoop = run("for i in range(3):\n    print(i)\n")
        assertEquals("0\n1\n2\n", forLoop.stdout)

        val whileLoop = run("i = 0\nwhile i < 3:\n    i += 1\n    print(i)\n")
        assertEquals("1\n2\n3\n", whileLoop.stdout)
    }

    @Test
    fun breakAndContinue() {
        val output = run("for i in range(10):\n    if i == 5:\n        break\n    if i % 2 == 0:\n        continue\n    print(i)\n")
        assertEquals("1\n3\n", output.stdout)
    }

    @Test
    fun conditionals() {
        assertEquals("big\n", run("size = 10\nif size > 5:\n    print('big')\nelse:\n    print('small')\n").stdout)
        assertEquals("mid\n", run("size = 5\nif size > 8:\n    print('big')\nelif size > 3:\n    print('mid')\nelse:\n    print('small')\n").stdout)
    }

    @Test
    fun functionsWithDefaultsAndReturn() {
        val output = run("def add(a, b=2):\n    return a + b\n\nprint(add(3))\nprint(add(3, 4))\n")
        assertEquals("5\n7\n", output.stdout)
    }

    @Test
    fun listsAndMethods() {
        assertEquals("[1, 2, 3]\n", run("items = [3, 1, 2]\nitems.sort()\nprint(items)\n").stdout)
        assertEquals("3\n", run("items = [1, 2]\nitems.append(3)\nprint(len(items))\n").stdout)
        assertEquals("1\n", run("items = [1, 2, 3]\nprint(items.index(2))\n").stdout)
        assertEquals("6\n", run("items = [1, 2, 3]\nprint(sum(items))\n").stdout)
    }

    @Test
    fun dictionaries() {
        assertEquals("2\n", run("d = {'a': 1}\nd['b'] = 2\nprint(d['b'])\n").stdout)
        assertEquals("['a']\n", run("d = {'a': 1}\nprint(d.keys())\n").stdout)
        assertEquals("1\n", run("d = {'a': 1}\nprint(d.get('a'))\n").stdout)
    }

    @Test
    fun comprehensions() {
        assertEquals("[0, 2, 4, 6]\n", run("print([x * 2 for x in range(4)])\n").stdout)
        assertEquals("[2, 4]\n", run("print([x for x in range(6) if x % 2 == 0 and x > 0])\n").stdout)
    }

    @Test
    fun mathModule() {
        assertEquals("4\n", run("import math\nprint(math.floor(4.7))\n").stdout)
        assertTrue(run("import math\nprint(round(math.sqrt(16)))\n").stdout.startsWith("4"))
    }

    @Test
    fun slicingAndIndexing() {
        assertEquals("bcd\n", run("s = 'abcdef'\nprint(s[1:4])\n").stdout)
        assertEquals("[2, 3]\n", run("l = [1, 2, 3]\nprint(l[1:])\n").stdout)
    }

    @Test
    fun errorsAreCapturedNotThrown() {
        val result = run("print(1 / 0)\n")
        assertTrue(result.exitCode != 0)
        assertTrue(result.stderr.contains("division by zero"))
    }

    @Test
    fun unknownNameIsReported() {
        val result = run("print(missing_variable)\n")
        assertTrue(result.exitCode != 0)
        assertTrue(result.stderr.contains("missing_variable"))
    }

    @Test
    fun writesFilesThroughTheRuntime() {
        val result = run(
            "import os\n" +
                "os.makedirs('out')\n" +
                "print(os.path.join('out', 'frame.png'))\n"
        )
        assertTrue(result.stdout.contains("out/frame.png"))
    }
}
