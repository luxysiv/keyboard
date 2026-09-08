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
    // 'w' is NOT an onset consonant; handled separately with W_INDEX.
    private const val ONSET_ALPHA = "aăâeêioôơuưycmntpghbdkđlrsvx"
    private const val W_INDEX = ONSET_ALPHA.length  // 28
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
        "b", "c", "d", "đ", "g", "h", "k", "l", "m", "n", "p", "r", "s", "t", "v", "x"
    )

    // ── Hash table ────────────────────────────────────────────────
    private const val TABLE_BITS = 11
    private const val TABLE_SIZE = 1 shl TABLE_BITS
    private const val TABLE_MASK = TABLE_SIZE - 1

    private lateinit var _keys: IntArray
    private lateinit var _data: ByteArray
    // bit 0: isComplete, bit 1: isPrefix (of some longer onset)

    init { build() }

    private fun build() {
        _keys = IntArray(TABLE_SIZE)
        _data = ByteArray(TABLE_SIZE)
        // Insert all complete onsets (NO prefix entries for single chars
        // like 'q' — those are handled by isPrefixOfCompound).
        for (o in ALL_ONSETS) insertOr(onsetKey(o), 0x01)
    }

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

    /** Complete valid onset (not just a prefix). */
    fun isCompleteOnset(onset: CharSequence, start: Int = 0, length: Int = onset.length - start): Boolean {
        if (length == 0) return false
        val i = find(onsetKey(onset, start, length))
        return i >= 0 && (_data[i].toInt() and 1) != 0
    }
}
