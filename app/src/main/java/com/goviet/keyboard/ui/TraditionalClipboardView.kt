package com.goviet.keyboard.ui

import com.goviet.keyboard.clipboard.ClipboardEntity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.goviet.core.AppPreferences
import com.goviet.core.density
import android.widget.OverScroller
import kotlin.math.max
import com.goviet.R
import com.goviet.keyboard.util.IconDrawer

class TraditionalClipboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyGridView(context, attrs, defStyleAttr) {

    // Properties
    var items: List<ClipboardEntity> = emptyList()
        set(value) {
            field = value
            refreshList()
        }

    // Callbacks
    var onSelect: ((String) -> Unit)? = null
    var onDeleteItem: ((ClipboardEntity) -> Unit)? = null

    private val pinnedTexts = mutableSetOf<String>().apply {
        addAll(AppPreferences.getPinnedClipboardTexts())
    }

    // List of internal DisplayItems
    private sealed class DisplayItem {
        data class Header(val title: String) : DisplayItem()
        data class Clipboard(
            val entity: ClipboardEntity,
            val isPinned: Boolean,
            val isPendingDelete: Boolean = false
        ) : DisplayItem()
    }

    private val displayItems = mutableListOf<DisplayItem>()

    // Layout representation for canvas components
    private class ItemLayout(
        val item: DisplayItem,
        val top: Float,
        val bottom: Float,
        val cardRect: RectF = RectF(),
        val undoRect: RectF = RectF(),
        val lines: MutableList<String> = mutableListOf(),
        var textStartX: Float = 0f,
        var textStartY: Float = 0f,
        var charHeight: Float = 0f,
        var lineSpacing: Float = 0f
    )

    private val itemLayouts = mutableListOf<ItemLayout>()
    private var totalContentHeight = 0f

    // Precalculated empty state description lines
    private val emptyDescLines = mutableListOf<String>()
    private var emptyDescCharHeight = 0f

    // Scrolling states
    private var scrollOffset = 0f
    private val scroller = OverScroller(context)
    private var lastTouchY = 0f
    private var isDragging = false
    private var velocityTracker: android.view.VelocityTracker? = null

    private val headerHeight: Float get() = 14f * density

    // Press & Swipe states
    private var pressedCardIndex = -1
    private var pressedUndoIndex = -1

    private var swipingLayoutIndex = -1
    private var swipeOffset = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isSwiping = false

    // Undo states
    private val pendingDeleteEntities = mutableSetOf<ClipboardEntity>()
    private val commitRunnables = mutableMapOf<ClipboardEntity, Runnable>()
    private val undoHandler = Handler(Looper.getMainLooper())

    init {
        calculateLayout()
    }

    private fun commitPendingDelete(entity: ClipboardEntity) {
        val runnable = commitRunnables.remove(entity)
        if (runnable != null) {
            undoHandler.removeCallbacks(runnable)
        }
        if (pendingDeleteEntities.remove(entity)) {
            onDeleteItem?.invoke(entity)
            refreshList()
        }
    }

    private fun undoPendingDelete(entity: ClipboardEntity) {
        val runnable = commitRunnables.remove(entity)
        if (runnable != null) {
            undoHandler.removeCallbacks(runnable)
        }
        pendingDeleteEntities.remove(entity)
        refreshList()
    }

    private fun commitAllPendingDeletes() {
        val list = pendingDeleteEntities.toList()
        pendingDeleteEntities.clear()
        for (runnable in commitRunnables.values) {
            undoHandler.removeCallbacks(runnable)
        }
        commitRunnables.clear()
        for (entity in list) {
            onDeleteItem?.invoke(entity)
        }
        refreshList()
    }

    private fun togglePin(text: String) {
        if (pinnedTexts.contains(text)) {
            pinnedTexts.remove(text)
        } else {
            pinnedTexts.add(text)
        }
        AppPreferences.setPinnedClipboardTexts(java.util.HashSet(pinnedTexts))
        refreshList()
    }

    private fun drawPinIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, paint: Paint, isSlash: Boolean = false) {
        val id = if (isSlash) "pin_off" else "pin"
        IconDrawer.draw(canvas, context, id, cx, cy, size, color)
    }

    private fun drawDeleteIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
        IconDrawer.draw(canvas, context, "delete", cx, cy, size, color)
    }

    private fun refreshList() {
        val pinnedList = mutableListOf<ClipboardEntity>()
        val todayList = mutableListOf<ClipboardEntity>()
        val olderList = mutableListOf<ClipboardEntity>()

        val now = System.currentTimeMillis()
        val oneDayMs = 24 * 60 * 60 * 1000L

        for (item in items) {
            if (pinnedTexts.contains(item.text)) {
                pinnedList.add(item)
            } else if (now - item.timestamp < oneDayMs) {
                todayList.add(item)
            } else {
                olderList.add(item)
            }
        }

        displayItems.clear()
        if (pinnedList.isNotEmpty()) {
            displayItems.add(DisplayItem.Header("Đã ghim"))
            displayItems.addAll(pinnedList.map { DisplayItem.Clipboard(it, true, pendingDeleteEntities.contains(it)) })
        }
        if (todayList.isNotEmpty()) {
            displayItems.add(DisplayItem.Header("Hôm nay"))
            displayItems.addAll(todayList.map { DisplayItem.Clipboard(it, false, pendingDeleteEntities.contains(it)) })
        }
        if (olderList.isNotEmpty()) {
            displayItems.add(DisplayItem.Header("Cũ hơn"))
            displayItems.addAll(olderList.map { DisplayItem.Clipboard(it, false, pendingDeleteEntities.contains(it)) })
        }

        calculateLayout()
        invalidate()
    }

    private fun clampScrollOffset() {
        val contentTop = headerHeight
        val contentHeight = max(0f, height - contentTop)
        val maxScroll = max(0f, totalContentHeight - contentHeight)
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
    }

    private fun calculateLayout() {
        itemLayouts.clear()
        emptyDescLines.clear()
        if (width <= 0) return

        if (displayItems.isEmpty()) {
            textPaint.textSize = 10.5f * density
            textPaint.typeface = normalTypeface
            val descText = "Nội dung bạn sao chép sẽ tự động xuất hiện tại đây để dán cực nhanh."
            val descAvailW = width - 32f * density
            val words = descText.split(" ")
            var currentLine = StringBuilder()
            for (word in words) {
                val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
                if (textPaint.measureText(testLine) <= descAvailW) {
                    currentLine.append(if (currentLine.isEmpty()) word else " $word")
                } else {
                    if (currentLine.isNotEmpty()) {
                        emptyDescLines.add(currentLine.toString())
                    }
                    currentLine = StringBuilder(word)
                }
            }
            if (currentLine.isNotEmpty()) {
                emptyDescLines.add(currentLine.toString())
            }
            val fm = textPaint.fontMetrics
            emptyDescCharHeight = fm.descent - fm.ascent
            return
        }

        // Precalculate scrollable list items coordinates and pre-wrap text
        textPaint.textSize = 12f * density
        textPaint.typeface = normalTypeface
        val fm = textPaint.fontMetrics
        val charHeight = fm.descent - fm.ascent
        val lineSpacing = 2f * density

        var currentY = 0f
        for (item in displayItems) {
            when (item) {
                is DisplayItem.Header -> {
                    val itemHeight = 28f * density
                    val top = currentY
                    val bottom = currentY + itemHeight
                    itemLayouts.add(ItemLayout(item, top, bottom))
                    currentY += itemHeight
                }
                is DisplayItem.Clipboard -> {
                    val itemHeight = 58f * density // 52dp card + 3dp top/bottom margins
                    val top = currentY
                    val bottom = currentY + itemHeight
                    
                    val layout = ItemLayout(item, top, bottom)
                    
                    val cardMarginHorizontal = 8f * density
                    val cardMarginVertical = 3f * density
                    
                    layout.cardRect.set(
                        cardMarginHorizontal,
                        top + cardMarginVertical,
                        width - cardMarginHorizontal,
                        bottom - cardMarginVertical
                    )
                    
                    if (item.isPendingDelete) {
                        val btnWidth = 70f * density
                        val undoRight = layout.cardRect.right - 12f * density
                        val undoLeft = undoRight - btnWidth
                        layout.undoRect.set(
                            undoLeft,
                            layout.cardRect.top,
                            undoRight,
                            layout.cardRect.bottom
                        )
                    } else {
                        var startX = layout.cardRect.left + 12f * density
                        if (item.isPinned) {
                            startX += 16f * density
                        }
                        layout.textStartX = startX
                        val availW = layout.cardRect.right - startX - 12f * density
                        val rawText = item.entity.text

                        // Pre-truncate extremely long text to prevent slow parsing on giant strings (e.g. Base64, raw dumps)
                        val maxPreviewChars = 240
                        val isTextTruncated = rawText.length > maxPreviewChars
                        val textToProcess = if (isTextTruncated) rawText.substring(0, maxPreviewChars) else rawText

                        val maxLines = 2
                        val paragraphs = textToProcess.split("\n")
                        outer@ for (para in paragraphs) {
                            var remaining = para.trimEnd()
                            if (remaining.isEmpty()) continue
                            
                            while (remaining.isNotEmpty() && layout.lines.size < maxLines) {
                                val fitCount = textPaint.breakText(remaining, true, availW, null)
                                if (fitCount <= 0) break
                                if (fitCount >= remaining.length) {
                                    layout.lines.add(remaining)
                                    break
                                } else {
                                    // Try breaking at word boundary if within a reasonable distance
                                    var breakIdx = fitCount
                                    val lastSpace = remaining.lastIndexOf(' ', breakIdx)
                                    if (lastSpace > 0 && lastSpace >= breakIdx - 12) {
                                        breakIdx = lastSpace
                                    }
                                    layout.lines.add(remaining.substring(0, breakIdx).trimEnd())
                                    remaining = remaining.substring(breakIdx).trimStart()
                                }
                            }
                            if (layout.lines.size >= maxLines) break@outer
                        }

                        if (layout.lines.isEmpty() && textToProcess.isNotEmpty()) {
                            val fit = textPaint.breakText(textToProcess, true, availW, null)
                            if (fit > 0) {
                                layout.lines.add(textToProcess.substring(0, fit))
                            }
                        }

                        val hasMore = isTextTruncated || paragraphs.size > layout.lines.size || (layout.lines.size == maxLines && textToProcess.length > layout.lines.sumOf { it.length } + 3)
                        while (layout.lines.size > maxLines) {
                            layout.lines.removeAt(layout.lines.size - 1)
                        }

                        if (hasMore && layout.lines.isNotEmpty()) {
                            val lastIdx = layout.lines.size - 1
                            val lastLine = layout.lines[lastIdx]
                            val ellipsis = "…"
                            val ellipsisW = textPaint.measureText(ellipsis)
                            val availForLast = (availW - ellipsisW).coerceAtLeast(0f)
                            val fitCount = textPaint.breakText(lastLine, true, availForLast, null)
                            val trimmed = if (fitCount < lastLine.length) lastLine.substring(0, fitCount).trimEnd() else lastLine
                            layout.lines[lastIdx] = "$trimmed$ellipsis"
                        }

                        val totalTextHeight = if (layout.lines.size == 2) charHeight * 2 + lineSpacing else charHeight
                        layout.textStartY = layout.cardRect.centerY() - totalTextHeight / 2f - fm.ascent
                        layout.charHeight = charHeight
                        layout.lineSpacing = lineSpacing
                    }
                    
                    itemLayouts.add(layout)
                    currentY += itemHeight
                }
            }
        }
        totalContentHeight = currentY
        clampScrollOffset()
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

        // 1. Background Fill
        canvas.drawColor(panelBgColor)

        // 3. Draw Content (clipped and translated)
        val contentTop = headerHeight
        canvas.save()
        canvas.clipRect(0f, contentTop, width.toFloat(), height.toFloat())
        canvas.translate(0f, contentTop - scrollOffset)

        if (displayItems.isEmpty()) {
            val contentHeight = height - contentTop
            val centerY = contentHeight / 2f
            
            // Icon
            val iconSize = 56f * density
            val iconCenterY = centerY - 34f * density
            IconDrawer.draw(canvas, context, R.drawable.ic_clipboard, width / 2f, iconCenterY, 
                iconSize, subTextColor)
            
            // Title
            textPaint.color = textColor
            textPaint.textSize = 14f * density
            textPaint.typeface = boldTypeface
            textPaint.textAlign = Paint.Align.CENTER
            val titleY = iconCenterY + iconSize / 2f + 16f * density
            canvas.drawText("Bộ nhớ tạm trống", width / 2f, titleY, textPaint)
            
            // Description
            textPaint.color = subTextColor
            textPaint.textSize = 10.5f * density
            textPaint.typeface = normalTypeface
            
            var drawY = titleY + 16f * density + 10f * density
            for (line in emptyDescLines) {
                canvas.drawText(line, width / 2f, drawY, textPaint)
                drawY += emptyDescCharHeight + 2f * density
            }
        } else {
            val viewportTop = scrollOffset
            val viewportBottom = scrollOffset + (height - contentTop)

            for (index in itemLayouts.indices) {
                val layout = itemLayouts[index]
                if (layout.bottom < viewportTop || layout.top > viewportBottom) continue

                when (val item = layout.item) {
                    is DisplayItem.Header -> {
                        textPaint.color = activeAccentColor
                        textPaint.textSize = 10f * density
                        textPaint.typeface = boldTypeface
                        textPaint.textAlign = Paint.Align.LEFT
                        val title = item.title.uppercase()
                        val baseline = KeyboardUtils.centerBaselineY((layout.top + layout.bottom) / 2f, textPaint)
                        canvas.drawText(title, 12f * density, baseline, textPaint)
                    }
                    is DisplayItem.Clipboard -> {
                        val isPendingDelete = item.isPendingDelete
                        
                        if (isPendingDelete) {
                            // Draw pending delete (Undo) state
                            val pendingDeleteColor = if (isDark) 0x1AFFFFFF.toInt() else 0x0D000000.toInt()
                            KeyRenderer.drawFlatRoundedRect(
                                canvas = canvas,
                                rect = layout.cardRect,
                                cornerRadius = 10f * density,
                                color = pendingDeleteColor,
                                style = Paint.Style.FILL
                            )

                            // Deleted banner text on the left
                            textPaint.textSize = 12f * density
                            textPaint.typeface = normalTypeface
                            textPaint.color = subTextColor
                            textPaint.textAlign = Paint.Align.LEFT
                            val textX = layout.cardRect.left + 12f * density
                            val textBaseline = KeyboardUtils.centerBaselineY(layout.cardRect, textPaint)
                            canvas.drawText("Đã xóa mục này", textX, textBaseline, textPaint)

                            // Undo button on the right
                            val isUndoPressed = (pressedUndoIndex == index)
                            textPaint.textSize = 13f * density
                            textPaint.typeface = boldTypeface
                            textPaint.color = if (isUndoPressed) keyPressedBgColor else activeAccentColor
                            textPaint.textAlign = Paint.Align.RIGHT
                            canvas.drawText("Hoàn tác", layout.undoRect.right, textBaseline, textPaint)
                        } else {
                            // Normal active clipboard card
                            val currentSwipeOffset = if (swipingLayoutIndex == index) swipeOffset else 0f
                            
                            // 3a. Draw swipe action background behind card
                            if (currentSwipeOffset != 0f) {
                                canvas.save()
                                canvas.clipRect(layout.cardRect)
                                
                                val centerY = layout.cardRect.centerY()
                                val iconSize = 14f * density
                                val textBaseline = KeyboardUtils.centerBaselineY(centerY, textPaint)
                                
                                if (currentSwipeOffset > 0f) {
                                    // Swipe right: Pin/Unpin (Blue background)
                                    KeyRenderer.drawFlatRoundedRect(
                                        canvas = canvas,
                                        rect = layout.cardRect,
                                        cornerRadius = 10f * density,
                                        color = 0xFF3B82F6.toInt(),
                                        style = Paint.Style.FILL
                                    )
                                    
                                    val isPinned = item.isPinned
                                    val swipeText = if (isPinned) "Bỏ ghim" else "Ghim"
                                    
                                    // Draw Pin icon
                                    val iconCx = layout.cardRect.left + 24f * density
                                    drawPinIcon(canvas, iconCx, centerY, iconSize, Color.WHITE, paint, isSlash = isPinned)
                                    
                                    // Draw text next to the icon
                                    textPaint.textSize = 13f * density
                                    textPaint.typeface = boldTypeface
                                    textPaint.color = Color.WHITE
                                    textPaint.textAlign = Paint.Align.LEFT
                                    canvas.drawText(swipeText, layout.cardRect.left + 38f * density, textBaseline, textPaint)
                                } else {
                                    // Swipe left: Delete (Red background)
                                    KeyRenderer.drawFlatRoundedRect(
                                        canvas = canvas,
                                        rect = layout.cardRect,
                                        cornerRadius = 10f * density,
                                        color = 0xFFEF4444.toInt(),
                                        style = Paint.Style.FILL
                                    )
                                    
                                    // Draw delete label
                                    textPaint.textSize = 13f * density
                                    textPaint.typeface = boldTypeface
                                    textPaint.color = Color.WHITE
                                    textPaint.textAlign = Paint.Align.RIGHT
                                    
                                    val textX = layout.cardRect.right - 16f * density
                                    canvas.drawText("Xóa", textX, textBaseline, textPaint)
                                    
                                    // Draw Delete icon to the left of the text
                                    val textW = textPaint.measureText("Xóa")
                                    val iconCx = textX - textW - 14f * density
                                    drawDeleteIcon(canvas, iconCx, centerY, iconSize, Color.WHITE, paint)
                                }
                                
                                canvas.restore()
                            }
                            
                            // 3b. Draw the card shifted by currentSwipeOffset
                            canvas.save()
                            canvas.translate(currentSwipeOffset, 0f)
                            
                            // Card Background
                            val isCardPressed = (pressedCardIndex == index)
                            val cardBgColor = if (isCardPressed) keyPressedBgColor else keyBgColor
                            KeyRenderer.drawFlatRoundedRect(
                                canvas = canvas,
                                rect = layout.cardRect,
                                cornerRadius = 10f * density,
                                color = cardBgColor,
                                style = Paint.Style.FILL
                            )

                            // If pinned, draw the pinned indicator
                            if (item.isPinned) {
                                val pinX = layout.cardRect.left + 12f * density + 5f * density
                                drawPinIcon(canvas, pinX, layout.cardRect.centerY(), 12f * density, activeAccentColor, paint)
                            }

                            // Text to draw using pre-wrapped cached lines
                            textPaint.textSize = 12f * density
                            textPaint.typeface = normalTypeface
                            textPaint.textAlign = Paint.Align.LEFT
                            textPaint.color = textColor

                            for (i in layout.lines.indices) {
                                val drawY = layout.textStartY + i * (layout.charHeight + layout.lineSpacing)
                                canvas.drawText(layout.lines[i], layout.textStartX, drawY, textPaint)
                            }
                            
                            canvas.restore()
                        }
                    }
                }
            }
        }

        canvas.restore()
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
                isSwiping = false
                swipingLayoutIndex = -1
                swipeOffset = 0f
                initialTouchX = x
                initialTouchY = y
                
                val contentX = x
                val contentY = y - headerHeight + scrollOffset
                
                pressedCardIndex = -1
                pressedUndoIndex = -1
                
                for (i in itemLayouts.indices) {
                    val layout = itemLayouts[i]
                    when (val item = layout.item) {
                        is DisplayItem.Clipboard -> {
                            if (item.isPendingDelete) {
                                if (layout.undoRect.contains(contentX, contentY)) {
                                    pressedUndoIndex = i
                                    break
                                }
                            } else {
                                if (layout.cardRect.contains(contentX, contentY)) {
                                    pressedCardIndex = i
                                    swipingLayoutIndex = i
                                    break
                                }
                            }
                        }
                        is DisplayItem.Header -> {}
                    }
                }
                invalidate()
            }
            
            MotionEvent.ACTION_MOVE -> {
                val deltaY = lastTouchY - y
                lastTouchY = y

                val touchSlop = 8f * density
                
                if (isSwiping) {
                    val deltaX = x - initialTouchX
                    swipeOffset = deltaX
                    invalidate()
                } else {
                    val deltaX = x - initialTouchX
                    val deltaYTotal = y - initialTouchY
                    
                    if (swipingLayoutIndex != -1 && Math.abs(deltaX) > touchSlop && Math.abs(deltaX) > Math.abs(deltaYTotal) * 1.5f) {
                        isSwiping = true
                        pressedCardIndex = -1
                        isDragging = false
                        initialTouchX = x
                        swipeOffset = 0f
                        invalidate()
                    } else if (Math.abs(deltaYTotal) > touchSlop && !isSwiping) {
                        swipingLayoutIndex = -1
                        if (!isDragging && y >= headerHeight) {
                            isDragging = true
                            pressedCardIndex = -1
                            invalidate()
                        }
                    }

                    if (isDragging) {
                        scrollOffset += deltaY
                        clampScrollOffset()
                        invalidate()
                    } else {
                        val contentX = x
                        val contentY = y - headerHeight + scrollOffset
                        
                        if (pressedCardIndex != -1) {
                            val layout = itemLayouts.getOrNull(pressedCardIndex)
                            if (layout == null || !layout.cardRect.contains(contentX, contentY)) {
                                pressedCardIndex = -1
                                invalidate()
                            }
                        }
                        if (pressedUndoIndex != -1) {
                            val layout = itemLayouts.getOrNull(pressedUndoIndex)
                            if (layout == null || !layout.undoRect.contains(contentX, contentY)) {
                                pressedUndoIndex = -1
                                invalidate()
                            }
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isSwiping) {
                    val trackedLayout = itemLayouts.getOrNull(swipingLayoutIndex)
                    if (trackedLayout != null && trackedLayout.item is DisplayItem.Clipboard) {
                        val item = trackedLayout.item as DisplayItem.Clipboard
                        val threshold = width * 0.25f
                        if (swipeOffset > threshold) {
                            // Swipe Right -> Pin/Unpin
                            togglePin(item.entity.text)
                        } else if (swipeOffset < -threshold) {
                            // Swipe Left -> Delete
                            val entity = item.entity
                            pendingDeleteEntities.add(entity)
                            val runnable = Runnable {
                                commitPendingDelete(entity)
                            }
                            commitRunnables[entity] = runnable
                            undoHandler.postDelayed(runnable, 4000L)
                            refreshList()
                        }
                    }
                    isSwiping = false
                    swipingLayoutIndex = -1
                    swipeOffset = 0f
                    invalidate()
                } else if (!isDragging) {
                    if (pressedUndoIndex != -1) {
                        val layout = itemLayouts.getOrNull(pressedUndoIndex)
                        val contentX = x
                        val contentY = y - headerHeight + scrollOffset
                        if (layout != null && layout.undoRect.contains(contentX, contentY)) {
                            val item = layout.item as? DisplayItem.Clipboard
                            if (item != null) {
                                undoPendingDelete(item.entity)
                            }
                        }
                    } else if (pressedCardIndex != -1) {
                        val layout = itemLayouts.getOrNull(pressedCardIndex)
                        val contentX = x
                        val contentY = y - headerHeight + scrollOffset
                        if (layout != null && layout.cardRect.contains(contentX, contentY)) {
                            val item = layout.item as DisplayItem.Clipboard
                            onSelect?.invoke(item.entity.text)
                        }
                    }
                } else {
                    velocityTracker?.let { tracker ->
                        tracker.computeCurrentVelocity(1000)
                        val velocityY = tracker.yVelocity
                        val contentHeight = max(0f, height - headerHeight)
                        val maxScroll = max(0f, totalContentHeight - contentHeight)
                        scroller.fling(
                            0, scrollOffset.toInt(),
                            0, -velocityY.toInt(),
                            0, 0,
                            0, maxScroll.toInt()
                        )
                        postInvalidateOnAnimation()
                    }
                }

                pressedCardIndex = -1
                pressedUndoIndex = -1
                isDragging = false
                isSwiping = false
                swipingLayoutIndex = -1
                swipeOffset = 0f
                velocityTracker?.recycle()
                velocityTracker = null
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedCardIndex = -1
                pressedUndoIndex = -1
                isDragging = false
                isSwiping = false
                swipingLayoutIndex = -1
                swipeOffset = 0f
                velocityTracker?.recycle()
                velocityTracker = null
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        commitAllPendingDeletes()
        undoHandler.removeCallbacksAndMessages(null)
    }
}
