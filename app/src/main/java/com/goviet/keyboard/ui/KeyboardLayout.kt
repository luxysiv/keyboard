package com.goviet.keyboard.ui

/**
 * Pure layout logic for keyboard construction and label resolution.
 * No Android View dependencies — safe to use from any context.
 */
object KeyboardLayout {

    fun buildKeyRows(
        mode: String,
        shiftState: Int,
        languageMode: String,
        imeOptions: Int,
        inputType: Int
    ): Pair<List<Key>, List<List<Key>>> {
        val keys = mutableListOf<Key>()

        when (mode) {
            "ABC" -> buildAlphanumericKeys(keys, shiftState)
            else -> buildSymbolKeys(keys, mode)
        }

        resolveLabels(keys, shiftState, languageMode, imeOptions, inputType)
        return Pair(keys, getRows(keys, mode))
    }

    fun resolveLabels(
        keys: List<Key>,
        shiftState: Int,
        languageMode: String,
        imeOptions: Int,
        inputType: Int
    ) {
        for (key in keys) {
            when {
                key.code == "SPACE" -> {
                    key.label = if (languageMode == "VIE") "‹   VI   ›" else "‹   EN   ›"
                }
                key.code == "SHIFT" -> {
                    key.label = when (shiftState) {
                        1 -> "⬆"
                        2 -> "⇪"
                        else -> "⇧"
                    }
                }
                key.code == "ENTER" -> {
                    key.label = KeyboardUtils.getEnterTextLabel(imeOptions, inputType)
                }
                key.code == "BACKSPACE" || key.code == "SYM" || key.code == "," ||
                key.code == "SWITCH_PAGE" || key.code == "ABC" || key.code == "TPAD" ||
                key.code == "SYM_PICKER" -> { /* static labels */ }
                key.code.length == 1 && key.code[0] in '0'..'9' -> {
                    key.label = key.code
                }
                else -> {
                    key.label = if (shiftState > 0) key.code.uppercase() else key.code
                    key.secondaryLabel = getSecondaryLabel(key.code, shiftState > 0)
                    key.longPressOptions = getLongPressOptions(key.code, shiftState > 0)
                }
            }
        }
    }

    private fun buildAlphanumericKeys(keys: MutableList<Key>, shiftState: Int) {
        val row0 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        val row3 = listOf("SHIFT", "z", "x", "c", "v", "b", "n", "m", "BACKSPACE")

        for (digit in row0) {
            keys.add(Key(
                code = digit,
                label = digit,
                secondaryLabel = symbolLongPressMap[digit]?.firstOrNull(),
                longPressOptions = symbolLongPressMap[digit]
            ))
        }
        for (letter in row1) {
            val opts = getLongPressOptions(letter, shiftState > 0)
            keys.add(Key(
                code = letter,
                label = letter,
                secondaryLabel = getSecondaryLabel(letter, shiftState > 0),
                longPressOptions = opts,
                longPressDefaultIndex = 0
            ))
        }
        for (letter in row2) {
            val opts = getLongPressOptions(letter, shiftState > 0)
            keys.add(Key(
                code = letter,
                label = letter,
                secondaryLabel = getSecondaryLabel(letter, shiftState > 0),
                longPressOptions = opts,
                longPressDefaultIndex = 0
            ))
        }
        for (letter in row3) {
            when (letter) {
                "SHIFT" -> keys.add(Key(code = "SHIFT", label = "\u21E7", isFunctional = true, weight = 1.4f))
                "BACKSPACE" -> keys.add(Key(code = "BACKSPACE", label = "\u232B", isFunctional = true, weight = 1.4f))
                else -> {
                    val opts = getLongPressOptions(letter, shiftState > 0)
                    keys.add(Key(
                        code = letter,
                        label = letter,
                        secondaryLabel = getSecondaryLabel(letter, shiftState > 0),
                        longPressOptions = opts,
                        longPressDefaultIndex = 0
                    ))
                }
            }
        }

        // Row 4 — space row
        keys.add(Key(code = "SYM", label = "?123", isFunctional = true, weight = 1.4f))
        keys.add(Key(
            code = ",",
            label = ",",
            isFunctional = true,
            weight = 1.2f,
            longPressOptions = symbolLongPressMap[","]
        ))
        keys.add(Key(code = "SPACE", label = "Space", weight = 5.5f))
        keys.add(Key(
            code = ".",
            label = ".",
            secondaryLabel = "\u2026",
            isFunctional = true,
            weight = 1.2f,
            longPressOptions = symbolLongPressMap["."]
        ))
        keys.add(Key(code = "ENTER", label = "Enter", isSpecialEnter = true, isFunctional = true, weight = 1.4f))
    }

    private fun buildSymbolKeys(keys: MutableList<Key>, mode: String) {
        val isPage2 = mode == "SYM2"

        val row0 = if (!isPage2) {
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        } else {
            listOf("₫", "€", "$", "£", "¥", "₩", "¢", "₹", "₽", "¤")
        }
        val row1 = if (!isPage2) {
            listOf("!", "@", "#", "$", "%", "&", "*", "(", ")", "_")
        } else {
            listOf("±", "−", "×", "÷", "=", "≠", "≈", "≤", "≥", "∞")
        }
        val row2 = if (!isPage2) {
            listOf("+", "=", "-", "<", ">", "/", "\\", "|", "~", "\"")
        } else {
            listOf("√", "∑", "∫", "π", "Δ", "•", "…", "©", "®", "™")
        }
        val row3 = if (!isPage2) {
            listOf("`", ":", ";", "'", "?", ".", "…")
        } else {
            listOf("°", "℃", "℉", "§", "¶", "↑", "↓")
        }

        for (sym in row0) addSymbolKey(keys, sym)
        for (sym in row1) addSymbolKey(keys, sym)
        for (sym in row2) addSymbolKey(keys, sym)

        val toggleLabel = if (!isPage2) "=\\" else "?123"
        keys.add(Key(code = "SWITCH_PAGE", label = toggleLabel, isFunctional = true, weight = 1.4f))

        for (sym in row3) addSymbolKey(keys, sym)

        keys.add(Key(code = "BACKSPACE", label = "\u232B", isFunctional = true, weight = 1.4f))

        // Bottom control row
        keys.add(Key(code = "ABC", label = "ABC", isFunctional = true, weight = 1.4f))
        keys.add(Key(
            code = ",",
            label = ",",
            isFunctional = true,
            weight = 1.2f,
            longPressOptions = symbolLongPressMap[","]
        ))
        keys.add(Key(code = "SPACE", label = "Space", weight = 5.5f))
        keys.add(Key(
            code = ".",
            label = ".",
            secondaryLabel = "\u2026",
            isFunctional = true,
            weight = 1.2f,
            longPressOptions = symbolLongPressMap["."]
        ))
        keys.add(Key(code = "ENTER", label = "Enter", isSpecialEnter = true, isFunctional = true, weight = 1.4f))
    }
    private fun addSymbolKey(keys: MutableList<Key>, sym: String) {
        keys.add(Key(
            code = sym,
            label = sym,
            secondaryLabel = symbolLongPressMap[sym]?.firstOrNull(),
            longPressOptions = symbolLongPressMap[sym]
        ))
    }

    fun getRows(keys: List<Key>, mode: String): List<List<Key>> {
        val rows = mutableListOf<List<Key>>()
        if (keys.isEmpty()) return rows

        if (mode == "ABC") {
            if (keys.size >= 10) rows.add(keys.subList(0, 10))
            if (keys.size >= 20) rows.add(keys.subList(10, 20))
            if (keys.size >= 29) rows.add(keys.subList(20, 29))
            if (keys.size >= 38) rows.add(keys.subList(29, 38))
            if (keys.size > 38) rows.add(keys.subList(38, keys.size))
        } else {
            if (keys.size >= 10) rows.add(keys.subList(0, 10))
            if (keys.size >= 20) rows.add(keys.subList(10, 20))
            if (keys.size >= 30) rows.add(keys.subList(20, 30))
            if (keys.size >= 39) rows.add(keys.subList(30, 39))
            if (keys.size > 39) rows.add(keys.subList(39, keys.size))
        }
        return rows
    }

    private fun getSecondaryLabel(letter: String, isShifted: Boolean): String? {
        val label = secondaryKeyMap[letter] ?: return null
        return if (isShifted) label.uppercase() else label
    }

    private fun getLongPressOptions(letter: String, isShifted: Boolean): List<String>? {
        val list = longPressSymbolMap[letter] ?: secondaryKeyMap[letter]?.let { listOf(it) } ?: return null
        return if (isShifted) list.map { it.uppercase() } else list
    }
}
