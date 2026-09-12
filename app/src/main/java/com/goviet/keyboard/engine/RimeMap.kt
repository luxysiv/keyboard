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

    /** Rime-alphabet character set: a ă â e ê i o ô ơ u ư y c g h m n p t + l r s j x q
     *  plus guard slots b d k v z.  The guard chars are never part of a valid rime
     *  string, but they ARE typed into the raw buffer — without unique indices they'd
     *  encode as index 0 (='a') and produce false-positive flatmap hits
     *  (e.g. rimeKey("ul") == rimeKey("ua"), rimeKey("uk") == rimeKey("ua") → "uk"
     *  wrongly accepted as a coda). */
    private const val RIME_ALPHA = "aăâeêioôơuưycmntpghlrsjxqbdkvz"

    /** Unique index for 'w' — prevents collision with 'a' (index 0) in packed keys.
     *  'w' never appears in valid Vietnamese rimes, but after an untoggle (e.g. uww → uw)
     *  it can appear in the nucleus string. Without this, rimeKey("uw") == rimeKey("ua")
     *  causing false-positive tone/coda lookups on the untoggled literal. */
    private const val W_INDEX = 30

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

    // ── Incremental key building (hot-path helpers) ───────────────
    //
    // Extend an existing rime key with additional characters without
    // re-encoding the whole string — used in the composer's resegment loop.

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
    private lateinit var _fold: LongArray    // per-nucleus fold targets (e/o/a + w-primary)
    private lateinit var _foldW: IntArray    // per-nucleus w alt variant + flags

    // Vowel combination map: (nucleus, char) → combined nucleus
    // Flatmap for vowel combinations: n1 + typed-vowel → compound nucleus.
    // Auto-derived from NUCLEI raw→display at build() time.
    private const val COMB_BITS = 4
    private const val COMB_SIZE = 1 shl COMB_BITS
    private const val COMB_MASK = COMB_SIZE - 1
    private lateinit var _combineKeys: IntArray
    private lateinit var _combineVals: Array<String>

    // Data byte layout:
    //   bit 0: isPrefix  (valid prefix of some rime)
    //   bit 1: isComplete (complete valid rime)
    //   bit 2: isStop    (stop coda: c, ch, p, t)
    //   bits 3-4: tonePosition (0-2)
    //
    // Fold-target tables (`_fold`, `_foldW`) carry Telex fold data as fields
    // on the flat-map values — one O(1) lookup answers BOTH "what does this
    // nucleus fold to" AND "where the tone lands".  No separate rule tables.
    //
    // `_fold[slot]` (nucleus entries only):
    //   bits 0-15:  'e' fold code
    //   bits 16-31: 'o' fold code
    //   bits 32-47: 'a' fold code
    //   bits 48-63: 'w' primary fold code
    //
    // `_foldW[slot]`:
    //   bits 0-15:  'w' alt fold code (dual-variant uo→uơ/ươ)
    //   bit 16:     primary code uses lookahead (ua/oa/uo)
    //   bit 17:     alt code uses lookahead (uo)
    //   bit 30:     nucleus is a w-compound display form (uơ/ươ/ưa/oă…)
    //
    // A 16-bit fold code:
    //   bits 0-2:  primary position in nucleus
    //   bits 3-7:  primary replacement char index (31 = invalid/none)
    //   bits 8-10: secondary position (compound folds)
    //   bits 11-15: secondary replacement char index (31 = none)

    /** Initialize the flat map.  Called once at class load time. */
    init { build() }

    /** Canonical raw keystroke for nuclei where naive char-by-char is wrong.
     *  (w-compound: the w serves double duty — u+w→ư, then the following
     *   vowel folds via the combination path, not its own 'w'.) */
    private fun rawOverride(nuc: String): String? = when (nuc) {
        "ươ" -> "uwo"; "ươi" -> "uowi"; "ươu" -> "uowu"; else -> null
    }

    /** Canonical Telex raw keystroke for a display nucleus.
     *  For single vowels + simple compounds, char-by-char via the vowel→raw table.
     *  For w-compounds with double-duty 'w' (computed by Telex combination path),
     *  explicit override from [rawOverride].  Case-insensitive: caller handles case. */
    @JvmStatic
    fun rawKeyForNucleus(nuc: String): String {
        val n = nuc.lowercase()
        rawOverride(n)?.let { return it }
        val sb = StringBuilder()
        for (c in n) sb.append(
            when (c) {
                'ă' -> "aw"; 'â' -> "aa"; 'ê' -> "ee"
                'ô' -> "oo"; 'ơ' -> "ow"; 'ư' -> "uw"
                else -> c
            }
        )
        return sb.toString()
    }

    private fun build() {
        _keys = IntArray(TABLE_SIZE)
        _data = ByteArray(TABLE_SIZE)
        _fold = LongArray(TABLE_SIZE)
        _foldW = IntArray(TABLE_SIZE)

        // ── Vowel combination map ──────────────────────────────────
        _combineKeys = IntArray(COMB_SIZE)
        _combineVals = arrayOf("", "", "", "", "", "", "", "",
                               "", "", "", "", "", "", "", "")


        // ── Nuclei and their valid codas (from phonology table) ────
        //
        // Each NucSpec: nucleus string, valid codas, tone position index,
        // and optional old-style tone position override.
        // Tone position: 0 = vowel itself, 1 = digraph second char,
        //                2 = trigraph middle char.
        // "isStop": coda in {c, ch, p, t} → only acute/dot (sắc/nặng) tones allowed.

        data class NucSpec(
            val nucleus: String,
            val codas: Array<String>,
            val tnNew: Int,
            val tnOld: Int = tnNew
        )

        // Coda groups — exact pairs that actually exist in Vietnamese
        // (verified against the 17,974-syllable corpus; NOT the full Cartesian product).
        //   c/ch/p/t = stop codas → only acute/dot (sắc/nặng) tones
        //   m/n/ng/nh = nasal codas → 6 tones
        val C_ALL   = arrayOf("c","ch","p","t","m","n","ng","nh") // a, ê, oa
        val C_SHORT = arrayOf("c","p","t","m","n","ng")           // ă, â, o, ô, u, uô, ươ, iê, uo, ie
        val C_I     = arrayOf("ch","p","t","m","n","nh")          // i (no c, no ng)
        val C_O5    = arrayOf("p","t","m","n")                    // ơ (no c, no ng)
        val C_U8    = arrayOf("c","m","n","ng","t")               // ư (no p)
        val C_Y     = arrayOf("p","t","ch","n","nh")              // y
        val C_OE    = arrayOf("m","n","p","t")                        // oe
        val C_OA5   = arrayOf("c","m","n","ng","p","t")               // oă (no p)
        val C_UE    = arrayOf("ch","n","nh","t")                          // ue, uê
        val C_UA4   = arrayOf("c","n","ng","t")                       // uâ
        val C_UY2   = arrayOf("p","t","ch","n","nh")              // uy
        val C_OO    = arrayOf("c","ng")                           // oo (coong, xoóc)
        val C_UYE   = arrayOf("n","t")                            // uye/uyê
        val C_TMNG  = arrayOf("t","m","n","ng")                   // ye/yê (pre-fold raw)
        val C_NONE  = emptyArray<String>()

        val NUCLEI = arrayOf(
            // ── Single vowels — tone on the vowel itself (pos 0) ──
            NucSpec("a",  C_ALL,   0), NucSpec("ă",  C_SHORT, 0),
            NucSpec("â",  C_SHORT, 0), NucSpec("e",  C_SHORT, 0),
            NucSpec("ê",  C_ALL,   0), NucSpec("i",  C_I,     0),
            NucSpec("o",  C_SHORT, 0), NucSpec("ô",  C_SHORT, 0),
            NucSpec("ơ",  C_O5,    0), NucSpec("u",  C_SHORT, 0),
            NucSpec("ư",  C_U8,    0), NucSpec("y",  C_Y,     0),

            // ── Digraph nuclei — tone on main vowel (modern) ──────
            // (glide + main). Tone position follows the s.ngonngu.net
            // canonical table: oa/oai→a, oe→e, uy→y, iê/uô/ươ→2nd char.
            NucSpec("oa", C_ALL,   1, 0), NucSpec("oă", C_OA5,  1, 0),
            NucSpec("oe", C_OE,    1, 0), NucSpec("ue", C_UE,   1, 0),
            NucSpec("uy", C_UY2,   1, 0), NucSpec("uâ", C_UA4,  1, 1),
            NucSpec("uê", C_UE,    1, 1), NucSpec("uô", C_SHORT,1, 1),
            NucSpec("uo", C_SHORT, 1, 1), NucSpec("ua", C_NONE, 0),
            NucSpec("ưa", C_NONE,  0),    NucSpec("uơ", C_NONE, 1),
            NucSpec("ươ", C_SHORT, 1, 1), NucSpec("ia", C_NONE, 0),
            NucSpec("ie", C_SHORT, 1, 1), NucSpec("iê", C_SHORT,1, 1),
            NucSpec("ye", C_TMNG,  1, 1), NucSpec("yê", C_TMNG, 1, 1),
            NucSpec("oo", C_OO,    1, 1),

            // ── Trigraph nuclei — tone on middle vowel (pos 2) ────
            NucSpec("uye", C_UYE,  2),    NucSpec("uyê", C_UYE, 2),

            // ── Open rimes (no final consonant) ────────────────────
            NucSpec("ai",  C_NONE, 0), NucSpec("ao",  C_NONE, 0),
            NucSpec("au",  C_NONE, 0), NucSpec("ay",  C_NONE, 0),
            NucSpec("âu",  C_NONE, 0), NucSpec("ây",  C_NONE, 0),
            NucSpec("eo",  C_NONE, 0), NucSpec("eu",  C_NONE, 0),
            NucSpec("êu",  C_NONE, 0), NucSpec("iu",  C_NONE, 0),
            NucSpec("oi",  C_NONE, 0), NucSpec("ôi",  C_NONE, 0),
            NucSpec("ơi",  C_NONE, 0), NucSpec("ui",  C_NONE, 0),
            NucSpec("uu",  C_NONE, 0), NucSpec("ưu",  C_NONE, 0),
            NucSpec("ưi",  C_NONE, 0),
            NucSpec("ieu", C_NONE, 1), NucSpec("iêu", C_NONE, 1),
            NucSpec("yeu", C_NONE, 1), NucSpec("yêu", C_NONE, 1),
            NucSpec("uoi", C_NONE, 1), NucSpec("uôi", C_NONE, 1),
            NucSpec("uou", C_NONE, 1),
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
            val slot = tableInsert(nucKey, packData(1, 1, 0, spec.tnNew, spec.tnOld))
            val w = computeFoldW(spec.nucleus)
            val wPrimary = (w and 0xFFFF).toInt()
            val wAlt = ((w ushr 16) and 0xFFFF).toInt()
            val wPrimaryLA = ((w ushr 32) and 1L) != 0L
            val wAltLA = ((w ushr 33) and 1L) != 0L
            _fold[slot] =
                (computeFoldE(spec.nucleus).toLong() and 0xFFFF) or
                ((computeFoldO(spec.nucleus).toLong() and 0xFFFF) shl 16) or
                ((computeFoldA(spec.nucleus).toLong() and 0xFFFF) shl 32) or
                ((wPrimary.toLong() and 0xFFFF) shl 48)
            _foldW[slot] =
                (wAlt and 0xFFFF) or
                (if (wPrimaryLA) (1 shl 16) else 0) or
                (if (wAltLA) (1 shl 17) else 0) or
                (if (isUoCompoundForm(spec.nucleus)) (1 shl 30) else 0)

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

        // ── Vowel combination map (derived from raw→display of NUCLEI) ──
        //
        // For each nucleus N and each base vowel V, compute the raw keystroke
        // for N+V; if it maps to a DIFFERENT display nucleus, that's a valid
        // vowel combination (e.g. ư + raw('o') → raw("uw"+"o") = "uwo" → "ươ").
        // All lookup is O(1) via the rawToDisplay flatmap — no hardcoded pairs.
        val rawToDisplay = HashMap<String, String>(NUCLEI.size * 2)
        for (spec in NUCLEI) rawToDisplay[rawKeyForNucleus(spec.nucleus)] = spec.nucleus
        // Overrides win over any naive collisions (uơi/ươi share raw "uowi").
        for (spec in NUCLEI) {
            val override = rawOverride(spec.nucleus.lowercase())
            if (override != null) rawToDisplay[override] = spec.nucleus
        }
        val plainVowels = charArrayOf('a', 'e', 'i', 'o', 'u')
        for (spec in NUCLEI) {
            val baseRaw = rawKeyForNucleus(spec.nucleus)
            for (v in plainVowels) {
                val combined = rawToDisplay[baseRaw + v] ?: continue
                // Skip no-op entries: plain extend already handles nucleus+v
                if (combined == spec.nucleus + v) continue
                combineInsert(spec.nucleus, v, combined)
            }
        }
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

    private fun tableInsert(key: Int, data: Int): Int {
        var slot = tableHash(key)
        while (_keys[slot] != 0) slot = (slot + 1) and TABLE_MASK
        _keys[slot] = key
        _data[slot] = data.toByte()
        return slot
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

    // ── Vowel combination lookup ─────────────────────────────────
    private fun combineInsert(nucLower: String, charLower: Char, result: String) {
        val compositeKey = keyCat(nucLower, nucLower.length, charLower)
        var slot = (compositeKey * -0x61c88647).toInt() and COMB_MASK
        while (_combineKeys[slot] != 0) slot = (slot + 1) and COMB_MASK
        _combineKeys[slot] = compositeKey
        _combineVals[slot] = result
    }

    /**
     * Vowel combination lookup: nucleus + char → combined nucleus.
     * Returns null if no special combination applies.
     * O(1) flatmap lookup, zero boxing.
     */
    @JvmStatic
    fun combineNucleus(nucleus: String, char: Char): String? {
        val nLower = nucleus.lowercase()
        val compositeKey = keyCat(nLower, nLower.length, char)
        var slot = (compositeKey * -0x61c88647).toInt() and COMB_MASK
        while (true) {
            if (_combineKeys[slot] == compositeKey) {
                val result = _combineVals[slot]
                val nucleusUpper = nucleus.isNotEmpty() && nucleus[0].isUpperCase()
                val charUpper = char.isUpperCase()
                val len = result.length
                val buf = CharArray(len)
                for (i in 0 until len) {
                    val makeUpper = if (i == 0) nucleusUpper else charUpper
                    buf[i] = if (makeUpper) result[i].uppercaseChar() else result[i]
                }
                return String(buf)
            }
            if (_combineKeys[slot] == 0) return null
            slot = (slot + 1) and COMB_MASK
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
    fun indexOf(key: Int): Int = find(key)

    @JvmStatic
    fun toneNewAt(idx: Int): Int = (_data[idx].toInt() ushr 3) and 3

    @JvmStatic
    fun toneOldAt(idx: Int): Int = (_data[idx].toInt() ushr 5) and 3

    /**
     * Check if a tone is allowed for this rime.
     * Stop codas (c, ch, p, t) only allow acute (sắc, 1) and dot (nặng, 5).
     */
    @JvmStatic
    fun isToneAllowed(key: Int, tone: Int): Boolean {
        val i = find(key)
        if (i < 0) return false
        val d = _data[i].toInt()
        if (tone == 0) return true                           // NONE always OK
        if ((d and 4) == 0) return true                      // non-stop: all tones OK
        return tone == 1 || tone == 5                        // stop: only acute/dot (sắc/nặng)
    }

    /**
     * Single-lookup check whether [key] is a valid prefix AND accepts [tone]:
     * combines [isValidPrefix] + [isToneAllowed] into one table probe for the
     * hot coda-building path.
     */
    @JvmStatic
    fun isValidPrefixWithTone(key: Int, tone: Int): Boolean {
        val i = find(key)
        if (i < 0) return false
        val d = _data[i].toInt()
        if (tone == 0) return true
        if ((d and 4) == 0) return true
        return tone == 1 || tone == 5
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

    // ── Fold-target data (fields on the flat-map value) ───────────
    //
    // The Telex fold targets are baked into the map at build time, so the
    // composer decides folds by lookup, not by if/else rule chains:
    //   'a' → a/ă → â,  'e' → e → ê,  'o' → o/ơ → ô,
    //   'w' → uo/uô → ươ|uơ, ua → ưa, oa → oă, and singles a→ă, o→ơ, u→ư.
    // The uo→uơ fold-back guard ("uowo stays uơo") is expressed here as
    // *absent* fold data on the "uơ" nucleus, not as a runtime comparison.

    private const val NO_FOLD_CHAR = 31
    private val CHAR_AT = RIME_ALPHA.toCharArray()

    /** Pack a single-char fold replacement at [pos1]. */
    private fun foldCode(pos1: Int, c1: Char): Int =
        (pos1 and 7) or (charIndex(c1) shl 3) or (NO_FOLD_CHAR shl 11)

    /** Pack a two-char compound fold replacement starting at [pos1]. */
    private fun foldCode(pos1: Int, c1: Char, pos2: Int, c2: Char): Int =
        (pos1 and 7) or (charIndex(c1) shl 3) or ((pos2 and 7) shl 8) or (charIndex(c2) shl 11)

    /** 'e' fold: first plain 'e' → ê. */
    private fun computeFoldE(nuc: String): Int {
        val n = nuc.lowercase()
        for (i in n.indices) if (n[i] == 'e') return foldCode(i, 'ê')
        return 0
    }

    /** 'o' fold: first plain 'o' → ô; 'ơ' → ô only when not the uo→uơ compound. */
    private fun computeFoldO(nuc: String): Int {
        val n = nuc.lowercase()
        for (i in n.indices) {
            if (n[i] == 'o') return foldCode(i, 'ô')
            if (n[i] == 'ơ' && !(i > 0 && n[i - 1] == 'u')) return foldCode(i, 'ô')
        }
        return 0
    }

    /** 'a' fold: first plain 'a' or 'ă' → â. */
    private fun computeFoldA(nuc: String): Int {
        val n = nuc.lowercase()
        for (i in n.indices) {
            if (n[i] == 'a') return foldCode(i, 'â')
            if (n[i] == 'ă') return foldCode(i, 'â')
        }
        return 0
    }

    /**
     * 'w' fold targets, packed:
     *   bits 0-15   primary code
     *   bits 16-31  alt code (dual-variant uo/uô → uơ)
     *   bit 32      primary uses lookahead
     *   bit 33      alt uses lookahead
     */
    private fun computeFoldW(nuc: String): Long {
        val n = nuc.lowercase()
        val uo = n.indexOf("uo")
        if (uo >= 0) {
            val prim = foldCode(uo, 'ư', uo + 1, 'ơ').toLong()   // ươ
            val alt = foldCode(uo, 'u', uo + 1, 'ơ').toLong()    // uơ (anchor on u)
            return prim or (alt shl 16) or (0b11L shl 32)
        }
        val uoHorn = n.indexOf("uô")
        if (uoHorn >= 0) {
            val prim = foldCode(uoHorn, 'ư', uoHorn + 1, 'ơ').toLong()   // ươ
            val alt = foldCode(uoHorn, 'u', uoHorn + 1, 'ơ').toLong()    // uơ
            return prim or (alt shl 16) or (0b11L shl 32)
        }
        if (n.contains("ươ")) return 0L                       // already horned — no-op
        val ua = n.indexOf("ua")
        if (ua >= 0) return foldCode(ua, 'ư').toLong() or (1L shl 32)    // ưa
        val oa = n.indexOf("oa")
        if (oa >= 0) return foldCode(oa + 1, 'ă').toLong() or (1L shl 32) // oă
        for (i in n.indices) {
            if (n[i] == 'u') {
                val next = i + 1
                val nextIsOA = next < n.length && (n[next] == 'o' || n[next] == 'a')
                if (!nextIsOA) return foldCode(i, 'ư').toLong()
                break
            }
        }
        for (i in n.indices) {
            if (n[i] == 'o' && !(i > 0 && n[i - 1] == 'u')) return foldCode(i, 'ơ').toLong()
        }
        // Hat→horn folds within vowel families: â→ă, ô→ơ
        val hatA = n.indexOf('â')
        if (hatA >= 0) return foldCode(hatA, 'ă').toLong()
        val hatO = n.indexOf('ô')
        if (hatO >= 0) return foldCode(hatO, 'ơ').toLong()
        val a = n.indexOf('a')
        if (a >= 0) return foldCode(a, 'ă').toLong()
        return 0L
    }

    /** True if [nuc] is a uo-family w-compound (uơ/ươ or derivative) whose
     *  repeated 'w' must be absorbed instead of untoggled. */
    private fun isUoCompoundForm(nuc: String): Boolean {
        val n = nuc.lowercase()
        return n.contains("ươ") || n.contains("uơ")
    }

    /**
     * Slot for [nucleusKey], or -1 when absent.  All fold data for a nucleus is
     * read through this single lookup — the accessors below only unpack the
     * already-found slot, so one key never triggers more than one table probe.
     */
    @JvmStatic
    fun foldSlot(nucleusKey: Int): Int = find(nucleusKey)

    /** 'e' fold code for a slot from [foldSlot]; 0 = none. */
    @JvmStatic
    fun foldE(slot: Int): Int = if (slot < 0) 0 else (_fold[slot] and 0xFFFF).toInt()

    /** 'o' fold code for a slot from [foldSlot]; 0 = none. */
    @JvmStatic
    fun foldO(slot: Int): Int = if (slot < 0) 0 else ((_fold[slot] ushr 16) and 0xFFFF).toInt()

    /** 'a' fold code for a slot from [foldSlot]; 0 = none. */
    @JvmStatic
    fun foldA(slot: Int): Int = if (slot < 0) 0 else ((_fold[slot] ushr 32) and 0xFFFF).toInt()

    /** 'w' primary fold code for a slot from [foldSlot]; 0 = none. */
    @JvmStatic
    fun foldWPrimary(slot: Int): Int = if (slot < 0) 0 else ((_fold[slot] ushr 48) and 0xFFFF).toInt()

    /** 'w' alt fold code (dual-variant uo/uô) for a slot; 0 = none. */
    @JvmStatic
    fun foldWAlt(slot: Int): Int = if (slot < 0) 0 else _foldW[slot] and 0xFFFF

    /** True when the primary 'w' fold for [slot] must validate with lookahead. */
    @JvmStatic
    fun foldWPrimaryLookahead(slot: Int): Boolean =
        slot >= 0 && (_foldW[slot] and (1 shl 16)) != 0

    /** True when a repeated 'w' after the w-compound at [slot] cannot untoggle:
     *  it is released as literal text (uo-family uơ/ươ: uoww → uơw). */
    @JvmStatic
    fun foldWRepeatLiteral(slot: Int): Boolean =
        slot >= 0 && (_foldW[slot] and (1 shl 30)) != 0

    /** Position where the fold lands (untoggle anchor). */
    @JvmStatic
    fun foldPos(code: Int): Int = code and 7

    /**
     * Fold code for [foldKey] on the nucleus with packed [nucleusKey].
     * Dispatches foldE/O/A/WPrimary by the fold key — thin key-based wrapper
     * over the slot-based accessors.  Returns 0 when no fold applies.
     */
    @JvmStatic
    fun foldPrimary(nucleusKey: Int, foldKey: Char): Int {
        val slot = foldSlot(nucleusKey)
        return when (foldKey.lowercaseChar()) {
            'e' -> foldE(slot)
            'o' -> foldO(slot)
            'a' -> foldA(slot)
            'w' -> foldWPrimary(slot)
            else -> 0
        }
    }

    /**
     * Alt fold code (dual-variant uo→uơ/ươ) for the nucleus with [nucleusKey].
     * Thin wrapper over [foldWAlt]; returns 0 when absent.
     */
    @JvmStatic
    fun foldAlt(nucleusKey: Int): Int = foldWAlt(foldSlot(nucleusKey))

    /** Apply a fold [code] to [nucleus], preserving casing.  Zero boxing. */
    @JvmStatic
    fun applyFold(nucleus: String, code: Int): String {
        if (code == 0) return nucleus
        val c1 = (code ushr 3) and 0x1F
        val p1 = code and 7
        if (c1 >= CHAR_AT.size || p1 >= nucleus.length) return nucleus
        val len = nucleus.length
        val buf = CharArray(len)
        nucleus.toCharArray(buf, 0, 0, len)
        val ch1 = CHAR_AT[c1]
        buf[p1] = if (buf[p1].isUpperCase()) ch1.uppercaseChar() else ch1
        val c2 = (code ushr 11) and 0x1F
        if (c2 < CHAR_AT.size) {
            val p2 = (code ushr 8) and 7
            if (p2 < len) {
                val ch2 = CHAR_AT[c2]
                buf[p2] = if (buf[p2].isUpperCase()) ch2.uppercaseChar() else ch2
            }
        }
        return String(buf)
    }



    // ── Vietnamese phonological utilities (formerly in VietnamesePhonology) ──

    /** 12 Vietnamese base vowels (unaccented): a ă â e ê i o ô ơ u ư y. */
    val BASE_VOWELS = "aăâeêioôơuưy"
    private val BASE_VOWEL_SET = BooleanArray(512).also { arr ->
        for (c in BASE_VOWELS) arr[c.code] = true
    }

    /** Telex tone keys: s(acute), f(grave), r(hook), x(tilde), j(dot), z(clear). */
    val TONE_KEYS = "sfrxjz"
    private val TONE_KEY_SET = BooleanArray(512).also { arr ->
        for (c in TONE_KEYS) arr[c.code] = true
    }

    /** Telex vowel modifier keys: e/o/a/w. */
    val VOWEL_MOD_KEYS = "eoaw"
    private val VOWEL_MOD_SET = BooleanArray(512).also { arr ->
        for (c in VOWEL_MOD_KEYS) arr[c.code] = true
    }

    /** Valid Vietnamese coda strings. */
    val CODAS = arrayOf("ng", "nh", "ch", "m", "p", "n", "t", "c")

    /** True if [c] is one of the 12 base Vietnamese vowels (unaccented). */
    @JvmStatic
    fun isBaseVowel(c: Char): Boolean {
        val code = c.lowercaseChar().code
        return code in 0 until 512 && BASE_VOWEL_SET[code]
    }

    /** Telex tone key — O(1) BooleanArray lookup. */
    @JvmStatic
    fun isToneKey(c: Char): Boolean {
        val code = c.lowercaseChar().code
        return code in 0 until 512 && TONE_KEY_SET[code]
    }

    /** Vowel-modifier/fold key — O(1) BooleanArray lookup. */
    @JvmStatic
    fun isFoldKey(c: Char): Boolean {
        val code = c.lowercaseChar().code
        return code in 0 until 512 && VOWEL_MOD_SET[code]
    }

    /** Plain letter that a folded display letter unfolds back to. */
    @JvmStatic
    fun plainOf(folded: Char): Char = when (folded) {
        'ê' -> 'e'; 'ô' -> 'o'; 'ơ' -> 'o'; 'â' -> 'a'; 'ă' -> 'a'; 'ư' -> 'u'; 'đ' -> 'd'
        else -> folded
    }

    /** True if the rime is a valid stop-coda rime (c, ch, p, t). */
    @JvmStatic
    fun isStopCoda(rime: CharSequence, start: Int = 0, length: Int = rime.length - start): Boolean {
        if (length == 0) return false
        return isStop(rimeKey(rime, start, length))
    }

    /** Validate that a rime string is valid for a specific tone. */
    @JvmStatic
    fun isRimeValidForTone(rime: String, tone: Tone): Boolean {
        if (rime.isEmpty()) return false
        return isToneAllowed(rimeKey(rime), tone.index)
    }

    /** Validate that a rime (by precomputed key) is valid for a specific tone. */
    @JvmStatic
    fun isRimeHashValidForTone(key: Long, tone: Tone): Boolean =
        isToneAllowed(key.toInt(), tone.index)

    /** Determine tone position from a precomputed rime key — zero allocation. */
    @JvmStatic
    fun determineTonePositionHash(rimeKey: Long, oldTonePlacement: Boolean, nucleusLength: Int = 0): Int {
        val key = rimeKey.toInt()
        val i = indexOf(key)
        if (i < 0 || !isComplete(key)) return (nucleusLength - 1).coerceAtLeast(0)
        return if (oldTonePlacement) toneOldAt(i) else toneNewAt(i)
    }

    /**
     * Determine tone mark position with onset prefix preprocessing (qu/gi).
     */
    @JvmStatic
    fun findTonePosition(onset: CharSequence, rime: CharSequence, oldTonePlacement: Boolean): Int? {
        val onsetLen = onset.length
        val rimeLen = rime.length
        if (rimeLen == 0) return null
        var rimeStart = 0
        var offset = 0
        if (rimeLen > 1 && onsetLen > 0) {
            val isRimeFirstU = rime[0] == 'u' || rime[0] == 'U'
            val isRimeFirstI = rime[0] == 'i' || rime[0] == 'I'
            val isQ = (onset[onsetLen - 1] == 'q' || onset[onsetLen - 1] == 'Q') ||
                    (onsetLen >= 2 && (onset[onsetLen - 2] == 'q' || onset[onsetLen - 2] == 'Q') && (onset[onsetLen - 1] == 'u' || onset[onsetLen - 1] == 'U'))
            val isG = (onset[onsetLen - 1] == 'g' || onset[onsetLen - 1] == 'G') ||
                    (onsetLen >= 2 && (onset[onsetLen - 2] == 'g' || onset[onsetLen - 2] == 'G') && (onset[onsetLen - 1] == 'i' || onset[onsetLen - 1] == 'I'))
            if (isRimeFirstU && isQ) { rimeStart = 1; offset = 1 }
            else if (isRimeFirstI && isG) { rimeStart = 1; offset = 1 }
        }
        val k = rimeKey(rime, rimeStart, rimeLen - rimeStart)
        val i = indexOf(k)
        if (i < 0) return null
        val basePos = if (oldTonePlacement) toneOldAt(i) else toneNewAt(i)
        return basePos + offset
    }

    /**
     * Get tone position for a rime (no onset preprocessing).
     */
    @JvmStatic
    fun getTonePosition(rime: CharSequence, oldTonePlacement: Boolean, start: Int = 0, length: Int = rime.length - start): Int {
        if (length == 0) return 0
        val k = rimeKey(rime, start, length)
        val i = indexOf(k)
        if (i < 0) return 0
        return if (oldTonePlacement) toneOldAt(i) else toneNewAt(i)
    }
}