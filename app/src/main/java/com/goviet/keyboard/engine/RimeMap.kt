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

    init { build() }

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

    private fun build() {
        // Complete rime strings
        val rimes = ArrayList<String>(250)
        val stops = HashSet<String>(100)

        val ca = arrayOf("c","ch","p","t","m","n","ng","nh")
        val cs = arrayOf("c","p","t","m","n","ng")
        val cd = arrayOf("t","ch","n","nh")
        val cl = arrayOf("p","t","ch","n","nh")

        fun add(r: String, stop: Boolean = false) { rimes.add(r); if (stop) stops.add(r) }
        fun nuc(nu: String, codas: Array<String>) {
            add(nu); for (c in codas) add(nu + c, c == "c" || c == "ch" || c == "p" || c == "t")
        }

        nuc("a",ca);nuc("ă",cs);nuc("â",cs);nuc("e",ca);nuc("ê",ca);nuc("i",ca)
        nuc("o",cs);nuc("ô",cs);nuc("ơ",cs);nuc("u",cs);nuc("ư",cs);nuc("y",cd)
        nuc("oa",ca);nuc("oă",cs);nuc("oe",cs);nuc("ue",ca);nuc("uy",cl)
        nuc("uâ",cs);nuc("uê",cd);nuc("uô",cs);nuc("uo",cs)
        nuc("ua",cs);add("ưa");add("uơ");nuc("ươ",cs)
        add("ia");nuc("ie",cs);nuc("iê",cs)
        nuc("ye",arrayOf("t","m","n","ng"));nuc("yê",arrayOf("t","m","n","ng"))
        nuc("oo",arrayOf("c","n","ng","m","p","t"))
        nuc("uye",ca);nuc("uyê",ca)
        for (r in arrayOf("ai","ao","au","ay","âu","ây","eo","eu","êu","iu","oi","ôi","ơi","ui","uu","ưi")) add(r)
        for (r in arrayOf("ieu","iêu","yeu","yêu","uoi","uôi","uơi","uou","uya","uyu","ươi","ươu","oai","oao","oay","oeo","uau","uay","uâu","uây","ueu","uêu")) add(r)

        // Nucleus → (tonePosNew, tonePosOld)
        val nt = HashMap<String, IntArray>(80)
        fun tn(nu: String, n: Int, o: Int = n) { nt[nu] = intArrayOf(n, o) }

        for (n in arrayOf("a","ă","â","e","ê","i","o","ô","ơ","u","ư","y")) tn(n, 0)
        for (n in arrayOf("oa","oă","oe","ue","uy")) tn(n, 1, 0)
        for (n in arrayOf("uâ","uê","uô","uo","uơ","ươ","ie","iê","ye","yê","oo")) tn(n, 1, 1)
        tn("ua", 0); tn("ia", 0); tn("ưa", 0)
        tn("uye", 2); tn("uyê", 2)
        for (n in arrayOf("ai","ao","au","ay","âu","ây","eo","eu","êu","iu","oi","ôi","ơi","ui","uu","ưi")) tn(n, 0)
        for (n in arrayOf("ieu","iêu","yeu","yêu","uoi","uôi","uơi","uou","uya","uyu","ươi","ươu","oai","oao","oay","oeo","uau","uay","uâu","uây","ueu","uêu")) tn(n, 1)

        // Nucleus registry for stripping coda
        val nucSet = nt.keys

        // Phase 1: complete rime entries
        val map = HashMap<Long, Entry>(600)
        for (r in rimes) {
            val hk = h(r)
            val nu = stripCoda(r, nucSet)
            val pos = nt[nu] ?: intArrayOf(0, 0)
            map[hk] = Entry(hk, pos[0], pos[1], if (r in stops) 1 else 0, 1)
        }
        // Phase 2: prefix entries (all leading substrings)
        for (r in rimes) {
            for (len in 1 until r.length) {
                val hk = h(r, len)
                map.putIfAbsent(hk, Entry(hk, 0, 0, 0, 0))
            }
        }

        // Phase 3: build open-addressing hash table (linear probing)
        val entryCount = map.size
        var cap = 16
        var tableBits = 4
        while (cap < entryCount * 2) { cap *= 2; tableBits++ }   // load factor ~0.5
        _mask = cap - 1
        _shift = 64 - tableBits

        _tableKeys  = LongArray(cap)
        _tnNew      = ByteArray(cap)
        _tnOld      = ByteArray(cap)
        _stop       = ByteArray(cap)
        _comp       = ByteArray(cap)

        for (e in map.values) {
            val hk = e.hk
            var slot = (hk ushr _shift).toInt() and _mask
            // Find empty slot (0L sentinel — hash is never 0)
            while (_tableKeys[slot] != 0L) slot = (slot + 1) and _mask
            _tableKeys[slot] = hk
            _tnNew[slot]     = e.tnN.toByte()
            _tnOld[slot]     = e.tnO.toByte()
            _stop[slot]      = e.isStop.toByte()
            _comp[slot]      = e.isComp.toByte()
        }
    }

    private fun stripCoda(r: String, nucSet: Set<String>): String {
        var len = r.length
        while (len > 1) { val n = r.substring(0, len); if (nucSet.contains(n)) return n; len-- }
        return r
    }
}
