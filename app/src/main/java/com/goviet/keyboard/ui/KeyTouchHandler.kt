package com.goviet.keyboard.ui

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View

/**
 * Handles all touch/gesture logic for the keyboard grid.
 * Extracted from StandardLetterGridView for separation of concerns.
 *
 * Responsibilities:
 * - Multi-touch tracking (pointer id → key mapping)
 * - Key hover slide with long-press timer reset
 * - Long press detection + popup display
 * - Spacebar cursor swipe
 * - Backspace repeat + swipe-to-delete
 * - Key release dispatch
 */
class KeyTouchHandler(
    private val keys: () -> List<Key>,
    private val keyPopup: KeyPopupWindow,
    private val onKey: (String) -> Unit,
    private val onSwitchToSymbols: () -> Unit,
    private val onSwitchPage: () -> Unit,
    private val invalidate: () -> Unit,
    private val density: Float,
    private val isDark: () -> Boolean,
    private val currentTheme: () -> KeyboardTheme,
    private val parentWidth: () -> Int,
    private val parentHeight: () -> Int
) {
    private val activePointerKeys = mutableMapOf<Int, Key>()
    private val trackedKeySet = mutableSetOf<Key>()
    private var activeTouchedKey: Key? = null
    private var isLongPressed = false

    private var startX = 0f
    private var startY = 0f

    // Spacebar cursor swipe
    private var cursorSwipeStartX = 0f
    private var cursorLastTriggerX = 0f
    private var isCursorSwipeActive = false

    // Backspace swipe
    private var backspaceStartX = 0f
    private var backspaceSelectCount = 0
    private var isBackspaceSwipeActive = false
    private var hasBackspaceTriggered = false
    private val backspaceRepeatHandler = RepeatingKeyPressHandler {
        onKey("BACKSPACE")
        hasBackspaceTriggered = true
    }

    // Long press
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var activePopupOptionIndex = -1
    private val longPressRunnable = Runnable {
        val key = activeTouchedKey ?: return@Runnable
        val opts = key.longPressOptions; if (opts != null && opts.isNotEmpty()) {
            isLongPressed = true
            keyPopup.dismiss()
            activePopupOptionIndex = key.longPressDefaultIndex
            keyPopup.showLongPress(
                parentView!!, opts, activePopupOptionIndex,
                isDark(), currentTheme(), key.rect
            )
            invalidate()
        } else if (key.code == "SHIFT") {
            isLongPressed = true
            onKey("SHIFT_LONG")
            invalidate()
        }
    }

    private var parentView: View? = null

    private val previewExcludedCodes = setOf(
        "SHIFT", "BACKSPACE", "ENTER", "SPACE", "SYM", "ABC", "SWITCH_PAGE"
    )

    fun attachView(view: View) {
        parentView = view
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val px = event.getX(index)
                val py = event.getY(index)

                val key = findKeyByCoordinates(px, py)
                if (key != null) {
                    activePointerKeys[id] = key
                    trackedKeySet.add(key)
                    key.isPressed = true

                    if (action == MotionEvent.ACTION_DOWN) {
                        startX = px
                        startY = py
                        isLongPressed = false
                        isCursorSwipeActive = false
                        isBackspaceSwipeActive = false
                        backspaceRepeatHandler.stop()
                        activeTouchedKey = key

                        if (key.code == "SPACE") {
                            cursorSwipeStartX = px
                            cursorLastTriggerX = px
                        }

                        if (key.code == "BACKSPACE") {
                            backspaceStartX = px
                            backspaceSelectCount = 0
                            isBackspaceSwipeActive = true
                            hasBackspaceTriggered = false
                            backspaceRepeatHandler.start()
                        }

                        if (isPreviewableKey(key)) {
                            keyPopup.showPreview(parentView!!, key.label, isDark(), currentTheme(), key.rect)
                        }

                        longPressHandler.postDelayed(longPressRunnable, 350)
                    }

                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val px = event.getX(i)
                    val py = event.getY(i)

                    val trackedKey = activePointerKeys[id]
                    if (trackedKey != null) {
                        if (trackedKey == activeTouchedKey) {
                            val deltaX = Math.abs(px - startX)
                            val deltaY = Math.abs(py - startY)

                            if (isLongPressed) {
                                val options = trackedKey.longPressOptions
                                if (options != null && options.isNotEmpty()) {
                                    val defaultIdx = trackedKey.longPressDefaultIndex
                                    val step = 32 * density
                                    val dragOffset = px - startX
                                    val hoveredIdx = (defaultIdx + (dragOffset / step).toInt()).coerceIn(0, options.size - 1)
                                    if (hoveredIdx != activePopupOptionIndex) {
                                        activePopupOptionIndex = hoveredIdx
                                        keyPopup.updateHoverIndex(hoveredIdx)
                                    }
                                }
                            } else {
                                val currentHovered = findKeyByCoordinates(px, py)
                                if (currentHovered != null && currentHovered != trackedKey && !isCursorSwipeActive && !isBackspaceSwipeActive) {
                                    trackedKey.isPressed = false
                                    currentHovered.isPressed = true
                                    trackedKeySet.remove(trackedKey)
                                    activePointerKeys[id] = currentHovered
                                    trackedKeySet.add(currentHovered)
                                    activeTouchedKey = currentHovered

                                    longPressHandler.removeCallbacks(longPressRunnable)
                                    val hoveredOpts = currentHovered.longPressOptions
                                    if (hoveredOpts != null && hoveredOpts.isNotEmpty()) {
                                        longPressHandler.postDelayed(longPressRunnable, 350)
                                    }

                                    if (isPreviewableKey(currentHovered)) {
                                        keyPopup.showPreview(parentView!!, currentHovered.label, isDark(), currentTheme(), currentHovered.rect)
                                    } else {
                                        keyPopup.dismiss()
                                    }

                                    invalidate()
                                } else if (currentHovered == null) {
                                    longPressHandler.removeCallbacks(longPressRunnable)
                                    keyPopup.dismiss()
                                    trackedKey.isPressed = false
                                    activeTouchedKey = null
                                    invalidate()
                                }

                                if (trackedKey.code == "SPACE") {
                                    val swipeDelta = px - cursorSwipeStartX
                                    if (Math.abs(swipeDelta) > 20f * density) {
                                        isCursorSwipeActive = true
                                    }

                                    if (isCursorSwipeActive) {
                                        val triggerStep = 10f * density
                                        val diffX = px - cursorLastTriggerX
                                        if (diffX > triggerStep) {
                                            onKey("RIGHT_MOVE")
                                            cursorLastTriggerX = px
                                        } else if (diffX < -triggerStep) {
                                            onKey("LEFT_MOVE")
                                            cursorLastTriggerX = px
                                        }
                                    }
                                }

                                if (trackedKey.code == "BACKSPACE" && isBackspaceSwipeActive) {
                                    val swipeDeltaX = px - backspaceStartX
                                    if (Math.abs(swipeDeltaX) > 10f * density) {
                                        backspaceRepeatHandler.stop()
                                    }

                                    if (swipeDeltaX < -30f * density) {
                                        val wordsToDelete = (-swipeDeltaX / (30f * density)).toInt()
                                        if (wordsToDelete > backspaceSelectCount) {
                                            val diff = wordsToDelete - backspaceSelectCount
                                            backspaceSelectCount = wordsToDelete
                                            repeat(diff) {
                                                onKey("DELETE_WORD")
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            val currentHovered = findKeyByCoordinates(px, py)
                            if (currentHovered != null && currentHovered != trackedKey && !trackedKeySet.contains(currentHovered)) {
                                trackedKey.isPressed = false
                                currentHovered.isPressed = true
                                trackedKeySet.remove(trackedKey)
                                activePointerKeys[id] = currentHovered
                                trackedKeySet.add(currentHovered)
                                invalidate()
                            }
                        }
                    } else {
                        val currentHovered = findKeyByCoordinates(px, py)
                        if (currentHovered != null && !trackedKeySet.contains(currentHovered)) {
                            activePointerKeys[id] = currentHovered
                            trackedKeySet.add(currentHovered)
                            currentHovered.isPressed = true
                            invalidate()
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val trackedKey = activePointerKeys.remove(id)
                if (trackedKey != null) {
                    trackedKey.isPressed = false
                    trackedKeySet.remove(trackedKey)
                    if (trackedKey == activeTouchedKey) {
                        keyPopup.dismiss()
                    }
                    handleKeyRelease(trackedKey)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                backspaceRepeatHandler.stop()

                if (isLongPressed) {
                    val key = activeTouchedKey ?: activePointerKeys[event.getPointerId(0)]
                    if (key != null) {
                        val lpOpts = key.longPressOptions
                        if (lpOpts != null && activePopupOptionIndex in lpOpts.indices) {
                            onKey(lpOpts[activePopupOptionIndex])
                        } else {
                            val defaultIdx = key.longPressDefaultIndex
                            val lpOpts2 = key.longPressOptions
                            if (lpOpts2 != null && defaultIdx in lpOpts2.indices) {
                                onKey(lpOpts2[defaultIdx])
                            }
                        }
                    }
                    keyPopup.dismiss()

                    resetTouchState()
                    invalidate()
                    return true
                }

                val id = event.getPointerId(0)
                val trackedKey = activePointerKeys.remove(id)

                keyPopup.dismiss()

                if (trackedKey != null) {
                    trackedKey.isPressed = false
                    handleKeyRelease(trackedKey)
                } else activeTouchedKey?.let { key ->
                    key.isPressed = false
                    handleKeyRelease(key)
                }

                resetTouchState()
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                backspaceRepeatHandler.stop()
                resetTouchState()
                invalidate()
            }
        }
        return true
    }

    private fun handleKeyRelease(key: Key) {
        if (isLongPressed && key == activeTouchedKey) {
            val lpOpts = key.longPressOptions
            if (lpOpts != null && activePopupOptionIndex in lpOpts.indices) {
                onKey(lpOpts[activePopupOptionIndex])
            }
        } else if (isCursorSwipeActive && key.code == "SPACE") {
            // Sliding cursor handled, skip normal release dispatch
        } else if (key.code == "BACKSPACE" && backspaceSelectCount > 0) {
            // Sliding backspace delete handled, skip normal release dispatch
        } else {
            when (key.code) {
                "SHIFT" -> onKey("SHIFT")
                "BACKSPACE" -> {
                    if (!hasBackspaceTriggered && backspaceSelectCount == 0) {
                        onKey("BACKSPACE")
                    }
                }
                "SYM" -> onSwitchToSymbols()
                "SWITCH_PAGE" -> onSwitchPage()
                "ABC" -> onKey("ABC")
                "ENTER" -> onKey("ENTER")
                else -> {
                    val textVal = if (key.code == "SPACE") "SPACE" else key.code
                    onKey(textVal)
                }
            }
        }
    }

    private fun resetTouchState() {
        activePointerKeys.forEach { (_, key) -> key.isPressed = false }
        activePointerKeys.clear()
        trackedKeySet.clear()
        activeTouchedKey = null
        isLongPressed = false
        isCursorSwipeActive = false
        isBackspaceSwipeActive = false
        keyPopup.dismiss()
    }

    private fun isPreviewableKey(key: Key): Boolean = key.code !in previewExcludedCodes

    fun findKeyByCoordinates(x: Float, y: Float): Key? {
        for (key in keys()) {
            if (key.rect.contains(x, y)) {
                return key
            }
        }
        return null
    }

    fun cleanup() {
        backspaceRepeatHandler.stop()
        longPressHandler.removeCallbacksAndMessages(null)
    }
}
