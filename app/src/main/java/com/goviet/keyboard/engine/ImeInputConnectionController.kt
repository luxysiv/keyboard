package com.goviet.keyboard.engine

import com.goviet.keyboard.VietnameseInputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import java.lang.StringBuilder

/**
 * ImeInputConnectionController (IME Input Controller)
 *
 * Architecture Role:
 * - Manages the IME layer interaction with Android's InputConnection.
 * - Handles composing text buffers (composingRaw), cursor tracking, selection, and backspace logic.
 * - Delegates Vietnamese syllable rules and settings (Telex, Simple Telex, Modern Style, Macros) to VietnameseInputEngine.
 */
class ImeInputConnectionController(
    val service: VietnameseInputMethodService,
    val inputEngine: VietnameseInputEngine
) {

    private val TAG = "ImeInputConnectionController"

    val backspaceHandler = BackspaceHandler(this)
    
    enum class TypingMode {
        VIETNAMESE,
        LATIN
    }

    var typingMode: TypingMode = TypingMode.VIETNAMESE
        private set

    fun updateTypingMode(editorInfo: android.view.inputmethod.EditorInfo?) {
        if (editorInfo == null) {
            typingMode = TypingMode.VIETNAMESE
            return
        }
        val inputType = editorInfo.inputType
        if (inputType == android.text.InputType.TYPE_NULL) {
            typingMode = TypingMode.LATIN
            return
        }
        val classType = inputType and android.text.InputType.TYPE_MASK_CLASS
        if (classType == android.text.InputType.TYPE_CLASS_TEXT) {
            val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
            if (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == 224) { // 224 is TYPE_TEXT_VARIATION_WEB_PASSWORD
                typingMode = TypingMode.LATIN
                return
            }
        }
        typingMode = TypingMode.VIETNAMESE
    }

    val composingRaw = StringBuilder()
    var lastSetComposingText: String? = null
    var activeComposingShiftState = 0 // 0: lowercase, 1: title case, 2: uppercase (caps lock)
    var isVietnamese: Boolean = true
    private var lastShiftTime = 0L
    var isSelecting: Boolean = false
    var lastKeyPressTime = 0L
    private val displayBuf = OwnedBuffer()
    var composingStartInEditor = -1
    var composingCursorIndex = 0

    /** Live syllable state — source of truth for display. */
    val composingState = VietnameseComposer.SyllableState()
    /** Pre-allocated undo log for zero-allocation backspace. */
    val composingUndoLog = VietnameseComposer.UndoLog()

    // Expected cursor positions set by our own setSelection calls, each stamped with its
    // creation time. WebViews can emit stale or duplicated onUpdateSelection callbacks after
    // a delay, so a slot is only accepted while it is still recent (within TTL). This keeps
    // the capability to acknowledge several cursor moves in quick succession while rejecting
    // callbacks that arrive too late to plausibly belong to the current operation.
    private val expectedCursorPositions = IntArray(16) { -1 }
    private val expectedCursorTimes = LongArray(16) { -1L }
    private var expectedCursorHead = 0

    // Window (ms) during which a self-generated cursor is still considered "ours".
    private val expectedCursorTtlMs: Long = 350

    fun pushExpectedCursor(cursor: Int) {
        if (cursor < 0) return
        expectedCursorPositions[expectedCursorHead] = cursor
        expectedCursorTimes[expectedCursorHead] = android.os.SystemClock.uptimeMillis()
        expectedCursorHead = (expectedCursorHead + 1) % expectedCursorPositions.size
    }

    fun isExpectedCursor(cursor: Int): Boolean {
        if (cursor < 0) return false
        val now = android.os.SystemClock.uptimeMillis()
        for (i in expectedCursorPositions.indices) {
            if (expectedCursorPositions[i] == cursor) {
                // Reject expectations that have gone stale: a delayed callback no longer
                // belongs to the operation that set it.
                if (now - expectedCursorTimes[i] > expectedCursorTtlMs) {
                    expectedCursorPositions[i] = -1
                    return false
                }
                expectedCursorPositions[i] = -1
                expectedCursorTimes[i] = -1L
                return true
            }
        }
        return false
    }

    fun clearExpectedCursors() {
        for (i in expectedCursorPositions.indices) {
            expectedCursorPositions[i] = -1
        }
        expectedCursorTimes.fill(-1L)
        expectedCursorHead = 0
    }

    /**
     * Moves the editor cursor to [cursor] and records it as the expected position,
     * so subsequent selection callbacks are not mistaken for a user move.
     */
    fun moveCursorTo(ic: InputConnection, cursor: Int) {
        if (cursor < 0) return
        ic.setSelection(cursor, cursor)
        expectedCursorStart = cursor
        expectedCursorEnd = cursor
    }

    var expectedCursorStart: Int = -1
        set(value) {
            field = value
            if (value >= 0) pushExpectedCursor(value)
        }
    var expectedCursorEnd: Int = -1
        set(value) {
            field = value
            if (value >= 0) pushExpectedCursor(value)
        }

    // Cached cursor & selection state pushed by Android OS via onUpdateSelection
    var cachedSelStart: Int = 0
    var cachedSelEnd: Int = 0
    var cachedCandidatesStart: Int = -1
    var cachedCandidatesEnd: Int = -1

    var userMovedCursor: Boolean = false
    var userSelectedText: Boolean = false

    // Region of the composing/committed span WE just wrote. Some editors
    // (WebView/Chrome) keep reporting this span as the active "selection" for a
    // few frames after commitText/setComposingText. Treating it as a real user
    // selection makes the next backspace delete the whole word, so the delete
    // paths must recognise and ignore it. Thread-confined, IME thread only.
    private var recentEngineRegionStart = -1
    private var recentEngineRegionLen = 0
    private var recentEngineRegionAt = 0L

    private fun recordEngineRegion(start: Int, len: Int) {
        if (start < 0) return
        recentEngineRegionStart = start
        recentEngineRegionLen = len
        recentEngineRegionAt = System.currentTimeMillis()
    }

    private fun isRecentEngineRegion(start: Int, end: Int): Boolean {
        if (recentEngineRegionStart < 0 || end - start != recentEngineRegionLen) return false
        if (System.currentTimeMillis() - recentEngineRegionAt > 600L) return false
        return start == recentEngineRegionStart
    }

    fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        cachedSelStart = newSelStart
        cachedSelEnd = newSelEnd
        cachedCandidatesStart = candidatesStart
        cachedCandidatesEnd = candidatesEnd

        // Resolve the composing region *before* deciding whether the cursor move is ours.
        // On the web (WebView/Chrome) the editor can emit spurious onUpdateSelection events
        // (reflow / autocomplete) whose cursor value coincides with a position we set earlier.
        // Consuming those blindly makes the IME treat a real user cursor move as its own and
        // jump the caret, so only swallow positions that actually fall inside the active
        // composing region for the current syllable.
        val lastDisplay = if (composingRaw.isEmpty()) null else (lastSetComposingText ?: compileComposingText())
        val compStart = if (candidatesStart >= 0) candidatesStart else composingStartInEditor
        val compEnd = if (compStart >= 0 && lastDisplay != null) compStart + lastDisplay.length else -1

        val insideComposingRegion = compStart >= 0 && newSelStart >= compStart &&
                newSelStart <= compEnd && newSelEnd == newSelStart

        val isRecentTyping = (System.currentTimeMillis() - lastKeyPressTime < 300)

        // 1. If this update matches one of our recent expected cursor positions OR is a rapid reflection
        //    of recent typing within the composing region, consume it as our own.
        val isExpected = (insideComposingRegion && isExpectedCursor(newSelStart)) || (insideComposingRegion && isRecentTyping)
        if (isExpected) {
            userMovedCursor = false
            userSelectedText = false
            // fcitx5 method: when the editor drops our composing span (InputFilter / WebView
            // reflow), re-announce it so the engine's composing region stays in sync with the
            // client instead of drifting and eventually making the caret jump.
            if (candidatesStart == -1 && composingRaw.isNotEmpty() && lastDisplay != null &&
                composingStartInEditor >= 0
            ) {
                val ic = service.currentInputConnection
                if (ic != null) {
                    ic.setComposingRegion(
                        composingStartInEditor,
                        composingStartInEditor + lastDisplay.length
                    )
                }
            }
            return
        }

        userMovedCursor = true
        userSelectedText = (newSelStart != newSelEnd)

        // 2. Synchronize composingStartInEditor if Android OS reports a valid composing region
        if (candidatesStart >= 0) {
            composingStartInEditor = candidatesStart
        }

        // 3. User actively moved cursor or tapped elsewhere while a composing session was active:
        // Commit the active composition as-is and reset IME state so the next keystroke starts cleanly.
        if (composingRaw.isNotEmpty()) {
            val ic = service.currentInputConnection
            if (ic != null) {
                ic.beginBatchEdit()
                try {
                    ic.finishComposingText()
                    clearState()
                } finally {
                    ic.endBatchEdit()
                }
            } else {
                clearState()
            }
        }
    }

    fun mapDisplayOffsetToRawCursor(raw: String, display: String, displayOffset: Int): Int {
        if (displayOffset <= 0 || raw.isEmpty()) return 0
        if (displayOffset >= display.length) return raw.length
        return displayOffset.coerceAtMost(raw.length)
    }

    data class ImeCommitRecord(val word: String, val timestamp: Long)
    private var lastImeCommit: ImeCommitRecord? = null

    data class MacroExpansionRecord(val trigger: String, val expandedText: String, val timestamp: Long)
    var lastExpandedMacro: MacroExpansionRecord? = null


    var lastCommittedChar: Char? = null
    var lastCommittedSeparator: String? = null

    private fun recordImeCommit(word: String) {
        val trimmed = word.trim()
        if (trimmed.isNotEmpty()) {
            lastCommittedChar = trimmed.lastOrNull()
            lastImeCommit = ImeCommitRecord(
                word = VietnameseUnicode.normalizeNfc(trimmed),
                timestamp = System.currentTimeMillis()
            )
            service.lastCommittedWord = trimmed
        }
    }

    fun clearState() {
        composingRaw.clear()
        composingState.reset()
        composingUndoLog.clear()
        lastSetComposingText = null
        activeComposingShiftState = 0
        isVietnamese = true
        lastKeyPressTime = 0L
        composingStartInEditor = -1
        composingCursorIndex = 0
        expectedCursorStart = -1
        expectedCursorEnd = -1
        clearExpectedCursors()
        lastExpandedMacro = null
        lastCommittedSeparator = null
        inputEngine.reset()
    }

    fun isVietnameseLetterChar(c: Char): Boolean {
        if (c.isDigit()) return false
        if (c.isLetter()) return true
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
    }

    /**
     * Queries the TRUE caret position from the InputConnection instead of relying on
     * the cached onUpdateSelection values. Our own edits can leave the cached cursor
     * stale for a few frames (e.g. space commit followed immediately by backspace),
     * and a stale offset makes the composing region drift — the composed vowel (from
     * "oo") ends up stranded behind the newly composed text. ExtractedText is the
     * only synchronous, race-free source of the caret position.
     */
    private fun realSelectionStart(ic: InputConnection): Int {
        val extracted = queryExtractedText(ic)
        val sel = extracted?.selectionStart ?: -1
        return if (sel < 0) cachedSelStart else sel
    }

    private fun realSelectionEnd(ic: InputConnection): Int {
        val extracted = queryExtractedText(ic)
        val sel = extracted?.selectionEnd ?: -1
        return if (sel < 0) cachedSelEnd else sel
    }

    private fun queryExtractedText(ic: InputConnection): android.view.inputmethod.ExtractedText? {
        val request = android.view.inputmethod.ExtractedTextRequest()
        request.token = 0
        return ic.getExtractedText(request, 0)
    }

    /** True caret selection, race-free — used by the delete paths. */
    fun hasRealSelection(ic: InputConnection): Boolean {
        if (isSelecting) return true
        // A selection reported by onUpdateSelection is editor-acknowledged and
        // always a genuine user gesture — trust it directly.
        if (userSelectedText) return true
        val extracted = queryExtractedText(ic)
        val selStart = extracted?.selectionStart ?: cachedSelStart
        val selEnd = extracted?.selectionEnd ?: cachedSelEnd
        if (selStart < 0 || selEnd < 0 || selStart == selEnd) return false
        // Exclude the composing/just-committed span we wrote ourselves: to the
        // engine that is a caret, never a selection ('rồng ' -> BACKSPACE must
        // remove the space, not the whole word).
        if (isRecentEngineRegion(selStart, selEnd)) return false
        return true
    }

    fun findWordAroundCursor(ic: InputConnection): WordAtCursor? {
        val beforeText = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
        val afterText = ic.getTextAfterCursor(64, 0)?.toString() ?: ""

        var i = beforeText.length - 1
        while (i >= 0 && isVietnameseLetterChar(beforeText[i])) {
            i--
        }
        val wordBefore = beforeText.substring(i + 1)

        var j = 0
        while (j < afterText.length && isVietnameseLetterChar(afterText[j])) {
            j++
        }
        val wordAfter = afterText.substring(0, j)

        val fullWord = wordBefore + wordAfter
        if (fullWord.isEmpty()) return null

        val curSelStart = realSelectionStart(ic)
        val base = if (curSelStart >= 0) curSelStart else 0

        return WordAtCursor(
            text = fullWord,
            startInEditor = (base - wordBefore.length).coerceAtLeast(0),
            endInEditor = base + wordAfter.length,
            cursorOffset = wordBefore.length
        )
    }

    /**
     * Dedicated cursor→engine bridge: decides how a fresh keystroke starts a composition
     * session relative to existing committed text at the editor caret.
     */
    private fun resolveCompositionAtCursor(
        ic: InputConnection,
        key: String,
        wordAtCursor: WordAtCursor?
    ) {
        val currentSel = realSelectionStart(ic)
        composingStartInEditor = if (currentSel >= 0) currentSel else -1
        composingCursorIndex = 0

        val wordCursorOffset = wordAtCursor?.cursorOffset ?: 0
        val wordText         = wordAtCursor?.text ?: ""
        val wordTextLength   = wordText.length

        val adoptResult = if (wordAtCursor != null && EditedVietnameseRecognizer.canRecompose(wordText)) {
            inputEngine.adoptWord(wordText)
        } else null

        val onsetEnd = adoptResult?.onsetLength ?: 0
        val isAtOrAfterVowel = adoptResult != null && adoptResult.isValid && wordCursorOffset >= onsetEnd + 1
        val isAtEnd = wordAtCursor != null && wordCursorOffset == wordTextLength

        val lowerKey = if (key.isNotEmpty()) key[0].lowercaseChar() else ' '
        val isTone = VietnameseComposer.isToneKey(lowerKey)
        val isVowelMod = VietnameseComposer.isVowelModifierKey(lowerKey)

        val shouldAdopt = wordAtCursor != null && adoptResult != null && adoptResult.isValid && (
            isAtEnd || (isAtOrAfterVowel && (isTone || isVowelMod))
        )

        // The adopted region must span exactly the word we read from the editor.
        // A stale/absent caret (WebView can report -1) would otherwise produce a
        // guessed region and setComposingText would rewrite the wrong range —
        // visually deleting the whole word. Trust only verifiable regions.
        val regionValid = wordAtCursor != null &&
                wordAtCursor.startInEditor >= 0 &&
                wordAtCursor.endInEditor > wordAtCursor.startInEditor &&
                (wordAtCursor.endInEditor - wordAtCursor.startInEditor) == wordAtCursor.text.length

        if (shouldAdopt && regionValid) {
            val canonicalRaw = adoptResult!!.canonicalRaw
            // Gate adoption by round-trip: the canonical keystrokes must replay to the
            // exact committed word through the same compile path. If not, start fresh —
            // the committed word stays untouched and the next keystroke begins a new
            // syllable (Unikey/Gboard behaviour), never a divergent interpretation.
            displayBuf.clear()
            inputEngine.compileRaw(canonicalRaw, vietnamese = true, displayBuf)
            if (displayBuf.toStringVal() != wordText) {
                isVietnamese = true
                userMovedCursor = false
                return
            }
            composingStartInEditor = wordAtCursor.startInEditor
            composingRaw.clear()
            composingRaw.append(canonicalRaw)
            composingCursorIndex = canonicalRaw.length
            isVietnamese = true
            inputEngine.replayRawToState(canonicalRaw, composingState)
            composingUndoLog.clear()
            lastSetComposingText = wordText
            ic.setComposingRegion(wordAtCursor.startInEditor, wordAtCursor.endInEditor)
            userMovedCursor = false
            return
        }

        // If not adopted as standard Vietnamese syllable, but cursor is at the end of an existing word (e.g. "confirm", "test", etc.)
        // Exception: a consonant-only word that is a valid Vietnamese onset ("r",
        // "th", "qu", "gi", ...) is a syllable seed, not a literal. Adopting it as
        // literal would lock the IME into Latin mode and "r" + "aw" would type
        // "raw" instead of composing "ră" — start a fresh Vietnamese composition.
        val isVietnameseOnsetSeed = wordText.lowercase() in VietnamesePhonology.ONSETS
        if (wordAtCursor != null && isAtEnd && wordText.isNotEmpty() && isVietnameseOnsetSeed && regionValid) {
            // Adopt the onset seed as a Vietnamese composition: replay the seed
            // keystrokes into the live state so the next character (vowel) extends
            // it naturally into a full syllable (e.g. "ch" + "o" → "cho" underlined).
            composingStartInEditor = wordAtCursor.startInEditor
            composingRaw.clear()
            composingRaw.append(wordText)
            composingCursorIndex = wordText.length
            isVietnamese = true
            inputEngine.replayRawToState(wordText, composingState)
            composingUndoLog.clear()
            lastSetComposingText = wordText
            ic.setComposingRegion(wordAtCursor.startInEditor, wordAtCursor.endInEditor)
            userMovedCursor = false
            return
        }
        if (wordAtCursor != null && isAtEnd && wordText.isNotEmpty() && !isVietnameseOnsetSeed && regionValid) {
            composingStartInEditor = wordAtCursor.startInEditor
            composingRaw.clear()
            composingRaw.append(wordText)
            composingCursorIndex = wordText.length
            isVietnamese = false
            composingState.reset()
            composingState.rawSuffix = wordText
            composingUndoLog.clear()
            lastSetComposingText = wordText

            ic.setComposingRegion(wordAtCursor.startInEditor, wordAtCursor.endInEditor)
            userMovedCursor = false
            return
        }

        isVietnamese = !(wordAtCursor != null && wordCursorOffset > 0 && wordCursorOffset < wordTextLength)

        userMovedCursor = false
    }

    fun isImmediateCommitMode(): Boolean {
        val editorInfo = service.currentInputEditorInfo ?: return false
        return editorInfo.inputType == android.text.InputType.TYPE_NULL
    }

    private fun isBypassVietnameseComposing(): Boolean {
        return typingMode == TypingMode.LATIN
    }

    private fun isVietnameseComposingKey(key: String): Boolean {
        if (service._languageMode.value == "ENG") return false
        if (isBypassVietnameseComposing()) return false
        if (key.length != 1) return false
        val char = key[0]
        return char in 'a'..'z' || char in 'A'..'Z' || char.lowercaseChar() != char.uppercaseChar()
    }

    /* =========================================================================
     * COMPOSING BUFFER & UI LIFECYCLE
     * ========================================================================= */

    fun resetComposingUI(ic: InputConnection, backspaceCountIfImmediate: Int = 0) {
        ic.beginBatchEdit()
        try {
            clearExpectedCursors()
            composingRaw.clear()
            composingState.reset()
            composingUndoLog.clear()
            activeComposingShiftState = 0
            lastSetComposingText = null
            expectedCursorStart = -1
            expectedCursorEnd = -1
                inputEngine.reset()
            if (isImmediateCommitMode()) {
                if (backspaceCountIfImmediate > 0) {
                    backspaceHandler.sendBackspaceEvents(ic, backspaceCountIfImmediate)
                }
            } else {
                ic.setComposingText("", 1)
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    fun updateComposingUI(ic: InputConnection, lastLenIfImmediate: Int = 0, explicitCompiled: String? = null) {
        ic.beginBatchEdit()
        try {
            val compiled = explicitCompiled ?: compileComposingText()
            if (isImmediateCommitMode()) {
                val lastStr = lastSetComposingText ?: ""
                if (lastStr.isNotEmpty() && compiled == lastStr.substring(0, lastStr.length - 1)) {
                    backspaceHandler.sendBackspaceEvents(ic, 1)
                } else if (lastLenIfImmediate > 0) {
                    backspaceHandler.sendBackspaceEvents(ic, lastLenIfImmediate)
                }
                ic.commitText(compiled, 1)
            } else {
                ic.setComposingText(compiled, 1)
            }
            if (composingStartInEditor >= 0) {
                if (composingCursorIndex != composingRaw.length) {
                    // Mid-preedit caret: place it at the display offset of the raw caret.
                    moveCursorTo(ic, composingStartInEditor + displayCursorIndex())
                } else {
                    // Caret at the end: the prefix equals the full raw, so its display
                    // length is the compiled text we already have — no extra recompile.
                    val displayCursor = compiled.length
                    expectedCursorStart = composingStartInEditor + displayCursor
                    expectedCursorEnd = expectedCursorStart
                }
            }
            if (!isImmediateCommitMode()) {
                // The span [composingStartInEditor, +compiled.length) now exists in
                // the editor as our composing region; remember it so delete paths
                // can tell it apart from a genuine selection.
                recordEngineRegion(composingStartInEditor, compiled.length)
            }
            lastSetComposingText = compiled
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun handleBackspace(ic: InputConnection) {
        backspaceHandler.handleBackspace(ic)
    }

    private fun handleDeleteForward(ic: InputConnection) {
        backspaceHandler.handleDeleteForward(ic)
    }

    private fun handleDeleteWord(ic: InputConnection) {
        backspaceHandler.handleDeleteWord(ic)
    }

    private fun handleSeparator(ic: InputConnection, separator: String) {
        if (composingRaw.isNotEmpty()) {
            commitAndReset(wordBreak = separator)
        } else {
            commitAndReset()
            ic.commitText(separator, 1)
        }
        lastCommittedChar = separator.lastOrNull()
        lastCommittedSeparator = separator
        if (separator != " ") {
            recordImeCommit(separator)
        }
        service.notifySentenceStateAfterKey(separator)
        service.evaluateAutoShift(forceIpc = false)
    }

    fun handleKeyPress(key: String) {
        val now = System.currentTimeMillis()
        lastKeyPressTime = now
        val ic: InputConnection? = service.currentInputConnection
        if (ic == null) {
            return
        }

        if (key != "BACKSPACE") {
            lastExpandedMacro = null
        }

        ic.beginBatchEdit()
        try {
            val isTelexMode = service._languageMode.value != "ENG" && !isBypassVietnameseComposing()
            if (key == "SPACE") {
                handleSeparator(ic, " ")
                return
            } else if (key == "ENTER") {
                commitAndReset()
                lastCommittedChar = '\n'
                lastCommittedSeparator = "\n"
                val editorInfo = service.currentInputEditorInfo
                val inputType = editorInfo?.inputType ?: 0
                val isMultiLine = (inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_TEXT &&
                        ((inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
                         (inputType and android.text.InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0)
                val imeOptions = editorInfo?.imeOptions ?: 0
                val actionMasked = imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
                val hasNoEnterAction = (imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

                if (!isMultiLine && !hasNoEnterAction && actionMasked != android.view.inputmethod.EditorInfo.IME_ACTION_NONE && actionMasked != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(actionMasked)
                } else if (!isMultiLine && !hasNoEnterAction && editorInfo?.actionId != 0 && editorInfo?.actionId != null) {
                    ic.performEditorAction(editorInfo.actionId)
                } else {
                    sendKeyEvent(ic, KeyEvent.KEYCODE_ENTER)
                }
                service.notifySentenceStateAfterKey("ENTER")
                service.evaluateAutoShift(forceIpc = false)
                return
            } else if (BoundaryClassifier.isBoundary(key)) {
                handleSeparator(ic, key)
                return
            }

            when (key) {
                "BACKSPACE" -> handleBackspace(ic)
                "DELETE", "FORWARD_DELETE" -> handleDeleteForward(ic)
                "DELETE_WORD" -> handleDeleteWord(ic)
                "PASTE_OTP" -> {
                    val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val primaryClip = clipboard.primaryClip
                    if (primaryClip != null && primaryClip.itemCount > 0) {
                        val text = primaryClip.getItemAt(0).text?.toString() ?: ""
                        val otpRegex = "\\d{4,8}".toRegex()
                        val match = otpRegex.find(text)
                        val otp = match?.value ?: text.filter { it.isDigit() }.take(6)
                        if (otp.isNotEmpty()) {
                            ic.commitText(otp, 1)
                        }
                    }
                }
                "SHIFT" -> {
                    val shiftNow = System.currentTimeMillis()
                    lastShiftTime = service.shiftController.toggleShiftKey(shiftNow, lastShiftTime)
                }
                "SHIFT_LONG" -> {
                    service.shiftController.forceCapsLock()
                }
                else -> {
                    val actualKey = if (service.shiftController.isShifted && key.length == 1 && key[0].isLetter()) {
                        key.uppercase()
                    } else {
                        key
                    }
                    service.notifySentenceStateAfterKey(actualKey)
                    if (!isVietnameseComposingKey(key)) {
                        commitAndReset()
                        ic.commitText(actualKey, 1)
                        service.lastCommittedWord = actualKey
                        lastCommittedChar = actualKey.lastOrNull()
                        lastCommittedSeparator = null
                        service.shiftController.consumeSingleShift()
                        service.evaluateAutoShift(forceIpc = false)
                    } else {
                        if (composingRaw.isEmpty()) {
                            val wordAtCursor = findWordAroundCursor(ic)
                            resolveCompositionAtCursor(ic, actualKey, wordAtCursor)
                        }
                        if (composingRaw.isEmpty()) {
                            activeComposingShiftState = service.shiftController.value
                        }
                        val lastLen = lastSetComposingText?.length ?: 0
                        // Record undo snapshot BEFORE modifying state.
                        composingUndoLog.record(composingState, composingRaw.length)
                        composingRaw.insert(composingCursorIndex, actualKey)
                        composingCursorIndex += actualKey.length
                        lastCommittedChar = actualKey.lastOrNull()
                        lastCommittedSeparator = null

                        // Feed key into live state — incremental, no replay.
                        if (isVietnamese && actualKey.length == 1) {
                            inputEngine.feedKey(composingState, actualKey[0])
                        }
                        val casedDisplay = if (isVietnamese) {
                            composingState.toDisplayString(inputEngine.options.oldTonePlacement)
                        } else {
                            compileRawDisplay()
                        }

                        updateComposingUI(ic, lastLen, casedDisplay)

                        if (isImmediateCommitMode()) {
                            recordImeCommit(casedDisplay)
                        }
                        service.shiftController.consumeSingleShift()
                    }
                }
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun sendKeyEvent(ic: InputConnection, keyCode: Int, isShifted: Boolean = false) {
        if (isShifted) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        }
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        if (isShifted) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
        }
    }

    private fun sendMoveKey(ic: InputConnection, keycode: Int) {
        sendKeyEvent(ic, keycode, isSelecting)
    }

    fun handleEditAction(action: String) {
        val ic = service.currentInputConnection ?: return
        when (action) {
            "LEFT" -> sendMoveKey(ic, KeyEvent.KEYCODE_DPAD_LEFT)
            "RIGHT" -> sendMoveKey(ic, KeyEvent.KEYCODE_DPAD_RIGHT)
            "UP" -> sendMoveKey(ic, KeyEvent.KEYCODE_DPAD_UP)
            "DOWN" -> sendMoveKey(ic, KeyEvent.KEYCODE_DPAD_DOWN)
            "HOME" -> sendMoveKey(ic, KeyEvent.KEYCODE_MOVE_HOME)
            "END" -> sendMoveKey(ic, KeyEvent.KEYCODE_MOVE_END)
            "TOGGLE_SELECT" -> {
                isSelecting = !isSelecting
            }
            "SELECT_ALL" -> ic.performContextMenuAction(android.R.id.selectAll)
            "COPY" -> ic.performContextMenuAction(android.R.id.copy)
            "PASTE" -> {
                ic.performContextMenuAction(android.R.id.paste)
                isSelecting = false
            }
            "CUT" -> {
                ic.performContextMenuAction(android.R.id.cut)
                isSelecting = false
            }
            "DELETE" -> {
                val selected = ic.getSelectedText(0)
                if (selected != null && selected.isNotEmpty()) {
                    ic.commitText("", 1)
                } else {
                    if (isImmediateCommitMode()) {
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD_DEL))
                    } else {
                        backspaceHandler.deleteNextGraphemeOrChar(ic)
                    }
                }
            }
        }
    }

    fun compileComposingText(): String = compileRawDisplay()

    /**
     * Single derivation path: display, commit and casing all flow through this
     * function, so what the user sees is always exactly what gets committed.
     */
    fun compileRawDisplay(): String {
        val raw = composingRaw.toString()
        if (raw.isEmpty()) return ""
        return compileText(raw)
    }

    /**
     * Compiles only the raw prefix [0, end) with the same casing rules as the
     * full display. Used to map a raw caret to its display offset and to
     * re-derive raw keystrokes after a grapheme-level composing backspace.
     */
    fun compilePrefixDisplay(raw: CharSequence, end: Int): String {
        if (end <= 0) return ""
        if (end >= raw.length) return compileRawDisplay()
        inputEngine.compileRaw(raw.subSequence(0, end), isVietnamese, displayBuf)
        return VietnameseUnicode.applyCasingFromRaw(displayBuf.toStringVal(), raw.subSequence(0, end).toString())
    }

    /** Display caret offset (chars) for the current raw caret. */
    fun displayCursorIndex(): Int = compilePrefixDisplay(composingRaw, composingCursorIndex.coerceIn(0, composingRaw.length)).length

    fun compileText(raw: String): String {
        if (raw.isEmpty()) return ""
        inputEngine.compileRaw(raw, isVietnamese, displayBuf)
        return VietnameseUnicode.applyCasingFromRaw(displayBuf.toStringVal(), raw)
    }

    private fun tryExpandMacro(raw: String, wordBreak: String): String? {
        if (!inputEngine.macroEnabled) return null
        val store = inputEngine.macroStore ?: return null
        if (store.isEmpty()) return null

        // Case 1: unaccented / raw trigger (e.g. "vn" -> "Việt Nam", "rs" -> "RoSino18k")
        store.lookup(raw.lowercase())?.let { expansion ->
            return applyMacroCase(expansion, raw) + wordBreak
        }

        // Case 2: accented Vietnamese trigger (e.g. "đc" -> "được", "ng" -> "người")
        val composed = compileText(raw)   // without wordBreak
        if (composed != raw) {
            store.lookup(composed.lowercase())?.let { expansion ->
                return applyMacroCase(expansion, composed) + wordBreak
            }
        }
        return null
    }

    private fun applyMacroCase(expansion: String, typed: String): String =
        if (typed.isNotEmpty() && typed.all { it.isUpperCase() }) expansion.uppercase() else expansion

    fun commitAndReset(wordBreak: String = "") {
        if (composingRaw.isNotEmpty()) {
            val ic = service.currentInputConnection
            if (ic != null) {
                ic.beginBatchEdit()
                try {
                    clearExpectedCursors()
                    val raw = composingRaw.toString()
                    val macroExpanded = tryExpandMacro(raw, wordBreak)
                    val outputText = macroExpanded ?: (if (!isVietnamese) raw + wordBreak else compileRawDisplay() + wordBreak)

                    if (isImmediateCommitMode()) {
                        val lastLen = lastSetComposingText?.length ?: 0
                        if (lastLen > 0) {
                            backspaceHandler.sendBackspaceEvents(ic, lastLen)
                        }
                        ic.commitText(outputText, 1)
                    } else {
                        if (lastSetComposingText == outputText && wordBreak.isEmpty()) {
                            ic.finishComposingText()
                        } else {
                            ic.commitText(outputText, 1)
                        }
                    }
                    if (!isImmediateCommitMode()) {
                        // The span we are committing was a composing region in the
                        // editor; keep it for a short window so a stale selection
                        // report from the editor is not mistaken for a user one.
                        recordEngineRegion(composingStartInEditor, lastSetComposingText?.length ?: 0)
                    }
                    recordImeCommit(outputText.trim())
                    clearState()
                    if (macroExpanded != null) {
                        lastExpandedMacro = MacroExpansionRecord(raw, outputText, System.currentTimeMillis())
                    }
                } finally {
                    ic.endBatchEdit()
                }
            } else {
                clearState()
            }
            service.evaluateAutoShift()
        }
    }

    fun commitAndFinishing(wordBreak: String = "") = commitAndReset(wordBreak)
}
