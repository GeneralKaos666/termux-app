package com.termux.app.domain.sessions

import java.io.File
import java.io.IOException

/**
 * Reads the command running in the foreground of a shell from `/proc`, without JNI.
 *
 * The shell's foreground process group is taken from its own `stat`, then the shell's children are
 * inspected for a process in that group. Everything here is best-effort: a denied or vanished entry
 * means "no command label", never an error the tab should show.
 */
fun readForegroundCommand(pid: Int, procRoot: File = File("/proc")): String? {
    if (pid < 1) return null
    return try {
        val shellStat = File(procRoot, "$pid/stat").readText()
        val pgrp = parsePgrp(shellStat) ?: return null
        val children = File(procRoot, "$pid/task/$pid/children").readText().trim()
        if (children.isEmpty()) return null

        for (raw in children.split(Regex("\\s+"))) {
            val childPid = raw.toIntOrNull() ?: continue
            val childStat = File(procRoot, "$childPid/stat").readText()
            if (parsePgrp(childStat) == pgrp) return parseStatComm(childStat)
        }
        null
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}

/** The command name in a `/proc/<pid>/stat` line, between the first `(` and the last `)`. */
internal fun parseStatComm(stat: String): String? {
    val open = stat.indexOf('(')
    val close = stat.lastIndexOf(')')
    if (open < 0 || close <= open) return null
    return stat.substring(open + 1, close)
}

/**
 * The process group id from a `/proc/<pid>/stat` line: the third whitespace field after the
 * closing parenthesis (state, ppid, pgrp).
 */
internal fun parsePgrp(stat: String): Int? {
    val close = stat.lastIndexOf(')')
    if (close < 0) return null
    val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
    return fields.getOrNull(2)?.toIntOrNull()
}
