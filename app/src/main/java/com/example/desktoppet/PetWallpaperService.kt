package com.example.desktoppet

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.TypedValue
import android.view.SurfaceHolder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Live wallpaper that renders the selected pets roaming the screen, so they show on
 * both the home screen and the lock screen. It reuses [SpriteSheet] for frame
 * detection and [PetAnimations.rowForVelocity] for facing — the same engine as the
 * overlay, drawn straight onto the wallpaper [Canvas].
 *
 * The set of pets mirrors the app's selection ([PetPrefs]); toggling pets in the app
 * updates the wallpaper live via a shared-prefs listener.
 */
class PetWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = PetEngine()

    private inner class PetEngine : WallpaperService.Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private val pets = ArrayList<Pet>()
        private val sheetCache = HashMap<String, SpriteSheet?>()

        private var width = 0
        private var height = 0
        private var visible = false

        private val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
            isDither = false
        }
        private val dst = RectF()
        private var background: Paint = Paint().apply { color = Color.parseColor("#12121A") }

        private val prefListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == PetPrefs.KEY_SELECTED) reloadPets()
            }

        private val frameRunnable = Runnable { frame() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            PetPrefs.prefs(this@PetWallpaperService)
                .registerOnSharedPreferenceChangeListener(prefListener)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            background = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    Color.parseColor("#1B1030"), Color.parseColor("#0C0C12"),
                    Shader.TileMode.CLAMP,
                )
            }
            reloadPets()
            if (visible) scheduleFrame()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                scheduleFrame()
            } else {
                handler.removeCallbacks(frameRunnable)
            }
        }

        override fun onDestroy() {
            handler.removeCallbacks(frameRunnable)
            PetPrefs.prefs(this@PetWallpaperService)
                .unregisterOnSharedPreferenceChangeListener(prefListener)
            super.onDestroy()
        }

        private fun scheduleFrame() {
            handler.removeCallbacks(frameRunnable)
            handler.postDelayed(frameRunnable, FRAME_MS)
        }

        private fun frame() {
            update()
            draw()
            if (visible) scheduleFrame()
        }

        private fun reloadPets() {
            pets.clear()
            if (width == 0 || height == 0) return
            for (id in PetPrefs.selected(this@PetWallpaperService)) {
                val def = PetCatalog.byId(this@PetWallpaperService, id) ?: continue
                val sheet = sheetCache.getOrPut(def.assetName) {
                    SpriteSheet.load(this@PetWallpaperService, def.assetName)
                } ?: continue
                if (sheet.rows.isEmpty()) continue
                pets.add(newPet(sheet))
            }
        }

        private fun newPet(sheet: SpriteSheet): Pet {
            val targetHeight = dpToPx(Random.nextInt(96, 140).toFloat()).toFloat()
            val speed = dpToPx(Random.nextDouble(0.9, 2.6).toFloat()).toFloat().coerceAtLeast(1.5f)
            val angle = Random.nextDouble(0.0, 2.0 * Math.PI)
            val frames = sheet.row(PetAnimations.ROW_SIDE)
            return Pet(
                sheet = sheet,
                frames = frames,
                boxW = frames.maxOf { it.width() },
                boxH = frames.maxOf { it.height() },
                fx = Random.nextFloat() * (width * 0.8f),
                fy = Random.nextFloat() * (height * 0.7f) + height * 0.1f,
                vx = (cos(angle) * speed).toFloat(),
                vy = (sin(angle) * speed).toFloat(),
                targetHeight = targetHeight,
                frameMs = Random.nextLong(90L, 160L),
            )
        }

        private fun update() {
            val now = SystemClock.uptimeMillis()
            for (pet in pets) {
                if (--pet.ticksUntilTurn <= 0) {
                    val speed = hypot(pet.vx.toDouble(), pet.vy.toDouble()).toFloat().coerceAtLeast(1.5f)
                    val angle = Random.nextDouble(0.0, 2.0 * Math.PI)
                    pet.vx = (cos(angle) * speed).toFloat()
                    pet.vy = (sin(angle) * speed).toFloat()
                    pet.ticksUntilTurn = Random.nextInt(120, 400)
                }

                pet.fx += pet.vx
                pet.fy += pet.vy

                val scale = pet.targetHeight / pet.boxH
                val boxWpx = pet.boxW * scale
                val maxX = (width - boxWpx).coerceAtLeast(0f)
                val maxY = (height - pet.targetHeight).coerceAtLeast(0f)
                if (pet.fx <= 0f) { pet.fx = 0f; pet.vx = abs(pet.vx) }
                if (pet.fx >= maxX) { pet.fx = maxX; pet.vx = -abs(pet.vx) }
                if (pet.fy <= 0f) { pet.fy = 0f; pet.vy = abs(pet.vy) }
                if (pet.fy >= maxY) { pet.fy = maxY; pet.vy = -abs(pet.vy) }

                val (row, flip) = PetAnimations.rowForVelocity(pet.vx, pet.vy)
                pet.flip = flip
                if (row != pet.currentRow) {
                    pet.currentRow = row
                    pet.frames = pet.sheet.row(row)
                    pet.boxW = pet.frames.maxOf { it.width() }
                    pet.boxH = pet.frames.maxOf { it.height() }
                    pet.frameIndex = 0
                }

                if (now - pet.lastFrameAt >= pet.frameMs) {
                    pet.frameIndex = (pet.frameIndex + 1) % pet.frames.size
                    pet.lastFrameAt = now
                }
            }
        }

        private fun draw() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas() ?: return
                canvas.drawPaint(background)
                for (pet in pets) {
                    val src = pet.frames.getOrNull(pet.frameIndex) ?: continue
                    val scale = pet.targetHeight / pet.boxH
                    val drawW = src.width() * scale
                    val drawH = src.height() * scale
                    val boxWpx = pet.boxW * scale
                    val left = pet.fx + (boxWpx - drawW) / 2f
                    val top = pet.fy + (pet.targetHeight - drawH)
                    dst.set(left, top, left + drawW, top + drawH)
                    if (pet.flip) {
                        canvas.save()
                        canvas.scale(-1f, 1f, pet.fx + boxWpx / 2f, 0f)
                        canvas.drawBitmap(pet.sheet.bitmap, src, dst, paint)
                        canvas.restore()
                    } else {
                        canvas.drawBitmap(pet.sheet.bitmap, src, dst, paint)
                    }
                }
            } finally {
                if (canvas != null) runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }

        private fun dpToPx(dp: Float): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics,
            ).toInt()
    }

    /** Per-pet runtime state for the wallpaper. */
    private class Pet(
        val sheet: SpriteSheet,
        var frames: List<Rect>,
        var boxW: Int,
        var boxH: Int,
        var fx: Float,
        var fy: Float,
        var vx: Float,
        var vy: Float,
        val targetHeight: Float,
        val frameMs: Long,
    ) {
        var currentRow = PetAnimations.ROW_SIDE
        var flip = false
        var frameIndex = 0
        var lastFrameAt = 0L
        var ticksUntilTurn = Random.nextInt(120, 400)
    }

    companion object {
        private const val FRAME_MS = 16L
    }
}
