package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import com.goviet.core.density

class TraditionalTpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyGridView(context, attrs, defStyleAttr) {

    // Properties
    var currentEditorInputType: Int = 0
        set(value) {
            field = value
            setupKeys()
        }

    var isOtpField: Boolean = false
        set(value) {
            field = value
            setupKeys()
        }

    var currentImeOptions: Int = 0
        set(value) {
            field = value
            setupKeys()
        }

    var currentInputType: Int = 0
        set(value) {
            field = value
            setupKeys()
        }

    // Callbacks
    var onKey: ((String) -> Unit)? = null
    var onSwitchToABC: (() -> Unit)? = null
    var onSwitchToSymbols: (() -> Unit)? = null

    private val keysList = mutableListOf<Key>()

    // Popup window for long press options
    private val keyPopup = KeyPopupWindow(context)
    private var activePopupOptionIndex = -1
    private var isLongPressed = false
    private var startX = 0f
    private var startY = 0f

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var activeTouchedKey: Key? = null

    // For BACKSPACE repeating and sliding deletion
    private var backspaceSelectCount = 0
    private var backspaceStartX = 0f
    private val backspaceRepeatHandler = RepeatingKeyPressHandler(
        intervalProvider = { count: Int ->
            when {
                count < 5 -> 150L
                count < 10 -> 100L
                count < 15 -> 60L
                else -> 40L
            }
        }
    ) {
        onKey?.invoke("BACKSPACE")
    }

    private val longPressRunnable = Runnable {
        activeTouchedKey?.let { key ->
            val opts = key.longPressOptions
            if (opts != null && opts.isNotEmpty()) {
                isLongPressed = true
                activePopupOptionIndex = key.longPressDefaultIndex
                keyPopup.showLongPress(this, opts, activePopupOptionIndex, isDark, currentTheme, key.rect)
                invalidate()
            }
        }
    }

    init {
        setupKeys()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateLayout()
    }

    private fun calculateLayout() {
        if (width <= 0 || height <= 0 || keysList.isEmpty()) return

        val paddingLeft = 4f * density
        val paddingRight = 4f * density
        val paddingTop = 4f * density
        val paddingBottom = 4f * density

        val usableWidth = width - paddingLeft - paddingRight
        val usableHeight = height - paddingTop - paddingBottom

        val colW = usableWidth / 4f
        val rowH = usableHeight / 4f

        for (index in keysList.indices) {
            val key = keysList[index]
            val row = index / 4
            val col = index % 4

            val cellLeft = paddingLeft + col * colW
            val cellRight = cellLeft + colW
            val cellTop = paddingTop + row * rowH
            val cellBottom = cellTop + rowH

            val marginX = 2f * density
            val marginY = 4f * density

            val keyLeft = cellLeft + marginX
            val keyRight = cellRight - marginX
            val keyTop = cellTop + marginY
            val keyBottom = cellBottom - marginY

            key.rect.set(cellLeft, cellTop, cellRight, cellBottom)
            key.visualRect.set(keyLeft, keyTop, keyRight, keyBottom)
            key.shadowRect.set(keyLeft, keyTop + 0.8f * density, keyRight, keyBottom + 1.2f * density)
        }
    }

    private fun setupKeys() {
        keysList.clear()

        val classType = currentEditorInputType and 0x0000000f // EditorInfo.TYPE_MASK_CLASS
        val isPhone = classType == 3 // EditorInfo.TYPE_CLASS_PHONE
        val isNumber = classType == 2 // EditorInfo.TYPE_CLASS_NUMBER
        val isDatetime = classType == 4 // EditorInfo.TYPE_CLASS_DATETIME

        val isSigned = (currentEditorInputType and 0x00001000) != 0
        val isDecimal = (currentEditorInputType and 0x00002000) != 0

        // Row 1
        keysList.add(Key(code = "1", label = "1"))
        keysList.add(Key(code = "2", label = "2"))
        keysList.add(Key(code = "3", label = "3"))
        keysList.add(Key(code = "BACKSPACE", label = "⌫", isFunctional = true))

        // Row 2
        keysList.add(Key(code = "4", label = "4"))
        keysList.add(Key(code = "5", label = "5"))
        keysList.add(Key(code = "6", label = "6"))
        if (isPhone) {
            keysList.add(Key(code = "*", label = "*", secondaryLabel = "#", longPressOptions = listOf("*", "#")))
        } else if (isDatetime) {
            keysList.add(Key(code = "-", label = "-", secondaryLabel = "/", longPressOptions = listOf("-", "/")))
        } else {
            keysList.add(Key(code = ".", label = ".", secondaryLabel = "-", longPressOptions = listOf(".", "-", "?", "!", ";", ":")))
        }

        // Row 3
        keysList.add(Key(code = "7", label = "7"))
        keysList.add(Key(code = "8", label = "8"))
        keysList.add(Key(code = "9", label = "9"))
        if (isPhone) {
            keysList.add(Key(code = "(", label = "(", secondaryLabel = ")", longPressOptions = listOf("(", ")")))
        } else if (isDatetime) {
            keysList.add(Key(code = ":", label = ":", secondaryLabel = "-", longPressOptions = listOf(":", "-")))
        } else if (isNumber && isSigned) {
            keysList.add(Key(code = "-", label = "-", secondaryLabel = "+", longPressOptions = listOf("-", "+")))
        } else {
            keysList.add(Key(code = ",", label = ",", secondaryLabel = "+", longPressOptions = listOf(",", "+", "-", "*", "/", "=")))
        }

        // Row 4
        keysList.add(Key(code = "ABC", label = "ABC", isFunctional = true))
        
        if (isPhone) {
            keysList.add(Key(code = "0", label = "0", secondaryLabel = "+", longPressOptions = listOf("0", "+")))
        } else {
            keysList.add(Key(code = "0", label = "0"))
        }

        if (isOtpField) {
            keysList.add(Key(code = "PASTE_OTP", label = "Paste", isFunctional = true))
        } else if (isPhone) {
            keysList.add(Key(code = "-", label = "-"))
        } else if (isDatetime) {
            keysList.add(Key(code = "/", label = "/"))
        } else if (isNumber && isDecimal) {
            keysList.add(Key(code = ".", label = "."))
        } else {
            keysList.add(Key(code = "SPACE", label = "Space"))
        }

        val enterLabel = KeyboardUtils.getEnterTextLabel(currentImeOptions, currentInputType)
        keysList.add(Key(code = "ENTER", label = enterLabel, isSpecialEnter = true))

        calculateLayout()
    }

    private fun handleKeyClick(key: Key) {
        if (key.code == "ABC") {
            onSwitchToABC?.invoke()
        } else if (key.code == "SYMBOLS") {
            onSwitchToSymbols?.invoke()
        } else {
            onKey?.invoke(key.code)
        }
    }


    private fun findKeyByCoordinates(x: Float, y: Float): Key? {
        for (key in keysList) {
            if (key.rect.contains(x, y)) {
                return key
            }
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(panelBgColor)

        for (key in keysList) {
            computeScaledRect(
                cx = key.visualRect.centerX(),
                cy = key.visualRect.centerY(),
                w = key.visualRect.width(),
                h = key.visualRect.height(),
                scale = if (key.isPressed) 0.96f else 1.0f
            )
            drawKeyBackgroundScaled(canvas, key, cornerRadius = 8f * density)

            // Draw label
            KeyboardUtils.drawKeyLabel(canvas, key.label, drawRect, textPaint, textColor, density, key.isFunctional)

            val tpadSec = key.secondaryLabel
            if (tpadSec != null) {
                KeyboardUtils.drawSecondaryLabel(canvas, tpadSec, drawRect, textPaint, subTextColor, density)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val x = event.x
        val y = event.y

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val key = findKeyByCoordinates(x, y)
                if (key != null) {
                    activeTouchedKey = key
                    key.isPressed = true
                    isLongPressed = false
                    startX = x
                    startY = y

                    if (key.code == "BACKSPACE") {
                        backspaceSelectCount = 0
                        backspaceStartX = x
                        onKey?.invoke("BACKSPACE")
                        backspaceRepeatHandler.start()
                    } else {
                        longPressHandler.postDelayed(longPressRunnable, 350)
                    }
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val trackedKey = activeTouchedKey
                if (trackedKey != null) {
                    if (isLongPressed) {
                        val options = trackedKey.longPressOptions
                        if (options != null && options.isNotEmpty()) {
                            val defaultIdx = trackedKey.longPressDefaultIndex
                            val step = 32 * density
                            val dragOffset = x - startX
                            val hoveredIdx = (defaultIdx + (dragOffset / step).toInt()).coerceIn(0, options.size - 1)
                            if (hoveredIdx != activePopupOptionIndex) {
                                activePopupOptionIndex = hoveredIdx
                                keyPopup.updateHoverIndex(hoveredIdx)
                            }
                        }
                    } else {
                        val currentHovered = findKeyByCoordinates(x, y)
                        if (currentHovered != trackedKey) {
                            trackedKey.isPressed = false
                            longPressHandler.removeCallbacks(longPressRunnable)
                            backspaceRepeatHandler.stop()

                            if (currentHovered != null) {
                                activeTouchedKey = currentHovered
                                currentHovered.isPressed = true
                                
                                if (currentHovered.code == "BACKSPACE") {
                                    backspaceSelectCount = 0
                                    backspaceStartX = x
                                    onKey?.invoke("BACKSPACE")
                                    backspaceRepeatHandler.start()
                                } else {
                                    longPressHandler.postDelayed(longPressRunnable, 350)
                                }
                            } else {
                                activeTouchedKey = null
                            }
                            invalidate()
                        } else if (trackedKey.code == "BACKSPACE" && !isLongPressed) {
                            val deltaX = x - backspaceStartX
                            if (Math.abs(deltaX) > 10f * density) {
                                backspaceRepeatHandler.stop()
                            }
                            if (deltaX < -30f * density) {
                                val wordsToDelete = (-deltaX / (30f * density)).toInt()
                                if (wordsToDelete > backspaceSelectCount) {
                                    val diff = wordsToDelete - backspaceSelectCount
                                    backspaceSelectCount = wordsToDelete
                                    repeat(diff) {
                                        onKey?.invoke("DELETE_WORD")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                backspaceRepeatHandler.stop()

                val trackedKey = activeTouchedKey
                if (trackedKey != null) {
                    trackedKey.isPressed = false
                    if (isLongPressed) {
                        val lpOpts = trackedKey.longPressOptions
                        if (lpOpts != null && activePopupOptionIndex in lpOpts.indices) {
                            val selectedOption = lpOpts[activePopupOptionIndex]
                            onKey?.invoke(selectedOption)
                        }
                        keyPopup.dismiss()
                    } else {
                        if (trackedKey.code == "BACKSPACE") {
                            // Already handled in down/repeat/swipe
                        } else {
                            handleKeyClick(trackedKey)
                        }
                    }
                }
                activeTouchedKey = null
                isLongPressed = false
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                backspaceRepeatHandler.stop()
                keyPopup.dismiss()
                activeTouchedKey?.let { it.isPressed = false }
                activeTouchedKey = null
                isLongPressed = false
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        backspaceRepeatHandler.stop()
        longPressHandler.removeCallbacksAndMessages(null)
    }
}
