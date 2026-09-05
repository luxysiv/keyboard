package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import com.goviet.keyboard.VietnameseInputMethodService

class SymbolsPickerGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyGridView(context, attrs, defStyleAttr) {

    // Inputs/setters
    var service: VietnameseInputMethodService? = null
    var activeTab: Int = 0
        set(value) {
            if (field != value) {
                field = value
                activePage = value
                invalidate()
            }
        }
    var onTabChange: ((Int) -> Unit)? = null
    var onKey: ((String) -> Unit)? = null

    // State sync
    var symbolsList: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var activePage: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var pagesCount: Int = 9
        set(value) {
            field = value
            invalidate()
        }

    var currentImeOptions: Int = 0
        set(value) {
            if (field != value) {
                field = value
                setupBottomKeys()
                requestLayoutAndCalculate()
            }
        }

    var currentInputType: Int = 0
        set(value) {
            if (field != value) {
                field = value
                setupBottomKeys()
                requestLayoutAndCalculate()
            }
        }

    var currentLanguageMode: String = "VIE"
        set(value) {
            field = value
            invalidate()
        }

    // Grid details
    private val cols = 8
    private val rows = 3
    private val itemsPerPage = cols * rows

    // Bottom control row keys
    private val bottomKeys = mutableListOf<Key>()

    // Touch variables
    private var startX = 0f
    private var startY = 0f
    private var isSwipeDetectionActive = false
    private var activeTouchedBottomKey: Key? = null
    private var pressedSymbolIndex = -1

    // Preallocated drawing structures to prevent GC in onDraw
    private val rowTops = FloatArray(3)
    private val rowBottoms = FloatArray(3)
    private val symbolCardRect = RectF()
    private val symbolVisualDrawRect = RectF()

    // Backspace loop
    private val backspaceRepeatHandler = RepeatingKeyPressHandler {
        onKey?.invoke("BACKSPACE")
    }

    init {
        setupBottomKeys()
    }

    private fun setupBottomKeys() {
        bottomKeys.clear()
        bottomKeys.add(Key(code = "ABC", label = "ABC", weight = 1.3f, isFunctional = true))
        bottomKeys.add(Key(code = ",", label = ",", weight = 1.0f))
        bottomKeys.add(Key(code = "EMOJI", label = "🙂", weight = 1.1f, isFunctional = true))
        bottomKeys.add(Key(code = "SPACE", label = "", weight = 3.4f))
        bottomKeys.add(Key(code = "BACKSPACE", label = "⌫", weight = 1.2f, isFunctional = true))
        val enterLabel = KeyboardUtils.getEnterSymbolLabel(currentImeOptions, currentInputType)
        bottomKeys.add(Key(code = "ENTER", label = enterLabel, weight = 1.3f, isSpecialEnter = true))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateKeyCoordinates(w, h)
    }

    private fun requestLayoutAndCalculate() {
        if (width > 0 && height > 0) {
            calculateKeyCoordinates(width, height)
        }
        invalidate()
    }

    private fun getBottomRowHeight(): Float {
        return KeyboardUtils.calculateStandardRowHeight(height.toFloat(), density, 5, 7.0f * density)
    }

    private fun calculateKeyCoordinates(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        val paddingLeft = 4 * density
        val paddingRight = 4 * density
        val paddingBottom = 4 * density

        val usableWidth = width - paddingLeft - paddingRight
        val bottomRowHeight = getBottomRowHeight()
        val topOfBottomRow = height - bottomRowHeight - paddingBottom

        val horizontalSpacing = 4.5f * density
        val totalSpacings = bottomKeys.size - 1
        val widthAvailable = usableWidth - (horizontalSpacing * totalSpacings)
        val totalWeight = bottomKeys.sumOf { it.weight.toDouble() }.toFloat()
        val unitWidth = widthAvailable / totalWeight

        var currentX = paddingLeft
        bottomKeys.forEach { key ->
            val actualWidth = key.weight * unitWidth
            key.rect.set(
                currentX,
                topOfBottomRow,
                currentX + actualWidth,
                topOfBottomRow + bottomRowHeight
            )
            currentX += actualWidth + horizontalSpacing
        }
    }

    private fun findRowIndexByY(y: Float, usableHeight: Float, heightPadding: Float): Int {
        val totalRowWeight = 3.0f
        val unitRowHeight = usableHeight / totalRowWeight
        var currentY = heightPadding
        for (r in 0 until 3) {
            val rowWeight = 1.0f
            val rHeight = rowWeight * unitRowHeight
            if (y >= currentY && y <= currentY + rHeight) {
                return r
            }
            currentY += rHeight
        }
        return -1
    }

    private fun findSymbolIndexByCoordinates(x: Float, y: Float, symbolCellWidth: Float, usableHeight: Float, heightPadding: Float): Int {
        if (y >= height - 66f * density) return -1
        val r = findRowIndexByY(y, usableHeight, heightPadding)
        val c = (x / symbolCellWidth).toInt()
        if (r < 0 || c < 0 || c >= cols) return -1
        val gridIdx = r * cols + c
        val pageSymbols = symbolsList.take(itemsPerPage)
        if (gridIdx in pageSymbols.indices) {
            val totalRowWeight = 3.0f
            val unitRowHeight = usableHeight / totalRowWeight
            var cellTop = heightPadding
            for (rowIndex in 0 until r) {
                val rowWeight = 1.0f
                cellTop += rowWeight * unitRowHeight
            }
            val rowWeight = 1.0f
            val cellHeight = rowWeight * unitRowHeight
            val cellLeft = c * symbolCellWidth
            val cardPadding = 1.5f * density
            val cardRect = RectF(
                cellLeft + cardPadding,
                cellTop + cardPadding,
                cellLeft + symbolCellWidth - cardPadding,
                cellTop + cellHeight - cardPadding
            )
            if (cardRect.contains(x, y)) {
                return gridIdx
            }
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val heightPadding = 2f * density
        val usableHeight = height - getBottomRowHeight() - (16f * density) - heightPadding
        val symbolCellWidth = width / cols.toFloat()

        val totalRowWeight = 3.0f
        val unitRowHeight = usableHeight / totalRowWeight
        var currentY = heightPadding
        for (r in 0 until 3) {
            val rowWeight = 1.0f
            val rHeight = rowWeight * unitRowHeight
            rowTops[r] = currentY
            rowBottoms[r] = currentY + rHeight
            currentY += rHeight
        }

        val totalPageItems = kotlin.math.min(symbolsList.size, itemsPerPage)

        for (i in 0 until totalPageItems) {
            val r = i / cols
            val c = i % cols
            val sym = symbolsList[i]

            val cellLeft = c * symbolCellWidth
            val cellTop = rowTops[r]
            val cellBottom = rowBottoms[r]
            
            val cardPadding = 1.5f * density
            symbolCardRect.set(
                cellLeft + cardPadding,
                cellTop + cardPadding,
                cellLeft + symbolCellWidth - cardPadding,
                cellBottom - cardPadding
            )
            
            val isCellPressed = (i == pressedSymbolIndex)
            val shouldDrawBg = isCellPressed || (keyStyle == 0 || keyStyle == 1)
            if (shouldDrawBg) {
                val cardColor = if (isCellPressed) {
                    keyPressedBgColor
                } else {
                    if (isDark) 0xFF2E3544.toInt() else 0xFFFFFFFF.toInt()
                }
                KeyRenderer.drawFlatRoundedRect(
                    canvas = canvas,
                    rect = symbolCardRect,
                    cornerRadius = 6f * density,
                    color = cardColor,
                    style = Paint.Style.FILL
                )
            }

            textPaint.textSize = 19f * density
            textPaint.typeface = boldTypeface
            textPaint.color = textColor
            val baseline = KeyboardUtils.centerBaselineY(symbolCardRect, textPaint)
            canvas.drawText(sym, symbolCardRect.centerX(), baseline, textPaint)
        }

        // 2. Draw Page Indicator Dots
        val indicatorCenterY = height - getBottomRowHeight() - (9f * density)
        val dotSpacing = 8f * density
        val totalDotsWidth = (pagesCount - 1) * dotSpacing
        val firstDotX = (width - totalDotsWidth) / 2f

        for (i in 0 until pagesCount) {
            val dotX = firstDotX + i * dotSpacing
            val isSelected = i == activePage
            paint.color = if (isSelected) activeAccentColor else (if (isDark) 0x4DFFFFFF else 0x4D000000)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(dotX, indicatorCenterY, if (isSelected) 3f * density else 2f * density, paint)
        }

        // 3. Draw Bottom Control Keys
        bottomKeys.forEach { key ->
            drawKeyBackground(canvas, key.rect, key.isPressed, key.isFunctional, key.isSpecialEnter)

            textPaint.color = textColor
            val isSingleChar = key.label.length == 1
            if (key.isFunctional) {
                textPaint.textSize = 13f * density
                textPaint.typeface = boldTypeface
            } else if (key.isSpecialEnter && !isSingleChar) {
                textPaint.textSize = 15f * density
                textPaint.typeface = boldTypeface
            } else {
                textPaint.textSize = 21f * density
                textPaint.typeface = boldTypeface
            }

            // Reposition text drawing area
            val scale = if (key.isPressed) 0.96f else 1.0f
            val w = key.rect.width()
            val h = key.rect.height()
            val cx = key.rect.centerX()
            val cy = key.rect.centerY()
            symbolVisualDrawRect.set(
                cx - w * scale / 2f,
                cy - h * scale / 2f,
                cx + w * scale / 2f,
                cy + h * scale / 2f
            )

            val baseline = KeyboardUtils.centerBaselineY(symbolVisualDrawRect, textPaint)
            if (key.code == "ENTER") {
                val enterColor = 0xFFFFFFFF.toInt()
                KeyboardUtils.drawEnterIcon(canvas, symbolVisualDrawRect, currentImeOptions, currentInputType, density, enterColor)
            } else if (key.code == "SPACE") {
                val spaceText = if (currentLanguageMode == "VIE") "Tiếng Việt" else "English"
                textPaint.textSize = 12.5f * density
                textPaint.color = subTextColor
                textPaint.typeface = normalTypeface
                val spaceBaseline = KeyboardUtils.centerBaselineY(symbolVisualDrawRect, textPaint)
                canvas.drawText(spaceText, symbolVisualDrawRect.centerX(), spaceBaseline, textPaint)
            } else {
                canvas.drawText(key.label, symbolVisualDrawRect.centerX(), baseline, textPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val x = event.x
        val y = event.y

        val heightPadding = 2f * density
        val usableHeight = height - getBottomRowHeight() - (16f * density) - heightPadding
        val symbolCellWidth = width / cols.toFloat()

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                startY = y
                isSwipeDetectionActive = false
                activeTouchedBottomKey = null
                backspaceRepeatHandler.stop()

                val key = findBottomKeyByCoordinates(x, y)
                if (key != null) {
                    activeTouchedBottomKey = key
                    key.isPressed = true
                    
                    if (key.code == "BACKSPACE") {
                        onKey?.invoke("BACKSPACE")
                        backspaceRepeatHandler.start()
                    }
                    invalidate()
                } else if (y < height - 64f * density) {
                    isSwipeDetectionActive = true
                    pressedSymbolIndex = findSymbolIndexByCoordinates(x, y, symbolCellWidth, usableHeight, heightPadding)
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isSwipeDetectionActive) {
                    val newPressedIndex = findSymbolIndexByCoordinates(x, y, symbolCellWidth, usableHeight, heightPadding)
                    if (newPressedIndex != pressedSymbolIndex) {
                        pressedSymbolIndex = newPressedIndex
                        invalidate()
                    }
                } else {
                    val deltaX = Math.abs(x - startX)
                    val deltaY = Math.abs(y - startY)
                    if (deltaX > 15f * density || deltaY > 15f * density) {
                        // Prevent random trigger if sliding fingers
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressedSymbolIndex = -1
                backspaceRepeatHandler.stop()

                activeTouchedBottomKey?.let { key ->
                    key.isPressed = false
                    if (action == MotionEvent.ACTION_UP) {
                        when (key.code) {
                            "ABC" -> {
                                onKey?.invoke("ABC")
                            }
                            "EMOJI" -> {
                                onKey?.invoke("EMOJI")
                            }
                            "BACKSPACE" -> {
                                // Handled on down
                            }
                            else -> {
                                onKey?.invoke(key.code)
                            }
                        }
                    }
                }

                if (isSwipeDetectionActive && action == MotionEvent.ACTION_UP) {
                    val deltaX = x - startX
                    val deltaY = y - startY
                    
                    if (Math.abs(deltaX) > 40f * density && Math.abs(deltaX) > Math.abs(deltaY)) {
                        if (deltaX > 0) {
                            onSwipeRight?.invoke()
                            onTabChange?.invoke((activeTab - 1).coerceAtLeast(0))
                        } else {
                            onSwipeLeft?.invoke()
                            onTabChange?.invoke((activeTab + 1).coerceAtMost(8))
                        }
                    } else if (Math.abs(deltaX) < 10f * density && Math.abs(deltaY) < 10f * density) {
                        val gridIdx = findSymbolIndexByCoordinates(x, y, symbolCellWidth, usableHeight, heightPadding)
                        if (gridIdx != -1) {
                            val pickedString = symbolsList[gridIdx]
                            service?.addRecentSymbol(pickedString)
                            onKey?.invoke(pickedString)
                        }
                    }
                }

                activeTouchedBottomKey = null
                isSwipeDetectionActive = false
                invalidate()
            }
        }
        return true
    }

    private fun findBottomKeyByCoordinates(x: Float, y: Float): Key? {
        bottomKeys.forEach { key ->
            if (key.rect.contains(x, y)) {
                return key
            }
        }
        return null
    }

    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        backspaceRepeatHandler.stop()
    }
}
