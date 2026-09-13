package com.goviet.keyboard.engine

/**
 * RimeMap — zero-computation flat map for Vietnamese rimes.
 *
 * Vietnamese rime characters are encoded to 5-bit indices and packed into a
 * compact 25-bit integer key via simple bit shifts (max rime length 5):
 *   key = (idx(a) << 10) | (idx(b) << 5) | idx(c)
 *
 * Keys live in the shared open-addressing flat table [IntFlatTable]
 * (Fibonacci-multiply hash + linear probing, 16384 slots) with the
 * fold-target and tone-position data riding in the table values;
 * ~600 valid rime entries + ~200 prefix entries.  The syllable-prefix table
 * (display → ASCII, 2705 entries) uses the same hash with an extra xor-fold
 * before truncation to its 14-bit address.
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
    private const val TABLE_MASK = TABLE_SIZE - 1     // syllable-prefix table only

    private val table = IntFlatTable(TABLE_BITS)
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
            val slot = table.insert(nucKey, packData(1, 1, 0, spec.tnNew, spec.tnOld))
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
                // With a final consonant (coda), the tone always lands on the main
                // vowel regardless of old/new placement style (hoàn, toán — never
                // hòan/tóan). tnOld only differs for open rimes oa/oe/uy.
                table.insert(rk, packData(1, 1, if (isStop) 1 else 0, spec.tnNew, spec.tnNew))
            }
        }

        for (r in allRimes) {
            for (len in 1 until r.length) {
                val pk = rimeKey(r, 0, len)
                table.insertIfAbsent(pk, packData(1, 0, 0, 0, 0))
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

    @JvmStatic
    fun isValidPrefix(key: Int): Boolean = table.find(key) >= 0

    @JvmStatic
    fun isComplete(key: Int): Boolean {
        val i = table.find(key)
        return i >= 0 && (table.data[i].toInt() and 2) != 0
    }

    @JvmStatic
    fun isStop(key: Int): Boolean {
        val i = table.find(key)
        return i >= 0 && (table.data[i].toInt() and 4) != 0
    }

    @JvmStatic
    fun indexOf(key: Int): Int = table.find(key)

    @JvmStatic
    fun toneNewAt(idx: Int): Int = (table.data[idx].toInt() ushr 3) and 3

    @JvmStatic
    fun toneOldAt(idx: Int): Int = (table.data[idx].toInt() ushr 5) and 3

    /**
     * Single-lookup check whether [key] is a valid prefix AND accepts [tone] —
     * one table probe.  Stop codas (c, ch, p, t) only allow acute (sắc, 1) and
     * dot (nặng, 5); NONE (0) is always allowed.
     */
    @JvmStatic
    fun isValidPrefixWithTone(key: Int, tone: Int): Boolean {
        val i = table.find(key)
        if (i < 0) return false
        val d = table.data[i].toInt()
        if (tone == 0) return true
        if ((d and 4) == 0) return true
        return tone == 1 || tone == 5
    }

    /**
     * Single source for the deferred-fold rule: fold [nucleus] with [foldKey];
     * when the folded form plus [tail] is a valid prefix (accepting [toneIndex]),
     * returns the folded nucleus — otherwise null.  Shared by the scan-time
     * lookahead (tryCoda) and the display-time tone anchor (pendingFoldCodaIndex).
     */
    @JvmStatic
    fun foldCodaValid(nucleus: String, nucleusKey: Int, foldKey: Char, tail: Char, toneIndex: Int = 0): String? {
        val fold = foldPrimaryAtSlot(foldSlot(nucleusKey), foldKey)
        if (fold == 0) return null
        val folded = applyFold(nucleus, fold)
        if (folded == nucleus) return null
        val rk = keyCat(folded, folded.length, tail)
        if (!isValidPrefixWithTone(rk, toneIndex)) return null
        return folded
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
    fun foldSlot(nucleusKey: Int): Int = table.find(nucleusKey)

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
     * Fold code for [foldKey] on the nucleus slot from [foldSlot] — the single
     * fold dispatch; returns 0 when no fold applies.
     */
    @JvmStatic
    fun foldPrimaryAtSlot(slot: Int, foldKey: Char): Int = when (foldKey.lowercaseChar()) {
        'e' -> foldE(slot)
        'o' -> foldO(slot)
        'a' -> foldA(slot)
        'w' -> foldWPrimary(slot)
        else -> 0
    }

    /** Fold code for [foldKey] on the nucleus with packed [nucleusKey]. */
    @JvmStatic
    fun foldPrimary(nucleusKey: Int, foldKey: Char): Int =
        foldPrimaryAtSlot(foldSlot(nucleusKey), foldKey)

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



    // ── Vietnamese phonological utilities ──

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
        return isValidPrefixWithTone(rimeKey(rime), tone.index)
    }

    /** Validate that a rime (by precomputed flat-table key) is valid for a specific tone. */
    @JvmStatic
    fun isRimeKeyValidForTone(key: Int, tone: Tone): Boolean =
        isValidPrefixWithTone(key, tone.index)

    /** Determine tone position from a precomputed flat-table key — zero allocation. */
    @JvmStatic
    fun determineTonePosition(rimeKey: Int, oldTonePlacement: Boolean, nucleusLength: Int = 0): Int {
        val i = indexOf(rimeKey)
        if (i < 0 || !isComplete(rimeKey)) return (nucleusLength - 1).coerceAtLeast(0)
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


    // ── Syllable prefix table (auto-generated from 18342 syllables) ──────
    // Single source of truth for display-prefix
    // validation (e.g. qu+ư invalid, onset+rime must be a prefix of a real syllable).
    private val _sylTable = LongArray(TABLE_SIZE)

    private fun sylPackKey(s: String): Long {
        if (s.isEmpty() || s.length > 10) return -1L
        var key = s.length.toLong() shl 55
        for (i in s.indices) {
            val idx = s[i].code - 'a'.code
            if (idx < 0 || idx >= 26) return -1L
            key = key or (idx.toLong() shl (50 - 5 * i))
        }
        return key
    }

    private fun sylHash(key: Long): Int =
        ((key * -0x61c88647L) xor (key ushr 32)).toInt() and TABLE_MASK

    private fun sylInsert(key: Long) {
        if (key < 0) return
        var slot = sylHash(key)
        while (true) {
            if (_sylTable[slot] == key) return
            if (_sylTable[slot] == 0L) { _sylTable[slot] = key; return }
            slot = (slot + 1) and TABLE_MASK
        }
    }

    /** Check if [s] is a valid unaccented syllable prefix (from corpus). */
    @JvmStatic
    fun isSyllablePrefixValid(s: String): Boolean {
        val key = sylPackKey(s)
        if (key < 0) return false
        var slot = sylHash(key)
        while (true) {
            if (_sylTable[slot] == key) return true
            if (_sylTable[slot] == 0L) return false
            slot = (slot + 1) and TABLE_MASK
        }
    }

    /** Check if [display] is a valid display prefix (strip diacritics first). */
    @JvmStatic
    fun isSyllableDisplayPrefixValid(display: String): Boolean {
        if (display.isEmpty()) return true
        val ascii = sylStripDiacritics(display)
        if (ascii.isEmpty()) return true
        return isSyllablePrefixValid(ascii)
    }

    /**
     * Reduce [text] to lowercase ASCII for the prefix table.  Vietnamese letters
     * (base + toned) go through the single [VietnameseUnicode] strip path; any
     * remaining non-ASCII char falls back to NFD decomposition.
     */
    private fun sylStripDiacritics(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (c.code in 0x61..0x7A) { sb.append(c); continue }
            if (c.code in 0x41..0x5A) { sb.append((c.code + 32).toChar()); continue }
            val stripped = VietnameseUnicode.stripDiacritics(c)
            if (stripped.lowercaseChar() != c.lowercaseChar()) { sb.append(stripped.lowercaseChar()); continue }
            val decomposed = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
            for (ch in decomposed) {
                if (ch.code in 0x61..0x7A) { sb.append(ch); break }
                if (ch.code in 0x41..0x5A) { sb.append((ch.code + 32).toChar()); break }
            }
        }
        return sb.toString()
    }
    private val EMBEDDED = arrayOf(
        "a", "ac", "ach", "ai", "am", "an", "ang", "anh",
        "ao", "ap", "at", "au", "ay", "b", "ba", "bac",
        "bach", "bai", "bam", "ban", "bang", "banh", "bao", "bap",
        "bat", "bau", "bay", "be", "bec", "bech", "bem", "ben",
        "beng", "benh", "beo", "bep", "bet", "beu", "bi", "bia",
        "bic", "bich", "bie", "biec", "biem", "bien", "bieng", "biep",
        "biet", "bieu", "bim", "bin", "binh", "bip", "bit", "biu",
        "bo", "boa", "boac", "boan", "boang", "boat", "boc", "boi",
        "bom", "bon", "bong", "boo", "boon", "boong", "bop", "bot",
        "bu", "bua", "buac", "buan", "buc", "bui", "bum", "bun",
        "bung", "buo", "buoc", "buoi", "buom", "buon", "buong", "buop",
        "buot", "buou", "bup", "but", "buu", "buy", "buye", "buyet",
        "buyt", "by", "c", "ca", "cac", "cach", "cai", "cam",
        "can", "cang", "canh", "cao", "cap", "cat", "cau", "cay",
        "ce", "cem", "cen", "ceo", "cet", "ceu", "ch", "cha",
        "chac", "chach", "chai", "cham", "chan", "chang", "chanh", "chao",
        "chap", "chat", "chau", "chay", "che", "chec", "chech", "chem",
        "chen", "cheng", "chenh", "cheo", "chep", "chet", "cheu", "chi",
        "chia", "chic", "chich", "chie", "chiec", "chiem", "chien", "chieng",
        "chiep", "chiet", "chieu", "chim", "chin", "chinh", "chip", "chit",
        "chiu", "cho", "choa", "choac", "choai", "choan", "choang", "choat",
        "choc", "choe", "choen", "choet", "choi", "chom", "chon", "chong",
        "choo", "choon", "choong", "chop", "chot", "chu", "chua", "chuan",
        "chuc", "chue", "chuec", "chuech", "chuen", "chuenh", "chui", "chum",
        "chun", "chung", "chuo", "chuoc", "chuoi", "chuom", "chuon", "chuong",
        "chuop", "chuot", "chup", "chut", "chuu", "chuy", "chuye", "chuyen",
        "chuyet", "chy", "ci", "cia", "cic", "cich", "cie", "cien",
        "cim", "cin", "cinh", "co", "coa", "coac", "coc", "coe",
        "coen", "coi", "com", "con", "cong", "coo", "cooc", "coon",
        "coong", "cop", "cot", "cu", "cua", "cuac", "cuc", "cue",
        "cui", "cum", "cun", "cung", "cuo", "cuoc", "cuoi", "cuom",
        "cuon", "cuong", "cuop", "cuot", "cup", "cut", "cuu", "cuy",
        "cy", "d", "da", "dac", "dach", "dai", "dam", "dan",
        "dang", "danh", "dao", "dap", "dat", "dau", "day", "de",
        "dec", "dech", "dem", "den", "deng", "denh", "deo", "dep",
        "det", "deu", "di", "dia", "dic", "dich", "die", "diec",
        "diem", "dien", "dieng", "diep", "diet", "dieu", "dim", "din",
        "dinh", "dip", "dit", "diu", "do", "doa", "doac", "doai",
        "doan", "doang", "doanh", "doat", "doc", "doi", "dom", "don",
        "dong", "dop", "dot", "du", "dua", "duan", "duat", "duc",
        "due", "duen", "duenh", "dui", "dum", "dun", "dung", "duo",
        "duoc", "duoi", "duom", "duon", "duong", "duot", "duou", "dup",
        "dut", "duu", "duy", "duye", "duyen", "duyet", "dy", "dyn",
        "e", "ec", "ech", "em", "en", "eng", "enh", "eo",
        "ep", "et", "eu", "g", "ga", "gac", "gach", "gai",
        "gam", "gan", "gang", "ganh", "gao", "gap", "gat", "gau",
        "gay", "ge", "gem", "gen", "gh", "gha", "ghan", "ghanh",
        "ghao", "ghap", "ghat", "ghe", "ghec", "ghech", "ghem", "ghen",
        "ghenh", "gheo", "ghep", "ghet", "ghi", "ghia", "ghie", "ghiec",
        "ghien", "ghim", "ghin", "ghinh", "ghip", "ghit", "gho", "ghot",
        "ghu", "ghue", "gi", "gia", "giac", "giai", "giam", "gian",
        "giang", "gianh", "giao", "giap", "giat", "giau", "giay", "gie",
        "giec", "giem", "gien", "gieng", "gieo", "giep", "giet", "gieu",
        "gii", "giie", "giiec", "giien", "gio", "gioa", "gioc", "gioi",
        "gion", "giong", "giot", "giu", "giua", "giuc", "giue", "giui",
        "gium", "giun", "giuo", "giuoc", "giuon", "giuong", "giup", "giut",
        "giuu", "giy", "go", "goa", "goac", "goao", "goc", "goe",
        "goeo", "goi", "gom", "gon", "gong", "goo", "goon", "goong",
        "gop", "got", "gu", "gua", "guan", "guay", "guc", "gue",
        "guet", "gui", "gum", "gun", "gung", "guo", "guoc", "guoi",
        "guom", "guon", "guong", "guot", "gup", "gut", "guy", "guye",
        "guyen", "guyet", "guyt", "h", "ha", "hac", "hach", "hai",
        "ham", "han", "hang", "hanh", "hao", "hap", "hat", "hau",
        "hay", "he", "hec", "hech", "hem", "hen", "heng", "henh",
        "heo", "hep", "het", "heu", "hi", "hia", "hic", "hich",
        "hie", "hiec", "hiem", "hien", "hieng", "hiep", "hiet", "hieu",
        "him", "hin", "hinh", "hip", "hit", "hiu", "ho", "hoa",
        "hoac", "hoach", "hoai", "hoam", "hoan", "hoang", "hoanh", "hoao",
        "hoat", "hoay", "hoc", "hoe", "hoen", "hoet", "hoi", "hom",
        "hon", "hong", "hoo", "hooc", "hop", "hot", "hu", "hua",
        "huan", "huat", "huc", "hue", "huec", "huech", "huen", "huenh",
        "hui", "hum", "hun", "hung", "huo", "huoc", "huoi", "huom",
        "huon", "huong", "huot", "huou", "hup", "hut", "huu", "huy",
        "huyc", "huych", "huye", "huyen", "huyet", "huyn", "huynh", "huyt",
        "hy", "i", "ia", "ic", "ich", "ie", "iec", "iem",
        "ien", "ieng", "iep", "iet", "ieu", "im", "in", "inh",
        "ip", "it", "iu", "k", "ka", "kai", "kam", "kan",
        "kang", "kanh", "kao", "kap", "kat", "kay", "ke", "kec",
        "kech", "kem", "ken", "keng", "kenh", "keo", "kep", "ket",
        "keu", "kh", "kha", "khac", "khach", "khai", "kham", "khan",
        "khang", "khanh", "khao", "khap", "khat", "khau", "khay", "khe",
        "khec", "khem", "khen", "kheng", "khenh", "kheo", "khep", "khet",
        "kheu", "khi", "khia", "khic", "khich", "khie", "khiem", "khien",
        "khieng", "khiep", "khiet", "khieu", "khin", "khinh", "khit", "khiu",
        "kho", "khoa", "khoac", "khoai", "khoam", "khoan", "khoang", "khoanh",
        "khoao", "khoat", "khoay", "khoc", "khoe", "khoen", "khoeo", "khoet",
        "khoi", "khom", "khon", "khong", "khoo", "khoon", "khoong", "khop",
        "khot", "khu", "khua", "khuan", "khuang", "khuat", "khuay", "khuc",
        "khue", "khuec", "khuech", "khui", "khum", "khun", "khung", "khuo",
        "khuoc", "khuoi", "khuon", "khuong", "khuot", "khuou", "khut", "khuu",
        "khuy", "khuya", "khuye", "khuyen", "khuyet", "khuyn", "khuynh", "khuyp",
        "khuyu", "khy", "khye", "khyen", "ki", "kia", "kic", "kich",
        "kie", "kiem", "kien", "kieng", "kiep", "kiet", "kieu", "kim",
        "kin", "kinh", "kip", "kit", "kiu", "ko", "koa", "koai",
        "koay", "koc", "koe", "koeo", "koi", "kom", "kon", "kong",
        "kop", "kot", "ku", "kua", "kuau", "kuc", "kue", "kuen",
        "kuenh", "kui", "kum", "kun", "kung", "kuo", "kuoc", "kuon",
        "kuong", "ky", "kye", "kyet", "l", "la", "lac", "lach",
        "lai", "lam", "lan", "lang", "lanh", "lao", "lap", "lat",
        "lau", "lay", "le", "lec", "lech", "lem", "len", "leng",
        "lenh", "leo", "lep", "let", "leu", "li", "lia", "lic",
        "lich", "lie", "liec", "liem", "lien", "lieng", "liep", "liet",
        "lieu", "lim", "lin", "linh", "lip", "lit", "liu", "lo",
        "loa", "loac", "loai", "loan", "loang", "loanh", "loat", "loay",
        "loc", "loe", "loen", "loet", "loi", "lom", "lon", "long",
        "loo", "loon", "loong", "lop", "lot", "lu", "lua", "luac",
        "luan", "luat", "luay", "luc", "lue", "lui", "lum", "lun",
        "lung", "luo", "luoc", "luoi", "luom", "luon", "luong", "luot",
        "lup", "lut", "luu", "luy", "luya", "luyc", "luych", "luye",
        "luyen", "luyn", "luynh", "ly", "m", "ma", "mac", "mach",
        "mai", "mam", "man", "mang", "manh", "mao", "map", "mat",
        "mau", "may", "me", "mec", "mech", "mem", "men", "meng",
        "menh", "meo", "mep", "met", "meu", "mi", "mia", "mic",
        "mich", "mie", "miem", "mien", "mieng", "miet", "mieu", "mim",
        "min", "minh", "mip", "mit", "miu", "mo", "moa", "moao",
        "moay", "moc", "moi", "mom", "mon", "mong", "moo", "mooc",
        "moon", "moong", "mop", "mot", "mu", "mua", "muan", "muau",
        "muc", "mue", "muen", "mui", "mum", "mun", "mung", "muo",
        "muoc", "muoi", "muom", "muon", "muong", "muop", "muot", "muou",
        "mup", "mut", "muu", "muy", "muya", "muye", "muyen", "my",
        "mye", "myen", "myet", "n", "na", "nac", "nach", "nai",
        "nam", "nan", "nang", "nanh", "nao", "nap", "nat", "nau",
        "nay", "ne", "nec", "nech", "nem", "nen", "neng", "nenh",
        "neo", "nep", "net", "neu", "ng", "nga", "ngac", "ngach",
        "ngai", "ngam", "ngan", "ngang", "nganh", "ngao", "ngap", "ngat",
        "ngau", "ngay", "nge", "ngec", "ngech", "ngen", "nget", "ngh",
        "ngha", "nghan", "nghanh", "nghao", "nghau", "nghe", "nghec", "nghech",
        "nghen", "nghenh", "ngheo", "nghet", "ngheu", "nghi", "nghia", "nghic",
        "nghich", "nghie", "nghiem", "nghien", "nghieng", "nghiep", "nghiet", "nghieu",
        "nghim", "nghin", "nghinh", "nghit", "nghiu", "ngho", "nghoo", "nghu",
        "nghua", "nghui", "nghum", "nghun", "ngi", "ngia", "ngim", "ngiu",
        "ngo", "ngoa", "ngoac", "ngoach", "ngoai", "ngoam", "ngoan", "ngoang",
        "ngoanh", "ngoao", "ngoap", "ngoat", "ngoay", "ngoc", "ngoe", "ngoem",
        "ngoen", "ngoeo", "ngoet", "ngoi", "ngom", "ngon", "ngong", "ngoo",
        "ngoon", "ngoong", "ngop", "ngot", "ngu", "ngua", "nguan", "nguay",
        "nguc", "ngue", "nguec", "nguech", "ngui", "ngum", "ngun", "ngung",
        "nguo", "nguoc", "nguoi", "nguon", "nguong", "ngup", "ngut", "nguu",
        "nguy", "nguye", "nguyen", "nguyet", "nguyt", "nguyu", "ngy", "ngye",
        "ngyen", "nh", "nha", "nhac", "nhach", "nhai", "nham", "nhan",
        "nhang", "nhanh", "nhao", "nhap", "nhat", "nhau", "nhay", "nhe",
        "nhec", "nhech", "nhem", "nhen", "nhenh", "nheo", "nhep", "nhet",
        "nheu", "nhi", "nhia", "nhic", "nhich", "nhie", "nhiec", "nhiem",
        "nhien", "nhiep", "nhiet", "nhieu", "nhim", "nhin", "nhinh", "nhip",
        "nhit", "nhiu", "nho", "nhoa", "nhoai", "nhoam", "nhoan", "nhoang",
        "nhoap", "nhoat", "nhoay", "nhoc", "nhoe", "nhoen", "nhoet", "nhoi",
        "nhom", "nhon", "nhong", "nhop", "nhot", "nhu", "nhua", "nhuan",
        "nhuc", "nhue", "nhui", "nhum", "nhun", "nhung", "nhuo", "nhuoc",
        "nhuom", "nhuon", "nhuong", "nhuot", "nhut", "nhuy", "nhuye", "nhuyen",
        "nhy", "ni", "nia", "nic", "nich", "nie", "niem", "nien",
        "nieng", "niep", "niet", "nieu", "nim", "nin", "ninh", "nip",
        "nit", "niu", "no", "noa", "noan", "noc", "noe", "noen",
        "noi", "nom", "non", "nong", "noo", "noon", "noong", "nop",
        "not", "nu", "nua", "nuan", "nuay", "nuc", "nui", "num",
        "nun", "nung", "nuo", "nuoc", "nuoi", "nuom", "nuon", "nuong",
        "nuop", "nuot", "nup", "nut", "nuu", "nuy", "nuye", "nuyen",
        "ny", "o", "oa", "oac", "oach", "oai", "oam", "oan",
        "oang", "oanh", "oap", "oat", "oc", "oe", "oi", "om",
        "on", "ong", "oo", "ooc", "oon", "oong", "op", "ot",
        "p", "pa", "pac", "pai", "pam", "pan", "pang", "panh",
        "pao", "pap", "pat", "pau", "pe", "pen", "peo", "pet",
        "peu", "pi", "pia", "pie", "piec", "pim", "pin", "pit",
        "po", "poc", "pom", "poo", "poon", "poong", "pop", "pu",
        "pua", "pui", "pun", "puo", "puoc", "puoi", "put", "q",
        "qu", "qua", "quac", "quach", "quai", "quam", "quan", "quang",
        "quanh", "quao", "quap", "quat", "quau", "quay", "que", "quec",
        "quech", "quen", "quenh", "queo", "quet", "queu", "qui", "quit",
        "quo", "quoc", "quoi", "quon", "quong", "quot", "quy", "quyc",
        "quych", "quye", "quyen", "quyet", "quyn", "quynh", "quyt", "r",
        "ra", "rac", "rach", "rai", "ram", "ran", "rang", "ranh",
        "rao", "rap", "rat", "rau", "ray", "re", "rec", "rech",
        "rem", "ren", "reng", "renh", "reo", "rep", "ret", "reu",
        "ri", "ria", "ric", "rich", "rie", "riem", "rien", "rieng",
        "riet", "rieu", "rim", "rin", "rinh", "rip", "rit", "riu",
        "ro", "roa", "roac", "roan", "roang", "roay", "roc", "roe",
        "roet", "roi", "rom", "ron", "rong", "rop", "rot", "ru",
        "rua", "ruan", "ruang", "ruat", "ruay", "ruc", "rue", "rui",
        "rum", "run", "rung", "ruo", "ruoc", "ruoi", "ruom", "ruon",
        "ruong", "ruot", "ruou", "rup", "rut", "ruu", "ruy", "ruye",
        "ruyen", "ruyet", "ry", "ryn", "s", "sa", "sac", "sach",
        "sai", "sam", "san", "sang", "sanh", "sao", "sap", "sat",
        "sau", "say", "se", "sec", "sem", "sen", "senh", "seo",
        "sep", "set", "seu", "si", "sia", "sic", "sich", "sie",
        "siec", "siem", "sien", "sieng", "siet", "sieu", "sim", "sin",
        "sinh", "sip", "sit", "siu", "so", "soa", "soac", "soai",
        "soan", "soang", "soat", "soc", "soi", "som", "son", "song",
        "soo", "sooc", "soon", "soong", "sop", "sot", "su", "sua",
        "suan", "suat", "suau", "suc", "sue", "sui", "sum", "sun",
        "sung", "suo", "suoc", "suoi", "suon", "suong", "suot", "sup",
        "sut", "suu", "suy", "suye", "suyen", "suyt", "sy", "sye",
        "syen", "t", "ta", "tac", "tach", "tai", "tam", "tan",
        "tang", "tanh", "tao", "tap", "tat", "tau", "tay", "te",
        "tec", "tech", "tem", "ten", "teng", "tenh", "teo", "tep",
        "tet", "teu", "th", "tha", "thac", "thach", "thai", "tham",
        "than", "thang", "thanh", "thao", "thap", "that", "thau", "thay",
        "the", "thec", "thech", "them", "then", "thenh", "theo", "thep",
        "thet", "theu", "thi", "thia", "thic", "thich", "thie", "thiec",
        "thiem", "thien", "thieng", "thiep", "thiet", "thieu", "thim", "thin",
        "thinh", "thip", "thit", "thiu", "tho", "thoa", "thoac", "thoai",
        "thoan", "thoang", "thoat", "thoc", "thoe", "thoi", "thom", "thon",
        "thong", "thoo", "thoon", "thoong", "thop", "thot", "thu", "thua",
        "thuac", "thuan", "thuat", "thuc", "thue", "thuec", "thuech", "thui",
        "thum", "thun", "thung", "thuo", "thuoc", "thuoi", "thuom", "thuon",
        "thuong", "thuot", "thup", "thut", "thuu", "thuy", "thuye", "thuyen",
        "thuyet", "ti", "tia", "tic", "tich", "tie", "tiec", "tiem",
        "tien", "tieng", "tiep", "tiet", "tieu", "tim", "tin", "tinh",
        "tip", "tit", "tiu", "to", "toa", "toac", "toai", "toan",
        "toang", "toanh", "toat", "toay", "toc", "toe", "toen", "toet",
        "toi", "tom", "ton", "tong", "too", "toon", "toong", "top",
        "tot", "tr", "tra", "trac", "trach", "trai", "tram", "tran",
        "trang", "tranh", "trao", "trap", "trat", "trau", "tray", "tre",
        "trec", "trech", "trem", "tren", "treng", "trenh", "treo", "trep",
        "tret", "treu", "tri", "tria", "tric", "trich", "trie", "trien",
        "trieng", "triet", "trieu", "trin", "trinh", "trit", "triu", "tro",
        "troc", "troi", "trom", "tron", "trong", "trop", "trot", "tru",
        "trua", "truan", "truat", "truc", "trui", "trum", "trun", "trung",
        "truo", "truoc", "truoi", "truom", "truon", "truong", "truot", "trut",
        "truu", "truy", "truye", "truyen", "truyn", "try", "trye", "tryen",
        "tu", "tua", "tuan", "tuat", "tuc", "tue", "tuec", "tuech",
        "tuen", "tuenh", "tuet", "tui", "tum", "tun", "tung", "tuo",
        "tuoc", "tuoi", "tuom", "tuon", "tuong", "tuop", "tuot", "tuou",
        "tup", "tut", "tuu", "tuy", "tuya", "tuye", "tuyen", "tuyet",
        "tuyn", "tuyp", "tuyt", "ty", "tye", "tyen", "u", "ua",
        "uan", "uang", "uat", "uau", "uay", "uc", "ue", "uen",
        "uet", "ui", "um", "un", "ung", "uo", "uoc", "uoi",
        "uom", "uon", "uong", "uop", "uot", "uou", "up", "ut",
        "uu", "uy", "uyc", "uych", "uye", "uyen", "uyet", "uyn",
        "uynh", "v", "va", "vac", "vach", "vai", "vam", "van",
        "vang", "vanh", "vao", "vap", "vat", "vau", "vay", "ve",
        "vec", "vech", "vem", "ven", "veng", "venh", "veo", "vet",
        "veu", "vi", "via", "vic", "vich", "vie", "viec", "viem",
        "vien", "vieng", "viet", "vim", "vin", "vinh", "vip", "vit",
        "viu", "vo", "voa", "voan", "voc", "voe", "voi", "vom",
        "von", "vong", "voo", "vop", "vot", "vu", "vua", "vuc",
        "vue", "vuet", "vui", "vum", "vun", "vung", "vuo", "vuoc",
        "vuoi", "vuom", "vuon", "vuong", "vuot", "vut", "vuu", "vy",
        "x", "xa", "xac", "xach", "xai", "xam", "xan", "xang",
        "xanh", "xao", "xap", "xat", "xau", "xay", "xe", "xec",
        "xech", "xem", "xen", "xeng", "xenh", "xeo", "xep", "xet",
        "xeu", "xi", "xia", "xic", "xich", "xie", "xiec", "xiem",
        "xien", "xieng", "xiep", "xiet", "xieu", "xim", "xin", "xinh",
        "xip", "xit", "xiu", "xo", "xoa", "xoac", "xoach", "xoai",
        "xoam", "xoan", "xoang", "xoanh", "xoat", "xoay", "xoc", "xoe",
        "xoen", "xoet", "xoi", "xom", "xon", "xong", "xoo", "xoon",
        "xoong", "xop", "xot", "xu", "xua", "xuan", "xuat", "xuay",
        "xuc", "xue", "xuen", "xuenh", "xui", "xum", "xun", "xung",
        "xuo", "xuoc", "xuoi", "xuom", "xuon", "xuong", "xuot", "xup",
        "xut", "xuy", "xuya", "xuye", "xuyen", "xuyet", "xuyt", "xy",
        "xyt", "y", "ye", "yem", "yen", "yeng", "yet", "yeu",
        )

    // ── Syllable prefix init ──────────────────────────────────
    init {
        for (prefix in EMBEDDED) sylInsert(sylPackKey(prefix))
    }

}
