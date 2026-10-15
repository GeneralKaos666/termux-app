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
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import java.io.File

/**
 * Hosts the Compose [AppearanceScreen]. Reached from the terminal's Style action and Settings; on
 * Apply it writes the appearance and bumps the styling revision, so the terminal reloads when it
 * next resumes (the reload broadcast is ignored while the terminal is not visible).
 */
class AppearanceActivity : ComponentActivity() {

    private lateinit var controller: AppearanceController
    private lateinit var preferences: AndroidAppearancePreferences

    private val pickFont = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult

        val candidate = copyFontToCache(uri)
        val error = candidate?.let(FontValidator::validate)
        when {
            candidate == null -> Toast.makeText(this, R.string.appearance_font_error_not_found, Toast.LENGTH_SHORT).show()
            error != null -> Toast.makeText(this, fontErrorMessage(error), Toast.LENGTH_SHORT).show()
            else -> controller.chooseFont(candidate)
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
     * Copies the picked document into the cache so the store installs a stable file. Returns `null`
     * when the document cannot be read or is obviously not a font-sized file.
     */
    private fun copyFontToCache(uri: Uri): File? = try {
        val length = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (length == 0L || length > FontValidator.MAX_FONT_BYTES) {
            null
        } else {
            val target = File(cacheDir, FONT_CANDIDATE_FILE_NAME)
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.takeIf { it.isFile && it.length() > 0 }
        }
    } catch (t: Throwable) {
        null
    }

    private fun fontErrorMessage(error: FontError): Int = when (error) {
        FontError.NOT_FOUND -> R.string.appearance_font_error_not_found
        FontError.INVALID -> R.string.appearance_font_error_invalid
        FontError.TOO_LARGE -> R.string.appearance_font_error_too_large
        FontError.COPY_FAILED -> R.string.appearance_font_error_copy_failed
    }

    private companion object {
        const val FONT_CANDIDATE_FILE_NAME = "appearance-font.ttf"
        const val FONT_MIME_TYPE = "*/*"
    }
}

/**
 * The Android-backed [AppearancePreferences]. Font size uses the app's existing preference; the
 * cursor settings live in an app-local file, falling back to `termux.properties` so a value set
 * there is still shown and honoured until the screen overrides it.
 */
class AndroidAppearancePreferences(context: Context) : AppearancePreferences {

    private val appPreferences: TermuxAppSharedPreferences? =
        TermuxAppSharedPreferences.build(context.applicationContext)

    private val appearancePreferences =
        context.applicationContext.getSharedPreferences(
            AppearancePreferenceKeys.PREFERENCES_FILE,
            Context.MODE_PRIVATE,
        )

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

    override fun getCursorStyle(): Int? =
        if (appearancePreferences.contains(AppearancePreferenceKeys.CURSOR_STYLE)) {
            appearancePreferences.getInt(AppearancePreferenceKeys.CURSOR_STYLE, CursorStyle.BLOCK.value)
        } else {
            propertiesCursorStyle()
        }

    override fun setCursorStyle(style: Int?) = putCursorPreference(AppearancePreferenceKeys.CURSOR_STYLE, style)

    override fun getCursorBlinkRate(): Int? =
        if (appearancePreferences.contains(AppearancePreferenceKeys.CURSOR_BLINK_RATE)) {
            appearancePreferences.getInt(AppearancePreferenceKeys.CURSOR_BLINK_RATE, 0)
        } else {
            propertiesCursorBlinkRate()
        }

    override fun setCursorBlinkRate(rate: Int?) =
        putCursorPreference(AppearancePreferenceKeys.CURSOR_BLINK_RATE, rate)

    private fun putCursorPreference(key: String, value: Int?) {
        appearancePreferences.edit().apply {
            if (value == null) remove(key) else putInt(key, value)
        }.apply()
    }

    private fun propertiesCursorStyle(): Int? =
        TermuxAppSharedProperties.getProperties()?.getTerminalCursorStyle()

    private fun propertiesCursorBlinkRate(): Int? =
        TermuxAppSharedProperties.getProperties()?.getTerminalCursorBlinkRate()

    private companion object {
        const val DEFAULT_FONT_SIZE = 14
    }
}
