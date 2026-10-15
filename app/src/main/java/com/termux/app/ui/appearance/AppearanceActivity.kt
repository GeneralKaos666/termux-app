package com.termux.app.ui.appearance

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.termux.R
import com.termux.app.ui.theme.TermuxShellTheme
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import java.io.File
import java.io.InputStream

/**
 * Hosts the Compose [AppearanceScreen]. Reached from the terminal's Style action and Settings; on
 * Apply it writes the appearance and bumps the styling revision, so the terminal reloads when it
 * next resumes (the reload broadcast is ignored while the terminal is not visible).
 */
class AppearanceActivity : ComponentActivity() {

    private lateinit var controller: AppearanceController
    private lateinit var preferences: AndroidAppearancePreferences

    /** The cache file staged for the last pick, deleted when a newer pick replaces it. */
    private var pendingFontCandidate: File? = null

    private val pickFont = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult

        val candidate = copyFontToCache(uri)
        val error = candidate?.let(FontValidator::validate)
        when {
            candidate == null -> Toast.makeText(this, R.string.appearance_font_error_not_found, Toast.LENGTH_SHORT).show()
            error != null -> {
                candidate.delete()
                Toast.makeText(this, fontErrorMessage(error), Toast.LENGTH_SHORT).show()
            }
            else -> {
                // Each pick gets its own cache file, so the preview reloads the typeface instead of
                // reusing the one already cached against the previous file's (unchanged) path.
                pendingFontCandidate?.delete()
                pendingFontCandidate = candidate
                controller.chooseFont(candidate)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferences = AndroidAppearancePreferences(this)
        controller = AppearanceController(
            AppearanceStore(
                colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE,
                fontFile = TermuxConstants.TERMUX_FONT_FILE,
                preferences = preferences,
            ),
        )

        setContent {
            TermuxShellTheme {
                AppearanceScreen(
                    controller = controller,
                    fontSizeRange = preferences.fontSizeRange,
                    onPickFont = { pickFont.launch(FONT_MIME_TYPE) },
                    onApplied = ::onAppearanceApplied,
                    onDiscard = { finish() },
                )
            }
        }
    }

    private fun onAppearanceApplied(result: AppearanceWriteResult) {
        result.fontError?.let { error ->
            Toast.makeText(this, fontErrorMessage(error), Toast.LENGTH_LONG).show()
        }
        // Bump the revision so the terminal reloads when it next resumes; the reload cannot be a
        // broadcast because the terminal is stopped while this screen is on top (see AppearanceStyling).
        AppearanceStyling.bumpRevision(this)
        finish()
    }

    /**
     * Copies the picked document into a fresh cache file so the store installs a stable file and the
     * preview sees a distinct path per pick. Returns `null` when the document cannot be read or is
     * obviously not a font-sized file.
     */
    private fun copyFontToCache(uri: Uri): File? {
        val target = try {
            File.createTempFile(FONT_CANDIDATE_PREFIX, FONT_CANDIDATE_SUFFIX, cacheDir)
        } catch (t: Throwable) {
            return null
        }
        return try {
            val length = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (length == 0L || length > FontValidator.MAX_FONT_BYTES) {
                target.delete()
                null
            } else {
                // A descriptor with an unknown length still gets bounded, so a huge stream is not
                // copied into the cache before the size cap rejects it.
                val copied = contentResolver.openInputStream(uri)?.use { input ->
                    writeBounded(input, target, FontValidator.MAX_FONT_BYTES)
                } ?: false
                if (copied && target.length() > 0) {
                    target
                } else {
                    target.delete()
                    null
                }
            }
        } catch (t: Throwable) {
            target.delete()
            null
        }
    }

    /** Copies at most [limit] bytes, returning `false` without a complete file when it is exceeded. */
    private fun writeBounded(input: InputStream, target: File, limit: Long): Boolean {
        target.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) return false
                output.write(buffer, 0, read)
            }
        }
        return true
    }

    private fun fontErrorMessage(error: FontError): Int = when (error) {
        FontError.NOT_FOUND -> R.string.appearance_font_error_not_found
        FontError.INVALID -> R.string.appearance_font_error_invalid
        FontError.TOO_LARGE -> R.string.appearance_font_error_too_large
        FontError.COPY_FAILED -> R.string.appearance_font_error_copy_failed
    }

    private companion object {
        const val FONT_CANDIDATE_PREFIX = "appearance-font-"
        const val FONT_CANDIDATE_SUFFIX = ".ttf"
        const val FONT_MIME_TYPE = "*/*"
    }
}

/**
 * The Android-backed [AppearancePreferences]. Font size uses the app's existing preference; the
 * cursor settings are resolved by [CursorPreferences], which prefers an app-local override and
 * falls back to `termux.properties`.
 */
class AndroidAppearancePreferences(context: Context) : AppearancePreferences {

    private val appPreferences: TermuxAppSharedPreferences? =
        TermuxAppSharedPreferences.build(context.applicationContext)

    private val cursorPreferences = CursorPreferences(context)

    /**
     * The selectable font-size range in physical pixels, from the app's density-aware defaults
     * (index 1 is the minimum, index 2 the maximum), so the slider matches what the terminal clamps to.
     */
    val fontSizeRange: IntRange =
        TermuxAppSharedPreferences.getDefaultFontSizes(context.applicationContext).let { it[1]..it[2] }

    override fun getFontSize(): Int = appPreferences?.getFontSize() ?: DEFAULT_FONT_SIZE

    override fun setFontSize(size: Int) {
        appPreferences?.setFontSize(size)
    }

    override fun getCursorStyle(): Int? = cursorPreferences.cursorStyle()

    override fun setCursorStyle(style: Int?) = cursorPreferences.setCursorStyle(style)

    override fun getCursorBlinkRate(): Int? = cursorPreferences.cursorBlinkRate()

    override fun setCursorBlinkRate(rate: Int?) = cursorPreferences.setCursorBlinkRate(rate)

    private companion object {
        const val DEFAULT_FONT_SIZE = 14
    }
}
