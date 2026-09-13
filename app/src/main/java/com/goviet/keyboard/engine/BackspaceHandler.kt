package com.goviet.keyboard.engine

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
// EDITED VIETNAMESE RECOGNIZER — canonical rime-table based
// ============================================================
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
        // Strip tone diacritics only — base letters (ê, â, ư, ...) are kept
        // as-is so the remaining rime matches the canonical table.
        val stripped = VietnameseUnicode.stripToneFromWord(lower)
        if (stripped.isEmpty()) return false

        // Longest valid onset wins (ONSETS is ordered longest-first).
        var onsetLen = 0
        for (cand in OnsetMap.ALL_ONSETS) {
            if (stripped.startsWith(cand)) {
                onsetLen = cand.length
                break
            }
        }

        val rime = stripped.substring(onsetLen)
        if (rime.isEmpty()) return false

        // The rime must be a (possibly partial) canonical Vietnamese rime
        // and must contain at least one base vowel.
        if (!RimeMap.isValidPrefix(RimeMap.rimeKey(rime))) return false
        return rime.any { RimeMap.isBaseVowel(it) }
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

            if (controller.inputEngine.isComposing()) {
                performComposingBackspace(ic)
                controller.service.evaluateAutoShift()
                return
            }

            if (rollbackMacroExpansion(ic)) {
                controller.service.evaluateAutoShift()
                return
            }

            // If the caret sits inside a Vietnamese word, adopt the prefix before
            // the caret as the preedit first (underline from the whitespace up to
            // the caret) so backspace deletes a complete letter inside the preedit.
            // This keeps the behavior identical regardless of whether the editor
            // reported the caret move through onUpdateSelection.
            controller.adoptPrefixAtCaret(ic)
            if (controller.inputEngine.isComposing()) {
                performComposingBackspace(ic)
                controller.service.evaluateAutoShift()
                return
            }

            // Committed text: remove the whole preceding Unicode grapheme
            // cluster ('á' -> "", 'nguyễn' -> 'nguyễ').
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
    // COMPOSING EDITS — display-level grapheme edits + re-adoption
    // ============================================================

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
            // Caret is at the very beginning of the preedit: the backspace must
            // hit committed text while the preedit itself stays untouched.
            deleteCommittedGraphemeBeforePreedit(ic)
            return
        }

        // Remove the whole grapheme cluster immediately before the caret.
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

        // Remove the whole grapheme cluster immediately after the caret.
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

        val adopt = controller.inputEngine.adoptWord(display)
        val useVietnamese = adopt != null && adopt.isValid &&
                controller.compileText(adopt.canonicalRaw) == display
        val canonical = if (useVietnamese) adopt!!.canonicalRaw else display

        controller.inputEngine.isVietnamese = useVietnamese
        controller.inputEngine.setComposingRaw(canonical)
        controller.composingCursorIndex = controller.rawIndexOfDisplay(
            canonical, display, caretInDisplay, useVietnamese
        )

        replaceComposingText(ic, display)
        if (controller.composingStartInEditor >= 0) {
            if (caretInDisplay < display.length) {
                controller.moveCursorTo(ic, controller.composingStartInEditor + caretInDisplay)
            } else {
                // setComposingText(..., 1) leaves the caret at the end of the preedit;
                // register it so the editor's reflection is consumed as ours.
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
        controller.inputEngine.isVietnamese = true
        controller.inputEngine.setComposingRaw(macro.trigger)
        controller.composingCursorIndex = macro.trigger.length
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
