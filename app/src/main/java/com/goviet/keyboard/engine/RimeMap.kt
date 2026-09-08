package com.goviet.keyboard.engine

/**
 * RimeMap — zero-computation flat map for Vietnamese rimes.
 *
 * Vietnamese rime characters are encoded to 5-bit indices and packed into a
 * compact 25-bit integer key via simple bit shifts.  All valid rimes and their
 * prefixes are stored in a flat [IntArray]-keyed lookup — no FNV multiplication,
 * no linear probing, O(1) average lookup on the hot path.
 *
 * The key for a rime "abc" (chars a, b, c) is computed as:
 *   key = (idx(a) << 10) | (idx(b) << 5) | idx(c)
 *
 * Max rime length 5 → 25-bit key → Int.
 * ~600 valid entries + ~200 prefix entries = ~800 entries in the flat table.
 */
object RimeMap {

    // ── Vietnamese rime character → 5-bit index encoding ──────────
    //
    // All 29 characters that appear in Vietnamese nuclei + codas.
    // Index 0..27 = Vietnamese chars; 28 = PADDING (for shorter keys).
    // 5 bits per char, max 5 chars → 25-bit key (fits Int).

    /** Rime-alphabet character set: a ă â e ê i o ô ơ u ư y c ch g h m n ng nh p t */
    private const val RIME_ALPHA = "aăâeêioôơuưycmntpgh"

    /** Unique index for 'w' — prevents collision with 'a' (index 0) in packed keys.
     *  'w' never appears in valid Vietnamese rimes, but after an untoggle (e.g. uww → uw)
     *  it can appear in the nucleus string. Without this, rimeKey("uw") == rimeKey("ua")
     *  causing false-positive tone/coda lookups on the untoggled literal. */
    private const val W_INDEX = 19

    /** 5-bit index for each Vietnamese rime character.  Non-rime chars → 0 (maps to 'a'). */
    private val CHAR_IDX = IntArray(512).also { arr ->
        for (i in RIME_ALPHA.indices) arr[RIME_ALPHA[i].code] = i
        arr['w'.code] = W_INDEX
    }

    /** Encode one Vietnamese rime character to its 5-bit index. */
    @JvmStatic
    fun charIndex(c: Char): Int {
        val code = c.lowercaseChar().code
        return if (code in 0 until CHAR_IDX.size) CHAR_IDX[code] else 0
    }

    /**
     * Compute the flat-map key for a rime string.
     * Max length 5 → key fits in Int.  O(1) — no FNV multiplication.
     */
    @JvmStatic
    fun rimeKey(str: String): Int {
        var chars = 0
        for (i in 0 until str.length) chars = (chars shl 5) or charIndex(str[i])
        return (str.length shl 25) or chars
    }

    /**
     * Compute the flat-map key for a rime substring — zero allocation.
     */
    @JvmStatic
    fun rimeKey(cs: CharSequence, start: Int = 0, length: Int = cs.length - start): Int {
        var chars = 0
        var i = start
        val end = start + length
        while (i < end) { chars = (chars shl 5) or charIndex(cs[i]); i++ }
        return (length shl 25) or chars
    }

    /**
     * Compute the flat-map key for two concatenated substrings — zero allocation.
     */
    @JvmStatic
    fun rimeKeyCat(a: CharSequence, aLen: Int, b: CharSequence, bLen: Int): Int {
        var chars = 0
        var i = 0
        while (i < aLen) { chars = (chars shl 5) or charIndex(a[i]); i++ }
        i = 0
        while (i < bLen) { chars = (chars shl 5) or charIndex(b[i]); i++ }
        val totalLen = aLen + bLen
        return (totalLen shl 25) or chars
    }

    // ── Incremental key building (for hot-path composition) ───────
    //
    // These let the composer maintain the rime key incrementally as
    // nucleus/coda strings change, avoiding full key recomputation.

    /** Shift existing key left 5 bits and add next character — O(1). */
    @JvmStatic
    fun extendKey(prevKey: Int, c: Char): Int = (prevKey shl 5) or charIndex(c)

    /** Remove last character from key (pop) — O(1). */
    @JvmStatic
    fun popKey(key: Int): Int {
        val len = (key ushr 25) - 1
        val chars = (key and 0x1FFFFFF) ushr 5
        return (len shl 25) or chars
    }

    // ── Flat map lookup table ─────────────────────────────────────
    //
    // Primitive IntArray+ByteArray tables — zero boxing, zero GC.
    // Key encodes (length << 25) | (char-encoded rime) so strings of
    // different lengths never collide.

    private const val TABLE_BITS = 14
    private const val TABLE_SIZE = 1 shl TABLE_BITS   // 16384 slots
    private const val TABLE_MASK = TABLE_SIZE - 1

    private lateinit var _keys: IntArray     // rime keys (0 = empty slot)
    private lateinit var _data: ByteArray    // packed flags per entry

    // Data byte layout:
    //   bit 0: isPrefix  (valid prefix of some rime)
    //   bit 1: isComplete (complete valid rime)
    //   bit 2: isStop    (stop coda: c, ch, p, t)
    //   bits 3-4: tonePosition (0-2)

    /** Initialize the flat map.  Called once at class load time. */
    init { build() }

    private fun build() {
        _keys = IntArray(TABLE_SIZE)
        _data = ByteArray(TABLE_SIZE)

        // ── Nuclei and their valid codas (from phonology table) ────
        //
        // Each NucSpec: nucleus string, valid codas, tone position index,
        // and optional old-style tone position override.
        // Tone position: 0 = vowel itself, 1 = digraph second char,
        //                2 = trigraph middle char.
        // "isStop": coda in {c, ch, p, t} → only Sắc/Nặng tones allowed.

        data class NucSpec(
            val nucleus: String,
            val codas: Array<String>,
            val tnNew: Int,
            val tnOld: Int = tnNew
        )

        // Coda groups from the phonology table
        val C_ALL    = arrayOf("c","ch","p","t","m","n","ng","nh")
        val C_SHORT  = arrayOf("c","p","t","m","n","ng")
        val C_Y      = arrayOf("t","ch","n","nh")
        val C_UY     = arrayOf("p","t","ch","n","nh")
        val C_TMNG   = arrayOf("t","m","n","ng")
        val C_COVER  = arrayOf("c","n","ng","m","p","t")
        val C_NONE   = emptyArray<String>()

        val NUCLEI = arrayOf(
            // Single vowels — tone on the vowel itself (pos 0)
            NucSpec("a",  C_ALL,   0),    NucSpec("ă",  C_SHORT, 0),
            NucSpec("â",  C_SHORT, 0),    NucSpec("e",  C_ALL,   0),
            NucSpec("ê",  C_ALL,   0),    NucSpec("i",  C_ALL,   0),
            NucSpec("o",  C_SHORT, 0),    NucSpec("ô",  C_SHORT, 0),
            NucSpec("ơ",  C_SHORT, 0),    NucSpec("u",  C_SHORT, 0),
            NucSpec("ư",  C_SHORT, 0),    NucSpec("y",  C_Y,     0),

            // Digraphs — tone on 2nd vowel (pos 1)
            NucSpec("oa", C_ALL,   1, 0), NucSpec("oă", C_SHORT, 1, 0),
            NucSpec("oe", C_SHORT, 1, 0), NucSpec("ue", C_ALL,   1, 0),
            NucSpec("uy", C_UY,    1, 0), NucSpec("uâ", C_SHORT, 1, 1),
            NucSpec("uê", C_Y,     1, 1), NucSpec("uô", C_SHORT, 1, 1),
            NucSpec("uo", C_SHORT, 1, 1), NucSpec("ua", C_SHORT, 0),
            NucSpec("ưa", C_NONE,  0),    NucSpec("uơ", C_NONE,  0),
            NucSpec("ươ", C_SHORT, 1, 1), NucSpec("ia", C_NONE,  0),
            NucSpec("ie", C_SHORT, 1, 1), NucSpec("iê", C_SHORT, 1, 1),
            NucSpec("ye", C_TMNG,  1, 1), NucSpec("yê", C_TMNG,  1, 1),
            NucSpec("oo", C_COVER, 1, 1),

            // Trigraphs — tone on middle vowel (pos 2)
            NucSpec("uye", C_ALL,  2),    NucSpec("uyê", C_ALL,  2),

            // Open codas (no final consonant) — no complete rime
            NucSpec("ai",  C_NONE, 0), NucSpec("ao",  C_NONE, 0),
            NucSpec("au",  C_NONE, 0), NucSpec("ay",  C_NONE, 0),
            NucSpec("âu",  C_NONE, 0), NucSpec("ây",  C_NONE, 0),
            NucSpec("eo",  C_NONE, 0), NucSpec("eu",  C_NONE, 0),
            NucSpec("êu",  C_NONE, 0), NucSpec("iu",  C_NONE, 0),
            NucSpec("oi",  C_NONE, 0), NucSpec("ôi",  C_NONE, 0),
            NucSpec("ơi",  C_NONE, 0), NucSpec("ui",  C_NONE, 0),
            NucSpec("uu",  C_NONE, 0), NucSpec("ưi",  C_NONE, 0),

            // Open codas with digraph nuclei — tone pos 1
            NucSpec("ieu", C_NONE, 1), NucSpec("iêu", C_NONE, 1),
            NucSpec("yeu", C_NONE, 1), NucSpec("yêu", C_NONE, 1),
            NucSpec("uoi", C_NONE, 1), NucSpec("uôi", C_NONE, 1),
            NucSpec("uơi", C_NONE, 1), NucSpec("uou", C_NONE, 1),
            NucSpec("uya", C_NONE, 1), NucSpec("uyu", C_NONE, 1),
            NucSpec("ươi", C_NONE, 1), NucSpec("ươu", C_NONE, 1),
            NucSpec("oai", C_NONE, 1), NucSpec("oao", C_NONE, 1),
            NucSpec("oay", C_NONE, 1), NucSpec("oeo", C_NONE, 1),
            NucSpec("uau", C_NONE, 1), NucSpec("uay", C_NONE, 1),
            NucSpec("uâu", C_NONE, 1), NucSpec("uây", C_NONE, 1),
            NucSpec("ueu", C_NONE, 1), NucSpec("uêu", C_NONE, 1),
        )

        // ── Populate: all complete rimes + their prefixes ──────────
        val allRimes = mutableListOf<String>()

        for (spec in NUCLEI) {
            allRimes.add(spec.nucleus)
            val nucKey = rimeKey(spec.nucleus)
            tableInsert(nucKey, packData(1, 1, 0, spec.tnNew, spec.tnOld))

            for (c in spec.codas) {
                val rime = spec.nucleus + c
                allRimes.add(rime)
                val rk = rimeKey(rime)
                val isStop = c == "c" || c == "ch" || c == "p" || c == "t"
                tableInsert(rk, packData(1, 1, if (isStop) 1 else 0, spec.tnNew, spec.tnOld))
            }
        }

        for (r in allRimes) {
            for (len in 1 until r.length) {
                val pk = rimeKey(r, 0, len)
                tableInsertIfAbsent(pk, packData(1, 0, 0, 0, 0))
            }
        }
    }

    private fun countEntries(): Int {
        val C_ALL = arrayOf("c","ch","p","t","m","n","ng","nh")
        val C_SHORT = arrayOf("c","p","t","m","n","ng")
        val C_Y = arrayOf("t","ch","n","nh")
        val C_UY = arrayOf("p","t","ch","n","nh")
        val C_TMNG = arrayOf("t","m","n","ng")
        val C_COVER = arrayOf("c","n","ng","m","p","t")
        val C_NONE = emptyArray<String>()

        data class S(val n: String, val c: Array<String>)
        val nuclei = arrayOf(
            S("a",C_ALL),S("ă",C_SHORT),S("â",C_SHORT),S("e",C_ALL),S("ê",C_ALL),S("i",C_ALL),
            S("o",C_SHORT),S("ô",C_SHORT),S("ơ",C_SHORT),S("u",C_SHORT),S("ư",C_SHORT),S("y",C_Y),
            S("oa",C_ALL),S("oă",C_SHORT),S("oe",C_SHORT),S("ue",C_ALL),S("uy",C_UY),
            S("uâ",C_SHORT),S("uê",C_Y),S("uô",C_SHORT),S("uo",C_SHORT),S("ua",C_SHORT),
            S("ưa",C_NONE),S("uơ",C_NONE),S("ươ",C_SHORT),S("ia",C_NONE),
            S("ie",C_SHORT),S("iê",C_SHORT),S("ye",C_TMNG),S("yê",C_TMNG),S("oo",C_COVER),
            S("uye",C_ALL),S("uyê",C_ALL),
            S("ai",C_NONE),S("ao",C_NONE),S("au",C_NONE),S("ay",C_NONE),
            S("âu",C_NONE),S("ây",C_NONE),S("eo",C_NONE),S("eu",C_NONE),
            S("êu",C_NONE),S("iu",C_NONE),S("oi",C_NONE),S("ôi",C_NONE),
            S("ơi",C_NONE),S("ui",C_NONE),S("uu",C_NONE),S("ưi",C_NONE),
            S("ieu",C_NONE),S("iêu",C_NONE),S("yeu",C_NONE),S("yêu",C_NONE),
            S("uoi",C_NONE),S("uôi",C_NONE),S("uơi",C_NONE),S("uou",C_NONE),
            S("uya",C_NONE),S("uyu",C_NONE),S("ươi",C_NONE),S("ươu",C_NONE),
            S("oai",C_NONE),S("oao",C_NONE),S("oay",C_NONE),S("oeo",C_NONE),
            S("uau",C_NONE),S("uay",C_NONE),S("uâu",C_NONE),S("uây",C_NONE),
            S("ueu",C_NONE),S("uêu",C_NONE)
        )
        var count = 0
        for (s in nuclei) {
            count += 1 + s.c.size
        }
        // Prefixes: for each rime of length L>1, L-1 prefixes
        var rimeCount = 0
        for (s in nuclei) {
            rimeCount += 1 + s.c.size
        }
        return count + rimeCount * 2
    }

    /** Pack metadata into a single Int (stored as Byte in table). */
    private fun packData(
        isPrefix: Int, isComplete: Int, isStop: Int,
        tnNew: Int, tnOld: Int
    ): Int {
        return isPrefix or (isComplete shl 1) or (isStop shl 2) or
                (tnNew shl 3) or (tnOld shl 5)
    }

    // ── Flat hash table (primitive arrays, zero boxing) ────────────

    private fun tableHash(key: Int): Int = (key * -0x61c88647).toInt() and TABLE_MASK

    private fun tableInsert(key: Int, data: Int) {
        var slot = tableHash(key)
        while (_keys[slot] != 0) slot = (slot + 1) and TABLE_MASK
        _keys[slot] = key
        _data[slot] = data.toByte()
    }

    private fun tableInsertIfAbsent(key: Int, data: Int) {
        var slot = tableHash(key)
        while (true) {
            if (_keys[slot] == key) return  // already present
            if (_keys[slot] == 0) {
                _keys[slot] = key
                _data[slot] = data.toByte()
                return
            }
            slot = (slot + 1) and TABLE_MASK
        }
    }

    // ── Lookup (zero allocation, O(1) average) ────────────────────

    private fun find(key: Int): Int {
        var i = tableHash(key)
        while (true) {
            if (_keys[i] == key) return i
            if (_keys[i] == 0) return -1
            i = (i + 1) and TABLE_MASK
        }
    }

    @JvmStatic
    fun isValidPrefix(key: Int): Boolean = find(key) >= 0

    @JvmStatic
    fun isComplete(key: Int): Boolean {
        val i = find(key)
        return i >= 0 && (_data[i].toInt() and 2) != 0
    }

    @JvmStatic
    fun isStop(key: Int): Boolean {
        val i = find(key)
        return i >= 0 && (_data[i].toInt() and 4) != 0
    }

    @JvmStatic
    fun toneNew(key: Int): Int {
        val i = find(key)
        return if (i >= 0) (_data[i].toInt() ushr 3) and 3 else 0
    }

    @JvmStatic
    fun toneOld(key: Int): Int {
        val i = find(key)
        return if (i >= 0) (_data[i].toInt() ushr 5) and 3 else 0
    }

    @JvmStatic
    fun indexOf(key: Int): Int = find(key)

    @JvmStatic
    fun toneNewAt(idx: Int): Int = (_data[idx].toInt() ushr 3) and 3

    @JvmStatic
    fun toneOldAt(idx: Int): Int = (_data[idx].toInt() ushr 5) and 3

    /**
     * Check if a tone is allowed for this rime.
     * Stop codas (c, ch, p, t) only allow Sác (1) and Nặng (5).
     */
    @JvmStatic
    fun isToneAllowed(key: Int, tone: Int): Boolean {
        val i = find(key)
        if (i < 0) return false
        val d = _data[i].toInt()
        if (tone == 0) return true                           // NONE always OK
        if ((d and 4) == 0) return true                      // non-stop: all tones OK
        return tone == 1 || tone == 5                        // stop: only Sác/Nặng
    }

    /**
     * Compute the key for an extended rime (existing rime + one new char)
     * without re-encoding the whole string.  O(1).
     */
    @JvmStatic
    fun extendKey(baseKey: Int, cs: CharSequence, start: Int, length: Int): Int {
        var chars = baseKey and 0x1FFFFFF
        var len = (baseKey ushr 25)
        var i = start
        val end = start + length
        while (i < end) { chars = (chars shl 5) or charIndex(cs[i]); i++; len++ }
        return (len shl 25) or chars
    }

    @JvmStatic
    fun extendKey(baseKey: Int, cs: CharArray, start: Int, length: Int): Int {
        var chars = baseKey and 0x1FFFFFF
        var len = (baseKey ushr 25)
        var i = start
        val end = start + length
        while (i < end) { chars = (chars shl 5) or charIndex(cs[i]); i++; len++ }
        return (len shl 25) or chars
    }

    @JvmStatic
    fun extendKeySingle(baseKey: Int, c: Char): Int {
        val len = (baseKey ushr 25) + 1
        val chars = (baseKey and 0x1FFFFFF) shl 5 or charIndex(c)
        return (len shl 25) or chars
    }

    /**
     * Compute key for concatenating two substrings: base + extension.
     */
    @JvmStatic
    fun keyCat(base: CharSequence, baseLen: Int, ext: Char): Int {
        var chars = 0
        var i = 0
        while (i < baseLen) { chars = (chars shl 5) or charIndex(base[i]); i++ }
        chars = (chars shl 5) or charIndex(ext)
        return ((baseLen + 1) shl 25) or chars
    }

    @JvmStatic
    fun keyCat(base: CharSequence, baseLen: Int, ext: CharSequence, extLen: Int): Int {
        var chars = 0
        var i = 0
        while (i < baseLen) { chars = (chars shl 5) or charIndex(base[i]); i++ }
        i = 0
        while (i < extLen) { chars = (chars shl 5) or charIndex(ext[i]); i++ }
        return ((baseLen + extLen) shl 25) or chars
    }

    // ── Legacy compatibility (kept for minimal churn) ──────────────

    /** Legacy hash function — now just a thin wrapper over [rimeKey]. */
    @JvmStatic @JvmOverloads
    fun hash(cs: CharSequence, start: Int = 0, length: Int = cs.length - start): Long =
        rimeKey(cs, start, length).toLong()

    @JvmStatic
    fun hashCat(a: CharSequence, aLen: Int, b: CharSequence, bLen: Int): Long =
        rimeKeyCat(a, aLen, b, bLen).toLong()
}
