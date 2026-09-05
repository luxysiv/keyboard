package com.goviet.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Suite kiểm thử toàn diện cấu trúc mới của VietnameseInputEngine:
 * 1. Vowel transforms (aa, aw, ee, oo, ow, uw)
 * 2. Tones (s, f, r, x, j)
 * 3. D transforms (dd, dad, dadd)
 * 4. Cancellation (as -> á -> as, aa -> â -> aa, aw -> ă -> aw)
 * 5. Deferred Rime resolution (uow, uown, uowng, uowc)
 * 6. Onset tests (gi, qu, nghiêng, chuyển...)
 * 7. English-like words without dictionary (follow, detect, VinFast, simple)
 * 8. V-C-V boundary & Free transform (bana, banaa, dungw, dungwf)
 * 9. Incremental composition via processKey
 */
class VietnameseInputEngineTest {

    private lateinit var engine: VietnameseInputEngine

    @Before
    fun setUp() {
        engine = VietnameseInputEngine()
        engine.vietnameseModeEnabled = true
    }

    @Test
    fun testEngineInitialization() {
        assertNotNull(engine)
        assertTrue(engine.vietnameseModeEnabled)
    }

    @Test
    fun testPassThroughProcessingWhenDisabled() {
        engine.vietnameseModeEnabled = false
        assertEquals("test", engine.process("test"))
    }

    @Test
    fun testVowelTransformsAndCancellation() {
        // aa -> â, aaa -> aa
        assertEquals("â", engine.process("aa"))
        assertEquals("aa", engine.process("aaa"))

        // aw -> ă, aww -> aw
        assertEquals("ă", engine.process("aw"))
        assertEquals("aw", engine.process("aww"))

        // ee -> ê, eee -> ee
        assertEquals("ê", engine.process("ee"))
        assertEquals("ee", engine.process("eee"))

        // oo -> ô, ooo -> oo
        assertEquals("ô", engine.process("oo"))
        assertEquals("oo", engine.process("ooo"))

        // ow -> ơ, oww -> ow
        assertEquals("ơ", engine.process("ow"))
        assertEquals("ow", engine.process("oww"))

        // uw -> ư, uww -> uw
        assertEquals("ư", engine.process("uw"))
        assertEquals("uw", engine.process("uww"))
    }

    @Test
    fun testTonesAndCancellation() {
        // as -> á, ass -> as
        assertEquals("á", engine.process("as"))
        assertEquals("as", engine.process("ass"))

        // af -> à, aff -> af
        assertEquals("à", engine.process("af"))
        assertEquals("af", engine.process("aff"))

        // ar -> ả, arr -> ar
        assertEquals("ả", engine.process("ar"))
        assertEquals("ar", engine.process("arr"))

        // ax -> ã, axx -> ax
        assertEquals("ã", engine.process("ax"))
        assertEquals("ax", engine.process("axx"))

        // aj -> ạ, ajj -> aj
        assertEquals("ạ", engine.process("aj"))
        assertEquals("aj", engine.process("ajj"))
    }

    @Test
    fun testDTransformsAndCancellation() {
        // dd -> đ, ddd -> dd
        assertEquals("đ", engine.process("dd"))
        assertEquals("dd", engine.process("ddd"))

        // da -> da
        assertEquals("da", engine.process("da"))

        // dad -> đa
        assertEquals("đa", engine.process("dad"))

        // dadd -> dad (cancellation)
        assertEquals("dad", engine.process("dadd"))
    }

    @Test
    fun testUOResolutionDeferred() {
        // uow -> uơ
        assertEquals("uơ", engine.process("uow"))

        // thuow -> thuơ
        assertEquals("thuơ", engine.process("thuow"))

        // thuown -> thươn
        assertEquals("thươn", engine.process("thuown"))
        assertEquals("thương", engine.process("thuowng"))
        assertEquals("thươc", engine.process("thuowc"))
        assertEquals("thước", engine.process("thuowcs"))
    }

    @Test
    fun testBanaAndBanaa() {
        // bana -> bân
        assertEquals("bân", engine.process("bana"))

        // banaa -> bana
        assertEquals("bana", engine.process("banaa"))
    }

    @Test
    fun testOOProtection() {
        assertEquals("cống", engine.process("coongs"))
        assertEquals("sốc", engine.process("soocs"))
        assertEquals("sộc", engine.process("soocj"))

        // ooo -> oo (hủy biến đổi)
        assertEquals("coo", engine.process("cooo"))
        assertEquals("cooo", engine.process("coooo"))
    }

    @Test
    fun testGiOnsetGrammar() {
        assertEquals("gi", engine.process("gi"))
        assertEquals("gì", engine.process("gif"))
        assertEquals("gí", engine.process("gis"))
        assertEquals("gìn", engine.process("ginf"))
        assertEquals("gia", engine.process("gia"))
        assertEquals("giá", engine.process("gias"))
        assertEquals("già", engine.process("giaf"))
        assertEquals("giảm", engine.process("giamr"))
        assertEquals("gian", engine.process("gian"))
        assertEquals("giáo", engine.process("giaos"))
        assertEquals("giêng", engine.process("gieeng"))
        assertEquals("giếng", engine.process("gieengs"))
        assertEquals("gió", engine.process("gios"))
        assertEquals("giữ", engine.process("giuwx"))
        assertEquals("giữa", engine.process("giuwxa"))
        assertEquals("giường", engine.process("giuowngf"))
        assertEquals("giúp", engine.process("giups"))
    }

    @Test
    fun testQuOnsetGrammar() {
        assertEquals("qua", engine.process("qua"))
        assertEquals("quá", engine.process("quas"))
        assertEquals("quà", engine.process("quaf"))
        assertEquals("quả", engine.process("quar"))
        assertEquals("quan", engine.process("quan"))
        assertEquals("quang", engine.process("quang"))
        assertEquals("quy", engine.process("quy"))
        assertEquals("quý", engine.process("quys"))
        assertEquals("quỳ", engine.process("quyf"))
        assertEquals("quyên", engine.process("quyeen"))
        assertEquals("quyền", engine.process("quyeenf"))
        assertEquals("quyết", engine.process("quyeets"))
        assertEquals("quốc", engine.process("quoocs"))
        assertEquals("quân", engine.process("quaan"))
        assertEquals("quần", engine.process("quaanf"))
        assertEquals("quỳnh", engine.process("quynhf"))
        assertEquals("quơ", engine.process("quow"))
    }

    @Test
    fun testFreeTransformAfterValidCodas() {
        assertEquals("dùng", engine.process("dungf"))
        assertEquals("dưng", engine.process("dungw"))
        assertEquals("dừng", engine.process("dungwf"))
        assertEquals("dứng", engine.process("dungws"))
        assertEquals("thương", engine.process("thuongw"))
        assertEquals("chuyền", engine.process("chuyeenf"))
        assertEquals("chuyện", engine.process("chuyeenj"))
        assertEquals("toán", engine.process("toans"))
        assertEquals("biết", engine.process("bieets"))
        assertEquals("lăng", engine.process("langw"))
    }

    @Test
    fun testTransformKeysMustFollowAtLeastOneVowel() {
        assertEquals("dsa", engine.process("dsa"))
        assertEquals("dxa", engine.process("dxa"))
        assertEquals("dfa", engine.process("dfa"))
        assertEquals("dra", engine.process("dra"))
        assertEquals("dja", engine.process("dja"))
        assertEquals("dza", engine.process("dza"))

        assertEquals("bsa", engine.process("bsa"))
        assertEquals("tsa", engine.process("tsa"))
        assertEquals("kfa", engine.process("kfa"))
        assertEquals("sra", engine.process("sra"))

        assertEquals("đá", engine.process("ddas"))
        assertEquals("dá", engine.process("das"))
        assertEquals("dã", engine.process("dax"))
        assertEquals("dà", engine.process("daf"))
    }

    @Test
    fun testNonVietnameseWordsWithoutDictionary() {
        assertEquals("simple", engine.process("simple"))
    }

    @Test
    fun testIncrementalProcessKey() {
        engine.reset()
        assertEquals("t", engine.processKey('t').text)
        assertEquals("to", engine.processKey('o').text)
        assertEquals("toa", engine.processKey('a').text)
        assertEquals("toá", engine.processKey('s').text)
        assertEquals("toán", engine.processKey('n').text)
    }

    @Test
    fun testHoansaAndHoanso() {
        assertEquals("hoána", engine.process("hoansa"))
        assertEquals("hoáno", engine.process("hoanso"))

        engine.reset()
        assertEquals("h", engine.processKey('h').text)
        assertEquals("ho", engine.processKey('o').text)
        assertEquals("hoa", engine.processKey('a').text)
        assertEquals("hoan", engine.processKey('n').text)
        assertEquals("hoán", engine.processKey('s').text)
        assertEquals("hoána", engine.processKey('a').text)

        engine.reset()
        assertEquals("h", engine.processKey('h').text)
        assertEquals("ho", engine.processKey('o').text)
        assertEquals("hoa", engine.processKey('a').text)
        assertEquals("hoan", engine.processKey('n').text)
        assertEquals("hoán", engine.processKey('s').text)
        assertEquals("hoáno", engine.processKey('o').text)
    }

    @Test
    fun testToneUntoggleNoLoop() {
        assertEquals("đừng", engine.process("ddungwf"))
        assertEquals("đưngf", engine.process("ddungwff"))
        assertEquals("đưngff", engine.process("ddungwfff"))
        assertEquals("đưngfff", engine.process("ddungwffff"))

        engine.reset()
        assertEquals("d", engine.processKey('d').text)
        assertEquals("đ", engine.processKey('d').text)
        assertEquals("đu", engine.processKey('u').text)
        assertEquals("đun", engine.processKey('n').text)
        assertEquals("đung", engine.processKey('g').text)
        assertEquals("đưng", engine.processKey('w').text)
        assertEquals("đừng", engine.processKey('f').text)
        assertEquals("đưngf", engine.processKey('f').text)
        assertEquals("đưngff", engine.processKey('f').text)
        assertEquals("đưngfff", engine.processKey('f').text)
    }

    @Test
    fun testAnToneUntoggle() {
        assertEquals("ắn", engine.process("awns"))
        assertEquals("ăns", engine.process("awnss"))
        assertEquals("ănss", engine.process("awnsss"))
        assertEquals("toán", engine.process("toans"))
        assertEquals("toans", engine.process("toanss"))
        assertEquals("tối", engine.process("toois"))
        assertEquals("tôis", engine.process("tooiss"))
    }

    @Test
    fun testOLogic() {
        assertEquals("ô", engine.process("oo"))
        assertEquals("oo", engine.process("ooo"))
        assertEquals("ooo", engine.process("oooo"))
        assertEquals("oooo", engine.process("ooooo"))
        assertEquals("oó", engine.process("ooos"))
        assertEquals("oọ", engine.process("oooj"))
        assertEquals("oow", engine.process("ooow"))
        assertEquals("coo", engine.process("cooo"))
        assertEquals("cooo", engine.process("coooo"))
        assertEquals("coó", engine.process("cooos"))
        assertEquals("coón", engine.process("cooosn"))
        assertEquals("coóng", engine.process("cooosng"))
        assertEquals("coọ", engine.process("coooj"))
        assertEquals("coóng", engine.process("cooongs"))
    }

    @Test
    fun testWTransformAndDualRole() {
        assertEquals("ư", engine.process("w"))
        assertEquals("w", engine.process("ww"))
        assertEquals("ww", engine.process("www"))
        assertEquals("www", engine.process("wwww"))
        assertEquals("ứ", engine.process("ws"))
        assertEquals("ừ", engine.process("wf"))
        assertEquals("ưng", engine.process("wng"))
        assertEquals("ứng", engine.process("wngs"))
        assertEquals("ưa", engine.process("wa"))
        assertEquals("ứa", engine.process("was"))
        assertEquals("tư", engine.process("tw"))
        assertEquals("tw", engine.process("tww"))
        assertEquals("tww", engine.process("twww"))

        // Comprehensive W transformations
        assertEquals("thư", engine.process("thw"))
        assertEquals("thứ", engine.process("thws"))
        assertEquals("như", engine.process("nhw"))
        assertEquals("nhưng", engine.process("nhwng"))
        assertEquals("những", engine.process("nhwngx"))
        assertEquals("dư", engine.process("dw"))
        assertEquals("dứ", engine.process("dws"))
        assertEquals("đư", engine.process("ddw"))
        assertEquals("đứ", engine.process("ddws"))
        assertEquals("đừng", engine.process("ddwngf"))
        assertEquals("mừng", engine.process("mwngf"))
        assertEquals("sư", engine.process("sw"))
        assertEquals("sứ", engine.process("sws"))

        // w + a / u + a + w / u + w + a
        assertEquals("mưa", engine.process("muaw"))
        assertEquals("mứa", engine.process("muaws"))
        assertEquals("chưa", engine.process("chuaw"))
        assertEquals("dưa", engine.process("duaw"))
        assertEquals("đưa", engine.process("dduaw"))
        assertEquals("đứa", engine.process("dduaws"))
        assertEquals("mưa", engine.process("muwa"))
        assertEquals("chưa", engine.process("chuwa"))

        // w + o / u + o + w / free w
        assertEquals("ươ", engine.process("wo"))
        assertEquals("ướ", engine.process("wos"))
        assertEquals("mươ", engine.process("mwo"))
        assertEquals("mướ", engine.process("mwos"))
        assertEquals("dươ", engine.process("dwo"))
        assertEquals("tươ", engine.process("two"))
        assertEquals("hươ", engine.process("hwo"))
        assertEquals("thươ", engine.process("thwo"))
        assertEquals("chươ", engine.process("chwo"))

        // uơ only goes with h (huơ, huở), th (thuở), q/qu (quở)
        assertEquals("huơ", engine.process("huow"))
        assertEquals("huở", engine.process("huowr"))
        assertEquals("thuơ", engine.process("thuow"))
        assertEquals("thuở", engine.process("thuowr"))
        assertEquals("hương", engine.process("huowng"))
        assertEquals("thương", engine.process("thuowng"))

        // uow is uơ (open rime) across all onsets; uwo / uwow / wo are ươ
        assertEquals("uơ", engine.process("uow"))
        assertEquals("muơ", engine.process("muow"))
        assertEquals("duơ", engine.process("duow"))
        assertEquals("nuơ", engine.process("nuow"))
        assertEquals("tuơ", engine.process("tuow"))
        assertEquals("luơ", engine.process("luow"))
        assertEquals("cuơ", engine.process("cuow"))

        // uwo / uwow / wo produce ươ
        assertEquals("ươ", engine.process("uwo"))
        assertEquals("ươ", engine.process("uwow"))
        assertEquals("ươ", engine.process("wo"))
        assertEquals("tươ", engine.process("tuwo"))
        assertEquals("tươ", engine.process("tuwow"))
        assertEquals("tươi", engine.process("tuwoi"))
        assertEquals("mươ", engine.process("muwo"))
        assertEquals("mươ", engine.process("muwow"))
        assertEquals("mươi", engine.process("muwoi"))

        assertEquals("ương", engine.process("wong"))
        assertEquals("ướng", engine.process("wongs"))
        assertEquals("ường", engine.process("wongf"))
        assertEquals("ươn", engine.process("won"))
        assertEquals("ươc", engine.process("woc"))
        assertEquals("ước", engine.process("wocs"))
        assertEquals("ươt", engine.process("wot"))
        assertEquals("ướt", engine.process("wots"))
        assertEquals("ươi", engine.process("woi"))
        assertEquals("ưới", engine.process("wois"))
        assertEquals("ươu", engine.process("wou"))
        assertEquals("ướu", engine.process("wous"))

        // uoi + w / uou + w / uon + w / uoc + w
        assertEquals("ngươi", engine.process("nguoiw"))
        assertEquals("người", engine.process("nguoiwf"))
        assertEquals("ngưới", engine.process("nguoiws"))
        assertEquals("cươi", engine.process("cuoiw"))
        assertEquals("cười", engine.process("cuoiwf"))
        assertEquals("mươn", engine.process("muonw"))
        assertEquals("mượn", engine.process("muonwj"))
        assertEquals("nươc", engine.process("nuocw"))
        assertEquals("nước", engine.process("nuocws"))
        assertEquals("dược", engine.process("duocwj"))
        assertEquals("được", engine.process("dduocwj"))
        assertEquals("rượu", engine.process("ruouwj"))
        assertEquals("hươu", engine.process("huouw"))
        assertEquals("hoăc", engine.process("hoawc"))
        assertEquals("hoặc", engine.process("hoawcj"))
    }

    @Test
    fun testNoRollbackToRawWhenTransformed() {
        // Hủy dấu thanh xuất phím thô:
        assertEquals("tối", engine.process("toois"))
        assertEquals("tôis", engine.process("tooiss"))
        // Quá trình composition từng phím:
        engine.reset()
        assertEquals("t", engine.processKey('t').text)
        assertEquals("to", engine.processKey('o').text)
        assertEquals("tô", engine.processKey('o').text)
        assertEquals("tôi", engine.processKey('i').text)
        assertEquals("tối", engine.processKey('s').text)
        // Bấm s lần nữa -> hủy dấu sắc, trả về 'tôis'
        assertEquals("tôis", engine.processKey('s').text)

        engine.reset()
        assertEquals("ư", engine.processKey('w').text) // w -> ư
        assertEquals("ứ", engine.processKey('s').text)
        // Bấm s lần nữa -> hủy dấu sắc, trả về 'ưs'
        assertEquals("ưs", engine.processKey('s').text)

        engine.reset()
        assertEquals("t", engine.processKey('t').text)
        assertEquals("tư", engine.processKey('w').text)
        assertEquals("tứ", engine.processKey('s').text)
        assertEquals("tưs", engine.processKey('s').text)
    }

    @Test
    fun testPhonotacticsAndMedialCoda() {
        assertEquals("ba", engine.process("ba"))
        assertEquals("mẹ", engine.process("mej"))
        assertEquals("ăn", engine.process("awn"))
        assertEquals("em", engine.process("em"))
        assertEquals("hoa", engine.process("hoa"))
        assertEquals("hoá", engine.process("hoas"))
        assertEquals("ngang", engine.process("ngang"))
        assertEquals("nhưng", engine.process("nhuwng"))
        assertEquals("yêu", engine.process("yeeu"))
        assertEquals("yếu", engine.process("yeeus"))
        assertEquals("uyên", engine.process("uyeen"))
        assertEquals("uyển", engine.process("uyeenr"))
        assertEquals("quyền", engine.process("quyeenf"))
        assertEquals("tuyết", engine.process("tuyeets"))
        assertEquals("tuyết", engine.process("tuyeest"))
        assertEquals("tuyét", engine.process("tuyest"))
        assertEquals("thuyết", engine.process("thuyeest"))
        assertEquals("thuyét", engine.process("thuyest"))
        assertEquals("chuyện", engine.process("chuyeenj"))
        assertEquals("chuyẹn", engine.process("chuyenj"))
        assertEquals("khuyên", engine.process("khuyeen"))
        assertEquals("khuyến", engine.process("khuyeens"))
        assertEquals("khuyén", engine.process("khuyesn"))
    }

    @Test
    fun testDoongAndSoocAndCoong() {
        // d d o o o -> đoo (hủy ô -> oo giữ nguyên đ)
        assertEquals("đoo", engine.process("ddooo"))
        assertEquals("đoong", engine.process("ddooong"))
        assertEquals("đoòng", engine.process("ddooongf"))
        assertEquals("đoóng", engine.process("ddooongs"))
        assertEquals("đooc", engine.process("ddoooc"))
        assertEquals("đoóc", engine.process("ddooocs"))
        assertEquals("đoọc", engine.process("ddooocj"))

        // sooc, soóc, soọc
        assertEquals("soo", engine.process("sooo"))
        assertEquals("sooc", engine.process("soooc"))
        assertEquals("soóc", engine.process("sooocs"))
        assertEquals("soọc", engine.process("sooocj"))

        // coong, coóng, coòng
        assertEquals("coo", engine.process("cooo"))
        assertEquals("coong", engine.process("cooong"))
        assertEquals("coóng", engine.process("cooongs"))
        assertEquals("coòng", engine.process("cooongf"))
    }

    @Test
    fun testFreeTransformAfterValidCoda() {
        // dung + w -> dưng, dung + w + f -> dừng
        assertEquals("dung", engine.process("dung"))
        assertEquals("dùng", engine.process("dungf"))
        assertEquals("dưng", engine.process("dungw"))
        assertEquals("dừng", engine.process("dungwf"))
        assertEquals("dứng", engine.process("dungws"))
        assertEquals("dựng", engine.process("dungwj"))

        // ban + a -> bân, ban + w -> băn
        assertEquals("bân", engine.process("bana"))
        assertEquals("bấn", engine.process("banas"))
        assertEquals("băn", engine.process("banw"))
        assertEquals("bắn", engine.process("banws"))
        assertEquals("bằn", engine.process("banwf"))

        // duong + w -> dương
        assertEquals("dương", engine.process("duongw"))
        assertEquals("dướng", engine.process("duongws"))
        assertEquals("dường", engine.process("duongwf"))
    }

    @Test
    fun testPreserveComposedVietnameseOnInvalidFollowUpKeys() {
        // t h u w a e -> thưa + e = thưae (không bị lùi về raw text thuwae)
        assertEquals("thưa", engine.process("thuwa"))
        assertEquals("thưae", engine.process("thuwae"))
        assertEquals("thưaeo", engine.process("thuwaeo"))

        // y r i -> ỷ + i = ỷi (không bị lùi về yri)
        assertEquals("ỷ", engine.process("yr"))
        assertEquals("ỷi", engine.process("yri"))
        assertEquals("ỷia", engine.process("yria"))

        // Các từ tiếng Việt khác khi gõ thêm ký tự không hợp lệ
        assertEquals("học", engine.process("hocj"))
        assertEquals("họct", engine.process("hocjt"))
        assertEquals("toán", engine.process("toans"))
        assertEquals("toánk", engine.process("toansk"))
        assertEquals("người", engine.process("nguoiwf"))
        assertEquals("ngườip", engine.process("nguoiwfp"))
        assertEquals("đoàn", engine.process("ddoanf"))
        assertEquals("đoànk", engine.process("ddoanfk"))
        // Gõ phím dấu thanh thay thế: ddoanf + x -> đoãn
        assertEquals("đoãn", engine.process("ddoanfx"))
    }

    @Test
    fun testIncrementalTypingWithUserReportedCases() {
        // Gõ từng phím: t -> h -> u -> w -> a -> e
        engine.reset()
        assertEquals("t", engine.processKey('t').text)
        assertEquals("th", engine.processKey('h').text)
        assertEquals("thu", engine.processKey('u').text)
        assertEquals("thư", engine.processKey('w').text)
        assertEquals("thưa", engine.processKey('a').text)
        assertEquals("thưae", engine.processKey('e').text)

        // Gõ từng phím: y -> r -> i
        engine.reset()
        assertEquals("y", engine.processKey('y').text)
        assertEquals("ỷ", engine.processKey('r').text)
        assertEquals("ỷi", engine.processKey('i').text)

        // Gõ t h a y a -> thây, t h a y a s -> thấy
        engine.reset()
        assertEquals("thay", engine.process("thay"))
        assertEquals("thây", engine.process("thaya"))
        assertEquals("thấy", engine.process("thayas"))

        // Gõ t h o i o -> thôi, t h o i o s -> thối
        engine.reset()
        assertEquals("thoi", engine.process("thoi"))
        assertEquals("thôi", engine.process("thoio"))
        assertEquals("thối", engine.process("thoios"))

        // Gõ a y a s -> ayas (v-c-v dừng biến đổi, không biến thành ayá)
        engine.reset()
        assertEquals("ay", engine.process("ay"))
        assertEquals("ây", engine.process("aya"))
        assertEquals("ấy", engine.process("ayas"))
    }

    @Test
    fun testSentenceProcessing() {
        val input = "Tooi ddi hocj veef"
        val expected = "Tôi đi học về"
        assertEquals(expected, engine.process(input))
    }

    @Test
    fun testVietnameseTransformationSpecificationRequirements() {
        // Yêu cầu 17.1: dung + f -> dùng (u = tiền nguyên âm, ng = âm cuối hợp lệ)
        assertEquals("dùng", engine.process("dungf"))

        // Yêu cầu 17.2: dung + w -> dưng
        assertEquals("dưng", engine.process("dungw"))

        // Yêu cầu 17.3: tieng (t + iê + ng) + f -> tiềng (trong quy ước gõ Telex: tieengf -> tiềng, tiengs -> tiéng/tiếng)
        assertEquals("tiềng", engine.process("tieengf"))
        assertEquals("tiếng", engine.process("tieengs"))

        // Yêu cầu 17.4: Tiền nguyên âm + phụ âm không hợp lệ + phím biến đổi -> không biến đổi, giữ nguyên
        // Ví dụ: du + k + f -> dukf (k không phải âm cuối hợp lệ của du, f giữ nguyên không quay về u)
        assertEquals("dukf", engine.process("dukf"))
        assertEquals("batkf", engine.process("batkf"))

        // Yêu cầu 17.5: [nguyên âm cũ] + [âm cuối] + [nguyên âm mới không phải biến đổi] + phím biến đổi -> không xuyên qua nguyên âm mới
        // Ví dụ: dung + a + f -> dungaf (không biến thành dũnga hoặc dùnga)
        assertEquals("dungaf", engine.process("dungaf"))
        assertEquals("dungof", engine.process("dungof"))
        assertEquals("tiềng", engine.process("tiengef"))

        // Cụm âm cuối nhiều ký tự: ng, nh, ch
        assertEquals("đoàng", engine.process("ddoangf"))
        assertEquals("bánh", engine.process("banhs"))
        assertEquals("sách", engine.process("sachs"))
        assertEquals("mạch", engine.process("machj"))

        // Cụm nguyên âm dài nhất: iêng, uông, ươn, uyên
        assertEquals("uống", engine.process("uoongs"))
        assertEquals("tiếng", engine.process("tieengs"))
        assertEquals("lượn", engine.process("luonwj"))
        assertEquals("thuyền", engine.process("thuyeenf"))

        // Không biến đổi xuyên qua âm tiết (khoảng trắng / ranh giới từ)
        assertEquals("dung f", engine.process("dung f"))
        assertEquals("học sinh", engine.process("hocj sinh"))
    }

    @Test
    fun testTonePositionMapCoverageAndValidity() {
        assertTrue(VietnamesePhonology.isValidRime("uyên"))
        assertTrue(VietnamesePhonology.isValidRime("ươm"))
        assertTrue(VietnamesePhonology.isValidRime("ương"))
        assertTrue(VietnamesePhonology.isValidRime("oang"))

        // Test qu and gi preprocessing
        assertEquals(1, VietnamesePhonology.findTonePosition("qu", "ua", false)) // qu + ua -> offset 1, idx 0 -> 1 (quá)
        assertEquals(1, VietnamesePhonology.findTonePosition("gi", "ia", false)) // gi + ia -> offset 1, idx 0 -> 1 (giá)
        assertEquals(1, VietnamesePhonology.findTonePosition("th", "uơ", false)) // uơ -> 1 in rime
    }

    @Test
    fun testTypingOrderFreedomToneBeforeAndAfter() {
        // Gõ dấu trước hoặc gõ biến đổi nguyên âm trước
        assertEquals("ngưới", engine.process("nguoisw"))
        assertEquals("người", engine.process("nguoifw"))
        assertEquals("người", engine.process("nguowif"))
        assertEquals("người", engine.process("ngươif"))

        // moiws / moisw -> mới
        assertEquals("mới", engine.process("moiws"))
        assertEquals("mới", engine.process("moisw"))
        assertEquals("chơi", engine.process("choiw"))
        assertEquals("lời", engine.process("loiwf"))
        assertEquals("gửi", engine.process("guiwr"))
        assertEquals("gửi", engine.process("guirw"))
        assertEquals("gứi", engine.process("guiws"))

        // Không tự động biến đổi nếu không có phím biến đổi tương ứng
        assertEquals("tiéng", engine.process("tiengs"))
        assertEquals("tiếng", engine.process("tieengs"))
        assertEquals("uóng", engine.process("uongs"))
        assertEquals("uống", engine.process("uoongs"))
        assertEquals("khuyét", engine.process("khuyets"))
        assertEquals("khuyết", engine.process("khuyeets"))
        assertEquals("thuyèn", engine.process("thuyenf"))
        assertEquals("thuyền", engine.process("thuyeenf"))

        // uây / uay
        assertEquals("khuáy", engine.process("khuays"))
        assertEquals("khuấy", engine.process("khuaays"))

        // uôi / uoi
        assertEquals("cuói", engine.process("cuois"))
        assertEquals("cuối", engine.process("cuoois"))

        // ươu / uou
        assertEquals("ruọu", engine.process("ruouj"))
        assertEquals("rượu", engine.process("ruoujw"))
        assertEquals("rượu", engine.process("ruouwj"))

        // iêu / ieu
        assertEquals("tiéu", engine.process("tieus"))
        assertEquals("tiếu", engine.process("tieeus"))

        // oă / oa + coda
        assertEquals("hoạc", engine.process("hoacj"))
        assertEquals("hoặc", engine.process("hoacjw"))
        assertEquals("hoặc", engine.process("hoawcj"))
    }

    @Test
    fun testUoUpgradeToUoWithCoda() {
        // uow -> uơ (open)
        assertEquals("thuơ", engine.process("thuow"))
        // thuơ + ng -> thương
        assertEquals("thương", engine.process("thuowng"))
        assertEquals("thương", engine.process("thuơng"))
        assertEquals("thươi", engine.process("thuơi"))
    }

    @Test
    fun testMoiws_shouldBeMoi() {
        val result = engine.process("moiws")
        assertEquals("mới", result)
    }

    @Test
    fun testThuyenef_shouldBeThuyen() {
        val result = engine.process("thuyenef")
        assertEquals("thuyền", result)
    }

    @Test
    fun testKeue_shouldBeKeu() {
        val result = engine.process("keue")
        assertEquals("kêu", result)
        assertEquals("kếu", engine.process("keues"))
    }

    @Test
    fun testNguoiws_shouldBeNguoi() {
        val result = engine.process("nguoiws")
        assertEquals("ngưới", result)
        assertEquals("người", engine.process("nguoifw"))
        assertEquals("người", engine.process("nguowif"))
    }

    @Test
    fun testOffglideAndCodaModifiers() {
        assertEquals("cây", engine.process("caya"))
        assertEquals("mây", engine.process("maya"))
        assertEquals("lây", engine.process("laya"))
        assertEquals("nâu", engine.process("naua"))
        assertEquals("tâu", engine.process("taua"))
        assertEquals("tôi", engine.process("toio"))
        assertEquals("sôi", engine.process("soio"))
        assertEquals("khôi", engine.process("khoio"))
        assertEquals("tiên", engine.process("tiene"))
        assertEquals("tiền", engine.process("tienef"))
        assertEquals("biến", engine.process("bienes"))
        assertEquals("khuyên", engine.process("khuyene"))
    }

    @Test
    fun testNoAutoRevert() {
        engine.reset()
        for (c in "tieeng") {
            engine.processKey(c)
        }
        val result1 = engine.processKey('k').text
        assertEquals("tiêngk", result1)

        // Backspace xóa 'k' còn "tiêng"
        val result2 = engine.backspace()
        assertEquals("tiêng", result2)

        // Gõ 's' -> "tiếng"
        val result3 = engine.processKey('s').text
        assertEquals("tiếng", result3)
    }

    @Test
    fun testTargetLetterTypeATransformations() {
        // w target in nucleus:
        assertEquals("mơi", engine.process("moiw"))
        assertEquals("mới", engine.process("moiws"))
        assertEquals("kêu", engine.process("keue"))
        assertEquals("thuyên", engine.process("thuyene"))
        assertEquals("thuyền", engine.process("thuyenef"))
        assertEquals("uơ", engine.process("uow"))
        assertEquals("ươi", engine.process("uoiw"))
        assertEquals("ươn", engine.process("uonw"))
        assertEquals("ương", engine.process("uongw"))
        assertEquals("khuây", engine.process("khuaya"))
    }

    @Test
    fun testDodoir() {
        assertEquals("đổi", engine.process("dodoir"))
    }

    @Test
    fun testUntoggleShift_Tenee() {
        assertEquals("tene", engine.process("tenee"))
    }

    @Test
    fun testUntoggleShift_Conoo() {
        assertEquals("cono", engine.process("conoo"))
    }

    @Test
    fun testUoOrderIndependence() {
        assertEquals("uơ", engine.process("uow"))
        assertEquals("ươ", engine.process("uwo"))
        assertEquals("ươ", engine.process("uwow"))
    }

    @Test
    fun testNoAutoRevertEver() {
        engine.reset()
        for (c in "tieeng") {
            engine.processKey(c)
        }
        val result1 = engine.processKey('k').text
        assertEquals("tiêngk", result1)

        val result2 = engine.backspace()
        assertEquals("tiêng", result2)

        val result3 = engine.processKey('s').text
        assertEquals("tiếng", result3)
    }

    @Test
    fun testForeignWordNotCorrupted_Deepseek() {
        val result = engine.reDerive("deepseek")
        assertEquals("deepseek", result)
    }

    @Test
    fun testUndoStack_Deepseel_Backspace() {
        engine.reset()
        for (c in "deepseel") {
            engine.processKey(c)
        }
        // Xóa 'l' phải phục hồi về trạng thái trước đó "dếpee"
        val afterDelL = engine.backspace()
        assertEquals("dếpee", afterDelL)

        // Gõ 'k' tiếp theo -> "dếpeek"
        val afterAddK = engine.processKey('k').text
        assertEquals("dếpeek", afterAddK)
    }

    @Test
    fun testUndoStack_StepByStepBackspaces() {
        engine.reset()
        for (c in "deepseel") {
            engine.processKey(c)
        }
        assertEquals("dếpee", engine.backspace())
        assertEquals("dếpe", engine.backspace())
        assertEquals("dếp", engine.backspace())
        assertEquals("dế", engine.backspace())
        assertEquals("d", engine.backspace())
        assertEquals("", engine.backspace())
    }

    @Test
    fun testCharacterByCharacterBackspace_Chuyen() {
        engine.reset()
        for (c in "chuyeenr") {
            engine.processKey(c)
        }
        // Lúc này từ hoàn thành là "chuyển"
        // Xóa lần 1: xóa 'n' -> còn "chuyể"
        assertEquals("chuyể", engine.backspace())
        // Xóa lần 2: xóa 'ể' -> còn "chuy"
        assertEquals("chuy", engine.backspace())
        // Xóa lần 3: xóa 'y' -> còn "chu"
        assertEquals("chu", engine.backspace())
        // Xóa lần 4: xóa 'u' -> còn "ch"
        assertEquals("ch", engine.backspace())
        // Xóa lần 5: xóa 'h' -> còn "c"
        assertEquals("c", engine.backspace())
        // Xóa lần 6: xóa 'c' -> còn ""
        assertEquals("", engine.backspace())
    }

    @Test
    fun testWordDeconstructionAtCursor() {
        val (canonical, snapshots) = engine.generateDeconstructedSnapshots("chuyển")
        assertEquals("chuyeenr", canonical)
        assertTrue(snapshots.isNotEmpty())
        assertEquals("chuyển", snapshots.last().displayText)
    }

    @Test
    fun testProcessStringDoesNotCorruptInteractiveState() {
        engine.reset()
        engine.processKey('d')
        engine.processKey('d')
        engine.processKey('a')
        engine.processKey('n')
        val lastResult = engine.processKey('g')
        assertEquals("đang", lastResult.text)

        // Gọi processString độc lập không được làm ảnh hưởng đến composingText hay undoStack
        val result = engine.process("tiếng việt")
        assertEquals("tiếng việt", result)

        // Trạng thái interactive vẫn phải nguyên vẹn là "đang"
        val afterDel = engine.backspace()
        assertEquals("đan", afterDel)
    }

    @Test
    fun testForeignWordNotCorrupted_Other() {
        assertEquals("keep", engine.reDerive("keep"))
        assertEquals("book", engine.reDerive("book"))
    }

    @Test
    fun testEnglishSafety() {
        assertEquals("vietnamese", engine.process("vietnamese"))
    }

    @Test
    fun testDirectWOption() {
        // By default directW is false: w -> ư
        engine.options.directW = false
        assertEquals("ư", engine.process("w"))
        assertEquals("ưa", engine.process("wa"))
        assertEquals("ưng", engine.process("wng"))

        // When directW is true: w -> w directly when starting or alone
        engine.options.directW = true
        assertEquals("w", engine.process("w"))
        assertEquals("wa", engine.process("wa"))
        assertEquals("win", engine.process("win"))
        assertEquals("www", engine.process("www"))

        // But vowel modifiers like aw -> ă, ow -> ơ, uw -> ư still work as expected
        assertEquals("ă", engine.process("aw"))
        assertEquals("ơ", engine.process("ow"))
        assertEquals("ư", engine.process("uw"))
        assertEquals("ăn", engine.process("awn"))
        assertEquals("ơn", engine.process("own"))
        assertEquals("ưng", engine.process("uwng"))
    }

    @Test
    fun testOldTonePlacementOption() {
        // By default (new style): tone mark placed on second vowel for oa, oe, uy (hoá, hoè, thuỷ)
        engine.options.oldTonePlacement = false
        assertEquals("ho\u00e1", engine.process("hoas")) // hoá (acute on a)
        assertEquals("ho\u00e8", engine.process("hoef")) // hoè (grave on e)
        assertEquals("thu\u1ef7", engine.process("thuyr")) // thuỷ (hook on y)
        assertEquals("thu\u00fd", engine.process("thuys")) // thuý (acute on y)
        assertEquals("qu\u00e1n", engine.process("quans")) // quán
        assertEquals("ho\u00e0n", engine.process("hoanf")) // hoàn (with coda -> on a)

        // Old style tone placement: tone mark placed on first vowel for open rime oa, oe, uy (hóa, hòe, thủy)
        engine.options.oldTonePlacement = true
        assertEquals("h\u00f3a", engine.process("hoas")) // hóa (acute on o)
        assertEquals("h\u00f2e", engine.process("hoef")) // hòe (grave on o)
        assertEquals("th\u1ee7y", engine.process("thuyr")) // thủy (hook on u)
        assertEquals("th\u00fay", engine.process("thuys")) // thúy (acute on u)
        assertEquals("ho\u00e0n", engine.process("hoanf")) // hoàn (with coda -> remains on a)
        assertEquals("to\u00e1n", engine.process("toans")) // toán (with coda -> remains on a)
    }

    @Test
    fun testNewlyAddedRimesAndIntermediateTones() {
        engine.options.oldTonePlacement = false
        assertEquals("hoen", engine.process("hoen"))
        assertEquals("ho\u00e8n", engine.process("hoenf")) // hoèn
        assertEquals("kho\u00e9t", engine.process("khoets")) // khoét
        assertEquals("lo\u00e9t", engine.process("loets")) // loét
        assertEquals("tu\u00fdp", engine.process("tuyps")) // tuýp
        assertEquals("tho\u1eaf", engine.process("thoaws")) // thoắ
        assertEquals("tho\u1eaft", engine.process("thoawst")) // thoắt
        assertEquals("khu\u1ea5", engine.process("khuaas")) // khuấ
        assertEquals("khu\u1ea5t", engine.process("khuaast")) // khuất
    }

    @Test
    // DISABLED — VietnameseLexicalParser removed:
    //     fun testVietnameseLexicalParserAndDeconstruction() {
    //         // 1. Phân tích từ "đường"
    //         val parsedDuong = VietnameseLexicalParser.parse("đường")
    //         assertEquals("đ", parsedDuong.onset)
    //         assertEquals("ươ", parsedDuong.nucleus)
    //         assertEquals("ng", parsedDuong.coda)
    //         assertEquals(Tone.GRAVE, parsedDuong.tone)
    //         assertEquals("dduwongf", parsedDuong.toCanonicalKeystrokes())
    // 
    //         // 2. Phân tích từ "toán"
    //         val parsedToan = VietnameseLexicalParser.parse("toán")
    //         assertEquals("t", parsedToan.onset)
    //         assertEquals("oa", parsedToan.nucleus)
    //         assertEquals("n", parsedToan.coda)
    //         assertEquals(Tone.ACUTE, parsedToan.tone)
    //         assertEquals("toans", parsedToan.toCanonicalKeystrokes())
    // 
    //         // 3. Phân tích từ "nghiêng"
    //         val parsedNghieng = VietnameseLexicalParser.parse("nghiêng")
    //         assertEquals("ngh", parsedNghieng.onset)
    //         assertEquals("iê", parsedNghieng.nucleus)
    //         assertEquals("ng", parsedNghieng.coda)
    //         assertEquals(Tone.NONE, parsedNghieng.tone)
    //         assertEquals("nghieeng", parsedNghieng.toCanonicalKeystrokes())
    // 
    //         // 4. Sinh chuỗi snapshot từng bước deconstruction
    //         val (raw, snapshots) = engine.generateDeconstructedSnapshots("đường")
    //         assertEquals("dduwongf", raw)
    //         assertTrue(snapshots.isNotEmpty())
    //         assertEquals("đường", snapshots.last().displayText)
    // 
    //         // 5. Kiểm thử hoàn tác từng ký tự hiển thị (grapheme) từ từ được phục dựng
    //         val composer = VietnameseComposer(engine.options)
    //         val lastResult = raw.map { composer.processKey(it) }.last()
    //         assertTrue(lastResult is CompositionResult.Update)
    //         assertEquals("đường", (lastResult as CompositionResult.Update).text.toString())
    // 
    //         // Xóa 'g' -> còn 'đườn'
    //         assertEquals("đườn", composer.backspace())
    //         // Xóa 'n' -> 'đườ'
    //         assertEquals("đườ", composer.backspace())
    //         // Xóa 'ờ' -> 'đư'
    //         assertEquals("đư", composer.backspace())
    //         // Xóa 'ư' -> 'đ'
    //         assertEquals("đ", composer.backspace())
    //         // Xóa 'đ' -> ''
    //         assertEquals("", composer.backspace())
    //     }

    @Test
    fun testQuOnsetTypingAndTone() {
        assertEquals("quéo", engine.process("queos"))
        assertEquals("quên", engine.process("queen"))
        assertEquals("quốc", engine.process("quoocs"))
        assertEquals("quw", engine.process("quw"))
        assertEquals("quwr", engine.process("quwr"))
        assertEquals("quơ", engine.process("quow"))
        assertEquals("quở", engine.process("quowr"))
        assertEquals("quý", engine.process("quys"))
        assertEquals("quảng", engine.process("quangr"))
        assertEquals("quậy", engine.process("quaayj"))
        assertEquals("quét", engine.process("quets"))
    }

    @Test
    fun testRimeAndToneLegality() {
        // Closed stop codas (-p, -t, -c, -ch) ONLY allow SẮC (s) and NẶNG (j)
        // Disallow HUYỀN (f), HỎI (r), NGÃ (x)
        assertEquals("át", engine.process("ats"))
        assertEquals("ạt", engine.process("atj"))
        assertEquals("atf", engine.process("atf"))
        assertEquals("atr", engine.process("atr"))
        assertEquals("atx", engine.process("atx"))

        assertEquals("áp", engine.process("aps"))
        assertEquals("ạp", engine.process("apj"))
        assertEquals("apf", engine.process("apf"))
        assertEquals("apr", engine.process("apr"))

        assertEquals("ác", engine.process("acs"))
        assertEquals("ạc", engine.process("acj"))
        assertEquals("acf", engine.process("acf"))

        assertEquals("ách", engine.process("achs"))
        assertEquals("ạch", engine.process("achj"))
        assertEquals("achf", engine.process("achf"))
        assertEquals("achr", engine.process("achr"))

        // Nasal/liquid codas (-m, -n, -ng, -nh) allow all 5 tones
        assertEquals("án", engine.process("ans"))
        assertEquals("àn", engine.process("anf"))
        assertEquals("ản", engine.process("anr"))
        assertEquals("ãn", engine.process("anx"))
        assertEquals("ạn", engine.process("anj"))

        assertEquals("áng", engine.process("angs"))
        assertEquals("àng", engine.process("angf"))
        assertEquals("ảng", engine.process("angr"))
        assertEquals("ãng", engine.process("angx"))
        assertEquals("ạng", engine.process("angj"))
    }

    @Test
    // DISABLED — VietnameseLexicalParser removed:
    //     fun testLexicalParserWithSymbolsAndDigits() {
    //         val p1 = VietnameseLexicalParser.parse("rosin")
    //         assertEquals("r", p1.onset)
    //         assertEquals("o", p1.nucleus)
    //         assertEquals("", p1.coda)
    //         assertEquals("sin", p1.rawSuffix)
    //         assertEquals(Tone.NONE, p1.tone)
    //         assertEquals("rosin", p1.toCanonicalKeystrokes())
    // 
    //         val p2 = VietnameseLexicalParser.parse("rosino18k")
    //         assertEquals("r", p2.onset)
    //         assertEquals("o", p2.nucleus)
    //         assertEquals("", p2.coda)
    //         assertEquals("sino18k", p2.rawSuffix)
    //         assertEquals(Tone.NONE, p2.tone)
    //         assertEquals("rosino18k", p2.toCanonicalKeystrokes())
    // 
    //         val p3 = VietnameseLexicalParser.parse("covid-19")
    //         assertEquals("c", p3.onset)
    //         assertEquals("o", p3.nucleus)
    //         assertEquals("", p3.coda)
    //         assertEquals("vid-19", p3.rawSuffix)
    //         assertEquals(Tone.NONE, p3.tone)
    //         assertEquals("covid-19", p3.toCanonicalKeystrokes())
    // 
    //         val p4 = VietnameseLexicalParser.parse("tiếng-việt")
    //         assertEquals("t", p4.onset)
    //         assertEquals("iê", p4.nucleus)
    //         assertEquals("ng", p4.coda)
    //         assertEquals(Tone.ACUTE, p4.tone)
    //         assertEquals("-viêt", p4.rawSuffix)
    //         assertEquals("tieengs-viêt", p4.toCanonicalKeystrokes())
    // 
    //         // Ensure non-Vietnamese words with rawSuffix return empty deconstruction snapshots
    //         val (canonicalRosino, snapshotsRosino) = engine.generateDeconstructedSnapshots("rosino")
    //         assertEquals("", canonicalRosino)
    //         assertTrue(snapshotsRosino.isEmpty())
    // 
    //         val (canonicalTien, snapshotsTien) = engine.generateDeconstructedSnapshots("tiến")
    //         assertEquals("tieens", canonicalTien)
    //         assertTrue(snapshotsTien.isNotEmpty())
    //     }

    @Test
    fun testBackspaceOnCommittedTextDoesNotMorphLetters() {
        // Typing rossino: r o s -> ró, s -> ros, i -> rosi, n -> rosin, o -> rosino
        assertEquals("rosino", engine.process("rossino"))
    }

    @Test
    fun testTypoRecoveryAndModifierReMorphing() {
        // User accidentally types nhanwn then a -> should recover and morph to nhân
        assertEquals("nhân", engine.process("nhanwna"))

        // nhanw (nhăn) then a -> nhân
        assertEquals("nhân", engine.process("nhanwa"))
        assertEquals("nhấn", engine.process("nhanwas"))
    }

    @Test
    fun testUyeAndUyeToneProgression() {
        assertEquals(2, VietnamesePhonology.findTonePosition("ch", "uye", false))
        assertEquals(2, VietnamesePhonology.findTonePosition("ch", "uyê", false))
        assertEquals(2, VietnamesePhonology.findTonePosition("ch", "uyên", false))
        assertEquals("chuyển", engine.process("churyeen"))
        assertEquals("chuyển", engine.process("chuyeenr"))
    }

    @Test
    fun testCompositionResultAndStateDrivenFlow() {
        engine.reset()
        val r1 = engine.processKey('v')
        assertTrue(r1 is CompositionResult.Update)
        assertEquals("v", (r1 as CompositionResult.Update).text.toString())

        val r2 = engine.processKey('i')
        assertTrue(r2 is CompositionResult.Update)
        assertEquals("vi", (r2 as CompositionResult.Update).text.toString())

        val r3 = engine.processKey('e')
        assertTrue(r3 is CompositionResult.Update)
        assertEquals("vie", (r3 as CompositionResult.Update).text.toString())

        val r4 = engine.processKey('e')
        assertTrue(r4 is CompositionResult.Update)
        assertEquals("viê", (r4 as CompositionResult.Update).text.toString())

        val r5 = engine.processKey('t')
        assertTrue(r5 is CompositionResult.Update)
        assertEquals("viêt", (r5 as CompositionResult.Update).text.toString())

        val r6 = engine.processKey('j')
        assertTrue(r6 is CompositionResult.Update)
        assertEquals("việt", (r6 as CompositionResult.Update).text.toString())

        val r7 = engine.processKey(' ')
        assertTrue(r7 is CompositionResult.CommitAndStartNew)
        assertEquals("việt", (r7 as CompositionResult.CommitAndStartNew).commitText)
        assertEquals(' ', r7.newChar)

        // Test Backspace Snapshot stack
        engine.reset()
        engine.processKey('t')
        engine.processKey('o')
        engine.processKey('o') // tô
        engine.processKey('i') // tôi
        engine.processKey('s') // tối
        assertEquals("tối", engine.toDisplayString())

        assertEquals("tố", engine.backspace())
        assertEquals("t", engine.backspace())
        assertEquals("", engine.backspace())
    }

    @Test
    fun testEditedVietnameseRecognizerClassification() {
        assertEquals(CompositionMode.LITERAL, EditedVietnameseRecognizer.classify("warm"))
        assertEquals(CompositionMode.LITERAL, EditedVietnameseRecognizer.classify("war"))
        assertEquals(CompositionMode.LITERAL, EditedVietnameseRecognizer.classify("work"))
        assertEquals(CompositionMode.LITERAL, EditedVietnameseRecognizer.classify("wor"))
        assertEquals(CompositionMode.LITERAL, EditedVietnameseRecognizer.classify("facebook"))
        assertEquals(CompositionMode.LITERAL, EditedVietnameseRecognizer.classify("xyz"))
        assertEquals(CompositionMode.LITERAL, EditedVietnameseRecognizer.classify("omo"))
        assertEquals(CompositionMode.LITERAL, EditedVietnameseRecognizer.classify("ana"))

        assertEquals(CompositionMode.VIETNAMESE, EditedVietnameseRecognizer.classify("việt"))
        assertEquals(CompositionMode.VIETNAMESE, EditedVietnameseRecognizer.classify("toán"))
        assertEquals(CompositionMode.VIETNAMESE, EditedVietnameseRecognizer.classify("thương"))
        assertEquals(CompositionMode.VIETNAMESE, EditedVietnameseRecognizer.classify("nghiêng"))
        assertEquals(CompositionMode.VIETNAMESE, EditedVietnameseRecognizer.classify("bàn"))
        assertEquals(CompositionMode.VIETNAMESE, EditedVietnameseRecognizer.classify("chào"))
    }

    @Test
    fun testWarmBackspaceWarnScenarioDoesNotProduceUan() {
        // Reproduce typing/adopting "warm", then backspace (deleting 'm'), then typing 'n' -> must be "warn", NOT "ửan"
        engine.reset()
        assertEquals(CompositionMode.LITERAL, EditedVietnameseRecognizer.classify("warm"))

        // Adopt/Hold "warm" as EDITED_LITERAL
        engine.isVietnamese = false
        engine.processKey('w')
        engine.processKey('a')
        engine.processKey('r')
        engine.processKey('m')
        assertEquals("warm", engine.toDisplayString())

        val backspaced = engine.backspace()
        assertEquals("war", backspaced)
        assertEquals(false, engine.isVietnamese)

        engine.processKey('n')
        assertEquals("warn", engine.toDisplayString())

        // Ensure subsequent modifier/tone letters don't transform into Vietnamese vowels
        engine.processKey('s')
        assertEquals("warns", engine.toDisplayString())
    }

    @Test
    fun testWorkBackspaceWordScenario() {
        // Reproduce typing/adopting "work", then backspace (deleting 'k'), then typing 'd' -> must be "word", NOT "wơd"
        engine.reset()
        engine.isVietnamese = false
        engine.processKey('w')
        engine.processKey('o')
        engine.processKey('r')
        engine.processKey('k')
        assertEquals("work", engine.toDisplayString())

        val backspaced = engine.backspace()
        assertEquals("wor", backspaced)
        assertEquals(false, engine.isVietnamese)

        engine.processKey('d')
        assertEquals("word", engine.toDisplayString())
    }

    @Test
    fun testVietnameseCompositionRemainsFunctionalAfterGuardRefactor() {
        // Typing "viê" + 't' + 'j' -> "việt", backspace -> "việ", backspace -> "vi", 'e' -> "vie", 'e' -> "viê", 't' -> "viêt", 'j' -> "việt"
        engine.reset()
        engine.processKey('v')
        engine.processKey('i')
        engine.processKey('e')
        engine.processKey('e')
        engine.processKey('t')
        engine.processKey('j')
        assertEquals("việt", engine.toDisplayString())

        val bs1 = engine.backspace()
        assertEquals("việ", bs1)

        val bs2 = engine.backspace()
        assertEquals("vi", bs2)

        engine.processKey('e')
        assertEquals("vie", engine.toDisplayString())

        engine.processKey('e')
        assertEquals("viê", engine.toDisplayString())

        engine.processKey('t')
        assertEquals("viêt", engine.toDisplayString())

        engine.processKey('j')
        assertEquals("việt", engine.toDisplayString())
    }

    @Test
    fun testVietnamesePhonologyTrieAndTonePosition() {
        // Test prefix lookups
        assertTrue(VietnamesePhonology.isValidPrefix("u"))
        assertTrue(VietnamesePhonology.isValidPrefix("uy"))
        assertTrue(VietnamesePhonology.isValidPrefix("uye"))
        assertTrue(VietnamesePhonology.isValidPrefix("uyê"))
        assertTrue(VietnamesePhonology.isValidPrefix("uyên"))
        assertTrue(VietnamesePhonology.isValidPrefix("uyet"))
        assertTrue(VietnamesePhonology.isValidPrefix("uơ"))
        assertTrue(VietnamesePhonology.isValidPrefix("ươ"))
        assertTrue(VietnamesePhonology.isValidPrefix("ương"))

        // Test complete rimes
        assertTrue(VietnamesePhonology.isCompleteRime("a"))
        assertTrue(VietnamesePhonology.isCompleteRime("uyên"))
        assertTrue(VietnamesePhonology.isCompleteRime("uơ"))
        assertTrue(VietnamesePhonology.isCompleteRime("ươ"))
        assertTrue(VietnamesePhonology.isCompleteRime("ương"))

        // Test stop coda identification
        assertTrue(VietnamesePhonology.isStopCoda("ac"))
        assertTrue(VietnamesePhonology.isStopCoda("at"))
        assertTrue(VietnamesePhonology.isStopCoda("ap"))
        assertTrue(VietnamesePhonology.isStopCoda("ach"))
        assertTrue(VietnamesePhonology.isStopCoda("uyet"))
        assertTrue(VietnamesePhonology.isStopCoda("ươc"))

        // Test tone positions: new style vs old style
        assertEquals(1, VietnamesePhonology.getTonePosition("oa", oldTonePlacement = false)) // hoá
        assertEquals(0, VietnamesePhonology.getTonePosition("oa", oldTonePlacement = true))  // hóa
        assertEquals(1, VietnamesePhonology.getTonePosition("uy", oldTonePlacement = false)) // thuỷ
        assertEquals(0, VietnamesePhonology.getTonePosition("uy", oldTonePlacement = true))  // thủy
        assertEquals(2, VietnamesePhonology.getTonePosition("uyên", oldTonePlacement = false)) // thuyền
        assertEquals(2, VietnamesePhonology.getTonePosition("uyên", oldTonePlacement = true))  // thuyền

        // qu / gi onset integration
        assertEquals(1, VietnamesePhonology.findTonePosition("qu", "ua", oldTonePlacement = false)) // quá
        assertEquals(1, VietnamesePhonology.findTonePosition("gi", "ia", oldTonePlacement = false)) // giá
    }

    @Test
    fun testGraphemeEditorCalculations() {
        assertEquals(1, GraphemeEditor.getBackwardGraphemeLength("hello"))
        assertEquals(0, GraphemeEditor.getBackwardGraphemeLength(""))
        assertEquals(2, GraphemeEditor.getBackwardGraphemeLength("😀"))
        assertEquals(1, GraphemeEditor.getForwardGraphemeLength("hello"))
        assertEquals(2, GraphemeEditor.getForwardGraphemeLength("😀"))

        assertEquals(4, GraphemeEditor.previousBoundary("hello", 5))
        assertEquals(0, GraphemeEditor.previousBoundary("😀", 2))
        assertEquals(5, GraphemeEditor.nextBoundary("hello", 4))
        assertEquals(2, GraphemeEditor.nextBoundary("😀", 0))

        val (deletedText, newCursor) = GraphemeEditor.deleteBackward("viet", 4)
        assertEquals("vie", deletedText)
        assertEquals(3, newCursor)

        val (forwardDelText, forwardCursor) = GraphemeEditor.deleteForward("viet", 0)
        assertEquals("iet", forwardDelText)
        assertEquals(0, forwardCursor)

        assertEquals("vie", GraphemeEditor.deleteLastGrapheme("viet"))
    }

    @Test
//     @org.junit.Test fun testVietnameseEditReducerBackspace() { // DISABLED — old API removed
//         val options = EngineOptions()
// 
//         // 1. Vietnamese word: "tiếng" at cursor 5 -> "tiến"
//         val res1 = VietnameseEditReducer.reduceBackspace("tiếng", 5, CompositionMode.VIETNAMESE, options)
//         assertEquals("tiến", res1.display)
//         assertEquals(4, res1.cursorInDisplay)
//         assertEquals(CompositionMode.VIETNAMESE, res1.ownership)
// 
//         // 2. Literal word: "warm" at cursor 4 -> "war"
//         val res2 = VietnameseEditReducer.reduceBackspace("warm", 4, CompositionMode.LITERAL, options)
//         assertEquals("war", res2.display)
//         assertEquals(3, res2.cursorInDisplay)
//         assertEquals(CompositionMode.LITERAL, res2.ownership)
// 
//         // 3. Middle cursor: "chuyển" cursor at 3 ("chu|yển") -> "ch|yển"
//         val res3 = VietnameseEditReducer.reduceBackspace("chuyển", 3, CompositionMode.VIETNAMESE, options)
//         assertEquals("chyển", res3.display)
//         assertEquals(2, res3.cursorInDisplay)
// 
//         // 4. Forward delete: "chuyển" cursor at 2 ("ch|uyển") -> delete forward 'u' -> "ch|yển"
//         val res4 = VietnameseEditReducer.reduceDeleteForward("chuyển", 2, CompositionMode.VIETNAMESE, options)
//         assertEquals("chyển", res4.display)
//         assertEquals(2, res4.cursorInDisplay)
//     }

    @Test
    fun testInputBoundaryClassification() {
        assertTrue(BoundaryClassifier.isBoundaryChar(' '))
        assertTrue(BoundaryClassifier.isBoundaryChar('.'))
        assertTrue(BoundaryClassifier.isBoundaryChar('?'))
        assertTrue(BoundaryClassifier.isBoundaryChar('!'))
        assertTrue(BoundaryClassifier.isBoundaryChar(','))
        assertEquals(InputBoundary.WHITESPACE, BoundaryClassifier.classify(' '))
        assertEquals(InputBoundary.SENTENCE_TERMINATOR, BoundaryClassifier.classify('.'))
        assertEquals(InputBoundary.NONE, BoundaryClassifier.classify('a'))

        // Boundary checks
        assertTrue(BoundaryClassifier.isBoundary("SPACE"))
        assertTrue(BoundaryClassifier.isBoundary("ENTER"))
        assertTrue(BoundaryClassifier.isBoundary(","))
        assertTrue(BoundaryClassifier.isBoundary("."))
        assertTrue(BoundaryClassifier.isBoundary("["))
        assertTrue(BoundaryClassifier.isBoundary("]"))
    }

    @Test
    fun testForeignWordTypingBackspaceNewChar() {
        // Test foreign word "link" -> backspace 'k' -> 'e' -> "line"
        engine.reset()
        assertEquals("l", engine.processKey('l').text)
        assertEquals("li", engine.processKey('i').text)
        assertEquals("lin", engine.processKey('n').text)
        assertEquals("link", engine.processKey('k').text)
        assertEquals("lin", engine.backspace())
        assertEquals("line", engine.processKey('e').text)

        // Test foreign word "chat" -> backspace 't' -> 'm' -> "cham"
        engine.reset()
        assertEquals("c", engine.processKey('c').text)
        assertEquals("ch", engine.processKey('h').text)
        assertEquals("cha", engine.processKey('a').text)
        assertEquals("chat", engine.processKey('t').text)
        assertEquals("cha", engine.backspace())
        assertEquals("cham", engine.processKey('m').text)
    }

    @Test
    @org.junit.Test fun testCursorMapper() { // DISABLED — old API removed
        val options = EngineOptions()
        // Word "toanf" -> "toàn"
        // raw: "toanf" (length 5), display: "toàn" (length 4)
        // display index: 0 ('t'), 1 ('o'), 2 ('à'), 3 ('n'), 4 (end)
        assertEquals(0, CursorMapper.displayToRaw("toanf", "toàn", 0))
        assertEquals(1, CursorMapper.displayToRaw("toanf", "toàn", 1))
        assertEquals(2, CursorMapper.displayToRaw("toanf", "toàn", 2))
        assertEquals(3, CursorMapper.displayToRaw("toanf", "toàn", 3))
        assertEquals(5, CursorMapper.displayToRaw("toanf", "toàn", 4))

        // Raw to display
        assertEquals(0, CursorMapper.rawToDisplay("toanf", 0))
        assertEquals(1, CursorMapper.rawToDisplay("toanf", 1))
        assertEquals(2, CursorMapper.rawToDisplay("toanf", 2))
        assertEquals(3, CursorMapper.rawToDisplay("toanf", 3))
        assertEquals(4, CursorMapper.rawToDisplay("toanf", 4))
        assertEquals(4, CursorMapper.rawToDisplay("toanf", 5))

        // Test "nguyeen" -> "nguyên"
        val mapping = CursorMapper.buildMapping("nguyeen", "nguyên", options = options)
        assertEquals(0, mapping.rawToDisplay(0))
        assertEquals(1, mapping.rawToDisplay(1))
        assertEquals(2, mapping.rawToDisplay(2))
        assertEquals(3, mapping.rawToDisplay(3))
        assertEquals(4, mapping.rawToDisplay(4))
        assertEquals(5, mapping.rawToDisplay(5))
        assertEquals(5, mapping.rawToDisplay(6))
        assertEquals(6, mapping.rawToDisplay(7))

        assertEquals(0, mapping.displayToRaw(0))
        assertEquals(1, mapping.displayToRaw(1))
        assertEquals(2, mapping.displayToRaw(2))
        assertEquals(3, mapping.displayToRaw(3))
        assertEquals(4, mapping.displayToRaw(4))
        assertEquals(5, mapping.displayToRaw(5))
        assertEquals(7, mapping.displayToRaw(6))

        // Literal mapping
        assertEquals(3, CursorMapper.displayToRaw("test", "test", 3, ownership = CompositionMode.LITERAL))
        assertEquals(3, CursorMapper.rawToDisplay("test", 3))
    }

    @Test
    // DISABLED — VietnameseLexicalParser removed:
    //     fun testVietnameseLexicalParserAnalyze() {
    //         val options = EngineOptions()
    // 
    //         // 1. Valid Vietnamese word: "đường"
    //         val resDuong = VietnameseLexicalParser.analyze("đường", options)
    //         assertTrue(resDuong.isValid)
    //         assertEquals(CompositionMode.VIETNAMESE, resDuong.ownership)
    //         assertEquals("đường", resDuong.display)
    //         assertEquals("dduwongf", resDuong.canonicalRaw.lowercase())
    //         assertEquals("đ", resDuong.parsed?.onset)
    //         assertEquals("ươ", resDuong.parsed?.nucleus)
    //         assertEquals("ng", resDuong.parsed?.coda)
    //         assertEquals(Tone.GRAVE, resDuong.parsed?.tone)
    // 
    //         // 2. Valid uppercase/mixed case: "Đường"
    //         val resCapDuong = VietnameseLexicalParser.analyze("Đường", options)
    //         assertTrue(resCapDuong.isValid)
    //         assertEquals("DD", resCapDuong.canonicalRaw.substring(0, 2))
    // 
    //         // 3. Non-Vietnamese / Literal word: "facebook"
    //         val resForeign = VietnameseLexicalParser.analyze("facebook", options)
    //         assertFalse(resForeign.isValid)
    //         assertEquals(CompositionMode.LITERAL, resForeign.ownership)
    //         assertEquals("facebook", resForeign.canonicalRaw)
    //         assertEquals("facebook", resForeign.display)
    // 
    //         // 4. Snapshots generated directly from analysis
    //         val (canonical, snaps) = VietnameseComposer(options).generateDeconstructedSnapshots(resDuong)
    //         assertTrue(canonical.isNotEmpty())
    //         assertTrue(snaps.isNotEmpty())
    //         assertEquals("đường", snaps.last().displayText)
    //     }

    @Test
    fun testVietnameseUnicodeAndCharUtils() {
        // VietnameseUnicode tests
        assertEquals('á', VietnameseUnicode.applyTone('a', Tone.ACUTE))
        assertEquals('À', VietnameseUnicode.applyTone('A', Tone.GRAVE))
        assertEquals('a', VietnameseUnicode.stripTone('á'))
        assertEquals('Ă', VietnameseUnicode.stripTone('Ắ'))
        assertEquals('a', VietnameseUnicode.stripDiacritics('ắ'))
        assertEquals('A', VietnameseUnicode.stripDiacritics('Ắ'))
        assertEquals('d', VietnameseUnicode.stripDiacritics('đ'))
        assertEquals('D', VietnameseUnicode.stripDiacritics('Đ'))
        assertEquals("duong", VietnameseUnicode.stripToneFromWord("đường").map { VietnameseUnicode.stripDiacritics(it) }.joinToString(""))

        // Tone placement index tests (single authority: VietnamesePhonology.findTonePosition)
        assertEquals(1, VietnamesePhonology.findTonePosition(onset = "t", rime = "oan", oldTonePlacement = false)) // toán -> 'a' (index 1)
        assertEquals(0, VietnamesePhonology.findTonePosition(onset = "h", rime = "oa", oldTonePlacement = true)) // hoà (old) -> 'o' (index 0)
        assertEquals(1, VietnamesePhonology.findTonePosition(onset = "h", rime = "oa", oldTonePlacement = false)) // hòa (new) -> 'a' (index 1)

        // VietnameseUnicode tests
        assertEquals("VIỆT", VietnameseUnicode.applyCasingFromRaw("việt", "VIET"))
        assertEquals("Việt", VietnameseUnicode.applyCasingFromRaw("việt", "Viet"))
        assertEquals("việt", VietnameseUnicode.applyCasingFromRaw("việt", "viet"))
        assertEquals("a", VietnameseUnicode.getBaseChar('ắ').toString())
        assertEquals("d", VietnameseUnicode.getBaseChar('Đ').toString())
        assertEquals("Hà Nội", VietnameseUnicode.normalizeNfc("Hà Nội"))
    }

    @Test
    fun testCompletedWordDetectorAndReDerive() {
        // Valid Vietnamese words with various onsets, nuclei, tones, and codas (-ng, -nh, -ch, -m, -p, -n, -t, -c)
        val validVietnameseWords = listOf(
            "thương", "không", "đường", "chương", "xuống", "đứng",
            "sách", "thuở", "nghiêng", "bàn", "chào", "toán", "việt",
            "nam", "học", "mát", "sáng", "làng"
        )
        for (w in validVietnameseWords) {
            assertTrue("Word '$w' must be classified as confident Vietnamese", EditedVietnameseRecognizer.canRecompose(w))
        }

        // Suspicious / Foreign / Non-Vietnamese words that must NOT be recomposed
        val foreignOrSuspiciousWords = listOf(
            "warm", "warn", "war", "omo", "ana", "test", "code", "key", "tax",
            "deepseek", "keep", "book", "facebook", "work", "wor", "xyz"
        )
        for (w in foreignOrSuspiciousWords) {
            assertFalse("Word '$w' must not be recognized as confident Vietnamese", EditedVietnameseRecognizer.canRecompose(w))
            assertEquals("Word '$w' must be preserved verbatim by reDerive", w, engine.reDerive(w))
        }
    }

    @Test
    // DISABLED — references removed API:
    //     fun testThuongBackspaceRecomposeSyllable() {
    //         // Test adopting "thương", backspacing to "thươn", then typing 'g' -> "thương"
    //         val analysis = VietnameseLexicalParser.analyze("thương")
    //         assertTrue(analysis.isValid)
    //         assertEquals(CompositionMode.VIETNAMESE, EditedVietnameseRecognizer.classify("thương"))
    // 
    // //         val (canonical, snaps) = VietnameseSnapshotBuilder.generate(analysis, engine.options)
    // //         engine.loadSyllable(analysis.syllableState, true)
    // //         assertEquals("thương", engine.toDisplayString())
    // //
    // //         val backspaced = engine.backspace()
    // //         assertEquals("thươn", backspaced)
    // //         assertEquals(true, engine.isVietnamese)
    // //
    // //         engine.processKey('g')
    // //         assertEquals("thương", engine.toDisplayString())
    // //     }
// 
//     @Test
//     fun testEditedVietnameseRecognizerCodaRules() {
//         // Valid coda words
//         assertTrue(EditedVietnameseRecognizer.canRecompose("thương")) // ng
//         assertTrue(EditedVietnameseRecognizer.canRecompose("toán"))   // n
//         assertTrue(EditedVietnameseRecognizer.canRecompose("làm"))    // m
//         assertTrue(EditedVietnameseRecognizer.canRecompose("bác"))    // c
//         assertTrue(EditedVietnameseRecognizer.canRecompose("bát"))    // t
//         assertTrue(EditedVietnameseRecognizer.canRecompose("bắp"))    // p
//         assertTrue(EditedVietnameseRecognizer.canRecompose("bách"))   // ch
//         assertTrue(EditedVietnameseRecognizer.canRecompose("bánh"))   // nh
// 
//         // Words with non-Vietnamese onsets or endings
//         assertFalse(EditedVietnameseRecognizer.canRecompose("warm")) // onset w
//         assertFalse(EditedVietnameseRecognizer.canRecompose("war"))  // onset w
//         assertFalse(EditedVietnameseRecognizer.canRecompose("work")) // onset w, coda k
//         assertFalse(EditedVietnameseRecognizer.canRecompose("car"))  // coda r
//         assertFalse(EditedVietnameseRecognizer.canRecompose("bus"))  // coda s
//     }

    @Test
    // DISABLED — references removed API:
    //     fun testWordAdoptionAndStepUndoChain() {
    //         val word = "tiếng"
    //         assertTrue(EditedVietnameseRecognizer.canRecompose(word))
    // //         val (canonical, snaps) = VietnameseSnapshotBuilder.generate(word, engine.options)
    // //         assertEquals("tieengs", canonical)
    // //         assertTrue(snaps.size >= 6)
    // //
    // //         engine.loadSyllable(snaps.last().state, true)
    // //         assertEquals("tiếng", engine.toDisplayString())
    // //
    // //         // Backspace step by step using VietnameseEditReducer
    // //         assertEquals("tiến", engine.backspace())
    // //         assertEquals("tiế", engine.backspace())
    // //         assertEquals("ti", engine.backspace())
    // //         assertEquals("t", engine.backspace())
    // //         assertEquals("", engine.backspace())
    // //     }
// 
//     @Test
//     fun testWordAdoptionToneModification() {
//         // Adopt "tiên", add tone 's' -> "tiến"
//         val (canonical, snaps) = VietnameseSnapshotBuilder.generate("tiên", engine.options)
//         engine.loadSyllable(snaps.last().state, true)
//         assertEquals("tiên", engine.toDisplayString())
// 
//         engine.processKey('s')
//         assertEquals("tiến", engine.toDisplayString())
// 
//         // Change tone to grave 'f' -> "tiền"
//         engine.processKey('f')
//         assertEquals("tiền", engine.toDisplayString())
//     }

    @Test
    fun testEscapedSequencesAndToneKey() {
        // chuww -> chuw, chuwws -> chuws (not chúw)
        assertEquals("chuw", engine.process("chuww"))
        assertEquals("chuws", engine.process("chuwws"))

        // uww -> uw, uwws -> uws
        assertEquals("uw", engine.process("uww"))
        assertEquals("uws", engine.process("uwws"))

        // ddd -> dd, ddds -> dds
        assertEquals("dd", engine.process("ddd"))
        assertEquals("dds", engine.process("ddds"))
    }

    @Test
    // DISABLED — VietnameseLexicalParser removed:
    //     fun testCursorAdoptionPhonologicalRules() {
    //         val analysis = VietnameseLexicalParser.analyze("bong", engine.options)
    //         assertTrue(analysis.isValid)
    //         val parsed = analysis.parsed
    //         assertNotNull(parsed)
    //         assertEquals("b", parsed!!.onset)
    //         assertEquals("o", parsed.nucleus)
    //         assertEquals("ng", parsed.coda)
    // 
    //         val onsetEnd = parsed.onset.length // 1
    //         // Offset 1 (after 'b'): wordCursorOffset (1) >= onsetEnd + 1 (2) is false (literal insertion 'bsong')
    //         val offsetAfterB = 1
    //         assertFalse(offsetAfterB >= onsetEnd + 1)
    // 
    //         // Offset 2 (after 'o'): wordCursorOffset (2) >= onsetEnd + 1 (2) is true (adopt -> 'bóng')
    //         val offsetAfterO = 2
    //         assertTrue(offsetAfterO >= onsetEnd + 1)
    // 
    //         // Offset 3 (after 'n'): wordCursorOffset (3) >= onsetEnd + 1 (2) is true (adopt -> 'bóng')
    //         val offsetAfterN = 3
    //         assertTrue(offsetAfterN >= onsetEnd + 1)
    // 
    //         // Offset 4 (after 'g', at end of word): isAtEnd is true (adopt -> 'bóng')
    //         val offsetAfterG = 4
    //         assertEquals(4, "bong".length)
    //         assertTrue(offsetAfterG == "bong".length)
    //     }

    @Test
    fun testStandardizedBackspaceVisualGraphemeReduction() {
        // "đường" -> "đườn" -> "đườ" -> "đư" -> "đ" -> ""
        engine.reset()
        for (c in "dduwongf") engine.processKey(c)
        assertEquals("đường", engine.toDisplayString())
        assertEquals("đườn", engine.backspace())
        assertEquals("đườ", engine.backspace())
        assertEquals("đư", engine.backspace())
        assertEquals("đ", engine.backspace())
        assertEquals("", engine.backspace())

        // "toán" -> "toá" -> "to" -> "t" -> ""
        engine.reset()
        for (c in "toans") engine.processKey(c)
        assertEquals("toán", engine.toDisplayString())
        assertEquals("toá", engine.backspace())
        assertEquals("to", engine.backspace())
        assertEquals("t", engine.backspace())
        assertEquals("", engine.backspace())

        // "việt" -> "việ" -> "vi" -> "v" -> ""
        engine.reset()
        for (c in "vieetj") engine.processKey(c)
        assertEquals("việt", engine.toDisplayString())
        assertEquals("việ", engine.backspace())
        assertEquals("vi", engine.backspace())
        assertEquals("v", engine.backspace())
        assertEquals("", engine.backspace())
    }

    @Test
    // DISABLED — references removed API:
    //     fun testAdoptAndContinueTypingOnExistingWords() {
    //         // Test lexical analysis of partial words/onsets
    //         val analysisD = VietnameseLexicalParser.analyze("đ")
    //         assertTrue(analysisD.isValid)
    //         assertEquals("dd", analysisD.canonicalRaw)
    // 
    //         val analysisV = VietnameseLexicalParser.analyze("v")
    //         assertTrue(analysisV.isValid)
    //         assertEquals("v", analysisV.canonicalRaw)
    // 
    //         val analysisVie = VietnameseLexicalParser.analyze("việ")
    //         assertTrue(analysisVie.isValid)
    //         assertEquals("vieej", analysisVie.canonicalRaw.lowercase())
    // 
    //         val analysisDuon = VietnameseLexicalParser.analyze("đườn")
    //         assertTrue(analysisDuon.isValid)
    // 
    //         // Test adopting and continuing typing with snapshot builder and engine
    // //         val (canonical, snaps) = VietnameseSnapshotBuilder.generate(analysisVie, EngineOptions())
    // //         assertTrue(snaps.isNotEmpty())
    // //         engine.reset()
    // //         engine.loadSyllable(snaps.last().state, true)
    // //         assertEquals("việt", engine.processKey('t').text)
    // //
    // //         // Test onset "đ" + typing "ang" -> "đang"
    // //         val (canonicalD, snapsD) = VietnameseSnapshotBuilder.generate(analysisD, EngineOptions())
    // //         engine.reset()
    // //         engine.loadSyllable(snapsD.last().state, true)
    // //         assertEquals("đa", engine.processKey('a').text)
    // //         assertEquals("đan", engine.processKey('n').text)
    // //         assertEquals("đang", engine.processKey('g').text)
    // //
    // //         // Test "đườn" + typing 'g' -> "đường"
    // //         val (canonicalDuon2, snapsDuon) = VietnameseSnapshotBuilder.generate(analysisDuon, EngineOptions())
    // //         engine.reset()
    // //         engine.loadSyllable(snapsDuon.last().state, true)
    // //         assertEquals("đường", engine.processKey('g').text)
    // //
    // //         // Test adopting literal / foreign / suggestion words like "confirm" and continuing typing
    // //         val syncBuf1 = VietnameseComposer.SyncResult()
    // //         engine.syncStateFromRaw("confirm", CompositionMode.LITERAL, syncBuf1)
    // //         val canonicalConfirm = syncBuf1.displayText
    // //         assertEquals("confirm", canonicalConfirm)
    // //         val syncBuf2 = VietnameseComposer.SyncResult()
    // //         engine.syncStateFromRaw("confirmf", CompositionMode.LITERAL, syncBuf2)
    // //         val canonicalConfirmF = syncBuf2.displayText
    // //         assertEquals("confirmf", canonicalConfirmF)
    // //
    // //         // Test Backspace on "thee" in LITERAL -> "the" promotes to VIETNAMESE -> typing 'e' -> "thê"
    // //         val reducedThee = VietnameseEditReducer.reduceBackspace(
    // //             currentDisplay = "thee",
    // //             cursorInDisplay = 4,
    // //             currentOwnership = CompositionMode.LITERAL,
    // //             options = EngineOptions()
    // //         )
    // //         assertEquals("the", reducedThee.display)
    // //         assertEquals(CompositionMode.VIETNAMESE, reducedThee.ownership)
    // //         engine.reset()
    // //         engine.loadSyllable(reducedThee.syllableState, true)
    // //         assertEquals("thê", engine.processKey('e').text)
    // //
    // //         // Test Backspace on "theo" -> "the" -> typing 'e' -> "thê"
    // //         val reducedTheo = VietnameseEditReducer.reduceBackspace(
    // //             currentDisplay = "theo",
    // //             cursorInDisplay = 4,
    // //             currentOwnership = CompositionMode.VIETNAMESE,
    // //             options = EngineOptions()
    // //         )
    // //         assertEquals("the", reducedTheo.display)
    // //         assertEquals(CompositionMode.VIETNAMESE, reducedTheo.ownership)
    // //         engine.reset()
    // //         engine.loadSyllable(reducedTheo.syllableState, true)
    // //         assertEquals("thê", engine.processKey('e').text)
    // //     }
// }
// 
// 
        //val (canonical, snaps) = VietnameseSnapshotBuilder.generate(analysisVie, EngineOptions())
        assertTrue(snaps.isNotEmpty())
        engine.reset()
        engine.loadSyllable(snaps.last().state, true)
        assertEquals("việt", engine.processKey('t').text)

        // Test onset "đ" + typing "ang" -> "đang"
//         val (canonicalD, snapsD) = VietnameseSnapshotBuilder.generate(analysisD, EngineOptions())
//         engine.reset()
//         engine.loadSyllable(snapsD.last().state, true)
//         assertEquals("đa", engine.processKey('a').text)
//         assertEquals("đan", engine.processKey('n').text)
//         assertEquals("đang", engine.processKey('g').text)
// 
//         // Test "đườn" + typing 'g' -> "đường"
//         val (canonicalDuon2, snapsDuon) = VietnameseSnapshotBuilder.generate(analysisDuon, EngineOptions())
//         engine.reset()
//         engine.loadSyllable(snapsDuon.last().state, true)
//         assertEquals("đường", engine.processKey('g').text)
// 
//         // Test adopting literal / foreign / suggestion words like "confirm" and continuing typing
//         val syncBuf1 = VietnameseComposer.SyncResult()
//         engine.syncStateFromRaw("confirm", CompositionMode.LITERAL, syncBuf1)
//         val canonicalConfirm = syncBuf1.displayText
//         assertEquals("confirm", canonicalConfirm)
//         val syncBuf2 = VietnameseComposer.SyncResult()
//         engine.syncStateFromRaw("confirmf", CompositionMode.LITERAL, syncBuf2)
//         val canonicalConfirmF = syncBuf2.displayText
//         assertEquals("confirmf", canonicalConfirmF)
// 
//         // Test Backspace on "thee" in LITERAL -> "the" promotes to VIETNAMESE -> typing 'e' -> "thê"
//         val reducedThee = VietnameseEditReducer.reduceBackspace(
//             currentDisplay = "thee",
//             cursorInDisplay = 4,
//             currentOwnership = CompositionMode.LITERAL,
//             options = EngineOptions()
//         )
//         assertEquals("the", reducedThee.display)
//         assertEquals(CompositionMode.VIETNAMESE, reducedThee.ownership)
//         engine.reset()
//         engine.loadSyllable(reducedThee.syllableState, true)
//         assertEquals("thê", engine.processKey('e').text)
// 
//         // Test Backspace on "theo" -> "the" -> typing 'e' -> "thê"
//         val reducedTheo = VietnameseEditReducer.reduceBackspace(
//             currentDisplay = "theo",
//             cursorInDisplay = 4,
//             currentOwnership = CompositionMode.VIETNAMESE,
//             options = EngineOptions()
//         )
//         assertEquals("the", reducedTheo.display)
//         assertEquals(CompositionMode.VIETNAMESE, reducedTheo.ownership)
//         engine.reset()
//         engine.loadSyllable(reducedTheo.syllableState, true)
//         assertEquals("thê", engine.processKey('e').text)
//     }
// }
// 
// 
        //val (canonicalD, snapsD) = VietnameseSnapshotBuilder.generate(analysisD, EngineOptions())
        engine.reset()
        engine.loadSyllable(snapsD.last().state, true)
        assertEquals("đa", engine.processKey('a').text)
        assertEquals("đan", engine.processKey('n').text)
        assertEquals("đang", engine.processKey('g').text)

        // Test "đườn" + typing 'g' -> "đường"
//         val (canonicalDuon2, snapsDuon) = VietnameseSnapshotBuilder.generate(analysisDuon, EngineOptions())
//         engine.reset()
//         engine.loadSyllable(snapsDuon.last().state, true)
//         assertEquals("đường", engine.processKey('g').text)
// 
//         // Test adopting literal / foreign / suggestion words like "confirm" and continuing typing
//         val syncBuf1 = VietnameseComposer.SyncResult()
//         engine.syncStateFromRaw("confirm", CompositionMode.LITERAL, syncBuf1)
//         val canonicalConfirm = syncBuf1.displayText
//         assertEquals("confirm", canonicalConfirm)
//         val syncBuf2 = VietnameseComposer.SyncResult()
//         engine.syncStateFromRaw("confirmf", CompositionMode.LITERAL, syncBuf2)
//         val canonicalConfirmF = syncBuf2.displayText
//         assertEquals("confirmf", canonicalConfirmF)
// 
//         // Test Backspace on "thee" in LITERAL -> "the" promotes to VIETNAMESE -> typing 'e' -> "thê"
//         val reducedThee = VietnameseEditReducer.reduceBackspace(
//             currentDisplay = "thee",
//             cursorInDisplay = 4,
//             currentOwnership = CompositionMode.LITERAL,
//             options = EngineOptions()
//         )
//         assertEquals("the", reducedThee.display)
//         assertEquals(CompositionMode.VIETNAMESE, reducedThee.ownership)
//         engine.reset()
//         engine.loadSyllable(reducedThee.syllableState, true)
//         assertEquals("thê", engine.processKey('e').text)
// 
//         // Test Backspace on "theo" -> "the" -> typing 'e' -> "thê"
//         val reducedTheo = VietnameseEditReducer.reduceBackspace(
//             currentDisplay = "theo",
//             cursorInDisplay = 4,
//             currentOwnership = CompositionMode.VIETNAMESE,
//             options = EngineOptions()
//         )
//         assertEquals("the", reducedTheo.display)
//         assertEquals(CompositionMode.VIETNAMESE, reducedTheo.ownership)
//         engine.reset()
//         engine.loadSyllable(reducedTheo.syllableState, true)
//         assertEquals("thê", engine.processKey('e').text)
//     }
// }
// 
// 
        //val (canonicalDuon2, snapsDuon) = VietnameseSnapshotBuilder.generate(analysisDuon, EngineOptions())
        engine.reset()
        engine.loadSyllable(snapsDuon.last().state, true)
        assertEquals("đường", engine.processKey('g').text)

        // Test adopting literal / foreign / suggestion words like "confirm" and continuing typing
        val syncBuf1 = VietnameseComposer.SyncResult()
        engine.syncStateFromRaw("confirm", CompositionMode.LITERAL, syncBuf1)
        val canonicalConfirm = syncBuf1.displayText
        assertEquals("confirm", canonicalConfirm)
        val syncBuf2 = VietnameseComposer.SyncResult()
        engine.syncStateFromRaw("confirmf", CompositionMode.LITERAL, syncBuf2)
        val canonicalConfirmF = syncBuf2.displayText
        assertEquals("confirmf", canonicalConfirmF)

        // Test Backspace on "thee" in LITERAL -> "the" promotes to VIETNAMESE -> typing 'e' -> "thê"
//         val reducedThee = VietnameseEditReducer.reduceBackspace(
//             currentDisplay = "thee",
//             cursorInDisplay = 4,
//             currentOwnership = CompositionMode.LITERAL,
//             options = EngineOptions()
//         )
//         assertEquals("the", reducedThee.display)
//         assertEquals(CompositionMode.VIETNAMESE, reducedThee.ownership)
//         engine.reset()
//         engine.loadSyllable(reducedThee.syllableState, true)
//         assertEquals("thê", engine.processKey('e').text)
// 
//         // Test Backspace on "theo" -> "the" -> typing 'e' -> "thê"
//         val reducedTheo = VietnameseEditReducer.reduceBackspace(
//             currentDisplay = "theo",
//             cursorInDisplay = 4,
//             currentOwnership = CompositionMode.VIETNAMESE,
//             options = EngineOptions()
//         )
//         assertEquals("the", reducedTheo.display)
//         assertEquals(CompositionMode.VIETNAMESE, reducedTheo.ownership)
//         engine.reset()
//         engine.loadSyllable(reducedTheo.syllableState, true)
//         assertEquals("thê", engine.processKey('e').text)
//     }
// }
// 
// 
        //val reducedThee = VietnameseEditReducer.reduceBackspace(
            currentDisplay = "thee",
            cursorInDisplay = 4,
            currentOwnership = CompositionMode.LITERAL,
            options = EngineOptions()
        )
        assertEquals("the", reducedThee.display)
        assertEquals(CompositionMode.VIETNAMESE, reducedThee.ownership)
        engine.reset()
        engine.loadSyllable(reducedThee.syllableState, true)
        assertEquals("thê", engine.processKey('e').text)

        // Test Backspace on "theo" -> "the" -> typing 'e' -> "thê"
//         val reducedTheo = VietnameseEditReducer.reduceBackspace(
//             currentDisplay = "theo",
//             cursorInDisplay = 4,
//             currentOwnership = CompositionMode.VIETNAMESE,
//             options = EngineOptions()
//         )
//         assertEquals("the", reducedTheo.display)
//         assertEquals(CompositionMode.VIETNAMESE, reducedTheo.ownership)
//         engine.reset()
//         engine.loadSyllable(reducedTheo.syllableState, true)
//         assertEquals("thê", engine.processKey('e').text)
//     }
// }
// 
// 
        //val reducedTheo = VietnameseEditReducer.reduceBackspace(
            currentDisplay = "theo",
            cursorInDisplay = 4,
            currentOwnership = CompositionMode.VIETNAMESE,
            options = EngineOptions()
        )
        assertEquals("the", reducedTheo.display)
        assertEquals(CompositionMode.VIETNAMESE, reducedTheo.ownership)
        engine.reset()
        engine.loadSyllable(reducedTheo.syllableState, true)
        assertEquals("thê", engine.processKey('e').text)
    }
}


