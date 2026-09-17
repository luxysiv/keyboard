package com.goviet.keyboard.engine.reference

/**
 * Independent reference implementation of Vietnamese Telex composition.
 *
 * Deliberately different architecture from the engine under test:
 *  - never reads RimeMap / OnsetMap / FoldAnchor;
 *  - every structural decision (fold applicability, tone position) is checked
 *    against a real dictionary of Vietnamese syllables;
 *  - recomputes the display from the raw keystroke log on every key.
 *
 * Spec interpretation:
 *  - order-free keystrokes ("tuana" -> "tuân");
 *  - a repeated modifier key completes one cycle: revert to plain form and add
 *    the key as a literal ("aanww" -> "anw", "aww" -> "aw", "uoww" -> "uow",
 *    "tôis" -> "tôis");
 *  - a plain vowel after a coda starts a new syllable unless it can fold the
 *    current nucleus into a real rime ("khoan"+"a" stays "khoana", but the
 *    fold key that creates "tuân" is still accepted after the coda);
 *  - tone/fold keys that do not fit stay literal.
 */
class ReferenceComposer(private val dict: ReferenceDictionary) {

    private val rawLog = StringBuilder()

    fun reset() {
        rawLog.clear()
    }

    fun processKey(c: Char): String {
        rawLog.append(c)
        return solve(rawLog)
    }

    fun solve(raw: CharSequence): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == ' ') {
                out.append(' ')
                i++
                continue
            }
            val syl = parseSyllable(raw, i)
            if (syl !== null) {
                out.append(syl.render())
                i = syl.next
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private class Sil(
        val onset: String,
        val nucleus: String,     // base nucleus with fold marks
        val coda: String,
        val tone: Char?,          // applied tone key
        val literalTail: String,
        val next: Int
    ) {
        fun render(): String {
            val marked = if (tone == null) nucleus else applyToneToPos(nucleus, pos)
            return onset + marked + coda + literalTail
        }
        var pos: Int = 0
        private val base = ReferenceDictionary.toneStrip(nucleus) + coda

        private fun applyToneToPos(nuc: String, position: Int): String {
            if (position < 0 || position >= nuc.length) return nuc
            val c = nuc[position]
            val toned = ReferenceDictionary.baseToTone[c]?.get(tone) ?: return nuc
            return nuc.substring(0, position) + toned + nuc.substring(position + 1)
        }
    }

    /** Greedy single-syllable parse; null when no vowel rime can start. */
    private fun parseSyllable(raw: CharSequence, start: Int): Sil? {
        val onset = StringBuilder()
        var nuc = ""
        var coda = ""
        var tone: Char? = null
        var lit = ""
        val foldSeen = HashSet<Char>()
        var i = start

        while (i < raw.length) {
            val c = raw[i]
            if (c == ' ') break

            if (nuc.isEmpty()) {
                if (isVowel(c)) {
                    if (dict.rimePrefixOk(c.toString())) {
                        nuc = c.toString()
                        i++
                    } else {
                        break
                    }
                    continue
                }
                if (isToneKey(c)) {
                    lit += c
                    i++
                    continue
                }
                if (c == 'w' || c == 'd') {
                    if (c == 'd' && onset.toString() == "d") {
                        onset.setLength(0)
                        onset.append('đ')
                        i++
                        continue
                    }
                    if (c == 'd') {
                        onset.append('d')
                        i++
                        continue
                    }
                    // 'w' cannot open a syllable on its own
                    lit += c
                    i++
                    continue
                }
                if (isConsonant(c)) {
                    if (onset.length >= 2) {
                        // already two letters and no vowel: not a syllable
                        break
                    }
                    onset.append(c)
                    i++
                    continue
                }
                lit += c
                i++
                continue
            }

            // nucleus is open
            when {
                isToneKey(c) -> {
                    when {
                        tone == c -> { tone = null; lit += c }
                        tone == null -> {
                            val fullBase = onset.toString() + ReferenceDictionary.toneStrip(nuc) + coda
                            if (dict.toneExists(fullBase, c)) tone = c else lit += c
                        }
                        else -> lit += c
                    }
                    i++
                }
                c == 'w' || c == 'd' -> {
                    if (c == 'w') {
                        if (foldSeen.contains('w')) {
                            nuc = ReferenceDictionary.toneStrip(nuc)
                            lit += c
                        } else {
                            val folded = foldW(nuc)
                            if (folded != null && dict.rimePrefixOk(folded + coda)) {
                                nuc = folded
                                foldSeen.add('w')
                            } else {
                                lit += c
                            }
                        }
                    } else {
                        // 'd' after a vowel: not part of a rime
                        lit += c
                    }
                    i++
                }
                isVowel(c) && (c == 'a' || c == 'e' || c == 'o') -> {
                    // this letter may act as a fold key on an existing nucleus char
                    val target = when (c) {
                        'a' -> if (nuc.contains('a')) 'a' else if (nuc.contains('ă')) 'ă' else null
                        'e' -> if (nuc.contains('e')) 'e' else null
                        else -> if (nuc.contains('o')) 'o' else null
                    }
                    if (target != null) {
                        if (foldSeen.contains(c)) {
                            // cycle: revert to plain + literal
                            nuc = ReferenceDictionary.toneStrip(nuc)
                            lit += c
                            i++
                            continue
                        }
                        val folded = foldLetter(nuc, c)
                        if (folded != null && dict.rimePrefixOk(folded + coda)) {
                            nuc = folded
                            foldSeen.add(c)
                            i++
                            continue
                        }
                    }
                    if (coda.isEmpty()) {
                        if (dict.rimePrefixOk(nuc + c + coda)) {
                            nuc += c
                            i++
                        } else {
                            // no way: literal (or new syllable if after coda)
                            if (coda.isNotEmpty()) break
                            lit += c
                            i++
                        }
                    } else {
                        // vowel after coda: new syllable
                        break
                    }
                }
                isVowel(c) -> {
                    // plain vowel (i/u/y and the plain side of a/e/o already tried)
                    if (coda.isEmpty()) {
                        if (dict.rimePrefixOk(nuc + c)) {
                            nuc += c
                            i++
                        } else lit += c.also { i++ }
                    } else break // new syllable
                }
                isConsonant(c) -> {
                    if (dict.rimePrefixOk(nuc + coda + c)) {
                        coda += c
                        i++
                    } else {
                        break
                    }
                }
                else -> { lit += c; i++ }
            }
        }

        if (nuc.isEmpty()) return null
        val finalSil = Sil(onset.toString(), nuc, coda, tone, lit, i)
        finalSil.pos = tonePosition(onset.toString(), nuc, coda, tone, dict)
        return finalSil
    }

    private fun foldLetter(nuc: String, key: Char): String? {
        val base = ReferenceDictionary.toneStrip(nuc)
        return when (key) {
            'a' -> replaceFirst(base, 'a', 'â') ?: replaceFirst(base, 'ă', 'â')
            'e' -> replaceFirst(base, 'e', 'ê')
            'o' -> replaceFirst(base, 'o', 'ô')
            else -> null
        }
    }

    private fun foldW(base: String): String? {
        if (base.contains("uo")) {
            val i = base.indexOf("uo")
            return base.replaceRange(i, i + 2, "ươ")
        }
        for ((from, to) in listOf("ô" to "ơ", "â" to "ă", "a" to "ă", "o" to "ơ", "u" to "ư")) {
            val i = base.indexOf(from)
            if (i >= 0) return base.replaceRange(i, i + 1, to)
        }
        return null
    }

    private fun replaceFirst(s: String, from: Char, to: Char): String? {
        val i = s.indexOf(from)
        return if (i >= 0) s.replaceRange(i, i + 1, to.toString()) else null
    }

    private fun replaceFirst(s: String, from: String, to: String): String? {
        val i = s.indexOf(from)
        return if (i >= 0) s.replaceRange(i, i + from.length, to) else null
    }

    /** Tone position index inside the nucleus (coda excluded). */
    private fun tonePosition(onset: String, nuc: String, coda: String, tone: Char?, dict: ReferenceDictionary): Int {
        if (tone == null) return 0
        val fullBase = onset + ReferenceDictionary.toneStrip(nuc) + coda
        val candidates = dict.entriesWithBase(fullBase).filter { it.toneKeys.contains(tone) }
        for (e in candidates) {
            val rimeIdx = e.tonedCharIndex - e.onset.length
            if (rimeIdx >= 0 && rimeIdx < nuc.length) return rimeIdx
        }
        // fallback: last vowel of the nucleus (with coda) else first vowel
        return if (coda.isEmpty()) 0 else nuc.length - 1
    }

    private fun isToneKey(c: Char) = c == 's' || c == 'f' || c == 'r' || c == 'x' || c == 'j'
    private fun isVowel(c: Char) = c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y'
    private fun isConsonant(c: Char) =
        c == 'b' || c == 'c' || c == 'd' || c == 'g' || c == 'h' || c == 'k' || c == 'l' ||
        c == 'm' || c == 'n' || c == 'p' || c == 'q' || c == 'r' || c == 's' || c == 't' || c == 'v' || c == 'x'
}