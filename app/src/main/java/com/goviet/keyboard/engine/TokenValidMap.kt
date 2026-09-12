package com.goviet.keyboard.engine

/**
 * TokenValidMap — thin facade over RimeMap's syllable-prefix table.
 *
 * All data and logic (corpus-derived prefixes, diacritic stripping, hash
 * probing) live in [RimeMap]; this object only preserves the historical alias
 * used by tests and callers.
 */
object TokenValidMap {
    @JvmStatic
    fun isValidPrefix(s: String): Boolean = RimeMap.isSyllablePrefixValid(s)

    @JvmStatic
    fun isDisplayPrefixValid(display: String): Boolean = RimeMap.isSyllableDisplayPrefixValid(display)
}
