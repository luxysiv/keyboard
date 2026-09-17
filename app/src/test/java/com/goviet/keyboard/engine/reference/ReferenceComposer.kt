package com.goviet.keyboard.engine.reference

/**
 * Independent reference implementation of Vietnamese Telex composition.
 *
 * Deliberately different architecture from the engine under test:
 *  - never reads RimeMap / OnsetMap / FoldAnchor;
 *  - the keystroke walk is structural (phonotactic, no rime tables);
 *  - validity and spelling are decided against a real dictionary of Vietnamese
 *    syllables ([ReferenceDictionary]); the longest still-valid prefix wins
 *    the syllable span and its literal display is rendered as-is;
 *  - a dictionary entry only matches when every fold mark it needs was actually
 *    produced by a typed fold key ("tuong" stays raw, "tuwowng" is "tương");
 *  - recomputes the display from the raw keystroke log on every key.
 *
 * Spec interpretation:
 *  - order-free keystrokes, the fold key may come after the coda
 *    ("tuana" -> "tuân");
 *  - one Telex cycle per plain letter: repeating a fold key folds the next
 *    plain target if one exists, otherwise closes the cycle by reverting the
 *    nucleus to its plain form and keeping the key literal
 *    ("aanww" -> "anw", "uoww" -> "uow");
 *  - a plain vowel after a coda still folds the nucleus when that lands on a
 *    real syllable ("tuana" -> "tuân"), otherwise it starts a new syllable
 *    ("khoan" + "a");
 *  - when no real Vietnamese syllable covers the keystrokes, the raw text is
 *    echoed verbatim (Phần 3 of the spec).
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
            } else {
                val syl = parseSyllable(raw, i)
                if (syl !== null) {
                    out.append(syl.text)
                    i = syl.next
                } else {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private class Sil(val text: String, val next: Int)

    /**
     * Real dictionary syllable compatible with the current state and keystrokes.
     *
     * Tone resolution: a tone key only lands on a dictionary-approved base
     * spelling. When no dictionary entry carries that exact tone mark (e.g.
     * "ngươi" maps to a sắc "ngưới" the word list omits), the tone is still
     * composed by rule (Telex always produces the toned letter). When the base
     * spelling itself has no ngang entry, null forces the raw echo.
     */
    private fun matchEntry(
        onset: StringBuilder,
        nuc: String,
        coda: String,
        tone: Char?,
        foldSeen: Set<Char>
    ): String? {
        val full = onset.toString() + ReferenceDictionary.toneStrip(nuc) + coda
        val qualified = dict.entriesWithBase(full).filter { e ->
            e.rimeFoldKeys.all { it in foldSeen }
        }
        if (qualified.isEmpty()) return null
        if (tone == null) return qualified.firstOrNull { it.toneKeys.isEmpty() }?.display
        qualified.firstOrNull { it.toneKeys.contains(tone) }?.let { return it.display }
        return tonePlace(qualified.first().display, tone)
    }

    /** Apply the Telex tone key to the given vowel string by rule ("ngươi"+s -> "ngưới"). */
    private fun tonePlace(display: String, tone: Char): String? {
        var idx = -1
        for (i in display.indices) {
            if (display[i] in "aăâeêioôơuưy") idx = i
        }
        if (idx < 0) return null
        if (idx > 0 && display[idx] in "yiuo") {
            for (j in idx - 1 downTo 0) {
                if (display[j] in "aăâeêioôơuưy") {
                    idx = j
                    break
                }
            }
        }
        val table = ReferenceDictionary.baseToTone[display[idx]] ?: return null
        val c = table[tone] ?: return null
        return display.substring(0, idx) + c + display.substring(idx + 1)
    }

    /**
     * Greedy structural walk. Every prefix that matches a real dictionary
     * syllable becomes the running best candidate; the last such prefix is
     * returned. A cycled (spec-mandated) state always wins. Null when no
     * consonant+rime can even start.
     */
    private fun parseSyllable(raw: CharSequence, start: Int): Sil? {
        var onset = StringBuilder()
        var nuc = ""
        var coda = ""
        var tone: Char? = null
        var lit = ""
        var foldSeen = HashSet<Char>()
        var cycleActive = false
        var i = start
        var best: Sil? = null

        fun snapshot(next: Int, force: Boolean = false) {
            if (force) {
                best = Sil(onset.toString() + nuc + coda + lit, next)
                return
            }
            val display = matchEntry(onset, nuc, coda, tone, foldSeen)
            if (display !== null) {
                best = Sil(display + lit, next)
            }
        }

        fun cycleClose(c: Char) {
            nuc = ReferenceDictionary.unfoldToBase(nuc)
            lit += c
            cycleActive = true
            snapshot(i + 1, force = true)
            i++
        }

        while (i < raw.length) {
            val c = raw[i]
            if (c == ' ') break

            if (nuc.isEmpty()) {
                if (c == 'w') {
                    if (dict.hasRimePrefix("ư")) {
                        nuc = "ư"
                        foldSeen.add('w')
                        snapshot(i + 1)
                        i++
                        continue
                    }
                    lit += c
                    i++
                    continue
                }
                if (c == 'd') {
                    when {
                        onset.isEmpty() -> onset.append('d')
                        onset.length == 1 && onset[0] == 'd' -> {
                            onset.setLength(0)
                            onset.append('đ')
                        }
                        else -> break
                    }
                    i++
                    continue
                }
                if (isConsonant(c)) {
                    val two = onset.toString()
                    if (two == "ng" && c == 'h') {
                        onset.append('h')
                    } else if (onset.length >= 2) {
                        break
                    } else {
                        onset.append(c)
                    }
                    i++
                    continue
                }
                if (isVowel(c)) {
                    nuc = c.toString()
                    snapshot(i + 1)
                    i++
                    continue
                }
                if (isToneKey(c)) {
                    lit += c
                    i++
                    continue
                }
                lit += c
                i++
                continue
            }

            when {
                isToneKey(c) -> {
                    when {
                        tone == c -> {
                            tone = null
                            lit += c
                            cycleActive = true
                            snapshot(i + 1, force = true)
                        }
                        tone == null && hasVowel(nuc) -> {
                            tone = c
                            snapshot(i + 1)
                        }
                        else -> lit += c
                    }
                    i++
                }
                c == 'w' -> {
                    val folded = foldW(nuc)
                    when {
                        folded === null && foldSeen.contains('w') -> cycleClose(c)
                        folded === null -> { lit += c; i++ }
                        else -> {
                            nuc = folded
                            foldSeen.add('w')
                            snapshot(i + 1)
                            i++
                        }
                    }
                }
                isVowel(c) && (c == 'a' || c == 'e' || c == 'o') -> {
                    val folded = foldLetter(nuc, c)
                    if (folded !== null) {
                        if (foldSeen.contains(c)) {
                            nuc = folded
                            if (matchEntry(onset, nuc, coda, tone, foldSeen) !== null) {
                                snapshot(i + 1)
                                i++
                                continue
                            }
                            nuc = ReferenceDictionary.unfoldToBase(nuc)
                            lit += c
                            cycleActive = true
                            snapshot(i + 1, force = true)
                            i++
                            continue
                        }
                        nuc = folded
                        foldSeen.add(c)
                        snapshot(i + 1)
                        i++
                        continue
                    }
                    if (foldSeen.contains(c)) {
                        cycleClose(c)
                        continue
                    }
                    if (coda.isNotEmpty()) {
                        break // no fold possible after coda: new syllable
                    }
                    nuc += c
                    snapshot(i + 1)
                    i++
                }
                isVowel(c) -> {
                    if (coda.isEmpty()) {
                        nuc += c
                        snapshot(i + 1)
                        i++
                    } else {
                        break
                    }
                }
                isConsonant(c) -> {
                    if (isCodaChar(c)) {
                        coda += c
                        snapshot(i + 1)
                        i++
                    } else {
                        break
                    }
                }
                else -> { lit += c; i++ }
            }

            if (cycleActive) break
        }

        return best
    }

    private fun hasVowel(nuc: String): Boolean =
        nuc.any { it == 'a' || it == 'ă' || it == 'â' || it == 'e' || it == 'ê' ||
                  it == 'i' || it == 'y' || it == 'o' || it == 'ô' || it == 'ơ' ||
                  it == 'u' || it == 'ư' }

    private fun foldLetter(nuc: String, key: Char): String? {
        val base = ReferenceDictionary.toneStrip(nuc)
        return when (key) {
            'a' -> replaceFirst(base, "a", "â") ?: replaceFirst(base, "ă", "â")
            'e' -> replaceFirst(base, "e", "ê")
            'o' -> firstFoldO(base)
            else -> null
        }
    }

    private fun firstFoldO(base: String): String? {
        for (i in base.indices) {
            if (base[i] == 'o') return base.replaceRange(i, i + 1, "ô")
            if (base[i] == 'ơ' && !(i > 0 && base[i - 1] == 'u')) {
                return base.replaceRange(i, i + 1, "ô")
            }
        }
        return null
    }

    private fun foldW(base: String): String? {
        val uo = base.indexOf("uo")
        if (uo >= 0) return base.replaceRange(uo, uo + 2, "ươ")
        val uoHorn = base.indexOf("uô")
        if (uoHorn >= 0) return base.replaceRange(uoHorn, uoHorn + 2, "ươ")
        if (base.contains("ươ")) return null
        val ua = base.indexOf("ua")
        if (ua >= 0) return base.replaceRange(ua, ua + 1, "ư")
        val oa = base.indexOf("oa")
        if (oa >= 0) return base.replaceRange(oa + 1, oa + 2, "ă")
        for (i in base.indices) {
            if (base[i] == 'u') {
                val next = i + 1
                if (next < base.length && (base[next] == 'o' || base[next] == 'a')) break
                return base.replaceRange(i, i + 1, "ư")
            }
        }
        for (i in base.indices) {
            if (base[i] == 'o' && !(i > 0 && base[i - 1] == 'u')) {
                return base.replaceRange(i, i + 1, "ơ")
            }
        }
        val hatA = base.indexOf('â')
        if (hatA >= 0) return base.replaceRange(hatA, hatA + 1, "ă")
        val hatO = base.indexOf('ô')
        if (hatO >= 0) return base.replaceRange(hatO, hatO + 1, "ơ")
        val a = base.indexOf('a')
        if (a >= 0) return base.replaceRange(a, a + 1, "ă")
        return null
    }

    private fun replaceFirst(s: String, from: String, to: String): String? {
        val i = s.indexOf(from)
        return if (i >= 0) s.replaceRange(i, i + from.length, to) else null
    }

    private fun isCodaChar(c: Char) = c == 'c' || c == 'h' || c == 'g' || c == 'm' || c == 'n' || c == 'p' || c == 't'
    private fun isToneKey(c: Char) = c == 's' || c == 'f' || c == 'r' || c == 'x' || c == 'j'
    private fun isVowel(c: Char) = c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y'
    private fun isConsonant(c: Char) =
        c == 'b' || c == 'c' || c == 'd' || c == 'g' || c == 'h' || c == 'k' || c == 'l' ||
        c == 'm' || c == 'n' || c == 'p' || c == 'q' || c == 'r' || c == 's' || c == 't' || c == 'v' || c == 'x'
}