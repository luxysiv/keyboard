package com.goviet.keyboard.engine

/**
 * VietnamesePhonology — thin backward-compatible facade over RimeMap + OnsetMap.
 *
 * All logic now lives in RimeMap (rimes, tones, vowels, folds) and OnsetMap
 * (onsets). This object preserves the public API used by tests.
 */
object VietnamesePhonology {

    val BASE_VOWELS = RimeMap.BASE_VOWELS
    val TONE_KEYS = RimeMap.TONE_KEYS
    val VOWEL_MOD_KEYS = RimeMap.VOWEL_MOD_KEYS
    val ONSETS = OnsetMap.ALL_ONSETS
    val CODAS = RimeMap.CODAS

    fun isBaseVowel(c: Char): Boolean = RimeMap.isBaseVowel(c)
    fun isToneKey(c: Char): Boolean = RimeMap.isToneKey(c)
    fun isFoldKey(c: Char): Boolean = RimeMap.isFoldKey(c)
    fun plainOf(folded: Char): Char = RimeMap.plainOf(folded)

    fun isValidPrefix(c: CharSequence, start: Int = 0, length: Int = c.length - start): Boolean =
        length == 0 || RimeMap.isValidPrefix(RimeMap.rimeKey(c, start, length))

    fun isCompleteRime(c: CharSequence, start: Int = 0, length: Int = c.length - start): Boolean =
        length > 0 && RimeMap.isComplete(RimeMap.rimeKey(c, start, length))

    fun isValidRime(c: CharSequence, start: Int = 0, length: Int = c.length - start): Boolean =
        isCompleteRime(c, start, length)

    fun isStopCoda(c: CharSequence, start: Int = 0, length: Int = c.length - start): Boolean =
        RimeMap.isStopCoda(c, start, length)

    fun isValidCoda(coda: CharSequence, start: Int = 0, length: Int = coda.length - start): Boolean {
        if (length == 0) return true
        val c0 = coda[start].lowercaseChar()
        if (length == 1) return c0 == 'm' || c0 == 'p' || c0 == 'n' || c0 == 't' || c0 == 'c'
        if (length == 2) {
            val c1 = coda[start + 1].lowercaseChar()
            return (c0 == 'n' && (c1 == 'g' || c1 == 'h')) || (c0 == 'c' && c1 == 'h')
        }
        return false
    }

    fun isValidOnset(o: CharSequence, start: Int = 0, length: Int = o.length - start): Boolean =
        OnsetMap.isValidOnset(o, start, length)

    fun getTonePosition(c: CharSequence, oldTonePlacement: Boolean, start: Int = 0, length: Int = c.length - start): Int =
        RimeMap.getTonePosition(c, oldTonePlacement, start, length)

    fun isRimeValidForTone(rime: String, tone: Tone): Boolean = RimeMap.isRimeValidForTone(rime, tone)
    fun isRimeHashValidForTone(key: Long, tone: Tone): Boolean = RimeMap.isRimeHashValidForTone(key, tone)
    fun determineTonePositionHash(rk: Long, old: Boolean, nl: Int = 0): Int = RimeMap.determineTonePositionHash(rk, old, nl)
    fun findTonePosition(onset: CharSequence, rime: CharSequence, old: Boolean): Int? = RimeMap.findTonePosition(onset, rime, old)

    fun lookupVowelCombination(nucleus: String, char: Char): String? = RimeMap.combineNucleus(nucleus, char)

    fun isValidWord(word: String): Boolean {
        if (word.isEmpty()) return false
        val stripped = VietnameseUnicode.stripToneFromWord(word)
        val len = stripped.length
        for (onsetLen in minOf(3, len) downTo 1) {
            if (OnsetMap.isCompleteOnset(stripped, 0, onsetLen)) {
                if (isValidRime(stripped, onsetLen, len - onsetLen)) return true
            }
        }
        return isValidRime(stripped, 0, len)
    }
}
