package com.goviet.keyboard.engine

import android.content.Context
import com.goviet.core.AppPreferences
import com.goviet.core.EngineConfig

/**
 * VietnameseComposer — Single-resegment Telex engine.
 *
 * All syllable segmentation is derived by the single `resegment` function.
 * No incremental mutation of syllable fields through per-keystroke handlers —
 * the controller appends each key to the raw buffer, then feedKey rederives
 * the full state from scratch on that buffer.
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

    class SyllableState(
        var onset: String = "",
        var nucleus: String = "",
        var coda: String = "",
        var tone: Tone = Tone.NONE,
        var rawSuffix: String = ""
    ) {
        fun reset() {
            onset = ""; nucleus = ""; coda = ""
            tone = Tone.NONE; rawSuffix = ""
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
            var toneIdx = RimeMap.determineTonePositionHash(
                rimeKey.toLong(), oldTonePlacement, nucleus.length)
            if (coda.isEmpty() && rawSuffix.isNotEmpty()) {
                val pending = pendingFoldCodaIndex()
                if (pending >= 0) toneIdx = pending
            }
            for (i in 0 until onset.length) out.append(onset[i])
            for (i in 0 until nucleus.length) {
                if (i == toneIdx) out.append(VietnameseUnicode.applyTone(nucleus[i], tone))
                else out.append(nucleus[i])
            }
            for (i in 0 until coda.length) out.append(coda[i])
            for (i in 0 until rawSuffix.length) out.append(rawSuffix[i])
        }

        /**
         * When a tone is set but the following consonant was rejected as a coda
         * (the current nucleus cannot host it — e.g. "ua" + "n"), the tone mark
         * stays on the first vowel even though the pending fold would move it.
         * If a Telex fold key could turn this tail into a valid coda (ua + n ->
         * uâ + n via 'a'), anchor the mark on the last nucleus vowel instead:
         * churan -> chuản, then churana -> chuẩn.
         */
        private fun pendingFoldCodaIndex(): Int {
            if (nucleus.isEmpty() || coda.isNotEmpty() || rawSuffix.isEmpty()) return -1
            val c0 = rawSuffix[0].lowercaseChar()
            val codaStart = c0 == 'm' || c0 == 'p' || c0 == 'n' || c0 == 't' || c0 == 'c'
            if (!codaStart) return -1
            val slot = RimeMap.foldSlot(RimeMap.rimeKey(nucleus))
            if (slot < 0) return -1
            val folds = intArrayOf(
                RimeMap.foldE(slot), RimeMap.foldO(slot),
                RimeMap.foldA(slot), RimeMap.foldWPrimary(slot)
            )
            for (fold in folds) {
                if (fold == 0) continue
                val folded = RimeMap.applyFold(nucleus, fold)
                if (folded == nucleus) continue
                val rk = RimeMap.keyCat(folded, folded.length, rawSuffix, 1)
                if (RimeMap.isValidPrefix(rk)) return (nucleus.length - 1).coerceAtLeast(0)
            }
            return -1
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
        var i = from
        var tailStart = -1
        var tailEnd = -1
        while (i < raw.length) {
            val c = raw[i].lowercaseChar()
            // Skip tone keys when predicting coda: in Telex the tone letter
            // (s/f/r/x/j/z) precedes the coda consonants in the raw stream
            // (e.g. "thuowrng" → tone r before coda ng).  Without this skip
            // the lookahead stops at the tone key and misses the coda, causing
            // w-fold to pick the open form "uơ" instead of the closed "ươ".
            if (isToneKey(c)) { i++; continue }
            if (isFoldKey(c)) { i++; continue }
            if (isConsonant(c)) {
                if (tailStart < 0) tailStart = i
                tailEnd = i + 1
                i++
            } else break
        }
        return if (tailStart >= 0) raw.subSequence(tailStart, tailEnd).toString() else ""
    }

    /**
     * Collect vowels immediately after [from] that would extend the nucleus
     * (plain vowels a/e/i/o/u/y + horned â/ô/ơ/ư). Used by the dual-variant
     * w-fold to validate the fold result against the nucleus extension that
     * will follow, so the correct variant is chosen even when both are valid
     * standalone.
     */
    private fun predictVowelTail(raw: CharSequence, from: Int): String {
        val sb = StringBuilder()
        var i = from
        while (i < raw.length) {
            val c = raw[i].lowercaseChar()
            if (RimeMap.isBaseVowel(c)) { sb.append(c); i++ }
            else break
        }
        return sb.toString()
    }

    private fun isFoldKey(c: Char): Boolean = RimeMap.isFoldKey(c)

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
                if (onsetLen == 1 && RimeMap.isBaseVowel(raw[0])) continue
                // When directW is OFF, 'w' at syllable start should fold, not onset
                if (!options.directW && onsetLen == 1 && raw[0].lowercaseChar() == 'w') continue
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
                            if (RimeMap.isBaseVowel(raw[k])) { vowelAfter = true; break }
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

        // ── Incremental key caches ─────────────────────────────────
        // Packed flat-map keys of onset / nucleus / rime, kept in sync with every
        // mutation.  Appending one char extends the key in O(1) instead of
        // re-encoding the whole substring; keys are recomputed only when the
        // nucleus or onset is replaced (fold, untoggle, vowel combination,
        // deferred fold).  Each keystroke therefore stays at a single table
        // probe on the hot path and never re-encodes the nucleus.
        var oKey = if (onsetEnd > 0) OnsetMap.onsetKeyOf(out.onset) else 0
        var nucKey = 0
        var rimeKey = 0

        // ── Main loop: tone, fold, vowel & coda processing ──────────
        var lastFoldKey = '\u0000'   // fold key that last modified nucleus
        var lastFoldNucIdx = -1        // nucleus index where fold was applied
        var lastToneKey = '\u0000'
        var syllableLocked = false  // once any char is rejected, the rest of the syllable is literal
        var justUntoggled = false  // true after fold→untoggle, prevents immediate re-fold
        var toneLocked = false  // true after tone key rejected (not cancelled) for invalid rime
        var wOnsetAbsorbed = false  // true after the first w-absorb after onset 'w' (w+w→w)
        var standaloneWFold = false  // true when standalone 'w' creates ư from nothing (no prior nucleus)

        while (pos < len) {
            val c = raw[pos]
            val cLow = c.lowercaseChar()

            // ── Onset fold: data-driven via OnsetMap (no per-char hardcoding) ──
            // OnsetMap.foldTarget maps (onset, foldKey) -> replacement; currently
            // only d→đ exists but the mechanism is generic -- any future onset fold
            // is just a data row in OnsetMap, no composer change needed.
            // Untoggle: onset equals the fold result for this key -> revert + literal.
            if (!syllableLocked && out.onset.isNotEmpty() &&
                OnsetMap.isRegisteredFoldKey(cLow)) {
                val oFold = OnsetMap.foldTarget(oKey, cLow)
                if (oFold != 0) {
                    out.onset = OnsetMap.applyFold(out.onset, oFold)
                    oKey = OnsetMap.onsetKeyOf(out.onset)
                    pos++; continue
                }
                val ufKey = OnsetMap.foldKeyForTarget(oKey)
                if (ufKey != '\u0000' && cLow == ufKey) {
                    out.onset = OnsetMap.unfoldOnset(out.onset, ufKey)
                    oKey = OnsetMap.onsetKeyOf(out.onset)
                    out.rawSuffix += c
                    syllableLocked = true; toneLocked = true
                    pos++; continue
                }
            }

            // ── Tone handling ──────────────────────────────────────
            if (isToneKey(cLow)) {
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
                    // z (clear-tone) with no existing tone → literal z, not consumed.
                    if (targetTone == Tone.NONE && out.tone == Tone.NONE) {
                        out.rawSuffix += c; syllableLocked = true; toneLocked = true
                        pos++; continue
                    }
                    // z with existing tone → clear it.  Do NOT lock syllable or
                    // tone so the user can immediately re-apply a different tone.
                    if (targetTone == Tone.NONE && out.tone != Tone.NONE) {
                        out.tone = Tone.NONE
                        lastToneKey = '\u0000'
                        pos++; continue
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
                    val rk = rimeKey
                    if (RimeMap.isRimeHashValidForTone(rk.toLong(), targetTone)) {
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
                // 'w' after a uo-family w-compound (uơ/ươ): the map says the fold
                // cannot untoggle, so the repeated key either becomes literal text
                // when it sits right after the fold key (uoww → uơw) or is
                // absorbed when the compound was rebuilt through other keys
                // (uwow → ươ).  The ua/oa-family compounds (ưa/oă) carry no such
                // flag and untoggle normally (huawwei → huawei).
                if (cLow == 'w' && !syllableLocked && lastFoldKey == 'w' &&
                    out.nucleus.isNotEmpty() &&
                    RimeMap.foldWRepeatLiteral(RimeMap.foldSlot(nucKey))) {
                    if (pos > 0 && raw[pos - 1].lowercaseChar() == 'w') {
                        out.rawSuffix += c
                        syllableLocked = true; toneLocked = true
                    }
                    lastFoldKey = '\u0000'
                    pos++; continue
                }
                // Absorb: second 'w' after onset 'w' → discard (w+w → w)
                // Only when directW=OFF: w is a fold key here. With directW=ON,
                // w is a literal onset consonant, so second w is also literal.
                if (!options.directW && cLow == 'w' && !syllableLocked &&
                    !wOnsetAbsorbed && out.nucleus.isEmpty() &&
                    out.onset.isNotEmpty() && out.onset[0].lowercaseChar() == 'w') {
                    wOnsetAbsorbed = true
                    pos++; continue
                }
                // w → ư when there is no nucleus yet (standalone "w" or after a
                // consonant onset: sw→sư, dw→dư, lw→lư).  Onset 'w' is excluded —
                // its repeated w is absorbed by the branch above.  The ư was created
                // from nothing, so another 'w' cancels it back to raw "tw".
                // The fold is accepted only when the onset+ư combo lives in the
                // valid-syllable prefix map — qu+ư does not (quw stays literal quw).
                if (!options.directW && cLow == 'w' && !syllableLocked &&
                    out.nucleus.isEmpty() &&
                    (out.onset.isEmpty() || out.onset[0].lowercaseChar() != 'w')) {
                    val wChar = if (c.isUpperCase()) 'Ư' else 'ư'
                    val comboOk = out.onset.isEmpty() ||
                        TokenValidMap.isDisplayPrefixValid((out.onset + wChar).lowercase())
                    if (comboOk) {
                        out.nucleus = wChar.toString()
                        nucKey = RimeMap.rimeKey(out.nucleus)
                        rimeKey = nucKey
                        lastFoldKey = 'w'; lastFoldNucIdx = 0
                        standaloneWFold = true
                        pos++; continue
                    }
                }

                // Vowel modifier: untoggle FIRST, then fold rules.
                // Per A7: pressing the same fold key again on the same position
                // untoggles and releases the key as literal. This must be checked
                // BEFORE fold rules to prevent a second fold (e.g. ơ→o) from
                // firing instead of the untoggle (ơ→o + release w).
                if (!syllableLocked && isFoldKey(cLow) && out.nucleus.isNotEmpty() && !justUntoggled) {
                    if (lastFoldKey != '\u0000' && cLow == lastFoldKey &&
                        lastFoldNucIdx >= 0 && lastFoldNucIdx < out.nucleus.length &&
                        out.nucleus[lastFoldNucIdx] != RimeMap.plainOf(out.nucleus[lastFoldNucIdx])) {
                        if (standaloneWFold) {
                            out.nucleus = ""
                            out.rawSuffix += c
                            syllableLocked = true; toneLocked = true
                        } else {
                            out.nucleus = replaceAt(out.nucleus, lastFoldNucIdx, RimeMap.plainOf(out.nucleus[lastFoldNucIdx]))
                            if (out.coda.isNotEmpty()) {
                                // Coda already present: the released fold key cannot
                                // re-join the nucleus — emit it as literal text after
                                // the valid syllable (banaan -> bana + n = banan).
                                out.rawSuffix += c
                                syllableLocked = true; toneLocked = true
                            } else {
                                out.nucleus += c
                            }
                        }
                        lastFoldKey = '\u0000'; lastFoldNucIdx = -1
                        nucKey = if (out.nucleus.isEmpty()) 0 else RimeMap.rimeKey(out.nucleus)
                        rimeKey = nucKey
                        standaloneWFold = false
                        justUntoggled = true
                        pos++; continue
                    }
                    val foldIdx = applyFoldRules(c, nucKey, pos, raw, out)
                    if (foldIdx >= 0) {
                        lastFoldKey = cLow; lastFoldNucIdx = foldIdx
                        nucKey = RimeMap.rimeKey(out.nucleus)
                        rimeKey = RimeMap.keyCat(out.nucleus, out.nucleus.length, out.coda, out.coda.length)
                        standaloneWFold = false
                        justUntoggled = false
                        pos++; continue
                    }
                }
                // Vowel combination
                if (!syllableLocked && out.nucleus.isNotEmpty() && cLow != 'w') {
                    val combo = RimeMap.combineNucleus(out.nucleus, c)
                    if (combo != null) {
                        out.nucleus = combo
                        nucKey = RimeMap.rimeKey(out.nucleus)
                        rimeKey = RimeMap.keyCat(out.nucleus, out.nucleus.length, out.coda, out.coda.length)
                        pos++; continue
                    }
                }
                // Plain vowel → extend nucleus (only while syllable is unlocked)
                if (!syllableLocked && out.coda.isEmpty()) {
                    val candidateKey = RimeMap.extendKeySingle(nucKey, c)
                    if (RimeMap.isValidPrefix(candidateKey)) {
                        out.nucleus += c
                        nucKey = candidateKey
                        rimeKey = nucKey
                        lastFoldKey = '\u0000'; lastFoldNucIdx = -1
                        pos++; continue
                    }
                }
                justUntoggled = false
                out.rawSuffix += c; syllableLocked = true; toneLocked = true
                pos++; continue
            }

            // ── Base vowel (not a fold key) → extend nucleus ──────
            if (!syllableLocked && !isConsonant(cLow) && RimeMap.isBaseVowel(c)) {
                if (out.nucleus.isEmpty()) {
                    // First vowel: start nucleus
                    out.nucleus = c.toString()
                    nucKey = RimeMap.rimeKey(out.nucleus)
                    rimeKey = nucKey
                    justUntoggled = false
                    pos++; continue
                }
                if (out.coda.isEmpty()) {
                    val candidateKey = RimeMap.extendKeySingle(nucKey, c)
                    if (RimeMap.isValidPrefix(candidateKey)) {
                        out.nucleus += c
                        nucKey = candidateKey
                        rimeKey = nucKey
                        pos++; continue
                    }
                }
            }

            // ── Consonant: try as coda, otherwise literal + lock ───
            if (!syllableLocked && isConsonant(cLow) && out.nucleus.isNotEmpty()) {
                // Try as coda — RimeMap is the authority (O(1) flatmap lookup)
                val codaLen = out.coda.length
                val codaOk = codaLen < 2
                if (codaOk) {
                    val rk = RimeMap.extendKeySingle(rimeKey, c)
                    if (RimeMap.isValidPrefixWithTone(rk, out.tone.index)) {
                        out.coda += c
                        rimeKey = rk
                        pos++; continue
                    }
                    // Deferred fold lookahead: if the next char in raw is a fold key
                    // that transforms the nucleus to accept this coda, pre-apply the fold
                    // and consume the fold key (tuana → tuân: n after "ua", 'a' folds→uâ).
                    if (out.coda.isEmpty() && pos + 1 < len) {
                        val nextChar = raw[pos + 1].lowercaseChar()
                        if (nextChar in FOLD_KEYS) {
                            val fold = foldPrimaryForSlot(RimeMap.foldSlot(nucKey), nextChar)
                            if (fold != 0) {
                                val foldedNuc = RimeMap.applyFold(out.nucleus, fold)
                                if (foldedNuc != out.nucleus) {
                                    val deferredRk = RimeMap.keyCat(foldedNuc, foldedNuc.length, c)
                                    if (RimeMap.isValidPrefixWithTone(deferredRk, out.tone.index)) {
                                        out.nucleus = foldedNuc
                                        nucKey = RimeMap.rimeKey(out.nucleus)
                                        out.coda += c
                                        rimeKey = RimeMap.extendKeySingle(nucKey, c)
                                        pos += 2  // skip coda char + fold key
                                        continue
                                    }
                                }
                            }
                        }
                        // Deferred fold after a tone key: the coda is rejected now but
                        // becomes valid once a later fold key transforms the nucleus and
                        // the tone key in between applies to the completed rime
                        // (chuanra → chuẩn: ua+n, tone r, fold a → uâ+n).
                        if (!toneLocked && pos + 2 < len && isToneKey(raw[pos + 1].lowercaseChar())) {
                            val toneKey = raw[pos + 1].lowercaseChar()
                            val foldKey = raw[pos + 2].lowercaseChar()
                            if (foldKey in FOLD_KEYS) {
                                val targetTone = Tone.fromKey(toneKey)
                                if (targetTone != null && targetTone != Tone.NONE) {
                                    val fold = foldPrimaryForSlot(RimeMap.foldSlot(nucKey), foldKey)
                                    if (fold != 0) {
                                        val foldedNuc = RimeMap.applyFold(out.nucleus, fold)
                                        if (foldedNuc != out.nucleus) {
                                            val deferredRk = RimeMap.keyCat(foldedNuc, foldedNuc.length, c)
                                            if (RimeMap.isValidPrefix(deferredRk) &&
                                                RimeMap.isToneAllowed(deferredRk, targetTone.index)) {
                                                out.nucleus = foldedNuc
                                                nucKey = RimeMap.rimeKey(out.nucleus)
                                                out.coda += c
                                                rimeKey = RimeMap.extendKeySingle(nucKey, c)
                                                out.tone = targetTone
                                                lastToneKey = toneKey
                                                pos += 3  // skip coda char + tone key + fold key
                                                continue
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // Consonants never extend the nucleus (nucleus is vowels-only).
                // Not a valid coda → literal + hard lock.
                out.rawSuffix += c; syllableLocked = true; toneLocked = true; pos++; continue
            }

            // ── Any other char → rawSuffix ────────────────────────
            out.rawSuffix += c; syllableLocked = true; toneLocked = true; pos++
        }

        // Prefix-freeze: never revert already-composed Vietnamese back to raw
        // text.  When rawSuffix collects genuine consonants, the syllable is
        // simply locked; subsequent keys append literally on top of the
        // composed output (invalid input becomes literal without destroying
        // valid output).

    }

    /** Primary fold code for [foldKey] on the nucleus slot obtained via [RimeMap.foldSlot]; 0 = none. */
    private fun foldPrimaryForSlot(slot: Int, foldKey: Char): Int = when (foldKey.lowercaseChar()) {
        'e' -> RimeMap.foldE(slot)
        'o' -> RimeMap.foldO(slot)
        'a' -> RimeMap.foldA(slot)
        'w' -> RimeMap.foldWPrimary(slot)
        else -> 0
    }

    /**
     * Apply the Telex fold for [c] by reading the fold-target data baked into
     * [RimeMap] for the current nucleus — no per-rule if/else, no hardcoded
     * vowel-pair comparisons.  Returns the nucleus index where the fold landed
     * (untoggle anchor), or -1 when no fold applies.
     */
    private fun applyFoldRules(c: Char, nucKey: Int, rawPos: Int, raw: CharSequence, out: SyllableState): Int {
        val nuc = out.nucleus
        val slot = RimeMap.foldSlot(nucKey)
        if (slot < 0) return -1
        val primary = foldPrimaryForSlot(slot, c)
        if (primary == 0) return -1
        val alt = if (c == 'w') RimeMap.foldWAlt(slot) else 0

        if (alt == 0) {
            // Single-target fold (a→â, e→ê, o→ô, w singles, ua→ưa, oa→oă):
            // validate against the current coda, plus the predicted tail when
            // the map says this fold is lookahead-sensitive.
            val newNuc = RimeMap.applyFold(nuc, primary)
            var ok = isValidRime(newNuc, out.coda)
            if (!ok && RimeMap.foldWPrimaryLookahead(slot)) {
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
        val primNuc = RimeMap.applyFold(nuc, primary)
        val altNuc = RimeMap.applyFold(nuc, alt)

        // Helper: pick from primNuc/altNuc given a coda, using tie-break rules.
        fun pickVariant(coda: String): String? {
            val pv = isValidRime(primNuc, coda)
            val av = isValidRime(altNuc, coda)
            if (pv != av) return if (pv) primNuc else altNuc
            if (!pv) return null
            // Both valid standalone — the vowel extension breaks the tie: the
            // right fold is the one whose compound + following vowels forms a
            // valid nucleus prefix ("ươ"+"i"="ươi" valid vs "uơ"+"i" invalid).
            val vt = predictVowelTail(raw, rawPos + 1)
            if (vt.isNotEmpty()) {
                val primKey = RimeMap.keyCat(primNuc, primNuc.length, vt, vt.length)
                val altKey = RimeMap.keyCat(altNuc, altNuc.length, vt, vt.length)
                val pe = RimeMap.isValidPrefix(RimeMap.extendKey(primKey, out.coda, 0, out.coda.length))
                val ae = RimeMap.isValidPrefix(RimeMap.extendKey(altKey, out.coda, 0, out.coda.length))
                if (pe != ae) return if (pe) primNuc else altNuc
            }
            val openUoOk = out.onset.isEmpty() || OnsetMap.allowsOpenUo(out.onset)
            return if (out.coda.isNotEmpty() || !openUoOk) primNuc else altNuc
        }

        // Try with the predicted tail first; if both invalid, fall back to
        // empty tail (the predicted consonant may not be a real coda).
        val candCoda = out.coda + tail
        var chosen = pickVariant(candCoda)
        if (chosen == null && tail.isNotEmpty()) {
            chosen = pickVariant(out.coda)
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

    private fun isConsonant(c: Char): Boolean = OnsetMap.isConsonant(c)

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
        compileRaw(raw, vietnamese, out, Int.MAX_VALUE)
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
        while (remIdx < remainingAfterOnset.length && RimeMap.isBaseVowel(remainingAfterOnset[remIdx])) {
            nucleusSb.append(remainingAfterOnset[remIdx]); remIdx++
        }
        var nucleus = nucleusSb.toString()
        var remainingAfterNucleus = remainingAfterOnset.substring(remIdx)
        var remLower = remainingAfterNucleus.lowercase()

        // If onset is "gi" but nucleus is empty, shrink to "g" so "i" becomes
        // the nucleus — mirrors the resegment logic that skips "gi" onset
        // when no vowel follows.  This fixes "gì/gí/gĩ/gỉ/gị" round-trip.
        if (nucleus.isEmpty() && onset.length > 1 && onset.last().lowercaseChar() == 'i') {
            val shorterOnset = baseWord.substring(0, onset.length - 1)
            if (OnsetMap.isCompleteOnset(shorterOnset.lowercase(), 0, shorterOnset.length)) {
                onset = shorterOnset
                remainingAfterOnset = baseWord.substring(onset.length)
                val nsb = StringBuilder()
                var ri = 0
                while (ri < remainingAfterOnset.length && RimeMap.isBaseVowel(remainingAfterOnset[ri])) {
                    nsb.append(remainingAfterOnset[ri]); ri++
                }
                nucleus = nsb.toString()
                remainingAfterNucleus = remainingAfterOnset.substring(ri)
                remLower = remainingAfterNucleus.lowercase()
            }
        }

        var coda = ""
        var rawSuffix = ""
        if (nucleus.isNotEmpty()) {
            var matchedCoda = false
            for (cand in RimeMap.CODAS) {
                if (remLower.startsWith(cand)) {
                    val candidateRime = nucleus.lowercase() + cand
                    if (RimeMap.isValidPrefix(RimeMap.rimeKey(candidateRime)) &&
                        RimeMap.isRimeValidForTone(candidateRime.lowercase(), detectedTone)) {
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

        val hasValidRime = nucleus.isNotEmpty() && RimeMap.isValidPrefix(RimeMap.rimeKey(nucleus.lowercase() + coda.lowercase()))
        val validTone = if (hasValidRime) detectedTone else Tone.NONE
        val validSuffix = if (hasValidRime) rawSuffix else (if (detectedTone != Tone.NONE) word.substring(onset.length) else rawSuffix)

        val rimeKey = nucleus.lowercase() + coda.lowercase()
        val isValidRimeOrPrefix = if (nucleus.isEmpty()) {
            onset.isNotEmpty() && coda.isEmpty()
        } else {
            RimeMap.isValidPrefix(RimeMap.rimeKey(rimeKey)) && RimeMap.isRimeValidForTone(rimeKey, validTone)
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
        fun isToneKey(c: Char): Boolean = RimeMap.isToneKey(c)

        @JvmStatic
        fun isVowelModifierKey(c: Char): Boolean = RimeMap.isFoldKey(c)

        fun nucleusToRawKeystroke(nucleus: String): String {
            if (nucleus.isEmpty()) return ""
            val raw = RimeMap.rawKeyForNucleus(nucleus)
            val allUpper = nucleus.all { it.isUpperCase() }
            val firstUpper = nucleus.isNotEmpty() && nucleus[0].isUpperCase()
            return when {
                allUpper -> raw.uppercase()
                firstUpper -> raw.replaceFirstChar { it.uppercase() }
                else -> raw
            }
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
