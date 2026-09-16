package com.goviet.keyboard.engine

/**
 * VietnameseUnicode:
 * Unicode tables, diacritics stripping/application, and Vietnamese tone target placement.
 */
object VietnameseUnicode {

    private val TONE_ROWS = arrayOf(
        "aáàảãạ", "ăắằẳẵặ", "âấầẩẫậ",
        "eéèẻẽẹ", "êếềểễệ", "iíìỉĩị",
        "oóòỏõọ", "ôốồổỗộ", "ơớờởỡợ",
        "uúùủũụ", "ưứừửữự", "yýỳỷỹỵ"
    )

    private const val TONE_COUNT = 6
    private const val TABLE_LIMIT = 0x2000

    /** Toned vowel → base vowel (identity for every other char). */
    private val STRIP_TONE = CharArray(TABLE_LIMIT) { it.toChar() }.apply {
        for (row in TONE_ROWS) {
            val base = row[0]
            val upper = base.uppercaseChar()
            for (t in 1 until row.length) {
                this[row[t].code] = base
                this[row[t].uppercaseChar().code] = upper
            }
        }
    }

    /** Base vowel → row index in [TONE_ROWS]; -1 for non-bases. */
    private val BASE_INDEX = ByteArray(TABLE_LIMIT) { -1 }.apply {
        for (i in TONE_ROWS.indices) {
            this[TONE_ROWS[i][0].code] = i.toByte()
            this[TONE_ROWS[i][0].uppercaseChar().code] = i.toByte()
        }
    }

    /** [TONED] / [TONED_UPPER] index = row * [TONE_COUNT] + tone.index. */
    private val TONED = buildToneTable(upper = false)
    private val TONED_UPPER = buildToneTable(upper = true)

    private fun buildToneTable(upper: Boolean): CharArray = CharArray(TONE_ROWS.size * TONE_COUNT) { i ->
        val c = TONE_ROWS[i / TONE_COUNT][i % TONE_COUNT]
        if (upper) c.uppercaseChar() else c
    }

    fun applyTone(char: Char, tone: Tone): Char {
        val code = char.code
        if (code < TABLE_LIMIT) {
            val base = BASE_INDEX[code]
            if (base >= 0) {
                val i = base * TONE_COUNT + tone.index
                return if (char.isUpperCase()) TONED_UPPER[i] else TONED[i]
            }
        }
        return if (tone == Tone.NONE) stripTone(char) else char
    }

    fun stripTone(char: Char): Char {
        val code = char.code
        return if (code < TABLE_LIMIT) STRIP_TONE[code] else char
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

    /**
     * Inverse of [applyTone] — the tone that produced [char], or NONE when the
     * char carries no tone.  Single source: the [applyTone] table.
     */
    fun toneOf(char: Char): Tone {
        val base = stripTone(char)
        if (base == char) return Tone.NONE
        for (t in Tone.values()) {
            if (t != Tone.NONE && applyTone(base, t) == char) return t
        }
        return Tone.NONE
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

    fun applyCasingFromRaw(compiled: CharSequence, raw: CharSequence): String {
        val compiledLen = compiled.length
        val rawLen = raw.length
        if (rawLen == 0) return compiled.toString()
        if (compiledLen == 0) return ""

        var hasUpperInRaw = false
        var isAllUpper = true
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

        val buf = ensureBuffer(compiledLen)
        var offset = 0

        if (!hasUpperInRaw) {
            for (i in 0 until compiledLen) {
                buf[offset++] = compiled[i].lowercaseChar()
            }
            return normalizeIfNeeded(buf, offset)
        }

        if (isAllUpper) {
            for (i in 0 until compiledLen) {
                buf[offset++] = compiled[i].uppercaseChar()
            }
            return normalizeIfNeeded(buf, offset)
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
        return normalizeIfNeeded(buf, offset)
    }

    fun applyCasingFromRaw(compiled: String, raw: String): String =
        applyCasingFromRaw(compiled as CharSequence, raw as CharSequence)

    private fun normalizeIfNeeded(buf: CharArray, len: Int): String {
        var needNfc = false
        for (i in 0 until len) {
            val c = buf[i].code
            if (c in 0x300..0x36F || c in 0x1AB0..0x1AFF || c in 0x20D0..0x20FF) {
                needNfc = true
                break
            }
        }
        val s = String(buf, 0, len)
        return if (needNfc) java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC) else s
    }
}
