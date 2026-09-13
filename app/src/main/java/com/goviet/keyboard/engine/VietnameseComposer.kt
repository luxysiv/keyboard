package com.goviet.keyboard.engine

import android.content.Context
import com.goviet.core.AppPreferences
import com.goviet.core.EngineConfig

/**
 * VietnameseComposer — Single-resegment Telex engine.
 *
 * All syllable segmentation is derived by the single `resegment` function.
 * The instance owns the composing preedit buffer and derives the display from
 * it through the session API below; the IME controller never keeps a second
 * copy of the composing state.
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

    // ── Data types ─────────────────────────────────────────────────

    class SyllableState(
        var onset: String = "",
        var nucleus: String = "",
        var coda: String = "",
        var tone: Tone = Tone.NONE,
        var rawSuffix: String = ""
    ) {
        /** Per-state render scratch — avoids allocating an OwnedBuffer per display. */
        private val displayScratch = OwnedBuffer()

        fun reset() {
            onset = ""; nucleus = ""; coda = ""
            tone = Tone.NONE; rawSuffix = ""
        }
        fun isEmpty(): Boolean = onset.isEmpty() && nucleus.isEmpty() && coda.isEmpty() && rawSuffix.isEmpty()

        fun toDisplayString(oldTonePlacement: Boolean = false): String {
            toDisplayBuffer(displayScratch, oldTonePlacement)
            return displayScratch.toStringVal()
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
            var toneIdx = RimeMap.determineTonePosition(
                rimeKey, oldTonePlacement, nucleus.length)
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
            if (c0 != 'm' && c0 != 'p' && c0 != 'n' && c0 != 't' && c0 != 'c') return -1
            val key = RimeMap.rimeKey(nucleus)
            for (fk in charArrayOf('e', 'o', 'a', 'w')) {
                if (RimeMap.foldCodaValid(nucleus, key, fk, c0) != null) {
                    return (nucleus.length - 1).coerceAtLeast(0)
                }
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
        abstract val text: CharSequence
        data class Update(override val text: CharSequence) : CompositionResult()
        data class CommitAndStartNew(val commitText: String, val newChar: Char) : CompositionResult() {
            override val text: CharSequence get() = commitText
        }
    }

    // ── Shared pools ───────────────────────────────────────────────
    private val replayState = SyllableState()
    private val stringOut = OwnedBuffer()

    /** Test API: internal buffer + state for processKey. */
    private val processRaw = StringBuilder()
    private val processState = SyllableState()

    fun reset() { replayState.reset(); processRaw.clear(); processState.reset(); isVietnamese = true }
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
            if (RimeMap.isFoldKey(c)) { i++; continue }
            if (OnsetMap.isConsonant(c)) {
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

    /**
     * Pipeline entry — single source of truth for syllable composition.
     *
     * Phase 1 [matchOnset] consumes the longest valid consonant prefix.
     * Phase 2 [scanBody] walks the remaining raw keys left-to-right, dispatching
     * each character to the appropriate handler (tone / modifier / vowel /
     * coda) and applying folds from the map data.  Phase 3 renders [SyllableState]
     * to display text (see [SyllableState.toDisplayString]).
     *
     * No incremental mutation survives between keystrokes: the controller appends
     * to the raw buffer and calls resegment again, so every state is derived.
     */
    private fun resegment(raw: CharSequence, out: SyllableState) {
        out.reset()
        if (raw.isEmpty()) return
        matchOnset(raw, out)
        scanBody(raw, out, ScanCtx(if (out.onset.isEmpty()) 0 else OnsetMap.onsetKeyOf(out.onset)))
    }

    /**
     * Fold anchor — the single record of the last Telex fold that touched the
     * nucleus.  Untoggle compares the next key against it; it replaces the old
     * loose fold-key/nucleus-index/raw-position/standalone fields.
     */
    private class FoldAnchor(
        var key: Char = '\u0000',    // fold key that last modified the nucleus
        var nucIdx: Int = -1,          // nucleus index where the fold landed
        var rawPos: Int = -1,          // raw position where the fold was applied
        var standalone: Boolean = false // a lone w created ư from nothing
    ) {
        val active: Boolean get() = key != '\u0000'
        fun set(key: Char, nucIdx: Int, rawPos: Int, standalone: Boolean = false) {
            this.key = key; this.nucIdx = nucIdx; this.rawPos = rawPos; this.standalone = standalone
        }
        fun clear() { key = '\u0000'; nucIdx = -1; rawPos = -1; standalone = false }
    }

    /** Per-call scan memory — 6 fields + fold anchor (was 12 loose fields). */
    private class ScanCtx(
        var oKey: Int,
        var nucKey: Int = 0,
        var rimeKey: Int = 0,
        var lastToneKey: Char = '\u0000',
        var syllableLocked: Boolean = false, // once a char is rejected the rest is literal
        var justUntoggled: Boolean = false,  // prevents immediate re-fold after untoggle
        var fold: FoldAnchor = FoldAnchor()  // last nucleus fold (untoggle anchor)
    )

    /**
     * Phase 1 — longest valid onset prefix.  Single vowels are never onsets; with
     * directW off a leading 'w' folds instead; "gi" only wins as an onset when a
     * vowel follows (otherwise its 'i' becomes the nucleus: gif → g + i + f).
     */
    private fun matchOnset(raw: CharSequence, out: SyllableState) {
        val len = raw.length
        val maxOnset = minOf(3, len)
        var onsetEnd = 0
        for (onsetLen in maxOnset downTo 1) {
            if (OnsetMap.isValidOnset(raw, 0, onsetLen)) {
                if (onsetLen == 1 && RimeMap.isBaseVowel(raw[0])) continue
                if (!options.directW && onsetLen == 1 && raw[0].lowercaseChar() == 'w') continue
                if (onsetLen > 1) {
                    // Only "gi" (ending in 'i') is ambiguous — there is no standalone 'q' onset.
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
        if (onsetEnd > 0) out.onset = raw.subSequence(0, onsetEnd).toString()
    }

    /**
     * Phase 2 — walk the body keys, dispatching by category.  Each handler owns
     * exactly one branch of the old loop; `ScanCtx` carries the scan memory.
     */
    private fun scanBody(raw: CharSequence, out: SyllableState, ctx: ScanCtx) {
        var pos = matchLen(out)
        val len = raw.length
        while (pos < len) {
            val c = raw[pos]
            val cLow = c.lowercaseChar()

            // 1. Onset fold (d→đ, data-driven via OnsetMap) + untoggle.
            if (tryOnsetFold(c, cLow, out, ctx)) { pos++; continue }

            // 2. Tone key (s/f/r/x/j/z).
            if (RimeMap.isToneKey(cLow)) {
                handleToneKey(c, cLow, out, ctx)
                pos++; continue
            }

            // 3. Vowel modifier / fold key (e/o/a/w) — 'w' has its own handler
            //    so every w-special rule lives in exactly one place.
            if (cLow == 'e' || cLow == 'o' || cLow == 'a' || cLow == 'w') {
                pos = if (cLow == 'w') handleWKey(raw, c, pos, out, ctx)
                      else applyModifierFold(raw, c, cLow, pos, out, ctx)
                continue
            }

            // 4. Plain vowel → start or extend the nucleus.
            if (!ctx.syllableLocked && !OnsetMap.isConsonant(cLow) && RimeMap.isBaseVowel(c)) {
                if (tryPlainVowel(c, out, ctx)) { pos++; continue }
            }

            // 5. Consonant → coda (or deferred fold lookahead, or literal).
            if (!ctx.syllableLocked && OnsetMap.isConsonant(cLow) && out.nucleus.isNotEmpty()) {
                val consumed = tryCoda(raw, c, cLow, pos, len, out, ctx)
                if (consumed > 0) { pos += consumed; continue }
            }

            // 6. Any other char / rejected → literal + hard lock.
            out.rawSuffix += c; ctx.syllableLocked = true; pos++
        }
    }

    private fun matchLen(out: SyllableState): Int = out.onset.length

    /** Onset fold handler — returns true when the key was consumed by d→đ or untoggle. */
    private fun tryOnsetFold(c: Char, cLow: Char, out: SyllableState, ctx: ScanCtx): Boolean {
        if (ctx.syllableLocked || out.onset.isEmpty() || !OnsetMap.isRegisteredFoldKey(cLow)) return false
        val oFold = OnsetMap.foldTarget(ctx.oKey, cLow)
        if (oFold != 0) {
            out.onset = OnsetMap.applyFold(out.onset, oFold)
            ctx.oKey = OnsetMap.onsetKeyOf(out.onset)
            return true
        }
        val ufKey = OnsetMap.foldKeyForTarget(ctx.oKey)
        if (ufKey != '\u0000' && cLow == ufKey) {
            out.onset = OnsetMap.unfoldOnset(out.onset, ufKey)
            ctx.oKey = OnsetMap.onsetKeyOf(out.onset)
            out.rawSuffix += c
            ctx.syllableLocked = true
            return true
        }
        return false
    }

    /** Tone handler — applies, clears, or untoggles the tone; locks on rejection. */
    private fun handleToneKey(c: Char, cLow: Char, out: SyllableState, ctx: ScanCtx) {
        if (ctx.syllableLocked) { out.rawSuffix += c; return }
        val targetTone = Tone.fromKey(cLow)
        if (targetTone != null && out.nucleus.isNotEmpty()) {
            if (out.nucleus.length >= 2) {
                val n0 = out.nucleus[0].lowercaseChar()
                val n1 = out.nucleus[1].lowercaseChar()
                if ((n0 == 'a' && n1 == 'a') || (n0 == 'e' && n1 == 'e')) {
                    out.rawSuffix += c; ctx.syllableLocked = true; return
                }
            }
            if (targetTone == Tone.NONE && out.tone == Tone.NONE) {
                out.rawSuffix += c; ctx.syllableLocked = true
                return
            }
            if (targetTone == Tone.NONE && out.tone != Tone.NONE) {
                out.tone = Tone.NONE
                ctx.lastToneKey = '\u0000'
                return
            }
            if (ctx.lastToneKey != '\u0000' && cLow == ctx.lastToneKey) {
                if (out.tone != Tone.NONE) {
                    out.tone = Tone.NONE
                    out.rawSuffix += c
                    ctx.lastToneKey = '\u0000'
                } else {
                    out.rawSuffix += c
                }
                ctx.syllableLocked = true
                return
            }
            val rk = ctx.rimeKey
            if (RimeMap.isRimeKeyValidForTone(rk, targetTone)) {
                out.tone = targetTone
                ctx.lastToneKey = cLow
            } else {
                out.rawSuffix += c
                ctx.syllableLocked = true
            }
            return
        }
        out.rawSuffix += c; ctx.syllableLocked = true
    }

    /**
     * 'w'-key handler — every w-special rule lives here (uo-family repeat-literal,
     * standalone w → ư when directW is off); everything else falls through to the
     * shared fold path so aw→ă, ow→ơ, uw→ư keep working in both directW modes.
     */
    private fun handleWKey(raw: CharSequence, c: Char, pos: Int, out: SyllableState, ctx: ScanCtx): Int {
        // 'w' after a uo-family w-compound (uơ/ươ): the map says the fold cannot
        // untoggle, so the repeated key is literal right after the fold key
        // (uoww → uơw); the ua/oa-family (ưa/oă) untoggle normally below
        // (huawwei → huawei).
        if (!ctx.syllableLocked && ctx.fold.key == 'w' && out.nucleus.isNotEmpty() &&
            RimeMap.foldWRepeatLiteral(RimeMap.foldSlot(ctx.nucKey))) {
            if (pos > 0 && raw[pos - 1].lowercaseChar() == 'w') {
                out.rawSuffix += c
                ctx.syllableLocked = true
            }
            ctx.fold.clear()
            return pos + 1
        }
        // Standalone 'w' → ư (sw → sư), only when directW is off.  qu+ư is
        // rejected by the syllable-prefix map (quw stays literal quw).  A second
        // 'w' untoggles it through the shared fold path below.
        if (!options.directW && !ctx.syllableLocked && out.nucleus.isEmpty() &&
            (out.onset.isEmpty() || out.onset[0].lowercaseChar() != 'w')) {
            val wChar = if (c.isUpperCase()) 'Ư' else 'ư'
            val comboOk = out.onset.isEmpty() ||
                RimeMap.isSyllableDisplayPrefixValid((out.onset + wChar).lowercase())
            if (comboOk) {
                out.nucleus = wChar.toString()
                ctx.nucKey = RimeMap.rimeKey(out.nucleus)
                ctx.rimeKey = ctx.nucKey
                ctx.fold.set('w', 0, pos, standalone = true)
                return pos + 1
            }
        }
        return applyModifierFold(raw, c, 'w', pos, out, ctx)
    }

    /**
     * Shared modifier machinery — untoggle-first fold, vowel combination, plain
     * extend, literal fallback.  Always consumes the key (returns new pos).
     */
    private fun applyModifierFold(raw: CharSequence, c: Char, cLow: Char, pos: Int, out: SyllableState, ctx: ScanCtx): Int {
        // Untoggle FIRST: pressing the same fold key again on the same position
        // releases the fold as literal (not a second fold: ơ→o + release w).
        if (!ctx.syllableLocked && RimeMap.isFoldKey(cLow) && out.nucleus.isNotEmpty() && !ctx.justUntoggled) {
            if (ctx.fold.active && cLow == ctx.fold.key &&
                pos == ctx.fold.rawPos + 1 &&
                ctx.fold.nucIdx >= 0 && ctx.fold.nucIdx < out.nucleus.length &&
                out.nucleus[ctx.fold.nucIdx] != RimeMap.plainOf(out.nucleus[ctx.fold.nucIdx])) {
                if (ctx.fold.standalone) {
                    out.nucleus = ""
                    out.rawSuffix += c
                    ctx.syllableLocked = true
                } else {
                    val sb = StringBuilder(out.nucleus)
                    sb[ctx.fold.nucIdx] = RimeMap.plainOf(out.nucleus[ctx.fold.nucIdx])
                    out.nucleus = sb.toString()
                    if (out.coda.isNotEmpty()) {
                        // Coda present: the released fold key cannot re-join the
                        // nucleus — it becomes literal text (banaan → banan).
                        out.rawSuffix += c
                        ctx.syllableLocked = true
                    } else {
                        out.nucleus += c
                    }
                }
                ctx.fold.clear()
                ctx.nucKey = if (out.nucleus.isEmpty()) 0 else RimeMap.rimeKey(out.nucleus)
                ctx.rimeKey = ctx.nucKey
                ctx.justUntoggled = true
                return pos + 1
            }
            val foldIdx = applyFoldRules(c, ctx.nucKey, pos, raw, out)
            if (foldIdx >= 0) {
                ctx.fold.set(cLow, foldIdx, pos)
                ctx.nucKey = RimeMap.rimeKey(out.nucleus)
                ctx.rimeKey = RimeMap.keyCat(out.nucleus, out.nucleus.length, out.coda, out.coda.length)
                ctx.justUntoggled = false
                return pos + 1
            }
        }
        // Vowel combination (ua + o → uô etc., from the map).
        if (!ctx.syllableLocked && out.nucleus.isNotEmpty() && cLow != 'w') {
            val combo = RimeMap.combineNucleus(out.nucleus, c)
            if (combo != null) {
                out.nucleus = combo
                ctx.nucKey = RimeMap.rimeKey(out.nucleus)
                ctx.rimeKey = RimeMap.keyCat(out.nucleus, out.nucleus.length, out.coda, out.coda.length)
                return pos + 1
            }
        }
        // Plain vowel → extend the nucleus while syllable is unlocked.
        if (!ctx.syllableLocked && out.coda.isEmpty()) {
            val candidateKey = RimeMap.extendKeySingle(ctx.nucKey, c)
            if (RimeMap.isValidPrefix(candidateKey)) {
                out.nucleus += c
                ctx.nucKey = candidateKey
                ctx.rimeKey = ctx.nucKey
                ctx.fold.clear()
                return pos + 1
            }
        }
        ctx.justUntoggled = false
        out.rawSuffix += c; ctx.syllableLocked = true
        return pos + 1
    }

    /** Plain vowel → start a nucleus or extend it; false falls through to literal. */
    private fun tryPlainVowel(c: Char, out: SyllableState, ctx: ScanCtx): Boolean {
        if (out.nucleus.isEmpty()) {
            out.nucleus = c.toString()
            ctx.nucKey = RimeMap.rimeKey(out.nucleus)
            ctx.rimeKey = ctx.nucKey
            ctx.justUntoggled = false
            return true
        }
        if (out.coda.isEmpty()) {
            val candidateKey = RimeMap.extendKeySingle(ctx.nucKey, c)
            if (RimeMap.isValidPrefix(candidateKey)) {
                out.nucleus += c
                ctx.nucKey = candidateKey
                ctx.rimeKey = ctx.nucKey
                return true
            }
        }
        return false
    }

    /**
     * Consonant → coda via the flat map; on rejection, deferred-fold lookahead may
     * pre-apply a fold that makes this coda valid (tuana → tuân, chuanra → chuẩn).
     * Returns chars consumed (1 literal / coda, 2 fold lookahead, 3 tone+fold), or
     * 0 when the caller's guard didn't match (rare — handled by literal fallback).
     */
    private fun tryCoda(raw: CharSequence, c: Char, cLow: Char, pos: Int, len: Int, out: SyllableState, ctx: ScanCtx): Int {
        val codaLen = out.coda.length
        val codaOk = codaLen < 2
        if (codaOk) {
            val rk = RimeMap.extendKeySingle(ctx.rimeKey, c)
            if (RimeMap.isValidPrefixWithTone(rk, out.tone.index)) {
                out.coda += c
                ctx.rimeKey = rk
                return 1
            }
            if (out.coda.isEmpty() && pos + 1 < len) {
                val nextChar = raw[pos + 1].lowercaseChar()
                if (RimeMap.isFoldKey(nextChar)) {
                    val foldedNuc = RimeMap.foldCodaValid(out.nucleus, ctx.nucKey, nextChar, c, out.tone.index)
                    if (foldedNuc != null) {
                        out.nucleus = foldedNuc
                        ctx.nucKey = RimeMap.rimeKey(out.nucleus)
                        ctx.fold.key = nextChar
                        ctx.fold.rawPos = pos + 1
                        out.coda += c
                        ctx.rimeKey = RimeMap.extendKeySingle(ctx.nucKey, c)
                        return 2  // skip coda char + fold key
                    }
                }
                // Deferred fold after a tone key: coda is rejected now but valid once
                // a later fold key transforms the nucleus (chuanra → chuẩn).
                if (!ctx.syllableLocked && pos + 2 < len && RimeMap.isToneKey(raw[pos + 1].lowercaseChar())) {
                    val toneKey = raw[pos + 1].lowercaseChar()
                    val foldKey = raw[pos + 2].lowercaseChar()
                    if (RimeMap.isFoldKey(foldKey)) {
                        val targetTone = Tone.fromKey(toneKey)
                        if (targetTone != null && targetTone != Tone.NONE) {
                            val foldedNuc = RimeMap.foldCodaValid(out.nucleus, ctx.nucKey, foldKey, c, targetTone.index)
                            if (foldedNuc != null) {
                                out.nucleus = foldedNuc
                                ctx.nucKey = RimeMap.rimeKey(out.nucleus)
                                ctx.fold.key = foldKey
                                ctx.fold.rawPos = pos + 2
                                out.coda += c
                                ctx.rimeKey = RimeMap.extendKeySingle(ctx.nucKey, c)
                                out.tone = targetTone
                                ctx.lastToneKey = toneKey
                                return 3  // skip coda char + tone key + fold key
                            }
                        }
                    }
                }
            }
        }
        // Not a valid coda → literal + hard lock.
        out.rawSuffix += c; ctx.syllableLocked = true
        return 1
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
        val primary = RimeMap.foldPrimaryAtSlot(slot, c)
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



    // ================================================================
    // PUBLIC API
    // ================================================================

    // ── Composing session API (single source of truth for the preedit) ──
    // The composer owns the composing buffer; the controller delegates every
    // keystroke, adoption and edit here instead of keeping a second copy.

    /** True while a preedit session has a non-empty raw buffer. */
    fun isComposing(): Boolean = processRaw.isNotEmpty()

    /** Read-only view of the composing raw keystrokes (caret mapping helpers). */
    fun composingRaw(): CharSequence = processRaw

    fun composingRawLength(): Int = processRaw.length

    /**
     * Replaces the composing raw buffer (adoption / display-level edits).
     * When [isVietnamese] is false the text is kept verbatim (word-edit literal
     * lock); otherwise it is resegmented through the Telex kernel.
     */
    fun setComposingRaw(raw: CharSequence) {
        processRaw.setLength(0)
        processRaw.append(raw)
        if (isVietnamese) {
            resegment(processRaw, processState)
        } else {
            processState.reset()
            processState.rawSuffix = raw.toString()
        }
    }

    /** Inserts one Telex key at [index] of the composing raw and resegments. */
    fun insertComposingKey(index: Int, key: Char) {
        processRaw.insert(index, key)
        if (isVietnamese) {
            resegment(processRaw, processState)
        } else {
            processState.reset()
            processState.rawSuffix = processRaw.toString()
        }
    }

    fun processKey(key: Char): CompositionResult {
        val isBoundary = key == ' ' || key == '\n' || key == '\t'
        if (isBoundary) {
            val commitText = processState.toDisplayString(options.oldTonePlacement)
            processRaw.clear()
            processState.reset()
            isVietnamese = true
            return CompositionResult.CommitAndStartNew(commitText, key)
        }
        insertComposingKey(processRaw.length, key)
        return CompositionResult.Update(processState.toDisplayString(options.oldTonePlacement))
    }

    fun backspace(): String {
        if (processRaw.isEmpty()) return ""
        val display = processState.toDisplayString(options.oldTonePlacement)
        // Gboard-style: delete one complete displayed grapheme, never one raw
        // keystroke. The surviving display is re-adopted to canonical Telex raw
        // when it round-trips exactly; otherwise it is locked as literal text so
        // the survivor can never silently re-transform (word-edit mode).
        // Same single path used by IME display edits: adoptRoundTrip +
        // setComposingRaw (resegment for Vietnamese, rawSuffix for literal).
        val start = GraphemeEditor.previousBoundary(display, display.length)
        if (start <= 0) {
            processRaw.clear(); processState.reset()
            return ""
        }
        val survivor = display.substring(0, start)
        val canonical = adoptRoundTrip(survivor)
        isVietnamese = canonical != null
        setComposingRaw(canonical ?: survivor)
        return processState.toDisplayString(options.oldTonePlacement)
    }

    // ================================================================
    // REPLAY / COMPILE
    // ================================================================

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
            val t = VietnameseUnicode.toneOf(c)
            if (t != Tone.NONE && detectedTone == Tone.NONE) detectedTone = t
            untonedChars.append(VietnameseUnicode.stripTone(c))
        }
        val baseWord = untonedChars.toString()
        val baseLower = baseWord.lowercase()

        val onsetLen = OnsetMap.longestOnsetPrefix(baseLower)
        var onset = if (onsetLen > 0) baseWord.substring(0, onsetLen) else ""
        var remainingAfterOnset = baseWord.substring(onsetLen)

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
            val toneKey = validTone.toKey()
            if (toneKey != null) sb.append(if (nucAllUpper) toneKey.uppercaseChar() else toneKey)
            VietnameseUnicode.applyCasingFromRaw(sb.toString(), word)
        } else { word }

        return AdoptResult(isValid, onset.length, canonicalRaw)
    }

    /**
     * Canonical Telex raw for [adopt] when the word round-trips exactly through
     * the Telex kernel; null otherwise.  Shared by every adopt path (composer
     * backspace, IME display edits, prefix adoption, mid-word resume) so the
     * "adopt iff replay == display" decision lives in exactly one place.
     */
    fun canonicalRawIfRoundTrips(adopt: AdoptResult?, display: String): String? {
        if (adopt == null || !adopt.isValid) return null
        val canonical = adopt.canonicalRaw
        return if (process(canonical) == display) canonical else null
    }

    /** [adoptWord] + round-trip gate in one call — null when not adoptable. */
    fun adoptRoundTrip(display: String): String? =
        canonicalRawIfRoundTrips(adoptWord(display), display)

    // ================================================================
    // PUBLIC: process
    // ================================================================

    fun process(raw: String): String {
        if (raw.isEmpty()) return ""
        compileRaw(raw, true, stringOut)
        return stringOut.toStringVal()
    }

    // ================================================================
    // COMPANION
    // ================================================================

    companion object {
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
