package com.goviet.keyboard.engine

/**
 * RimeMap — zero-GC primitive hash table for Vietnamese rimes.
 *
 * Open-addressing hash table (linear probing) over primitive LongArray keys —
 * O(1) average lookup (1 probe @ load 0.5) vs binary search O(log n).
 * Cache-friendly: keys table ~8KB fits Snapdragon L1 (32KB+).  No HashMap,
 * no String allocation, no Long/Int boxing on the hot path.
 */
object RimeMap {

    private const val FNV_OFF = -0x342d6e84b540832bL
    private const val FNV_MUL = 0x100000001b3L

    private var _mask = 0          // table_size - 1 (power of two)
    private var _shift = 0         // 64 - tableBits (top-bits indexing)
    private lateinit var _tableKeys: LongArray
    private lateinit var _tnNew: ByteArray
    private lateinit var _tnOld: ByteArray
    private lateinit var _stop: ByteArray
    private lateinit var _comp: ByteArray


    // ── Hash functions (zero allocation) ──────────────────────────
    //
    // CRITICAL: hash()/hashCat() apply `or 1L` at the end to avoid hash=0.
    // hashExtend() takes the RAW intermediate hash (without `or 1L`) and
    // applies `or 1L` itself.  Always use hashRaw/hashCatRaw as the base
    // for incremental extension — never pass hash()/hashCat() output to
    // hashExtend().

    /** Raw intermediate hash — NO `or 1L`. Use as base for hashExtend(). */
    @JvmStatic @JvmOverloads
    fun hashRaw(cs: CharSequence, start: Int = 0, length: Int = cs.length - start): Long {
        var h = FNV_OFF; var i = start; val end = start + length
        while (i < end) { h = h xor (cs[i].lowercaseChar().code.toLong()); h *= FNV_MUL; i++ }
        return h
    }

    /** Raw intermediate hash — NO `or 1L`. Use as base for hashExtend(). */
    @JvmStatic
    fun hashCatRaw(a: CharSequence, aLen: Int, b: CharSequence, bLen: Int): Long {
        var h = FNV_OFF; var i = 0
        while (i < aLen) { h = h xor (a[i].lowercaseChar().code.toLong()); h *= FNV_MUL; i++ }
        i = 0; while (i < bLen) { h = h xor (b[i].lowercaseChar().code.toLong()); h *= FNV_MUL; i++ }
        return h
    }



    @JvmStatic @JvmOverloads
    fun hash(cs: CharSequence, start: Int = 0, length: Int = cs.length - start): Long {
        var h = FNV_OFF; var i = start; val end = start + length
        while (i < end) { h = h xor (cs[i].lowercaseChar().code.toLong()); h *= FNV_MUL; i++ }
        return h or 1L
    }

    @JvmStatic
    fun hashCat(a: CharSequence, aLen: Int, b: CharSequence, bLen: Int): Long {
        var h = FNV_OFF; var i = 0
        while (i < aLen) { h = h xor (a[i].lowercaseChar().code.toLong()); h *= FNV_MUL; i++ }
        i = 0; while (i < bLen) { h = h xor (b[i].lowercaseChar().code.toLong()); h *= FNV_MUL; i++ }
        return h or 1L
    }

    @JvmStatic
    fun hashExtend(baseHash: Long, c: Char): Long {
        var h = baseHash xor (c.lowercaseChar().code.toLong()); h *= FNV_MUL
        return h or 1L
    }

    @JvmStatic
    fun hashExtend(baseHash: Long, cs: CharSequence, start: Int, length: Int): Long {
        var h = baseHash; var i = start; val end = start + length
        while (i < end) { h = h xor (cs[i].lowercaseChar().code.toLong()); h *= FNV_MUL; i++ }
        return h or 1L
    }

    @JvmStatic
    fun hashExtend(baseHash: Long, cs: CharArray, start: Int, length: Int): Long {
        var h = baseHash; var i = start; val end = start + length
        while (i < end) { h = h xor (cs[i].lowercaseChar().code.toLong()); h *= FNV_MUL; i++ }
        return h or 1L
    }

    @JvmStatic
    fun hashAppend(base: CharSequence, baseLen: Int, c: Char): Long {
        var h = FNV_OFF; var i = 0
        while (i < baseLen) { h = h xor (base[i].lowercaseChar().code.toLong()); h *= FNV_MUL; i++ }
        h = h xor (c.lowercaseChar().code.toLong()); h *= FNV_MUL
        return h or 1L
    }

    @JvmStatic
    fun hashChar(c: Char): Long {
        var h = FNV_OFF
        h = h xor (c.lowercaseChar().code.toLong()); h *= FNV_MUL
        return h or 1L
    }

    @JvmStatic
    fun hash2(a: Char, b: Char): Long {
        var h = FNV_OFF
        h = h xor (a.lowercaseChar().code.toLong()); h *= FNV_MUL
        h = h xor (b.lowercaseChar().code.toLong()); h *= FNV_MUL
        return h or 1L
    }

    @JvmStatic
    fun hash3(a: Char, b: Char, c: Char): Long {
        var h = FNV_OFF
        h = h xor (a.lowercaseChar().code.toLong()); h *= FNV_MUL
        h = h xor (b.lowercaseChar().code.toLong()); h *= FNV_MUL
        h = h xor (c.lowercaseChar().code.toLong()); h *= FNV_MUL
        return h or 1L
    }

    // ── Lookup (zero allocation) ──────────────────────────────────

    @JvmStatic fun isValidPrefix(hash: Long): Boolean = find(hash) >= 0
    @JvmStatic fun isComplete(hash: Long): Boolean { val i = find(hash); return i >= 0 && _comp[i].toInt() != 0 }
    @JvmStatic fun isStop(hash: Long): Boolean { val i = find(hash); return i >= 0 && _stop[i].toInt() != 0 }
    @JvmStatic fun toneNew(hash: Long): Int { val i = find(hash); return if (i >= 0) _tnNew[i].toInt() else 0 }
    @JvmStatic fun toneOld(hash: Long): Int { val i = find(hash); return if (i >= 0) _tnOld[i].toInt() else 0 }

    @JvmStatic
    fun isToneAllowed(hash: Long, tone: Int): Boolean {
        val i = find(hash); if (i < 0) return false
        return tone == 0 || _stop[i].toInt() == 0 || tone == 1 || tone == 5
    }

    @JvmStatic
    fun indexOf(hash: Long): Int = find(hash)

    @JvmStatic
    fun toneOldAt(idx: Int): Int = _tnOld[idx].toInt()

    @JvmStatic
    fun toneNewAt(idx: Int): Int = _tnNew[idx].toInt()

    /**
     * O(1) average lookup via linear probing.  Top-bits indexing from FNV-1a
     * hash distributes uniformly.  Empty sentinel is 0L (hash is never 0
     * because all hash functions apply `or 1L`).
     */
    private fun find(hash: Long): Int {
        var i = (hash ushr _shift).toInt() and _mask
        while (true) {
            val k = _tableKeys[i]
            if (k == hash) return i
            if (k == 0L) return -1
            i = (i + 1) and _mask
        }
    }

    // ── Builder (init only — runs once, allocation OK) ────────────

    private fun h(r: String, len: Int = r.length): Long = hash(r, 0, len)

    private data class Entry(val hk: Long, val tnN: Int, val tnO: Int, val isStop: Int, val isComp: Int)

    // Vietnamese rime model (data-driven)
    private val C_ALL    = arrayOf("c","ch","p","t","m","n","ng","nh")
    private val C_SHORT  = arrayOf("c","p","t","m","n","ng")
    private val C_Y      = arrayOf("t","ch","n","nh")
    private val C_UY     = arrayOf("p","t","ch","n","nh")
    private val C_TMNG   = arrayOf("t","m","n","ng")
    private val C_COVER  = arrayOf("c","n","ng","m","p","t")
    private val C_NONE   = emptyArray<String>()

    private data class NucSpec(
        val nucleus: String,
        val codas: Array<String>,
        val tnNew: Int,
        val tnOld: Int = tnNew
    )

    private val NUCLEI = arrayOf(
        NucSpec("a",  C_ALL,   0),    NucSpec("ă",  C_SHORT, 0),
        NucSpec("â",  C_SHORT, 0),    NucSpec("e",  C_ALL,   0),
        NucSpec("ê",  C_ALL,   0),    NucSpec("i",  C_ALL,   0),
        NucSpec("o",  C_SHORT, 0),    NucSpec("ô",  C_SHORT, 0),
        NucSpec("ơ",  C_SHORT, 0),    NucSpec("u",  C_SHORT, 0),
        NucSpec("ư",  C_SHORT, 0),    NucSpec("y",  C_Y,     0),
        NucSpec("oa", C_ALL,   1, 0), NucSpec("oă", C_SHORT, 1, 0),
        NucSpec("oe", C_SHORT, 1, 0), NucSpec("ue", C_ALL,   1, 0),
        NucSpec("uy", C_UY,    1, 0), NucSpec("uâ", C_SHORT, 1, 1),
        NucSpec("uê", C_Y,     1, 1), NucSpec("uô", C_SHORT, 1, 1),
        NucSpec("uo", C_SHORT, 1, 1), NucSpec("ua", C_SHORT, 0),
        NucSpec("ưa", C_NONE,  0),    NucSpec("uơ", C_NONE,  0),
        NucSpec("ươ", C_SHORT, 1, 1), NucSpec("ia", C_NONE,  0),
        NucSpec("ie", C_SHORT, 1, 1), NucSpec("iê", C_SHORT, 1, 1),
        NucSpec("ye", C_TMNG,  1, 1), NucSpec("yê", C_TMNG,  1, 1),
        NucSpec("oo", C_COVER, 1, 1), NucSpec("uye", C_ALL,  2),
        NucSpec("uyê", C_ALL,  2),
        NucSpec("ai",  C_NONE, 0), NucSpec("ao",  C_NONE, 0),
        NucSpec("au",  C_NONE, 0), NucSpec("ay",  C_NONE, 0),
        NucSpec("âu",  C_NONE, 0), NucSpec("ây",  C_NONE, 0),
        NucSpec("eo",  C_NONE, 0), NucSpec("eu",  C_NONE, 0),
        NucSpec("êu",  C_NONE, 0), NucSpec("iu",  C_NONE, 0),
        NucSpec("oi",  C_NONE, 0), NucSpec("ôi",  C_NONE, 0),
        NucSpec("ơi",  C_NONE, 0), NucSpec("ui",  C_NONE, 0),
        NucSpec("uu",  C_NONE, 0), NucSpec("ưi",  C_NONE, 0),
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

    init { build() }

    private fun build() {
        val map = HashMap<Long, Entry>(600)
        val allRimes = mutableListOf<String>()
        for (spec in NUCLEI) {
            allRimes.add(spec.nucleus)
            val nucHash = h(spec.nucleus)
            map.putIfAbsent(nucHash, Entry(nucHash, spec.tnNew, spec.tnOld, 0, 1))
            for (c in spec.codas) {
                val rime = spec.nucleus + c
                allRimes.add(rime)
                val rk = h(rime)
                val isStop = c == "c" || c == "ch" || c == "p" || c == "t"
                map[rk] = Entry(rk, spec.tnNew, spec.tnOld, if (isStop) 1 else 0, 1)
            }
        }
        for (r in allRimes) {
            for (len in 1 until r.length) {
                val hk = h(r, len)
                map.putIfAbsent(hk, Entry(hk, 0, 0, 0, 0))
            }
        }
        val entryCount = map.size
        var cap = 16; var tableBits = 4
        while (cap < entryCount * 2) { cap *= 2; tableBits++ }
        _mask = cap - 1; _shift = 64 - tableBits
        _tableKeys = LongArray(cap)
        _tnNew = ByteArray(cap); _tnOld = ByteArray(cap)
        _stop  = ByteArray(cap); _comp  = ByteArray(cap)
        for (e in map.values) {
            val hk = e.hk
            var slot = (hk ushr _shift).toInt() and _mask
            while (_tableKeys[slot] != 0L) slot = (slot + 1) and _mask
            _tableKeys[slot] = hk
            _tnNew[slot] = e.tnN.toByte(); _tnOld[slot] = e.tnO.toByte()
            _stop[slot]  = e.isStop.toByte(); _comp[slot] = e.isComp.toByte()
        }
    }
}
