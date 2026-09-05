package com.goviet.keyboard.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.goviet.core.AppPreferences
import com.goviet.core.density
import com.goviet.keyboard.util.IconDrawer
import com.goviet.R

open class TraditionalSettingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyGridView(context, attrs, defStyleAttr) {

    // Settings state
    var macroEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var alwaysMacro: Boolean = false
    var autoCapitalize: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var directW: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var oldTonePlacement: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var themeMode: String = "system"
        set(value) {
            field = value
            invalidate()
        }

    var bottomPaddingLevel: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    // Callbacks
    var onUpdateSettings: ((
        macro: Boolean,
        autoCap: Boolean,
        dirW: Boolean,
        oldTone: Boolean
    ) -> Unit)? = null
    var onKeyStyleChange: ((Int) -> Unit)? = null
    var onThemeChange: (() -> Unit)? = null
    var onBottomPaddingChange: (() -> Unit)? = null
    var onOpenFullSettings: (() -> Unit)? = null

    private val keycapTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Submenu architecture (Submenus with multiple choices)
    enum class SubMenu {
        NONE, STYLE, THEME, PADDING
    }

    var activeSubMenu: SubMenu = SubMenu.NONE

    // Data models for categories
    data class CategoryItem(
        val titleResId: Int,
        val iconId: String,
        val isToggle: Boolean = false,
        val toggleIndex: Int = -1
    )

    private val categories = listOf(
        CategoryItem(R.string.pref_cat_style, "border_style"),
        CategoryItem(R.string.pref_cat_theme, "palette"),
        CategoryItem(R.string.pref_cat_padding, "height"),
        CategoryItem(R.string.pref_cat_macro, "build", isToggle = true, toggleIndex = 0),
        CategoryItem(R.string.pref_cat_auto_capitalize, "typing_style", isToggle = true, toggleIndex = 1)
    )

    // Scrolling & Animations
    private val scroller = OverScroller(context)
    private var scrollOffset = 0f
    private var maxScroll = 0f
    private var lastTouchY = 0f
    private var totalDragY = 0f
    private var isDragging = false
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var transitionProgress = 1f
    private var transitionAnimator: ValueAnimator? = null
    private var pressedRectIndex: Int = -1

    // Bounds for layout & click targets
    private val catCardRects = List(5) { RectF() }

    private val styleButtonRects = List(3) { RectF() }
    private val styleLabelResIds = listOf(R.string.pref_style_border, R.string.pref_style_flat, R.string.pref_style_none)

    private val themeButtonRects = List(4) { RectF() }
    private val paddingButtonRects = List(4) { RectF() }

    // Preallocated drawing structures
    private val simulatedShadowRect = RectF()
    private val styleKeyRect = RectF()

    private val themeModes = listOf("system", "light", "dark", "dynamic")
    private val themeLabelsResIds = listOf(
        R.string.pref_theme_system_cap,
        R.string.pref_theme_light_cap,
        R.string.pref_theme_dark_cap,
        R.string.pref_theme_dynamic_cap
    )
    private val themeIcons = listOf("brightness_auto", "light_mode", "dark_mode", "palette")

    private val paddingLevels = listOf(0, 1, 2, 3)
    private val paddingLabelsResIds = listOf(
        R.string.pref_padding_default_cap,
        R.string.pref_padding_medium_cap,
        R.string.pref_padding_high_cap,
        R.string.pref_padding_very_high_cap
    )

    private val padding = 12f * density
    private val itemSpacing = 10f * density

    fun goBackToMainMenu() {
        changeSubMenu(SubMenu.NONE)
    }

    private fun changeSubMenu(newSubMenu: SubMenu) {
        transitionAnimator?.cancel()
        activeSubMenu = newSubMenu
        pressedRectIndex = -1
        calculateLayout()

        transitionProgress = 0f
        transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                transitionProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun getColorWithAlpha(color: Int, alpha: Int): Int {
        val a = (Color.alpha(color) * (alpha / 255f)).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun drawCheckmarkBadge(canvas: Canvas, rect: RectF, alpha: Int) {
        val cx = rect.right - 10f * density
        val cy = rect.top + 10f * density
        val radius = 7f * density

        val bgPaint = paint
        bgPaint.color = getColorWithAlpha(if (isDark) 0xFF1E293B.toInt() else 0xFFFFFFFF.toInt(), alpha)
        bgPaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius, bgPaint)

        IconDrawer.draw(
            canvas = canvas,
            context = context,
            resId = R.drawable.ic_check_circle,
            cx = cx,
            cy = cy,
            sizePx = radius * 2f,
            tintColor = getColorWithAlpha(activeAccentColor, alpha)
        )
    }

    private fun getToggleValue(index: Int): Boolean {
        return when (index) {
            0 -> macroEnabled
            1 -> autoCapitalize
            else -> false
        }
    }

    private fun toggleDirectly(index: Int) {
        when (index) {
            0 -> {
                macroEnabled = !macroEnabled
                alwaysMacro = macroEnabled
            }
            1 -> {
                autoCapitalize = !autoCapitalize
            }
        }
        notifySettingsUpdated()
    }

    private fun notifySettingsUpdated() {
        onUpdateSettings?.invoke(
            macroEnabled,
            autoCapitalize,
            directW,
            oldTonePlacement
        )
        invalidate()
    }

    private fun calculateLayout() {
        if (width <= 0 || height <= 0) return

        val headerHeight = 32f * density
        val mainTop = padding + headerHeight
        val usableWidth = width - padding * 2

        // Row 1: 3 cards for Appearance/Style (Kiểu phím, Giao diện, Cạnh dưới)
        val colWidth3 = (usableWidth - itemSpacing * 2) / 3f
        val cellHeight = 72f * density

        for (i in 0..2) {
            val left = padding + i * (colWidth3 + itemSpacing)
            catCardRects[i].set(left, mainTop, left + colWidth3, mainTop + cellHeight)
        }

        // Row 2: 2 cards for Fast 1-Tap Toggles (Gõ tắt, Viết hoa đầu câu)
        val colWidth2 = (usableWidth - itemSpacing) / 2f
        val row2Top = mainTop + cellHeight + itemSpacing
        for (i in 3..4) {
            val col = i - 3
            val left = padding + col * (colWidth2 + itemSpacing)
            catCardRects[i].set(left, row2Top, left + colWidth2, row2Top + cellHeight)
        }

        val totalContentHeight = cellHeight * 2 + itemSpacing
        val visibleHeight = height - mainTop - padding
        maxScroll = (totalContentHeight - visibleHeight).coerceAtLeast(0f)
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll)

        // 2. Submenus Layout
        val subMenuHeight = height - padding * 2 - headerHeight

        // A. STYLE SUBMENU
        val styleWidth = (usableWidth - itemSpacing * 2) / 3f
        var sX = padding
        for (i in 0..2) {
            styleButtonRects[i].set(sX, mainTop + 2f * density, sX + styleWidth, mainTop + 2f * density + subMenuHeight - 8f * density)
            sX += styleWidth + itemSpacing
        }

        // B. THEME SUBMENU (2x2)
        val subColW = (usableWidth - itemSpacing) / 2f
        val subRowH = (subMenuHeight - itemSpacing) / 2f
        themeButtonRects[0].set(padding, mainTop, padding + subColW, mainTop + subRowH)
        themeButtonRects[1].set(padding + subColW + itemSpacing, mainTop, padding + subColW * 2 + itemSpacing, mainTop + subRowH)
        themeButtonRects[2].set(padding, mainTop + subRowH + itemSpacing, padding + subColW, mainTop + subRowH * 2 + itemSpacing)
        themeButtonRects[3].set(padding + subColW + itemSpacing, mainTop + subRowH + itemSpacing, padding + subColW * 2 + itemSpacing, mainTop + subRowH * 2 + itemSpacing)

        // C. PADDING SUBMENU (2x2)
        paddingButtonRects[0].set(padding, mainTop, padding + subColW, mainTop + subRowH)
        paddingButtonRects[1].set(padding + subColW + itemSpacing, mainTop, padding + subColW * 2 + itemSpacing, mainTop + subRowH)
        paddingButtonRects[2].set(padding, mainTop + subRowH + itemSpacing, padding + subColW, mainTop + subRowH * 2 + itemSpacing)
        paddingButtonRects[3].set(padding + subColW + itemSpacing, mainTop + subRowH + itemSpacing, padding + subColW * 2 + itemSpacing, mainTop + subRowH * 2 + itemSpacing)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateLayout()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            invalidate()
        }
    }

    private fun drawSimulatedKey(
        canvas: Canvas,
        rect: RectF,
        letter: String,
        style: Int,
        isSelected: Boolean,
        alpha: Int = 255
    ) {
        val radius = 4f * density

        when (style) {
            0 -> {
                // Style 0: Bordered keycap (3D keycap with subtle drop shadow)
                simulatedShadowRect.set(rect.left, rect.top + 1.5f * density, rect.right, rect.bottom + 1.5f * density)
                paint.color = getColorWithAlpha(if (isDark) 0xFF111827.toInt() else 0xFF9CA3AF.toInt(), alpha)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(simulatedShadowRect, radius, radius, paint)

                val capColor = if (isSelected) activeAccentColor else (if (isDark) 0xFF374151.toInt() else 0xFFFFFFFF.toInt())
                paint.color = getColorWithAlpha(capColor, alpha)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            1 -> {
                // Style 1: Flat keycap surface
                val flatColor = if (isSelected) activeAccentColor else (if (isDark) 0xFF374151.toInt() else 0xFFE5E7EB.toInt())
                paint.color = getColorWithAlpha(flatColor, alpha)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            else -> {
                // Style 2: Borderless / flat surface plane with subtle panel contrast
                val planeColor = if (isSelected) {
                    activeAccentColor
                } else {
                    if (isDark) 0xFF242C3D.toInt() else 0xFFE9EDF5.toInt()
                }
                paint.color = getColorWithAlpha(planeColor, alpha)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }

        keycapTextPaint.color = getColorWithAlpha(if (isSelected) Color.WHITE else textColor, alpha)
        keycapTextPaint.textSize = 9f * density
        keycapTextPaint.typeface = boldTypeface
        keycapTextPaint.textAlign = Paint.Align.CENTER
        val baseline = KeyboardUtils.centerBaselineY(rect, keycapTextPaint)
        canvas.drawText(letter, rect.centerX(), baseline, keycapTextPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val headerHeight = 32f * density
        val mainTop = padding + headerHeight
        val alpha = (transitionProgress * 255).toInt().coerceIn(0, 255)

        // Draw SubMenu or Dashboard
        if (activeSubMenu == SubMenu.NONE) {
            // 1. App Header: Title with settings icon & launch arrow
            val isHeaderPressed = (pressedRectIndex == 999)
            val headerAlpha = if (isHeaderPressed) 0.6f else 1.0f

            IconDrawer.draw(
                canvas = canvas,
                context = context,
                id = "settings",
                cx = padding + 8f * density,
                cy = padding + 8f * density,
                sizePx = 16f * density,
                tintColor = getColorWithAlpha(activeAccentColor, (alpha * headerAlpha).toInt())
            )

            textPaint.color = getColorWithAlpha(textColor, (alpha * headerAlpha).toInt())
            textPaint.textSize = 12f * density
            textPaint.typeface = boldTypeface
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(context.getString(R.string.pref_title), padding + 22f * density, padding + 12f * density, textPaint)

            IconDrawer.draw(
                canvas = canvas,
                context = context,
                id = "chevron_right",
                cx = width - padding - 8f * density,
                cy = padding + 8f * density,
                sizePx = 14f * density,
                tintColor = getColorWithAlpha(subTextColor, (alpha * headerAlpha).toInt())
            )

            canvas.save()
            canvas.clipRect(0f, mainTop, width.toFloat(), height - padding)
            canvas.translate(0f, -scrollOffset)

            // Draw Category Items in 3 Columns
            for (i in categories.indices) {
                val item = categories[i]
                val rect = catCardRects[i]
                val isPressed = (pressedRectIndex == i)

                if (isPressed) {
                    canvas.save()
                    canvas.scale(0.96f, 0.96f, rect.centerX(), rect.centerY())
                }

                val cx = rect.centerX()
                val cy = rect.top + 20f * density

                val isToggleActive = item.isToggle && getToggleValue(item.toggleIndex)

                // Minimalist airy icon background
                val circleRadius = 15f * density
                val circleBgAlpha = if (isToggleActive) 0.20f else 0.12f
                paint.color = getColorWithAlpha(activeAccentColor, (alpha * circleBgAlpha).toInt())
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, circleRadius, paint)

                // Icon
                IconDrawer.draw(
                    canvas = canvas,
                    context = context,
                    id = item.iconId,
                    cx = cx,
                    cy = cy,
                    sizePx = 18f * density,
                    tintColor = getColorWithAlpha(activeAccentColor, alpha)
                )

                // Category Title Text
                textPaint.color = getColorWithAlpha(textColor, alpha)
                textPaint.textSize = 9.5f * density
                textPaint.typeface = boldTypeface
                textPaint.textAlign = Paint.Align.CENTER
                val titleY = cy + circleRadius + 12f * density
                canvas.drawText(context.getString(item.titleResId), cx, titleY, textPaint)

                // Value Subtext
                val subText = if (!item.isToggle) {
                    when (i) {
                        0 -> when (keyStyle) { 0 -> context.getString(R.string.pref_style_border_sub); 1 -> context.getString(R.string.pref_style_flat_sub); else -> context.getString(R.string.pref_style_none_sub) }
                        1 -> when (themeMode) { "system" -> context.getString(R.string.pref_theme_system_sub); "light" -> context.getString(R.string.pref_theme_light_sub); "dark" -> context.getString(R.string.pref_theme_dark_sub); "dynamic" -> context.getString(R.string.pref_theme_dynamic_sub); else -> context.getString(R.string.pref_theme_default_sub) }
                        2 -> when (bottomPaddingLevel) { 1 -> context.getString(R.string.pref_padding_medium_sub); 2 -> context.getString(R.string.pref_padding_high_sub); 3 -> context.getString(R.string.pref_padding_very_high_sub); else -> context.getString(R.string.pref_theme_default_sub) }
                        else -> ""
                    }
                } else {
                    val valBool = getToggleValue(item.toggleIndex)
                    if (valBool) context.getString(R.string.pref_status_on) else context.getString(R.string.pref_status_off)
                }

                textPaint.color = getColorWithAlpha(if (isToggleActive) activeAccentColor else subTextColor, alpha)
                textPaint.textSize = 8f * density
                textPaint.typeface = if (isToggleActive) boldTypeface else normalTypeface
                val subY = titleY + 11f * density
                canvas.drawText(subText, cx, subY, textPaint)

                // Active dot indicator under active item
                val isNonDefault = if (!item.isToggle) {
                    when (i) {
                        0 -> keyStyle != 0
                        1 -> themeMode != "system"
                        2 -> bottomPaddingLevel != 0
                        else -> false
                    }
                } else {
                    isToggleActive
                }

                if (isNonDefault) {
                    paint.color = getColorWithAlpha(activeAccentColor, alpha)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, rect.bottom - 3f * density, 2f * density, paint)
                }

                if (isPressed) {
                    canvas.restore()
                }
            }

            canvas.restore()
        } else {
            // SubMenu Header
            val titleText = when (activeSubMenu) {
                SubMenu.STYLE -> context.getString(R.string.pref_submenu_style)
                SubMenu.THEME -> context.getString(R.string.pref_submenu_theme)
                SubMenu.PADDING -> context.getString(R.string.pref_submenu_padding)
                else -> ""
            }
            textPaint.color = getColorWithAlpha(textColor, alpha)
            textPaint.textSize = 12f * density
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(titleText, padding, padding + 12f * density, textPaint)

            val subMenuHeight = height - padding * 2 - headerHeight

            when (activeSubMenu) {
                SubMenu.NONE -> {}
                SubMenu.STYLE -> {
                    val dividerPaint = paint
                    dividerPaint.color = getColorWithAlpha(if (isDark) 0x1AFFFFFF else 0x1A000000, alpha)
                    dividerPaint.style = Paint.Style.STROKE
                    dividerPaint.strokeWidth = 0.8f * density

                    for (i in 0..2) {
                        val rect = styleButtonRects[i]
                        val isOptionSelected = (keyStyle == i)
                        val isPressed = (pressedRectIndex == 100 + i)

                        if (isPressed) {
                            canvas.save()
                            canvas.scale(0.96f, 0.96f, rect.centerX(), rect.centerY())
                        }

                        if (i < 2) {
                            val divX = rect.right + itemSpacing / 2f
                            canvas.drawLine(divX, mainTop + 8f * density, divX, mainTop + subMenuHeight - 16f * density, dividerPaint)
                        }

                        val cx = rect.centerX()
                        val itemTintColor = if (isOptionSelected) activeAccentColor else subTextColor

                        IconDrawer.draw(
                            canvas = canvas,
                            context = context,
                            id = "border_style",
                            cx = cx,
                            cy = rect.top + 16f * density,
                            sizePx = 18f * density,
                            tintColor = itemTintColor
                        )

                        textPaint.color = getColorWithAlpha(if (isOptionSelected) activeAccentColor else textColor, alpha)
                        textPaint.textSize = 9.5f * density
                        textPaint.typeface = boldTypeface
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText(context.getString(styleLabelResIds[i]), cx, rect.top + 36f * density, textPaint)

                        styleKeyRect.set(cx - 14f * density, rect.centerY() - 4f * density, cx + 14f * density, rect.centerY() + 16f * density)
                        drawSimulatedKey(canvas, styleKeyRect, "A", i, isOptionSelected, alpha)

                        val subLabel = when (i) {
                            0 -> context.getString(R.string.pref_style_border_sub)
                            1 -> context.getString(R.string.pref_style_flat_sub)
                            else -> context.getString(R.string.pref_style_none_sub)
                        }
                        textPaint.color = getColorWithAlpha(subTextColor, alpha)
                        textPaint.textSize = 8f * density
                        textPaint.typeface = normalTypeface
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText(subLabel, cx, rect.bottom - 8f * density, textPaint)

                        if (isOptionSelected) {
                            drawCheckmarkBadge(canvas, rect, alpha)
                        }

                        if (isPressed) {
                            canvas.restore()
                        }
                    }
                }
                SubMenu.THEME -> {
                    for (i in 0..3) {
                        val rect = themeButtonRects[i]
                        val isOptionSelected = (themeMode == themeModes[i])
                        val isPressed = (pressedRectIndex == 100 + i)

                        if (isPressed) {
                            canvas.save()
                            canvas.scale(0.96f, 0.96f, rect.centerX(), rect.centerY())
                        }

                        val cardBg = if (isOptionSelected) {
                            getColorWithAlpha(activeAccentColor, (alpha * 0.16f).toInt())
                        } else {
                            getColorWithAlpha(if (isDark) 0x1AFFFFFF else 0x0D000000, alpha)
                        }
                        paint.color = cardBg
                        paint.style = Paint.Style.FILL
                        canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)

                        if (isOptionSelected) {
                            paint.color = getColorWithAlpha(activeAccentColor, alpha)
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = 1.5f * density
                            canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
                        }

                        val iconColor = if (isOptionSelected) activeAccentColor else subTextColor
                        IconDrawer.draw(
                            canvas = canvas,
                            context = context,
                            id = themeIcons[i],
                            cx = rect.left + 20f * density,
                            cy = rect.centerY(),
                            sizePx = 18f * density,
                            tintColor = iconColor
                        )

                        textPaint.color = getColorWithAlpha(if (isOptionSelected) activeAccentColor else textColor, alpha)
                        textPaint.textSize = 10.5f * density
                        textPaint.typeface = boldTypeface
                        textPaint.textAlign = Paint.Align.LEFT
                        val baseline = KeyboardUtils.centerBaselineY(rect, textPaint)
                        canvas.drawText(context.getString(themeLabelsResIds[i]), rect.left + 36f * density, baseline, textPaint)

                        if (isOptionSelected) {
                            drawCheckmarkBadge(canvas, rect, alpha)
                        }

                        if (isPressed) {
                            canvas.restore()
                        }
                    }
                }
                SubMenu.PADDING -> {
                    for (i in 0..3) {
                        val rect = paddingButtonRects[i]
                        val isOptionSelected = (bottomPaddingLevel == paddingLevels[i])
                        val isPressed = (pressedRectIndex == 100 + i)

                        if (isPressed) {
                            canvas.save()
                            canvas.scale(0.96f, 0.96f, rect.centerX(), rect.centerY())
                        }

                        val cardBg = if (isOptionSelected) {
                            getColorWithAlpha(activeAccentColor, (alpha * 0.16f).toInt())
                        } else {
                            getColorWithAlpha(if (isDark) 0x1AFFFFFF else 0x0D000000, alpha)
                        }
                        paint.color = cardBg
                        paint.style = Paint.Style.FILL
                        canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)

                        if (isOptionSelected) {
                            paint.color = getColorWithAlpha(activeAccentColor, alpha)
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = 1.5f * density
                            canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
                        }

                        val iconColor = if (isOptionSelected) activeAccentColor else subTextColor
                        IconDrawer.draw(
                            canvas = canvas,
                            context = context,
                            id = "height",
                            cx = rect.left + 20f * density,
                            cy = rect.centerY(),
                            sizePx = 18f * density,
                            tintColor = iconColor
                        )

                        textPaint.color = getColorWithAlpha(if (isOptionSelected) activeAccentColor else textColor, alpha)
                        textPaint.textSize = 10.5f * density
                        textPaint.typeface = boldTypeface
                        textPaint.textAlign = Paint.Align.LEFT
                        val baseline = KeyboardUtils.centerBaselineY(rect, textPaint)
                        canvas.drawText(context.getString(paddingLabelsResIds[i]), rect.left + 36f * density, baseline, textPaint)

                        if (isOptionSelected) {
                            drawCheckmarkBadge(canvas, rect, alpha)
                        }

                        if (isPressed) {
                            canvas.restore()
                        }
                    }
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        val x = event.x
        val y = event.y
        val headerHeight = 32f * density
        val mainTop = padding + headerHeight

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = y
                totalDragY = 0f
                isDragging = false
                pressedRectIndex = -1

                if (activeSubMenu == SubMenu.NONE) {
                    if (y < mainTop) {
                        pressedRectIndex = 999
                        invalidate()
                    } else if (y <= height - padding) {
                        val scrolledY = y + scrollOffset
                        for (i in categories.indices) {
                            if (catCardRects[i].contains(x, scrolledY)) {
                                pressedRectIndex = i
                                invalidate()
                                break
                            }
                        }
                    }
                } else {
                    when (activeSubMenu) {
                        SubMenu.STYLE -> {
                            for (i in 0..2) {
                                if (styleButtonRects[i].contains(x, y)) {
                                    pressedRectIndex = 100 + i
                                    invalidate()
                                    break
                                }
                            }
                        }
                        SubMenu.THEME -> {
                            for (i in 0..3) {
                                if (themeButtonRects[i].contains(x, y)) {
                                    pressedRectIndex = 100 + i
                                    invalidate()
                                    break
                                }
                            }
                        }
                        SubMenu.PADDING -> {
                            for (i in 0..3) {
                                if (paddingButtonRects[i].contains(x, y)) {
                                    pressedRectIndex = 100 + i
                                    invalidate()
                                    break
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = lastTouchY - y
                lastTouchY = y
                totalDragY += deltaY

                if (activeSubMenu == SubMenu.NONE && maxScroll > 0f) {
                    if (!isDragging && Math.abs(totalDragY) > touchSlop) {
                        isDragging = true
                        pressedRectIndex = -1
                        invalidate()
                    }

                    if (isDragging) {
                        scrollOffset = (scrollOffset + deltaY).coerceIn(0f, maxScroll)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val initialVelocity = velocityTracker?.yVelocity ?: 0f
                    scroller.fling(0, scrollOffset.toInt(), 0, -initialVelocity.toInt(), 0, 0, 0, maxScroll.toInt())
                    invalidate()
                } else {
                    val prevPressed = pressedRectIndex
                    pressedRectIndex = -1
                    invalidate()

                    if (activeSubMenu == SubMenu.NONE) {
                        if (prevPressed == 999 && y < mainTop) {
                            onOpenFullSettings?.invoke()
                            return true
                        }
                        if (prevPressed in categories.indices) {
                            val item = categories[prevPressed]
                            if (item.isToggle) {
                                // Direct 1-tap fast toggle!
                                toggleDirectly(item.toggleIndex)
                                return true
                            } else {
                                // Multi-option SubMenu
                                val targetSubMenu = when (prevPressed) {
                                    0 -> SubMenu.STYLE
                                    1 -> SubMenu.THEME
                                    2 -> SubMenu.PADDING
                                    else -> SubMenu.NONE
                                }
                                changeSubMenu(targetSubMenu)
                                return true
                            }
                        }
                    } else if (prevPressed >= 100) {
                        val i = prevPressed - 100
                        when (activeSubMenu) {
                            SubMenu.STYLE -> {
                                if (i in 0..2 && styleButtonRects[i].contains(x, y)) {
                                    keyStyle = i
                                    onKeyStyleChange?.invoke(i)
                                    return true
                                }
                            }
                            SubMenu.THEME -> {
                                val themeModes = listOf("system", "light", "dark", "dynamic")
                                if (i in 0..3 && themeButtonRects[i].contains(x, y)) {
                                    val mode = themeModes[i]
                                    themeMode = mode
                                    AppPreferences.setThemeMode(mode)
                                    onThemeChange?.invoke()
                                    return true
                                }
                            }
                            SubMenu.PADDING -> {
                                if (i in 0..3 && paddingButtonRects[i].contains(x, y)) {
                                    bottomPaddingLevel = i
                                    AppPreferences.setBottomPaddingLevel(i)
                                    onBottomPaddingChange?.invoke()
                                    return true
                                }
                            }
                            else -> {}
                        }
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedRectIndex = -1
                velocityTracker?.recycle()
                velocityTracker = null
                invalidate()
            }
        }
        return true
    }
}
