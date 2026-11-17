package com.termux.app.domain.appearance

import java.io.File

/**
 * The font picked but not yet installed. Each pick copies into a fresh cache file, so the holder
 * deletes the file it replaces and the one it abandons when the screen closes or applies.
 */
internal class PendingFontFile {
    var file: File? = null
        private set

    fun replace(next: File) {
        clear()
        file = next
    }

    fun clear() {
        file?.delete()
        file = null
    }
}
