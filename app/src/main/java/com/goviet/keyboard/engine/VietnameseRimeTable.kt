package com.goviet.keyboard.engine

/**
 * VietnameseRimeTable — Canonical flat rime table from
 * https://s.ngonngu.net/syllables/rhymes/
 *
 * Used for word-level recognition (backspace/adopt) instead of
 * heuristic NON_VN_LETTERS filtering.  Lightweight — just a Set
 * of all canonical rimes (162 entries) plus a derived prefix set.
 */
object VietnameseRimeTable {

    /**
     * All canonical Vietnamese rimes (nucleus + coda), lowercase.
     * Each entry is a complete rime that can appear in committed text.
     * Source: s.ngonngu.net/syllables/rhymes/ + standard Vietnamese phonology.
     */
    private val CANONICAL_RIMES: Set<String> = setOf(
        // A (a)
        "a", "ac", "ach", "ai", "am", "an", "ang", "anh", "ao", "ap", "at", "au", "ay",
        "oa", "oac", "oach", "oai", "oam", "oan", "oang", "oanh", "oao", "oap", "oat", "oay",
        // Ă
        "ăc", "ăm", "ăn", "ăng", "ăp", "ăt",
        "oăc", "oăm", "oăn", "oăng", "oăp", "oăt",
        // Â
        "âc", "âm", "ân", "âng", "âp", "ât", "âu", "ây",
        "uâc", "uân", "uâng", "uât", "uây",
        // E
        "e", "ec", "em", "en", "eng", "eo", "ep", "et",
        "oe", "oen", "oeo", "oep", "oet",
        // Ê
        "ê", "êch", "êm", "ên", "ênh", "êp", "êt", "êu",
        "uê", "uêch", "uên", "uênh", "uêt", "uêu",
        // I / Y
        "i", "ich", "im", "in", "inh", "ip", "it", "iu",
        "y", "yêm", "yên", "yêng", "yêt", "yêu",
        "uy", "uya", "uych", "uyn", "uynh", "uyp", "uyt", "uyu", "uyên", "uyêt",
        // O
        "o", "oc", "oi", "om", "on", "ong", "op", "ot",
        // OO
        "ooc", "oong",
        // Ô
        "ô", "ôc", "ôi", "ôm", "ôn", "ông", "ôp", "ôt",
        // Ơ
        "ơ", "ơi", "ơm", "ơn", "ơp", "ơt",
        "uơ",
        // U
        "u", "uc", "ui", "um", "un", "ung", "up", "ut",
        // Ư
        "ư", "ưc", "ưi", "ưm", "ưn", "ưng", "ưt", "ưu",
        "ưa",
        // ƯƠ
        "ươc", "ươi", "ươm", "ươn", "ương", "ươp", "ươt", "ươu",
        // IA / YA
        "ia", "uya",
        // IÊ / YÊ
        "iêc", "iêm", "iên", "iêng", "iêp", "iêt", "iêu",
        // UA
        "ua",
        // UÔ
        "uôc", "uôi", "uôm", "uôn", "uông", "uôt",
    )

    /**
     * All valid prefixes of canonical rimes (length 1..N-1).
     * Includes bare nuclei like "a", "o", "oa", "uw" etc.
     * that are leading parts of longer canonical rimes.
     */
    private val VALID_PREFIXES: Set<String> = buildSet {
        for (rime in CANONICAL_RIMES) {
            for (len in 1 until rime.length) {
                add(rime.substring(0, len))
            }
        }
    }

    /**
     * Base Vietnamese vowels (unaccented).
     */
    private val BASE_VOWELS: Set<Char> = setOf(
        'a', 'ă', 'â', 'e', 'ê', 'i', 'y', 'o', 'ô', 'ơ', 'u', 'ư'
    )

    /**
     * Check if a lowercase, tone-stripped rime is a valid prefix
     * of some canonical Vietnamese rime (or a complete rime itself).
     */
    fun isPrefixValid(rime: String): Boolean {
        if (rime.isEmpty()) return false
        return rime in VALID_PREFIXES || rime in CANONICAL_RIMES
    }

    /**
     * Check if a lowercase, tone-stripped rime is a complete canonical rime.
     */
    fun isComplete(rime: String): Boolean = rime in CANONICAL_RIMES

    /**
     * Check if a character is a base Vietnamese vowel (no tone/diacritic).
     */
    fun isBaseVowel(c: Char): Boolean = c.lowercaseChar() in BASE_VOWELS
}
