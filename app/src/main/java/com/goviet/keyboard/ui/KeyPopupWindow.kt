package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow

import com.goviet.core.density

class KeyPopupWindow(private val context: Context) {
    private val popupWindow = PopupWindow(context).apply {
        setBackgroundDrawable(null)
        isOutsideTouchable = true
        isFocusable = false
        isClippingEnabled = false
        animationStyle = 0
    }

    private val popupView = PopupView(context)

    init {
        popupWindow.contentView = popupView
    }

    enum class Mode {
        PREVIEW,
        LONG_PRESS
    }

    private var currentMode: Mode = Mode.PREVIEW

    private data class PopupPosition(val x: Int, val y: Int, val width: Int, val height: Int)

    private fun computePosition(anchorView: View, keyRect: RectF?, widthDp: Int): PopupPosition {
        val density = context.density
        val width = (widthDp * density).toInt()
        val height = (72 * density).toInt()

        val location = IntArray(2)
        anchorView.getLocationInWindow(location)

        val keyCenterX = if (keyRect != null) {
            location[0] + keyRect.centerX()
        } else {
            location[0] + anchorView.width / 2f
        }
        val idealLeft = keyCenterX - width / 2f
        val screenWidth = context.resources.displayMetrics.widthPixels
        val margin = (8 * density).toInt()

        val left = idealLeft.coerceIn(margin.toFloat(), (screenWidth - margin - width).toFloat())

        val x = left.toInt()
        val y = if (keyRect != null) {
            (location[1] + keyRect.top - height - 4f * density).toInt()
        } else {
            location[1] - (70 * density).toInt()
        }

        return PopupPosition(x, y, width, height)
    }

    fun showPreview(
        anchorView: View,
        label: String,
        isDark: Boolean,
        theme: KeyboardTheme,
        keyRect: RectF? = null
    ) {
        currentMode = Mode.PREVIEW
        popupView.setPreviewData(label, isDark, theme)

        val (x, y, width, height) = computePosition(anchorView, keyRect, 66)

        popupWindow.width = width
        popupWindow.height = height

        if (popupWindow.isShowing) {
            popupWindow.update(x, y, width, height)
        } else {
            popupWindow.showAtLocation(anchorView.rootView, Gravity.NO_GRAVITY, x, y)
        }
    }

    fun showLongPress(
        anchorView: View,
        options: List<String>,
        hoveredIdx: Int,
        isDark: Boolean,
        theme: KeyboardTheme,
        keyRect: RectF? = null
    ) {
        currentMode = Mode.LONG_PRESS
        popupView.setLongPressData(options, hoveredIdx, isDark, theme)

        val (x, y, width, height) = computePosition(anchorView, keyRect,
            if (options.size <= 1) 66 else 44 * options.size)

        popupWindow.width = width
        popupWindow.height = height

        if (popupWindow.isShowing) {
            popupWindow.update(x, y, width, height)
        } else {
            popupWindow.showAtLocation(anchorView.rootView, Gravity.NO_GRAVITY, x, y)
        }
    }

    fun updateHoverIndex(index: Int) {
        if (currentMode == Mode.LONG_PRESS) {
            popupView.updateHoverIndex(index)
        }
    }

    fun dismiss() {
        if (popupWindow.isShowing) {
            popupWindow.dismiss()
        }
    }

    private class PopupView(context: Context) : View(context) {
        private var mode: Mode = Mode.PREVIEW
        private var label: String = ""
        private var isDark: Boolean = false
        private lateinit var theme: KeyboardTheme

        private var options: List<String> = emptyList()
        private var hoveredIdx: Int = -1

        private val density get() = context.density
        private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = boldTypeface
        }

        // Preallocated RectFs to avoid any allocation in onDraw
        private val mainRect = RectF()
        private val shadowRect1 = RectF()
        private val shadowRect2 = RectF()
        private val itemHighlightRect = RectF()

        fun setPreviewData(label: String, isDark: Boolean, theme: KeyboardTheme) {
            this.mode = Mode.PREVIEW
            this.label = label
            this.isDark = isDark
            this.theme = theme
            invalidate()
        }

        fun setLongPressData(options: List<String>, hoveredIdx: Int, isDark: Boolean, theme: KeyboardTheme) {
            this.mode = Mode.LONG_PRESS
            this.options = options
            this.hoveredIdx = hoveredIdx
            this.isDark = isDark
            this.theme = theme
            invalidate()
        }

        fun updateHoverIndex(index: Int) {
            if (this.hoveredIdx != index) {
                this.hoveredIdx = index
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!::theme.isInitialized) return

            val w = width.toFloat()
            val h = height.toFloat()

            val shadowPadding = 4f * density
            mainRect.set(shadowPadding, shadowPadding, w - shadowPadding, h - shadowPadding - 2f * density)

            shadowRect1.set(mainRect.left, mainRect.top + 3f * density, mainRect.right, mainRect.bottom + 3f * density)
            KeyRenderer.drawFlatRoundedRect(
                canvas = canvas,
                rect = shadowRect1,
                cornerRadius = 12f * density,
                color = if (isDark) 0x24000000 else 0x0F000000,
                style = Paint.Style.FILL
            )

            shadowRect2.set(mainRect.left, mainRect.top + 1.5f * density, mainRect.right, mainRect.bottom + 1.5f * density)
            KeyRenderer.drawFlatRoundedRect(
                canvas = canvas,
                rect = shadowRect2,
                cornerRadius = 12f * density,
                color = if (isDark) 0x3D000000 else 0x1A000000,
                style = Paint.Style.FILL
            )

            val surfaceVariant = theme.keyBgColor
            KeyRenderer.drawFlatRoundedRect(
                canvas = canvas,
                rect = mainRect,
                cornerRadius = 12f * density,
                color = surfaceVariant,
                style = Paint.Style.FILL
            )

            val borderColor = (theme.textColor and 0x00FFFFFF) or (0x1C shl 24)
            KeyRenderer.drawFlatRoundedRect(
                canvas = canvas,
                rect = mainRect,
                cornerRadius = 12f * density,
                color = borderColor,
                style = Paint.Style.STROKE,
                strokeWidth = 1f * density
            )

            if (mode == Mode.PREVIEW || (mode == Mode.LONG_PRESS && options.size <= 1)) {
                val displayText = if (mode == Mode.PREVIEW) label else (options.firstOrNull() ?: "")
                textPaint.color = theme.textColor
                textPaint.textSize = 28f * density
                textPaint.typeface = boldTypeface

                val baseline = KeyboardUtils.centerBaselineY(mainRect, textPaint)
                canvas.drawText(displayText, w / 2f, baseline, textPaint)
            } else {
                if (options.isEmpty()) return
                val contentWidth = mainRect.width()
                val optionWidth = contentWidth / options.size
                val itemTop = mainRect.top + 3f * density
                val itemBottom = mainRect.bottom - 3f * density

                for (idx in options.indices) {
                    val optChar = options[idx]
                    val isHovered = (idx == hoveredIdx)

                    val itemLeft = mainRect.left + idx * optionWidth
                    val itemRight = itemLeft + optionWidth

                    if (isHovered) {
                        val highlightPadding = 2f * density
                        itemHighlightRect.set(
                            itemLeft + highlightPadding,
                            itemTop,
                            itemRight - highlightPadding,
                            itemBottom
                        )
                        KeyRenderer.drawFlatRoundedRect(
                            canvas = canvas,
                            rect = itemHighlightRect,
                            cornerRadius = 8f * density,
                            color = theme.activeAccentColor,
                            style = Paint.Style.FILL
                        )
                    }

                    val textOnPrimary = getContrastColor(theme.activeAccentColor)
                    textPaint.color = if (isHovered) textOnPrimary else theme.textColor
                    textPaint.textSize = 18f * density
                    textPaint.typeface = boldTypeface

                    val baseline = KeyboardUtils.centerBaselineY(mainRect, textPaint)
                    canvas.drawText(optChar, itemLeft + optionWidth / 2f, baseline, textPaint)
                }
            }
        }

        private fun getContrastColor(color: Int): Int {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val yiq = (r * 299 + g * 587 + b * 114) / 1000
            return if (yiq >= 128) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
}
