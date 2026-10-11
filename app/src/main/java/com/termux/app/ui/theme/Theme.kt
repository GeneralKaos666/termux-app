package com.termux.app.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Fallback shell palette, seeded from the always-dark terminal: black surfaces, light content and
 * the app's grey accent. Used below API 31 and whenever dynamic color is unavailable.
 */
internal val TermuxFallbackColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = Color(0xFFECECEC),
    secondary = Color(0xFF9E9E9E),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFE0E0E0),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF202020),
    onSurfaceVariant = Color(0xFFBDBDBD),
    error = Color(0xFFCF6679),
    onError = Color(0xFF000000),
    outline = Color(0xFF8A8A8A),
)

/**
 * True when system animations are enabled (i.e. the "remove animations" setting is off and the
 * animator duration scale is non-zero). Shell motion should be instant when this is false.
 */
@Composable
fun shellAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale > 0f
    }
}

/**
 * The shell theme: Material You dynamic color (dark variant, so the shell stays dark next to the
 * always-dark terminal) guarded to API 31+, falling back to the Termux palette. The terminal's own
 * theme (terminal colours, key grid) is untouched.
 */
@Composable
fun TermuxShellTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                dynamicDarkColorScheme(context)
            } catch (t: Throwable) {
                // Some OEMs throw when parsing the system palette; fall back gracefully.
                TermuxFallbackColors
            }
        } else {
            TermuxFallbackColors
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
