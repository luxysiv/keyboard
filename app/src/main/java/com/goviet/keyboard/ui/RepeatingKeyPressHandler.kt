package com.goviet.keyboard.ui

import android.os.Handler
import android.os.Looper

/**
 * Utility class to manage repeating key press logic (e.g., holding backspace or cursor keys).
 * Standardizes the initial delay (350ms) and repeating intervals across keyboard views.
 */
class RepeatingKeyPressHandler(
    private val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    private val defaultIntervalMs: Long = DEFAULT_INTERVAL_MS,
    private val intervalProvider: ((repeatCount: Int) -> Long)? = null,
    private val onRepeat: () -> Unit
) {
    companion object {
        const val DEFAULT_INITIAL_DELAY_MS = 350L
        const val DEFAULT_INTERVAL_MS = 60L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isHolding = false
    private var repeatCount = 0

    private val runnable = object : Runnable {
        override fun run() {
            if (isHolding) {
                repeatCount++
                onRepeat()
                val nextInterval = intervalProvider?.invoke(repeatCount) ?: defaultIntervalMs
                handler.postDelayed(this, nextInterval)
            }
        }
    }

    fun start() {
        stop()
        isHolding = true
        repeatCount = 0
        handler.postDelayed(runnable, initialDelayMs)
    }

    fun stop() {
        isHolding = false
        repeatCount = 0
        handler.removeCallbacks(runnable)
    }

    fun isHolding(): Boolean = isHolding
}
