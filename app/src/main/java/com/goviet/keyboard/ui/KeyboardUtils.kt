package com.goviet.keyboard.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.inputmethod.EditorInfo
import android.text.InputType

object KeyboardUtils {
    private fun isMultiLineOrNoEnterAction(imeOptions: Int, inputType: Int): Boolean {
        val isMultiLine = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                ((inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
                 (inputType and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0)
        return isMultiLine || (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
    }

    private fun enterActionCode(imeOptions: Int, inputType: Int): Int =
        if (isMultiLineOrNoEnterAction(imeOptions, inputType)) -1 else imeOptions and EditorInfo.IME_MASK_ACTION
    // Reusable objects to prevent garbage collection allocations in onDraw
    private val paint = Paint().apply {
        isAntiAlias = true
    }
    private val path = Path()
    private val rectF = RectF()

    fun calculateStandardRowHeight(
        totalHeight: Float,
        density: Float,
        rowCount: Int = 4,
        verticalSpacing: Float = 11.5f * density
    ): Float {
        val paddingTop = 6f * density
        val paddingBottom = 4f * density
        val usableHeight = totalHeight - paddingTop - paddingBottom - (verticalSpacing * (rowCount - 1))
        return usableHeight / rowCount
    }

    fun getEnterTextLabel(imeOptions: Int, inputType: Int): String {
        return enterActionLabel(imeOptions, inputType, "Enter", "Go", "Search", "Send", "Next", "Done")
    }

    fun getEnterSymbolLabel(imeOptions: Int, inputType: Int): String {
        return enterActionLabel(imeOptions, inputType, "↵", "➔", "🔍", "➤", "➔", "✓")
    }

    private fun enterActionLabel(
        imeOptions: Int,
        inputType: Int,
        defaultLabel: String,
        goLabel: String,
        searchLabel: String,
        sendLabel: String,
        nextLabel: String,
        doneLabel: String
    ): String {
        if (isMultiLineOrNoEnterAction(imeOptions, inputType)) {
            return defaultLabel
        }
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        return when (action) {
            EditorInfo.IME_ACTION_GO -> goLabel
            EditorInfo.IME_ACTION_SEARCH -> searchLabel
            EditorInfo.IME_ACTION_SEND -> sendLabel
            EditorInfo.IME_ACTION_NEXT -> nextLabel
            EditorInfo.IME_ACTION_DONE -> doneLabel
            else -> defaultLabel
        }
    }

    fun drawShiftIcon(canvas: Canvas, rect: RectF, shiftState: Int, density: Float, color: Int) {
        val cx = rect.centerX()
        val cy = rect.centerY()

        paint.reset()
        paint.isAntiAlias = true
        paint.color = color

        path.reset()

        if (shiftState == 2) { // Caps Lock
            val top = cy - 8f * density
            val bottom = cy + 2f * density
            val middle = cy - 3f * density
            val stemLeft = cx - 3.5f * density
            val stemRight = cx + 3.5f * density
            val triLeft = cx - 8f * density
            val triRight = cx + 8f * density

            path.moveTo(cx, top)
            path.lineTo(triRight, middle)
            path.lineTo(stemRight, middle)
            path.lineTo(stemRight, bottom)
            path.lineTo(stemLeft, bottom)
            path.lineTo(stemLeft, middle)
            path.lineTo(triLeft, middle)
            path.close()

            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)

            // Draw a rounded horizontal bar underneath
            val barTop = cy + 5.5f * density
            val barBottom = cy + 8f * density
            val barLeft = cx - 8f * density
            val barRight = cx + 8f * density
            
            rectF.set(barLeft, barTop, barRight, barBottom)
            canvas.drawRoundRect(rectF, 1.5f * density, 1.5f * density, paint)

        } else {
            // Normal shift (0: lowercase, 1: active)
            val top = cy - 8f * density
            val bottom = cy + 8f * density
            val middle = cy + 0f * density
            val stemLeft = cx - 3.5f * density
            val stemRight = cx + 3.5f * density
            val triLeft = cx - 8f * density
            val triRight = cx + 8f * density

            path.moveTo(cx, top)
            path.lineTo(triRight, middle)
            path.lineTo(stemRight, middle)
            path.lineTo(stemRight, bottom)
            path.lineTo(stemLeft, bottom)
            path.lineTo(stemLeft, middle)
            path.lineTo(triLeft, middle)
            path.close()

            if (shiftState == 1) { // Active Shift
                paint.style = Paint.Style.FILL
                canvas.drawPath(path, paint)
            } else { // Inactive Shift (outlined)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * density
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(path, paint)
            }
        }
    }

    fun drawEnterIcon(
        canvas: Canvas,
        rect: RectF,
        imeOptions: Int,
        inputType: Int,
        density: Float,
        color: Int
    ) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        
        paint.reset()
        paint.isAntiAlias = true
        paint.color = color
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        val action = enterActionCode(imeOptions, inputType)

        when (action) {
            EditorInfo.IME_ACTION_SEARCH -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.2f * density
                canvas.drawCircle(cx - 2f * density, cy - 2f * density, 4.5f * density, paint)
                canvas.drawLine(cx + 1.2f * density, cy + 1.2f * density, cx + 7f * density, cy + 7f * density, paint)
            }
            EditorInfo.IME_ACTION_SEND -> {
                path.reset()
                path.moveTo(cx + 8f * density, cy)
                path.lineTo(cx - 7f * density, cy - 7f * density)
                path.lineTo(cx - 2.5f * density, cy)
                path.lineTo(cx - 7f * density, cy + 7f * density)
                path.close()
                paint.style = Paint.Style.FILL
                canvas.drawPath(path, paint)
            }
            EditorInfo.IME_ACTION_DONE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f * density
                path.reset()
                path.moveTo(cx - 6f * density, cy + 0.5f * density)
                path.lineTo(cx - 1.5f * density, cy + 4.5f * density)
                path.lineTo(cx + 6f * density, cy - 4.5f * density)
                canvas.drawPath(path, paint)
            }
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.2f * density
                canvas.drawLine(cx - 7f * density, cy, cx + 7f * density, cy, paint)
                canvas.drawLine(cx + 2f * density, cy - 5f * density, cx + 7f * density, cy, paint)
                canvas.drawLine(cx + 2f * density, cy + 5f * density, cx + 7f * density, cy, paint)
            }
            else -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.2f * density
                canvas.drawLine(cx + 5f * density, cy - 5f * density, cx + 5f * density, cy + 2f * density, paint)
                canvas.drawLine(cx + 5f * density, cy + 2f * density, cx - 6f * density, cy + 2f * density, paint)
                canvas.drawLine(cx - 2f * density, cy - 2f * density, cx - 6f * density, cy + 2f * density, paint)
                canvas.drawLine(cx - 2f * density, cy + 6f * density, cx - 6f * density, cy + 2f * density, paint)
            }
        }
    }

    /**
     * Calculate baseline Y for vertically centered text in a RectF.
     */
    fun centerBaselineY(rect: RectF, paint: Paint): Float {
        return rect.centerY() - (paint.descent() + paint.ascent()) / 2f
    }

    fun centerBaselineY(centerY: Float, paint: Paint): Float {
        return centerY - (paint.descent() + paint.ascent()) / 2f
    }

    /**
     * Draw a secondary label (corner symbol) on a key.
     * Uses font-metrics centering so characters with unusual ascenders
     * (like backtick) render at the correct position.
     */
    fun drawSecondaryLabel(
        canvas: Canvas,
        label: String,
        drawRect: RectF,
        textPaint: Paint,
        textColor: Int,
        density: Float
    ) {
        textPaint.textSize = 9f * density
        textPaint.color = textColor
        val secX = drawRect.right - 5f * density
        val secWidth = textPaint.measureText(label)
        val secCenterX = secX - secWidth / 2f
        val secY = centerBaselineY(drawRect.top + 9f * density, textPaint)
        canvas.drawText(label, secCenterX, secY, textPaint)
    }

    /**
     * Draw a key label centered in drawRect.
     * Automatically sizes based on key type (single char, functional, etc.)
     */
    fun drawKeyLabel(
        canvas: Canvas,
        label: String,
        drawRect: RectF,
        textPaint: Paint,
        textColor: Int,
        density: Float,
        isFunctional: Boolean = false
    ) {
        textPaint.color = textColor
        textPaint.textSize = when {
            label.length == 1 -> 21f * density
            isFunctional -> 13f * density
            else -> 16f * density
        }
        canvas.drawText(label, drawRect.centerX(), centerBaselineY(drawRect, textPaint), textPaint)
    }
}
