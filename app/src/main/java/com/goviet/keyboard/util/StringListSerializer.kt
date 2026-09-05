package com.goviet.keyboard.util

/**
 * Helper object to serialize and deserialize lists of strings (e.g. recent emojis, recent symbols).
 * Uses a null character (`\u0000`) as the primary delimiter to safely support elements containing commas,
 * while maintaining backward compatibility with legacy comma-separated values.
 */
object StringListSerializer {
    private const val NULL_DELIMITER = "\u0000"

    /**
     * Serializes a list of strings into a single delimited string.
     */
    fun serialize(list: List<String>): String {
        if (list.isEmpty()) return ""
        return list.joinToString(NULL_DELIMITER)
    }

    /**
     * Deserializes a raw string into a list of strings.
     * Supports null-character delimited strings as well as legacy comma-separated strings.
     */
    fun deserialize(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return when {
            raw.contains('\u0000') -> raw.split('\u0000')
            raw.contains('\u001F') -> raw.split('\u001F')
            else -> raw.split(",")
        }.filter { it.isNotEmpty() }
    }
}
