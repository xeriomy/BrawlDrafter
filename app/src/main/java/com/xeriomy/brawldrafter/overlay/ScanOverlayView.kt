package com.xeriomy.brawldrafter.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Custom view that draws a high-tech scanning animation overlay.
 * Features:
 * - Green bordered scan frame with bright corner brackets
 * - Animated horizontal scan line sweeping top to bottom
 * - Faint grid lines for "tech" aesthetic
 * - Glow effect trailing the scan line
 */
class ScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 0.5f * density
        alpha = 20
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        alpha = 70
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val scanLinePaint = Paint().apply {
        color = Color.parseColor("#00E676")
    }

    private val pad = 6f * density
    private val cornerLen = 28f * density
    private val gridSpacing = 18f * density

    /** 0.0 = top of frame, 1.0 = bottom of frame */
    var scanProgress: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private var glowGradient: LinearGradient? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Recreate gradient for glow effect
        glowGradient = LinearGradient(
            0f, -50f * density, 0f, 0f,
            Color.TRANSPARENT, Color.parseColor("#00E676"), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = pad
        val top = pad
        val right = width.toFloat() - pad
        val bottom = height.toFloat() - pad
        val frameW = right - left
        val frameH = bottom - top

        if (frameW <= 0 || frameH <= 0) return

        // 1. Grid lines (faint horizontal)
        var y = top + gridSpacing
        while (y < bottom) {
            canvas.drawLine(left, y, right, y, gridPaint)
            y += gridSpacing
        }
        // Faint vertical grid lines
        var x = left + gridSpacing
        while (x < right) {
            canvas.drawLine(x, top, x, bottom, gridPaint)
            x += gridSpacing
        }

        // 2. Main border (faint)
        canvas.drawRect(left, top, right, bottom, borderPaint)

        // 3. Corner brackets (bright)
        cornerPaint.alpha = 255
        // Top-left
        canvas.drawLine(left, top, left + cornerLen, top, cornerPaint)
        canvas.drawLine(left, top, left, top + cornerLen, cornerPaint)
        // Top-right
        canvas.drawLine(right - cornerLen, top, right, top, cornerPaint)
        canvas.drawLine(right, top, right, top + cornerLen, cornerPaint)
        // Bottom-left
        canvas.drawLine(left, bottom, left + cornerLen, bottom, cornerPaint)
        canvas.drawLine(left, bottom - cornerLen, left, bottom, cornerPaint)
        // Bottom-right
        canvas.drawLine(right - cornerLen, bottom, right, bottom, cornerPaint)
        canvas.drawLine(right, bottom - cornerLen, right, bottom, cornerPaint)

        // 4. Scan line with glow
        val lineY = top + scanProgress * frameH

        // Glow trailing above the scan line
        val glow = glowGradient
        if (glow != null) {
            val glowPaint = Paint().apply {
                shader = LinearGradient(
                    0f, lineY - 60f * density, 0f, lineY,
                    Color.TRANSPARENT, Color.parseColor("#00E676"), Shader.TileMode.CLAMP
                )
                alpha = 40
            }
            canvas.save()
            canvas.clipRect(left, top, right, lineY)
            canvas.drawRect(left, lineY - 60f * density, right, lineY, glowPaint)
            canvas.restore()
        }

        // Main scan line (2dp bright green)
        scanLinePaint.alpha = 230
        val lineHeight = 2f * density
        canvas.drawRect(left, lineY - lineHeight / 2, right, lineY + lineHeight / 2, scanLinePaint)

        // Bright dot at the leading edge (center)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            alpha = 200
        }
        canvas.drawCircle((left + right) / 2, lineY, 3f * density, dotPaint)
    }
}