package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import kotlin.math.max

import com.goviet.core.density

class TraditionalEmojiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyGridView(context, attrs, defStyleAttr) {

    // Properties
    var emojisList: List<String> = emptyList()
        set(value) {
            field = value
            calculateLayout()
            invalidate()
        }

    var currentImeOptions: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var currentInputType: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var currentLanguageMode: String = "VIE"
        set(value) {
            field = value
            invalidate()
        }

    // Callbacks
    var onSelectEmoji: ((String) -> Unit)? = null
    var onBackToLetters: (() -> Unit)? = null
    var onSwitchToSymbols: (() -> Unit)? = null
    var onKeyPress: ((String) -> Unit)? = null

    private val keysInfo = listOf(
        Key(code = "ABC", label = "ABC", weight = 1.3f, isFunctional = true),
        Key(code = ",", label = ",", weight = 1.0f),
        Key(code = "!?#", label = "!?#", weight = 1.1f, isFunctional = true),
        Key(code = "SPACE", label = "", weight = 3.4f),
        Key(code = "BACKSPACE", label = "⌫", weight = 1.2f, isFunctional = true),
        Key(code = "ENTER", label = "⏎", weight = 1.3f, isSpecialEnter = true)
    )

    // Layout values
    private var totalContentHeight = 0f
    private var colW = 0f
    private var emojiAreaLeft = 0f
    private var emojiAreaRight = 0f
    private var emojiAreaTop = 0f
    private var emojiAreaBottom = 0f
    private var emojiAreaWidth = 0f
    private var emojiAreaHeight = 0f

    // Scrolling states
    private var scrollOffset = 0f
    private val scroller = OverScroller(context)
    private var lastTouchY = 0f
    private var isDragging = false
    private var velocityTracker: android.view.VelocityTracker? = null

    // Press states
    private var pressedBottomKeyIndex = -1
    private var pressedEmojiIndex = -1
    private val pressedEmojiRect = RectF()

    private val horizontalSpacing = 4.5f * density
    private val verticalSpacing = 7.0f * density

    init {
        calculateLayout()
    }

    private fun getBottomRowHeight(): Float {
        return KeyboardUtils.calculateStandardRowHeight(height.toFloat(), density, 5, verticalSpacing)
    }

    private fun calculateLayout() {
        if (width <= 0 || height <= 0) return

        // 1. Calculate bottom row bounds matching StandardLetterGridView and SymbolsPickerGridView
        val paddingLeft = 4f * density
        val paddingRight = 4f * density
        val paddingBottom = 4f * density

        val usableWidth = width - paddingLeft - paddingRight
        val bottomRowHeight = getBottomRowHeight()
        val bottomContainerTop = height - bottomRowHeight - paddingBottom
        val bottomContainerBottom = height - paddingBottom
        
        val totalSpacings = keysInfo.size - 1
        val widthAvailable = usableWidth - (horizontalSpacing * totalSpacings)
        val totalWeight = keysInfo.sumOf { it.weight.toDouble() }.toFloat()
        val unitWidth = widthAvailable / totalWeight

        var curX = paddingLeft
        for (key in keysInfo) {
            val keyBoundingW = key.weight * unitWidth
            key.rect.set(
                curX,
                bottomContainerTop,
                curX + keyBoundingW,
                bottomContainerBottom
            )
            key.visualRect.set(key.rect)
            key.shadowRect.set(
                key.rect.left,
                key.rect.top + 0.8f * density,
                key.rect.right,
                key.rect.bottom + 1.2f * density
            )
            curX += keyBoundingW + horizontalSpacing
        }

        // 2. Calculate emoji area bounds
        emojiAreaLeft = 4f * density
        emojiAreaRight = width - 4f * density
        emojiAreaTop = 6f * density
        emojiAreaBottom = bottomContainerTop - 6f * density
        emojiAreaWidth = emojiAreaRight - emojiAreaLeft
        emojiAreaHeight = emojiAreaBottom - emojiAreaTop

        colW = emojiAreaWidth / 7f
        val numRows = (emojisList.size + 6) / 7
        totalContentHeight = numRows * colW

        clampScrollOffset()
    }

    private fun clampScrollOffset() {
        val maxScroll = max(0f, totalContentHeight - emojiAreaHeight)
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateLayout()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.toFloat()
            clampScrollOffset()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(panelBgColor)

        // 1. Draw Emojis (Clipped to the emoji area and scrolled)
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), emojiAreaBottom)
        canvas.translate(0f, -scrollOffset)

        textPaint.textSize = colW * 0.55f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.DEFAULT

        val viewportTop = scrollOffset
        val viewportBottom = scrollOffset + emojiAreaHeight

        val firstRow = max(0, (viewportTop / colW).toInt())
        val lastRow = ((viewportBottom / colW).toInt() + 1).coerceAtMost((emojisList.size + 6) / 7)

        for (row in firstRow until lastRow) {
            val cellTop = emojiAreaTop + row * colW
            val cellBottom = cellTop + colW
            val cellCenterY = (cellTop + cellBottom) / 2f
            val baseline = KeyboardUtils.centerBaselineY(cellCenterY, textPaint)

            for (col in 0 until 7) {
                val index = row * 7 + col
                if (index < emojisList.size) {
                    val emoji = emojisList[index]
                    val cellLeft = emojiAreaLeft + col * colW
                    val cellRight = cellLeft + colW
                    val cellCenterX = (cellLeft + cellRight) / 2f

                    if (pressedEmojiIndex == index) {
                        pressedEmojiRect.set(
                            cellLeft + 2f * density,
                            cellTop + 2f * density,
                            cellRight - 2f * density,
                            cellBottom - 2f * density
                        )
                        KeyRenderer.drawFlatRoundedRect(
                            canvas = canvas,
                            rect = pressedEmojiRect,
                            cornerRadius = 6f * density,
                            color = keyPressedBgColor,
                            style = Paint.Style.FILL
                        )
                    }

                    canvas.drawText(emoji, cellCenterX, baseline, textPaint)
                }
            }
        }
        canvas.restore()

        // 2. Draw Bottom Keys
        for (i in keysInfo.indices) {
            val key = keysInfo[i]
            val code = key.code
            val isFunctional = code == "ABC" || code == "!?#" || code == "BACKSPACE"
            val isSpecialEnter = code == "ENTER"

            val isPressed = (pressedBottomKeyIndex == i)

            val bgColor = if (isSpecialEnter || isFunctional) functionalKeyBgColor else keyBgColor
            val pressedBgColor = if (isSpecialEnter || isFunctional) functionalKeyPressedBgColor else keyPressedBgColor

            val textCol = if (code == "SPACE") {
                subTextColor
            } else {
                textColor
            }

            KeyRenderer.drawStandardKey(
                canvas = canvas,
                drawRect = key.rect,
                shadowRect = key.shadowRect,
                cornerRadius = 8f * density,
                density = density,
                isDark = isDark,
                keyStyle = keyStyle,
                isPressed = isPressed,
                isFunctional = isFunctional,
                isSpecialEnter = isSpecialEnter,
                bgColor = bgColor,
                pressedBgColor = pressedBgColor
            )

            val label = when (code) {
                "SPACE" -> if (currentLanguageMode == "VIE") "Tiếng Việt" else "English"
                "ENTER" -> KeyboardUtils.getEnterSymbolLabel(currentImeOptions, currentInputType)
                else -> key.label
            }

            textPaint.color = textCol
            textPaint.typeface = if (code == "SPACE") Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            textPaint.textSize = if (code == "SPACE" || code == "BACKSPACE" || code == "ENTER") 13f * density else 16f * density
            textPaint.textAlign = Paint.Align.CENTER

            val baseline = KeyboardUtils.centerBaselineY(key.rect, textPaint)
            canvas.drawText(label, key.rect.centerX(), baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (velocityTracker == null) {
            velocityTracker = android.view.VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        val action = event.actionMasked
        val x = event.x
        val y = event.y

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastTouchY = y
                isDragging = false

                if (y >= emojiAreaBottom) {
                    pressedBottomKeyIndex = -1
                    pressedEmojiIndex = -1
                    for (i in keysInfo.indices) {
                        if (keysInfo[i].rect.contains(x, y)) {
                            pressedBottomKeyIndex = i
                            break
                        }
                    }
                    invalidate()
                } else {
                    pressedBottomKeyIndex = -1
                    pressedEmojiIndex = -1
                    val relativeY = y - emojiAreaTop + scrollOffset
                    val relativeX = x - emojiAreaLeft
                    if (relativeX in 0f..emojiAreaWidth && relativeY >= 0f) {
                        val col = (relativeX / colW).toInt().coerceIn(0, 6)
                        val row = (relativeY / colW).toInt()
                        val emojiIndex = row * 7 + col
                        if (emojiIndex in emojisList.indices) {
                            pressedEmojiIndex = emojiIndex
                        }
                    }
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaY = lastTouchY - y
                lastTouchY = y

                val touchSlop = 8f * density
                if (!isDragging && y < emojiAreaBottom) {
                    if (Math.abs(deltaY) > touchSlop) {
                        isDragging = true
                        pressedEmojiIndex = -1
                        pressedBottomKeyIndex = -1
                        invalidate()
                    }
                }

                if (isDragging) {
                    scrollOffset += deltaY
                    clampScrollOffset()
                    invalidate()
                } else {
                    if (pressedBottomKeyIndex != -1) {
                        if (!keysInfo[pressedBottomKeyIndex].rect.contains(x, y)) {
                            pressedBottomKeyIndex = -1
                            invalidate()
                        }
                    }
                    if (pressedEmojiIndex != -1) {
                        val relativeY = y - emojiAreaTop + scrollOffset
                        val relativeX = x - emojiAreaLeft
                        var stillInCell = false
                        if (relativeX in 0f..emojiAreaWidth && relativeY >= 0f) {
                            val col = (relativeX / colW).toInt().coerceIn(0, 6)
                            val row = (relativeY / colW).toInt()
                            val emojiIndex = row * 7 + col
                            if (emojiIndex == pressedEmojiIndex) {
                                stillInCell = true
                            }
                        }
                        if (!stillInCell) {
                            pressedEmojiIndex = -1
                            invalidate()
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    if (pressedBottomKeyIndex != -1) {
                        if (keysInfo[pressedBottomKeyIndex].rect.contains(x, y)) {
                            val code = keysInfo[pressedBottomKeyIndex].code
                            when (code) {
                                "ABC" -> onBackToLetters?.invoke()
                                "!?#" -> onSwitchToSymbols?.invoke()
                                "BACKSPACE" -> onKeyPress?.invoke("BACKSPACE")
                                "SPACE" -> onKeyPress?.invoke("SPACE")
                                "ENTER" -> onKeyPress?.invoke("ENTER")
                                else -> onKeyPress?.invoke(code)
                            }
                        }
                    } else if (pressedEmojiIndex != -1) {
                        val relativeY = y - emojiAreaTop + scrollOffset
                        val relativeX = x - emojiAreaLeft
                        if (relativeX in 0f..emojiAreaWidth && relativeY >= 0f) {
                            val col = (relativeX / colW).toInt().coerceIn(0, 6)
                            val row = (relativeY / colW).toInt()
                            val emojiIndex = row * 7 + col
                            if (emojiIndex == pressedEmojiIndex && emojiIndex in emojisList.indices) {
                                onSelectEmoji?.invoke(emojisList[emojiIndex])
                            }
                        }
                    }
                } else {
                    velocityTracker?.let { tracker ->
                        tracker.computeCurrentVelocity(1000)
                        val velocityY = tracker.yVelocity
                        val maxScroll = max(0f, totalContentHeight - emojiAreaHeight)
                        scroller.fling(
                            0, scrollOffset.toInt(),
                            0, -velocityY.toInt(),
                            0, 0,
                            0, maxScroll.toInt()
                        )
                        postInvalidateOnAnimation()
                    }
                }

                pressedBottomKeyIndex = -1
                pressedEmojiIndex = -1
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedBottomKeyIndex = -1
                pressedEmojiIndex = -1
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                invalidate()
            }
        }
        return true
    }
}
