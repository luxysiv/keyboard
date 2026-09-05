package com.goviet.keyboard.engine

import android.content.Context
import com.goviet.core.AppPreferences
import org.json.JSONArray
import org.json.JSONObject

data class MacroEntry(
    val trigger: String,
    val expansion: String
)

class MacroStore(initialEntries: List<MacroEntry> = emptyList()) {
    private val entries = mutableMapOf<String, String>()

    var version: Int = 0
        private set

    init {
        for (entry in initialEntries) {
            val cleanTrigger = entry.trigger.trim().lowercase()
            if (cleanTrigger.isNotEmpty()) {
                entries[cleanTrigger] = entry.expansion
            }
        }
    }

    fun lookup(key: String): String? {
        return entries[key.lowercase()]
    }

    fun addOrUpdate(trigger: String, expansion: String) {
        val cleanTrigger = trigger.trim().lowercase()
        if (cleanTrigger.isNotEmpty()) {
            entries[cleanTrigger] = expansion
            version++
        }
    }

    fun remove(trigger: String): Boolean {
        val removed = entries.remove(trigger.trim().lowercase()) != null
        if (removed) {
            version++
        }
        return removed
    }

    fun all(): List<MacroEntry> {
        return entries.map { (k, v) -> MacroEntry(k, v) }
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun clear() {
        if (entries.isNotEmpty()) {
            entries.clear()
            version++
        }
    }

    val size: Int
        get() = entries.size
}

class MacroRepository(private val context: Context) {
    fun loadMacroStore(): MacroStore {
        AppPreferences.init(context)
        val jsonStr = AppPreferences.getMacroData() ?: return MacroStore()
        val entries = mutableListOf<MacroEntry>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val trigger = obj.optString("trigger", "")
                val expansion = obj.optString("expansion", "")
                if (trigger.isNotEmpty()) {
                    entries.add(MacroEntry(trigger, expansion))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return MacroStore(entries)
    }

    fun saveMacroStore(store: MacroStore) {
        AppPreferences.init(context)
        val array = JSONArray()
        for (entry in store.all()) {
            val obj = JSONObject()
            obj.put("trigger", entry.trigger)
            obj.put("expansion", entry.expansion)
            array.put(obj)
        }
        AppPreferences.setMacroData(array.toString())
    }
}
