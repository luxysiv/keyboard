package com.goviet.keyboard.engine

/**
 * RimeMap — zero-GC primitive flat-map for Vietnamese rimes.
 *
 * Sorted LongArray + binary search.  No HashMap, no String allocation,
 * no Long/Int boxing on the hot path.  Designed for low-end Unisoc / A53.
 */
object RimeMap {

    private const val FNV_OFF = -0x342d6e84b540832bL
    private const val FNV_MUL = 0x100000001b3L

    private var _size = 0
    private lateinit var _keys: LongArray
    private lateinit var _tnNew: ShortArray
    private lateinit var _tnOld: ShortArray
    private lateinit var _stop: ByteArray
    private lateinit var _comp: ByteArray

    init { build() }

    // ── Hash functions (zero allocation) ──────────────────────────

    @JvmStatic @JvmOverloads
    fun hash(cs: CharSequence, start: Int = 0, length: Int = cs.length - start): Long {
        var h = FNV_OFF; var i = start; val end = start + length
        while (i < end) { h = h xor (cs[i].lowercaseChar().code.toLong() and 0xFFL); h *= FNV_MUL; i++ }
        return h or 1L
    }

    @JvmStatic
    fun hashCat(a: CharSequence, aLen: Int, b: CharSequence, bLen: Int): Long {
        var h = FNV_OFF; var i = 0
        while (i < aLen) { h = h xor (a[i].lowercaseChar().code.toLong() and 0xFFL); h *= FNV_MUL; i++ }
        i = 0; while (i < bLen) { h = h xor (b[i].lowercaseChar().code.toLong() and 0xFFL); h *= FNV_MUL; i++ }
        return h or 1L
    }

    @JvmStatic
    fun hashExtend(baseHash: Long, c: Char): Long {
        var h = baseHash xor (c.lowercaseChar().code.toLong() and 0xFFL); h *= FNV_MUL
        return h or 1L
    }

    @JvmStatic
    fun hashExtend(baseHash: Long, cs: CharSequence, start: Int, length: Int): Long {
        var h = baseHash; var i = start; val end = start + length
        while (i < end) { h = h xor (cs[i].lowercaseChar().code.toLong() and 0xFFL); h *= FNV_MUL; i++ }
        return h or 1L
    }

    @JvmStatic
    fun hashExtend(baseHash: Long, cs: CharArray, start: Int, length: Int): Long {
        var h = baseHash; var i = start; val end = start + length
        while (i < end) { h = h xor (cs[i].lowercaseChar().code.toLong() and 0xFFL); h *= FNV_MUL; i++ }
        return h or 1L
    }

    @JvmStatic
    fun hashAppend(base: CharSequence, baseLen: Int, c: Char): Long {
        var h = FNV_OFF; var i = 0
        while (i < baseLen) { h = h xor (base[i].lowercaseChar().code.toLong() and 0xFFL); h *= FNV_MUL; i++ }
        h = h xor (c.lowercaseChar().code.toLong() and 0xFFL); h *= FNV_MUL
        return h or 1L
    }

    @JvmStatic
    fun hashChar(c: Char): Long {
        var h = FNV_OFF
        h = h xor (c.lowercaseChar().code.toLong() and 0xFFL); h *= FNV_MUL
        return h or 1L
    }

    @JvmStatic
    fun hash2(a: Char, b: Char): Long {
        var h = FNV_OFF
        h = h xor (a.lowercaseChar().code.toLong() and 0xFFL); h *= FNV_MUL
        h = h xor (b.lowercaseChar().code.toLong() and 0xFFL); h *= FNV_MUL
        return h or 1L
    }

    @JvmStatic
    fun hash3(a: Char, b: Char, c: Char): Long {
        var h = FNV_OFF
        h = h xor (a.lowercaseChar().code.toLong() and 0xFFL); h *= FNV_MUL
        h = h xor (b.lowercaseChar().code.toLong() and 0xFFL); h *= FNV_MUL
        h = h xor (c.lowercaseChar().code.toLong() and 0xFFL); h *= FNV_MUL
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

    private fun find(hash: Long): Int {
        var lo = 0; var hi = _size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val v = _keys[mid]
            when { hash < v -> hi = mid - 1; hash > v -> lo = mid + 1; else -> return mid }
        }
        return -1
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

        // Phase 3: sort and fill primitive arrays
        val sorted = ArrayList(map.values)
        sorted.sortBy { it.hk }
        _size = sorted.size
        _keys = LongArray(_size); _tnNew = ShortArray(_size); _tnOld = ShortArray(_size)
        _stop = ByteArray(_size); _comp = ByteArray(_size)
        for (i in 0 until _size) {
            val e = sorted[i]
            _keys[i] = e.hk
            _tnNew[i] = e.tnN.toShort()
            _tnOld[i] = e.tnO.toShort()
            _stop[i] = e.isStop.toByte()
            _comp[i] = e.isComp.toByte()
        }
    }

    private fun stripCoda(r: String, nucSet: Set<String>): String {
        var len = r.length
        while (len > 1) { val n = r.substring(0, len); if (nucSet.contains(n)) return n; len-- }
        return r
    }
}
