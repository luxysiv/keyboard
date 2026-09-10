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
    private const val ONSET_ALPHA = "aăâeêioôơuưycmntpghbdkđlrsvx"
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
    private const val TABLE_MASK = TABLE_SIZE - 1

    private lateinit var _keys: IntArray
    private lateinit var _data: ByteArray
    private lateinit var _fold: ByteArray
    private lateinit var _foldKey: CharArray  // reverse: fold-result slot → fold key
    // bit 0: isComplete, bit 1: isPrefix (of some longer onset),
    // bit 2: allows the OPEN rime "uơ" (huơ, thuở, khuơ, quơ, luơ…)
    //
    // `_fold[slot]` carries the Telex fold target for the onset (only d→đ
    // today), same 16-bit code shape as RimeMap folds — folded down to one
    // byte since onset folds are always single-char at position 0:
    //   bits 0-2: position, bits 3-7: replacement char index (0 = no fold).

    init { build() }

    private fun build() {
        _keys = IntArray(TABLE_SIZE)
        _data = ByteArray(TABLE_SIZE)
        _fold = ByteArray(TABLE_SIZE)
        _foldKey = CharArray(TABLE_SIZE)
        // Insert all complete onsets (NO prefix entries for single chars
        // like 'q' — those are handled by isPrefixOfCompound).
        for (o in ALL_ONSETS) insertOr(onsetKey(o), 0x01)
        // Onsets after which the open rime "uơ" is real (list from the actual
        // words containing the vần "uơ": huơ, thuở, khuơ, quơ, luơ).
        val openUoOnsets = arrayOf("h", "th", "kh", "qu", "l")
        for (o in openUoOnsets) insertOr(onsetKey(o), 0x04)
        // Fold target: plain 'd' onset + 'd' → 'đ' (the fold/untoggle cycle is
        // the SAME mechanism as the nucleus folds — data on the map value).
        val dSlot = find(onsetKey("d"))
        if (dSlot >= 0) {
            _fold[dSlot] = foldCode(0, 'đ').toByte()
            // Record reverse: đ's slot maps back to fold key 'd'
            val foldedSlot = find(onsetKey("đ"))
            if (foldedSlot >= 0) _foldKey[foldedSlot] = 'd'
        }
    }

    /** Pack a single-char onset fold replacement (always position 0 today). */
    private fun foldCode(pos: Int, to: Char): Int =
        (pos and 7) or (charIndex(to) shl 3)

    private fun insertOr(key: Int, data: Int) {
        var slot = (key * -0x61c88647).toInt() and TABLE_MASK
        while (true) {
            if (_keys[slot] == key) { _data[slot] = (_data[slot].toInt() or data).toByte(); return }
            if (_keys[slot] == 0) { _keys[slot] = key; _data[slot] = data.toByte(); return }
            slot = (slot + 1) and TABLE_MASK
        }
    }

    private fun find(key: Int): Int {
        var i = (key * -0x61c88647).toInt() and TABLE_MASK
        while (true) {
            if (_keys[i] == key) return i
            if (_keys[i] == 0) return -1
            i = (i + 1) and TABLE_MASK
        }
    }

    /** Valid onset or prefix of one (for composition: 't' passes because 'th'/'tr' exist). */
    fun isValidOnset(onset: CharSequence, start: Int = 0, length: Int = onset.length - start): Boolean =
        length == 0 || find(onsetKey(onset, start, length)) >= 0

    /** Single character is valid onset or prefix of one. */
    fun isValidOnsetSingle(c: Char): Boolean = find(onsetKey(c)) >= 0

    // First chars of compound onsets ("th","tr","ch","ph","kh","gh","gi","qu","ng","nh","ngh").
    // Char-SET (not charIndex) so 'q' never collides with 'a' — both would
    // encode to index 0 in the 5-bit alphabet.
    private val COMPOUND_FIRST_CHARS = ALL_ONSETS.filter { it.length > 1 }.map { it[0].lowercaseChar() }.toSet()

    /**
     * Single char is the first char of a compound onset (e.g. 't' in "th").
     * 'q' is included because "qu" is a valid onset — but 'q' is NOT returned
     * as a complete onset, only as a prefix that can grow into "qu".
     */
    fun isPrefixOfCompound(c: Char): Boolean = c.lowercaseChar() in COMPOUND_FIRST_CHARS

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
        val slot = find(onsetKey)
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
        val slot = find(onsetKey)
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
        val i = find(onsetKey(onset, start, length))
        return i >= 0 && (_data[i].toInt() and 1) != 0
    }

    /**
     * True when the open rime "uơ" is valid after this onset
     * (empty onset → true: "uơ" itself is a real syllable).
     */
    fun allowsOpenUo(onset: CharSequence, start: Int = 0, length: Int = onset.length - start): Boolean {
        if (length == 0) return true
        val i = find(onsetKey(onset, start, length))
        return i >= 0 && (_data[i].toInt() and 4) != 0
    }
}
