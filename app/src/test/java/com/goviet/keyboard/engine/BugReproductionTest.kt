package com.goviet.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reproduction tests + UniKey/Laban behavior contracts for the 4 bugs:
 * Bug 1: luan + space + backspace + a → 'luân'
 * Bug 2: l u a n a a → 'luana'
 * Bug 3: xuat + space + backspace + a + s → 'xuất'
 * Bug 4: l u y e n e e → 'luyene' (pure typing) and commit "luyên" + 'e' → 'luyene'
 */
class BugReproductionTest {

    private lateinit var engine: VietnameseComposer

    @Before
    fun setUp() {
        engine = VietnameseComposer()
        engine.vietnameseModeEnabled = true
    }

    private fun type(keys: String) {
        for (c in keys) engine.processKey(c)
    }

    /** Simulate the IME display-level backspace: re-adopt survivor to canonical Telex. */
    private fun adoptBack(survivor: String) {
        val canonical = engine.adoptRoundTrip(survivor)
        assertNotNull("'$survivor' must be re-adoptable", canonical)
        engine.composeAsVietnamese = true
        engine.setComposingRaw(canonical!!)
    }

    // ── Bug 1: luan + space + backspace + a → 'luân' ──
    @Test
    fun testBug1_luan_space_backspace_a_shouldBe_luan() {
        // "luan" is a complete Vietnamese rime (ua + n) so it round-trips.
        assertEquals("luan", engine.process("luan"))
        assertEquals("luan", engine.adoptRoundTrip("luan"))

        // The committed word is deleted one grapheme at a time; the survivor
        // "luan" is re-adopted and typing 'a' folds ua → uâ → "luân".
        adoptBack("luan")
        engine.processKey('a')
        val display = engine.toDisplayString()
        println("Bug1: raw='${engine.composingRaw()}' display='$display'")
        assertEquals("luân", display)
        assertEquals("luana", engine.composingRaw().toString())
    }

    // ── Bug 2: l u a n a a → 'luana' (fold then untoggle) ──
    @Test
    fun testBug2_luanaa_shouldBe_luana() {
        assertEquals("luan", engine.process("luan"))
        assertEquals("luân", engine.process("luana"))
        assertEquals("luana", engine.process("luanaa"))
        assertEquals("luanan", engine.process("luanaan"))
    }

    // ── Bug 3: xuat + space + backspace + a + s → 'xuất' ──
    @Test
    fun testBug3_xuat_space_backspace_a_s_shouldBe_xuat() {
        assertEquals("xuat", engine.process("xuat"))
        assertEquals("xuat", engine.adoptRoundTrip("xuat"))

        adoptBack("xuat")
        engine.processKey('a')
        val afterA = engine.toDisplayString()
        println("Bug3 after 'a': raw='${engine.composingRaw()}' display='$afterA'")
        assertEquals("xuât", afterA)
        engine.processKey('s')
        val display = engine.toDisplayString()
        println("Bug3 after 's': raw='${engine.composingRaw()}' display='$display'")
        assertEquals("xuất", display)
        assertEquals("xuatas", engine.composingRaw().toString())
    }

    // ── Bug 4: l u y e n e e → 'luyene' ──
    @Test
    fun testBug4_luyenee_shouldBe_luyene() {
        assertEquals("luyên", engine.process("luyene"))
        assertEquals("luyene", engine.process("luyenee"))
    }

    // Bug 4 retype path: commit the folded word then type 'e' again. The
    // adopted raw must be fold-last ("luyene") so the retype untoggles to
    // "luyene" — the Laban Key continuation (old code resumed "luyeen"+e →
    // "luyêne").
    @Test
    fun testBug4_commitLuyen_thenRetype_e_shouldBe_luyene() {
        type("luyene")
        assertEquals("luyên", engine.toDisplayString())
        val canonical = engine.adoptRoundTrip("luyên")
        assertNotNull(canonical)
        assertEquals("luyene", canonical)
        engine.composeAsVietnamese = true
        engine.setComposingRaw(canonical!!)
        engine.processKey('e')
        val display = engine.toDisplayString()
        println("Bug4 retype: raw='${engine.composingRaw()}' display='$display'")
        assertEquals("luyene", display)
    }

    // Same retype contract for uâ: commit "luân" + 'a' → "luana".
    @Test
    fun testCommitLuan_thenRetype_a_shouldBe_luana() {
        type("luana")
        assertEquals("luân", engine.toDisplayString())
        val canonical = engine.adoptRoundTrip("luân")
        assertNotNull(canonical)
        assertEquals("luana", canonical)
        engine.composeAsVietnamese = true
        engine.setComposingRaw(canonical!!)
        engine.processKey('a')
        assertEquals("luana", engine.toDisplayString())
    }

    // ── UniKey VCPairList contracts ──
    @Test
    fun testUaCodaGroup() {
        // ua + n/ng/t (UniKey {vs_ua, cs_n/ng/t}).
        assertTrue(RimeMap.isValidPrefix(RimeMap.rimeKey("uan")))
        assertTrue(RimeMap.isValidPrefix(RimeMap.rimeKey("uang")))
        assertTrue(RimeMap.isValidPrefix(RimeMap.rimeKey("uat")))
        assertFalse(RimeMap.isValidPrefix(RimeMap.rimeKey("uac")))
        assertFalse(RimeMap.isValidPrefix(RimeMap.rimeKey("uam")))
        // uâ keeps the same codas plus c.
        assertTrue(RimeMap.isValidPrefix(RimeMap.rimeKey("uân")))
        assertTrue(RimeMap.isValidPrefix(RimeMap.rimeKey("uâng")))
        assertTrue(RimeMap.isValidPrefix(RimeMap.rimeKey("uất")))
    }

    @Test
    fun testOpenUaToneOnU() {
        // Real words chùa/của/lúa: tone mark sits on 'u' (position 0).
        assertEquals("chùa", engine.process("chuaf"))
        assertEquals("của", engine.process("cuar"))
        assertEquals("lúa", engine.process("luas"))
    }

    @Test
    fun testFoldedUaToneOnA() {
        // Folded uâ: tone moves onto â (position 1) — xuất, luận.
        assertEquals("xuất", engine.process("xuatas"))
        assertEquals("luận", engine.process("luanaj"))
        assertEquals("tuân", engine.process("tuana"))
        assertEquals("luân", engine.process("luana"))
    }

    // ── Bug 5: Multi-char coda + deferred fold — lenhe → lênh, cheches → chếch ──
    @Test
    fun testBug5_lenhe_shouldBe_lenh() {
        // "en" is valid coda for 'e'; "nh" is only valid for 'ê'.
        // The fold key 'e' at pos+1 triggers e→ê making "nh" a valid coda.
        assertEquals("lênh", engine.process("lenhe"))
    }

    @Test
    fun testBug5_lenhes_shouldBe_lenh_with_tone() {
        // lenhe + s = lếnh (s = sắc tone applied after fold)
        assertEquals("lếnh", engine.process("lenhes"))
        assertEquals("lềnh", engine.process("lenhef"))
    }

    @Test
    fun testBug5_cheches_shouldBe_chech() {
        // "ec" is valid coda for 'e'; "ch" is only valid for 'ê'.
        assertEquals("chếch", engine.process("cheches"))
    }

    @Test
    fun testBug5_theche_shouldBe_thech() {
        // th + ech + fold → thêch
        assertEquals("thêch", engine.process("theche"))
    }

    @Test
    fun testBug5_no_fold_multiCharCoda_staysLiteral() {
        // Without the fold key, multi-char coda extension should not happen
        assertEquals("lenh", engine.process("lenh"))
        assertEquals("chech", engine.process("chech"))
    }


    @Test
    fun testBug5_chechse_shouldBe_chech() {
        // coda ext 'h' + tone 's' + fold 'e' → chếch
        assertEquals("chếch", engine.process("chechse"))
    }

}
