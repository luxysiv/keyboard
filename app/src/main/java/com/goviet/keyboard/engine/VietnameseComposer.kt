package com.goviet.keyboard.engine

import android.content.Context
import com.goviet.core.AppPreferences
import com.goviet.core.EngineConfig

/**
 * VietnameseComposer:
 * Full Telex transformation algorithm implementation:
 * - Target letter principle
 * - Buffer state re-derivation
 * - Asymmetric toggle / untoggle handling
 * - Cross-syllable d/đ transformation
 * - Auto completion and promotion of uơ / ươ
 * - Never unilaterally revert to raw text
 */
class VietnameseComposer(var options: EngineOptions = EngineOptions()) {

    var vietnameseModeEnabled: Boolean = true
    var autoCapitalize: Boolean = false

    var macroEnabled: Boolean
        get() = options.macroEnabled
        set(v) { options.macroEnabled = v }

    var alwaysMacro: Boolean
        get() = options.alwaysMacro
        set(v) { options.alwaysMacro = v }

    var directW: Boolean
        get() = options.directW
        set(v) { options.directW = v }

    var oldTonePlacement: Boolean
        get() = options.oldTonePlacement
        set(v) { options.oldTonePlacement = v }

    enum class TargetType {
        D_ONSET,
        E_NUCLEUS,
        O_NUCLEUS,
        A_NUCLEUS,
        W_NUCLEUS,
        W_SOLO
    }

    data class LastToggle(
        val key: Char,
        val targetType: TargetType,
        val hadCharsAfter: Boolean
    )

    class SyllableState(
        var onset: String = "",
        var nucleus: String = "",       // P: vowel nucleus (including offglides i, y, u, o)
        var coda: String = "",          // True final consonant coda: c, t, n, p, m, ng, ch, nh
        var tone: Tone = Tone.NONE,
        var lastToggle: LastToggle? = null,
        var lastUntoggledToneKey: Char? = null,
        var rawSuffix: String = ""      // Trailing invalid characters, preserved without revert
    ) {
        fun reset() {
            onset = ""
            nucleus = ""
            coda = ""
            tone = Tone.NONE
            lastToggle = null
            lastUntoggledToneKey = null
            rawSuffix = ""
        }

        fun isEmpty(): Boolean = onset.isEmpty() && nucleus.isEmpty() && coda.isEmpty() && rawSuffix.isEmpty()

        /** Single String allocation — delegates to zero-alloc buffer. */
        fun toDisplayString(oldTonePlacement: Boolean = false): String {
            val buf = OwnedBuffer()
            toDisplayBuffer(buf, oldTonePlacement)
            return buf.toStringVal()
        }

        /**
         * Write display text directly to an OwnedBuffer. Zero allocation.
         * Used in feedChar hot path to avoid String creation.
         */
        fun toDisplayBuffer(out: OwnedBuffer, oldTonePlacement: Boolean = false) {
            out.clear()
            if (isEmpty()) return
            val totalLen = onset.length + nucleus.length + coda.length + rawSuffix.length
            if (totalLen == 0) return

            if (tone == Tone.NONE || nucleus.isEmpty()) {
                for (i in 0 until onset.length) out.append(onset[i])
                for (i in 0 until nucleus.length) out.append(nucleus[i])
                for (i in 0 until coda.length) out.append(coda[i])
                for (i in 0 until rawSuffix.length) out.append(rawSuffix[i])
                return
            }

            val rime = nucleus + coda
            val placement = if (oldTonePlacement) TonePlacement.LEGACY else TonePlacement.MODERN
            val toneIdx = VietnamesePhonology.determineTonePosition(rime, onset, placement)

            for (i in 0 until onset.length) out.append(onset[i])
            for (i in 0 until nucleus.length) {
                if (i == toneIdx) out.append(VietnameseUnicode.applyTone(nucleus[i], tone))
                else out.append(nucleus[i])
            }
            for (i in 0 until coda.length) out.append(coda[i])
            for (i in 0 until rawSuffix.length) out.append(rawSuffix[i])
        }
    }


    // ============================================================
    // UNDO LOG — pre-allocated, zero-allocation per keystroke
    // ============================================================
    /**
     * A single undo entry: captures all SyllableState field references + raw length
     * BEFORE a keystroke is applied. Since Kotlin Strings are immutable, storing
     * references is sufficient — no copying needed.
     */
    class UndoEntry(
        var onset: String = "",
        var nucleus: String = "",
        var coda: String = "",
        var tone: Tone = Tone.NONE,
        var lastToggle: LastToggle? = null,
        var lastUntoggledToneKey: Char? = null,
        var rawSuffix: String = "",
        var rawLength: Int = 0
    ) {
        fun captureFrom(state: SyllableState, rawLen: Int) {
            onset = state.onset
            nucleus = state.nucleus
            coda = state.coda
            tone = state.tone
            lastToggle = state.lastToggle
            lastUntoggledToneKey = state.lastUntoggledToneKey
            rawSuffix = state.rawSuffix
            rawLength = rawLen
        }

        fun restoreTo(state: SyllableState) {
            state.onset = onset
            state.nucleus = nucleus
            state.coda = coda
            state.tone = tone
            state.lastToggle = lastToggle
            state.lastUntoggledToneKey = lastUntoggledToneKey
            state.rawSuffix = rawSuffix
        }
    }

    /**
     * Pre-allocated undo log (stack of [UndoEntry]). No allocation per keystroke.
     * Thread-confined: IME thread only.
     */
    class UndoLog(private val maxDepth: Int = 30) {
        private val entries = Array(maxDepth) { UndoEntry() }
        private var sp = 0

        /** Capture current state BEFORE applying a keystroke. */
        fun record(state: SyllableState, rawLength: Int) {
            if (sp < maxDepth) {
                entries[sp].captureFrom(state, rawLength)
                sp++
            }
        }

        /**
         * Restore the state to the snapshot BEFORE the last keystroke.
         * Returns the raw length at that snapshot, or -1 if the log is empty.
         */
        fun undo(state: SyllableState): Int {
            if (sp <= 0) return -1
            sp--
            entries[sp].restoreTo(state)
            return entries[sp].rawLength
        }

        fun clear() { sp = 0 }
        fun isEmpty(): Boolean = sp == 0
    }

    /**
     * Single source of truth: pure re-derivation from the raw keystroke buffer.
     * The engine keeps no persistent syllable state — every call replays the raw
     * keystrokes through the spelling kernel, so display and commit are always
     * derived by the exact same function and can never diverge.
     */
    private val replayState = SyllableState()
    private val replayOut = StepOut()
    private val stringOut = OwnedBuffer()

    fun reset() {
        replayState.reset()
    }

    /**
     * Pure recompile: replay [raw] through the spelling kernel and write the
     * display into [out]. Zero extra allocation in the hot path — pooled states
     * are reused, and callers materialize a String only when the IME API needs it.
     */

    // ============================================================
    // PUBLIC KEY FEED — for live composing (state-based, no replay)
    // ============================================================

    /**
     * Feed a single keystroke into [state] (incremental, no replay).
     * Returns true if the key was handled by the Vietnamese spelling kernel.
     * Callers should call [undoLog].record(state, rawLength) BEFORE this
     * to capture the pre-keystroke snapshot.
     */
    fun feedKey(state: SyllableState, key: Char): Boolean {
        return applyKey(state, key, isStaticReDerive = false)
    }

    /**
     * Replay [raw] keystrokes into [state] from scratch. Used when adopting
     * a committed word (e.g. after space+backspace) to rebuild state from
     * the canonical raw encoding.
     */
    fun replayRawToState(raw: CharSequence, state: SyllableState) {
        state.reset()
        for (i in 0 until raw.length) {
            applyKey(state, raw[i], isStaticReDerive = false)
        }
    }

        fun compileRaw(raw: CharSequence, vietnamese: Boolean, out: OwnedBuffer) {
        out.clear()
        val rawLen = raw.length
        if (rawLen == 0) return
        if (!vietnamese || !vietnameseModeEnabled) {
            out.append(raw)
            return
        }
        replayState.reset()
        for (i in 0 until rawLen) {
            when (feedChar(replayState, raw[i], true, isStaticReDerive = false, out = replayOut)) {
                CoreStep.BOUNDARY -> {
                    out.append(replayOut.committedBuffer)
                    out.append(replayOut.separator)
                }
                CoreStep.MUTATION -> { /* display updated by the kernel */ }
            }
        }
        replayState.toDisplayBuffer(out, options.oldTonePlacement)
    }

    private enum class CoreStep { BOUNDARY, MUTATION }

    private class StepOut {
        val committedBuffer = OwnedBuffer()
        var separator: Char = ' '
        val displayBuffer = OwnedBuffer()
    }

    private fun feedChar(
        state: SyllableState,
        c: Char,
        vietnamese: Boolean = true,
        isStaticReDerive: Boolean = false,
        out: StepOut
    ): CoreStep {
        if (BoundaryClassifier.isBoundaryChar(c)) {
            out.committedBuffer.clear()
            state.toDisplayBuffer(out.committedBuffer, options.oldTonePlacement)
            out.separator = c
            state.reset()
            return CoreStep.BOUNDARY
        }

        if (!vietnamese) {
            state.rawSuffix += c
            out.displayBuffer.clear()
            state.toDisplayBuffer(out.displayBuffer, options.oldTonePlacement)
            return CoreStep.MUTATION
        }

        applyKey(state, c, isStaticReDerive = isStaticReDerive)
        out.displayBuffer.clear()
        state.toDisplayBuffer(out.displayBuffer, options.oldTonePlacement)
        return CoreStep.MUTATION
    }


    private val foldResult = VietnamesePhonology.FoldResult()


    /**
     * Result of word adoption — contains all info needed by the Controller.
     */
    data class AdoptResult(
        val isValid: Boolean,
        val onsetLength: Int,
        val canonicalRaw: String
    )

    /**
     * Adopt a word: parse display text into syllable components and generate canonical raw keystrokes.
     * Returns null if word is empty, or AdoptResult with all needed info.
     */
    fun adoptWord(word: String): AdoptResult? {
        if (word.isEmpty()) return null

        // Parse display text into phonological components
        val nfcWord = VietnameseUnicode.normalizeNfc(word)

        // 1. Extract tone
        var detectedTone = Tone.NONE
        val untonedChars = StringBuilder()
        for (c in nfcWord) {
            val t = extractToneFromChar(c)
            if (t != Tone.NONE && detectedTone == Tone.NONE) detectedTone = t
            untonedChars.append(VietnameseUnicode.stripTone(c))
        }
        val baseWord = untonedChars.toString()
        val baseLower = baseWord.lowercase()

        // 2. Extract onset (longest valid initial consonant)
        var onset = ""
        var remainingAfterOnset = baseWord
        for (cand in VietnamesePhonology.ONSETS) {
            if (baseLower.startsWith(cand)) {
                if (cand == "gi" && baseLower.length > 2 && VietnamesePhonology.isBaseVowel(baseLower[2])) {
                    onset = baseWord.substring(0, 2)
                    remainingAfterOnset = baseWord.substring(2)
                    break
                } else if (cand == "qu" && baseLower.length > 2 && VietnamesePhonology.isBaseVowel(baseLower[2])) {
                    onset = baseWord.substring(0, 2)
                    remainingAfterOnset = baseWord.substring(2)
                    break
                } else if (cand == "gi" && (baseLower.length == 2 || !VietnamesePhonology.isBaseVowel(baseLower[2]))) {
                    onset = baseWord.substring(0, 1)
                    remainingAfterOnset = baseWord.substring(1)
                    break
                } else {
                    onset = baseWord.substring(0, cand.length)
                    remainingAfterOnset = baseWord.substring(cand.length)
                    break
                }
            }
        }

        // 3. Extract nucleus (contiguous vowels)
        val nucleusSb = StringBuilder()
        var remIdx = 0
        while (remIdx < remainingAfterOnset.length && VietnamesePhonology.isBaseVowel(remainingAfterOnset[remIdx])) {
            nucleusSb.append(remainingAfterOnset[remIdx])
            remIdx++
        }
        val nucleus = nucleusSb.toString()
        val remainingAfterNucleus = remainingAfterOnset.substring(remIdx)
        val remLower = remainingAfterNucleus.lowercase()

        // 4. Extract coda
        var coda = ""
        var rawSuffix = ""
        if (nucleus.isNotEmpty()) {
            var matchedCoda = false
            for (cand in VietnamesePhonology.CODAS) {
                if (remLower.startsWith(cand)) {
                    val candidateRime = nucleus.lowercase() + cand
                    if (VietnamesePhonology.isValidPrefix(candidateRime) &&
                        VietnamesePhonology.isValidToneForRime(candidateRime, detectedTone)) {
                        coda = remainingAfterNucleus.substring(0, cand.length)
                        rawSuffix = remainingAfterNucleus.substring(cand.length)
                        matchedCoda = true
                        break
                    }
                }
            }
            if (!matchedCoda) rawSuffix = remainingAfterNucleus
        } else {
            rawSuffix = remainingAfterNucleus
        }

        val hasValidRime = nucleus.isNotEmpty() && VietnamesePhonology.isValidRime(nucleus.lowercase() + coda.lowercase())
        val validTone = if (hasValidRime) detectedTone else Tone.NONE
        val validSuffix = if (hasValidRime) rawSuffix else (if (detectedTone != Tone.NONE) word.substring(onset.length) else rawSuffix)

        // 5. Validate and build result
        val rimeKey = nucleus.lowercase() + coda.lowercase()
        val isValidRimeOrPrefix = if (nucleus.isEmpty()) {
            onset.isNotEmpty() && coda.isEmpty()
        } else {
            VietnamesePhonology.isValidPrefix(rimeKey) &&
            VietnamesePhonology.isValidToneForRime(rimeKey, validTone)
        }
        val isValid = validSuffix.isEmpty() && isValidRimeOrPrefix

        // 6. Generate canonical raw keystrokes
        val canonicalRaw = if (isValid) {
            val sb = StringBuilder()
            val onsetLower = onset.lowercase()
            when (onsetLower) {
                "đ" -> sb.append(if (onset == "Đ") "DD" else if (onset[0].isUpperCase()) "Dd" else "dd")
                else -> sb.append(onset)
            }
            val nucleusLower = nucleus.lowercase()
            val nucAllUpper = nucleus.isNotEmpty() && nucleus.all { it.isUpperCase() }
            val nucFirstUpper = nucleus.isNotEmpty() && nucleus[0].isUpperCase()
            if (nucleusLower == "ươ") {
                sb.append(if (nucAllUpper) "UWO" else if (nucFirstUpper) "Uwo" else "uwo")
            } else if (nucleusLower == "ưa") {
                sb.append(if (nucAllUpper) "UWA" else if (nucFirstUpper) "Uwa" else "uwa")
            } else if (nucleusLower == "uơ") {
                sb.append(if (nucAllUpper) "UOW" else if (nucFirstUpper) "Uow" else "uow")
            } else {
                for (c in nucleus) {
                    val cl = c.lowercaseChar()
                    val isUpper = c.isUpperCase()
                    when (cl) {
                        'â' -> sb.append(if (isUpper) "Aa" else "aa")
                        'ă' -> sb.append(if (isUpper) "Aw" else "aw")
                        'ê' -> sb.append(if (isUpper) "Ee" else "ee")
                        'ô' -> sb.append(if (isUpper) "Oo" else "oo")
                        'ơ' -> sb.append(if (isUpper) "Ow" else "ow")
                        'ư' -> sb.append(if (isUpper) "Uw" else "uw")
                        else -> sb.append(c)
                    }
                }
            }
            sb.append(coda)
            val toneKey = when (validTone) {
                Tone.ACUTE -> 's'; Tone.GRAVE -> 'f'; Tone.HOOK -> 'r'
                Tone.TILDE -> 'x'; Tone.DOT -> 'j'; Tone.NONE -> null
            }
            if (toneKey != null) {
                sb.append(if (nucAllUpper) toneKey.uppercaseChar() else toneKey)
            }
            VietnameseUnicode.applyCasingFromRaw(sb.toString(), word)
        } else {
            word
        }

        // 7. Return the parse result. The controller gates adoption by round-tripping
        // canonicalRaw through compileRaw() against the committed word, so a canonical
        // encoding that does not replay faithfully is never adopted (fresh typing instead).
        return AdoptResult(isValid, onset.length, canonicalRaw)
    }

    private fun extractToneFromChar(c: Char): Tone {
        val lower = c.lowercaseChar()
        return when (lower) {
            'á', 'ắ', 'ấ', 'é', 'ế', 'í', 'ó', 'ố', 'ớ', 'ú', 'ứ', 'ý' -> Tone.ACUTE
            'à', 'ằ', 'ầ', 'è', 'ề', 'ì', 'ò', 'ồ', 'ờ', 'ù', 'ừ', 'ỳ' -> Tone.GRAVE
            'ả', 'ẳ', 'ẩ', 'ẻ', 'ể', 'ỉ', 'ỏ', 'ổ', 'ở', 'ủ', 'ử', 'ỷ' -> Tone.HOOK
            'ã', 'ẵ', 'ẫ', 'ẽ', 'ễ', 'ĩ', 'õ', 'ỗ', 'ỡ', 'ũ', 'ữ', 'ỹ' -> Tone.TILDE
            'ạ', 'ặ', 'ậ', 'ẹ', 'ệ', 'ị', 'ọ', 'ộ', 'ợ', 'ụ', 'ự', 'ỵ' -> Tone.DOT
            else -> Tone.NONE
        }
    }

    fun process(raw: String): String = processString(raw)

    fun processString(raw: String): String {
        if (raw.isEmpty()) return ""
        compileRaw(raw, true, stringOut)
        return stringOut.toStringVal()
    }



    companion object {
        private val displayBuffer = ThreadLocal.withInitial { CharArray(32) }

        private fun ensureBuffer(size: Int): CharArray {
            val current = displayBuffer.get() ?: return CharArray(size.coerceAtLeast(64))
            return if (current.size >= size) current else {
                val grown = CharArray(size.coerceAtLeast(64))
                displayBuffer.set(grown)
                grown
            }
        }

        @JvmStatic
        fun isToneKey(c: Char): Boolean = when (c.lowercaseChar()) {
            's', 'f', 'r', 'x', 'j', 'z' -> true
            else -> false
        }

        @JvmStatic
        fun isVowelModifierKey(c: Char): Boolean = c.lowercaseChar() in VOWEL_MODIFIER_KEYS


        /** Modifier key → TargetType for fold dispatch. */
        private val MODIFIER_TARGET_TYPES = mapOf(
            'e' to TargetType.E_NUCLEUS,
            'o' to TargetType.O_NUCLEUS,
            'a' to TargetType.A_NUCLEUS
        )

        /** Nucleus auto-promotion: uơ → ươ when consonant follows. */
        private val NUCLEUS_AUTOPROMOTIONS = mapOf("uơ" to true)

        private val VOWEL_MODIFIER_KEYS = setOf('e', 'o', 'a', 'w')
    }

    private fun applyKey(state: SyllableState, c: Char, isStaticReDerive: Boolean): Boolean {
        val lower = c.lowercaseChar()
        val isUpper = c.isUpperCase()

        if (state.rawSuffix.isNotEmpty()) {
            if (lower == 'a' && state.rawSuffix == "n" && state.coda == "n" && state.nucleus.contains('ă')) {
                val handled = handleVowelModifierKey(state, lower, isUpper, isStaticReDerive)
                if (handled) {
                    state.rawSuffix = ""
                    return true
                }
            }
            state.lastToggle = null
            state.rawSuffix += c
            return true
        }

        if (lower == 'd') {
            val handled = handleKeyD(state, c)
            if (handled) return true
        }

        if (isToneKey(lower)) {
            val handled = handleToneKey(state, lower)
            if (handled) return true
        }

        if (isVowelModifierKey(lower)) {
            val handled = handleVowelModifierKey(state, lower, isUpper, isStaticReDerive)
            if (handled) return true
        }

        if (VietnamesePhonology.isBaseVowel(lower)) {
            return handleVowelChar(state, c)
        }

        if (VietnamesePhonology.isConsonant(lower)) {
            return handleConsonantChar(state, c)
        }

        state.lastToggle = null
        state.rawSuffix += c
        return true
    }

    private fun handleKeyD(state: SyllableState, c: Char): Boolean {
        val isUpper = c.isUpperCase()

        if (state.lastToggle?.key == 'd' && (state.onset.lowercase() == "đ")) {
            val dChar = if (state.onset[0].isUpperCase()) "D" else "d"
            state.onset = dChar
            val extraChar = if (isUpper) "D" else "d"
            if (state.nucleus.isEmpty() && state.coda.isEmpty() && state.rawSuffix.isEmpty()) {
                state.onset = dChar + extraChar
            } else {
                state.rawSuffix += extraChar
            }
            state.lastToggle = null
            return true
        }

        val onsetLower = state.onset.lowercase()
        if (onsetLower == "d") {
            val dChar = if (state.onset[0].isUpperCase()) "Đ" else "đ"
            state.onset = dChar
            val hadCharsAfter = state.nucleus.isNotEmpty() || state.coda.isNotEmpty()
            state.lastToggle = LastToggle(key = 'd', targetType = TargetType.D_ONSET, hadCharsAfter = hadCharsAfter)
            return true
        }

        if (state.onset.isEmpty() && state.nucleus.isEmpty() && state.coda.isEmpty()) {
            state.onset = c.toString()
            state.lastToggle = null
            return true
        }

        state.lastToggle = null
        state.rawSuffix += c
        return true
    }

    private fun handleToneKey(state: SyllableState, key: Char): Boolean {
        if (state.nucleus.isEmpty()) return false

        if (state.coda.isNotEmpty() && !VietnamesePhonology.isValidCoda(state.coda)) {
            return false
        }

        val targetTone = Tone.fromKey(key) ?: return false

        val nucleusLower = state.nucleus.lowercase()
        if (nucleusLower == "aa" || nucleusLower == "ee") {
            return false
        }

        // Single trie walk: validate rime + tone + get node for position lookup
        val currentRime = state.nucleus + state.coda
        val rimeNode = VietnamesePhonology.validateRimeAndFindNode(currentRime)
        if (!VietnamesePhonology.isRimeNodeValidForTone(rimeNode, targetTone)) {
            return false
        }

        if (key == 'z') {
            if (state.tone != Tone.NONE) {
                state.tone = Tone.NONE
                state.lastToggle = null
                return true
            }
            return false
        }

        if (state.tone == targetTone) {
            state.tone = Tone.NONE
            state.rawSuffix += key
            state.lastToggle = null
            return true
        } else {
            state.tone = targetTone
            state.lastToggle = null
            return true
        }
    }

    private fun handleVowelModifierKey(
        state: SyllableState,
        key: Char,
        isUpper: Boolean,
        isStaticReDerive: Boolean
    ): Boolean {
        if (state.lastToggle?.key == key) {
            val toggle = state.lastToggle!!
            val untoggled = untoggleVowelModifier(state, toggle, key, isUpper)
            if (untoggled) {
                state.lastToggle = null
                return true
            }
        }

        if (isStaticReDerive && (key == 'a' || key == 'e' || key == 'u') && key != 'o') {
            return false
        }

        val targetType = MODIFIER_TARGET_TYPES[key]
        if (targetType != null) {
            foldResult.nucleus = ""
            foldResult.hadCharsAfter = false
            if (VietnamesePhonology.foldSingle(
                    state.nucleus,
                    VietnamesePhonology.foldRulesFor(key),
                    state.coda,
                    state.onset,
                    foldResult
                )
            ) {
                state.nucleus = foldResult.nucleus
                state.lastToggle = LastToggle(key, targetType, foldResult.hadCharsAfter)
                return true
            }
            return false
        }

        if (key == 'w') return handleKeyW(state, isUpper)
        return false
    }

    private fun handleKeyW(state: SyllableState, isUpper: Boolean): Boolean {
        if (state.nucleus.isEmpty()) {
            if (options.directW) {
                val wChar = if (isUpper) "W" else "w"
                if (state.onset.isEmpty()) {
                    state.onset = wChar
                } else {
                    state.rawSuffix += wChar
                }
                state.lastToggle = null
                return true
            }
            val uChar = if (isUpper) 'Ư' else 'ư'
            state.nucleus = uChar.toString()
            state.lastToggle = LastToggle(key = 'w', targetType = TargetType.W_SOLO, hadCharsAfter = false)
            return true
        }

        val result = VietnamesePhonology.applyW(state.nucleus, state.coda, state.onset)
        if (result != null) {
            state.nucleus = result.first
            result.second?.let { state.lastToggle = it }
            return true
        }
        return false
    }

    private fun untoggleVowelModifier(
        state: SyllableState,
        toggle: LastToggle,
        key: Char,
        isUpper: Boolean
    ): Boolean {
        if (toggle.targetType == TargetType.W_SOLO) {
            val extraChar = if (isUpper) 'W' else 'w'
            state.nucleus = ""
            state.rawSuffix = extraChar.toString()
            return true
        }

        val res = VietnamesePhonology.UnfoldResult()
        if (!VietnamesePhonology.unfold(state.nucleus, toggle.targetType, isUpper, res)) {
            return false
        }
        state.nucleus = res.nucleus

        if (state.coda.isEmpty() && state.rawSuffix.isEmpty() && res.foldedIndex == res.nucleus.length - 1) {
            state.nucleus += res.tail
            return true
        }
        state.rawSuffix += res.tail
        return true
    }

    private fun handleVowelChar(state: SyllableState, c: Char): Boolean {
        val lower = c.lowercaseChar()

        // 1. Onset promotion: gi+V → onset "gi", V becomes nucleus; qu+V → onset "qu", V becomes nucleus
        val promotedOnset = VietnamesePhonology.lookupOnsetPromotion(state.onset, state.nucleus)
        if (promotedOnset != null && state.coda.isEmpty()) {
            if (state.onset[0].isUpperCase()) {
                state.onset = promotedOnset.replaceFirstChar { it.uppercase() }
            } else {
                state.onset = promotedOnset
            }
            state.nucleus = c.toString()
            state.lastToggle = null
            return true
        }

        // 2. Vowel combination: ư+o→ươ, ư+a→ưa, uơ+i→ươi, uơ+u→ươu
        val combo = VietnamesePhonology.lookupVowelCombination(state.nucleus, c)
        if (combo != null) {
            state.nucleus = combo
            state.lastToggle = null
            return true
        }

        // 3. Normal vowel expansion into nucleus (if no coda yet)
        if (state.coda.isEmpty()) {
            val candidate = state.nucleus + c
            if (VietnamesePhonology.isValidPrefix(candidate)) {
                state.nucleus = candidate
                state.lastToggle = null
                return true
            }
            state.lastToggle = null
            state.rawSuffix += c
            return true
        }

        // 4. Coda exists → append to raw suffix
        state.lastToggle = null
        state.rawSuffix += c
        return true
    }

    private fun handleConsonantChar(state: SyllableState, c: Char): Boolean {
        if (state.nucleus.isEmpty()) {
            val candidate = state.onset + c
            if (VietnamesePhonology.isValidOnset(candidate) || state.onset.isEmpty()) {
                state.onset = candidate
                state.lastToggle = null
                return true
            }
            state.lastToggle = null
            state.rawSuffix += c
            return true
        }

        var effectiveNucleus = state.nucleus
        if (NUCLEUS_AUTOPROMOTIONS.containsKey(effectiveNucleus.lowercase())) {
            effectiveNucleus = VietnamesePhonology.buildUoPair(effectiveNucleus[0], effectiveNucleus[1], hornU = true)
        }

        // Single-pass validation: coda validity + rime validity + tone validity
        val candidateCoda = state.coda + c
        if (VietnamesePhonology.validateCodaAddition(effectiveNucleus, candidateCoda, state.tone)) {
            state.nucleus = effectiveNucleus
            state.coda = candidateCoda
            state.lastToggle = null
            return true
        }

        state.lastToggle = null
        state.rawSuffix += c
        return true
    }



    // ==========================================
    // PREFS / MACRO / CONFIG (merged from VietnameseInputEngine)
    // ==========================================

    var macroStore: MacroStore? = null
    private var macroPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var settingsPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun loadPreferences(context: Context) {
        try {
            AppPreferences.init(context)
            val config = AppPreferences.getEngineConfig()
            applyConfig(config)
            macroStore = MacroRepository(context).loadMacroStore()
            if (macroPrefsListener == null) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (AppPreferences.isMacroDataKey(key)) {
                        reloadMacroStore(context)
                    }
                }
                macroPrefsListener = listener
                AppPreferences.registerMacroPrefsListener(listener)
            }
            if (settingsPrefsListener == null) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                    loadPreferences(context)
                }
                settingsPrefsListener = listener
                AppPreferences.registerSettingsPrefsListener(listener)
            }
        } catch (e: Exception) {
            System.err.println("[VietnameseComposer] Failed to load preferences: ${e.message}")
        }
    }

    fun cleanup() {
        macroPrefsListener?.let {
            AppPreferences.unregisterMacroPrefsListener(it)
            macroPrefsListener = null
        }
        settingsPrefsListener?.let {
            AppPreferences.unregisterSettingsPrefsListener(it)
            settingsPrefsListener = null
        }
    }

    fun reloadMacroStore(context: Context) {
        try {
            AppPreferences.init(context)
            applyConfig(AppPreferences.getEngineConfig())
            macroStore = MacroRepository(context).loadMacroStore()
            reset()
        } catch (e: Exception) {
            System.err.println("[VietnameseComposer] Failed to reload macro store: ${e.message}")
        }
    }

    fun savePreferences(
        context: Context,
        macro: Boolean = options.macroEnabled,
        alwaysMac: Boolean = options.alwaysMacro,
        autoCap: Boolean = autoCapitalize,
        dirW: Boolean = options.directW,
        oldTone: Boolean = options.oldTonePlacement
    ) {
        try {
            AppPreferences.init(context)
            val config = EngineConfig(
                macroEnabled = macro,
                alwaysMacro = alwaysMac,
                autoCapitalize = autoCap,
                directW = dirW,
                oldTonePlacement = oldTone
            )
            AppPreferences.setEngineConfig(config)
            applyConfig(config)
        } catch (e: Exception) {
            System.err.println("[VietnameseComposer] Failed to save preferences: ${e.message}")
        }
    }

    private fun applyConfig(config: EngineConfig) {
        options.macroEnabled = config.macroEnabled
        options.alwaysMacro = config.alwaysMacro
        options.directW = config.directW
        options.oldTonePlacement = config.oldTonePlacement
        autoCapitalize = config.autoCapitalize
    }
}
