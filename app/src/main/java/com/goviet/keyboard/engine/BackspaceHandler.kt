package com.goviet.keyboard.engine

import java.text.BreakIterator
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

// ============================================================
// WORD AT CURSOR
// ============================================================
data class WordAtCursor(
    val text: String,
    val startInEditor: Int,
    val endInEditor: Int,
    val cursorOffset: Int
)

// ============================================================
// GRAPHEME EDITOR (Unicode grapheme cluster manipulation)
// ============================================================
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

// ============================================================
// EDITED VIETNAMESE RECOGNIZER (simplified — no full re-parse)
// ============================================================
object EditedVietnameseRecognizer {
    private val BASE_VOWELS = setOf('a', 'ă', 'â', 'e', 'ê', 'i', 'y', 'o', 'ô', 'ơ', 'u', 'ư')
    private val TONED_VOWELS = setOf(
        'á', 'à', 'ả', 'ã', 'ạ', 'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ', 'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ',
        'é', 'è', 'ẻ', 'ẽ', 'ẹ', 'ế', 'ề', 'ể', 'ễ', 'ệ',
        'í', 'ì', 'ỉ', 'ĩ', 'ị',
        'ó', 'ò', 'ỏ', 'õ', 'ọ', 'ố', 'ồ', 'ổ', 'ỗ', 'ộ', 'ớ', 'ờ', 'ở', 'ỡ', 'ợ',
        'ú', 'ù', 'ủ', 'ũ', 'ụ', 'ứ', 'ừ', 'ử', 'ữ', 'ự',
        'ý', 'ỳ', 'ỷ', 'ỹ', 'ỵ'
    )
    private val NON_VN_LETTERS = setOf('f', 'j', 'z')

    fun canRecompose(word: String): Boolean {
        if (word.isEmpty()) return false
        val lower = word.lowercase()
        // Reject if contains f, j, z (non-Vietnamese letters in Telex)
        if (lower.any { it in NON_VN_LETTERS }) return false
        // Reject if has disjoint vowel clusters (V-C-V like "ana", "omo")
        if (hasDisjointVowelClusters(lower)) return false
        // Must have at least one vowel
        return lower.any { it in BASE_VOWELS || it in TONED_VOWELS }
    }

    private fun hasDisjointVowelClusters(word: String): Boolean {
        var vowelGroupCount = 0
        var inVowel = false
        for (c in word) {
            val isVowel = c in BASE_VOWELS || c in TONED_VOWELS
            if (isVowel) {
                if (!inVowel) {
                    vowelGroupCount++
                    inVowel = true
                }
            } else {
                inVowel = false
            }
        }
        return vowelGroupCount > 1
    }

    fun classify(word: String): CompositionMode {
        return if (canRecompose(word)) CompositionMode.VIETNAMESE else CompositionMode.LITERAL
    }
}

// ============================================================
// BACKSPACE HANDLER (deletion operations)
// ============================================================
class BackspaceHandler(
    private val controller: ImeInputConnectionController
) {

    // ============================================================
    // ENTRY POINTS
    // ============================================================

    fun handleBackspace(ic: InputConnection) {
        ic.beginBatchEdit()
        try {
            controller.lastKeyPressTime = System.currentTimeMillis()

            if (controller.hasRealSelection(ic)) {
                deleteSelection(ic)
                controller.clearState()
                controller.isSelecting = false
                controller.service.evaluateAutoShift()
                return
            }

            if (controller.composingRaw.isNotEmpty()) {
                performComposingBackspace(ic)
                controller.service.evaluateAutoShift()
                return
            }

            if (rollbackMacroExpansion(ic)) {
                controller.service.evaluateAutoShift()
                return
            }

            // Committed text, Gboard/Laban style: remove the whole preceding
            // Unicode grapheme cluster ('á' -> "", 'nguyễn' -> 'nguyễ').
            deleteLastGraphemeOrChar(ic)
            controller.service.evaluateAutoShift()
        } finally {
            ic.endBatchEdit()
        }
    }

    fun handleDeleteForward(ic: InputConnection) {
        ic.beginBatchEdit()
        try {
            controller.lastKeyPressTime = System.currentTimeMillis()

            if (controller.hasRealSelection(ic)) {
                deleteSelection(ic)
                controller.clearState()
                controller.isSelecting = false
                controller.service.evaluateAutoShift()
                return
            }

            if (controller.composingRaw.isNotEmpty()) {
                performComposingDeleteForward(ic)
                controller.service.evaluateAutoShift()
                return
            }

            deleteNextGraphemeOrChar(ic)
            controller.service.evaluateAutoShift()
        } finally {
            ic.endBatchEdit()
        }
    }

    fun handleDeleteWord(ic: InputConnection) {
        ic.beginBatchEdit()
        try {
            controller.lastExpandedMacro = null
            if (controller.composingRaw.isNotEmpty()) {
                // Delete the whole preedit (swipe/word-delete), then clear composing UI.
                val lastLen = controller.lastSetComposingText?.length ?: 0
                controller.resetComposingUI(ic, lastLen)
                controller.service.evaluateAutoShift()
                return
            }
            deleteLastWordInEditor(ic)
            controller.service.evaluateAutoShift()
        } finally {
            ic.endBatchEdit()
        }
    }

    // ============================================================
    // COMPOSING EDITS — single raw buffer + pure recompile
    // ============================================================

    /**
     * Backspace while composing — raw-buffer mutation + full replay.
     *
     * The preedit is a pure function of [composingRaw]: instead of keeping
     * per-keystroke state snapshots (the old C++-derived undo stack), we delete
     * exactly one raw keystroke before the caret and re-derive both the display
     * and the live state from the remaining buffer through the same replay path
     * used everywhere else. Display, caret and later keystrokes therefore always
     * agree with the raw buffer — the engine stays the single source of truth.
     */
    private fun performComposingBackspace(ic: InputConnection) {
        val raw = controller.composingRaw
        if (raw.isEmpty()) {
            deleteLastGraphemeOrChar(ic)
            return
        }

        val cursor = controller.composingCursorIndex
        if (cursor <= 0) {
            // Caret is at the very beginning of the preedit: the backspace must
            // hit committed text while the preedit itself stays untouched.
            deleteCommittedGraphemeBeforePreedit(ic)
            return
        }

        // Drop exactly one raw keystroke immediately before the caret.
        raw.deleteCharAt(cursor - 1)
        controller.composingCursorIndex = cursor - 1

        if (raw.isEmpty()) {
            resetPreeditToEmpty(ic)
            return
        }

        recomposePreedit(ic, midPreedit = controller.composingCursorIndex < raw.length)
    }

    private fun performComposingDeleteForward(ic: InputConnection) {
        val raw = controller.composingRaw
        val cursor = controller.composingCursorIndex
        if (cursor >= raw.length) {
            deleteNextGraphemeOrChar(ic)
            return
        }
        raw.deleteCharAt(cursor)
        if (raw.isEmpty()) {
            resetPreeditToEmpty(ic)
            return
        }
        recomposePreedit(ic, midPreedit = true)
    }

    /**
     * Re-derive the preedit after a raw mutation and keep the caret at the display
     * offset of the (possibly mid-preedit) raw caret.
     * The live syllable state is rebuilt from the raw buffer so subsequent
     * keystrokes continue from exactly the same state as the display.
     */
    private fun recomposePreedit(ic: InputConnection, midPreedit: Boolean) {
        val raw = controller.composingRaw
        if (raw.isEmpty()) {
            resetPreeditToEmpty(ic)
            return
        }
        val display: String
        if (controller.isVietnamese) {
            controller.inputEngine.replayRawToState(raw, controller.composingState)
            display = controller.composingState.toDisplayString(controller.inputEngine.options.oldTonePlacement)
        } else {
            // Literal preedit: passthrough text, never run through the Telex kernel.
            controller.composingState.reset()
            controller.composingState.rawSuffix = raw.toString()
            display = raw.toString()
        }
        replaceComposingText(ic, display)
        if (midPreedit && controller.composingStartInEditor >= 0) {
            controller.moveCursorTo(ic, controller.composingStartInEditor + controller.displayCursorIndex())
        }
    }

    /** Backspace on committed text immediately before the preedit, keeping it intact. */
    private fun deleteCommittedGraphemeBeforePreedit(ic: InputConnection) {
        val beforeText = ic.getTextBeforeCursor(128, 0)?.toString() ?: ""
        var charsToDelete = GraphemeEditor.getBackwardGraphemeLength(beforeText)
        if (charsToDelete <= 0) charsToDelete = 1
        deleteBefore(ic, charsToDelete)
        if (controller.composingStartInEditor >= charsToDelete) {
            controller.composingStartInEditor -= charsToDelete
        }
    }

    private fun resetPreeditToEmpty(ic: InputConnection) {
        val lastLen = controller.lastSetComposingText?.length ?: 0
        controller.resetComposingUI(ic, lastLen)
    }


    // ============================================================
    // MACRO ROLLBACK
    // ============================================================

    private fun rollbackMacroExpansion(ic: InputConnection): Boolean {
        val macro = controller.lastExpandedMacro ?: return false
        if (System.currentTimeMillis() - macro.timestamp >= 3000) {
            controller.lastExpandedMacro = null
            return false
        }
        val beforeText = ic.getTextBeforeCursor(macro.expandedText.length + 16, 0)?.toString() ?: ""
        if (!beforeText.endsWith(macro.expandedText)) {
            controller.lastExpandedMacro = null
            return false
        }
        controller.lastExpandedMacro = null
        deleteBefore(ic, macro.expandedText.length)
        // Replay the macro trigger through the same raw recompiler.
        controller.composingRaw.clear()
        controller.composingRaw.append(macro.trigger)
        controller.composingCursorIndex = macro.trigger.length
        controller.isVietnamese = true
        replaceComposingText(ic, controller.compileRawDisplay())
        return true
    }

    // ============================================================
    // COMMITTED EDITOR — grapheme-cluster deletion
    // ============================================================

    /** Delete the whole grapheme cluster before the caret ('á' -> ""). */
    fun deleteLastGraphemeOrChar(ic: InputConnection) {
        val beforeText = ic.getTextBeforeCursor(128, 0)
        if (beforeText != null && beforeText.isNotEmpty()) {
            val text = beforeText.toString()
            val charsToDelete = GraphemeEditor.getBackwardGraphemeLength(text)
            if (charsToDelete > 0) {
                deleteBefore(ic, charsToDelete)
                return
            }
        }
        deleteBefore(ic, 1)
    }

    fun deleteNextGraphemeOrChar(ic: InputConnection) {
        val afterText = ic.getTextAfterCursor(128, 0)
        if (afterText != null && afterText.isNotEmpty()) {
            val text = afterText.toString()
            val charsToDelete = GraphemeEditor.getForwardGraphemeLength(text)
            if (charsToDelete > 0) {
                deleteForwardCount(ic, charsToDelete)
                return
            }
        }
        deleteForwardCount(ic, 1)
    }

    /** Delete the last word (and any trailing whitespace) before the caret. */
    private fun deleteLastWordInEditor(ic: InputConnection) {
        val beforeText = ic.getTextBeforeCursor(100, 0)?.toString() ?: ""
        if (beforeText.isEmpty()) {
            deleteLastGraphemeOrChar(ic)
            return
        }
        val trimmed = beforeText.trimEnd()
        val wordStart = if (trimmed.isEmpty()) 0 else trimmed.lastIndexOf(' ') + 1
        val charsToDelete = beforeText.length - wordStart
        if (charsToDelete > 0) deleteBefore(ic, charsToDelete) else deleteLastGraphemeOrChar(ic)
    }

    private fun deleteBefore(ic: InputConnection, count: Int) {
        if (controller.isImmediateCommitMode()) {
            sendBackspaceEvents(ic, count)
        } else {
            ic.deleteSurroundingText(count, 0)
        }
    }

    private fun deleteForwardCount(ic: InputConnection, count: Int) {
        if (controller.isImmediateCommitMode()) {
            sendForwardDeleteEvents(ic)
        } else {
            ic.deleteSurroundingText(0, count)
        }
    }

    private fun deleteSelection(ic: InputConnection) {
        if (controller.isImmediateCommitMode()) {
            sendBackspaceEvents(ic, 1) // KEYCODE_DEL with an active selection deletes it
        } else {
            ic.commitText("", 1)
        }
    }

    // ============================================================
    // PREEDIT RENDERING
    // ============================================================

    fun replaceComposingText(ic: InputConnection, display: String) {
        if (controller.isImmediateCommitMode()) {
            val lastStr = controller.lastSetComposingText ?: ""
            sendBackspaceEvents(ic, lastStr.length)
            if (display.isNotEmpty()) {
                ic.commitText(display, 1)
            }
        } else {
            ic.setComposingText(display, 1)
        }
        controller.lastSetComposingText = display
    }

    fun sendBackspaceEvents(ic: InputConnection, count: Int) {
        for (i in 0 until count) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    private fun sendForwardDeleteEvents(ic: InputConnection) {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD_DEL))
    }
}
