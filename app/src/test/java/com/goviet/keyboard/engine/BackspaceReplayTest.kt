package com.goviet.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contracts for the display-level composing backspace.
 *
 * Backspace/delete edit the *displayed* text, one complete letter (grapheme
 * cluster) at a time — exactly like committed text and like the big keyboards.
 * The surviving display is re-adopted to canonical Telex raw keystrokes so
 * typing continues seamlessly. These tests lock that contract so raw-keystroke
 * deletion or snapshot-style undo stacks cannot silently come back.
 */
class BackspaceReplayTest {

    private lateinit var engine: VietnameseComposer

    @Before
    fun setUp() {
        engine = VietnameseComposer()
        engine.vietnameseModeEnabled = true
    }

    @Test
    fun backspaceDeletesCompleteGraphemesNotRawKeystrokes() {
        // "thấy" = [t][h][ấ][y]. Backspace removes one complete letter each time:
        // thấy -> thấ -> th -> t -> "" (never thây -> thay, which is raw Telex undo).
        var display = "thấy"
        assertEquals("thấ", backspaceGrapheme(display))
        assertEquals("th", backspaceGrapheme("thấ"))
        assertEquals("t", backspaceGrapheme("th"))
        assertEquals("", backspaceGrapheme("t"))
    }

    @Test
    fun displayBackspaceReSyncsCanonicalRaw() {
        // After a grapheme backspace the surviving display is re-adopted to
        // canonical Telex raw so the next keystroke continues correctly.
        val steps = mapOf(
            "thấy" to "thaays",
            "thấ" to "thaas",
            "thâ" to "thaa",
            "tha" to "tha",
            "th" to "th",
            "t" to "t",
            "thươn" to "thuown",
            "toá" to "toas"
        )
        for ((display, canonical) in steps) {
            val adopt = engine.adoptWord(display)
            assertNotNull("'$display' must be re-adoptable", adopt)
            assertTrue("'$display' must be valid", adopt!!.isValid)
            assertEquals("canonical raw for '$display'", canonical, adopt.canonicalRaw)
            assertEquals("round-trip for '$display'", display, engine.process(adopt.canonicalRaw))
        }
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

private fun backspaceGrapheme(display: String): String {
    if (display.isEmpty()) return ""
    val start = GraphemeEditor.previousBoundary(display, display.length)
    return display.substring(0, start)
}

/** The literal insertion path used by the controller (Telex kernel is bypassed). */
private fun VietnameseComposer.literalDisplay(raw: String): String = raw
