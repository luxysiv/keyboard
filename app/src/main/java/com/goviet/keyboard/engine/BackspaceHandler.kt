package com.goviet.keyboard.engine

import android.view.KeyEvent
import android.view.inputmethod.InputConnection

data class WordAtCursor(
    val text: String,
    val startInEditor: Int,
    val endInEditor: Int,
    val cursorOffset: Int
)

object EditedVietnameseRecognizer {

    /**
     * A committed word "looks Vietnamese" when it parses as a valid
     * Vietnamese onset + a canonical rime (or a leading part of one).
     * Backed by the zero-GC [RimeMap] flat map instead of the old
     * NON_VN_LETTERS heuristic, so foreign words like "warm"/"confirm"
     * are rejected structurally rather than by letter blacklists.
     */
    fun canRecompose(word: String): Boolean {
        if (word.isEmpty()) return false
        val lower = word.lowercase()
        val stripped = VietnameseUnicode.stripToneFromWord(lower)
        if (stripped.isEmpty()) return false

        val onsetLen = OnsetMap.longestOnsetPrefix(stripped)
        val rime = stripped.substring(onsetLen)
        if (rime.isEmpty()) return false

        if (!RimeMap.isValidPrefix(RimeMap.rimeKey(rime))) return false
        return rime.any { RimeMap.isBaseVowel(it) }
    }

}

class BackspaceHandler(
    private val controller: ImeInputConnectionController
) {

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

            if (controller.inputEngine.isComposing()) {
                performComposingBackspace(ic)
                controller.service.evaluateAutoShift()
                return
            }

            if (rollbackMacroExpansion(ic)) {
                controller.service.evaluateAutoShift()
                return
            }

            controller.adoptPrefixAtCaret(ic)
            if (controller.inputEngine.isComposing()) {
                performComposingBackspace(ic)
                controller.service.evaluateAutoShift()
                return
            }

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

            if (controller.inputEngine.isComposing()) {
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
            if (controller.inputEngine.isComposing()) {
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

    /**
     * Backspace while composing — deletes one complete displayed letter
     * (Unicode grapheme cluster), exactly like committed-text backspace and like
     * the big keyboard apps: "thấy" → "thấ" → "th" → "t" → "".
     *
     * Raw Telex keystrokes are never deleted one-by-one here; after removing the
     * grapheme from the display, the remaining display is re-adopted to its
     * canonical raw encoding so typing continues seamlessly.
     */
    private fun performComposingBackspace(ic: InputConnection) {
        val display = controller.lastSetComposingText ?: controller.compileComposingText()
        if (display.isEmpty()) {
            deleteLastGraphemeOrChar(ic)
            return
        }

        val caretInDisplay = controller.displayCursorIndex()
        if (caretInDisplay <= 0) {
            deleteCommittedGraphemeBeforePreedit(ic)
            return
        }

        val clusterStart = GraphemeEditor.previousBoundary(display, caretInDisplay)
        if (clusterStart >= caretInDisplay) {
            deleteLastGraphemeOrChar(ic)
            return
        }
        val sb = StringBuilder(display.length - (caretInDisplay - clusterStart))
        sb.append(display, 0, clusterStart)
        sb.append(display, caretInDisplay, display.length)
        resyncPreeditFromDisplay(ic, sb.toString(), caretInDisplay = clusterStart)
    }

    private fun performComposingDeleteForward(ic: InputConnection) {
        val display = controller.lastSetComposingText ?: controller.compileComposingText()
        if (display.isEmpty()) {
            deleteNextGraphemeOrChar(ic)
            return
        }

        val caretInDisplay = controller.displayCursorIndex()
        if (caretInDisplay >= display.length) {
            deleteNextGraphemeOrChar(ic)
            return
        }

        val clusterEnd = GraphemeEditor.nextBoundary(display, caretInDisplay)
        if (clusterEnd <= caretInDisplay) {
            deleteNextGraphemeOrChar(ic)
            return
        }
        val sb = StringBuilder(display.length - (clusterEnd - caretInDisplay))
        sb.append(display, 0, caretInDisplay)
        sb.append(display, clusterEnd, display.length)
        resyncPreeditFromDisplay(ic, sb.toString(), caretInDisplay = caretInDisplay)
    }

    /**
     * One unified re-sync path after any display-level edit: adopt the new display
     * back to canonical Telex raw keystrokes (when possible), rebuild the live
     * state, update the composing text and restore the caret position.
     */
    private fun resyncPreeditFromDisplay(ic: InputConnection, display: String, caretInDisplay: Int) {
        if (display.isEmpty()) {
            resetPreeditToEmpty(ic)
            return
        }

        val canonical = controller.inputEngine.adoptRoundTrip(display)
        val useVietnamese = canonical != null

        controller.inputEngine.composeAsVietnamese = useVietnamese
        controller.inputEngine.setComposingRaw(canonical ?: display)
        controller.composingCursorIndex = controller.rawIndexOfDisplay(
            canonical ?: display, display, caretInDisplay, useVietnamese
        )

        replaceComposingText(ic, display)
        if (controller.composingStartInEditor >= 0) {
            if (caretInDisplay < display.length) {
                controller.moveCursorTo(ic, controller.composingStartInEditor + caretInDisplay)
            } else {
                controller.registerCaretAsOurs(controller.composingStartInEditor + display.length)
            }
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
        controller.inputEngine.composeAsVietnamese = true
        controller.inputEngine.setComposingRaw(macro.trigger)
        controller.composingCursorIndex = macro.trigger.length
        replaceComposingText(ic, controller.compileRawDisplay())
        return true
    }

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
            sendBackspaceEvents(ic, 1)
        } else {
            ic.commitText("", 1)
        }
    }

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
