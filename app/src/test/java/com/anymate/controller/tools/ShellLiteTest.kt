package com.anymate.controller.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ShellLiteTest {

    private lateinit var workspace: File

    @Before
    fun setUp() {
        workspace = File(System.getProperty("java.io.tmpdir"), "anymate-shell-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun run(command: String) = ShellLite.run(workspace, command)

    @Test
    fun echoPrints() {
        assertEquals("hello\n", run("echo hello").stdout)
    }

    @Test
    fun writeReadAndList() {
        run("mkdir demo")
        run("echo frame one > demo/notes.txt")
        assertTrue(run("cat demo/notes.txt").stdout.contains("frame one"))
        assertTrue(run("ls demo").stdout.contains("notes.txt"))
    }

    @Test
    fun grepFindsLines() {
        run("echo alpha > a.txt")
        run("echo beta > b.txt")
        val result = run("grep alpha a.txt b.txt")
        assertTrue(result.stdout.contains("alpha"))
        assertTrue(!result.stdout.contains("beta"))
    }

    @Test
    fun pipesChainCommands() {
        run("echo one > a.txt")
        run("echo two > b.txt")
        val result = run("cat a.txt b.txt | grep two")
        assertTrue(result.stdout.contains("two"))
    }

    @Test
    fun pythonCommandRunsTheRuntime() {
        val result = run("python print(6 * 7)")
        assertTrue(result.stdout.contains("42"))
    }

    @Test
    fun unknownCommandFails() {
        val result = run("nope")
        assertTrue(result.exitCode != 0)
        assertTrue(result.stderr.contains("unknown command"))
    }

    @Test
    fun pathsCannotEscapeTheWorkspace() {
        val result = run("cat ../../etc/passwd")
        assertTrue(result.exitCode != 0)
    }
}
