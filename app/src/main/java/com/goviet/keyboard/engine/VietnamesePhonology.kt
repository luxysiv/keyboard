package com.goviet.keyboard.engine

/**
 * VietnamesePhonology: Unified Vietnamese phonological knowledge base.
 *
 * Merged from: VietnameseFiniteStateTable, VietnameseSpellingGuide, VietnamesePhonology.
 * Single Source of Truth for:
 * - Bit-packed flat map (fibonacci hash + open addressing) for
 *   rime/nucleus validation & tone placement — see RimeMap
 * - Onset/coda phonological rules
 * - Telex fold/unfold rules and tone placement
 * - Vietnamese vowel/consonant inventories
 */
object VietnamesePhonology {

    // ============================================================
    // SECTION 1: LEXICON (vowels, consonants, onsets, codas)
    // ============================================================
/**
     * 12 Vietnamese base vowels (unaccented) — single source of truth.
     * Derived from the Vietnamese vowel inventory: a ă â e ê i o ô ơ u ư y.
     */
    val BASE_VOWELS = "aăâeêioôơuưy"

    /** Telex tone keys: s(acute), f(grave), r(hook), x(tilde), j(dot), z(clear). */
    val TONE_KEYS = "sfrxjz"

    /** Telex vowel modifier keys (fold triggers): e(→ê), o(→ô), a(→â), w(→ă/ơ/ư). */
    val VOWEL_MOD_KEYS = "eoaw"

    // ── BooleanArray(512) fast-path lookups (O(1), zero allocation) ──
    private val BASE_VOWEL_SET = BooleanArray(512).also { arr ->
        for (c in BASE_VOWELS) arr[c.code] = true
    }
    private val TONE_KEY_SET = BooleanArray(512).also { arr ->
        for (c in TONE_KEYS) arr[c.code] = true
    }
    private val VOWEL_MOD_SET = BooleanArray(512).also { arr ->
        for (c in VOWEL_MOD_KEYS) arr[c.code] = true
    }


    private val VOWELS = setOf(
        'a', 'ă', 'â', 'e', 'ê', 'i', 'y', 'o', 'ô', 'ơ', 'u', 'ư',
        'á', 'ắ', 'ấ', 'é', 'ế', 'í', 'ý', 'ó', 'ố', 'ớ', 'ú', 'ứ',
        'à', 'ằ', 'ầ', 'è', 'ề', 'ì', 'ỳ', 'ò', 'ồ', 'ờ', 'ù', 'ừ',
        'ả', 'ẳ', 'ẩ', 'ẻ', 'ể', 'ỉ', 'ỷ', 'ỏ', 'ổ', 'ở', 'ủ', 'ử',
        'ã', 'ẵ', 'ẫ', 'ẽ', 'ễ', 'ĩ', 'ỹ', 'õ', 'ỗ', 'ỡ', 'ũ', 'ữ',
        'ạ', 'ặ', 'ậ', 'ẹ', 'ệ', 'ị', 'ỵ', 'ọ', 'ộ', 'ợ', 'ụ', 'ự'
    )
    // Accented vowels go up to ự (U+1EF1 = 7855) — need 8192 slots.
    private val VOWEL_SET = BooleanArray(8192).also { arr ->
        for (c in VOWELS) arr[c.code] = true
    }

    /** All valid Vietnamese onsets — delegated to [OnsetMap]. */
    val ONSETS = OnsetMap.ALL_ONSETS

    /**
     * Valid final consonantal clusters.
     */
    val CODAS = arrayOf("ng", "nh", "ch", "m", "p", "n", "t", "c")

    /**
     * True if [c] is one of the 12 base Vietnamese vowels (unaccented), used while
     * actively composing Telex input where accents are applied separately.
     */
    fun isBaseVowel(c: Char): Boolean {
        val code = c.lowercaseChar().code
        return code in 0 until 512 && BASE_VOWEL_SET[code]
    }

    /**
     * True if [c] is any Vietnamese vowel including accented variants, used when
     * recognizing already-tone-marked completed words.
     */
    fun isVowel(c: Char): Boolean {
        val code = c.lowercaseChar().code
        return code in 0 until VOWEL_SET.size && VOWEL_SET[code]
    }

    /** Telex tone key (s/f/r/x/j/z) — O(1) BooleanArray lookup. */
    fun isToneKey(c: Char): Boolean {
        val code = c.lowercaseChar().code
        return code in 0 until 512 && TONE_KEY_SET[code]
    }

    /** Vowel-modifier/fold key (e/o/a/w) — O(1) BooleanArray lookup. */
    fun isFoldKey(c: Char): Boolean {
        val code = c.lowercaseChar().code
        return code in 0 until 512 && VOWEL_MOD_SET[code]
    }

// ============================================================
    // SECTION 2: FLAT MAP RIME DATA (validation, tone placement)
    // ============================================================

    /**
     * Rime validation and tone placement are delegated to [RimeMap] — a flat
     * map with direct integer key lookup.  All lookups are O(1) with no FNV
     * multiplication — just compact bit-shift key computation + flat primitive probe.
     */

    /**
     * Check if [candidate] (a substring) is a valid prefix of any Vietnamese rime.
     */
    fun isValidPrefix(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean {
        if (length == 0) return true
        return RimeMap.isValidPrefix(RimeMap.rimeKey(candidate, start, length))
    }

    /**
     * Check if [candidate] is a complete valid rime.
     */
    fun isCompleteRime(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean {
        if (length == 0) return false
        return RimeMap.isComplete(RimeMap.rimeKey(candidate, start, length))
    }

    fun isValidRime(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean =
        isCompleteRime(candidate, start, length)

    fun isStopCoda(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean {
        if (length == 0) return false
        return RimeMap.isStop(RimeMap.rimeKey(candidate, start, length))
    }

    fun getTonePosition(candidate: CharSequence, oldTonePlacement: Boolean, start: Int = 0, length: Int = candidate.length - start): Int {
        if (length == 0) return 0
        val k = RimeMap.rimeKey(candidate, start, length)
        val i = RimeMap.indexOf(k)
        if (i < 0) return 0
        return if (oldTonePlacement) RimeMap.toneOldAt(i) else RimeMap.toneNewAt(i)
    }

    // ============================================================
    // COMBINED VALIDATION (hot-path helpers)
    // ============================================================

    /**
     * Validate that a rime is valid for a specific tone.
     * Used by [VietnameseComposer.handleToneKey].
     */
    fun isRimeValidForTone(rime: String, tone: Tone): Boolean {
        if (rime.isEmpty()) return false
        return RimeMap.isToneAllowed(RimeMap.rimeKey(rime), tone.index)
    }

    /**
     * Validate that a rime (defined by its precomputed key) is valid for a specific tone.
     * Key is an Int rimeKey from [RimeMap.rimeKey] — passed as Long for backward compatibility.
     */
    fun isRimeHashValidForTone(key: Long, tone: Tone): Boolean =
        RimeMap.isToneAllowed(key.toInt(), tone.index)

    /**
     * Determine tone position from a precomputed rime key — zero allocation.
     * Key is an Int rimeKey from [RimeMap.rimeKey] — passed as Long for backward compatibility.
     */
    fun determineTonePositionHash(rimeKey: Long, oldTonePlacement: Boolean, nucleusLength: Int = 0): Int {
        val key = rimeKey.toInt()
        val i = RimeMap.indexOf(key)
        // Prefix-only entries (incomplete rimes like "oon" while the coda is still
        // being typed) carry no meaningful tone data — fall back to the last nucleus
        // vowel so the mark sits where it will land once the rime completes
        // (cooosn -> coón, not cóon).
        if (i < 0 || !RimeMap.isComplete(key)) return (nucleusLength - 1).coerceAtLeast(0)
        return if (oldTonePlacement) RimeMap.toneOldAt(i) else RimeMap.toneNewAt(i)
    }

    /**
     * Determine tone mark position with onset prefix preprocessing (qu/gi).
     */
    fun findTonePosition(onset: CharSequence, rime: CharSequence, oldTonePlacement: Boolean): Int? {
        val onsetLen = onset.length
        val rimeLen = rime.length
        if (rimeLen == 0) return null

        var rimeStart = 0
        var offset = 0

        if (rimeLen > 1 && onsetLen > 0) {
            val rimeFirst = rime[0]
            val isRimeFirstU = rimeFirst == 'u' || rimeFirst == 'U'
            val isRimeFirstI = rimeFirst == 'i' || rimeFirst == 'I'
            val isQ = (onset[onsetLen - 1] == 'q' || onset[onsetLen - 1] == 'Q') ||
                    (onsetLen >= 2 && (onset[onsetLen - 2] == 'q' || onset[onsetLen - 2] == 'Q') && (onset[onsetLen - 1] == 'u' || onset[onsetLen - 1] == 'U'))
            val isG = (onset[onsetLen - 1] == 'g' || onset[onsetLen - 1] == 'G') ||
                    (onsetLen >= 2 && (onset[onsetLen - 2] == 'g' || onset[onsetLen - 2] == 'G') && (onset[onsetLen - 1] == 'i' || onset[onsetLen - 1] == 'I'))
            if (isRimeFirstU && isQ) { rimeStart = 1; offset = 1 }
            else if (isRimeFirstI && isG) { rimeStart = 1; offset = 1 }
        }

        val k = RimeMap.rimeKey(rime, rimeStart, rimeLen - rimeStart)
        val i = RimeMap.indexOf(k)
        if (i < 0) return null
        val basePos = if (oldTonePlacement) RimeMap.toneOldAt(i) else RimeMap.toneNewAt(i)
        return basePos + offset
    }


    @Suppress("NOTHING_TO_INLINE")
    private inline fun toLower(c: Char): Char =
        if (c in 'A'..'Z') (c.code + 32).toChar() else c.lowercaseChar()

    fun isValidOnset(onset: CharSequence, start: Int = 0, length: Int = onset.length - start): Boolean =
        OnsetMap.isValidOnset(onset, start, length)

    fun isValidCoda(coda: CharSequence, start: Int = 0, length: Int = coda.length - start): Boolean {
        if (length == 0) return true
        if (length == 1) {
            val c = toLower(coda[start])
            return c == 'm' || c == 'p' || c == 'n' || c == 't' || c == 'c'
        }
        if (length == 2) {
            val c0 = toLower(coda[start])
            val c1 = toLower(coda[start + 1])
            return (c0 == 'n' && (c1 == 'g' || c1 == 'h')) || (c0 == 'c' && c1 == 'h')
        }
        return false
    }

    fun isValidWord(word: String): Boolean {
        if (word.isEmpty()) return false
        val stripped = VietnameseUnicode.stripToneFromWord(word)
        val len = stripped.length
        for (onsetLen in minOf(3, len) downTo 1) {
            if (OnsetMap.isCompleteOnset(stripped, 0, onsetLen)) {
                if (isValidRime(stripped, onsetLen, len - onsetLen)) {
                    return true
                }
            }
        }
        return isValidRime(stripped, 0, len)
    }
    // ============================================================
    // SECTION 3: SPELLING GUIDE (fold/unfold, tone placement)
    // ============================================================
    /** Plain letter that a folded display letter unfolds back to. */
    fun plainOf(folded: Char): Char = when (folded) {
        'ê' -> 'e'; 'ô' -> 'o'; 'ơ' -> 'o'; 'â' -> 'a'; 'ă' -> 'a'; 'ư' -> 'u'; 'đ' -> 'd'
        else -> folded
    }



    // ============================================================
    // VOWEL COMBINATION TABLE — vowel+vowel nucleus expansion
    // ============================================================
    /**
     * When a vowel character is typed after an existing nucleus, these rules
     * determine the resulting compound nucleus.
     * Key insight from Vietnamese phonology:
     *   ư + o → ươ, ư + a → ưa, uơ + i → ươi, uơ + u → ươu.
     */
    // Vowel combination rules moved to RimeMap.combineNucleus flatmap

    /**
     * Lookup vowel combination: nucleus + char → expanded nucleus.
     * Delegates to RimeMap flatmap (O(1) lookup).
     */
    fun lookupVowelCombination(nucleus: String, char: Char): String? =
        RimeMap.combineNucleus(nucleus, char)

    // ============================================================
    // TONE PLACEMENT — delegates to RimeMap (zero-GC)
    // ============================================================

    /**
     * Determines the character index within [rime] where the tone mark lands.
     * [rime] = nucleus + coda (e.g. "oan" for hoàn, "iêng" for tiếng).
     *
     * Pure RimeMap hash lookup — no branching, no String allocation, no
     * intermediate STYLE_VARIANT_RIMES or BARE_NUCLEI sets.  The RimeMap
     * encodes all tone positions (legacy + modern) in its primitive arrays.
     */
    fun determineTonePosition(rime: String, onset: String, placement: TonePlacement): Int {
        if (rime.isEmpty()) return 0
        val k = RimeMap.rimeKey(rime)
        val i = RimeMap.indexOf(k)
        if (i < 0) return 0
        return if (placement == TonePlacement.LEGACY) RimeMap.toneOldAt(i) else RimeMap.toneNewAt(i)
    }

}
