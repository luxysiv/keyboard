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

            val rimeKey = RimeMap.keyCat(nucleus, nucleus.length, coda, coda.length)
            val toneIdx = VietnamesePhonology.determineTonePositionHash(rimeKey.toLong(), oldTonePlacement)

            for (i in 0 until onset.length) out.append(onset[i])
            for (i in 0 until nucleus.length) {
                if (i == toneIdx) out.append(VietnameseUnicode.applyTone(nucleus[i], tone))
                else out.append(nucleus[i])
            }
            for (i in 0 until coda.length) out.append(coda[i])
            for (i in 0 until rawSuffix.length) out.append(rawSuffix[i])
        }
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
     * Backspace never needs a snapshot: it mutates the caller's raw buffer and
     * re-derives the state with [replayRawToState].
     */
    fun feedKey(state: SyllableState, key: Char): Boolean {
        return applyKey(state, key, isStaticReDerive = false)
    }

    /**
     * Replay [raw] keystrokes into [state] from scratch. Used when adopting
     * a committed word (e.g. after space+backspace) and when re-deriving the
     * live state after a composing backspace/delete edit, so the display and
     * the next keystrokes always agree with the raw buffer.
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

        /** Compile with a maximum character limit — avoids substring allocation. */
        fun compileRaw(raw: CharSequence, vietnamese: Boolean, out: OwnedBuffer, maxLen: Int) {
        out.clear()
        val rawLen = maxLen.coerceAtMost(raw.length)
        if (rawLen == 0) return
        if (!vietnamese || !vietnameseModeEnabled) {
            out.append(raw, 0, rawLen)
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
        if (isBoundaryKey(c)) {
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
        for (onsetLen in minOf(3, baseLower.length) downTo 1) {
            if (OnsetMap.isCompleteOnset(baseLower, 0, onsetLen)) {
                onset = baseWord.substring(0, onsetLen)
                remainingAfterOnset = baseWord.substring(onsetLen)
                break
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
                        VietnamesePhonology.isRimeValidForTone(candidateRime.lowercase(), detectedTone)) {
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
            VietnamesePhonology.isRimeValidForTone(rimeKey, validTone)
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
            sb.append(nucleusToRawKeystroke(nucleus))
            val nucAllUpper = nucleus.isNotEmpty() && nucleus.all { it.isUpperCase() }
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

        private const val PROP_BOUNDARY = 32
        private const val PROP_TONE = 1
        private const val PROP_MOD = 2
        private const val PROP_VOWEL = 4
        private const val PROP_ONSET = 8
        private const val PROP_D = 16
        private const val PROP_D_ONSET = PROP_D or PROP_ONSET
        private const val PROP_TONE_ONSET = PROP_TONE or PROP_ONSET
        private const val PROP_MOD_VOWEL = PROP_MOD or PROP_VOWEL

        /**
         * Key property bitmap — one array read classifies every keystroke on the
         * applyKey hot path (boundary → d/đ → tone → vowel modifier → vowel →
         * onset → raw), replacing 5 sequential classifier checks with a single
         * lookup + one switch.  Zero allocation, cache-resident.
         * Size 512 covers all base Vietnamese letters (đ=273, ơ=417, ư=432).
         */
        private val CHAR_PROPS = ByteArray(512).also { p ->
            for (c in VietnamesePhonology.TONE_KEYS) p[c.code] = (p[c.code].toInt() or PROP_TONE).toByte()
            for (c in VietnamesePhonology.VOWEL_MOD_KEYS) p[c.code] = (p[c.code].toInt() or PROP_MOD).toByte()
            for (c in VietnamesePhonology.BASE_VOWELS) p[c.code] = (p[c.code].toInt() or PROP_VOWEL).toByte()
            for (c in 0..511) { val ch = c.toChar(); if (OnsetMap.isValidOnsetSingle(ch)) p[ch.code] = (p[ch.code].toInt() or PROP_ONSET).toByte() }
            p['d'.code] = (p['d'.code].toInt() or PROP_D).toByte() // only d → handleKeyD; đ stays an onset
            // Boundary set == BoundaryClassifier: ASCII whitespace + separators +
            // NEL(133), NBSP(160), «(171), »(187) — all < 256.
            for (code in intArrayOf(
                9, 10, 11, 12, 13, 28, 29, 30, 31, 32,
                33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47,
                58, 59, 60, 61, 62, 63, 64, 91, 92, 93, 94, 95, 96,
                123, 124, 125, 126, 133, 160, 171, 187
            )) p[code] = (p[code].toInt() or PROP_BOUNDARY).toByte()
        }

        @JvmStatic
        private fun charProps(c: Char): Int {
            val code = c.lowercaseChar().code
            return if (code < CHAR_PROPS.size) CHAR_PROPS[code].toInt() else 0
        }

        @JvmStatic
        fun isToneKey(c: Char): Boolean = charProps(c) and PROP_TONE != 0

        @JvmStatic
        fun isVowelModifierKey(c: Char): Boolean = charProps(c) and PROP_MOD != 0

        /**
         * Boundary test via the same bitmap; chars above the table fall back to
         * Character.isWhitespace (the only high-Unicode boundary class).
         */
        @JvmStatic
        private fun isBoundaryKey(c: Char): Boolean {
            val code = c.code
            if (code < CHAR_PROPS.size) return (CHAR_PROPS[code].toInt() and PROP_BOUNDARY) != 0
            return c.isWhitespace()
        }

        /** Modifier key → TargetType for fold dispatch. */
        private val MODIFIER_TARGET_TYPES = mapOf(
            'e' to TargetType.E_NUCLEUS,
            'o' to TargetType.O_NUCLEUS,
            'a' to TargetType.A_NUCLEUS
        )

        /** Nucleus auto-promotion: uơ → ươ when consonant follows. */
        private val NUCLEUS_AUTOPROMOTIONS = mapOf("uơ" to true)

        // ── Flat map: display nucleus → raw Telex keystroke (O(1)) ──────

        /** Nucleus-level flat map: only nuclei where per-char decomposition fails.
         *  "ươ" → "uwo" (NOT "uwow"; w folds u→ư, then o combines ư+o→ươ).
         *  "ưa" and "uơ" decompose correctly per-char but kept here for clarity. */
        private val NUCLEUS_RAW = arrayOf(
            "ươ" to "uwo", "ưa" to "uwa", "uơ" to "uow"
        ).toMap()

        /** Per-char flat map: char code → packed raw keystroke (2 chars in 16 bits).
         *  0 = no conversion (char typed directly, e.g. a, e, i, o, u, y).
         *  Encoding: (rawChar0 shl 8) | rawChar1. Case applied at lookup time. */
        private val CHAR_RAW = IntArray(512).also { a ->
            fun p(c: Char, r0: Char, r1: Char) { a[c.code] = (r0.code shl 8) or r1.code }
            p('â', 'a', 'a'); p('Ă', 'A', 'w'); p('ă', 'a', 'w'); p('Â', 'A', 'a')
            p('ê', 'e', 'e'); p('Ê', 'E', 'e')
            p('ô', 'o', 'o'); p('Ô', 'O', 'o')
            p('ơ', 'o', 'w'); p('Ơ', 'O', 'w')
            p('ư', 'u', 'w'); p('Ư', 'U', 'w')
        }

        /**
         * Convert a display nucleus to raw Telex keystrokes via flat map.
         * O(1) for nucleus-level match, O(n) per-char fallback.
         * Replaces the if-else chain that was in adoptWord.
         */
        @JvmStatic
        fun nucleusToRawKeystroke(nucleus: String): String {
            if (nucleus.isEmpty()) return ""
            val nucLower = nucleus.lowercase()
            val raw = NUCLEUS_RAW[nucLower]
            if (raw != null) {
                val allUpper = nucleus.all { it.isUpperCase() }
                val firstUpper = nucleus[0].isUpperCase()
                return when {
                    allUpper -> raw.uppercase()
                    firstUpper -> raw.replaceFirstChar { it.uppercase() }
                    else -> raw
                }
            }
            // Per-character decomposition via flat array
            val sb = StringBuilder()
            for (c in nucleus) {
                val packed = CHAR_RAW[c.code]
                if (packed != 0) {
                    val r0 = (packed ushr 8).toChar()
                    val r1 = (packed and 0xFF).toChar()
                    sb.append(if (c.isUpperCase()) r0.uppercaseChar() else r0)
                    sb.append(r1)
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }
    }

    private fun applyKey(state: SyllableState, c: Char, isStaticReDerive: Boolean): Boolean {
        val lower = c.lowercaseChar()
        val isUpper = c.isUpperCase()

        if (state.rawSuffix.isNotEmpty()) {
            state.lastToggle = null
            state.rawSuffix += c
            return true
        }

        // Single bitmap lookup + one switch routes every key to its handler,
        // preserving the old priority order: d/đ → tone → vowel modifier →
        // vowel → onset; everything else is raw text.
        when (charProps(lower)) {
            PROP_D_ONSET -> {
                if (handleKeyD(state, c)) return true
                return handleConsonantChar(state, c)
            }
            PROP_TONE -> {
                if (handleToneKey(state, lower)) return true
            }
            PROP_TONE_ONSET -> {
                if (handleToneKey(state, lower)) return true
                return handleConsonantChar(state, c) // r/s/x stay valid onsets
            }
            PROP_MOD -> {
                if (handleVowelModifierKey(state, lower, isUpper, isStaticReDerive)) return true
            }
            PROP_MOD_VOWEL -> {
                if (handleVowelModifierKey(state, lower, isUpper, isStaticReDerive)) return true
                return handleVowelChar(state, c) // a/e/o fall back to vowels
            }
            PROP_VOWEL -> return handleVowelChar(state, c)
            PROP_ONSET -> return handleConsonantChar(state, c)
        }

        state.lastToggle = null
        state.rawSuffix += c
        return true
    }

    private fun handleKeyD(state: SyllableState, c: Char): Boolean {
        val isUpper = c.isUpperCase()

        if (state.lastToggle?.key == 'd' && state.onset.length == 1 && state.onset[0].lowercaseChar() == 'đ') {
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

        if (state.onset.length == 1 && state.onset[0].lowercaseChar() == 'd') {
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

        // Check raw nucleus for double-letter guard (aa/ee) — direct char check, no String alloc
        val nLen = state.nucleus.length
        if (nLen >= 2) {
            val c0 = state.nucleus[0].lowercaseChar()
            val c1 = state.nucleus[1].lowercaseChar()
            if ((c0 == 'a' && c1 == 'a') || (c0 == 'e' && c1 == 'e')) return false
        }

        // Validate rime + tone in a single hash lookup — zero allocation
        val currentRimeKey = RimeMap.keyCat(state.nucleus, nLen, state.coda, state.coda.length)
        if (!VietnamesePhonology.isRimeHashValidForTone(currentRimeKey.toLong(), targetTone)) {
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
        // Empty nucleus: fold can't modify nothing → let the vowel modifier
        // key (a/e/o) fall through to handleVowelChar and become the nucleus.
        // Solo 'w' still goes to handleKeyW to produce ư.
        if (state.nucleus.isEmpty() && key != 'w') return false

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
            // Hash nucleus + new char incrementally — zero allocation
            // Direct rime key: nucleus + new vowel char — O(1) bit-shift encoding
            val candidateKey = RimeMap.extendKeySingle(RimeMap.rimeKey(state.nucleus), c)
            if (RimeMap.isValidPrefix(candidateKey)) {
                state.nucleus = state.nucleus + c
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
            if (OnsetMap.isValidOnset(candidate)) {
                state.onset = candidate
                state.lastToggle = null
                return true
            }
            state.lastToggle = null
            state.rawSuffix += c
            return true
        }

        var effectiveNucleus = state.nucleus
        // Auto-promotion check: direct lowercaseChar comparison — no String.lowercase() alloc
        val effLowerLen = effectiveNucleus.length
        if (effLowerLen == 2 && effectiveNucleus[0].lowercaseChar() == 'u' && effectiveNucleus[1].lowercaseChar() == 'ơ') {
            effectiveNucleus = VietnamesePhonology.buildUoPair(effectiveNucleus[0], effectiveNucleus[1], hornU = true)
        }

        // Validate coda + rime + tone — all hash-based, zero allocation
        // Coda max 2 chars; single-char coda must be m/p/n/t/c
        val cLow = c.lowercaseChar()
        val codaValid = if (state.coda.isEmpty()) {
            cLow == 'm' || cLow == 'p' || cLow == 'n' || cLow == 't' || cLow == 'c'
        } else if (state.coda.length == 1) {
            val c0 = state.coda[0].lowercaseChar()
            (c0 == 'n' && (cLow == 'g' || cLow == 'h')) || (c0 == 'c' && cLow == 'h')
        } else false
        if (codaValid) {
            // Build rime key incrementally: nucleus → +coda → +new coda char
            // Zero allocation — just bit-shift extendKeySingle on existing chars.
            var candidateKey = RimeMap.rimeKey(effectiveNucleus)
            var ci = 0
            while (ci < state.coda.length) { candidateKey = RimeMap.extendKeySingle(candidateKey, state.coda[ci]); ci++ }
            candidateKey = RimeMap.extendKeySingle(candidateKey, c)
            if (RimeMap.isValidPrefix(candidateKey) &&
                RimeMap.isToneAllowed(candidateKey, state.tone.index)) {
                state.nucleus = effectiveNucleus
                state.coda = state.coda + c
                state.lastToggle = null
                return true
            }
        }

        state.lastToggle = null
        state.rawSuffix += c
        return true
    }



    // ==========================================
    // PREFS / MACRO / CONFIG
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
