package com.goviet.keyboard.engine

/**
 * VietnamesePhonology: Unified Vietnamese phonological knowledge base.
 *
 * Merged from: VietnameseFiniteStateTable, VietnameseSpellingGuide, VietnamesePhonology.
 * Single Source of Truth for:
 * - Flat-array Trie for rime/nucleus validation & tone placement
 * - Onset/coda phonological rules
 * - Telex fold/unfold rules and tone placement
 * - Vietnamese vowel/consonant inventories
 */
object VietnamesePhonology {

    // ============================================================
    // SECTION 1: LEXICON (vowels, consonants, onsets, codas)
    // ============================================================
private val BASE_VOWELS = setOf(
        'a', 'ă', 'â', 'e', 'ê', 'i', 'y', 'o', 'ô', 'ơ', 'u', 'ư'
    )

    private val VOWELS = setOf(
        'a', 'ă', 'â', 'e', 'ê', 'i', 'y', 'o', 'ô', 'ơ', 'u', 'ư',
        'á', 'ắ', 'ấ', 'é', 'ế', 'í', 'ý', 'ó', 'ố', 'ớ', 'ú', 'ứ',
        'à', 'ằ', 'ầ', 'è', 'ề', 'ì', 'ỳ', 'ò', 'ồ', 'ờ', 'ù', 'ừ',
        'ả', 'ẳ', 'ẩ', 'ẻ', 'ể', 'ỉ', 'ỷ', 'ỏ', 'ổ', 'ở', 'ủ', 'ử',
        'ã', 'ẵ', 'ẫ', 'ẽ', 'ễ', 'ĩ', 'ỹ', 'õ', 'ỗ', 'ỡ', 'ũ', 'ữ',
        'ạ', 'ặ', 'ậ', 'ẹ', 'ệ', 'ị', 'ỵ', 'ọ', 'ộ', 'ợ', 'ụ', 'ự'
    )

    /**
     * Consonant letters recognized while typing (superset of valid onsets:
     * includes f/j/q/w/x/z which are not valid Vietnamese onsets but are Telex
     * modifier or literal letters).
     */
    private val CONSONANTS = setOf(
        'b', 'c', 'd', 'đ', 'f', 'g', 'h', 'j', 'k', 'l', 'm', 'n',
        'p', 'q', 'r', 's', 't', 'v', 'w', 'x', 'z'
    )

    /**
     * Valid initial consonantal clusters (longest-first order for greedy matching).
     */
    val ONSETS = arrayOf(
        "ngh", "ng", "nh", "th", "tr", "ch", "ph", "kh", "gh", "gi", "qu",
        "b", "c", "d", "đ", "g", "h", "k", "l", "m", "n", "p", "r", "s", "t", "v", "x"
    )

    /**
     * Valid final consonantal clusters.
     */
    val CODAS = arrayOf("ng", "nh", "ch", "m", "p", "n", "t", "c")

    /**
     * True if [c] is one of the 12 base Vietnamese vowels (unaccented), used while
     * actively composing Telex input where accents are applied separately.
     */
    fun isBaseVowel(c: Char): Boolean = c.lowercaseChar() in BASE_VOWELS

    /**
     * True if [c] is any Vietnamese vowel including accented variants, used when
     * recognizing already-tone-marked completed words.
     */
    fun isVowel(c: Char): Boolean = c.lowercaseChar() in VOWELS

    fun isConsonant(c: Char): Boolean = c.lowercaseChar() in CONSONANTS

    // ============================================================
    // SECTION 2: FLAT-ARRAY TRIE (rime validation, tone placement)
    // ============================================================
// ==========================================
    // RIME / NUCLEUS STATE BIT FLAGS
    // ==========================================
    const val FLAG_INVALID = 0
    const val FLAG_PREFIX = 1
    const val FLAG_COMPLETE = 2
    const val FLAG_PREFIX_AND_COMPLETE = FLAG_PREFIX or FLAG_COMPLETE
    const val FLAG_CAN_CODA = 4
    const val FLAG_CAN_TONE = 8
    const val FLAG_STOP_CODA = 16

    // ==========================================
    // STRUCTURAL STATES
    // ==========================================
    enum class StructuralState {
        EMPTY,
        ONSET,
        NUCLEUS,
        CODA,
        RAW_SUFFIX
    }

    // ==========================================
    // FSM ACTION CODES
    // ==========================================
    enum class FsmAction {
        NONE,
        START_ONSET,
        EXTEND_ONSET,
        START_NUCLEUS,
        EXTEND_NUCLEUS,
        TRANSFORM_NUCLEUS,
        TRANSFORM_D,
        APPLY_TONE,
        REMOVE_TONE,
        START_CODA,
        EXTEND_CODA,
        APPEND_LITERAL
    }

    // ==========================================
    // FLAT-ARRAY TRIE DATA STRUCTURE (0-ALLOC)
    // ==========================================
    private const val MAX_NODES = 512
    private var nodeCount = 1 // Node 0 is ROOT

    // ============================================================
    // RIME ALPHABET — single chars appearing in the rime trie.
    // 19 chars: a, ă, â, c, e, ê, g, h, i, m, n, o, ô, ơ, p, t, u, ư, y
    // ============================================================
    private const val CHAR_SET_SIZE = 19
    private val CHAR_INDEX: ByteArray = ByteArray(0x1B1).also { // covers up to ư = U+01B0
        it.fill(-1)
        val chars = charArrayOf(
            'a', 'ă', 'â', 'c', 'e', 'ê', 'g', 'h', 'i',
            'm', 'n', 'o', 'ô', 'ơ', 'p', 't', 'u', 'ư', 'y'
        )
        for ((idx, c) in chars.withIndex()) {
            it[c.code] = idx.toByte()
        }
    }

    // Flat primitive arrays for Cache-friendly CPU execution
    private val TRIE_CHAR = CharArray(MAX_NODES)
    private val TRIE_FLAGS = ByteArray(MAX_NODES)
    private val TRIE_TONE_POS_NEW = ByteArray(MAX_NODES)
    private val TRIE_TONE_POS_OLD = ByteArray(MAX_NODES)
    // Direct child lookup: DIRECT_CHILD[node * CHAR_SET_SIZE + charIndex(ch)] = child nodeId (-1 = none)
    private val DIRECT_CHILD = IntArray(MAX_NODES * CHAR_SET_SIZE) { -1 }

    init {
        buildRimeTrie()
    }

    /**
     * Insert a rime into the Flat-Array Trie.
     */
    private fun insertRime(
        rime: String,
        newTonePos: Int,
        oldTonePos: Int = newTonePos,
        isStopCoda: Boolean = false,
        canTakeCoda: Boolean = false
    ) {
        var curr = 0
        for (i in rime.indices) {
            val ch = rime[i]
            var child = findChild(curr, ch)
            if (child == -1) {
                child = nodeCount++
                TRIE_CHAR[child] = ch
                TRIE_FLAGS[child] = FLAG_PREFIX.toByte()
                TRIE_TONE_POS_NEW[child] = if (curr != 0) TRIE_TONE_POS_NEW[curr] else 0
                TRIE_TONE_POS_OLD[child] = if (curr != 0) TRIE_TONE_POS_OLD[curr] else 0
                val ci = ch.code
                val cidx = if (ci < CHAR_INDEX.size) CHAR_INDEX[ci].toInt() else -1
                if (cidx >= 0) DIRECT_CHILD[curr * CHAR_SET_SIZE + cidx] = child
            }
            curr = child
            if (i < rime.length - 1) {
                TRIE_FLAGS[curr] = (TRIE_FLAGS[curr].toInt() or FLAG_PREFIX).toByte()
            }
        }

        var flags = TRIE_FLAGS[curr].toInt() or FLAG_COMPLETE or FLAG_CAN_TONE
        if (canTakeCoda) flags = flags or FLAG_CAN_CODA
        if (isStopCoda) flags = flags or FLAG_STOP_CODA
        TRIE_FLAGS[curr] = flags.toByte()
        TRIE_TONE_POS_NEW[curr] = newTonePos.toByte()
        TRIE_TONE_POS_OLD[curr] = oldTonePos.toByte()
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun findChild(parent: Int, ch: Char): Int {
        val idx = ch.code
        val ci = if (idx < CHAR_INDEX.size) CHAR_INDEX[idx].toInt() else -1
        if (ci < 0) return -1
        return DIRECT_CHILD[parent * CHAR_SET_SIZE + ci]
    }

    /**
     * Fast 0-Allocation Lookup of a rime/nucleus candidate in the Flat-Array Trie.
     * Returns nodeId or -1 if not found.
     */
    fun findNode(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Int {
        if (length == 0) return 0
        var curr = 0
        val end = start + length
        for (i in start until end) {
            val ch = candidate[i]
            val lower = toLower(ch)
            curr = findChild(curr, lower)
            if (curr == -1) return -1
        }
        return curr
    }

    /**
     * Check if candidate is a valid prefix (or complete rime) in Vietnamese.
     */
    fun isValidPrefix(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean {
        if (length == 0) return true
        val node = findNode(candidate, start, length)
        return node != -1
    }

    /**
     * Check if candidate is a complete valid rime.
     */
    fun isCompleteRime(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean {
        val node = findNode(candidate, start, length)
        if (node == -1) return false
        return (TRIE_FLAGS[node].toInt() and FLAG_COMPLETE) != 0
    }

    /**
     * Alias for isCompleteRime to support full phonological validation.
     */
    fun isValidRime(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean =
        isCompleteRime(candidate, start, length)

    /**
     * Check if candidate can take a following coda consonant.
     */
    fun canTakeCoda(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean {
        val node = findNode(candidate, start, length)
        if (node == -1) return false
        return (TRIE_FLAGS[node].toInt() and FLAG_CAN_CODA) != 0
    }

    /**
     * Check if candidate is a stop-coda rime (c, ch, p, t) restricting tones to Acute/Dot.
     */
    fun isStopCoda(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean {
        val node = findNode(candidate, start, length)
        if (node == -1) {
            if (length == 0) return false
            val lastChar = toLower(candidate[start + length - 1])
            return lastChar == 'p' || lastChar == 't' || lastChar == 'c' ||
                    (length >= 2 && lastChar == 'h' && toLower(candidate[start + length - 2]) == 'c')
        }
        return (TRIE_FLAGS[node].toInt() and FLAG_STOP_CODA) != 0
    }

    /**
     * Lookup tone placement index in rime in O(len) without string allocations.
     */
    fun getTonePosition(candidate: CharSequence, oldTonePlacement: Boolean, start: Int = 0, length: Int = candidate.length - start): Int {
        val node = findNode(candidate, start, length)
        if (node == -1) return 0
        return if (oldTonePlacement) {
            TRIE_TONE_POS_OLD[node].toInt()
        } else {
            TRIE_TONE_POS_NEW[node].toInt()
        }
    }

    /**
     * Single-pass validation for consonant addition to nucleus+coda.
     * Combines isValidPrefix(rime+coda) + isValidCoda(coda) + isValidToneForRime(rime+coda, tone)
     * into one trie walk — eliminates 2 redundant trie traversals.
     *
     * @param nucleus the vowel nucleus (e.g. "ê", "ươ", "oa")
     * @param coda the candidate coda character(s) (e.g. "n", "ng")
     * @param tone the current tone to validate
     * @return true if the coda is valid, the full rime is a valid prefix, and the tone is allowed
     */
    fun validateCodaAddition(nucleus: CharSequence, coda: CharSequence, tone: Tone): Boolean {
        if (coda.isEmpty()) return true
        val codaLen = coda.length

        // Quick set membership check (no trie needed)
        if (codaLen == 1) {
            val c = toLower(coda[0])
            if (c != 'm' && c != 'p' && c != 'n' && c != 't' && c != 'c') return false
        } else if (codaLen == 2) {
            val c0 = toLower(coda[0])
            val c1 = toLower(coda[1])
            val valid = (c0 == 'n' && (c1 == 'g' || c1 == 'h')) || (c0 == 'c' && c1 == 'h')
            if (!valid) return false
        } else {
            return false
        }

        // Combined rime validation: walk trie for nucleus+coda once
        val nLen = nucleus.length

        val node = findNode(nucleus, 0, nLen)
        if (node == -1) return false

        // Walk remaining coda characters from nucleus node
        var curr = node
        for (i in 0 until codaLen) {
            curr = findChild(curr, toLower(coda[i]))
            if (curr == -1) return false
        }

        val flags = TRIE_FLAGS[curr].toInt()
        // Must be a valid prefix (or complete rime)
        if ((flags and FLAG_PREFIX) == 0 && (flags and FLAG_COMPLETE) == 0) return false

        // Tone validation: stop-coda restricts to ACUTE/DOT only
        if (tone != Tone.NONE) {
            val isStop = (flags and FLAG_STOP_CODA) != 0
            if (isStop && tone != Tone.ACUTE && tone != Tone.DOT) return false
        }

        return true
    }

    /**
     * Combined validate + tone position lookup for tone key handling.
     * Single trie walk for rime validation, then reuses the node for tone position.
     *
     * @return the rime node ID if valid, -1 otherwise. Caller uses [getTonePosFromNode] for position.
     */
    fun validateRimeAndFindNode(rime: CharSequence): Int {
        return findNode(rime)
    }

    /**
     * Extract tone position from a pre-found rime node. Zero trie walk.
     */
    fun getTonePosFromNode(node: Int, oldTonePlacement: Boolean): Int {
        if (node == -1) return 0
        return if (oldTonePlacement) TRIE_TONE_POS_OLD[node].toInt() else TRIE_TONE_POS_NEW[node].toInt()
    }

    /**
     * Check if a rime node represents a complete, valid rime with valid tone.
     */
    fun isRimeNodeValidForTone(node: Int, tone: Tone): Boolean {
        if (node == -1) return false
        val flags = TRIE_FLAGS[node].toInt()
        if ((flags and FLAG_COMPLETE) == 0) return false
        if (tone != Tone.NONE) {
            val isStop = (flags and FLAG_STOP_CODA) != 0
            if (isStop && tone != Tone.ACUTE && tone != Tone.DOT) return false
        }
        return true
    }

    /**
     * Determine tone mark position with initial onset prefix preprocessing (e.g. qu, gi).
     */
    fun findTonePosition(onset: CharSequence, rime: CharSequence, oldTonePlacement: Boolean): Int? {
        val onsetLen = onset.length
        val rimeLen = rime.length
        if (rimeLen == 0) return null

        var rimeStart = 0
        var offset = 0

        // Zero-allocation preprocessing for onset prefix: qu / gi
        if (rimeLen > 1 && onsetLen > 0) {
            val rimeFirst = rime[0]
            val isRimeFirstU = rimeFirst == 'u' || rimeFirst == 'U'
            val isRimeFirstI = rimeFirst == 'i' || rimeFirst == 'I'

            val isQ = (onset[onsetLen - 1] == 'q' || onset[onsetLen - 1] == 'Q') ||
                    (onsetLen >= 2 && (onset[onsetLen - 2] == 'q' || onset[onsetLen - 2] == 'Q') && (onset[onsetLen - 1] == 'u' || onset[onsetLen - 1] == 'U'))
            val isG = (onset[onsetLen - 1] == 'g' || onset[onsetLen - 1] == 'G') ||
                    (onsetLen >= 2 && (onset[onsetLen - 2] == 'g' || onset[onsetLen - 2] == 'G') && (onset[onsetLen - 1] == 'i' || onset[onsetLen - 1] == 'I'))

            if (isRimeFirstU && isQ) {
                rimeStart = 1
                offset = 1
            } else if (isRimeFirstI && isG) {
                rimeStart = 1
                offset = 1
            }
        }

        val node = findNode(rime, rimeStart, rimeLen - rimeStart)
        if (node == -1) return null

        val basePos = if (oldTonePlacement) TRIE_TONE_POS_OLD[node].toInt() else TRIE_TONE_POS_NEW[node].toInt()
        return basePos + offset
    }

    // ==========================================
    // ONSET & CODA PHONOLOGICAL RULES (0-ALLOC)
    // ==========================================

    @Suppress("NOTHING_TO_INLINE")
    private inline fun toLower(c: Char): Char =
        if (c in 'A'..'Z') (c.code + 32).toChar() else c.lowercaseChar()

    fun isValidOnset(onset: CharSequence, start: Int = 0, length: Int = onset.length - start): Boolean {
        if (length == 0) return true
        if (length == 1) {
            val c = toLower(onset[start])
            return when (c) {
                'b', 'c', 'd', 'đ', 'g', 'h', 'k', 'l', 'm', 'n', 'p', 'r', 's', 't', 'v', 'x' -> true
                else -> false
            }
        }
        if (length == 2) {
            val c0 = toLower(onset[start])
            val c1 = toLower(onset[start + 1])
            return when (c0) {
                'c' -> c1 == 'h'
                'g' -> c1 == 'h' || c1 == 'i'
                'k' -> c1 == 'h'
                'n' -> c1 == 'h' || c1 == 'g'
                'p' -> c1 == 'h'
                'q' -> c1 == 'u'
                't' -> c1 == 'h' || c1 == 'r'
                else -> false
            }
        }
        if (length == 3) {
            val c0 = toLower(onset[start])
            val c1 = toLower(onset[start + 1])
            val c2 = toLower(onset[start + 2])
            return c0 == 'n' && c1 == 'g' && c2 == 'h'
        }
        return false
    }

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

    /**
     * Validate whether a tone is grammatically allowed on a specific rime (0 Alloc).
     */
    fun isValidToneForRime(rime: CharSequence, tone: Tone, start: Int = 0, length: Int = rime.length - start): Boolean {
        if (tone == Tone.NONE) return true
        if (length == 0) return true
        if (isStopCoda(rime, start, length)) {
            return tone == Tone.ACUTE || tone == Tone.DOT
        }
        return true
    }

    /**
     * Check if a complete word is phonologically valid in Vietnamese.
     */
    fun isValidWord(word: String): Boolean {
        if (word.isEmpty()) return false
        val stripped = VietnameseUnicode.stripToneFromWord(word)
        val len = stripped.length
        for (onsetLen in minOf(3, len) downTo 1) {
            if (isValidOnset(stripped, 0, onsetLen)) {
                if (isValidRime(stripped, onsetLen, len - onsetLen)) {
                    return true
                }
            }
        }
        return isValidRime(stripped, 0, len)
    }

    /**
     * Build the Trie containing all standard and raw Vietnamese rimes using data-driven combinations.
     */
    private fun buildRimeTrie() {
        val codasAll = arrayOf("c", "ch", "p", "t", "m", "n", "ng", "nh")
        val codasStandard = arrayOf("c", "p", "t", "m", "n", "ng")
        val codasDental = arrayOf("t", "ch", "n", "nh")
        val codasLabialDental = arrayOf("p", "t", "ch", "n", "nh")

        fun insertNucleusWithCodas(nucleus: String, codas: Array<String>, tonePos: Int) {
            for (coda in codas) {
                val isStop = coda == "c" || coda == "ch" || coda == "p" || coda == "t"
                insertRime(nucleus + coda, tonePos, tonePos, isStopCoda = isStop, canTakeCoda = false)
            }
        }

        // 1. Single vowels (Bare tone: 0, 0 | with Coda: 0, 0)
        insertRime("a", 0, 0, canTakeCoda = true); insertNucleusWithCodas("a", codasAll, 0)
        insertRime("ă", 0, 0, canTakeCoda = true); insertNucleusWithCodas("ă", codasStandard, 0)
        insertRime("â", 0, 0, canTakeCoda = true); insertNucleusWithCodas("â", codasStandard, 0)
        insertRime("e", 0, 0, canTakeCoda = true); insertNucleusWithCodas("e", codasAll, 0)
        insertRime("ê", 0, 0, canTakeCoda = true); insertNucleusWithCodas("ê", codasAll, 0)
        insertRime("i", 0, 0, canTakeCoda = true); insertNucleusWithCodas("i", codasAll, 0)
        insertRime("o", 0, 0, canTakeCoda = true); insertNucleusWithCodas("o", codasStandard, 0)
        insertRime("ô", 0, 0, canTakeCoda = true); insertNucleusWithCodas("ô", codasStandard, 0)
        insertRime("ơ", 0, 0, canTakeCoda = true); insertNucleusWithCodas("ơ", codasStandard, 0)
        insertRime("u", 0, 0, canTakeCoda = true); insertNucleusWithCodas("u", codasStandard, 0)
        insertRime("ư", 0, 0, canTakeCoda = true); insertNucleusWithCodas("ư", codasStandard, 0)
        insertRime("y", 0, 0, canTakeCoda = true); insertNucleusWithCodas("y", codasDental, 0)

        // 2. Open diphthongs with style variation when bare (Bare: new=1, old=0 | with Coda: 1, 1)
        insertRime("oa", 1, 0, canTakeCoda = true); insertNucleusWithCodas("oa", codasAll, 1)
        insertRime("oă", 1, 0, canTakeCoda = true); insertNucleusWithCodas("oă", codasStandard, 1)
        insertRime("oe", 1, 0, canTakeCoda = true); insertNucleusWithCodas("oe", codasStandard, 1)
        insertRime("ue", 1, 0, canTakeCoda = true); insertNucleusWithCodas("ue", codasAll, 1)
        insertRime("uy", 1, 0, canTakeCoda = true); insertNucleusWithCodas("uy", codasLabialDental, 1)

        // 3. Compound diphthongs (Tone: 1, 1 | with Coda: 1, 1)
        insertRime("uâ", 1, 1, canTakeCoda = true); insertNucleusWithCodas("uâ", codasStandard, 1)
        insertRime("uê", 1, 1, canTakeCoda = true); insertNucleusWithCodas("uê", codasDental, 1)
        insertRime("uô", 1, 1, canTakeCoda = true); insertNucleusWithCodas("uô", codasStandard, 1)
        insertRime("uo", 1, 1, canTakeCoda = true); insertNucleusWithCodas("uo", codasStandard, 1)
        insertRime("ua", 0, 0, canTakeCoda = true); insertNucleusWithCodas("ua", codasStandard, 1)
        insertRime("ưa", 0, 0, canTakeCoda = false)
        insertRime("uơ", 1, 1, canTakeCoda = false)
        insertRime("ươ", 1, 1, canTakeCoda = true); insertNucleusWithCodas("ươ", codasStandard, 1)
        insertRime("ia", 0, 0, canTakeCoda = false)
        insertRime("ie", 1, 1, canTakeCoda = true); insertNucleusWithCodas("ie", codasStandard, 1)
        insertRime("iê", 1, 1, canTakeCoda = true); insertNucleusWithCodas("iê", codasStandard, 1)
        insertRime("ye", 1, 1, canTakeCoda = true); insertNucleusWithCodas("ye", arrayOf("t", "m", "n", "ng"), 1)
        insertRime("yê", 1, 1, canTakeCoda = true); insertNucleusWithCodas("yê", arrayOf("t", "m", "n", "ng"), 1)
        insertRime("oo", 1, 1, canTakeCoda = true); insertNucleusWithCodas("oo", arrayOf("c", "n", "ng", "m", "p", "t"), 1)

        // 4. Triphthong nuclei (Tone: 2, 2 | with Coda: 2, 2)
        insertRime("uye", 2, 2, canTakeCoda = true); insertNucleusWithCodas("uye", codasAll, 2)
        insertRime("uyê", 2, 2, canTakeCoda = true); insertNucleusWithCodas("uyê", codasAll, 2)

        // 5. Offglides (Tone: 0, 0)
        val simpleOffglides = arrayOf(
            "ai", "ao", "au", "ay", "âu", "ây", "eo", "eu", "êu", "iu", "oi", "ôi", "ơi", "ui", "uu", "ưu", "ưi"
        )
        for (r in simpleOffglides) insertRime(r, 0, 0)

        // 6. Compound & Triphthong Offglides (Tone: 1, 1)
        val compoundOffglides = arrayOf(
            "ieu", "iêu", "yeu", "yêu", "uoi", "uôi", "uơi", "uou", "uya", "uyu", "ươi", "ươu",
            "oai", "oao", "oay", "oeo", "uau", "uay", "uâu", "uây", "ueu", "uêu"
        )
        for (r in compoundOffglides) insertRime(r, 1, 1)
    }

    // ============================================================
    // SECTION 3: SPELLING GUIDE (fold/unfold, tone placement)
    // ============================================================
// ============================================================
    // DATA: FOLD TABLE
    // ============================================================
    data class FoldRule(
        val key: Char,
        val fromA: Char,     // primary plain tile
        val fromB: Char,     // secondary plain tile (0 = none)
        val to: Char,        // folded display tile
        val require: CharArray? = null,
        val exclude: CharArray? = null,
        val excludeOnset: String? = null
    )

    private val FOLD_RULES = arrayOf(
        // w horn (priority): o->ơ, u->ư (not after q), a->ă
        FoldRule('w', 'o', 'ô', 'ơ', require = charArrayOf('o', 'ô'), exclude = charArrayOf('ơ')),
        FoldRule('w', 'u', '\u0000', 'ư', require = charArrayOf('u'), exclude = charArrayOf('ư'), excludeOnset = "q"),
        FoldRule('w', 'a', 'â', 'ă', require = charArrayOf('a', 'â'), exclude = charArrayOf('ă')),
        // o: o/ơ -> ô
        FoldRule('o', 'o', 'ơ', 'ô', require = charArrayOf('o', 'ơ')),
        // e: e -> ê
        FoldRule('e', 'e', '\u0000', 'ê', require = charArrayOf('e')),
        // a: a/ă -> â
        FoldRule('a', 'a', 'ă', 'â', require = charArrayOf('a', 'ă'))
    )

    private val EMPTY_RULES = arrayOf<FoldRule>()
    private val W_RULES = arrayOf(FOLD_RULES[0], FOLD_RULES[1], FOLD_RULES[2])
    private val O_RULES = arrayOf(FOLD_RULES[3])
    private val E_RULES = arrayOf(FOLD_RULES[4])
    private val A_RULES = arrayOf(FOLD_RULES[5])

    fun foldRulesFor(key: Char): Array<FoldRule> = when (key) {
        'w' -> W_RULES
        'o' -> O_RULES
        'e' -> E_RULES
        'a' -> A_RULES
        else -> EMPTY_RULES
    }

    /** Plain letter that a folded display letter unfolds back to. */
    fun plainOf(folded: Char): Char = when (folded) {
        'ê' -> 'e'; 'ô' -> 'o'; 'ơ' -> 'o'; 'â' -> 'a'; 'ă' -> 'a'; 'ư' -> 'u'; 'đ' -> 'd'
        else -> folded
    }

    // ============================================================
    // SINGLE-TILE FOLD KERNEL
    // ============================================================
    class FoldResult {
        var nucleus: String = ""
        var hadCharsAfter: Boolean = false
    }

    /**
     * Apply the first applicable rule in [rules] to [nucleus].
     * Returns true and fills [out] on success; false otherwise.
     */
    fun foldSingle(
        nucleus: String,
        rules: Array<FoldRule>,
        withCoda: String,
        onset: String,
        out: FoldResult
    ): Boolean {
        if (nucleus.isEmpty()) return false
        val pLower = nucleus.lowercase()
        val onsetLower = onset.lowercase()
        for (rule in rules) {
            val require = rule.require!!
            var has = false
            for (c in require) if (c != '\u0000' && pLower.indexOf(c) >= 0) { has = true; break }
            if (!has) continue
            if (rule.exclude != null) {
                var blocked = false
                for (c in rule.exclude) if (pLower.indexOf(c) >= 0) { blocked = true; break }
                if (blocked) continue
            }
            if (rule.excludeOnset != null && onsetLower == rule.excludeOnset) continue

            var idx = -1
            for (i in 0 until nucleus.length) {
                val c = nucleus[i].lowercaseChar()
                if (c == rule.fromA || (rule.fromB != '\u0000' && c == rule.fromB)) { idx = i; break }
            }
            if (idx == -1) continue

            val isUpper = nucleus[idx].isUpperCase()
            val replacement = if (isUpper) rule.to.uppercaseChar() else rule.to
            val newNucleus = replaceAt(nucleus, idx, replacement)
            val newRime = newNucleus + withCoda
            if (!VietnamesePhonology.isValidPrefix(newRime)) return false
            out.nucleus = newNucleus
            out.hadCharsAfter = withCoda.isNotEmpty() || (idx < nucleus.length - 1)
            return true
        }
        return false
    }

    // ============================================================
    // UNFOLD KERNEL (double-consume at cursor)
    // ============================================================

    /**
     * Unfold a previously applied fold back to its plain letter and produce the
     * literal that must be appended (user's double-consume rule).
     * On success returns Pair(newNucleus, literalTail) where literalTail is the
     * character(s) appended after the unfold (may be the literal key to preserve).
     */
    class UnfoldResult {
        var nucleus: String = ""
        var tail: Char = '\u0000'
        var foldedIndex: Int = -1
    }

    /**
     * Unfold a previously applied fold back to its plain letter.
     * The composer uses [UnfoldResult.foldedIndex] to decide whether the literal
     * [UnfoldResult.tail] appends to the nucleus (nothing after the fold) or to the
     * raw suffix (characters follow), implementing the user's double-consume rule.
     */
    fun unfold(
        nucleus: String,
        targetType: VietnameseComposer.TargetType,
        isUpper: Boolean,
        out: UnfoldResult
    ): Boolean {
        val target = when (targetType) {
            VietnameseComposer.TargetType.E_NUCLEUS -> 'ê'
            VietnameseComposer.TargetType.O_NUCLEUS -> 'ô'
            VietnameseComposer.TargetType.A_NUCLEUS -> 'â'
            VietnameseComposer.TargetType.W_NUCLEUS -> {
                val pLower = nucleus.lowercase()
                when {
                    pLower.contains("ươ") || pLower.contains('ơ') -> 'ơ'
                    pLower.contains('ư') -> 'ư'
                    pLower.contains('ă') -> 'ă'
                    else -> return false
                }
            }
            else -> return false
        }
        var foundIdx = -1
        var foundChar = '\u0000'
        for (i in 0 until nucleus.length) {
            if (nucleus[i].lowercaseChar() == target) { foundIdx = i; foundChar = nucleus[i]; break }
        }
        if (foundIdx == -1) return false
        val isCharUpper = foundChar.isUpperCase()
        val replacement = if (isCharUpper) plainOf(target).uppercaseChar() else plainOf(target)
        var newNucleus = replaceAt(nucleus, foundIdx, replacement)
        if (targetType == VietnameseComposer.TargetType.W_NUCLEUS && nucleus.lowercase().contains("ươ")) {
            // Replace Ư→U in-place via char array to avoid intermediate String
            val arr = newNucleus.toCharArray()
            for (i in arr.indices) {
                if (arr[i] == 'Ư') arr[i] = 'U'
                else if (arr[i] == 'ư') arr[i] = 'u'
            }
            newNucleus = String(arr)
        }
        val extraChar = if (isUpper) keyCharUpper(targetType) else keyChar(targetType)
        out.nucleus = newNucleus
        out.tail = extraChar
        out.foldedIndex = foundIdx
        return true
    }


    // ============================================================
    // VOWEL COMBINATION TABLE — vowel+vowel nucleus expansion
    // ============================================================
    /**
     * When a vowel character is typed after an existing nucleus, these rules
     * determine the resulting compound nucleus. Ordered by specificity.
     *
     * Key insight from Vietnamese phonology:
     *   ư + o → ươ    (compound)
     *   ư + a → ưa    (compound)
     *   uơ + i → ươi  (offglide)
     *   uơ + u → ươu  (offglide)
     */
    private data class VowelComboRule(
        val nucleusLower: String,
        val char: Char,
        val resultTemplate: String  // 'uppercase' means use char's case for 2nd letter
    )

    private val VOWEL_COMBINATION_RULES = arrayOf(
        VowelComboRule("ư", 'o', "ươ"),   // ươ
        VowelComboRule("ư", 'a', "ưa"),    // ưa
        VowelComboRule("uơ", 'i', "ươi"),  // ươi
        VowelComboRule("uơ", 'u', "ươu"),  // ươu
    )

    /**
     * Lookup vowel combination: nucleus + char → expanded nucleus.
     * Returns null if no special combination applies.
     */
    fun lookupVowelCombination(nucleus: String, char: Char): String? {
        val nLower = nucleus.lowercase()
        val cLower = char.lowercaseChar()
        for (rule in VOWEL_COMBINATION_RULES) {
            if (nLower == rule.nucleusLower && cLower == rule.char) {
                val result = rule.resultTemplate
                val len = result.length
                val nucleusUpper = nucleus.isNotEmpty() && nucleus[0].isUpperCase()
                val charUpper = char.isUpperCase()
                val buf = CharArray(len)
                for (i in 0 until len) {
                    val makeUpper = if (i == 0) nucleusUpper else charUpper
                    buf[i] = if (makeUpper) result[i].uppercaseChar() else result[i]
                }
                return String(buf)
            }
        }
        return null
    }

    // ============================================================
    // ONSET PROMOTION TABLE — gi/qu prefix handling
    // ============================================================
    /**
     * When a vowel is typed after nucleus is a single letter that forms
     * a compound onset prefix with the current onset consonant:
     *   g + i + V → gi|V  (gi becomes onset, V becomes nucleus)
     *   q + u + V → qu|V  (qu becomes onset, V becomes nucleus)
     */
    private val ONSET_PROMOTIONS = mapOf(
        "g" to "gi",
        "q" to "qu"
    )

    /**
     * Check if onset+nucleus should be promoted to a compound onset prefix.
     * Returns the new onset string if promotion applies, null otherwise.
     */
    fun lookupOnsetPromotion(onset: String, nucleus: String): String? {
        val oLower = onset.lowercase()
        val nLower = nucleus.lowercase()
        return ONSET_PROMOTIONS[oLower]?.takeIf { nLower == it.drop(oLower.length) }
    }

    // ============================================================
    // W PRIORITY CHAIN — declarative transformation rules
    // ============================================================
    /**
     * Each W rule matches a pattern in the nucleus and transforms it.
     * Evaluated in order — first match wins.
     */
    private data class WPatternRule(
        val pattern: String,           // substring to match in nucleus (lowercase)
        val transform: (String, String) -> Pair<String, VietnameseComposer.LastToggle?>?  // (nucleus, coda) → result
    )

    private val W_PATTERN_CHAIN = arrayOf(
        // uo/uơ → uơ (open) or ươ (with coda)
        WPatternRule("uo") { nucleus, coda ->
            val hasCoda = coda.isNotEmpty() || nucleus.lowercase() in setOf("uoi", "uou")
            val transformed = buildUoPair(nucleus[0], nucleus[1], hornU = hasCoda)
            val newNucleus = nucleus.replaceRange(0, 2, transformed)
            val newRime = newNucleus + coda
            if (VietnamesePhonology.isValidPrefix(newRime)) {
                Pair(newNucleus, VietnameseComposer.LastToggle('w', VietnameseComposer.TargetType.W_NUCLEUS, hasCoda))
            } else null
        },
        // ươ already exists → no-op
        WPatternRule("ươ") { nucleus, _ ->
            Pair(nucleus, null)
        },
        // ua → ưa
        WPatternRule("ua") { nucleus, coda ->
            val uStr = if (nucleus[0].isUpperCase()) "Ư" else "ư"
            val aStr = if (nucleus.length > 1 && nucleus[1].isUpperCase()) "A" else "a"
            val newNucleus = nucleus.replaceRange(0, 2, uStr + aStr)
            val newRime = newNucleus + coda
            if (VietnamesePhonology.isValidPrefix(newRime)) {
                Pair(newNucleus, VietnameseComposer.LastToggle('w', VietnameseComposer.TargetType.W_NUCLEUS, coda.isNotEmpty()))
            } else null
        },
        // oa → oă
        WPatternRule("oa") { nucleus, coda ->
            val oStr = if (nucleus[0].isUpperCase()) "O" else "o"
            val aStr = if (nucleus.length > 1 && nucleus[1].isUpperCase()) "Ă" else "ă"
            val newNucleus = nucleus.replaceRange(0, 2, oStr + aStr)
            val newRime = newNucleus + coda
            if (VietnamesePhonology.isValidPrefix(newRime)) {
                Pair(newNucleus, VietnameseComposer.LastToggle('w', VietnameseComposer.TargetType.W_NUCLEUS, coda.isNotEmpty()))
            } else null
        },
    )

    // ============================================================
    // W HORN KERNEL
    // ============================================================
    fun applyW(
        nucleus: String,
        withCoda: String,
        onset: String
    ): Pair<String, VietnameseComposer.LastToggle?>? {
        val pLower = nucleus.lowercase()

        // Priority chain: pattern rules evaluated in order, first match wins
        for (rule in W_PATTERN_CHAIN) {
            if (pLower.contains(rule.pattern)) {
                val result = rule.transform(nucleus, withCoda)
                if (result != null) return result
                // Pattern matched but transform failed validation → fall through
            }
        }

        // Generic single-tile fold fallback: o→ơ, u→ư, a→ă
        val res = FoldResult()
        if (foldSingle(nucleus, foldRulesFor('w'), withCoda, onset, res)) {
            return Pair(res.nucleus, VietnameseComposer.LastToggle('w', VietnameseComposer.TargetType.W_NUCLEUS, res.hadCharsAfter))
        }
        return null
    }

    /** Build the uơ/ươ pair preserving casing (hornU controls the first letter). */
    fun buildUoPair(uChar: Char, oChar: Char, hornU: Boolean): String {
        val c0 = if (hornU) {
            if (uChar.isUpperCase()) 'Ư' else 'ư'
        } else {
            if (uChar.isUpperCase()) 'U' else 'u'
        }
        val c1 = if (oChar.isUpperCase()) 'Ơ' else 'ơ'
        return String(charArrayOf(c0, c1))
    }

    private val replaceBuf = ThreadLocal.withInitial { CharArray(16) }

    private fun replaceAt(str: String, idx: Int, replacement: Char): String {
        val len = str.length
        var arr = replaceBuf.get()
        if (arr == null || arr.size < len) {
            arr = CharArray(len.coerceAtLeast(16))
            replaceBuf.set(arr)
        }
        str.toCharArray(arr, 0, 0, len)
        arr[idx] = replacement
        return String(arr, 0, len)
    }

    private fun keyChar(t: VietnameseComposer.TargetType): Char = when (t) {
        VietnameseComposer.TargetType.E_NUCLEUS -> 'e'
        VietnameseComposer.TargetType.O_NUCLEUS -> 'o'
        VietnameseComposer.TargetType.A_NUCLEUS -> 'a'
        VietnameseComposer.TargetType.W_NUCLEUS -> 'w'
        VietnameseComposer.TargetType.W_SOLO -> 'w'
        VietnameseComposer.TargetType.D_ONSET -> 'd'
    }

    private fun keyCharUpper(t: VietnameseComposer.TargetType): Char = keyChar(t).uppercaseChar()

    // ============================================================
    // TONE PLACEMENT — data-driven nucleus-position table
    // ============================================================

    /**
     * Maps each rime (nucleus+coda) to the character index where the tone mark lands.
     *
     * Index semantics:
     *  - pos 0 → tone on the FIRST vowel (head vowel)
     *  - pos 1 → tone on the SECOND vowel (main/rhyming vowel)
     *  - pos 2 → tone on the THIRD vowel
     *
     * Single vowels always get pos 0.
     * Compound nuclei (iê, uô, ươ, ...) get pos 1 (the main vowel is the 2nd char).
     * Triple nuclei (uye, uyê) get pos 2.
     * STYLE_VARIANT_RIMES (oa, oă, oe, ue, uy) differ between LEGACY and MODERN.
     */
    private val STYLE_VARIANT_RIMES = setOf("oa", "oă", "oe", "ue", "uy")

    /**
     * All bare Vietnamese nuclei (no coda). Used to distinguish bare nuclei from
     * coda forms (e.g. "oa" is a bare nucleus, "oan" is a coda form).
     */
    private val BARE_NUCLEI = setOf(
        "a", "ă", "â", "e", "ê", "i", "o", "ô", "ơ", "u", "ư", "y",
        "ua", "ưa", "ia",
        "uâ", "uê", "uô", "uơ", "ươ",
        "ie", "iê", "ye", "yê", "oo", "uo", "oa", "oă", "oe", "ue", "uy",
        "ieu", "yêu", "yeu",
        "uôi", "uơi", "uou", "uya", "uyu", "ươi", "ươu",
        "oai", "oao", "oay", "oeo", "uau", "uay", "uâu", "uây",
        "ueu", "uêu", "uye", "uyê"
    )

    /**
     * Computes tone position for a bare nucleus (no coda) using phonological rules.
     *
     * Rules (derived from Vietnamese phonology):
     *  - Single vowel: pos 0 (tone on the vowel itself)
     *  - Head-vowel nuclei (ua, ưa, ia): pos 0
     *  - Compound/triple nuclei: pos 1 (rhyming vowel is 2nd char)
     *  - Triple nuclei uye/uyê: pos 2
     *
     * This replaces the old NUCLEUS_POSITIONS lookup table with a computed function.
     */
    private fun computeTonePos(nucleus: String): Int = when {
        nucleus.length == 1 -> 0
        nucleus == "ua" || nucleus == "ưa" || nucleus == "ia" -> 0
        nucleus == "uye" || nucleus == "uyê" -> 2
        else -> 1
    }

    /**
     * Determines the character index within [rime] where the tone mark lands.
     * This is the ONE function all tone logic calls.
     *
     * [rime] = nucleus + coda (e.g. "oan" for hoàn, "iêng" for tiếng).
     * The caller must concatenate nucleus and coda before calling this function.
     *
     * Lookup order:
     *  1. STYLE_VARIANT_RIMES → style-dependent (LEGACY=0, MODERN=1)
     *  2. computeTonePos() for bare nuclei (whitelist-checked)
     *  3. Trie fallback (for coda forms not covered by the above)
     */
    fun determineTonePosition(rime: String, onset: String, placement: TonePlacement): Int {
        if (rime.isEmpty()) return 0

        val lc = rime.lowercase()

        // 1. Style-variant bare rimes (coda forms handled by trie)
        if (lc in STYLE_VARIANT_RIMES) {
            return if (placement == TonePlacement.LEGACY) 0 else 1
        }

        // 2. Computed tone position for bare nuclei (whitelist-checked to avoid
        //    misrouting 3-char coda forms like "ang", "anh", "ong" to computeTonePos)
        if (lc in BARE_NUCLEI) {
            return computeTonePos(lc)
        }

        // 3. Fallback: trie handles coda forms (oan, iên, oang, etc.)
        val findOld = (placement == TonePlacement.LEGACY)
        return VietnamesePhonology.findTonePosition(onset, rime, findOld) ?: 0
    }
}
