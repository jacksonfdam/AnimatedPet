package com.example.desktoppet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect

/**
 * Loads a sprite sheet from `assets/` and auto-detects each sprite's tight bounding
 * box from the alpha channel.
 *
 * This is deliberately NOT a fixed grid. Hand-drawn / AI-generated sheets have
 * irregular spacing (they don't divide evenly) and their frames drift, so a
 * uniform grid cuts sprites off. Instead we find rows of content separated by
 * gutters, and within each row the individual sprites, then tight-crop each.
 *
 * Two robustness rules matter for these sheets:
 *  - A row/column counts as a GUTTER when its opaque-pixel count is below a small
 *    fraction of the span — not only when it is perfectly empty. Faint anti-alias
 *    halos leave a few stray pixels in every gutter and would otherwise bridge
 *    them into one giant "frame".
 *  - Detected segments far narrower than their siblings are dropped as artifacts
 *    (stray 1-2px pixel columns at a sheet edge).
 */
class SpriteSheet(
    val bitmap: Bitmap,
    /** Detected frames grouped by row, top-to-bottom, left-to-right within a row. */
    val rows: List<List<Rect>>,
) {
    fun row(index: Int): List<Rect> =
        rows.getOrElse(index) { rows.firstOrNull() ?: emptyList() }

    companion object {
        /** Alpha at or below this is treated as transparent. */
        private const val ALPHA_THRESHOLD = 16

        /** A line is a gutter if its opaque-pixel count is <= this fraction of the span. */
        private const val GUTTER_FRACTION = 0.02

        /** Segments narrower/shorter than this fraction of the largest sibling are artifacts. */
        private const val MIN_RELATIVE_SIZE = 0.25

        /**
         * Loads and slices `assets/[assetName]`. Returns null if the file is missing
         * so the app still runs (SpriteView draws a placeholder) before the PNG is added.
         */
        fun load(context: Context, assetName: String = "pet_sheet.png"): SpriteSheet? {
            return try {
                val options = BitmapFactory.Options().apply { inScaled = false }
                val bitmap = context.assets.open(assetName).use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                } ?: return null
                SpriteSheet(bitmap, detectFrames(bitmap))
            } catch (e: Exception) {
                null
            }
        }

        /** Finds tight sprite rectangles by scanning the alpha channel for gutters. */
        private fun detectFrames(bitmap: Bitmap): List<List<Rect>> {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            fun opaque(x: Int, y: Int): Boolean =
                ((pixels[y * w + x] ushr 24) and 0xFF) > ALPHA_THRESHOLD

            // 1) Opaque-pixel count per row; a row is a gutter if it's mostly empty.
            val rowLimit = maxOf(1, (GUTTER_FRACTION * w).toInt())
            val rowEmpty = BooleanArray(h) { y ->
                var count = 0
                var x = 0
                while (x < w) {
                    if (opaque(x, y)) count++
                    x++
                }
                count <= rowLimit
            }
            val bands = segments(rowEmpty).filterBySize()

            // 2) Within each band, split into sprites by gutter columns, then tight-crop.
            val result = ArrayList<List<Rect>>(bands.size)
            for (band in bands) {
                val y0 = band[0]
                val y1 = band[1]
                val span = y1 - y0
                val colLimit = maxOf(1, (GUTTER_FRACTION * span).toInt())
                val colEmpty = BooleanArray(w) { x ->
                    var count = 0
                    var y = y0
                    while (y < y1) {
                        if (opaque(x, y)) count++
                        y++
                    }
                    count <= colLimit
                }
                val cols = segments(colEmpty).filterBySize()

                val frames = ArrayList<Rect>(cols.size)
                for (col in cols) {
                    val x0 = col[0]
                    val x1 = col[1]
                    // Tighten vertical bounds to this sprite specifically.
                    var top = y0
                    while (top < y1 && !rowRangeHasContent(pixels, w, x0, x1, top)) top++
                    var bottom = y1
                    while (bottom > top && !rowRangeHasContent(pixels, w, x0, x1, bottom - 1)) bottom--
                    frames.add(Rect(x0, top, x1, bottom))
                }
                if (frames.isNotEmpty()) result.add(frames)
            }
            return result
        }

        /** Runs of `false` (content) in [empty], as [start, end) index pairs. */
        private fun segments(empty: BooleanArray): List<IntArray> {
            val out = ArrayList<IntArray>()
            var i = 0
            val n = empty.size
            while (i < n) {
                if (!empty[i]) {
                    val start = i
                    while (i < n && !empty[i]) i++
                    out.add(intArrayOf(start, i))
                } else {
                    i++
                }
            }
            return out
        }

        /** Drops segments far smaller than the largest — stray-pixel artifacts. */
        private fun List<IntArray>.filterBySize(): List<IntArray> {
            if (isEmpty()) return this
            val max = maxOf { it[1] - it[0] }
            val minSize = MIN_RELATIVE_SIZE * max
            return filter { (it[1] - it[0]) >= minSize }
        }

        private fun rowRangeHasContent(
            pixels: IntArray,
            w: Int,
            x0: Int,
            x1: Int,
            y: Int,
        ): Boolean {
            var x = x0
            while (x < x1) {
                if (((pixels[y * w + x] ushr 24) and 0xFF) > ALPHA_THRESHOLD) return true
                x++
            }
            return false
        }
    }
}

/**
 * Row convention shared by every sheet in this project (5 frames each):
 *   0 — facing the viewer (used when walking DOWN)
 *   1 — facing away       (used when walking UP)
 *   2 — side profile facing LEFT (used for LEFT; mirrored for RIGHT)
 *   3 — extra side/action row
 *
 * The service picks the row from the pet's direction of travel (see applyFacing).
 */
object PetAnimations {
    const val ROW_FRONT = 0
    const val ROW_BACK = 1
    const val ROW_SIDE = 2
    const val ROW_EXTRA = 3

    const val WALK_FRAME_MS = 120L

    /**
     * Picks the row that matches a pet's direction of travel and whether the sprite
     * must be mirrored. The side row faces LEFT, so travelling right returns flip=true.
     */
    fun rowForVelocity(vx: Float, vy: Float): Pair<Int, Boolean> =
        if (kotlin.math.abs(vx) >= kotlin.math.abs(vy)) {
            ROW_SIDE to (vx > 0f)
        } else if (vy > 0f) {
            ROW_FRONT to false
        } else {
            ROW_BACK to false
        }
}
