package com.goviet.keyboard.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Classification of input boundaries for consistent word breaks, commit triggers,
 * sentence endings, and auto-capitalization across the engine and UI.
 */
enum class InputBoundary {
    NONE,
    WHITESPACE,
    WORD_SEPARATOR,
    SENTENCE_TERMINATOR,
    HARD_BREAK
}

object BoundaryClassifier {

    fun classify(c: Char): InputBoundary {
        return when {
            c == '\n' || c == '\r' -> InputBoundary.HARD_BREAK
            c.isWhitespace() -> InputBoundary.WHITESPACE
            c == '.' || c == '?' || c == '!' -> InputBoundary.SENTENCE_TERMINATOR
            c == ',' || c == ';' || c == ':' || c == '-' || c == '/' || c == '(' || c == ')' ||
            c == '[' || c == ']' || c == '{' || c == '}' || c == '\"' || c == '\'' || c == '«' ||
            c == '»' || c == '`' || c == '~' || c == '@' || c == '#' || c == '$' || c == '%' ||
            c == '^' || c == '&' || c == '*' || c == '_' || c == '=' || c == '+' || c == '|' ||
            c == '\\' || c == '<' || c == '>' -> InputBoundary.WORD_SEPARATOR
            else -> InputBoundary.NONE
        }
    }

    fun isBoundaryChar(c: Char): Boolean {
        return classify(c) != InputBoundary.NONE
    }

    fun isWhitespace(c: Char): Boolean = classify(c) == InputBoundary.WHITESPACE

    fun isHardBreak(c: Char): Boolean = classify(c) == InputBoundary.HARD_BREAK

    fun isSentenceTerminator(c: Char): Boolean = classify(c) == InputBoundary.SENTENCE_TERMINATOR

    fun isSentenceTerminator(key: String): Boolean {
        if (key == "ENTER" || key == "\n" || key == "\r") return true
        if (key.length == 1) return isSentenceTerminator(key[0])
        return false
    }

    fun isWordSeparator(c: Char): Boolean =
        classify(c) == InputBoundary.WORD_SEPARATOR

    fun isBoundary(key: String): Boolean {
        if (key.isEmpty()) return false
        if (key == "SPACE" || key == "ENTER") return true
        if (key.length == 1) {
            return isBoundaryChar(key[0])
        }
        return false
    }
}

/**
 * Controller for managing shift / caps lock state cleanly and consistently.
 * Values:
 * 0 = Lowercase
 * 1 = Single uppercase shift
 * 2 = Caps lock
 */
class ShiftStateController(
    private val _state: MutableStateFlow<Int> = MutableStateFlow(0)
) {
    val state: StateFlow<Int> = _state.asStateFlow()

    var value: Int
        get() = _state.value
        set(v) { _state.value = v }

    val isShifted: Boolean get() = _state.value > 0
    val isSingleShift: Boolean get() = _state.value == 1
    val isCapsLock: Boolean get() = _state.value == 2

    /**
     * Consume single shift state after a letter key is typed or committed.
     * Keeps Caps Lock intact if active.
     */
    fun consumeSingleShift() {
        if (_state.value == 1) {
            _state.value = 0
        }
    }

    /**
     * Triggered when sentence start condition is detected.
     * Sets single shift if not already in Caps Lock.
     */
    fun onSentenceStartDetected() {
        if (_state.value != 2) {
            _state.value = 1
        }
    }

    /**
     * Triggered when cursor moves away from sentence start or auto-capitalize is lost.
     */
    fun onSentenceStartLost() {
        if (_state.value == 1) {
            _state.value = 0
        }
    }

    /**
     * Toggle shift key on soft keyboard key press.
     * Double-tap within doubleTapTimeoutMs turns on Caps Lock.
     */
    fun toggleShiftKey(now: Long, lastShiftTime: Long, doubleTapTimeoutMs: Long = 300L): Long {
        return if (_state.value == 2) {
            _state.value = 0
            0L
        } else if (now - lastShiftTime < doubleTapTimeoutMs) {
            _state.value = 2 // Caps Lock
            0L
        } else {
            _state.value = if (_state.value == 0) 1 else 0
            now
        }
    }

    fun forceCapsLock() {
        _state.value = 2
    }

    fun reset() {
        _state.value = 0
    }
}
