package com.goviet.keyboard.engine

/**
 * OwnedBuffer:
 * A reusable character buffer that eliminates per-keystroke
 * String/StringBuilder allocations in the IME hot path.
 *
 * Callers append characters to the buffer, then call `toStringVal()`
 * (which performs a single String allocation ONLY when a String is
 * actually required by the InputConnection API). Frequent internal
 * transformations run directly against the underlying CharArray.
 *
 * Thread-confined: NOT thread-safe. Each IME thread owns its buffer.
 */
class OwnedBuffer : CharSequence {
    var chars = CharArray(64)
        private set
    var len = 0
        private set

    /** Reset length to zero without deallocating the backing array. */
    fun clear() {
        len = 0
    }

    /** Reset and release the backing array (batch cleanup). */
    fun reset() {
        chars = CharArray(64)
        len = 0
    }

    fun isEmpty(): Boolean = len == 0
    fun isNotEmpty(): Boolean = len > 0

    fun append(c: Char) {
        if (len == chars.size) grow()
        chars[len++] = c
    }

    fun append(s: CharSequence, start: Int = 0, end: Int = s.length) {
        val add = end - start
        if (add <= 0) return
        ensureCapacity(len + add)
        for (i in start until end) chars[len++] = s[i]
    }

    fun append(a: CharArray, start: Int = 0, end: Int = a.size) {
        val add = end - start
        if (add <= 0) return
        ensureCapacity(len + add)
        for (i in start until end) chars[len++] = a[i]
    }

    fun append(other: OwnedBuffer) {
        if (other.isEmpty()) return
        ensureCapacity(len + other.len)
        for (i in 0 until other.len) chars[len++] = other.chars[i]
    }

    fun append(other: OwnedBuffer, start: Int, end: Int) {
        val add = end - start
        if (add <= 0 || other.isEmpty()) return
        ensureCapacity(len + add)
        for (i in start until end) chars[len++] = other.chars[i]
    }

    fun setLength(newLen: Int) {
        if (newLen in 0..len) len = newLen
    }

    override val length: Int get() = len

    override operator fun get(index: Int): Char = chars[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        String(chars, startIndex, endIndex - startIndex)

    /** Produce a String ONLY when required by the InputConnection API. */
    fun toStringVal(): String = String(chars, 0, len)

    override fun toString(): String = toStringVal()


    /**
     * Apply Vietnamese casing in-place: reads [raw] for case flags,
     * transforms the receiver's chars directly.  Single String allocation
     * only at the end via [toStringVal] — the IME caller owns the
     * conversion to InputConnection.setComposingText.
     */
    fun applyCasingFromRaw(raw: CharSequence) {
        if (len == 0 || raw.length == 0) return

        var hasUpperInRaw = false
        var isAllUpper = true
        val rawLen = raw.length
        for (i in 0 until rawLen) {
            val c = raw[i]
            val isLtr = c in 'a'..'z' || c in 'A'..'Z' || c.lowercaseChar() != c.uppercaseChar()
            if (isLtr) {
                if (c.isUpperCase()) hasUpperInRaw = true
                else isAllUpper = false
            }
        }

        if (!hasUpperInRaw) {
            for (i in 0 until len) chars[i] = chars[i].lowercaseChar()
            return
        }

        if (isAllUpper) {
            for (i in 0 until len) chars[i] = chars[i].uppercaseChar()
            return
        }

        val isFirstUpper = raw[0].isUpperCase()
        var rawIdx = 0
        var outIdx = 0
        while (outIdx < len) {
            val char = chars[outIdx]
            val baseCompiled = VietnameseUnicode.getBaseChar(char)
            var matchedChar: Char? = null
            var tempIdx = rawIdx
            while (tempIdx < rawLen) {
                val rawChar = raw[tempIdx]
                if (VietnameseUnicode.getBaseChar(rawChar) == baseCompiled) {
                    matchedChar = rawChar
                    rawIdx = tempIdx + 1
                    break
                }
                tempIdx++
            }
            chars[outIdx] = when {
                matchedChar != null && matchedChar.isUpperCase() -> char.uppercaseChar()
                matchedChar == null && isFirstUpper && outIdx == 0 -> char.uppercaseChar()
                matchedChar == null -> char.lowercaseChar()
                else -> char.lowercaseChar()
            }
            outIdx++
        }
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity <= chars.size) return
        var newSize = chars.size * 2
        while (newSize < minCapacity) newSize *= 2
        val grown = CharArray(newSize)
        System.arraycopy(chars, 0, grown, 0, len)
        chars = grown
    }

    private fun grow() {
        val grown = CharArray(chars.size * 2)
        System.arraycopy(chars, 0, grown, 0, chars.size)
        chars = grown
    }
}
