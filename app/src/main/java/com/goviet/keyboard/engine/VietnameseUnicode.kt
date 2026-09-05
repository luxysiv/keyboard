package com.goviet.keyboard.engine

/**
 * VietnameseUnicode:
 * Unicode tables, diacritics stripping/application, and Vietnamese tone target placement.
 */
object VietnameseUnicode {

    fun applyTone(char: Char, tone: Tone): Char {
        if (tone == Tone.NONE) return stripTone(char)
        val isUpper = char.isUpperCase()
        val base = char.lowercaseChar()
        val result = when (base) {
            'a' -> when (tone) { Tone.ACUTE -> 'á'; Tone.GRAVE -> 'à'; Tone.HOOK -> 'ả'; Tone.TILDE -> 'ã'; Tone.DOT -> 'ạ'; else -> 'a' }
            'ă' -> when (tone) { Tone.ACUTE -> 'ắ'; Tone.GRAVE -> 'ằ'; Tone.HOOK -> 'ẳ'; Tone.TILDE -> 'ẵ'; Tone.DOT -> 'ặ'; else -> 'ă' }
            'â' -> when (tone) { Tone.ACUTE -> 'ấ'; Tone.GRAVE -> 'ầ'; Tone.HOOK -> 'ẩ'; Tone.TILDE -> 'ẫ'; Tone.DOT -> 'ậ'; else -> 'â' }
            'e' -> when (tone) { Tone.ACUTE -> 'é'; Tone.GRAVE -> 'è'; Tone.HOOK -> 'ẻ'; Tone.TILDE -> 'ẽ'; Tone.DOT -> 'ẹ'; else -> 'e' }
            'ê' -> when (tone) { Tone.ACUTE -> 'ế'; Tone.GRAVE -> 'ề'; Tone.HOOK -> 'ể'; Tone.TILDE -> 'ễ'; Tone.DOT -> 'ệ'; else -> 'ê' }
            'i' -> when (tone) { Tone.ACUTE -> 'í'; Tone.GRAVE -> 'ì'; Tone.HOOK -> 'ỉ'; Tone.TILDE -> 'ĩ'; Tone.DOT -> 'ị'; else -> 'i' }
            'o' -> when (tone) { Tone.ACUTE -> 'ó'; Tone.GRAVE -> 'ò'; Tone.HOOK -> 'ỏ'; Tone.TILDE -> 'õ'; Tone.DOT -> 'ọ'; else -> 'o' }
            'ô' -> when (tone) { Tone.ACUTE -> 'ố'; Tone.GRAVE -> 'ồ'; Tone.HOOK -> 'ổ'; Tone.TILDE -> 'ỗ'; Tone.DOT -> 'ộ'; else -> 'ô' }
            'ơ' -> when (tone) { Tone.ACUTE -> 'ớ'; Tone.GRAVE -> 'ờ'; Tone.HOOK -> 'ở'; Tone.TILDE -> 'ỡ'; Tone.DOT -> 'ợ'; else -> 'ơ' }
            'u' -> when (tone) { Tone.ACUTE -> 'ú'; Tone.GRAVE -> 'ù'; Tone.HOOK -> 'ủ'; Tone.TILDE -> 'ũ'; Tone.DOT -> 'ụ'; else -> 'u' }
            'ư' -> when (tone) { Tone.ACUTE -> 'ứ'; Tone.GRAVE -> 'ừ'; Tone.HOOK -> 'ử'; Tone.TILDE -> 'ữ'; Tone.DOT -> 'ự'; else -> 'ư' }
            'y' -> when (tone) { Tone.ACUTE -> 'ý'; Tone.GRAVE -> 'ỳ'; Tone.HOOK -> 'ỷ'; Tone.TILDE -> 'ỹ'; Tone.DOT -> 'ỵ'; else -> 'y' }
            else -> char
        }
        return if (isUpper) result.uppercaseChar() else result
    }

    fun stripTone(char: Char): Char {
        return when (char) {
            'á', 'à', 'ả', 'ã', 'ạ' -> 'a'
            'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ' -> 'ă'
            'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ' -> 'â'
            'é', 'è', 'ẻ', 'ẽ', 'ẹ' -> 'e'
            'ế', 'ề', 'ể', 'ễ', 'ệ' -> 'ê'
            'í', 'ì', 'ỉ', 'ĩ', 'ị' -> 'i'
            'ó', 'ò', 'ỏ', 'õ', 'ọ' -> 'o'
            'ố', 'ồ', 'ổ', 'ỗ', 'ộ' -> 'ô'
            'ớ', 'ờ', 'ở', 'ỡ', 'ợ' -> 'ơ'
            'ú', 'ù', 'ủ', 'ũ', 'ụ' -> 'u'
            'ứ', 'ừ', 'ử', 'ữ', 'ự' -> 'ư'
            'ý', 'ỳ', 'ỷ', 'ỹ', 'ỵ' -> 'y'

            'Á', 'À', 'Ả', 'Ã', 'Ạ' -> 'A'
            'Ắ', 'Ằ', 'Ẳ', 'Ẵ', 'Ặ' -> 'Ă'
            'Ấ', 'Ầ', 'Ẩ', 'Ẫ', 'Ậ' -> 'Â'
            'É', 'È', 'Ẻ', 'Ẽ', 'Ẹ' -> 'E'
            'Ế', 'Ề', 'Ể', 'Ễ', 'Ệ' -> 'Ê'
            'Í', 'Ì', 'Ỉ', 'Ĩ', 'Ị' -> 'I'
            'Ó', 'Ò', 'Ỏ', 'Õ', 'Ọ' -> 'O'
            'Ố', 'Ồ', 'Ổ', 'Ỗ', 'Ộ' -> 'Ô'
            'Ớ', 'Ờ', 'Ở', 'Ỡ', 'Ợ' -> 'Ơ'
            'Ú', 'Ù', 'Ủ', 'Ũ', 'Ụ' -> 'U'
            'Ứ', 'Ừ', 'Ử', 'Ữ', 'Ự' -> 'Ư'
            'Ý', 'Ỳ', 'Ỷ', 'Ỹ', 'Ỵ' -> 'Y'
            else -> char
        }
    }

    fun stripDiacritics(char: Char): Char {
        return when (stripTone(char)) {
            'ă', 'â' -> 'a'
            'Ă', 'Â' -> 'A'
            'ê' -> 'e'
            'Ê' -> 'E'
            'ô', 'ơ' -> 'o'
            'Ô', 'Ơ' -> 'O'
            'ư' -> 'u'
            'Ư' -> 'U'
            'đ' -> 'd'
            'Đ' -> 'D'
            else -> stripTone(char)
        }
    }

    fun stripToneFromWord(word: String): String {
        val len = word.length
        if (len == 0) return ""
        val chars = CharArray(len)
        var changed = false
        for (i in 0 until len) {
            val c = word[i]
            val stripped = stripTone(c)
            chars[i] = stripped
            if (c != stripped) changed = true
        }
        return if (changed) String(chars) else word
    }


    // ==========================================
    // CASING & NFC UTILITIES (merged from VietnameseCharUtils)
    // ==========================================

    private val caseBuffer = ThreadLocal.withInitial { CharArray(32) }

    private fun ensureBuffer(size: Int): CharArray {
        val current = caseBuffer.get() ?: return CharArray(size.coerceAtLeast(64))
        return if (current.size >= size) current else {
            val grown = CharArray(size.coerceAtLeast(64))
            caseBuffer.set(grown)
            grown
        }
    }

    fun normalizeNfc(text: String): String {
        if (text.isEmpty()) return text
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
    }

    fun getBaseChar(c: Char): Char {
        return stripDiacritics(stripTone(c)).lowercaseChar()
    }

    fun applyCasingFromRaw(compiled: String, raw: String): String {
        if (compiled.isEmpty() || raw.isEmpty()) return normalizeNfc(compiled)

        var hasUpperInRaw = false
        var isAllUpper = true
        val rawLen = raw.length
        for (i in 0 until rawLen) {
            val c = raw[i]
            val isLtr = c in 'a'..'z' || c in 'A'..'Z' || c.lowercaseChar() != c.uppercaseChar()
            if (isLtr) {
                if (c.isUpperCase()) {
                    hasUpperInRaw = true
                } else {
                    isAllUpper = false
                }
            }
        }

        val compiledLen = compiled.length
        val buf = ensureBuffer(compiledLen)
        var offset = 0

        if (!hasUpperInRaw) {
            for (i in 0 until compiledLen) {
                buf[offset++] = compiled[i].lowercaseChar()
            }
            return normalizeNfc(String(buf, 0, offset))
        }

        if (isAllUpper) {
            for (i in 0 until compiledLen) {
                buf[offset++] = compiled[i].uppercaseChar()
            }
            return normalizeNfc(String(buf, 0, offset))
        }

        val isFirstUpper = raw[0].isUpperCase()
        var rawIdx = 0

        for (i in 0 until compiledLen) {
            val char = compiled[i]
            val baseCompiled = getBaseChar(char)

            var matchedChar: Char? = null
            var tempIdx = rawIdx
            while (tempIdx < rawLen) {
                val rawChar = raw[tempIdx]
                if (getBaseChar(rawChar) == baseCompiled) {
                    matchedChar = rawChar
                    rawIdx = tempIdx + 1
                    break
                }
                tempIdx++
            }

            if (matchedChar != null) {
                if (matchedChar.isUpperCase()) {
                    buf[offset++] = char.uppercaseChar()
                } else if (char.isUpperCase()) {
                    buf[offset++] = char
                } else {
                    buf[offset++] = char.lowercaseChar()
                }
            } else {
                if (char.isUpperCase()) {
                    buf[offset++] = char
                } else if (isFirstUpper && i == 0) {
                    buf[offset++] = char.uppercaseChar()
                } else {
                    buf[offset++] = char.lowercaseChar()
                }
            }
        }
        return normalizeNfc(String(buf, 0, offset))
    }
}
