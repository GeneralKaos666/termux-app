package com.termux.app.data

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.DisplayMetrics
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.termux.app.domain.background.BackgroundFx
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

interface WallpaperSource {
    fun systemWallpaper(): Bitmap?
    fun customImage(path: String?): Bitmap?
    fun downscaleForBlur(bitmap: Bitmap?, blurRadius: Float): Bitmap?
}

class AndroidWallpaperSource @JvmOverloads constructor(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : WallpaperSource {

    private val appContext = context.applicationContext
    private val resources = appContext.resources

    @Nullable
    @SuppressWarnings("deprecation")
    override fun systemWallpaper(): Bitmap? {
        return try {
            val wallpaperManager = WallpaperManager.getInstance(appContext)
            val liveWallpaper = wallpaperManager.wallpaperInfo != null
            val wallpaperDrawable = wallpaperManager.drawable
            val usableFrame = wallpaperDrawable != null
                && wallpaperDrawable.intrinsicWidth > 0
                && wallpaperDrawable.intrinsicHeight > 0
            if (BackgroundFx.useWallpaperFallbackColor(liveWallpaper, usableFrame)) {
                com.termux.shared.logger.Logger.logDebug(LOG_TAG, if (liveWallpaper)
                    "Live wallpaper frame not captured, using the fallback colour"
                else
                    "No usable wallpaper frame, using the fallback colour")
                wallpaperFallbackBitmap(wallpaperManager)
            } else {
                drawableToScreenBitmap(wallpaperDrawable!!)
            }
        } catch (e: Exception) {
            com.termux.shared.logger.Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load the system wallpaper", e)
            null
        }
    }

    @Nullable
    override fun customImage(path: String?): Bitmap? {
        if (path.isNullOrEmpty()) return null
        return try {
            val displayMetrics: DisplayMetrics = resources.displayMetrics
            val targetW = displayMetrics.widthPixels
            val targetH = displayMetrics.heightPixels

            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, options)
            val srcW = max(options.outWidth, 1)
            val srcH = max(options.outHeight, 1)
            var inSampleSize = 1
            while ((srcW / inSampleSize) > targetW * 2 || (srcH / inSampleSize) > targetH * 2)
                inSampleSize *= 2

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            com.termux.shared.logger.Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load the custom terminal background image", e)
            null
        }
    }

    @Nullable
    override fun downscaleForBlur(@Nullable bitmap: Bitmap?, blurRadius: Float): Bitmap? {
        if (bitmap == null || blurRadius <= 0f) return bitmap
        if (sdkInt >= Build.VERSION_CODES.S) return bitmap
        val divisor = BackgroundFx.blurDownscale(blurRadius)
        if (divisor <= 1f) return bitmap
        val width = max(1, (bitmap.width / divisor).roundToInt())
        val height = max(1, (bitmap.height / divisor).roundToInt())
        if (width == bitmap.width && height == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    @NonNull
    private fun wallpaperFallbackBitmap(@NonNull wallpaperManager: WallpaperManager): Bitmap {
        val terminalBackgroundColor =
            com.termux.terminal.TerminalColors.COLOR_SCHEME.mDefaultColors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND]
        var colorsAvailable = false
        var primaryColor = terminalBackgroundColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val colors: WallpaperColors? = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            if (colors != null) {
                colorsAvailable = true
                primaryColor = colors.primaryColor.toArgb()
            }
        }
        val color = BackgroundFx.wallpaperFallbackColor(colorsAvailable, primaryColor, terminalBackgroundColor)
        com.termux.shared.logger.Logger.logDebug(LOG_TAG, "Live-wallpaper fallback colour: "
            + if (colorsAvailable) "wallpaper primary" else "terminal background")
        return Bitmap.createBitmap(intArrayOf(color), 1, 1, Bitmap.Config.ARGB_8888)
    }

    @Nullable
    private fun drawableToScreenBitmap(@NonNull drawable: Drawable): Bitmap? {
        val displayMetrics = resources.displayMetrics
        if (displayMetrics.widthPixels <= 0 || displayMetrics.heightPixels <= 0) return null

        val srcW = max(drawable.intrinsicWidth, 1)
        val srcH = max(drawable.intrinsicHeight, 1)
        val scale = min(1f, min(displayMetrics.widthPixels.toFloat() / srcW, displayMetrics.heightPixels.toFloat() / srcH))
        val dstW = max(1, (srcW * scale).roundToInt())
        val dstH = max(1, (srcH * scale).roundToInt())

        val bitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, dstW, dstH)
        drawable.draw(canvas)
        return bitmap
    }

    private companion object {
        const val LOG_TAG = "TermuxActivity"
    }
}
