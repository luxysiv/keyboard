package com.goviet.keyboard.engine

import android.content.Context
import com.goviet.core.AppPreferences
import com.goviet.core.EngineConfig

/**
 * VietnameseComposer — Single-resegment Telex engine.
 *
 * All syllable segmentation is derived by the single `resegment` function.
 * No incremental mutation of syllable fields through per-keystroke handlers —
 * every call to feedKey appends the key to the raw buffer and rederives the
 * full state from scratch.
 */
class VietnameseComposer(var options: EngineOptions = EngineOptions()) {

    var vietnameseModeEnabled: Boolean = true
    var autoCapitalize: Boolean = false

    var macroEnabled: Boolean
        get() = options.macroEnabled
        set(v) { options.macroEnabled = v }

    var alwaysMacro: Boolean
        get() = options.alwaysMacro
        set(v) { options.alwaysMacro = v }

    var directW: Boolean
        get() = options.directW
        set(v) { options.directW = v }

    var oldTonePlacement: Boolean
        get() = options.oldTonePlacement
        set(v) { options.oldTonePlacement = v }

    // ── Fold key set ───────────────────────────────────────────────
    private val FOLD_KEYS = setOf('e', 'o', 'a', 'w')

    // ── Data types ─────────────────────────────────────────────────

    enum class TargetType {
        D_ONSET, E_NUCLEUS, O_NUCLEUS, A_NUCLEUS, W_NUCLEUS, W_SOLO
    }

    data class LastToggle(
        val key: Char,
        val targetType: TargetType,
        val hadCharsAfter: Boolean
    )

    class SyllableState(
        var onset: String = "",
        var nucleus: String = "",
        var coda: String = "",
        var tone: Tone = Tone.NONE,
        var lastToggle: LastToggle? = null,
        var lastUntoggledToneKey: Char? = null,
        var rawSuffix: String = ""
    ) {
        fun reset() {
            onset = ""; nucleus = ""; coda = ""
            tone = Tone.NONE; lastToggle = null
            lastUntoggledToneKey = null; rawSuffix = ""
        }
        fun isEmpty(): Boolean = onset.isEmpty() && nucleus.isEmpty() && coda.isEmpty() && rawSuffix.isEmpty()

        fun toDisplayString(oldTonePlacement: Boolean = false): String {
            val buf = OwnedBuffer()
            toDisplayBuffer(buf, oldTonePlacement)
            return buf.toStringVal()
        }

        fun toDisplayBuffer(out: OwnedBuffer, oldTonePlacement: Boolean = false) {
            out.clear()
            if (isEmpty()) return
            if (tone == Tone.NONE || nucleus.isEmpty()) {
                for (i in 0 until onset.length) out.append(onset[i])
                for (i in 0 until nucleus.length) out.append(nucleus[i])
                for (i in 0 until coda.length) out.append(coda[i])
                for (i in 0 until rawSuffix.length) out.append(rawSuffix[i])
                return
            }
            val rimeKey = RimeMap.keyCat(nucleus, nucleus.length, coda, coda.length)
            val toneIdx = VietnamesePhonology.determineTonePositionHash(rimeKey.toLong(), oldTonePlacement)
            for (i in 0 until onset.length) out.append(onset[i])
            for (i in 0 until nucleus.length) {
                if (i == toneIdx) out.append(VietnameseUnicode.applyTone(nucleus[i], tone))
                else out.append(nucleus[i])
            }
            for (i in 0 until coda.length) out.append(coda[i])
            for (i in 0 until rawSuffix.length) out.append(rawSuffix[i])
        }
    }

    data class AdoptResult(
        val isValid: Boolean,
        val onsetLength: Int,
        val canonicalRaw: String
    )

    sealed class CompositionResult {
        data class Update(val text: CharSequence) : CompositionResult()
        data class CommitAndStartNew(val commitText: String, val newChar: Char) : CompositionResult()
    }

    data class SyncResult(
        var displayText: String = "",
        var isVietnamese: Boolean = true
    )

    // ── Shared pools ───────────────────────────────────────────────
    private val replayState = SyllableState()
    private val stringOut = OwnedBuffer()

    /** Test API: internal buffer + state for processKey. */
    private val processRaw = StringBuilder()
    private val processState = SyllableState()

    fun reset() { replayState.reset(); processRaw.clear(); processState.reset() }
    /** isVietnamese flag — used by tests to switch Vietnamese/Literal mode. */
    var isVietnamese: Boolean = true

    /** Display string of the current interactive state. */
    fun toDisplayString(): String =
        processState.toDisplayString(options.oldTonePlacement)

    /**
     * Generate deconstructed snapshots: adopt [word], replay keystroke by keystroke,
     * return (canonicalRaw, snapshots).
     */
    fun generateDeconstructedSnapshots(word: String): Pair<String, List<Snapshot>> {
        val adopt = adoptWord(word) ?: return Pair(word, listOf(Snapshot(word)))
        val canonical = adopt.canonicalRaw
        val snaps = mutableListOf<Snapshot>()
        val tempState = SyllableState()
        for (i in 0 until canonical.length) {
            resegment(canonical.subSequence(0, i + 1), tempState)
            snaps.add(Snapshot(tempState.toDisplayString(options.oldTonePlacement)))
        }
        return Pair(canonical, snaps)
    }

    data class Snapshot(val displayText: String)


    // ================================================================
    // CORE: resegment — single source of truth
    // ================================================================

    /**
     * Lookahead in raw: collect consonant chars from [from] that could form
     * a coda (stopping at non-consonant or end of string).
     * Used by fold rules to predict the coda before deciding fold variant.
     */
    private fun predictConsonantTail(raw: CharSequence, from: Int): String {
        val sb = StringBuilder()
        var i = from
        while (i < raw.length) {
            val c = raw[i].lowercaseChar()
            // Check functional keys FIRST — they're not coda chars
            if (c in FOLD_KEYS || VietnamesePhonology.TONE_KEYS.indexOf(c) >= 0) {
                i++; continue
            }
            if (isConsonant(c)) { sb.append(c); i++ }
            else break
        }
        return sb.toString()
    }

    private fun resegment(raw: CharSequence, out: SyllableState) {
        out.reset()
        val len = raw.length
        if (len == 0) return

        // ── Step 1: Onset (longest valid consonant prefix) ───────
        // Single vowels (a, e, i, o, u, y, etc.) are NOT onsets — only consonants.
        val maxOnset = minOf(3, len)
        var onsetEnd = 0
        for (onsetLen in maxOnset downTo 1) {
            if (OnsetMap.isValidOnset(raw, 0, onsetLen)) {
                // Single vowel char at start is NOT a valid onset
                if (onsetLen == 1 && VietnamesePhonology.isBaseVowel(raw[0])) continue
                // Compound onsets ending with a vowel (gi, qu) should only win
                // when a vowel follows in the remaining text — otherwise the
                // final vowel character should become the nucleus (e.g. 'gif' →
                // onset 'g' + nucleus 'i' + tone, not onset 'gi' + literal 'f').
                if (onsetLen > 1) {
                    // Only "gi" (ending in 'i') is ambiguous: its 'i' can serve
                    // as the nucleus. "qu" must never shrink — there is no
                    // standalone 'q' onset in Vietnamese.
                    if (raw[onsetLen - 1].lowercaseChar() == 'i') {
                        var vowelAfter = false
                        for (k in onsetLen until len) {
                            if (VietnamesePhonology.isBaseVowel(raw[k])) { vowelAfter = true; break }
                        }
                        if (!vowelAfter) continue
                    }
                }
                onsetEnd = onsetLen
                break
            }
        }
        if (onsetEnd > 0) {
            out.onset = raw.subSequence(0, onsetEnd).toString()
        }

        var pos = onsetEnd

        // ── Step 2–4: Vowel/fold processing ───────────────────────
        var lastFoldKey = '\u0000'   // fold key that last modified nucleus
        var lastFoldNucIdx = -1        // nucleus index where fold was applied
        var lastFoldRawPos = -1        // raw text position of the fold key
        var lastToneKey = '\u0000'
        var syllableLocked = false  // once any char is rejected, the rest of the syllable is literal
        var justUntoggled = false  // true after fold→untoggle, prevents immediate re-fold
        var toneLocked = false  // true after tone key rejected (not cancelled) for invalid rime

        while (pos < len) {
            val c = raw[pos]
            val cLow = c.lowercaseChar()

            // ── Onset fold (d → đ): same data-driven fold/untoggle as nuclei ──
            // The plain 'd' onset has a fold target on OnsetMap; pressing 'd'
            // folds d→đ, pressing 'd' again on the folded "đ" untoggles back to
            // 'd' + a literal 'd' (double-consume), then locks the syllable.
            if ((c == 'd' || c == 'D') && !syllableLocked) {
                val oKey = OnsetMap.onsetKeyOf(out.onset)
                val dFold = OnsetMap.foldTarget(oKey, 'd')
                if (dFold != 0) {
                    out.onset = OnsetMap.applyFold(out.onset, dFold)
                    pos++; continue
                }
                if (out.onset == "đ" || out.onset == "Đ") {
                    out.onset = if (out.onset[0].isUpperCase()) "D" else "d"
                    out.rawSuffix += c
                    syllableLocked = true
                    toneLocked = true
                    pos++; continue
                }
            }

            // ── Tone handling ──────────────────────────────────────
            if (VietnamesePhonology.TONE_KEYS.indexOf(cLow) >= 0) {
                if (toneLocked) { out.rawSuffix += c; syllableLocked = true; pos++; continue }
                val targetTone = Tone.fromKey(cLow)
                if (targetTone != null && out.nucleus.isNotEmpty()) {
                    if (out.nucleus.length >= 2) {
                        val n0 = out.nucleus[0].lowercaseChar()
                        val n1 = out.nucleus[1].lowercaseChar()
                        if ((n0 == 'a' && n1 == 'a') || (n0 == 'e' && n1 == 'e')) {
                            out.rawSuffix += c; syllableLocked = true; toneLocked = true; pos++; continue
                        }
                    }
                    if (lastToneKey != '\u0000' && cLow == lastToneKey) {
                        if (out.tone != Tone.NONE) {
                            out.tone = Tone.NONE
                            out.rawSuffix += c
                            lastToneKey = '\u0000'
                        } else {
                            out.rawSuffix += c
                        }
                        toneLocked = true
                        syllableLocked = true
                        pos++; continue
                    }
                    val rk = buildRimeKey(out.nucleus, out.coda)
                    if (VietnamesePhonology.isRimeHashValidForTone(rk.toLong(), targetTone)) {
                        out.tone = targetTone
                        lastToneKey = cLow
                        toneLocked = false
                    } else {
                        out.rawSuffix += c
                        toneLocked = true
                        syllableLocked = true
                    }
                    pos++; continue
                }
                out.rawSuffix += c; syllableLocked = true; toneLocked = true; pos++; continue
            }

            // ── Vowel modifier / vowel / consonant ─────────────────
            if (cLow == 'e' || cLow == 'o' || cLow == 'a' || cLow == 'w') {
                // Second 'w' immediately after a w-compound (uơ/ươ/ưa/oă)
                // is absorbed: the compound fold's toggle cycle is already consumed,
                // so it must not unfold back to "uo"+"w" (fixes huowwngs → hướng).
                if (cLow == 'w' && !syllableLocked && lastFoldKey == 'w' &&
                    out.nucleus.isNotEmpty() &&
                    RimeMap.isWCompoundForm(RimeMap.rimeKey(out.nucleus))) {
                    lastFoldKey = '\u0000'
                    pos++; continue
                }
                // Solo 'w' on empty nucleus → ư
                if (!syllableLocked && cLow == 'w' && out.nucleus.isEmpty()) {
                    val wChar = if (c.isUpperCase()) 'Ư' else 'ư'
                    out.nucleus = wChar.toString()
                    lastFoldKey = 'w'; lastFoldNucIdx = 0; lastFoldRawPos = pos
                    pos++; continue
                }
                // Vowel modifier: try fold rules
                if (!syllableLocked && cLow in FOLD_KEYS && out.nucleus.isNotEmpty() && !justUntoggled) {
                    val foldIdx = applyFoldRules(c, pos, raw, out)
                    if (foldIdx >= 0) {
                        lastFoldKey = cLow; lastFoldNucIdx = foldIdx; lastFoldRawPos = pos
                        justUntoggled = false
                        pos++; continue
                    }
                    // Fold rejected → check if this is an untoggle:
                    // Untoggle: revert the folded char, then append the new key
                    // to the nucleus (standard Telex: "ooo"→"oo", "eee"→"ee").
                    // Appending to nucleus (not rawSuffix) keeps the nucleus as
                    // a valid rime candidate so following codas slot in naturally.
                    if (lastFoldKey != '\u0000' && cLow == lastFoldKey &&
                        lastFoldNucIdx >= 0 && lastFoldNucIdx < out.nucleus.length &&
                        out.nucleus[lastFoldNucIdx] != VietnamesePhonology.plainOf(out.nucleus[lastFoldNucIdx])) {
                        out.nucleus = replaceAt(out.nucleus, lastFoldNucIdx, VietnamesePhonology.plainOf(out.nucleus[lastFoldNucIdx]))
                        out.nucleus += c
                        lastFoldKey = '\u0000'; lastFoldNucIdx = -1; lastFoldRawPos = -1
                        justUntoggled = true
                        pos++; continue
                    }
                }
                // Vowel combination
                if (!syllableLocked && out.nucleus.isNotEmpty() && cLow != 'w') {
                    val combo = VietnamesePhonology.lookupVowelCombination(out.nucleus, c)
                    if (combo != null) {
                        out.nucleus = combo
                        pos++; continue
                    }
                }
                // Plain vowel → extend nucleus (only while syllable is unlocked)
                if (!syllableLocked && out.coda.isEmpty()) {
                    val candidateKey = RimeMap.rimeKey(out.nucleus + c.toString())
                    if (RimeMap.isValidPrefix(candidateKey)) {
                        out.nucleus += c
                        lastFoldKey = '\u0000'; lastFoldNucIdx = -1; lastFoldRawPos = -1
                        pos++; continue
                    }
                }
                justUntoggled = false
                out.rawSuffix += c; syllableLocked = true
                if (cLow != 'w') toneLocked = true
                pos++; continue
            }

            // ── Base vowel (not a fold key) → extend nucleus ──────
            if (!syllableLocked && !isConsonant(cLow) && VietnamesePhonology.isBaseVowel(c)) {
                if (out.nucleus.isEmpty()) {
                    // First vowel: start nucleus
                    out.nucleus = c.toString()
                    justUntoggled = false
                    pos++; continue
                }
                if (out.coda.isEmpty()) {
                    val candidateKey = RimeMap.rimeKey(out.nucleus + c.toString())
                    if (RimeMap.isValidPrefix(candidateKey)) {
                        out.nucleus += c
                        pos++; continue
                    }
                }
            }

            // ── Consonant: try as coda, otherwise literal + lock ───
            if (!syllableLocked && isConsonant(cLow) && out.nucleus.isNotEmpty()) {
                // Try as coda
                val codaOk = if (out.coda.isEmpty()) {
                    cLow == 'm' || cLow == 'p' || cLow == 'n' || cLow == 't' || cLow == 'c'
                } else if (out.coda.length == 1) {
                    val c0 = out.coda[0].lowercaseChar()
                    (c0 == 'n' && (cLow == 'g' || cLow == 'h')) || (c0 == 'c' && cLow == 'h')
                } else false
                if (codaOk) {
                    val rk = RimeMap.rimeKey(out.nucleus + out.coda + c)
                    if (RimeMap.isValidPrefix(rk) && RimeMap.isToneAllowed(rk, out.tone.index)) {
                        out.coda += c; pos++; continue
                    }
                }
                // Consonants never extend the nucleus (nucleus is vowels-only).
                // Not a valid coda → literal + hard lock.
                out.rawSuffix += c; syllableLocked = true; toneLocked = true; pos++; continue
            }

            // ── Any other char → rawSuffix ────────────────────────
            out.rawSuffix += c; syllableLocked = true; toneLocked = true; pos++
        }

    }

    /** Build composite rime key for nucleus + coda. */
    private fun buildRimeKey(nucleus: String, coda: String): Int {
        return RimeMap.rimeKey(nucleus + coda)
    }

    /**
     * Apply the Telex fold for [c] by reading the fold-target data baked into
     * [RimeMap] for the current nucleus — no per-rule if/else, no hardcoded
     * vowel-pair comparisons.  Returns the nucleus index where the fold landed
     * (untoggle anchor), or -1 when no fold applies.
     */
    private fun applyFoldRules(c: Char, rawPos: Int, raw: CharSequence, out: SyllableState): Int {
        val nuc = out.nucleus
        val nucKey = RimeMap.rimeKey(nuc)
        val primary = RimeMap.foldPrimary(nucKey, c)
        if (primary == 0) return -1
        val alt = if (c == 'w') RimeMap.foldAlt(nucKey) else 0

        if (alt == 0) {
            // Single-target fold (a→â, e→ê, o→ô, w singles, ua→ưa, oa→oă):
            // validate against the current coda, plus the predicted tail when
            // the map says this fold is lookahead-sensitive.
            val newNuc = RimeMap.applyFold(nuc, primary)
            var ok = isValidRime(newNuc, out.coda)
            if (!ok && RimeMap.foldPrimaryLookahead(nucKey)) {
                val tail = predictConsonantTail(raw, rawPos + 1)
                if (tail.isNotEmpty()) ok = isValidRime(newNuc, out.coda + tail)
            }
            if (!ok) return -1
            out.nucleus = newNuc
            return RimeMap.foldPos(primary)
        }

        // Dual-variant w-compound (uo/uô → uơ/ươ): both candidates come from
        // the map; pick the one whose rime — including the predicted coda tail —
        // validates via RimeMap.  Tie-break (both open rimes valid) by onset:
        // the open "uơ" form only exists after the onsets listed in OnsetMap.
        val tail = predictConsonantTail(raw, rawPos + 1)
        val candCoda = out.coda + tail
        val primNuc = RimeMap.applyFold(nuc, primary)
        val altNuc = RimeMap.applyFold(nuc, alt)
        val primValid = isValidRime(primNuc, candCoda)
        val altValid = isValidRime(altNuc, candCoda)
        val chosen: String? = when {
            primValid && !altValid -> primNuc
            altValid && !primValid -> altNuc
            primValid && altValid -> {
                val openUoOk = out.onset.isEmpty() || OnsetMap.allowsOpenUo(out.onset)
                if (out.coda.isNotEmpty() || !openUoOk) primNuc else altNuc
            }
            else -> null
        }
        if (chosen == null) return -1
        out.nucleus = chosen
        return RimeMap.foldPos(primary)
    }

    private fun isValidRime(nucleus: String, coda: String): Boolean {
        return RimeMap.isValidPrefix(RimeMap.keyCat(nucleus, nucleus.length, coda, coda.length))
    }

    private fun replaceAt(str: String, idx: Int, replacement: Char): String {
        val arr = CharArray(str.length)
        str.toCharArray(arr, 0, 0, str.length)
        arr[idx] = replacement
        return String(arr, 0, str.length)
    }

    private fun isConsonant(c: Char): Boolean =
        OnsetMap.isValidOnsetSingle(c) || OnsetMap.isPrefixOfCompound(c)

    // ================================================================
    // PUBLIC API
    // ================================================================

    fun feedKey(state: SyllableState, key: Char): Boolean {
        // Controller manages composingRaw; feedKey just marks key as handled.
        // Actual resegment is done via compileRaw/replayRawToState.
        return true
    }

    fun feedKey(composingRaw: StringBuilder, state: SyllableState, key: Char): Boolean {
        // Controller already appended key to composingRaw — just resegment.
        resegment(composingRaw, state)
        return true
    }

    fun processKey(key: Char): CompositionResult {
        val isBoundary = key == ' ' || key == '\n' || key == '\t'
        if (isBoundary) {
            val commitText = processState.toDisplayString(options.oldTonePlacement)
            processRaw.clear()
            processState.reset()
            return CompositionResult.CommitAndStartNew(commitText, key)
        }
        processRaw.append(key)
        resegment(processRaw, processState)
        return CompositionResult.Update(processState.toDisplayString(options.oldTonePlacement))
    }

    fun backspace(): String {
        if (processRaw.isNotEmpty()) {
            processRaw.deleteCharAt(processRaw.length - 1)
            resegment(processRaw, processState)
        }
        return processState.toDisplayString(options.oldTonePlacement)
    }

    fun reDerive(text: String): String {
        if (text.isEmpty()) return ""
        val buf = OwnedBuffer()
        val tempState = SyllableState()
        for (c in text) {
            if (c == ' ' || c == '\n' || c == '\t') {
                tempState.toDisplayBuffer(buf, options.oldTonePlacement)
                buf.append(c)
                tempState.reset()
            } else {
                tempState.rawSuffix += c
            }
        }
        tempState.toDisplayBuffer(buf, options.oldTonePlacement)
        return buf.toStringVal()
    }

    fun loadSyllable(state: SyllableState, isStaticReDerive: Boolean) {
        processState.onset = state.onset
        processState.nucleus = state.nucleus
        processState.coda = state.coda
        processState.tone = state.tone
        processState.rawSuffix = state.rawSuffix
        processRaw.clear()
        processRaw.append(state.onset)
        processRaw.append(state.nucleus)
        processRaw.append(state.coda)
        processRaw.append(state.rawSuffix)
    }

    fun syncStateFromRaw(raw: String, mode: CompositionMode, buf: SyncResult) {
        buf.isVietnamese = mode == CompositionMode.VIETNAMESE
        if (mode == CompositionMode.LITERAL) {
            buf.displayText = raw
        } else {
            val tempState = SyllableState()
            resegment(raw, tempState)
            buf.displayText = tempState.toDisplayString(options.oldTonePlacement)
        }
    }

    // ================================================================
    // REPLAY / COMPILE
    // ================================================================

    fun replayRawToState(raw: CharSequence, state: SyllableState) {
        resegment(raw, state)
    }

    fun compileRaw(raw: CharSequence, vietnamese: Boolean, out: OwnedBuffer) {
        out.clear()
        if (raw.length == 0) return
        if (!vietnamese || !vietnameseModeEnabled) { out.append(raw); return }

        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (isBoundaryKey(c)) {
                out.append(c)
                replayState.reset()
                i++
                continue
            }
            val start = i
            while (i < raw.length && !isBoundaryKey(raw[i])) i++
            val syllable = raw.subSequence(start, i)
            resegment(syllable, replayState)
            out.append(replayState.toDisplayString(options.oldTonePlacement))
        }
        replayState.reset()
    }

    fun compileRaw(raw: CharSequence, vietnamese: Boolean, out: OwnedBuffer, maxLen: Int) {
        out.clear()
        val rawLen = maxLen.coerceAtMost(raw.length)
        if (rawLen == 0) return
        if (!vietnamese || !vietnameseModeEnabled) { out.append(raw, 0, rawLen); return }

        var i = 0
        while (i < rawLen) {
            val c = raw[i]
            if (isBoundaryKey(c)) {
                out.append(c)
                replayState.reset()
                i++
                continue
            }
            val start = i
            while (i < rawLen && !isBoundaryKey(raw[i])) i++
            val syllable = raw.subSequence(start, i)
            resegment(syllable, replayState)
            out.append(replayState.toDisplayString(options.oldTonePlacement))
        }
        replayState.reset()
    }

    private fun isBoundaryKey(c: Char): Boolean {
        return c == ' ' || c == '\n' || c == '\t' || c == '\r'
    }

    // ================================================================
    // ADOPT WORD
    // ================================================================

    fun adoptWord(word: String): AdoptResult? {
        if (word.isEmpty()) return null
        val nfcWord = VietnameseUnicode.normalizeNfc(word)

        var detectedTone = Tone.NONE
        val untonedChars = StringBuilder()
        for (c in nfcWord) {
            val t = extractToneFromChar(c)
            if (t != Tone.NONE && detectedTone == Tone.NONE) detectedTone = t
            untonedChars.append(VietnameseUnicode.stripTone(c))
        }
        val baseWord = untonedChars.toString()
        val baseLower = baseWord.lowercase()

        var onset = ""
        var remainingAfterOnset = baseWord
        for (onsetLen in minOf(3, baseLower.length) downTo 1) {
            if (OnsetMap.isCompleteOnset(baseLower, 0, onsetLen)) {
                onset = baseWord.substring(0, onsetLen)
                remainingAfterOnset = baseWord.substring(onsetLen)
                break
            }
        }

        val nucleusSb = StringBuilder()
        var remIdx = 0
        while (remIdx < remainingAfterOnset.length && VietnamesePhonology.isBaseVowel(remainingAfterOnset[remIdx])) {
            nucleusSb.append(remainingAfterOnset[remIdx]); remIdx++
        }
        val nucleus = nucleusSb.toString()
        val remainingAfterNucleus = remainingAfterOnset.substring(remIdx)
        val remLower = remainingAfterNucleus.lowercase()

        var coda = ""
        var rawSuffix = ""
        if (nucleus.isNotEmpty()) {
            var matchedCoda = false
            for (cand in VietnamesePhonology.CODAS) {
                if (remLower.startsWith(cand)) {
                    val candidateRime = nucleus.lowercase() + cand
                    if (VietnamesePhonology.isValidPrefix(candidateRime) &&
                        VietnamesePhonology.isRimeValidForTone(candidateRime.lowercase(), detectedTone)) {
                        coda = remainingAfterNucleus.substring(0, cand.length)
                        rawSuffix = remainingAfterNucleus.substring(cand.length)
                        matchedCoda = true; break
                    }
                }
            }
            if (!matchedCoda) rawSuffix = remainingAfterNucleus
        } else {
            rawSuffix = remainingAfterNucleus
        }

        val hasValidRime = nucleus.isNotEmpty() && VietnamesePhonology.isValidRime(nucleus.lowercase() + coda.lowercase())
        val validTone = if (hasValidRime) detectedTone else Tone.NONE
        val validSuffix = if (hasValidRime) rawSuffix else (if (detectedTone != Tone.NONE) word.substring(onset.length) else rawSuffix)

        val rimeKey = nucleus.lowercase() + coda.lowercase()
        val isValidRimeOrPrefix = if (nucleus.isEmpty()) {
            onset.isNotEmpty() && coda.isEmpty()
        } else {
            VietnamesePhonology.isValidPrefix(rimeKey) && VietnamesePhonology.isRimeValidForTone(rimeKey, validTone)
        }
        val isValid = validSuffix.isEmpty() && isValidRimeOrPrefix

        val canonicalRaw = if (isValid) {
            val sb = StringBuilder()
            when (onset.lowercase()) {
                "đ" -> sb.append(if (onset == "Đ") "DD" else if (onset[0].isUpperCase()) "Dd" else "dd")
                else -> sb.append(onset)
            }
            sb.append(nucleusToRawKeystroke(nucleus))
            val nucAllUpper = nucleus.isNotEmpty() && nucleus.all { it.isUpperCase() }
            sb.append(coda)
            val toneKey = when (validTone) {
                Tone.ACUTE -> 's'; Tone.GRAVE -> 'f'; Tone.HOOK -> 'r'
                Tone.TILDE -> 'x'; Tone.DOT -> 'j'; Tone.NONE -> null
            }
            if (toneKey != null) sb.append(if (nucAllUpper) toneKey.uppercaseChar() else toneKey)
            VietnameseUnicode.applyCasingFromRaw(sb.toString(), word)
        } else { word }

        return AdoptResult(isValid, onset.length, canonicalRaw)
    }

    private fun extractToneFromChar(c: Char): Tone {
        return when (c.lowercaseChar()) {
            'á', 'ắ', 'ấ', 'é', 'ế', 'í', 'ó', 'ố', 'ớ', 'ú', 'ứ', 'ý' -> Tone.ACUTE
            'à', 'ằ', 'ầ', 'è', 'ề', 'ì', 'ò', 'ồ', 'ờ', 'ù', 'ừ', 'ỳ' -> Tone.GRAVE
            'ả', 'ẳ', 'ẩ', 'ẻ', 'ể', 'ỉ', 'ỏ', 'ổ', 'ở', 'ủ', 'ử', 'ỷ' -> Tone.HOOK
            'ã', 'ẵ', 'ẫ', 'ẽ', 'ễ', 'ĩ', 'õ', 'ỗ', 'ỡ', 'ũ', 'ữ', 'ỹ' -> Tone.TILDE
            'ạ', 'ặ', 'ậ', 'ẹ', 'ệ', 'ị', 'ọ', 'ộ', 'ợ', 'ụ', 'ự', 'ỵ' -> Tone.DOT
            else -> Tone.NONE
        }
    }

    // ================================================================
    // PUBLIC: process / processString
    // ================================================================

    fun process(raw: String): String = processString(raw)

    fun processString(raw: String): String {
        if (raw.isEmpty()) return ""
        compileRaw(raw, true, stringOut)
        return stringOut.toStringVal()
    }

    // ================================================================
    // COMPANION
    // ================================================================

    companion object {
        private val displayBuffer = ThreadLocal.withInitial { CharArray(32) }

        @JvmStatic
        fun isToneKey(c: Char): Boolean =
            VietnamesePhonology.TONE_KEYS.indexOf(c.lowercaseChar()) >= 0

        @JvmStatic
        fun isVowelModifierKey(c: Char): Boolean =
            c.lowercaseChar() in "eoaw"

        private val NUCLEUS_RAW = arrayOf(
            "ươ" to "uwo", "ưa" to "uwa", "uơ" to "uow"
        ).toMap()

        private val CHAR_RAW = IntArray(512).also { a ->
            fun p(c: Char, r0: Char, r1: Char) { a[c.code] = (r0.code shl 8) or r1.code }
            p('â', 'a', 'a'); p('Ă', 'A', 'w'); p('ă', 'a', 'w'); p('Â', 'A', 'a')
            p('ê', 'e', 'e'); p('Ê', 'E', 'e')
            p('ô', 'o', 'o'); p('Ô', 'O', 'o')
            p('ơ', 'o', 'w'); p('Ơ', 'O', 'w')
            p('ư', 'u', 'w'); p('Ư', 'U', 'w')
        }

        fun nucleusToRawKeystroke(nucleus: String): String {
            if (nucleus.isEmpty()) return ""
            val nucLower = nucleus.lowercase()
            val raw = NUCLEUS_RAW[nucLower]
            if (raw != null) {
                val allUpper = nucleus.all { it.isUpperCase() }
                val firstUpper = nucleus[0].isUpperCase()
                return when {
                    allUpper -> raw.uppercase()
                    firstUpper -> raw.replaceFirstChar { it.uppercase() }
                    else -> raw
                }
            }
            val sb = StringBuilder()
            for (c in nucleus) {
                val packed = CHAR_RAW[c.code]
                if (packed != 0) {
                    val r0 = (packed ushr 8).toChar()
                    val r1 = (packed and 0xFF).toChar()
                    sb.append(if (c.isUpperCase()) r0.uppercaseChar() else r0)
                    sb.append(r1)
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }
    }

    // ================================================================
    // PREFERENCES
    // ================================================================

    var macroStore: MacroStore? = null
    private var macroPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var settingsPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun loadPreferences(context: Context) {
        try {
            AppPreferences.init(context)
            val config = AppPreferences.getEngineConfig()
            applyConfig(config)
            macroStore = MacroRepository(context).loadMacroStore()
            if (macroPrefsListener == null) {
                macroPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (AppPreferences.isMacroDataKey(key)) reloadMacroStore(context)
                }
                AppPreferences.registerMacroPrefsListener(macroPrefsListener!!)
            }
            if (settingsPrefsListener == null) {
                settingsPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                    loadPreferences(context)
                }
                AppPreferences.registerSettingsPrefsListener(settingsPrefsListener!!)
            }
        } catch (e: Exception) {
            System.err.println("[VietnameseComposer] Failed to load preferences: ${e.message}")
        }
    }

    fun cleanup() {
        macroPrefsListener?.let { AppPreferences.unregisterMacroPrefsListener(it); macroPrefsListener = null }
        settingsPrefsListener?.let { AppPreferences.unregisterSettingsPrefsListener(it); settingsPrefsListener = null }
    }

    fun reloadMacroStore(context: Context) {
        try {
            AppPreferences.init(context)
            applyConfig(AppPreferences.getEngineConfig())
            macroStore = MacroRepository(context).loadMacroStore()
            reset()
        } catch (e: Exception) {
            System.err.println("[VietnameseComposer] Failed to reload macro store: ${e.message}")
        }
    }

    fun savePreferences(
        context: Context,
        macro: Boolean = options.macroEnabled,
        alwaysMac: Boolean = options.alwaysMacro,
        autoCap: Boolean = autoCapitalize,
        dirW: Boolean = options.directW,
        oldTone: Boolean = options.oldTonePlacement
    ) {
        try {
            AppPreferences.init(context)
            val config = EngineConfig(macroEnabled = macro, alwaysMacro = alwaysMac,
                autoCapitalize = autoCap, directW = dirW, oldTonePlacement = oldTone)
            AppPreferences.setEngineConfig(config)
            applyConfig(config)
        } catch (e: Exception) {
            System.err.println("[VietnameseComposer] Failed to save preferences: ${e.message}")
        }
    }

    private fun applyConfig(config: EngineConfig) {
        options.macroEnabled = config.macroEnabled
        options.alwaysMacro = config.alwaysMacro
        options.directW = config.directW
        options.oldTonePlacement = config.oldTonePlacement
        autoCapitalize = config.autoCapitalize
    }
}
