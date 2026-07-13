package com.example.desktoppet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/**
 * Draws one frame of a [SpriteSheet] at a time and loops through a frame list.
 *
 * Frames may have different sizes (see [SpriteSheet]), so each frame is drawn
 * anchored to the BOTTOM-CENTER of the view's box: feet stay on the same line and
 * the body stays centered, which keeps a walk cycle from jittering. Rendering is
 * nearest-neighbour so upscaled pixel art stays crisp.
 */
class SpriteView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private var frames: List<Rect> = emptyList()
    private var frameIndex = 0
    private var frameDurationMs = PetAnimations.WALK_FRAME_MS

    /** Size of the display box, taken from the largest frame in the current animation. */
    private var boxWidth = 1
    private var boxHeight = 1

    /** Mirror horizontally — used so the left-facing sprite can walk to the right. */
    var flipX = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var targetHeightDp = 140f
        set(value) {
            field = value
            requestLayout()
        }

    private val paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }
    private val dst = RectF()

    private val placeholderPaint = Paint().apply {
        color = Color.argb(180, 90, 140, 200)
        isAntiAlias = true
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (frames.isNotEmpty()) {
                frameIndex = (frameIndex + 1) % frames.size
                invalidate()
            }
            postDelayed(this, frameDurationMs)
        }
    }

    /** Set the current animation: draw [frames] of [sheetBitmap], one every [durationMs]. */
    fun setAnimation(sheetBitmap: Bitmap?, frames: List<Rect>, durationMs: Long) {
        this.bitmap = sheetBitmap
        this.frames = frames
        this.frameDurationMs = durationMs
        this.frameIndex = 0
        boxWidth = frames.maxOfOrNull { it.width() } ?: 1
        boxHeight = frames.maxOfOrNull { it.height() } ?: 1
        removeCallbacks(ticker)
        postDelayed(ticker, frameDurationMs)
        requestLayout()
        invalidate()
    }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics,
        ).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (frames.isEmpty()) {
            val fallback = dpToPx(targetHeightDp)
            setMeasuredDimension(fallback, fallback)
            return
        }
        val height = dpToPx(targetHeightDp)
        val scale = height.toFloat() / boxHeight
        val width = (boxWidth * scale).toInt().coerceAtLeast(1)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap
        if (bmp == null || frames.isEmpty()) {
            val r = (minOf(width, height) / 2f) - 4f
            canvas.drawCircle(width / 2f, height / 2f, r, placeholderPaint)
            return
        }

        val src = frames[frameIndex]
        val scale = height.toFloat() / boxHeight
        val drawW = src.width() * scale
        val drawH = src.height() * scale
        val left = (width - drawW) / 2f      // horizontal center
        val top = height - drawH             // bottom aligned (feet on the floor)
        dst.set(left, top, left + drawW, top + drawH)

        if (flipX) {
            canvas.save()
            canvas.scale(-1f, 1f, width / 2f, 0f)
            canvas.drawBitmap(bmp, src, dst, paint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bmp, src, dst, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        postDelayed(ticker, frameDurationMs)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }
}
