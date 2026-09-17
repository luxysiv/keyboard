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
 * (single-cycle folds, order-free keystrokes, dict-validated spellings and
 * tone). Divergences are collected and printed; each is then either fixed in
 * the engine or documented as a spec decision.
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
        assertEquals("reference deviates for \"$keys\" | $note", refExpected, rdisp)
        assertEquals("engine deviates for \"$keys\" | $note", engineExpected, edisp)
    }

    /** Compare both; collect and print divergence, never fails (diagnostic). */
    private fun diag(keys: String, note: String) {
        try {
            val (edisp, rdisp) = combined(keys)
            if (edisp != rdisp) {
                val known = DECIDED_DIVERGENCES[keys]
                val tag = if (known != null) "KNOWN-DECISION (%s)".format(known) else "UNRESOLVED"
                println("DIVERGE | \"$keys\" | engine=\"$edisp\" ref=\"$rdisp\" | $note | $tag")
                mismatches.append("DIVERGE | \"$keys\" | engine=\"$edisp\" ref=\"$rdisp\" | $note | $tag\n")
            }
        } catch (ex: Throwable) {
            println("ERROR   | \"$keys\" | ${ex.javaClass.simpleName}: ${ex.message}")
            mismatches.append("ERROR   | \"$keys\" | ${ex.javaClass.simpleName}: ${ex.message}\n")
        }
    }

    /**
     * Documented engine-vs-reference divergences (triaged, intentionally left).
     * Each entry is a decision: the reference implements the spec (dict-validated,
     * order-free folds); the production engine may be stricter or narrower.
     */
    private companion object {
        val DECIDED_DIVERGENCES = mapOf(
            "tuwowng" to "engine does not fold alternate order uwow->ương (only uowng); ref = real word",
            "huwowng" to "engine does not fold alternate order uwow->ương; ref = real word",
            "chiocs" to "ref splits chi+óc; engine echoes (no full syllable chioc); ref free-split artifact",
            "yenw" to "ref splits y+en+ư; engine echoes; ref free-split artifact",
            "undefined" to "English input: ref splits un+dèined; engine echoes; ref free-split artifact"
        )
    }

    @Test
    fun `decided w-unfold behaviour matches`() {
        assertBoth("aanw", "ăn", "ăn", "user-decided: a a n w -> ăn")
        assertBoth("aanww", "anw", "anw", "user-decided: repeated w reverts to plain base")
        assertBoth("awnw", "anw", "anw", "user-decided: w after onset")
        assertBoth("aww", "aw", "aw", "user-decided: ă cycle")
        assertBoth("uoww", "uow", "uow", "user-decided: ươ cycle")
        assertBoth("uo", "uo", "uo", "plain uo before fold")
        assertBoth("tuana", "tuân", "tuân", "order-free: fold a arrives after coda n")
    }

    @Test
    fun `engine-verified fold and tone targets match the reference`() {
        // These cases are asserted in VietnameseComposerTest for the engine;
        // the reference must reproduce them because they encode basic Telex.
        assertBoth("toio", "tôi", "tôi", "repeated o folds o -> ô")
        assertBoth("soio", "sôi", "sôi", "repeated o folds o -> ô")
        assertBoth("toois", "tối", "tối", "o fold then sắc")
        assertBoth("tooiss", "tôis", "tôis", "cancel tone, literal tail")
        assertBoth("caya", "cây", "cây", "repeated a folds a -> â")
        assertBoth("maya", "mây", "mây", "repeated a folds a -> â")
        assertBoth("naua", "nâu", "nâu", "repeated a folds a -> â")
        assertBoth("taua", "tâu", "tâu", "repeated a folds a -> â")
        assertBoth("tiene", "tiên", "tiên", "repeated e folds e -> ê")
        assertBoth("tienef", "tiền", "tiền", "ê with huyền")
        assertBoth("bienes", "biến", "biến", "ê with sắc")
        assertBoth("khuyene", "khuyên", "khuyên", "uyê compound")
        assertBoth("nguoifw", "người", "người", "uwow-style người via trailing w")
        assertBoth("nguowif", "người", "người", "uwow-style người, tone before fold")
    }

    @Test
    fun `diagnostic corpus sweep`() {
        val corpus = listOf(
            // plain rimes, tones, folds, order-free mixes and non-words
            "ban", "baan", "banf", "hoan", "hoans", "hoas", "tuong", "tuwng",
            "tut", "tuwowng", "huong", "huwowng", "ngieng", "nghieeng",
            "chiocs", "khoan", "khoana", "khoanh", "hoang", "toan", "toans",
            "tois", "toanf", "ddung", "dduwng", "ddungf", "mua", "muaw",
            "thuwa", "uong", "uowg", "uawn", "uow", "uoww", "nguyen", "nguoiws",
            "nguowif", "quyen", "quyeen", "quyenr", "ruou", "yen", "yenw",
            "khoanh", "toanh", "loang", "giang", "giong", "gioiws", "truong",
            "trung", "chuyen", "chuyeen", "biet", "bietj", "quyet", "quieet",
            "yent", "tr", "tra", "banh", "undefined", "hello", "test", "oy",
            "giuw", "giuwx", "giuwr", "sach", "sachs", "sachr", "lang", "lange"
        )
        for (keys in corpus.distinct()) {
            diag(keys, "sweep")
        }
    }
}