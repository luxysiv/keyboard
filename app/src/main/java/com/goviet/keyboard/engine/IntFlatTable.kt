package com.goviet.keyboard.engine

/**
 * Minimal open-addressing flat table shared by [RimeMap] and [OnsetMap] —
 * Fibonacci-multiply hash + linear probing, primitive arrays, key 0 reserved
 * as the empty-slot sentinel.  The two maps used to carry a byte-for-byte copy
 * of this table; now the insert/lookup mechanics live here in one place.
 */
internal class IntFlatTable(bits: Int) {
    private val mask: Int = (1 shl bits) - 1
    val keys = IntArray(1 shl bits)
    val data = ByteArray(1 shl bits)

    private fun slot(key: Int): Int = (key * -0x61c88647).toInt() and mask

    /** Insert a new key (caller guarantees it is not present yet) — returns the slot. */
    fun insert(key: Int, value: Int): Int {
        var i = slot(key)
        while (keys[i] != 0) i = (i + 1) and mask
        keys[i] = key
        data[i] = value.toByte()
        return i
    }

    /** Insert, OR-ing [value] into the entry when the key already exists. */
    fun insertOr(key: Int, value: Int) {
        var i = slot(key)
        while (true) {
            if (keys[i] == key) { data[i] = (data[i].toInt() or value).toByte(); return }
            if (keys[i] == 0) { keys[i] = key; data[i] = value.toByte(); return }
            i = (i + 1) and mask
        }
    }

    /** Insert only when the key is absent (prefix rows that may collide). */
    fun insertIfAbsent(key: Int, value: Int) {
        var i = slot(key)
        while (true) {
            if (keys[i] == key) return
            if (keys[i] == 0) { keys[i] = key; data[i] = value.toByte(); return }
            i = (i + 1) and mask
        }
    }

    /** Slot index for [key], or -1 when absent. */
    fun find(key: Int): Int {
        var i = slot(key)
        while (true) {
            if (keys[i] == key) return i
            if (keys[i] == 0) return -1
            i = (i + 1) and mask
        }
    }
}
