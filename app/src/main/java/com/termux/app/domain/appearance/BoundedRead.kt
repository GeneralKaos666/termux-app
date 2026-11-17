package com.termux.app.domain.appearance

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads at most [limit] bytes of UTF-8 text; `null` when the stream is absent or larger. */
internal fun readBounded(input: InputStream?, limit: Long): String? {
    if (input == null) return null
    val buffer = ByteArray(8192)
    val out = ByteArrayOutputStream()
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        if (out.size() > limit) return null
    }
    return out.toString(Charsets.UTF_8.name())
}
