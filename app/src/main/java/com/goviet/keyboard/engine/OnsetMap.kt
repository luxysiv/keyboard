package com.goviet.keyboard.engine

/**
 * OnsetMap — zero-computation flat map for Vietnamese onset validation.
 *
 * Same architecture as [RimeMap]: 5-bit character encoding, Fibonacci-hash
 * table, O(1) lookup. Replaces ONSETS array + ONSET_LETTERS + isValidOnset().
 */
object OnsetMap {

    // ── Character encoding (onset-specific alphabet) ─────────────
    // 5 bits → max 32 unique indices. This alphabet covers all Vietnamese
    // onset consonants so each char gets a unique index (no collisions).
    // 'w' is an onset consonant for the direct-w option (English/loan words).
    private const val ONSET_ALPHA = "aăâeêioôơuqwycmntpghbdkđlrsvx"
    private const val W_INDEX = ONSET_ALPHA.length  // 28
    private val ONSET_AT = ONSET_ALPHA.toCharArray()
    private val CHAR_IDX = IntArray(512).also { arr ->
        for (i in ONSET_ALPHA.indices) arr[ONSET_ALPHA[i].code] = i
        arr['w'.code] = W_INDEX
    }

    private fun charIndex(c: Char): Int {
        val code = c.lowercaseChar().code
        return if (code in 0 until CHAR_IDX.size) CHAR_IDX[code] else 0
    }

    private fun onsetKey(cs: CharSequence, start: Int = 0, length: Int = cs.length - start): Int {
        var chars = 0
        var i = start
        val end = start + length
        while (i < end) { chars = (chars shl 5) or charIndex(cs[i]); i++ }
        return (length shl 25) or chars
    }

    private fun onsetKey(c: Char): Int = (1 shl 25) or charIndex(c)

    // ── Data ──────────────────────────────────────────────────────

    /**
     * All valid Vietnamese onsets — ordered longest-first for greedy prefix matching.
     * This replaces both ONSETS and ONSET_LETTERS.
     */
    val ALL_ONSETS = arrayOf(
        "ngh", "ng", "nh", "th", "tr", "ch", "ph", "kh", "gh", "gi", "qu",
        "b", "c", "d", "đ", "g", "h", "k", "l", "m", "n", "p", "r", "s", "t", "v", "w", "x"
    )

    // ── Hash table ────────────────────────────────────────────────
    private const val TABLE_BITS = 11
    private const val TABLE_SIZE = 1 shl TABLE_BITS

    private val table = IntFlatTable(TABLE_BITS)
    private lateinit var _fold: ByteArray
    private lateinit var _foldKey: CharArray  // reverse: fold-result slot → fold key
    private lateinit var _foldKeySet: BooleanArray  // fast pre-check: chars that have fold data
    // Data byte layout (only bits 0 and 2 are currently used):
    //   bit 0: isComplete — every stored entry is a complete onset; prefix
    //          detection for composition is handled by isConsonant
    //   bit 2: allows the OPEN rime "uơ" (huơ, thuở, khuơ, quơ, luơ…)
    //
    // `_fold[slot]` carries the Telex fold target for the onset (only d→đ
    // today), same 16-bit code shape as RimeMap folds — folded down to one
    // byte since onset folds are always single-char at position 0:
    //   bits 0-2: position, bits 3-7: replacement char index (0 = no fold).

    init { build() }

    private fun build() {
        _fold = ByteArray(TABLE_SIZE)
        _foldKey = CharArray(TABLE_SIZE)
        _foldKeySet = BooleanArray(512)
        // Insert all complete onsets (NO prefix entries for single chars
        // like 'q' — those are handled by isConsonant).
        for (o in ALL_ONSETS) table.insertOr(onsetKey(o), 0x01)
        // Onsets after which the open rime "uơ" is real (list derived from the
        // actual words containing the rime "uơ": huơ, thuở, khuơ, quơ, luơ).
        val openUoOnsets = arrayOf("h", "th", "kh", "qu", "l")
        for (o in openUoOnsets) table.insertOr(onsetKey(o), 0x04)
        // Fold target: plain 'd' onset + 'd' → 'đ' (the fold/untoggle cycle is
        // the SAME mechanism as the nucleus folds — data on the map value).
        val dSlot = table.find(onsetKey("d"))
        if (dSlot >= 0) {
            _fold[dSlot] = foldCode(0, 'đ').toByte()
            // Record reverse: đ's slot maps back to fold key 'd'
            val foldedSlot = table.find(onsetKey("đ"))
            if (foldedSlot >= 0) _foldKey[foldedSlot] = 'd'
            // Data-driven: 'd' is the only onset fold key today.
            _foldKeySet['d'.code] = true
        }
    }

    /**
     * True when [c] is a fold key that actually has a target in the table.
     * Built from the fold data itself; the composer uses it to skip the two
     * onset fold probes for unrelated keys (most typed characters).
     */
    @JvmStatic
    fun isRegisteredFoldKey(c: Char): Boolean {
        val code = c.lowercaseChar().code
        return code in 0 until _foldKeySet.size && _foldKeySet[code]
    }

    /** Pack a single-char onset fold replacement (always position 0 today). */
    private fun foldCode(pos: Int, to: Char): Int =
        (pos and 7) or (charIndex(to) shl 3)

    /** Valid onset or prefix of one (for composition: 't' passes because 'th'/'tr' exist). */
    fun isValidOnset(onset: CharSequence, start: Int = 0, length: Int = onset.length - start): Boolean =
        length == 0 || isCompleteOnset(onset, start, length)

    /** Longest complete onset prefix of [cs] starting at [start]; 0 when none. */
    @JvmStatic
    fun longestOnsetPrefix(cs: CharSequence, start: Int = 0, length: Int = cs.length - start): Int {
        val max = minOf(3, length)
        for (len in max downTo 1) {
            if (isCompleteOnset(cs, start, len)) return len
        }
        return 0
    }

    // Single-char check that combines "valid onset" + "first char of a compound"
    // into ONE BooleanArray(512) lookup — hot path in the composer loop.
    private val CONSONANT_BOOL = BooleanArray(512).also { arr ->
        for (o in ALL_ONSETS) if (o.length == 1) arr[o[0].lowercaseChar().code] = true
        for (o in ALL_ONSETS) if (o.length > 1) arr[o[0].lowercaseChar().code] = true
        arr['q'.lowercaseChar().code] = true
    }

    /** True if [c] is a valid onset char or the first char of a compound onset. */
    @JvmStatic
    fun isConsonant(c: Char): Boolean {
        val code = c.lowercaseChar().code
        return code in 0 until 512 && CONSONANT_BOOL[code]
    }

    /** Public packed key for an onset string — same 25-bit encoding as the table. */
    @JvmStatic
    fun onsetKeyOf(onset: CharSequence, start: Int = 0, length: Int = onset.length - start): Int =
        onsetKey(onset, start, length)

    /**
     * Fold target for [foldKey] on the onset with key [onsetKey]; 0 = none.
     * Only 'd' → 'đ' exists today (the "đ" onset itself has no fold, which is
     * what lets the composer untoggle).  Same code shape as [RimeMap] folds.
     */
    @JvmStatic
    fun foldTarget(onsetKey: Int, foldKey: Char): Int {
        if (foldKey.lowercaseChar() != 'd') return 0
        val slot = table.find(onsetKey)
        return if (slot < 0) 0 else _fold[slot].toInt()
    }

    /** Apply an onset fold [code] to [onset], preserving casing. */
    @JvmStatic
    fun applyFold(onset: String, code: Int): String {
        if (code == 0) return onset
        val p = code and 7
        val idx = (code ushr 3) and 0x1F
        if (p >= onset.length || idx >= ONSET_AT.size) return onset
        val buf = onset.toCharArray()
        val ch = ONSET_AT[idx]
        buf[p] = if (buf[p].isUpperCase()) ch.uppercaseChar() else ch
        return String(buf)
    }

    /**
     * Given an onset that IS a fold result, return the fold key that produced it.
     * Returns '\u0000' if this onset is not a fold target of any key.
     */
    @JvmStatic
    fun foldKeyForTarget(onsetKey: Int): Char {
        val slot = table.find(onsetKey)
        return if (slot < 0) '\u0000' else _foldKey[slot]
    }

    /**
     * Reverse a fold: given a folded onset and the fold key, restore the plain form.
     * Fold always operates at position 0, so we just restore that char.
     */
    @JvmStatic
    fun unfoldOnset(onset: String, foldKey: Char): String {
        if (onset.isEmpty()) return onset
        val buf = onset.toCharArray()
        val ch = foldKey.lowercaseChar()
        buf[0] = if (buf[0].isUpperCase()) ch.uppercaseChar() else ch
        return String(buf)
    }

    /** Complete valid onset (not just a prefix). */
    fun isCompleteOnset(onset: CharSequence, start: Int = 0, length: Int = onset.length - start): Boolean {
        if (length == 0) return false
        val i = table.find(onsetKey(onset, start, length))
        return i >= 0 && (table.data[i].toInt() and 1) != 0
    }

    /**
     * True when the open rime "uơ" is valid after this onset
     * (empty onset → true: "uơ" itself is a real syllable).
     */
    fun allowsOpenUo(onset: CharSequence, start: Int = 0, length: Int = onset.length - start): Boolean {
        if (length == 0) return true
        val i = table.find(onsetKey(onset, start, length))
        return i >= 0 && (table.data[i].toInt() and 4) != 0
    }
}
