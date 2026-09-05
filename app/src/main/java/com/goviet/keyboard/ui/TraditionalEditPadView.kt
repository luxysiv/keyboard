package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.util.AttributeSet
import com.goviet.R
import com.goviet.keyboard.util.IconDrawer

import com.goviet.core.density

class TraditionalEditPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyGridView(context, attrs, defStyleAttr) {

    // Properties passed from Compose
    var isSelecting: Boolean = false
        set(value) {
            field = value
            setupKeysAndCoordinates()
            invalidate()
        }

    // Single unified callback for action execution
    var onAction: ((String) -> Unit)? = null

    var errorColor: Int = 0xFFEF4444.toInt()
        set(value) {
            field = value
            invalidate()
        }

    private val spacing = 4f * density

    private val leftKeys = mutableListOf<Key>()
    private val centerKeys = mutableListOf<Key>()
    private val rightKeys = mutableListOf<Key>()
    private val allKeys = mutableListOf<Key>()
    private val dpadContainerRect = RectF()

    private var activeTouchedKey: Key? = null

    // For repeating actions like DPAD moves / backspaces on long press / hold
    private var holdingCode: String? = null
    private val keyRepeatHandler = RepeatingKeyPressHandler(defaultIntervalMs = 100L) {
        val code = holdingCode
        if (code == "UP" || code == "DOWN" || code == "LEFT" || code == "RIGHT" || code == "BACKSPACE" || code == "DELETE") {
            onAction?.invoke(code)
        }
    }

    init {
        setupKeys()
    }

    private fun setupKeys() {
        leftKeys.clear()
        leftKeys.add(Key(code = "SELECT_ALL", label = "Chọn hết"))
        leftKeys.add(Key(code = "CUT", label = "Cắt"))
        leftKeys.add(Key(code = "TOGGLE_SELECT", label = if (isSelecting) "Đang chọn" else "Chọn chữ", isSelectingStatus = true))

        centerKeys.clear()
        // Row 1
        centerKeys.add(Key(code = "HOME", label = "⇤", isCenterPad = true, iconId = "home_edge"))
        centerKeys.add(Key(code = "UP", label = "▲", isCenterPad = true, iconId = "arrow_up"))
        centerKeys.add(Key(code = "END", label = "⇥", isCenterPad = true, iconId = "end_edge"))
        // Row 2
        centerKeys.add(Key(code = "LEFT", label = "◀", isCenterPad = true, iconId = "arrow_left"))
        centerKeys.add(Key(code = "INDICATOR", label = if (isSelecting) "TẮT CHỌN" else "BẬT CHỌN", isAccent = isSelecting, isCenterPad = true))
        centerKeys.add(Key(code = "RIGHT", label = "▶", isCenterPad = true, iconId = "arrow_right"))
        // Row 3
        centerKeys.add(Key(code = "BACKSPACE", label = "⌫", isError = true, isCenterPad = true, iconId = "backspace"))
        centerKeys.add(Key(code = "DOWN", label = "▼", isCenterPad = true, iconId = "arrow_down"))
        centerKeys.add(Key(code = "DELETE", label = "DEL", isCenterPad = true))

        rightKeys.clear()
        rightKeys.add(Key(code = "COPY", label = "Sao chép"))
        rightKeys.add(Key(code = "PASTE", label = "Dán"))
        rightKeys.add(Key(code = "CLOSE", label = "XONG", isAccent = true))

        allKeys.clear()
        allKeys.addAll(leftKeys)
        allKeys.addAll(centerKeys)
        allKeys.addAll(rightKeys)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setupKeysAndCoordinates()
    }

    private fun setupKeysAndCoordinates() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        // Update labels based on live `isSelecting` status dynamically
        setupKeys()

        val padding = 4f * density
        val usableWidth = w - padding * 2
        val usableHeight = h - padding * 2

        // Total weights: Left (1.1) + Center (1.8) + Right (1.1) = 4.0
        val totalWeight = 4.0f
        val unitColWidth = (usableWidth - spacing * 2) / totalWeight

        val leftWidth = unitColWidth * 1.1f
        val centerWidth = unitColWidth * 1.8f
        val rightWidth = unitColWidth * 1.1f

        // 1. Calculate Left Column Areas (3 keys, stacked vertically)
        val leftX = padding
        val rowHeightLeft = (usableHeight - spacing * 2) / 3.0f
        for (i in leftKeys.indices) {
            val key = leftKeys[i]
            val keyTop = padding + i * (rowHeightLeft + spacing)
            key.rect.set(leftX, keyTop, leftX + leftWidth, keyTop + rowHeightLeft)
        }

        // 2. Calculate Center Column Areas (3x3 grid)
        val centerX = leftX + leftWidth + spacing
        val centerPadding = 4f * density
        val innerCenterWidth = centerWidth - centerPadding * 2
        val innerCenterHeight = usableHeight - centerPadding * 2

        val dpadColWidth = (innerCenterWidth - spacing * 2) / 3.0f
        val dpadRowHeight = (innerCenterHeight - spacing * 2) / 3.0f

        val containerRect = RectF(centerX, padding, centerX + centerWidth, padding + usableHeight)

        for (i in centerKeys.indices) {
            val key = centerKeys[i]
            val row = i / 3
            val col = i % 3

            val keyLeft = centerX + centerPadding + col * (dpadColWidth + spacing)
            val keyTop = padding + centerPadding + row * (dpadRowHeight + spacing)
            key.rect.set(keyLeft, keyTop, keyLeft + dpadColWidth, keyTop + dpadRowHeight)
        }

        // 3. Calculate Right Column Areas (3 keys, stacked vertically)
        val rightX = centerX + centerWidth + spacing
        val rowHeightRight = (usableHeight - spacing * 2) / 3.0f
        for (i in rightKeys.indices) {
            val key = rightKeys[i]
            val keyTop = padding + i * (rowHeightRight + spacing)
            key.rect.set(rightX, keyTop, rightX + rightWidth, keyTop + rowHeightRight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw panel background color
        paint.color = panelBgColor
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Draw center dpad container card boundary background
        val leftX = padding
        val totalWeight = 4.0f
        val usableWidth = width - padding * 2
        val unitColWidth = (usableWidth - spacing * 2) / totalWeight
        val leftWidth = unitColWidth * 1.1f
        val centerX = leftX + leftWidth + spacing
        val centerWidth = unitColWidth * 1.8f

        dpadContainerRect.set(centerX, spacing, centerX + centerWidth, height - spacing)
        KeyRenderer.drawFlatRoundedRect(
            canvas = canvas,
            rect = dpadContainerRect,
            cornerRadius = 12f * density,
            color = if (isDark) 0xFF141A25.toInt() else 0xFFE2E8F0.toInt(),
            style = Paint.Style.FILL
        )

        // Draw all individual keys
        allKeys.forEach { key ->
            drawKey(canvas, key)
        }
    }

    private fun drawKey(canvas: Canvas, key: Key) {
        val isPressed = key.isPressed
        val scale = if (isPressed) 0.96f else 1.0f
        computeScaledRect(
            cx = key.rect.centerX(),
            cy = key.rect.centerY(),
            w = key.rect.width(),
            h = key.rect.height(),
            scale = scale
        )

        val bgPaintColor = when {
            isPressed -> keyPressedBgColor
            key.isAccent -> activeAccentColor
            key.isSelectingStatus && isSelecting -> (activeAccentColor and 0x00FFFFFF) or 0x33000000
            key.isCenterPad -> {
                if (isDark) 0xFF222B3C.toInt() else 0xFFFFFFFF.toInt()
            }
            else -> keyBgColor
        }

        KeyRenderer.drawStandardKey(
            canvas = canvas,
            drawRect = drawRect,
            shadowRect = KeyRenderer.emptyRect,
            cornerRadius = keyCornerRadius,
            density = density,
            isDark = isDark,
            keyStyle = if (keyStyle == 0) 1 else keyStyle,
            isPressed = isPressed,
            isFunctional = false,
            isSpecialEnter = false,
            bgColor = bgPaintColor,
            pressedBgColor = bgPaintColor,
            forceDrawBg = key.isAccent || (key.isSelectingStatus && isSelecting)
        )

        // Optional borders
        if (keyStyle == 0) {
            KeyRenderer.drawFlatRoundedRect(
                canvas = canvas,
                rect = drawRect,
                cornerRadius = keyCornerRadius,
                color = if (isDark) 0x11FFFFFF else 0x11000000,
                style = Paint.Style.STROKE,
                strokeWidth = 1f * density
            )
        }

        // Draw key text
        textPaint.color = when {
            key.isAccent -> if (isDark) 0xFF0F172A.toInt() else 0xFFFFFFFF.toInt()
            key.isError -> errorColor
            key.isSelectingStatus && isSelecting -> activeAccentColor
            else -> textColor
        }

        val baseline = KeyboardUtils.centerBaselineY(drawRect, textPaint)

        if (key.iconId != null) {
            val iconSize = 20f * density
            IconDrawer.draw(canvas, context, key.iconId, drawRect.centerX(), drawRect.centerY(), iconSize, textPaint.color)
        } else if (key.isSelectingStatus) {
            textPaint.textSize = 11f * density
            textPaint.typeface = boldTypeface
            canvas.drawText(key.label, drawRect.centerX(), baseline - 4f * density, textPaint)

            // Small indicator status dot below "Selecting status" label
            val dotRadius = 3f * density
            paint.color = if (isSelecting) activeAccentColor else 0x33FFFFFF
            paint.style = Paint.Style.FILL
            canvas.drawCircle(drawRect.centerX(), drawRect.bottom - 12f * density, dotRadius, paint)
        } else if (key.code == "INDICATOR") {
            textPaint.textSize = 8.5f * density
            textPaint.typeface = boldTypeface
            canvas.drawText(key.label, drawRect.centerX(), baseline, textPaint)
        } else {
            textPaint.textSize = if (key.label.length > 2) 11.5f * density else 16.5f * density
            textPaint.typeface = boldTypeface
            canvas.drawText(key.label, drawRect.centerX(), baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val x = event.x
        val y = event.y

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                holdingCode = null
                keyRepeatHandler.stop()

                val key = findKeyByCoordinates(x, y)
                if (key != null) {
                    activeTouchedKey = key
                    key.isPressed = true

                    if (key.code == "INDICATOR") {
                        onAction?.invoke("TOGGLE_SELECT")
                    } else {
                        onAction?.invoke(key.code)
                    }

                    // Start repeating mode on hold down for directionals and backspaces
                    if (key.code == "UP" || key.code == "DOWN" || key.code == "LEFT" || key.code == "RIGHT" || key.code == "BACKSPACE" || key.code == "DELETE") {
                        holdingCode = key.code
                        keyRepeatHandler.start()
                    }

                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                activeTouchedKey?.let { key ->
                    // If dragged outside the target keys, cancel press
                    if (!key.rect.contains(x, y)) {
                        key.isPressed = false
                        activeTouchedKey = null
                        holdingCode = null
                        keyRepeatHandler.stop()
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                holdingCode = null
                keyRepeatHandler.stop()

                activeTouchedKey?.let { key ->
                    key.isPressed = false
                }
                activeTouchedKey = null
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        keyRepeatHandler.stop()
    }

    private fun findKeyByCoordinates(x: Float, y: Float): Key? {
        allKeys.forEach { key ->
            if (key.rect.contains(x, y)) {
                return key
            }
        }
        return null
    }

    private val padding get() = 4f * density
}
