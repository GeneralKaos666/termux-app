package com.termux.app.domain.sessions

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ForegroundProcessTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `parses the comm after the last paren`() {
        assertEquals(
            "my (odd) shell",
            parseStatComm("42 (my (odd) shell) S 1 42 42 0 -1"),
        )
    }

    @Test
    fun `parses the process group`() {
        assertEquals(42, parsePgrp("42 (bash) S 1 42 42 0 -1"))
    }

    @Test
    fun `pid below one is absent`() {
        assertNull(readForegroundCommand(0))
        assertNull(readForegroundCommand(-1))
    }

    @Test
    fun `missing proc entry is absent`() {
        assertNull(readForegroundCommand(424242, temporary.root))
    }

    @Test
    fun `returns the child in the shell's process group`() {
        val pid = 42
        val procRoot = temporary.root
        writeFile(procRoot, pid, "stat", "42 (bash) S 1 42 42 0 -1")
        writeFile(procRoot, pid, "task/$pid/children", "77 78 ")
        writeFile(procRoot, 77, "stat", "77 (other) S 42 41 41 0 -1")
        writeFile(procRoot, 78, "stat", "78 (npm) S 42 42 42 0 -1")

        assertEquals("npm", readForegroundCommand(pid, procRoot))
    }

    @Test
    fun `no matching child is absent`() {
        val pid = 42
        val procRoot = temporary.root
        writeFile(procRoot, pid, "stat", "42 (bash) S 1 42 42 0 -1")
        writeFile(procRoot, pid, "task/$pid/children", "")

        assertNull(readForegroundCommand(pid, procRoot))
    }

    private fun writeFile(root: File, pid: Int, relative: String, content: String) {
        val file = File(root, "$pid/$relative")
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
