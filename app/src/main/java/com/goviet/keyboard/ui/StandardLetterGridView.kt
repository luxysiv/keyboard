package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import com.goviet.core.density

class StandardLetterGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyGridView(context, attrs, defStyleAttr) {

    var keyboardMode: String = "QWERTY"
        set(value) {
            if (field != value) {
                field = value
                internalKeyboardMode = if (value == "SYMBOLS") "SYM1" else "ABC"
                rebuildKeys()
                recalcCoordinates()
            }
        }

    var shiftState: Int = 0
        set(value) {
            if (field != value) {
                field = value
                KeyboardLayout.resolveLabels(keys, shiftState, languageMode, imeOptions, inputType)
                invalidate()
            }
        }

    var languageMode: String = "VIE"
        set(value) {
            if (field != value) {
                field = value
                KeyboardLayout.resolveLabels(keys, shiftState, languageMode, imeOptions, inputType)
                invalidate()
            }
        }

    var imeOptions: Int = 0
        set(value) {
            if (field != value) {
                field = value
                KeyboardLayout.resolveLabels(keys, shiftState, languageMode, imeOptions, inputType)
                invalidate()
            }
        }

    var inputType: Int = 0
        set(value) {
            if (field != value) {
                field = value
                KeyboardLayout.resolveLabels(keys, shiftState, languageMode, imeOptions, inputType)
                invalidate()
            }
        }

    var onKey: ((String) -> Unit)? = null
    var onSwitchToSymbols: (() -> Unit)? = null
    var onSwitchToEmoji: (() -> Unit)? = null
    var onOpenSettings: (() -> Unit)? = null
    var onToggleLanguage: (() -> Unit)? = null
    var onOpenPopup: ((List<String>) -> Unit)? = null

    private var internalKeyboardMode: String = "ABC"

    private val keys = mutableListOf<Key>()
    private var rows: List<List<Key>> = emptyList()

    private val keyPopup = KeyPopupWindow(context)

    private val touchHandler = KeyTouchHandler(
        keys = { keys },
        keyPopup = keyPopup,
        onKey = { key -> onKey?.invoke(key) },
        onSwitchToSymbols = { onSwitchToSymbols?.invoke() },
        onSwitchPage = { switchPage() },
        invalidate = { invalidate() },
        density = density,
        isDark = { isDark },
        currentTheme = { currentTheme },
        parentWidth = { width },
        parentHeight = { height }
    )

    private val horizontalSpacing = 2.8f * density
    private val verticalSpacing = 7.0f * density

    init {
        touchHandler.attachView(this)
        rebuildKeys()
    }

    private fun rebuildKeys() {
        val (newKeys, _) = KeyboardLayout.buildKeyRows(
            internalKeyboardMode, shiftState, languageMode, imeOptions, inputType
        )
        keys.clear()
        keys.addAll(newKeys)
        rows = KeyboardLayout.getRows(keys, internalKeyboardMode)
    }

    private fun recalcCoordinates() {
        if (width > 0 && height > 0) {
            calculateKeyCoordinates(width, height)
        }
        invalidate()
    }

    private fun switchPage() {
        internalKeyboardMode = if (internalKeyboardMode == "SYM1") "SYM2" else "SYM1"
        rebuildKeys()
        recalcCoordinates()
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateKeyCoordinates(w, h)
    }

    private fun calculateKeyCoordinates(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        val paddingLeft = 4 * density
        val paddingRight = 4 * density
        val paddingTop = 6 * density
        val paddingBottom = 4 * density

        val usableWidth = width - paddingLeft - paddingRight
        val unitRowHeight = KeyboardUtils.calculateStandardRowHeight(height.toFloat(), density, 5, verticalSpacing)

        var currentY = paddingTop
        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            val topOfRow = currentY
            val bottomOfRow = topOfRow + unitRowHeight

            if (internalKeyboardMode == "ABC" && rowIndex == 2) {
                val r2WidthAvailable = usableWidth - (horizontalSpacing * 10)
                val r2UnitWidth = r2WidthAvailable / 9.64f
                val r2SideMargin = 0.32f * r2UnitWidth
                var currentX = paddingLeft + r2SideMargin
                for (key in row) {
                    key.visualRect.set(currentX, topOfRow, currentX + r2UnitWidth, bottomOfRow)
                    key.shadowRect.set(
                        key.visualRect.left, key.visualRect.top + 0.8f * density,
                        key.visualRect.right, key.visualRect.bottom + 1.2f * density
                    )
                    currentX += r2UnitWidth + horizontalSpacing
                }
            } else {
                val totalSpacings = row.size - 1
                val widthAvailable = usableWidth - (horizontalSpacing * totalSpacings)
                val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
                val unitWidth = widthAvailable / totalWeight

                var currentX = paddingLeft
                for (key in row) {
                    val actualWidth = key.weight * unitWidth
                    key.visualRect.set(currentX, topOfRow, currentX + actualWidth, bottomOfRow)
                    key.shadowRect.set(
                        key.visualRect.left, key.visualRect.top + 0.8f * density,
                        key.visualRect.right, key.visualRect.bottom + 1.2f * density
                    )
                    currentX += actualWidth + horizontalSpacing
                }
            }
            currentY += unitRowHeight + verticalSpacing
        }

        // Hit bounds (touch rects) — extend to midpoint between keys
        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]

            val topBound = if (rowIndex == 0) 0f else {
                val prevBottom = rows[rowIndex - 1][0].visualRect.bottom
                val currTop = row[0].visualRect.top
                (prevBottom + currTop) / 2f
            }

            val bottomBound = if (rowIndex == rows.size - 1) height.toFloat() else {
                val currBottom = row[0].visualRect.bottom
                val nextTop = rows[rowIndex + 1][0].visualRect.top
                (currBottom + nextTop) / 2f
            }

            for (i in row.indices) {
                val key = row[i]
                val leftBound = if (i == 0) 0f else {
                    val prevRight = row[i - 1].visualRect.right
                    (prevRight + key.visualRect.left) / 2f
                }
                val rightBound = if (i == row.size - 1) width.toFloat() else {
                    (key.visualRect.right + row[i + 1].visualRect.left) / 2f
                }
                key.rect.set(leftBound, topBound, rightBound, bottomBound)
            }
        }
    }

    // ── Drawing ─────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (key in keys) {
            drawKey(canvas, key)
        }
    }

    private fun drawKey(canvas: Canvas, key: Key) {
        computeScaledRect(
            cx = key.visualRect.centerX(),
            cy = key.visualRect.centerY(),
            w = key.visualRect.width(),
            h = key.visualRect.height(),
            scale = if (key.isPressed) 0.96f else 1.0f
        )
        drawKeyBackgroundScaled(canvas, key)

        textPaint.typeface = boldTypeface
        textPaint.color = textColor
        val isShiftActive = shiftState > 0
        val isSingleChar = key.label.length == 1
        val isEnter = key.code == "ENTER"

        textPaint.textSize = when {
            isEnter && isSingleChar -> 22f * density
            isEnter -> 15f * density
            key.code == "SHIFT" -> 21f * density
            isSingleChar -> 21f * density
            key.isFunctional -> 13f * density
            else -> 16f * density
        }

        when {
            key.code == "SHIFT" -> {
                KeyboardUtils.drawShiftIcon(canvas, drawRect, shiftState, density, textColor)
            }
            isEnter -> {
                KeyboardUtils.drawEnterIcon(canvas, drawRect, imeOptions, inputType, density, textColor)
            }
            key.code == "SPACE" -> {
                val spaceText = if (languageMode == "VIE") "Tiếng Việt" else "English"
                textPaint.textSize = 12.5f * density
                textPaint.color = subTextColor
                textPaint.typeface = normalTypeface
                val baseline = KeyboardUtils.centerBaselineY(drawRect, textPaint)
                canvas.drawText(spaceText, drawRect.centerX(), baseline, textPaint)

                val indicatorW = 36f * density
                val indicatorH = 2.5f * density
                val indicatorY = drawRect.bottom - 7f * density
                val indicatorLeft = drawRect.centerX() - indicatorW / 2f
                shadowDrawRect.set(indicatorLeft, indicatorY - indicatorH, indicatorLeft + indicatorW, indicatorY)
                paint.color = activeAccentColor
                paint.alpha = if (isDark) 90 else 130
                canvas.drawRoundRect(shadowDrawRect, 1.2f * density, 1.2f * density, paint)
            }
            else -> {
                KeyboardUtils.drawKeyLabel(canvas, key.label, drawRect, textPaint, textColor, density)
            }
        }

        val secLabel = key.secondaryLabel
        if (secLabel != null && !isShiftActive && key.code != "SPACE") {
            KeyboardUtils.drawSecondaryLabel(canvas, secLabel, drawRect, textPaint, subTextColor, density)
        }
    }

    // ── Touch ───────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return touchHandler.handleMotionEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        touchHandler.cleanup()
    }
}
