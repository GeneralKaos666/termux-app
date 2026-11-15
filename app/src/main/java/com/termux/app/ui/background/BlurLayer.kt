package com.termux.app.ui.background

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import com.termux.app.ui.background.BackgroundFx.Crop
import kotlin.math.roundToInt

/**
 * The blurred terminal background as one reusable layer.
 *
 * `DrawScope.drawBehind` runs on every frame, so the `Paint`, both `Rect`s, the `RenderEffect` and
 * the recorded `RenderNode` are kept here between frames. The node is re-recorded only when what it
 * captured changes: the picture instance, the destination size, the crop, the radius or the
 * opacity. Everything else replays the existing recording, so a static background costs one
 * `drawRenderNode` per frame.
 *
 * On API 31+ the blur is a `RenderEffect` on the node. Below that the effect is not available, so
 * the picture is drawn directly with a filtered upscale: [com.termux.app.TermuxActivity] already
 * downscaled it at load time by [BackgroundFx.blurDownscale], which is the fallback blur.
 */
class BlurLayer {

    private val paint = Paint().apply { isFilterBitmap = true }
    private val src = Rect()
    private val dst = Rect()

    private val node =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RenderNode("terminalBackgroundBlur") else null

    private var effectRadius = Float.NaN
    private var effect: RenderEffect? = null

    private var recordedPicture: Bitmap? = null
    private var recordedWidth = 0
    private var recordedHeight = 0
    private var recordedCrop: Crop? = null
    private var recordedRadius = Float.NaN
    private var recordedAlpha = -1

    /**
     * Draws [picture] centre-cropped by [crop] into the [width] x [height] area at the origin, with
     * the blur [radius] and the [opacity] fraction applied.
     */
    fun draw(
        canvas: Canvas,
        picture: Bitmap,
        crop: Crop,
        width: Int,
        height: Int,
        radius: Float,
        opacity: Float,
    ) {
        // The alpha is derived here rather than kept by the caller, so every mutation of the paint
        // happens in one place, close to the early return below.
        val alpha = (opacity * 255f).roundToInt().coerceIn(0, 255)
        paint.alpha = alpha
        src.set(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height)
        dst.set(0, 0, width, height)

        if (node == null || !canvas.isHardwareAccelerated) {
            canvas.drawBitmap(picture, src, dst, paint)
            return
        }

        if (picture !== recordedPicture
            || width != recordedWidth
            || height != recordedHeight
            || crop != recordedCrop
            || radius != recordedRadius
            || alpha != recordedAlpha
        ) {
            if (radius != effectRadius) {
                effect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                effectRadius = radius
            }
            node.setPosition(0, 0, width, height)
            node.setRenderEffect(effect)
            val recording = node.beginRecording(width, height)
            recording.drawBitmap(picture, src, dst, paint)
            node.endRecording()

            recordedPicture = picture
            recordedWidth = width
            recordedHeight = height
            recordedCrop = crop
            recordedRadius = radius
            recordedAlpha = alpha
        }
        canvas.drawRenderNode(node)
    }
}
