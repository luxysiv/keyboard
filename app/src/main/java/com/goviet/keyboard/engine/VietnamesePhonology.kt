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
    // SECTION 2: ZERO-GC FLAT MAP RIME DATA (validation, tone placement)
    // ============================================================

    /**
     * Rime validation and tone placement are delegated to [RimeMap] — a sorted
     * LongArray + binary search backed by 64-bit FNV-1a hashes.  All lookups are
     * primitive (no HashMap boxing, no String allocation) so the composer hot path
     * stays garbage-free on low-end Unisoc / Cortex-A53 devices.
     */

    /**
     * Check if [candidate] (a substring) is a valid prefix of any Vietnamese rime.
     */
    fun isValidPrefix(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean {
        if (length == 0) return true
        return RimeMap.isValidPrefix(RimeMap.hash(candidate, start, length))
    }

    /**
     * Check if a rime formed by concatenating [a] (first [aLen] chars) and [b]
     * (first [bLen] chars) is a valid prefix.  Zero allocation on the hot path.
     */
    fun isValidPrefixCat(a: CharSequence, aLen: Int, b: CharSequence, bLen: Int): Boolean {
        return RimeMap.isValidPrefix(RimeMap.hashCat(a, aLen, b, bLen))
    }

    /**
     * Check if [candidate] is a complete valid rime.
     */
    fun isCompleteRime(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean {
        if (length == 0) return false
        return RimeMap.isComplete(RimeMap.hash(candidate, start, length))
    }

    fun isValidRime(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean =
        isCompleteRime(candidate, start, length)

    fun isStopCoda(candidate: CharSequence, start: Int = 0, length: Int = candidate.length - start): Boolean {
        if (length == 0) return false
        return RimeMap.isStop(RimeMap.hash(candidate, start, length))
    }

    fun getTonePosition(candidate: CharSequence, oldTonePlacement: Boolean, start: Int = 0, length: Int = candidate.length - start): Int {
        if (length == 0) return 0
        val h = RimeMap.hash(candidate, start, length)
        val i = RimeMap.indexOf(h)
        if (i < 0) return 0
        return if (oldTonePlacement) RimeMap.toneOldAt(i) else RimeMap.toneNewAt(i)
    }

    // ============================================================
    // COMBINED VALIDATION (hot-path helpers)
    // ============================================================

    /**
     * Validate that a rime is valid for a specific tone.
     * Used by [VietnameseComposer.handleToneKey].
     */
    fun isRimeValidForTone(rime: String, tone: Tone): Boolean {
        if (rime.isEmpty()) return false
        return RimeMap.isToneAllowed(RimeMap.hash(rime), tone.index)
    }

    /**
     * Validate that a rime (defined by its precomputed hash) is valid for a specific tone.
     */
    fun isRimeHashValidForTone(hash: Long, tone: Tone): Boolean =
        RimeMap.isToneAllowed(hash, tone.index)

    /**
     * Determine tone mark position with onset prefix preprocessing (qu/gi).
     */
    fun findTonePosition(onset: CharSequence, rime: CharSequence, oldTonePlacement: Boolean): Int? {
        val onsetLen = onset.length
        val rimeLen = rime.length
        if (rimeLen == 0) return null

        var rimeStart = 0
        var offset = 0

        if (rimeLen > 1 && onsetLen > 0) {
            val rimeFirst = rime[0]
            val isRimeFirstU = rimeFirst == 'u' || rimeFirst == 'U'
            val isRimeFirstI = rimeFirst == 'i' || rimeFirst == 'I'
            val isQ = (onset[onsetLen - 1] == 'q' || onset[onsetLen - 1] == 'Q') ||
                    (onsetLen >= 2 && (onset[onsetLen - 2] == 'q' || onset[onsetLen - 2] == 'Q') && (onset[onsetLen - 1] == 'u' || onset[onsetLen - 1] == 'U'))
            val isG = (onset[onsetLen - 1] == 'g' || onset[onsetLen - 1] == 'G') ||
                    (onsetLen >= 2 && (onset[onsetLen - 2] == 'g' || onset[onsetLen - 2] == 'G') && (onset[onsetLen - 1] == 'i' || onset[onsetLen - 1] == 'I'))
            if (isRimeFirstU && isQ) { rimeStart = 1; offset = 1 }
            else if (isRimeFirstI && isG) { rimeStart = 1; offset = 1 }
        }

        val h = RimeMap.hash(rime, rimeStart, rimeLen - rimeStart)
        val i = RimeMap.indexOf(h)
        if (i < 0) return null
        val basePos = if (oldTonePlacement) RimeMap.toneOldAt(i) else RimeMap.toneNewAt(i)
        return basePos + offset
    }


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
    // TONE PLACEMENT — delegates to RimeMap (zero-GC)
    // ============================================================

    /**
     * Determines the character index within [rime] where the tone mark lands.
     * [rime] = nucleus + coda (e.g. "oan" for hoàn, "iêng" for tiếng).
     *
     * Pure RimeMap hash lookup — no branching, no String allocation, no
     * intermediate STYLE_VARIANT_RIMES or BARE_NUCLEI sets.  The RimeMap
     * encodes all tone positions (legacy + modern) in its primitive arrays.
     */
    fun determineTonePosition(rime: String, onset: String, placement: TonePlacement): Int {
        if (rime.isEmpty()) return 0
        val h = RimeMap.hash(rime)
        val i = RimeMap.indexOf(h)
        if (i < 0) return 0
        return if (placement == TonePlacement.LEGACY) RimeMap.toneOldAt(i) else RimeMap.toneNewAt(i)
    }

}
