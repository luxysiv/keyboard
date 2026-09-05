package com.goviet.keyboard.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

object KeyRenderer {
    // Reusable Paint and RectF to avoid allocations in onDraw (UI Thread only)
    private val paint = Paint().apply {
        isAntiAlias = true
    }

    val emptyRect = RectF()

    fun drawStandardKey(
        canvas: Canvas,
        drawRect: RectF,
        shadowRect: RectF,
        cornerRadius: Float,
        density: Float,
        isDark: Boolean,
        keyStyle: Int,
        isPressed: Boolean,
        isFunctional: Boolean,
        isSpecialEnter: Boolean,
        bgColor: Int,
        pressedBgColor: Int,
        forceDrawBg: Boolean = false
    ) {
        val bgPaintColor = if (isPressed) pressedBgColor else bgColor

        // Draw shadow / border (for keyStyle == 0)
        if (!isPressed && keyStyle == 0 && !shadowRect.isEmpty) {
            paint.color = if (isDark) 0x33000000 else 0x1A000000
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, paint)
        }

        val shouldDrawBg = isPressed || (keyStyle == 0 || keyStyle == 1) || isSpecialEnter || forceDrawBg
        if (shouldDrawBg) {
            paint.color = bgPaintColor
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, paint)
        }

        // Draw outline border for unpressed normal keys in light mode
        if (keyStyle == 0 && !isDark && !isPressed && !isFunctional && !isSpecialEnter) {
            paint.color = 0x0D000000
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, paint)
        }
    }

    fun drawFlatRoundedRect(
        canvas: Canvas,
        rect: RectF,
        cornerRadius: Float,
        color: Int,
        style: Paint.Style = Paint.Style.FILL,
        strokeWidth: Float = 0f
    ) {
        paint.color = color
        paint.style = style
        if (style == Paint.Style.STROKE) {
            paint.strokeWidth = strokeWidth
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
    }
}
