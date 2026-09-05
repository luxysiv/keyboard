package com.goviet.keyboard.ui

import android.content.Context
import android.graphics.Color
import com.goviet.R

data class KeyboardTheme(
    val textColor: Int,
    val subTextColor: Int,
    val keyBgColor: Int,
    val keyPressedBgColor: Int,
    val functionalKeyBgColor: Int,
    val functionalKeyPressedBgColor: Int,
    val activeAccentColor: Int,
    val isDark: Boolean
) {
    companion object {
        fun resolve(context: Context, isDark: Boolean, themeMode: String): ResolvedTheme {
            var backgroundColor = context.getColor(if (isDark) R.color.keyboard_bg_dark else R.color.keyboard_bg_light)
            var textColor = context.getColor(if (isDark) R.color.keyboard_text_dark else R.color.keyboard_text_light)
            var accentColor = context.getColor(if (isDark) R.color.keyboard_accent_dark else R.color.keyboard_accent_light)
            var headerBg = context.getColor(if (isDark) R.color.keyboard_header_bg_dark else R.color.keyboard_header_bg_light)
            var keyBgColor = context.getColor(if (isDark) R.color.keyboard_key_bg_dark else R.color.keyboard_key_bg_light)
            var functionalKeyBgColor = context.getColor(if (isDark) R.color.keyboard_functional_key_bg_dark else R.color.keyboard_functional_key_bg_light)
            var keyPressedBgColor = context.getColor(if (isDark) R.color.keyboard_key_pressed_bg_dark else R.color.keyboard_key_pressed_bg_light)
            var functionalKeyPressedBgColor = context.getColor(if (isDark) R.color.keyboard_functional_key_pressed_bg_dark else R.color.keyboard_functional_key_pressed_bg_light)

            if (themeMode == "dynamic" && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                try {
                    accentColor = context.getColor(if (isDark) android.R.color.system_accent1_300 else android.R.color.system_accent1_600)
                    backgroundColor = context.getColor(if (isDark) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_50)
                    textColor = context.getColor(if (isDark) android.R.color.system_neutral1_50 else android.R.color.system_neutral1_900)
                    headerBg = context.getColor(if (isDark) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_100)
                    keyBgColor = context.getColor(if (isDark) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_100)
                    functionalKeyBgColor = context.getColor(if (isDark) android.R.color.system_neutral1_700 else android.R.color.system_neutral1_200)
                    keyPressedBgColor = context.getColor(if (isDark) android.R.color.system_neutral1_700 else android.R.color.system_neutral1_200)
                    functionalKeyPressedBgColor = context.getColor(if (isDark) android.R.color.system_neutral1_600 else android.R.color.system_neutral1_300)
                } catch (e: Exception) {
                    // fallback to default values
                }
            }

            val subTextColor = Color.argb(128, Color.red(textColor), Color.green(textColor), Color.blue(textColor))

            val theme = KeyboardTheme(
                textColor = textColor,
                subTextColor = subTextColor,
                keyBgColor = keyBgColor,
                keyPressedBgColor = keyPressedBgColor,
                functionalKeyBgColor = functionalKeyBgColor,
                functionalKeyPressedBgColor = functionalKeyPressedBgColor,
                activeAccentColor = accentColor,
                isDark = isDark
            )

            return ResolvedTheme(theme, backgroundColor, headerBg)
        }
    }
}

data class ResolvedTheme(
    val theme: KeyboardTheme,
    val backgroundColor: Int,
    val headerBg: Int
)
