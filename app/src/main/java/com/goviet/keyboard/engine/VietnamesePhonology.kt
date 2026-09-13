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

    fun isValidOnset(o: CharSequence, start: Int = 0, length: Int = o.length - start): Boolean =
        OnsetMap.isValidOnset(o, start, length)

    fun getTonePosition(c: CharSequence, oldTonePlacement: Boolean, start: Int = 0, length: Int = c.length - start): Int =
        RimeMap.getTonePosition(c, oldTonePlacement, start, length)

    fun isRimeValidForTone(rime: String, tone: Tone): Boolean = RimeMap.isRimeValidForTone(rime, tone)
    fun isRimeHashValidForTone(key: Long, tone: Tone): Boolean = RimeMap.isRimeHashValidForTone(key, tone)
    fun determineTonePositionHash(rk: Long, old: Boolean, nl: Int = 0): Int = RimeMap.determineTonePositionHash(rk, old, nl)
    fun findTonePosition(onset: CharSequence, rime: CharSequence, old: Boolean): Int? = RimeMap.findTonePosition(onset, rime, old)

}
