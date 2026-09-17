package com.goviet.keyboard.engine

import com.goviet.keyboard.engine.reference.ReferenceComposer
import com.goviet.keyboard.engine.reference.ReferenceDictionary
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Comparison harness: drives the production [VietnameseComposer] and the
 * independent [ReferenceComposer] (real-syllable dictionary based) over the
 * same keystroke streams and reports every mismatch.
 *
 * The engine is the production target; the reference encodes the user's spec
 * (single-cycle folds, order-free keystrokes, dict-validated rimes, tone
 * position from a real syllable). Divergences are collected and printed; each
 * is then either fixed in the engine or documented as a spec decision.
 */
class HarnessReferenceTest {

    private val dict = ReferenceDictionary.load()
    private val mismatches = StringBuilder()

    private fun engine(): VietnameseComposer =
        VietnameseComposer().apply { reset() }

    /** Last display of both composers after typing [keys]. */
    private fun combined(keys: String): Pair<String, String> {
        val e = engine()
        var edisp = ""
        var rdisp = ""
        val r = ReferenceComposer(dict)
        for (c in keys) {
            edisp = e.processKey(c).text.toString()
            rdisp = r.processKey(c)
        }
        return Pair(edisp, rdisp)
    }

    private fun assertBoth(keys: String, engineExpected: String, refExpected: String, note: String) {
        val (edisp, rdisp) = combined(keys)
        assertEquals(refExpected, rdisp, "reference deviates for \"$keys\" | $note")
        assertEquals(engineExpected, edisp, "engine deviates for \"$keys\" | $note")
    }

    /** Compare both; collect and print divergence, never fails (diagnostic). */
    private fun diag(keys: String, note: String) {
        try {
            val (edisp, rdisp) = combined(keys)
            if (edisp != rdisp) {
                println("DIVERGE | \"$keys\" | engine=\"$edisp\" ref=\"$rdisp\" | $note")
                mismatches.append("DIVERGE | \"$keys\" | engine=\"$edisp\" ref=\"$rdisp\" | $note\n")
            }
        } catch (ex: Throwable) {
            println("ERROR   | \"$keys\" | ${ex.javaClass.simpleName}: ${ex.message}")
            mismatches.append("ERROR   | \"$keys\" | ${ex.javaClass.simpleName}: ${ex.message}\n")
        }
    }

    @Test
    fun `decided w-unfold behaviour matches`() {
        assertBoth("aanw", "ăn", "ăn", "user-decided: a a n w -> ăn")
        assertBoth("aanww", "anw", "anw", "user-decided: repeated w reverts to plain base")
        assertBoth("awnw", "anw", "anw", "user-decided: w after onset")
        assertBoth("aww", "aw", "aw", "user-decided: ă cycle")
        assertBoth("uoww", "uow", "uow", "user-decided: ươ cycle")
        assertBoth("uo", "uo", "uo", "plain uo before fold")
    }

    @Test
    fun `order-free folds including fold after coda`() {
        assertBoth("tuana", "tuân", "tuân", "fold a arrives after coda n")
        assertBoth("tuaan", "tuân", "tuân", "canonical fold order")
        assertBoth("chuanra", "chuẩn", "chuẩn", "tone r then fold a after coda")
        assertBoth("churana", "chuẩn", "chuẩn", "tone r early, plain extension of ua")
        assertBoth("chuaanr", "chuẩn", "chuẩn", "canonical, tone last")
    }

    @Test
    fun `tone keys position from dictionary syllable`() {
        assertBoth("toan", "toan", "toan", "plain rime")
        assertBoth("toans", "toán", "toán", "sắc on a")
        assertBoth("tois", "tối", "tối", "tone lands on ô via dict syllable")
        assertBoth("tôi", "tôi", "tôi", "ngang syllable")
    }

    @Test
    fun `diagnostic corpus sweep`() {
        val corpus = listOf(
            "ban", "baan", "banf", "hoan", "hoas", "tuong", "tuwng", "tương",
            "chợt", "chiocs", "khoan", "khoana", "hoang", "đừng", "ddung",
            "ddungf", "mua", "muaw", "thưa", "thuwa", "uống", "uôngw",
            "nghiêng", "ngieng", "giang", "quyen", "quyeen", "rượu", "ruou",
            "yen", "uawn", "nghieeng", "khoanh", "toanh", "loang",
            "hello", "test", "chuanra", "churana", "tuana", "uawn", "oy"
        )
        for (keys in corpus.distinct()) {
            diag(keys, "sweep")
        }
    }
}