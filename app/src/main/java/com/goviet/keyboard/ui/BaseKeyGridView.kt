package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.goviet.core.AppPreferences
import com.goviet.core.density

abstract class BaseKeyGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Theme and style properties
    open var isDark: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    open var textColor: Int = 0xFFFFFFFF.toInt()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    open var subTextColor: Int = 0x80FFFFFF.toInt()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    open var panelBgColor: Int = 0xFF1E2431.toInt()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    open var keyBgColor: Int = 0xFF2E3544.toInt()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    open var keyPressedBgColor: Int = 0xFF454E63.toInt()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    open var functionalKeyBgColor: Int = 0xFF1E2431.toInt()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    open var functionalKeyPressedBgColor: Int = 0xFF283144.toInt()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    open var activeAccentColor: Int = 0xFF1E2431.toInt()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    open var currentTheme: KeyboardTheme = KeyboardTheme(
        textColor = textColor,
        subTextColor = subTextColor,
        keyBgColor = keyBgColor,
        keyPressedBgColor = keyPressedBgColor,
        functionalKeyBgColor = functionalKeyBgColor,
        functionalKeyPressedBgColor = functionalKeyPressedBgColor,
        activeAccentColor = activeAccentColor,
        isDark = isDark
    )
    private var themeVersion: Long = 0L

    open var keyStyle: Int = 0
        set(value) {
            if (field != value) {
                field = value
                showKeyBorders = (value == 0)
                invalidate()
            }
        }

    open var showKeyBorders: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    protected val density get() = context.density
    protected val keyCornerRadius get() = 10f * density

    protected val boldTypeface: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    protected val normalTypeface: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    protected val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = boldTypeface
    }

    protected val drawRect: RectF = RectF()
    protected val shadowDrawRect: RectF = RectF()

    init {
        keyStyle = AppPreferences.getKeyStyle()
        showKeyBorders = (keyStyle == 0)
    }

    open fun updateTheme(theme: KeyboardTheme) {
        val latestStyle = AppPreferences.getKeyStyle()
        var changed = false
        if (keyStyle != latestStyle) {
            keyStyle = latestStyle
            showKeyBorders = (keyStyle == 0)
            changed = true
        }
        if (this.currentTheme == theme && !changed) return
        this.textColor = theme.textColor
        this.subTextColor = theme.subTextColor
        this.keyBgColor = theme.keyBgColor
        this.keyPressedBgColor = theme.keyPressedBgColor
        this.functionalKeyBgColor = theme.functionalKeyBgColor
        this.functionalKeyPressedBgColor = theme.functionalKeyPressedBgColor
        this.activeAccentColor = theme.activeAccentColor
        this.isDark = theme.isDark
        this.currentTheme = theme
        themeVersion++
        invalidate()
    }

    protected fun computeScaledRect(
        cx: Float, cy: Float, w: Float, h: Float, scale: Float
    ) {
        drawRect.set(
            cx - w * scale / 2f,
            cy - h * scale / 2f,
            cx + w * scale / 2f,
            cy + h * scale / 2f
        )
    }

    protected fun drawKeyBackgroundScaled(
        canvas: Canvas,
        key: Key,
        scale: Float = if (key.isPressed) 0.96f else 1.0f,
        cornerRadius: Float = keyCornerRadius
    ) {
        val bgColor = if (key.isFunctional || key.isSpecialEnter) functionalKeyBgColor else keyBgColor
        val pressedBg = if (key.isFunctional || key.isSpecialEnter) functionalKeyPressedBgColor else keyPressedBgColor

        KeyRenderer.drawStandardKey(
            canvas = canvas,
            drawRect = drawRect,
            shadowRect = key.shadowRect,
            cornerRadius = cornerRadius,
            density = density,
            isDark = isDark,
            keyStyle = keyStyle,
            isPressed = key.isPressed,
            isFunctional = key.isFunctional,
            isSpecialEnter = key.isSpecialEnter,
            bgColor = bgColor,
            pressedBgColor = pressedBg
        )
    }

    protected fun drawKeyBackground(
        canvas: Canvas,
        rect: RectF,
        isPressed: Boolean,
        isFunctional: Boolean,
        isSpecialEnter: Boolean
    ) {
        val scale = if (isPressed) 0.96f else 1.0f
        val w = rect.width()
        val h = rect.height()
        val cx = rect.centerX()
        val cy = rect.centerY()

        drawRect.set(
            cx - w * scale / 2f,
            cy - h * scale / 2f,
            cx + w * scale / 2f,
            cy + h * scale / 2f
        )

        val bgColor = if (isSpecialEnter || isFunctional) functionalKeyBgColor else keyBgColor
        val pressedBgColor = if (isFunctional || isSpecialEnter) functionalKeyPressedBgColor else keyPressedBgColor

        if (!isPressed && keyStyle == 0) {
            shadowDrawRect.set(
                drawRect.left,
                drawRect.top + 0.8f * density,
                drawRect.right,
                drawRect.bottom + 1.2f * density
            )
        }

        KeyRenderer.drawStandardKey(
            canvas = canvas,
            drawRect = drawRect,
            shadowRect = shadowDrawRect,
            cornerRadius = keyCornerRadius,
            density = density,
            isDark = isDark,
            keyStyle = keyStyle,
            isPressed = isPressed,
            isFunctional = isFunctional,
            isSpecialEnter = isSpecialEnter,
            bgColor = bgColor,
            pressedBgColor = pressedBgColor
        )
    }
}
