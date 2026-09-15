package com.example.vrsbsplayer.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Builds a single Full-SBS style bitmap (left half + right half) used by the
 * calibration screen so the same distortion/eye-split pipeline used for real
 * video can be exercised with a known-good synthetic image.
 *
 * The left and right halves are IDENTICAL except:
 *  - the L/R label text (so mis-routed eyes are obvious)
 *  - small depth-marker squares drawn with a slight horizontal offset between
 *    halves, at several "depths" (offsets), so the user can visually judge
 *    whether stereo separation/convergence looks natural once fused.
 */
object TestPatternGenerator {

    fun generate(eyeWidth: Int = 1000, eyeHeight: Int = 1000): Bitmap {
        val bmp = Bitmap.createBitmap(eyeWidth * 2, eyeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)

        drawEye(canvas, offsetX = 0, w = eyeWidth, h = eyeHeight, label = "L", depthShiftPx = -6)
        drawEye(canvas, offsetX = eyeWidth, w = eyeWidth, h = eyeHeight, label = "R", depthShiftPx = 6)

        return bmp
    }

    private fun drawEye(canvas: Canvas, offsetX: Int, w: Int, h: Int, label: String, depthShiftPx: Int) {
        val gridPaint = Paint().apply { color = Color.rgb(0, 140, 0); strokeWidth = 2f }
        val majorPaint = Paint().apply { color = Color.rgb(0, 220, 0); strokeWidth = 4f }
        val circlePaint = Paint().apply { color = Color.rgb(255, 165, 0); style = Paint.Style.STROKE; strokeWidth = 4f }
        val crosshairPaint = Paint().apply { color = Color.WHITE; strokeWidth = 5f }
        val textPaint = Paint().apply { color = Color.CYAN; textSize = h / 6f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        val depthPaint = Paint().apply { color = Color.MAGENTA }
        val borderPaint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 6f }

        val cx = offsetX + w / 2f
        val cy = h / 2f

        // Grid lines every 10% + major line every 50%
        for (i in 0..10) {
            val x = offsetX + w * i / 10f
            canvas.drawLine(x, 0f, x, h.toFloat(), if (i % 5 == 0) majorPaint else gridPaint)
        }
        for (j in 0..10) {
            val y = h * j / 10f
            canvas.drawLine(offsetX.toFloat(), y, (offsetX + w).toFloat(), y, if (j % 5 == 0) majorPaint else gridPaint)
        }

        // Concentric circles (useful for verifying radial distortion correction)
        val maxR = minOf(w, h) / 2f
        var r = maxR
        while (r > 0) {
            canvas.drawCircle(cx, cy, r, circlePaint)
            r -= maxR / 5f
        }

        // Crosshair
        canvas.drawLine(cx - 60, cy, cx + 60, cy, crosshairPaint)
        canvas.drawLine(cx, cy - 60, cx, cy + 60, crosshairPaint)

        // Border, to check FOV edges are visible / not over-cropped
        canvas.drawRect(offsetX + 4f, 4f, offsetX + w - 4f, h - 4f, borderPaint)

        // Eye label
        canvas.drawText(label, cx, cy - maxR * 0.5f, textPaint)

        // Depth markers: 3 squares at different simulated depths (different
        // horizontal shift between L/R), stacked vertically below center.
        val sizes = floatArrayOf(40f, 30f, 20f)
        for ((idx, size) in sizes.withIndex()) {
            val markerY = cy + maxR * 0.35f + idx * (size + 20f)
            val shift = depthShiftPx * (idx + 1) / 3f
            canvas.drawRect(cx + shift - size / 2f, markerY - size / 2f, cx + shift + size / 2f, markerY + size / 2f, depthPaint)
        }
    }
}
