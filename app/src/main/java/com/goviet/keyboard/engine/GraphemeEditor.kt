package com.goviet.keyboard.engine

import java.text.BreakIterator

/**
 * Unicode grapheme cluster manipulation — pure text logic shared by the
 * backspace handler (IME layer) and the composing engine's display-level
 * backspace.
 */
object GraphemeEditor {

    private val threadLocalBreakIterator = ThreadLocal.withInitial {
        BreakIterator.getCharacterInstance()
    }

    private fun getIterator(text: String): BreakIterator {
        val iterator = threadLocalBreakIterator.get() ?: BreakIterator.getCharacterInstance()
        iterator.setText(text)
        return iterator
    }

    fun previousBoundary(text: String, cursorIndex: Int): Int {
        if (text.isEmpty() || cursorIndex <= 0) return 0
        val clampedCursor = cursorIndex.coerceIn(0, text.length)
        val prev = getIterator(text).preceding(clampedCursor)
        return if (prev != BreakIterator.DONE && prev >= 0) prev else 0
    }

    fun nextBoundary(text: String, cursorIndex: Int): Int {
        if (text.isEmpty() || cursorIndex >= text.length) return text.length
        val clampedCursor = cursorIndex.coerceIn(0, text.length)
        val next = getIterator(text).following(clampedCursor)
        return if (next != BreakIterator.DONE && next >= 0) next else text.length
    }

    fun deleteBackward(text: String, cursorIndex: Int): Pair<String, Int> {
        if (text.isEmpty() || cursorIndex <= 0) return Pair(text, 0)
        val clampedCursor = cursorIndex.coerceIn(0, text.length)
        val start = previousBoundary(text, clampedCursor)
        if (start >= clampedCursor) return Pair(text, clampedCursor)
        val newLength = text.length - (clampedCursor - start)
        val sb = StringBuilder(newLength)
        sb.append(text, 0, start)
        sb.append(text, clampedCursor, text.length)
        return Pair(sb.toString(), start)
    }

    fun deleteForward(text: String, cursorIndex: Int): Pair<String, Int> {
        if (text.isEmpty() || cursorIndex >= text.length) return Pair(text, cursorIndex.coerceIn(0, text.length))
        val clampedCursor = cursorIndex.coerceIn(0, text.length)
        val end = nextBoundary(text, clampedCursor)
        if (end <= clampedCursor) return Pair(text, clampedCursor)
        val newLength = text.length - (end - clampedCursor)
        val sb = StringBuilder(newLength)
        sb.append(text, 0, clampedCursor)
        sb.append(text, end, text.length)
        return Pair(sb.toString(), clampedCursor)
    }

    fun getBackwardGraphemeLength(text: String, cursorIndex: Int = text.length): Int {
        if (text.isEmpty() || cursorIndex <= 0) return 0
        return cursorIndex.coerceIn(0, text.length) - previousBoundary(text, cursorIndex.coerceIn(0, text.length))
    }

    fun getForwardGraphemeLength(text: String, cursorIndex: Int = 0): Int {
        if (text.isEmpty() || cursorIndex >= text.length) return 0
        val clamped = cursorIndex.coerceIn(0, text.length)
        return nextBoundary(text, clamped) - clamped
    }
}
