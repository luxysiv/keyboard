package com.goviet.keyboard.engine.reference

/**
 * Independent reference dictionary of real Vietnamese syllables.
 *
 * Data: vietnameselanguage/syllable → vietnamesesyllable_7884.txt
 *   (BambooEngine/ibus-bamboo data/vietnamese.cm.dict, MIT).
 */
class ReferenceDictionary private constructor(
    private val all: List<Entry>,
    private val byBase: Map<String, List<Entry>>,
    private val rimePrefixes: Set<String>
) {
    data class Entry(
        val display: String,
        val base: String,
        val onset: String,
        val rimeBase: String,
        val toneKeys: List<Char>,
        val foldKeys: List<Char>,
        val rimeFoldKeys: Set<Char>, // fold keys the RIME needs (no onset đ)
        val tonedCharIndex: Int, // index in display where toned char lives
    )

    fun entriesWithBase(base: String): List<Entry> = byBase[base] ?: emptyList()

    /** True if [rime] (nucleus+coda) is a prefix of some real rime base. */
    fun rimePrefixOk(rime: String): Boolean {
        if (rime.isEmpty()) return true
        return rime in rimePrefixes
    }

    /** True if [rime] is a prefix of some real rime base. */
    fun hasRimePrefix(rime: String): Boolean = rime in rimePrefixes

    /** True if some real syllable with base [fullBase] supports tone key [toneKey]. */
    fun toneExists(fullBase: String, toneKey: Char): Boolean =
        entriesWithBase(fullBase).any { it.toneKeys.contains(toneKey) }

    companion object {
        private val tonedToBase = mapOf(
            'á' to 'a', 'à' to 'a', 'ả' to 'a', 'ã' to 'a', 'ạ' to 'a',
            'ắ' to 'ă', 'ằ' to 'ă', 'ẳ' to 'ă', 'ẵ' to 'ă', 'ặ' to 'ă',
            'ấ' to 'â', 'ầ' to 'â', 'ẩ' to 'â', 'ẫ' to 'â', 'ậ' to 'â',
            'é' to 'e', 'è' to 'e', 'ẻ' to 'e', 'ẽ' to 'e', 'ẹ' to 'e',
            'ế' to 'ê', 'ề' to 'ê', 'ể' to 'ê', 'ễ' to 'ê', 'ệ' to 'ê',
            'í' to 'i', 'ì' to 'i', 'ỉ' to 'i', 'ĩ' to 'i', 'ị' to 'i',
            'ó' to 'o', 'ò' to 'o', 'ỏ' to 'o', 'õ' to 'o', 'ọ' to 'o',
            'ố' to 'ô', 'ồ' to 'ô', 'ổ' to 'ô', 'ỗ' to 'ô', 'ộ' to 'ô',
            'ớ' to 'ơ', 'ờ' to 'ơ', 'ở' to 'ơ', 'ỡ' to 'ơ', 'ợ' to 'ơ',
            'ú' to 'u', 'ù' to 'u', 'ủ' to 'u', 'ũ' to 'u', 'ụ' to 'u',
            'ứ' to 'ư', 'ừ' to 'ư', 'ử' to 'ư', 'ữ' to 'ư', 'ự' to 'ư',
            'ý' to 'y', 'ỳ' to 'y', 'ỷ' to 'y', 'ỹ' to 'y', 'ỵ' to 'y'
        )

        private val tonedCharToToneKey = mapOf(
            'á' to 's', 'à' to 'f', 'ả' to 'r', 'ã' to 'x', 'ạ' to 'j',
            'ắ' to 's', 'ằ' to 'f', 'ẳ' to 'r', 'ẵ' to 'x', 'ặ' to 'j',
            'ấ' to 's', 'ầ' to 'f', 'ẩ' to 'r', 'ẫ' to 'x', 'ậ' to 'j',
            'é' to 's', 'è' to 'f', 'ẻ' to 'r', 'ẽ' to 'x', 'ẹ' to 'j',
            'ế' to 's', 'ề' to 'f', 'ể' to 'r', 'ễ' to 'x', 'ệ' to 'j',
            'í' to 's', 'ì' to 'f', 'ỉ' to 'r', 'ĩ' to 'x', 'ị' to 'j',
            'ó' to 's', 'ò' to 'f', 'ỏ' to 'r', 'õ' to 'x', 'ọ' to 'j',
            'ố' to 's', 'ồ' to 'f', 'ổ' to 'r', 'ỗ' to 'x', 'ộ' to 'j',
            'ớ' to 's', 'ờ' to 'f', 'ở' to 'r', 'ỡ' to 'x', 'ợ' to 'j',
            'ú' to 's', 'ù' to 'f', 'ủ' to 'r', 'ũ' to 'x', 'ụ' to 'j',
            'ứ' to 's', 'ừ' to 'f', 'ử' to 'r', 'ữ' to 'x', 'ự' to 'j',
            'ý' to 's', 'ỳ' to 'f', 'ỷ' to 'r', 'ỹ' to 'x', 'ỵ' to 'j'
        )

        /** Map each base vowel to its toned variants keyed by tone letter. */
        val baseToTone: Map<Char, Map<Char, Char>> = mapOf(
            'a' to mapOf('s' to 'á', 'f' to 'à', 'r' to 'ả', 'x' to 'ã', 'j' to 'ạ'),
            'ă' to mapOf('s' to 'ắ', 'f' to 'ằ', 'r' to 'ẳ', 'x' to 'ẵ', 'j' to 'ặ'),
            'â' to mapOf('s' to 'ấ', 'f' to 'ầ', 'r' to 'ẩ', 'x' to 'ẫ', 'j' to 'ậ'),
            'e' to mapOf('s' to 'é', 'f' to 'è', 'r' to 'ẻ', 'x' to 'ẽ', 'j' to 'ẹ'),
            'ê' to mapOf('s' to 'ế', 'f' to 'ề', 'r' to 'ể', 'x' to 'ễ', 'j' to 'ệ'),
            'i' to mapOf('s' to 'í', 'f' to 'ì', 'r' to 'ỉ', 'x' to 'ĩ', 'j' to 'ị'),
            'o' to mapOf('s' to 'ó', 'f' to 'ò', 'r' to 'ỏ', 'x' to 'õ', 'j' to 'ọ'),
            'ô' to mapOf('s' to 'ố', 'f' to 'ồ', 'r' to 'ổ', 'x' to 'ỗ', 'j' to 'ộ'),
            'ơ' to mapOf('s' to 'ớ', 'f' to 'ờ', 'r' to 'ở', 'x' to 'ỡ', 'j' to 'ợ'),
            'u' to mapOf('s' to 'ú', 'f' to 'ù', 'r' to 'ủ', 'x' to 'ũ', 'j' to 'ụ'),
            'ư' to mapOf('s' to 'ứ', 'f' to 'ừ', 'r' to 'ử', 'x' to 'ữ', 'j' to 'ự'),
            'y' to mapOf('s' to 'ý', 'f' to 'ỳ', 'r' to 'ỷ', 'x' to 'ỹ', 'j' to 'ỵ')
        )

        fun toneStrip(s: String): String = s.map { tonedToBase[it] ?: it }.joinToString("")

        private val foldToPlain = mapOf(
            'â' to 'a', 'ă' to 'a', 'ê' to 'e', 'ô' to 'o', 'ơ' to 'o', 'ư' to 'u', 'đ' to 'd'
        )

        /** Revert every fold mark of a nucleus to its plain letter ("ươ" -> "uo"). */
        fun unfoldToBase(s: String): String = s.map { foldToPlain[it] ?: it }.joinToString("")

        fun toneKeysOf(s: String): List<Char> =
            s.mapNotNull { tonedCharToToneKey[it] }.distinct().sorted()

        fun foldKeysOf(s: String): List<Char> {
            val keys = LinkedHashSet<Char>()
            for (c in s) when (c) {
                'â' -> keys.add('a')
                'ă' -> keys.add('w')
                'ê' -> keys.add('e')
                'ô' -> keys.add('o')
                'ơ', 'ư' -> keys.add('w')
                'đ' -> keys.add('d')
            }
            return keys.toList()
        }

        private val onsets = listOf(
            "ngh", "ch", "gh", "gi", "kh", "nh", "ng", "ph", "qu", "th", "tr",
            "b", "c", "d", "đ", "g", "h", "k", "l", "m", "n", "p", "r", "s", "t", "v", "x"
        )

        fun longestOnset(base: String): String =
            onsets.firstOrNull { base.startsWith(it) } ?: ""

        fun load(resource: String = "vietnamese_syllables.txt"): ReferenceDictionary {
            val stream = checkNotNull(
                ReferenceDictionary::class.java.getResourceAsStream("/$resource")
            ) { "missing resource $resource" }
            val lines = stream.bufferedReader(Charsets.UTF_8).readLines()

            val all = lines.mapNotNull { line ->
                val s = line.trim()
                if (s.isEmpty() || !s.all { it.isLowerCase() } || !s.all { it.isLetter() }) return@mapNotNull null
                val display = s
                val base = toneStrip(s)
                val onset = longestOnset(base)
                val rimeBase = base.substring(onset.length)
                val toneKeys = toneKeysOf(s)
                val foldKeys = foldKeysOf(s)
                val rimeFoldKeys = foldKeysOf(rimeBase).toSet()
                // index of the character that actually carries a TONE mark (-1 when none)
                val tonedIdx = display.indices.firstOrNull { display[it] in tonedToBase } ?: -1
                Entry(display, base, onset, rimeBase, toneKeys, foldKeys, rimeFoldKeys, tonedIdx)
            }

            val byBase = all.groupBy { it.base }
            val rimePrefixes = mutableSetOf<String>()
            for (e in all) {
                var r = e.rimeBase
                while (r.isNotEmpty()) {
                    rimePrefixes.add(r)
                    r = r.dropLast(1)
                }
            }
            return ReferenceDictionary(all, byBase, rimePrefixes)
        }
    }
}