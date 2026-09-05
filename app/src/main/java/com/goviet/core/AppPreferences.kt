package com.goviet.core

import android.content.Context
import android.content.SharedPreferences
import com.goviet.keyboard.util.StringListSerializer

data class EngineConfig(
    val macroEnabled: Boolean = false,
    val alwaysMacro: Boolean = false,
    val autoCapitalize: Boolean = false,
    val useSyllableEngine: Boolean = true,
    val directW: Boolean = false,
    val oldTonePlacement: Boolean = false
)

object AppPreferences {
    private const val CURRENT_SCHEMA_VERSION = 1

    @Volatile
    private var isInitialized = false

    private lateinit var globalPrefs: SharedPreferences
    private lateinit var settingsPrefs: SharedPreferences
    private lateinit var clipboardPinsPrefs: SharedPreferences
    private lateinit var legacySettingsPrefs: SharedPreferences
    private lateinit var recentPrefs: SharedPreferences
    private lateinit var macroPrefs: SharedPreferences

    const val PREF_THEME_MODE = "pref_theme_mode"
    const val PREF_DARK_THEME = "pref_dark_theme"
    const val KEY_MACRO_DATA = "macro_data"

    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (!isInitialized) {
                appContext = context.applicationContext
                globalPrefs = appContext.getSharedPreferences("goviet_global_prefs", Context.MODE_PRIVATE)
                settingsPrefs = appContext.getSharedPreferences("goviet_settings", Context.MODE_PRIVATE)
                clipboardPinsPrefs = appContext.getSharedPreferences("goviet_clipboard_pins", Context.MODE_PRIVATE)
                legacySettingsPrefs = appContext.getSharedPreferences("goviet_legacy_settings", Context.MODE_PRIVATE)
                recentPrefs = appContext.getSharedPreferences("goviet_recent_prefs", Context.MODE_PRIVATE)
                macroPrefs = appContext.getSharedPreferences("goviet_macro_prefs", Context.MODE_PRIVATE)
                migrateIfNeeded()
                isInitialized = true
            }
        }
    }

    private fun migrateIfNeeded() {
        val currentVersion = globalPrefs.getInt("schema_version", 0)
        if (currentVersion < CURRENT_SCHEMA_VERSION) {
            // Migrate legacy settings if needed
            if (legacySettingsPrefs.all.isNotEmpty()) {
                val editor = settingsPrefs.edit()
                legacySettingsPrefs.all.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                    }
                }
                editor.apply()
                println("[AppPreferences] Migrated goviet_legacy_settings to goviet_settings successfully")
            }
            // Save schema version
            globalPrefs.edit().putInt("schema_version", CURRENT_SCHEMA_VERSION).apply()
        }
    }


    fun registerGlobalPrefsListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        globalPrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterGlobalPrefsListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        globalPrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun isThemeKey(key: String?): Boolean {
        return key == PREF_THEME_MODE || key == PREF_DARK_THEME
    }

    fun isMacroDataKey(key: String?): Boolean {
        return key == KEY_MACRO_DATA
    }

    fun getMacroData(): String? {
        return macroPrefs.getString(KEY_MACRO_DATA, null)
    }

    fun setMacroData(jsonStr: String) {
        macroPrefs.edit().putString(KEY_MACRO_DATA, jsonStr).apply()
    }

    fun registerMacroPrefsListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        macroPrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterMacroPrefsListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        macroPrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun registerSettingsPrefsListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterSettingsPrefsListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun getKeyStyle(): Int {
        return if (settingsPrefs.contains("key_style")) {
            settingsPrefs.getInt("key_style", 0)
        } else {
            if (settingsPrefs.getBoolean("key_borders", true)) 0 else 1
        }
    }

    fun setKeyStyle(style: Int) {
        settingsPrefs.edit()
            .putInt("key_style", style)
            .putBoolean("key_borders", style == 0)
            .apply()
    }



    fun getBottomPaddingLevel(): Int {
        return settingsPrefs.getInt("bottom_padding_level", 0)
    }

    fun setBottomPaddingLevel(level: Int) {
        settingsPrefs.edit()
            .putInt("bottom_padding_level", level)
            .apply()
    }

    fun getThemeMode(): String {
        return if (globalPrefs.contains("pref_theme_mode")) {
            globalPrefs.getString("pref_theme_mode", "system") ?: "system"
        } else if (globalPrefs.contains("pref_dark_theme")) {
            if (globalPrefs.getBoolean("pref_dark_theme", false)) "dark" else "light"
        } else {
            "system"
        }
    }

    fun setThemeMode(mode: String) {
        globalPrefs.edit()
            .putString("pref_theme_mode", mode)
            .putBoolean("pref_dark_theme", mode == "dark")
            .apply()
    }

    fun getLanguage(): String {
        return globalPrefs.getString("pref_language", "vi") ?: "vi"
    }



    fun getPinnedClipboardTexts(): Set<String> {
        return clipboardPinsPrefs.getStringSet("pinned_texts", emptySet()) ?: emptySet()
    }

    fun setPinnedClipboardTexts(texts: Set<String>) {
        clipboardPinsPrefs.edit().putStringSet("pinned_texts", texts).apply()
    }

    fun getRecentEmojisRaw(): String {
        return recentPrefs.getString("recent_emojis", "") ?: ""
    }

    fun setRecentEmojisRaw(raw: String) {
        recentPrefs.edit().putString("recent_emojis", raw).apply()
    }

    fun getRecentEmojis(): List<String> {
        return StringListSerializer.deserialize(getRecentEmojisRaw())
    }

    fun setRecentEmojis(list: List<String>) {
        setRecentEmojisRaw(StringListSerializer.serialize(list))
    }

    fun getRecentSymbolsRaw(): String {
        return recentPrefs.getString("recent_symbols", "") ?: ""
    }

    fun setRecentSymbolsRaw(raw: String) {
        recentPrefs.edit().putString("recent_symbols", raw).apply()
    }

    fun getRecentSymbols(): List<String> {
        return StringListSerializer.deserialize(getRecentSymbolsRaw())
    }

    fun setRecentSymbols(list: List<String>) {
        setRecentSymbolsRaw(StringListSerializer.serialize(list))
    }

    fun isDirectW(): Boolean {
        return settingsPrefs.getBoolean("direct_w", false)
    }

    fun setDirectW(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("direct_w", enabled).apply()
    }

    fun isOldTonePlacement(): Boolean {
        return settingsPrefs.getBoolean("old_tone_placement", false)
    }

    fun setOldTonePlacement(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("old_tone_placement", enabled).apply()
    }

    fun getEngineConfig(): EngineConfig {
        val macroEnabled = settingsPrefs.getBoolean("macro_enabled", true)
        val alwaysMacro = settingsPrefs.getBoolean("always_macro", true)
        val autoCapitalize = settingsPrefs.getBoolean("auto_capitalize", false)
        val useSyllableEngine = settingsPrefs.getBoolean("use_syllable_engine", true)
        val directW = settingsPrefs.getBoolean("direct_w", false)
        val oldTonePlacement = settingsPrefs.getBoolean("old_tone_placement", false)

        return EngineConfig(
            macroEnabled = macroEnabled,
            alwaysMacro = alwaysMacro,
            autoCapitalize = autoCapitalize,
            useSyllableEngine = useSyllableEngine,
            directW = directW,
            oldTonePlacement = oldTonePlacement
        )
    }

    fun setEngineConfig(config: EngineConfig) {
        settingsPrefs.edit().apply {
            putBoolean("macro_enabled", config.macroEnabled)
            putBoolean("always_macro", config.alwaysMacro)
            putBoolean("auto_capitalize", config.autoCapitalize)
            putBoolean("use_syllable_engine", config.useSyllableEngine)
            putBoolean("direct_w", config.directW)
            putBoolean("old_tone_placement", config.oldTonePlacement)
            apply()
        }
    }
}
