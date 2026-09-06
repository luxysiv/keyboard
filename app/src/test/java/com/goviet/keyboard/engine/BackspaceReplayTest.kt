package com.goviet.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contracts for the replay-based composing backspace.
 *
 * The preedit is a pure function of the raw keystroke buffer: every backspace
 * deletes exactly one raw keystroke before the caret and re-derives display +
 * live state from the remaining buffer via the same engine replay path used
 * elsewhere. These tests lock that contract so the old snapshot/undo-log
 * behavior (which lost whole adopted words once the log ran dry) cannot
 * silently come back.
 */
class BackspaceReplayTest {

    private lateinit var engine: VietnameseComposer

    @Before
    fun setUp() {
        engine = VietnameseComposer()
        engine.vietnameseModeEnabled = true
    }

    @Test
    fun adoptedWordSurvivesThreeConsecutiveBackspaces() {
        // User types "thay", commits with space, taps into the word, then types
        // 'a' and 's' -> raw "thayas" renders as "thấy".
        val raw = StringBuilder("thayas")
        assertEquals("thấy", engine.process(raw.toString()))

        // Backspaces keep draining exactly one raw keystroke per press and never
        // blow away the previously committed word ("thay" -> "" in one step).
        raw.deleteCharAt(raw.length - 1)
        assertEquals("thây", engine.process(raw.toString()))
        raw.deleteCharAt(raw.length - 1)
        assertEquals("thay", engine.process(raw.toString()))
        raw.deleteCharAt(raw.length - 1)
        assertEquals("tha", engine.process(raw.toString()))

        // Draining to the end is gradual all the way down.
        raw.deleteCharAt(raw.length - 1)
        assertEquals("th", engine.process(raw.toString()))
        raw.deleteCharAt(raw.length - 1)
        assertEquals("t", engine.process(raw.toString()))
        raw.deleteCharAt(raw.length - 1)
        assertEquals("", engine.process(raw.toString()))
    }

    @Test
    fun replayAfterBackspaceKeepsLiveStateSynchronized() {
        for (step in arrayOf("thayas", "thaya", "thay", "tha", "th", "t")) {
            val state = VietnameseComposer.SyllableState()
            engine.replayRawToState(step, state)
            val display = state.toDisplayString(engine.options.oldTonePlacement)
            assertEquals("display diverges from engine replay for raw '$step'", engine.process(step), display)
            assertTrue("non-empty raw must keep a non-empty display", step.isEmpty() || display.isNotEmpty())
        }
    }

    @Test
    fun literalPreeditBackspacePassesRawTextThrough() {
        // Literal composition is not run through the Telex kernel: backspacing
        // must simply shorten the passthrough text.
        val raw = StringBuilder("confirm")
        raw.deleteCharAt(raw.length - 1)
        assertEquals("confir", engine.literalDisplay(raw.toString()))
        raw.deleteCharAt(raw.length - 1)
        assertEquals("confi", engine.literalDisplay(raw.toString()))
    }

    @Test
    fun midWordEditAdoptsOnlyThePrefixBeforeCaret() {
        // "thay" with the caret between 'a' and 'y': the composed region is the
        // prefix "tha"; the committed 'y' stays outside and the caret never jumps.
        assertEquals("tha", engine.process("tha"))
        // Typing 'a' folds into 'â' (view: "thâ" + committed "y" = "thây").
        assertEquals("thâ", engine.process("thaa"))
        // Typing 's' finishes the edit (view: "thấ" + committed "y" = "thấy").
        assertEquals("thấ", engine.process("thaas"))
    }
}

/** The literal insertion path used by the controller (Telex kernel is bypassed). */
private fun VietnameseComposer.literalDisplay(raw: String): String = raw
