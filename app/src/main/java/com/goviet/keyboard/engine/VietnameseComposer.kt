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
            onset = ""
            nucleus = ""
            coda = ""
            tone = Tone.NONE
            rawSuffix = ""
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
        val canonicalRaw: String,
        val canonicalFoldLast: String? = null
    )

    sealed class CompositionResult {
        abstract val text: CharSequence
        data class Update(override val text: CharSequence) : CompositionResult()
        data class CommitAndStartNew(val commitText: String, val newChar: Char) : CompositionResult() {
            override val text: CharSequence get() = commitText
        }
    }

    private val replayState = SyllableState()
    private val stringOut = OwnedBuffer()

    /** Test API: internal buffer + state for processKey. */
    private val processRaw = StringBuilder()
    private val processState = SyllableState()
    private val syllableRenderBuf = OwnedBuffer()

    fun reset() {
        replayState.reset()
        processRaw.clear()
        processState.reset()
        composeAsVietnamese = true
    }
    /** composeAsVietnamese flag — used by tests to switch Vietnamese/Literal mode. */
    var composeAsVietnamese: Boolean = true

    /** Display string of the current interactive state. */
    fun toDisplayString(): String =
        processState.toDisplayString(options.oldTonePlacement)

    /** Render the current interactive state into [out] without allocating a String. */
    fun toDisplayBuffer(out: OwnedBuffer) {
        processState.toDisplayBuffer(out, options.oldTonePlacement)
    }

    /**
     * Generate deconstructed snapshots: adopt [word], replay keystroke by keystroke,
     * return (canonicalRaw, snapshots).
     */
    internal fun generateDeconstructedSnapshots(word: String): Pair<String, List<Snapshot>> {
        val adopt = adoptWord(word) ?: return Pair(word, listOf(Snapshot(word)))
        val canonical = canonicalRawIfRoundTrips(adopt, word) ?: adopt.canonicalRaw
        val snaps = mutableListOf<Snapshot>()
        val tempState = SyllableState()
        for (i in 0 until canonical.length) {
            resegment(canonical.subSequence(0, i + 1), tempState)
            snaps.add(Snapshot(tempState.toDisplayString(options.oldTonePlacement)))
        }
        return Pair(canonical, snaps)
    }

    data class Snapshot(val displayText: String)

    /**
     * Lookahead in raw: collect consonant chars from [from] that could form
     * a coda (stopping at non-consonant or end of string).  Tone/fold keys in
     * between are skipped WITHOUT lengthening the returned tail, so a tone
     * typed mid-keystrokes (huownsg) still yields the coda "ng", letting the
     * dual-variant w-fold resolve to ươ instead of falling back to uơ.
     * Used by fold rules to predict the coda before deciding fold variant.
     */
    private fun predictConsonantTail(raw: CharSequence, from: Int): String {
        val sb = StringBuilder()
        var i = from
        while (i < raw.length) {
            val c = raw[i].lowercaseChar()
            if (isToneKey(c) || RimeMap.isFoldKey(c)) { i++; continue }
            if (OnsetMap.isConsonant(c)) {
                sb.append(c)
                i++
            } else break
        }
        return sb.toString()
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

    /** Test API: resegment [raw] into a fresh state (the resegment the kernel
     *  uses for every replay); mirrors [resegment] for display-level tests. */
    internal fun replayRawToState(raw: CharSequence, out: SyllableState) {
        resegment(raw, out)
    }

    /**
     * Fold anchor — the single record of the last Telex fold that touched the
     * nucleus.  Untoggle compares the next key against it; it replaces the old
     * loose fold-key/nucleus-index/raw-position/standalone fields.
     */
    private class FoldAnchor(
        var key: Char = '\u0000',
        var nucIdx: Int = -1,
        var rawPos: Int = -1,
        var standalone: Boolean = false,
        var plainNucleus: String = ""
    ) {
        val active: Boolean get() = key != '\u0000'
        fun set(key: Char, nucIdx: Int, rawPos: Int, standalone: Boolean = false, plainNucleus: String = "") {
            this.key = key; this.nucIdx = nucIdx; this.rawPos = rawPos
            this.standalone = standalone; this.plainNucleus = plainNucleus
        }
        fun clear() { key = '\u0000'; nucIdx = -1; rawPos = -1; standalone = false; plainNucleus = "" }
    }

    /** Per-call scan memory — 6 fields + fold anchor (was 12 loose fields). */
    private class ScanCtx(
        var oKey: Int,
        var nucKey: Int = 0,
        var rimeKey: Int = 0,
        var lastToneKey: Char = '\u0000',
        var syllableLocked: Boolean = false,
        var justUntoggled: Boolean = false,
        var fold: FoldAnchor = FoldAnchor(),
        var pendingTone: Tone = Tone.NONE,
        var pendingToneKey: Char = '\u0000',
        var pendingUo: Boolean = false
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
                if (onsetLen > 1 && RimeMap.isGiOnset(raw, 0, onsetLen)) {
                    var vowelAfter = false
                    for (k in onsetLen until len) {
                        val ch = raw[k].lowercaseChar()
                        if (RimeMap.isBaseVowel(ch) || ch == 'w') { vowelAfter = true; break }
                    }
                    if (!vowelAfter) continue
                }
                onsetEnd = onsetLen
                break
            }
        }
        if (onsetEnd > 0) out.onset = raw.subSequence(0, onsetEnd).toString()
    }

    /** Append [c] as literal text and hard-lock the rest of the syllable. */
    private fun lockLiteral(out: SyllableState, ctx: ScanCtx, c: Char) {
        out.rawSuffix += c
        ctx.syllableLocked = true
    }

    /**
     * Phase 2 — walk the body keys, dispatching by category.  Each handler owns
     * exactly one branch of the old loop; `ScanCtx` carries the scan memory.
     */
    private fun scanBody(raw: CharSequence, out: SyllableState, ctx: ScanCtx) {
        var pos = out.onset.length
        val len = raw.length
        while (pos < len) {
            val c = raw[pos]
            val cLow = c.lowercaseChar()

            if (tryOnsetFold(c, cLow, out, ctx)) { pos++; continue }

            if (RimeMap.isToneKey(cLow)) {
                handleToneKey(c, cLow, out, ctx)
                pos++; continue
            }

            if (cLow == 'e' || cLow == 'o' || cLow == 'a' || cLow == 'w') {
                pos = if (cLow == 'w') handleWKey(raw, c, pos, out, ctx)
                      else applyModifierFold(raw, c, cLow, pos, out, ctx)
                continue
            }

            if (!ctx.syllableLocked && !OnsetMap.isConsonant(cLow) && RimeMap.isBaseVowel(c)) {
                if (tryPlainVowel(c, out, ctx)) { pos++; continue }
            }

            if (!ctx.syllableLocked && OnsetMap.isConsonant(cLow) && out.nucleus.isNotEmpty()) {
                val consumed = tryCoda(raw, c, cLow, pos, len, out, ctx)
                if (consumed > 0) { pos += consumed; continue }
            }

            lockLiteral(out, ctx, c)
            pos++
        }
    }

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
            lockLiteral(out, ctx, c)
            return true
        }
        return false
    }

    /** Tone handler — applies, clears, or untoggles the tone; locks on rejection. */
    private fun handleToneKey(c: Char, cLow: Char, out: SyllableState, ctx: ScanCtx) {
        if (ctx.syllableLocked) {
            out.rawSuffix += c
            return
        }
        val targetTone = Tone.fromKey(cLow)
        if (targetTone != null && out.nucleus.isNotEmpty()) {
            if (out.nucleus.length >= 2) {
                val n0 = out.nucleus[0].lowercaseChar()
                val n1 = out.nucleus[1].lowercaseChar()
                if ((n0 == 'a' && n1 == 'a') || (n0 == 'e' && n1 == 'e')) {
                    lockLiteral(out, ctx, c)
                    return
                }
            }
            if (targetTone == Tone.NONE && out.tone == Tone.NONE) {
                lockLiteral(out, ctx, c)
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
                    ctx.lastToneKey = '\u0000'
                }
                lockLiteral(out, ctx, c)
                return
            }
            val rk = ctx.rimeKey
            if (RimeMap.isRimeKeyValidForTone(rk, targetTone)) {
                if (ctx.pendingUo) resolvePendingUo(out, ctx)
                out.tone = targetTone
                ctx.lastToneKey = cLow
            } else {
                lockLiteral(out, ctx, c)
            }
            return
        }
        if (out.nucleus.isEmpty() && RimeMap.isGiOnset(out.onset)) {
            if (targetTone != null && targetTone != Tone.NONE) {
                if (ctx.pendingTone != Tone.NONE && cLow == ctx.pendingToneKey) {
                    ctx.pendingTone = Tone.NONE; ctx.pendingToneKey = '\u0000'
                } else {
                    ctx.pendingTone = targetTone; ctx.pendingToneKey = cLow
                }
                return
            }
            if (targetTone == Tone.NONE && ctx.pendingTone != Tone.NONE) {
                ctx.pendingTone = Tone.NONE; ctx.pendingToneKey = '\u0000'
                return
            }
        }
        lockLiteral(out, ctx, c)
    }

    /** Letter that a repeated fold key unfolds back to — plain base of [nuc]. */
    private fun unfoldToBase(nuc: String): String {
        var changed = -1
        for (i in nuc.indices) {
            if (RimeMap.plainOf(nuc[i]) != nuc[i]) { changed = i; break }
        }
        if (changed < 0) return nuc
        val sb = StringBuilder(nuc.length)
        for (i in nuc.indices) sb.append(RimeMap.plainOf(nuc[i]))
        return sb.toString()
    }

    /**
     * 'w'-key handler — w-special rules live here (standalone w → ư when directW
     * is off); a repeated 'w' after an applied fold falls through to the shared
     * untoggle path (like d→đ and the tone keys), releasing the fold and letting
     * the extra 'w' out as literal text: uoww → uow, thuowwngs → thuowngs.
     * Everything else goes to the shared fold path so aw→ă, ow→ơ, uw→ư work in
     * both directW modes.
     */
    private fun handleWKey(raw: CharSequence, c: Char, pos: Int, out: SyllableState, ctx: ScanCtx): Int {
        if (!options.directW && !ctx.syllableLocked && out.nucleus.isEmpty() &&
            (out.onset.isEmpty() || out.onset[0].lowercaseChar() != 'w')) {
            val wChar = if (c.isUpperCase()) 'Ư' else 'ư'
            val comboOk = out.onset.isEmpty() ||
                RimeMap.isSyllableDisplayPrefixValid(out.onset, wChar)
            if (comboOk) {
                out.nucleus = wChar.toString()
                ctx.nucKey = RimeMap.rimeKey(out.nucleus)
                ctx.rimeKey = ctx.nucKey
                ctx.fold.set('w', 0, pos, standalone = true)
                applyPendingTone(out, ctx)
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
        if (!ctx.syllableLocked && RimeMap.isFoldKey(cLow) && out.nucleus.isNotEmpty() && !ctx.justUntoggled) {
            if (ctx.fold.active && cLow == ctx.fold.key &&
                (pos == ctx.fold.rawPos + 1 ||
                    (cLow == 'w' && out.coda.isNotEmpty() && pos > ctx.fold.rawPos)) &&
                out.nucleus != ctx.fold.plainNucleus) {
                if (ctx.fold.standalone) {
                    out.nucleus = ""
                    out.rawSuffix += c
                    ctx.syllableLocked = true
                } else {
                    out.nucleus = unfoldToBase(ctx.fold.plainNucleus)
                    if (out.coda.isNotEmpty()) {
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
            /*
             * u/pivot-w pending compound: "uw" -> ư parks the double-duty w, so
             * a trailing 'o' keeps the w visible as "ưo" (uwo -> ưo, wo -> ưo)
             * instead of folding straight to ươ.  The pivot 'w' resolves it
             * (uwow -> ươ), and any continuation that ươ accepts resolves it
             * first (uwoc -> ươc, uwoi -> ươi); an invalid follower keeps the
             * pending form (uwok -> ưok).  Keys are computed as if "ươ" all
             * along, so prefix/coda validation needs no special casing.
             */
            if (cLow == 'o' && out.coda.isEmpty() && (out.nucleus == "ư" || out.nucleus == "Ư")) {
                out.nucleus = if (out.nucleus == "Ư") "Ưo" else "ưo"
                ctx.pendingUo = true
                ctx.nucKey = RimeMap.rimeKey("ươ")
                ctx.rimeKey = ctx.nucKey
                ctx.fold.clear()
                return pos + 1
            }
            if (ctx.pendingUo && cLow == 'w') {
                out.nucleus = out.nucleus.replace('o', 'ơ')
                ctx.pendingUo = false
                ctx.fold.set('w', 0, pos, plainNucleus = "uo")
                return pos + 1
            }
            val plainNuc = out.nucleus
            val foldIdx = applyFoldRules(c, ctx.nucKey, pos, raw, out)
            if (foldIdx >= 0) {
                ctx.fold.set(cLow, foldIdx, pos, plainNucleus = plainNuc)
                ctx.nucKey = RimeMap.rimeKey(out.nucleus)
                ctx.rimeKey = RimeMap.extendKey(ctx.nucKey, out.coda, 0, out.coda.length)
                ctx.justUntoggled = false
                return pos + 1
            }
        }
        if (!ctx.syllableLocked && out.nucleus.isNotEmpty() && cLow != 'w') {
            val combo = RimeMap.combineNucleus(out.nucleus, c)
            if (combo != null) {
                out.nucleus = combo
                ctx.nucKey = RimeMap.rimeKey(out.nucleus)
                ctx.rimeKey = RimeMap.extendKey(ctx.nucKey, out.coda, 0, out.coda.length)
                return pos + 1
            }
        }
        if (!ctx.syllableLocked && out.coda.isEmpty()) {
            val candidateKey = RimeMap.extendKeySingle(ctx.nucKey, c)
            if (RimeMap.isValidPrefix(candidateKey)) {
                if (out.nucleus.isEmpty() && out.onset.isNotEmpty()) {
                    if (!RimeMap.isSyllableDisplayPrefixValid(out.onset, c)) {
                        lockLiteral(out, ctx, c)
                        return pos + 1
                    }
                }
                out.nucleus += c
                ctx.nucKey = candidateKey
                ctx.rimeKey = ctx.nucKey
                ctx.fold.clear()
                applyPendingTone(out, ctx)
                return pos + 1
            }
        }
        ctx.justUntoggled = false
        lockLiteral(out, ctx, c)
        return pos + 1
    }

    /** Plain vowel → start a nucleus or extend it; false falls through to literal. */
    private fun tryPlainVowel(c: Char, out: SyllableState, ctx: ScanCtx): Boolean {
        if (out.nucleus.isEmpty()) {
            if (out.onset.isNotEmpty()) {
                if (!RimeMap.isSyllableDisplayPrefixValid(out.onset, c)) {
                    return false
                }
            }
            out.nucleus = c.toString()
            ctx.nucKey = RimeMap.rimeKey(out.nucleus)
            ctx.rimeKey = ctx.nucKey
            ctx.justUntoggled = false
            applyPendingTone(out, ctx)
            return true
        }
        if (out.coda.isEmpty()) {
            val candidateKey = RimeMap.extendKeySingle(ctx.nucKey, c)
            if (RimeMap.isValidPrefix(candidateKey)) {
                if (ctx.pendingUo) resolvePendingUo(out, ctx)
                out.nucleus += c
                ctx.nucKey = candidateKey
                ctx.rimeKey = ctx.nucKey
                return true
            }
        }
        return false
    }

    /** Parked u/pivot-w compound (see applyModifierFold) → display "ươ". */
    private fun resolvePendingUo(out: SyllableState, ctx: ScanCtx) {
        out.nucleus = out.nucleus.replace('o', 'ơ')
        ctx.pendingUo = false
    }

    /**
     * Consonant → coda via the flat map.  The fold keys that follow (e.g. the
     * double-a in tuana) are folded by the later modifier pass, so no lookahead
     * is needed here: the rime keys are resolved in gõ order (tuana → tuân).
     * Returns 1 when the coda is valid (or becomes literal), 0 on caller guard.
     */
    private fun tryCoda(raw: CharSequence, c: Char, cLow: Char, pos: Int, len: Int, out: SyllableState, ctx: ScanCtx): Int {
        val codaLen = out.coda.length
        val codaOk = codaLen < 2
        if (codaOk) {
            val rk = RimeMap.extendKeySingle(ctx.rimeKey, c)
            if (RimeMap.isValidPrefixWithTone(rk, out.tone.index)) {
                if (ctx.pendingUo) resolvePendingUo(out, ctx)
                out.coda += c
                ctx.rimeKey = rk
                return 1
            }
        }
        lockLiteral(out, ctx, c)
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

        val tail = predictConsonantTail(raw, rawPos + 1)
        val primNuc = RimeMap.applyFold(nuc, primary)
        val altNuc = RimeMap.applyFold(nuc, alt)

        fun pickVariant(coda: String): String? {
            val pv = isValidRime(primNuc, coda)
            val av = isValidRime(altNuc, coda)
            if (pv != av) return if (pv) primNuc else altNuc
            if (!pv) return null
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

    /** True while a preedit session has a non-empty raw buffer. */
    fun isComposing(): Boolean = processRaw.isNotEmpty()

    /** Read-only view of the composing raw keystrokes (caret mapping helpers). */
    fun composingRaw(): CharSequence = processRaw

    fun composingRawLength(): Int = processRaw.length

    /**
     * Replaces the composing raw buffer (adoption / display-level edits).
     * When [composeAsVietnamese] is false the text is kept verbatim (word-edit literal
     * lock); otherwise it is resegmented through the Telex kernel.
     */
    /** Refresh composing state after a raw-buffer mutation (resegment if Vietnamese). */
    private fun refreshProcessState() {
        if (composeAsVietnamese) {
            resegment(processRaw, processState)
        } else {
            processState.reset()
            processState.rawSuffix = processRaw.toString()
        }
    }

    fun setComposingRaw(raw: CharSequence) {
        processRaw.setLength(0)
        processRaw.append(raw)
        refreshProcessState()
    }

    /** Inserts one Telex key at [index] of the composing raw and resegments. */
    fun insertComposingKey(index: Int, key: Char) {
        if (index >= processRaw.length) processRaw.append(key) else processRaw.insert(index, key)
        refreshProcessState()
    }

    fun processKey(key: Char): CompositionResult {
        if (isBoundaryKey(key)) {
            val commitText = processState.toDisplayString(options.oldTonePlacement)
            processRaw.clear()
            processState.reset()
            composeAsVietnamese = true
            return CompositionResult.CommitAndStartNew(commitText, key)
        }
        insertComposingKey(processRaw.length, key)
        return CompositionResult.Update(processState.toDisplayString(options.oldTonePlacement))
    }

    fun backspace(): String {
        if (processRaw.isEmpty()) return ""
        val display = processState.toDisplayString(options.oldTonePlacement)
        val start = GraphemeEditor.previousBoundary(display, display.length)
        if (start <= 0) {
            processRaw.clear(); processState.reset()
            return ""
        }
        val survivor = display.substring(0, start)
        val canonical = adoptRoundTrip(survivor)
        composeAsVietnamese = canonical != null
        setComposingRaw(canonical ?: survivor)
        return processState.toDisplayString(options.oldTonePlacement)
    }

    /** Compile raw into an existing buffer — avoids allocation per call. */
    fun compileRaw(raw: CharSequence, vietnamese: Boolean, out: OwnedBuffer) {
compileRawInto(raw, vietnamese, out, raw.length)
    }

    /** Compile raw into an existing buffer — avoids allocation per call. */
    fun compileRawInto(raw: CharSequence, vietnamese: Boolean, out: OwnedBuffer, maxLen: Int = raw.length) {
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
            replayState.toDisplayBuffer(syllableRenderBuf, options.oldTonePlacement)
            out.append(syllableRenderBuf)
        }
        replayState.reset()
    }

    private fun isBoundaryKey(c: Char): Boolean {
        return c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '.' || c == '?' || c == '!' ||
            c == ',' || c == ';' || c == ':' || c == '-' || c == '/' || c == '(' || c == ')' ||
            c == '[' || c == ']' || c == '{' || c == '}' || c == '"' || c == '\'' || c == '«' ||
            c == '»' || c == '`' || c == '~' || c == '@' || c == '#' || c == '$' || c == '%' ||
            c == '^' || c == '&' || c == '*' || c == '_' || c == '=' || c == '+' || c == '|' ||
            c == '\\' || c == '<' || c == '>'
    }

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

        val nucleusEnd = scanNucleusEnd(remainingAfterOnset)
        var nucleus = remainingAfterOnset.substring(0, nucleusEnd)
        var remainingAfterNucleus = remainingAfterOnset.substring(nucleusEnd)
        var remLower = remainingAfterNucleus.lowercase()

        if (nucleus.isEmpty() && RimeMap.isGiOnset(onset)) {
            val shorterOnset = baseWord.substring(0, onset.length - 1)
            if (OnsetMap.isCompleteOnset(shorterOnset.lowercase(), 0, shorterOnset.length)) {
                onset = shorterOnset
                remainingAfterOnset = baseWord.substring(onset.length)
                val nucEnd2 = scanNucleusEnd(remainingAfterOnset)
                nucleus = remainingAfterOnset.substring(0, nucEnd2)
                remainingAfterNucleus = remainingAfterOnset.substring(nucEnd2)
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
            sb.append(canonicalOnsetFoldTopLevel(onset))
            sb.append(nucleusToRaw(nucleus))
            val nucAllUpper = nucleus.isNotEmpty() && nucleus.all { it.isUpperCase() }
            sb.append(coda)
            val toneKey = validTone.key
            if (toneKey != null) sb.append(if (nucAllUpper) toneKey.uppercaseChar() else toneKey)
            VietnameseUnicode.applyCasingFromRaw(sb.toString(), word)
        } else { word }

        val canonicalFoldLast = if (isValid) {
            canonicalFoldLastRaw(onset, nucleus, coda, validTone, word)
        } else null

        return AdoptResult(isValid, onset.length, canonicalRaw, canonicalFoldLast)
    }

    /**
     * Canonical Telex raw for [adopt] when the word round-trips exactly through
     * the Telex kernel; null otherwise.  Shared by every adopt path (composer
     * backspace, IME display edits, prefix adoption, mid-word resume) so the
     * "adopt iff replay == display" decision lives in exactly one place.
     */
    fun canonicalRawIfRoundTrips(adopt: AdoptResult?, display: String): String? {
        if (adopt == null || !adopt.isValid) return null
        adopt.canonicalFoldLast?.let { foldedLast ->
            if (process(foldedLast) == display) return foldedLast
        }
        val canonical = adopt.canonicalRaw
        return if (process(canonical) == display) canonical else null
    }

    /** Scans the maximal base-vowel run at the start of [remainingAfterOnset]:
     *  returns (nucleus, remainingAfterNucleus). */
    private fun scanNucleusEnd(remainingAfterOnset: String): Int {
        var i = 0
        while (i < remainingAfterOnset.length && RimeMap.isBaseVowel(remainingAfterOnset[i])) {
            i++
        }
        return i
    }

    /** Canonical raw onset with `đ` folded by casing (Đ→DD, Đx→Dd, else dd). */

    /**
     * [adoptWord] + round-trip gate in one call — null when not adoptable.
     * (Refactor note: composing-state refresh, nucleus scan, and `đ` onset
     * folding are each shared by one helper — see scanNucleusEnd and
     * canonicalOnsetFold above.)
     */
    fun adoptRoundTrip(display: String): String? =
        canonicalRawIfRoundTrips(adoptWord(display), display)

    fun process(raw: String): String {
        if (raw.isEmpty()) return ""
        compileRawInto(raw, true, stringOut)
        return stringOut.toStringVal()
    }

    /** Apply deferred tone (from handleToneKey) to the current nucleus. */
    private fun applyPendingTone(out: SyllableState, ctx: ScanCtx) {
        if (ctx.pendingTone != Tone.NONE) {
            val rk = RimeMap.keyCat(out.nucleus, out.nucleus.length, out.coda, out.coda.length)
            if (RimeMap.isRimeKeyValidForTone(rk, ctx.pendingTone)) {
                out.tone = ctx.pendingTone
                ctx.lastToneKey = ctx.pendingToneKey
            }
            ctx.pendingTone = Tone.NONE
            ctx.pendingToneKey = '\u0000'
        }
    }

    companion object {
        @JvmStatic
        fun isToneKey(c: Char): Boolean = RimeMap.isToneKey(c)

        @JvmStatic
        fun isVowelModifierKey(c: Char): Boolean = RimeMap.isFoldKey(c)

        fun nucleusToRaw(nucleus: String): String {
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

        /**
         * Fold-last canonical raw for a folded nucleus + coda: plain nucleus +
         * coda + the single Telex fold key + tone key (bân → "bana", uyên →
         * "uyene", xuất → "xuatas"). Only single-fold nuclei (one folded vowel)
         * qualify — multi-fold w-compounds (ươ/uơ) keep the fold-first spelling
         * through [nucleusToRaw]. Returns null when no reordering applies
         * (no coda, nothing folded, or a compound fold).
         */
        fun canonicalFoldLastRaw(
            onset: String, nucleus: String, coda: String,
            tone: Tone, word: String
        ): String? {
            if (nucleus.isEmpty() || coda.isEmpty()) return null
            val plain = StringBuilder(nucleus.length)
            var foldKey: Char? = null
            var foldedCount = 0
            for (ch in nucleus) {
                when (ch.lowercaseChar()) {
                    'â' -> { plain.append('a'); foldKey = foldKey ?: 'a'; foldedCount++ }
                    'ê' -> { plain.append('e'); foldKey = foldKey ?: 'e'; foldedCount++ }
                    'ô' -> { plain.append('o'); foldKey = foldKey ?: 'o'; foldedCount++ }
                    'ă' -> { plain.append('a'); foldKey = foldKey ?: 'w'; foldedCount++ }
                    'ơ' -> { plain.append('o'); foldKey = foldKey ?: 'w'; foldedCount++ }
                    'ư' -> { plain.append('u'); foldKey = foldKey ?: 'w'; foldedCount++ }
                    else -> plain.append(ch)
                }
            }
            val fk = foldKey ?: return null
            if (foldedCount != 1) return null

            val sb = StringBuilder()
            sb.append(canonicalOnsetFoldTopLevel(onset))
            sb.append(plain)
            sb.append(coda)
            sb.append(fk)
            val nucAllUpper = nucleus.all { it.isUpperCase() }
            val toneKey = tone.key
            if (toneKey != null) sb.append(if (nucAllUpper) toneKey.uppercaseChar() else toneKey)
            return VietnameseUnicode.applyCasingFromRaw(sb.toString(), word)
        }
    }

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

/** Canonical raw onset with `đ` folded by casing (top-level → resolves from any scope). */
internal fun canonicalOnsetFoldTopLevel(onset: String): String = when (onset.lowercase()) {
    "đ" -> if (onset == "Đ") "DD" else if (onset[0].isUpperCase()) "Dd" else "dd"
    else -> onset
}
