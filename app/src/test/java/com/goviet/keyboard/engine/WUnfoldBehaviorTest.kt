package com.goviet.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class WUnfoldBehaviorTest {
    private val engine: VietnameseComposer
        get() = VietnameseComposer(EngineOptions())

    // a a n w -> ăn ; a a n w w -> anw (w as tone/untoggle-style unfold key)
    @Test
    fun doubleAdaWNwBehavior() {
        assertEquals("ân", engine.process("aan"))
        assertEquals("ăn", engine.process("aanw"))
        assertEquals("anw", engine.process("aanww"))
    }

    // baseline untoggle semantics of s/f/j/x the user references
    @Test
    fun toneKeyUnfoldBaseline() {
        assertEquals("á", engine.process("as"))
        assertEquals("à", engine.process("af"))
        assertEquals("anw", engine.process("aanww"))
    }

    @Test
    fun wRespectsDiacriticBases() {
        assertEquals("ă", engine.process("aw"))
        assertEquals("aw", engine.process("aww"))
        assertEquals("ăn", engine.process("awn"))
        assertEquals("anw", engine.process("awnw"))
        assertEquals("uơ", engine.process("uow"))
        assertEquals("uow", engine.process("uoww"))
    }
}