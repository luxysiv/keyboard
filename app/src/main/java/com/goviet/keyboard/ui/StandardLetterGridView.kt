package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import com.goviet.core.density
import com.goviet.core.AppPreferences

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
                useVietSpace = (value == "VIE")
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

    var isLandscape: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                rebuildKeys()
                recalcCoordinates()
            }
        }

    var landscapeMode: String = AppPreferences.LANDSCAPE_SPLIT
        set(value) {
            if (field != value) {
                field = value
                rebuildKeys()
                recalcCoordinates()
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

    // Pre-allocated CharArrays for space key — zero-GC on onDraw
    private val spaceVietChars = "Tiếng Việt".toCharArray()
    private val spaceEngChars = "English".toCharArray()
    private var useVietSpace = true  // sync with languageMode changes

    private val horizontalSpacing = 2.8f * density
    private val verticalSpacing = 7.0f * density

    init {
        touchHandler.attachView(this)
        rebuildKeys()
    }

    private fun rebuildKeys() {
        val isSplit = isLandscape && (landscapeMode == AppPreferences.LANDSCAPE_SPLIT)
        val (newKeys, _) = KeyboardLayout.buildKeyRows(
            internalKeyboardMode, shiftState, languageMode, imeOptions, inputType, isSplit
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

        val isSplit = isLandscape && (landscapeMode == AppPreferences.LANDSCAPE_SPLIT)
        val isCompact = isLandscape && (landscapeMode == AppPreferences.LANDSCAPE_COMPACT)

        val baseSidePadding = 4f * density
        val paddingTop = if (isLandscape) 3f * density else 6f * density
        val paddingBottom = if (isLandscape) 2f * density else 4f * density
        val rowSpacing = if (isLandscape) 4.5f * density else verticalSpacing
        val unitRowHeight = ((height - paddingTop - paddingBottom - (rowSpacing * (rows.size - 1))) / rows.size.toFloat()).coerceAtLeast(20f * density)

        if (isSplit) {
            // ── Split Mode Layout (Ergonomic thumb reach with center gap) ──
            val paddingLeft = 6f * density
            val paddingRight = 6f * density
            val usableWidth = width - paddingLeft - paddingRight

            val centerGap = (usableWidth * 0.22f).coerceIn(80f * density, 220f * density)
            val clusterWidth = (usableWidth - centerGap) / 2f
            val leftStart = paddingLeft
            val rightStart = width - paddingRight - clusterWidth

            var currentY = paddingTop
            for (rowIndex in rows.indices) {
                val row = rows[rowIndex]
                val topOfRow = currentY
                val bottomOfRow = topOfRow + unitRowHeight

                val splitIndex = when (rowIndex) {
                    0, 1 -> 5
                    2 -> 5
                    3 -> 5
                    4 -> 3
                    else -> row.size / 2
                }

                val leftKeys = row.subList(0, splitIndex.coerceAtMost(row.size))
                val rightKeys = row.subList(splitIndex.coerceAtMost(row.size), row.size)

                layoutCluster(leftKeys, leftStart, clusterWidth, topOfRow, bottomOfRow)
                layoutCluster(rightKeys, rightStart, clusterWidth, topOfRow, bottomOfRow)

                currentY += unitRowHeight + rowSpacing
            }

            // Hit bounds (touch bounds) for Split Mode
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

                val splitIndex = when (rowIndex) {
                    0, 1 -> 5
                    2 -> 5
                    3 -> 5
                    4 -> 3
                    else -> row.size / 2
                }

                val leftKeys = row.subList(0, splitIndex.coerceAtMost(row.size))
                val rightKeys = row.subList(splitIndex.coerceAtMost(row.size), row.size)

                for (i in leftKeys.indices) {
                    val key = leftKeys[i]
                    val leftBound = if (i == 0) 0f else (leftKeys[i - 1].visualRect.right + key.visualRect.left) / 2f
                    val rightBound = if (i == leftKeys.size - 1) {
                        key.visualRect.right + (centerGap / 3f)
                    } else {
                        (key.visualRect.right + leftKeys[i + 1].visualRect.left) / 2f
                    }
                    key.rect.set(leftBound, topBound, rightBound, bottomBound)
                }

                for (i in rightKeys.indices) {
                    val key = rightKeys[i]
                    val leftBound = if (i == 0) {
                        key.visualRect.left - (centerGap / 3f)
                    } else {
                        (rightKeys[i - 1].visualRect.right + key.visualRect.left) / 2f
                    }
                    val rightBound = if (i == rightKeys.size - 1) width.toFloat() else {
                        (key.visualRect.right + rightKeys[i + 1].visualRect.left) / 2f
                    }
                    key.rect.set(leftBound, topBound, rightBound, bottomBound)
                }
            }
            return
        }

        // ── Normal or Side-Inset Compact Mode Layout ──
        val sideInset = if (isCompact) {
            (width * 0.16f).coerceIn(40f * density, 140f * density)
        } else {
            0f
        }

        val paddingLeft = baseSidePadding + sideInset
        val paddingRight = baseSidePadding + sideInset
        val usableWidth = width - paddingLeft - paddingRight

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
            currentY += unitRowHeight + rowSpacing
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

    private fun layoutCluster(
        clusterKeys: List<Key>,
        startX: Float,
        availableClusterWidth: Float,
        topY: Float,
        bottomY: Float
    ) {
        if (clusterKeys.isEmpty()) return
        val totalSpacings = (clusterKeys.size - 1).coerceAtLeast(0)
        val widthAvailable = availableClusterWidth - (horizontalSpacing * totalSpacings)
        val totalWeight = clusterKeys.sumOf { it.weight.toDouble() }.toFloat()
        val unitWidth = widthAvailable / totalWeight

        var currentX = startX
        for (key in clusterKeys) {
            val actualWidth = key.weight * unitWidth
            key.visualRect.set(currentX, topY, currentX + actualWidth, bottomY)
            key.shadowRect.set(
                key.visualRect.left, key.visualRect.top + 0.8f * density,
                key.visualRect.right, key.visualRect.bottom + 1.2f * density
            )
            currentX += actualWidth + horizontalSpacing
        }
    }

    // ── Drawing ─────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isLandscape && landscapeMode == AppPreferences.LANDSCAPE_SPLIT) {
            drawSplitCenterIndicator(canvas, width.toFloat(), height.toFloat())
        }
        for (key in keys) {
            drawKey(canvas, key)
        }
    }

    private fun drawSplitCenterIndicator(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val indicatorH = 26f * density
        val cy = h / 2f
        val pillW = 3.5f * density
        paint.color = subTextColor
        paint.alpha = if (isDark) 35 else 50
        shadowDrawRect.set(cx - pillW / 2f, cy - indicatorH / 2f, cx + pillW / 2f, cy + indicatorH / 2f)
        canvas.drawRoundRect(shadowDrawRect, pillW / 2f, pillW / 2f, paint)
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
                val spaceChars = if (useVietSpace) spaceVietChars else spaceEngChars
                textPaint.textSize = if (isLandscape) 11.5f * density else 12.5f * density
                textPaint.color = subTextColor
                textPaint.typeface = normalTypeface
                val baseline = KeyboardUtils.centerBaselineY(drawRect, textPaint)
                canvas.drawText(spaceChars, 0, spaceChars.size, drawRect.centerX(), baseline, textPaint)
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
