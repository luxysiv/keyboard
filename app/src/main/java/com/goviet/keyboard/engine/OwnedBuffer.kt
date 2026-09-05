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
class OwnedBuffer {
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

    fun charAt(index: Int): Char = chars[index]

    /** Produce a String ONLY when required by the InputConnection API. */
    fun toStringVal(): String = String(chars, 0, len)

    override fun toString(): String = toStringVal()

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

// Extension-free overload to append another OwnedBuffer
