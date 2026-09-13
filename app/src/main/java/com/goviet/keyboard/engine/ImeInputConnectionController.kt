package com.goviet.keyboard.engine

import com.goviet.keyboard.VietnameseInputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/**
 * ImeInputConnectionController (IME Input Controller)
 *
 * Architecture Role:
 * - Manages the IME layer interaction with Android's InputConnection.
 * - Delegates the composing preedit to VietnameseComposer's session buffer (single
 *   source of truth); handles cursor tracking, selection, and backspace logic.
 * - Delegates Vietnamese syllable rules and settings (Telex, Simple Telex, Modern Style, Macros) to VietnameseComposer.
 */
class ImeInputConnectionController(
    val service: VietnameseInputMethodService,
    val inputEngine: VietnameseComposer
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

    var lastSetComposingText: String? = null
    var activeComposingShiftState = 0 // 0: lowercase, 1: title case, 2: uppercase (caps lock)
    private var lastShiftTime = 0L
    var isSelecting: Boolean = false
    var lastKeyPressTime = 0L
    private val displayBuf = OwnedBuffer()
    var composingStartInEditor = -1
    var composingCursorIndex = 0

    /**
     * SelectionGuard — the single owner of "is this onUpdateSelection ours?" bookkeeping.
     *
     * Every place that moves the caret or announces a composing region MUST go through
     * this guard, so the editor's asynchronous reflection of our own setSelection /
     * setComposingText / setComposingRegion is always registered as "ours" and never
     * mistaken for a user cursor move (which would commit/re-adopt the syllable and
     * flicker the underline).
     *
     * Owns:
     *  - expected cursor ring buffer: positions we set, each stamped with its creation
     *    time. WebViews can emit stale or duplicated onUpdateSelection callbacks after
     *    a delay, so a slot is only accepted while still recent (within TTL); several
     *    moves in quick succession are still acknowledged.
     *  - composing-region announcement throttle: some editors (WebView/Chrome) persist
     *    in reporting candidatesStart == -1 on every reflection of our composing span;
     *    re-asserting unthrottled would loop setComposingRegion -> onUpdateSelection ->
     *    setComposingRegion and make the underline flicker.
     *  - recent engine region record: the span WE just wrote, so delete paths can tell
     *    it apart from a genuine selection a few frames later.
     */
    private inner class SelectionGuard {
        private val expectedPositions = IntArray(16) { -1 }
        private val expectedTimes = LongArray(16) { -1L }
        private var expectedHead = 0
        private var lastAnnounceAt = 0L
        private var recentRegionStart = -1
        private var recentRegionLen = 0
        private var recentRegionAt = 0L

        /** Window (ms) during which a self-generated cursor is still considered "ours". */
        private val expectedCursorTtlMs: Long = 350

        /** Minimum gap between our own setComposingRegion re-announcements. */
        private val composingReannounceMinGapMs: Long = 500

        /** Remember [cursor] as a position WE moved to / announced. */
        fun register(cursor: Int) {
            if (cursor < 0) return
            expectedPositions[expectedHead] = cursor
            expectedTimes[expectedHead] = android.os.SystemClock.uptimeMillis()
            expectedHead = (expectedHead + 1) % expectedPositions.size
        }

        /** TTL-checks and consumes [cursor] if it was registered by us. */
        fun isExpected(cursor: Int): Boolean {
            if (cursor < 0) return false
            val now = android.os.SystemClock.uptimeMillis()
            for (i in expectedPositions.indices) {
                if (expectedPositions[i] == cursor) {
                    // Reject expectations that have gone stale: a delayed callback no
                    // longer belongs to the operation that set it.
                    if (now - expectedTimes[i] > expectedCursorTtlMs) {
                        expectedPositions[i] = -1
                        return false
                    }
                    expectedPositions[i] = -1
                    expectedTimes[i] = -1L
                    return true
                }
            }
            return false
        }

        /** Moves the editor caret to [cursor] and registers it as ours. */
        fun moveTo(ic: InputConnection, cursor: Int) {
            if (cursor < 0) return
            ic.setSelection(cursor, cursor)
            register(cursor)
        }

        /**
         * Announces a composing region and registers its caret as ours in one step —
         * the register must NEVER be forgotten next to a setComposingRegion.
         */
        fun announceRegion(ic: InputConnection, start: Int, end: Int, caret: Int) {
            if (start < 0 || end <= start) return
            lastAnnounceAt = System.currentTimeMillis()
            register(caret)
            ic.setComposingRegion(start, end)
        }

        /**
         * Re-announces [start, end) with caret [caret], but only if the last
         * announcement was long enough ago — an unthrottled re-assert would loop
         * setComposingRegion -> onUpdateSelection -> setComposingRegion.
         */
        fun maybeReannounce(ic: InputConnection, start: Int, end: Int, caret: Int): Boolean {
            if (start < 0 || end <= start) return false
            val now = System.currentTimeMillis()
            if (now - lastAnnounceAt <= composingReannounceMinGapMs) return false
            announceRegion(ic, start, end, caret)
            return true
        }

        /** Records the span [start, start+len) WE just wrote (composing/committing). */
        fun markRecentRegion(start: Int, len: Int) {
            if (start < 0) return
            recentRegionStart = start
            recentRegionLen = len
            recentRegionAt = System.currentTimeMillis()
        }

        /** True when [start, end) is a span we wrote recently (not a user selection). */
        fun isRecentRegion(start: Int, end: Int): Boolean {
            if (recentRegionStart < 0 || end - start != recentRegionLen) return false
            if (System.currentTimeMillis() - recentRegionAt > 600L) return false
            return start == recentRegionStart
        }

        /** Resets all "ours" bookkeeping (ring, throttle, recent region). */
        fun clear() {
            for (i in expectedPositions.indices) {
                expectedPositions[i] = -1
                expectedTimes[i] = -1L
            }
            expectedHead = 0
            lastAnnounceAt = 0L
            recentRegionStart = -1
            recentRegionLen = 0
            recentRegionAt = 0L
        }
    }

    private val selectionGuard = SelectionGuard()

    /** Public wrapper: move the editor caret and register it as ours. */
    fun moveCursorTo(ic: InputConnection, cursor: Int) = selectionGuard.moveTo(ic, cursor)

    /** Public wrapper: register a caret we just wrote, without moving the editor caret
     *  (used by BackspaceHandler when a composing-text rewrite leaves the caret at the
     *  end of the preedit). Keeps every caret write registered through the guard. */
    fun registerCaretAsOurs(cursor: Int) = selectionGuard.register(cursor)

    // Cached cursor & selection state pushed by Android OS via onUpdateSelection
    var cachedSelStart: Int = 0
    var cachedSelEnd: Int = 0
    var cachedCandidatesStart: Int = -1
    var cachedCandidatesEnd: Int = -1

    var userMovedCursor: Boolean = false
    var userSelectedText: Boolean = false

    // NOTE: the "region WE just wrote" record (used by the delete paths) now lives
    // in SelectionGuard.markRecentRegion/isRecentRegion — see above.

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
        val lastDisplay = if (!inputEngine.isComposing()) null else (lastSetComposingText ?: compileComposingText())
        val compStart = if (candidatesStart >= 0) candidatesStart else composingStartInEditor
        val compEnd = if (compStart >= 0 && lastDisplay != null) compStart + lastDisplay.length else -1

        val insideComposingRegion = compStart >= 0 && newSelStart >= compStart &&
                newSelStart <= compEnd && newSelEnd == newSelStart

        val isRecentTyping = (System.currentTimeMillis() - lastKeyPressTime < 300)

        // 1. If this update matches one of our recent expected cursor positions OR is a rapid reflection
        //    of recent typing within the composing region, consume it as our own.
        val isExpected = (insideComposingRegion && selectionGuard.isExpected(newSelStart)) ||
                (insideComposingRegion && isRecentTyping)
        if (isExpected) {
            userMovedCursor = false
            userSelectedText = false
            // When the editor drops our composing span (InputFilter / WebView reflow),
            // re-announce it so the engine's composing region stays in sync with the
            // client instead of drifting and eventually making the caret jump.
            if (candidatesStart == -1 && inputEngine.isComposing() && lastDisplay != null &&
                composingStartInEditor >= 0
            ) {
                val ic = service.currentInputConnection
                if (ic != null) {
                    // Re-announce is throttled and registers the caret inside the guard,
                    // so its reflection is consumed as ours, never as a user move.
                    selectionGuard.maybeReannounce(
                        ic,
                        composingStartInEditor,
                        composingStartInEditor + lastDisplay.length,
                        newSelStart
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
        if (inputEngine.isComposing()) {
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

        // 4. Tapping into the middle of an existing Vietnamese word starts the
        // preedit immediately: the underline covers the prefix before the caret
        // ("tha" in "tha|y") without waiting for a key.
        val ic = service.currentInputConnection
        // Only a deliberate caret move starts the adoption; our recent keystrokes
        // or the caret jump right after a space-commit must not underline the word.
        if (ic != null && System.currentTimeMillis() - lastKeyPressTime >= 350L) {
            adoptPrefixAtCaret(ic)
        }
    }

    /**
     * Adopts the Vietnamese prefix before the caret right away when the user taps
     * into the middle of a word. The composing region then spans only [start, prefix)
     * — the remainder of the word stays committed outside — so the caret keeps its
     * exact position and every later edit (typing, backspace, delete) happens on
     * the display text through one unified path. Public so backspace can also ask
     * for the adoption when an editor does not report the tap via onUpdateSelection.
     */
    fun adoptPrefixAtCaret(ic: InputConnection) {
        if (inputEngine.isComposing()) return
        if (userSelectedText) return
        if (service._languageMode.value == "ENG" || isBypassVietnameseComposing()) return

        val word = findWordAroundCursor(ic) ?: return
        if (word.text.isEmpty()) return
        val offset = word.cursorOffset
        // The underlined region always runs from the whitespace before the word up
        // to the caret ("tha" in "tha|y", the whole word at the end, nothing at 0).
        if (offset <= 0) return
        if (word.startInEditor < 0 || word.endInEditor <= word.startInEditor) return
        if (word.endInEditor - word.startInEditor != word.text.length) return

        val prefix = word.text.substring(0, offset)
        // The whole word must look Vietnamese (e.g. "thay"/"thấy"); foreign words
        // like "confirm" must never be converted to a Telex preedit.
        if (!EditedVietnameseRecognizer.canRecompose(word.text)) return
        val adopt = inputEngine.adoptWord(prefix) ?: return
        if (!adopt.isValid) return
        if (compileText(adopt.canonicalRaw) != prefix) return

        composingStartInEditor = word.startInEditor
        inputEngine.isVietnamese = true
        inputEngine.setComposingRaw(adopt.canonicalRaw)
        composingCursorIndex = adopt.canonicalRaw.length
        lastSetComposingText = prefix
        // announceRegion registers the caret AND updates the editor in one step, so the
        // reflection of our own setComposingRegion is consumed as ours instead of being
        // mistaken for a user move (which would commit/clear and re-adopt, flickering
        // the underline forever).
        selectionGuard.announceRegion(
            ic, word.startInEditor, word.startInEditor + prefix.length, word.startInEditor + offset
        )
        userMovedCursor = false
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
        inputEngine.reset()
        lastSetComposingText = null
        activeComposingShiftState = 0
        lastKeyPressTime = 0L
        composingStartInEditor = -1
        composingCursorIndex = 0
        selectionGuard.clear()
        lastExpandedMacro = null
        lastCommittedSeparator = null
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
        if (selectionGuard.isRecentRegion(selStart, selEnd)) return false
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

        val isAtEnd = wordAtCursor != null && wordCursorOffset == wordTextLength

        // Middle-of-word edits adopt only the prefix before the caret: the underline
        // covers the typed part ("tha" in "tha|y"), the rest of the word stays
        // committed outside the region, and the caret keeps its exact position instead
        // of jumping to the end of the word.
        val adoptTarget = if (isAtEnd) wordText else wordText.substring(0, wordCursorOffset)

        val adoptResult = if (adoptTarget.isNotEmpty() && EditedVietnameseRecognizer.canRecompose(adoptTarget)) {
            inputEngine.adoptWord(adoptTarget)
        } else null

        val onsetEnd = adoptResult?.onsetLength ?: 0
        val isAtOrAfterVowel = adoptResult != null && adoptResult.isValid && wordCursorOffset >= onsetEnd + 1

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
            // syllable, never a divergent interpretation.
            displayBuf.clear()
            inputEngine.compileRaw(canonicalRaw, vietnamese = true, displayBuf)
            if (displayBuf.toStringVal() != adoptTarget) {
                inputEngine.isVietnamese = true
                userMovedCursor = false
                return
            }
            composingStartInEditor = wordAtCursor.startInEditor
            inputEngine.isVietnamese = true
            inputEngine.setComposingRaw(canonicalRaw)
            composingCursorIndex = canonicalRaw.length
            lastSetComposingText = displayBuf.toStringVal()
            selectionGuard.announceRegion(
                ic,
                wordAtCursor.startInEditor,
                wordAtCursor.startInEditor + adoptTarget.length,
                wordAtCursor.startInEditor + adoptTarget.length
            )
            userMovedCursor = false
            return
        }

        // If not adopted as standard Vietnamese syllable, but cursor is at the end of an existing word (e.g. "confirm", "test", etc.)
        // Exception: a consonant-only word that is a valid Vietnamese onset ("r",
        // "th", "qu", "gi", ...) is a syllable seed, not a literal. Adopting it as
        // literal would lock the IME into Latin mode and "r" + "aw" would type
        // "raw" instead of composing "ră" — start a fresh Vietnamese composition.
        val isVietnameseOnsetSeed = OnsetMap.ALL_ONSETS.contains(wordText.lowercase())
        if (wordAtCursor != null && isAtEnd && wordText.isNotEmpty() && isVietnameseOnsetSeed && regionValid) {
            // Adopt the onset seed as a Vietnamese composition: replay the seed
            // keystrokes into the live state so the next character (vowel) extends
            // it naturally into a full syllable (e.g. "ch" + "o" → "cho" underlined).
            composingStartInEditor = wordAtCursor.startInEditor
            inputEngine.isVietnamese = true
            inputEngine.setComposingRaw(wordText)
            composingCursorIndex = wordText.length
            lastSetComposingText = wordText
            selectionGuard.announceRegion(
                ic, wordAtCursor.startInEditor, wordAtCursor.endInEditor, wordAtCursor.endInEditor
            )
            userMovedCursor = false
            return
        }
        if (wordAtCursor != null && isAtEnd && wordText.isNotEmpty() && !isVietnameseOnsetSeed && regionValid) {
            composingStartInEditor = wordAtCursor.startInEditor
            inputEngine.isVietnamese = false
            inputEngine.setComposingRaw(wordText)
            composingCursorIndex = wordText.length
            lastSetComposingText = wordText
            selectionGuard.announceRegion(
                ic, wordAtCursor.startInEditor, wordAtCursor.endInEditor, wordAtCursor.endInEditor
            )
            userMovedCursor = false
            return
        }

        inputEngine.isVietnamese = !(wordAtCursor != null && wordCursorOffset > 0 && wordCursorOffset < wordTextLength)

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
            selectionGuard.clear()
            activeComposingShiftState = 0
            lastSetComposingText = null
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
                if (composingCursorIndex != inputEngine.composingRawLength()) {
                    // Mid-preedit caret: place it at the display offset of the raw caret.
                    moveCursorTo(ic, composingStartInEditor + displayCursorIndex())
                } else {
                    // Caret at the end: the prefix equals the full raw, so its display
                    // length is the compiled text we already have — no extra recompile.
                    val displayCursor = compiled.length
                    selectionGuard.register(composingStartInEditor + displayCursor)
                }
            }
            if (!isImmediateCommitMode()) {
                // The span [composingStartInEditor, +compiled.length) now exists in
                // the editor as our composing region; remember it so delete paths
                // can tell it apart from a genuine selection.
                selectionGuard.markRecentRegion(composingStartInEditor, compiled.length)
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
        if (inputEngine.isComposing()) {
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
                        if (!inputEngine.isComposing()) {
                            val wordAtCursor = findWordAroundCursor(ic)
                            resolveCompositionAtCursor(ic, actualKey, wordAtCursor)
                        }
                        if (!inputEngine.isComposing()) {
                            activeComposingShiftState = service.shiftController.value
                        }
                        val lastLen = lastSetComposingText?.length ?: 0
                        inputEngine.insertComposingKey(composingCursorIndex, actualKey[0])
                        composingCursorIndex += actualKey.length
                        lastCommittedChar = actualKey.lastOrNull()
                        lastCommittedSeparator = null

                        val casedDisplay = if (inputEngine.isVietnamese) {
                            inputEngine.toDisplayString()
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
        if (!inputEngine.isComposing()) return ""
        return VietnameseUnicode.applyCasingFromRaw(
            inputEngine.toDisplayString(), inputEngine.composingRaw().toString())
    }

    /**
     * Compiles only the raw prefix [0, end) with the same casing rules as the
     * full display. Used to map a raw caret to its display offset and to
     * re-derive raw keystrokes after a grapheme-level composing backspace.
     */
    fun compilePrefixDisplay(raw: CharSequence, end: Int): String {
        if (end <= 0) return ""
        if (end >= raw.length) return compileRawDisplay()
        // Pass raw + end directly — compileRaw reads chars without needing a substring
        inputEngine.compileRaw(raw, inputEngine.isVietnamese, displayBuf, end)
        return VietnameseUnicode.applyCasingFromRaw(displayBuf.toStringVal(), raw.subSequence(0, end).toString())
    }

    /** Display caret offset (chars) for the current raw caret. */
    fun displayCursorIndex(): Int = compilePrefixDisplay(
        inputEngine.composingRaw(), composingCursorIndex.coerceIn(0, inputEngine.composingRawLength())).length

    /**
     * Maps a display offset back to the raw buffer offset. Used after display-level
     * edits (backspace/delete + re-adoption) where the canonical raw no longer maps
     * 1:1 to display characters (e.g. "â" is one grapheme but two raw keys "aa").
     */
    fun rawIndexOfDisplay(
        raw: CharSequence,
        display: String,
        displayOffset: Int,
        vietnamese: Boolean
    ): Int {
        if (displayOffset <= 0) return 0
        if (displayOffset >= display.length) return raw.length
        if (!vietnamese) return displayOffset.coerceAtMost(raw.length)
        val target = display.substring(0, displayOffset)
        for (i in 0..raw.length) {
            if (compilePrefixDisplay(raw, i) == target) return i
        }
        return displayOffset.coerceAtMost(raw.length)
    }

    fun compileText(raw: String): String {
        if (raw.isEmpty()) return ""
        // The caller always passes Telex keystrokes (canonical raw). Vietnamese
        // compilation MUST NOT depend on the current isVietnamese flag: a word
        // that went literal (rawSuffix) would otherwise fail this round-trip and
        // stay locked in literal mode forever — after backspace the remaining
        // display ("tiê", "ê") would never re-transform even though it is a valid
        // Vietnamese syllable. Force Vietnamese mode here.
        inputEngine.compileRaw(raw, vietnamese = true, displayBuf)
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
        if (inputEngine.isComposing()) {
            val ic = service.currentInputConnection
            if (ic != null) {
                ic.beginBatchEdit()
                try {
                    selectionGuard.clear()
                    val raw = inputEngine.composingRaw().toString()
                    val macroExpanded = tryExpandMacro(raw, wordBreak)
                    val outputText = macroExpanded ?: (if (!inputEngine.isVietnamese) raw + wordBreak else compileRawDisplay() + wordBreak)

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
                        selectionGuard.markRecentRegion(composingStartInEditor, lastSetComposingText?.length ?: 0)
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
