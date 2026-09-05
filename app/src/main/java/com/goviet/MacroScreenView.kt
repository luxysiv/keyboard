package com.goviet

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.goviet.core.AppPreferences
import com.goviet.databinding.FragmentMacroBinding
import com.goviet.keyboard.engine.MacroEntry
import com.goviet.keyboard.engine.MacroRepository

import com.goviet.core.density

class MacroScreenView(private val activity: MainActivity) : ScreenView {

    private val binding: FragmentMacroBinding

    init {
        val inflater = LayoutInflater.from(activity)
        binding = FragmentMacroBinding.inflate(inflater)

        val lang = AppPreferences.getLanguage()

        binding.toolbar.title = Loc.get("macro_title", lang)
        binding.toolbar.setNavigationOnClickListener {
            activity.showScreen("home")
        }

        rebuildList()
    }

    override fun getView(): View {
        return binding.root
    }

    private fun rebuildList() {
        val container = binding.macroContainer
        container.removeAllViews()

        val inflater = LayoutInflater.from(activity)
        val lang = AppPreferences.getLanguage()

        val density = activity.density
        val isDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val primaryColor = activity.getColorFromAttr(com.google.android.material.R.attr.colorPrimary)
        val surfaceVariantColor = activity.getColorFromAttr(com.google.android.material.R.attr.colorSurfaceVariant)
        val onSurfaceVariantColor = activity.getColorFromAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val dividerColor = if (isDark) 0xFF334155.toInt() else 0xFFE5E7EB.toInt()
        val rippleColorVal = if (isDark) 0x1AE5E7EB.toInt() else 0x141E2431.toInt()

        fun addHeader(title: String) {
            container.addCategoryHeader(inflater, title)
            val headerView = container.getChildAt(container.childCount - 1)
            val params = headerView.layoutParams as LinearLayout.LayoutParams
            params.setMargins(
                (24 * density).toInt(),
                (16 * density).toInt(),
                (24 * density).toInt(),
                (4 * density).toInt()
            )
            headerView.layoutParams = params
        }

        fun createSectionCard(): LinearLayout {
            return LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f * density
                    setColor(surfaceVariantColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(
                        (16 * density).toInt(),
                        (8 * density).toInt(),
                        (16 * density).toInt(),
                        (8 * density).toInt()
                    )
                }
            }
        }

        // 1. Add new macro action
        addHeader(if (lang == "vi") "Thao tác" else "Actions")
        val actionCard = createSectionCard()
        val addItemView = inflater.inflate(R.layout.item_preference, actionCard, false)
        addItemView.findViewById<TextView>(R.id.pref_title).text = if (lang == "vi") "Thêm từ viết tắt mới" else "Add New Shortcut"
        addItemView.findViewById<TextView>(R.id.pref_summary).apply {
            text = if (lang == "vi") "Nhấn để tạo phím tắt gõ nhanh mới" else "Tap to create a new shortcut"
            visibility = View.VISIBLE
        }
        addItemView.findViewById<ImageView>(R.id.pref_icon).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(primaryColor)
            visibility = View.VISIBLE
        }
        addItemView.setPadding(
            (16 * density).toInt(),
            (14 * density).toInt(),
            (16 * density).toInt(),
            (14 * density).toInt()
        )
        addItemView.setOnClickListener {
            showAddEditDialog(null)
        }
        actionCard.addView(addItemView)
        container.addView(actionCard)

        // 2. Macro items or empty guidance
        val store = MacroRepository(activity).loadMacroStore()
        val entries = store.all()

        if (entries.isEmpty()) {
            addHeader(if (lang == "vi") "Hướng dẫn" else "Guidance")
            val emptyCard = createSectionCard()
            val emptyView = inflater.inflate(R.layout.item_preference, emptyCard, false)
            emptyView.findViewById<TextView>(R.id.pref_title).text = if (lang == "vi") "Chưa có từ viết tắt nào" else "No shortcuts added"
            emptyView.findViewById<TextView>(R.id.pref_summary).apply {
                text = if (lang == "vi") {
                    "Thêm các cụm từ gõ tắt để soạn thảo nhanh hơn (ví dụ: vn ➔ Việt Nam).\nLưu ý: Hãy đảm bảo đã bật 'GÕ TẮT' trong cài đặt bàn phím."
                } else {
                    "Add shortcuts to type faster (e.g. vn ➔ Việt Nam).\nNote: Make sure 'GÕ TẮT' is enabled in keyboard settings."
                }
                visibility = View.VISIBLE
            }
            emptyView.findViewById<ImageView>(R.id.pref_icon).apply {
                setImageResource(R.drawable.ic_info)
                setColorFilter(onSurfaceVariantColor)
                visibility = View.VISIBLE
            }
            emptyView.setPadding(
                (16 * density).toInt(),
                (14 * density).toInt(),
                (16 * density).toInt(),
                (14 * density).toInt()
            )
            emptyCard.addView(emptyView)
            container.addView(emptyCard)
        } else {
            val countText = if (lang == "vi") "Danh sách từ viết tắt (${entries.size})" else "Shortcuts List (${entries.size})"
            addHeader(countText)

            val listCard = createSectionCard()

            entries.forEachIndexed { index, entry ->
                val itemView = inflater.inflate(R.layout.item_preference, listCard, false)

                itemView.findViewById<TextView>(R.id.pref_title).text = entry.trigger
                itemView.findViewById<TextView>(R.id.pref_summary).apply {
                    text = "➔ ${entry.expansion}"
                    visibility = View.VISIBLE
                }

                itemView.findViewById<ImageView>(R.id.pref_icon).apply {
                    setImageResource(R.drawable.ic_typing_style)
                    setColorFilter(onSurfaceVariantColor)
                    visibility = View.VISIBLE
                }

                // Delete action button on the right
                val deleteIcon = ImageView(activity).apply {
                    setImageResource(R.drawable.ic_delete)
                    setColorFilter(0xFFEF4444.toInt()) // Red accent
                    val iconPadding = (8 * density).toInt()
                    setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        store.remove(entry.trigger)
                        MacroRepository(activity).saveMacroStore(store)
                        rebuildList()
                    }
                }

                val trailingContainer = itemView.findViewById<android.widget.FrameLayout>(R.id.trailing_container)
                trailingContainer.removeAllViews()
                trailingContainer.addView(deleteIcon)
                trailingContainer.visibility = View.VISIBLE

                itemView.setPadding(
                    (16 * density).toInt(),
                    (12 * density).toInt(),
                    (16 * density).toInt(),
                    (12 * density).toInt()
                )

                itemView.setOnClickListener {
                    showAddEditDialog(entry)
                }

                listCard.addView(itemView)

                if (index < entries.size - 1) {
                    val divider = View(activity).apply {
                        setBackgroundColor(dividerColor)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (1 * density).toInt()
                        ).apply {
                            setMargins((56 * density).toInt(), 0, 0, 0)
                        }
                    }
                    listCard.addView(divider)
                }
            }

            container.addView(listCard)
        }
    }

    private fun showAddEditDialog(entryToEdit: MacroEntry?) {
        val lang = AppPreferences.getLanguage()
        val inflater = LayoutInflater.from(activity)
        val dialogView = inflater.inflate(R.layout.dialog_add_macro, null, false)

        val layoutTrigger = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_trigger)
        val inputTrigger = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_trigger)
        val layoutExpansion = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_expansion)
        val inputExpansion = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_expansion)

        layoutTrigger.hint = if (lang == "vi") "Từ gõ tắt" else "Shortcut trigger"
        layoutTrigger.placeholderText = if (lang == "vi") "Ví dụ: vn" else "e.g. vn"

        layoutExpansion.hint = if (lang == "vi") "Cụm từ đầy đủ" else "Expansion phrase"
        layoutExpansion.placeholderText = if (lang == "vi") "Ví dụ: Việt Nam" else "e.g. Việt Nam"

        if (entryToEdit != null) {
            inputTrigger.setText(entryToEdit.trigger)
            inputExpansion.setText(entryToEdit.expansion)
        }

        val isEdit = entryToEdit != null
        val dialogTitle = if (isEdit) {
            if (lang == "vi") "Sửa từ viết tắt" else "Edit Shortcut"
        } else {
            if (lang == "vi") "Thêm từ viết tắt" else "Add Shortcut"
        }

        val posButtonText = if (isEdit) {
            if (lang == "vi") "Lưu" else "Save"
        } else {
            if (lang == "vi") "Thêm" else "Add"
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(dialogTitle)
            .setView(dialogView)
            .setPositiveButton(posButtonText) { _, _ ->
                val trigger = inputTrigger.text?.toString()?.trim() ?: ""
                val expansion = inputExpansion.text?.toString()?.trim() ?: ""

                if (trigger.isNotEmpty() && expansion.isNotEmpty()) {
                    val store = MacroRepository(activity).loadMacroStore()
                    if (entryToEdit != null && entryToEdit.trigger != trigger.lowercase()) {
                        store.remove(entryToEdit.trigger)
                    }
                    store.addOrUpdate(trigger, expansion)
                    MacroRepository(activity).saveMacroStore(store)

                    val config = AppPreferences.getEngineConfig()
                    if (!config.macroEnabled) {
                        AppPreferences.setEngineConfig(config.copy(macroEnabled = true))
                    }

                    rebuildList()
                } else {
                    val msg = if (lang == "vi") "Vui lòng nhập đầy đủ từ gõ tắt và cụm từ đầy đủ" else "Please fill in all fields"
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(if (lang == "vi") "Hủy" else "Cancel", null)
            .show()
    }
}
