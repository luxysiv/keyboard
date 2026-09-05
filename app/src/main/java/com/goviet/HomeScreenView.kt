package com.goviet

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatDelegate
import com.goviet.databinding.FragmentHomeBinding
import com.goviet.core.AppPreferences

import com.goviet.core.density

class HomeScreenView(private val activity: MainActivity) : ScreenView {

    private val binding: FragmentHomeBinding

    init {
        val inflater = LayoutInflater.from(activity)
        binding = FragmentHomeBinding.inflate(inflater)
        
        val lang = AppPreferences.getLanguage()
        binding.toolbar.title = Loc.get("app_title", lang)
        
        rebuildList()
    }

    override fun getView(): View {
        return binding.root
    }

    override fun onResume() {
        rebuildList()
    }

    override fun onPause() {
    }

    private fun applyPremiumRipple(
        view: View,
        density: Float,
        backgroundColor: Int,
        rippleColor: Int,
        topRadius: Float,
        bottomRadius: Float
    ) {
        val radii = floatArrayOf(
            topRadius, topRadius,         // top-left
            topRadius, topRadius,         // top-right
            bottomRadius, bottomRadius,   // bottom-right
            bottomRadius, bottomRadius    // bottom-left
        )
        
        val contentDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(backgroundColor)
        }
        
        val maskDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(0xFFFFFFFF.toInt())
        }
        
        view.background = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            contentDrawable,
            maskDrawable
        )
    }

    private fun createSectionCard(context: Context, density: Float, surfaceVariantColor: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            
            // 16dp corner radius matches the keyboard design
            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(surfaceVariantColor)
            }
            background = bgDrawable
            
            // Consistent 8dp horizontal / 12dp vertical margin spacing for cards
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(
                    (16 * density).toInt(), // left
                    (8 * density).toInt(),  // top
                    (16 * density).toInt(), // right
                    (8 * density).toInt()   // bottom
                )
            }
            layoutParams = params
        }
    }

    private fun addDivider(parent: LinearLayout, density: Float, dividerColor: Int) {
        val divider = View(parent.context).apply {
            setBackgroundColor(dividerColor)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt() // Sleek thin 1dp divider
            ).apply {
                // Align with text start by shifting past the icon (56dp)
                setMargins((56 * density).toInt(), 0, 0, 0)
            }
            layoutParams = params
        }
        parent.addView(divider)
    }

    private fun addStyledItemToCard(
        parentCard: LinearLayout,
        inflater: LayoutInflater,
        title: String,
        summary: String?,
        iconRes: Int?,
        iconTint: Int?,
        trailingView: View?,
        onClick: () -> Unit,
        topRadius: Float,
        bottomRadius: Float,
        density: Float,
        surfaceVariantColor: Int,
        rippleColor: Int
    ): View {
        val itemView = inflater.inflate(R.layout.item_preference, parentCard, false)
        
        val titleView = itemView.findViewById<android.widget.TextView>(R.id.pref_title)
        titleView.text = title
        
        val summaryView = itemView.findViewById<android.widget.TextView>(R.id.pref_summary)
        if (summary != null) {
            summaryView.text = summary
            summaryView.visibility = View.VISIBLE
        } else {
            summaryView.visibility = View.GONE
        }
        
        val iconView = itemView.findViewById<ImageView>(R.id.pref_icon)
        if (iconRes != null) {
            iconView.setImageResource(iconRes)
            if (iconTint != null) {
                iconView.setColorFilter(iconTint)
            } else {
                iconView.clearColorFilter()
            }
            iconView.visibility = View.VISIBLE
        } else {
            iconView.visibility = View.GONE
        }
        
        val trailingContainer = itemView.findViewById<android.widget.FrameLayout>(R.id.trailing_container)
        trailingContainer.removeAllViews()
        if (trailingView != null) {
            trailingContainer.addView(trailingView)
            trailingContainer.visibility = View.VISIBLE
        } else {
            trailingContainer.visibility = View.GONE
        }
        
        applyPremiumRipple(itemView, density, surfaceVariantColor, rippleColor, topRadius, bottomRadius)
        
        // Professional inner margins and touch target height standard (min 48dp)
        itemView.setPadding(
            (16 * density).toInt(),
            (14 * density).toInt(),
            (16 * density).toInt(),
            (14 * density).toInt()
        )
        
        itemView.setOnClickListener {
            onClick()
        }
        
        parentCard.addView(itemView)
        return itemView
    }

    private fun addStyledSwitchToCard(
        parentCard: LinearLayout,
        inflater: LayoutInflater,
        title: String,
        summary: String?,
        iconRes: Int?,
        iconTint: Int?,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        topRadius: Float,
        bottomRadius: Float,
        density: Float,
        surfaceVariantColor: Int,
        rippleColor: Int
    ): View {
        val itemView = inflater.inflate(R.layout.item_preference_switch, parentCard, false)
        
        val titleView = itemView.findViewById<android.widget.TextView>(R.id.pref_title)
        titleView.text = title
        
        val summaryView = itemView.findViewById<android.widget.TextView>(R.id.pref_summary)
        if (summary != null) {
            summaryView.text = summary
            summaryView.visibility = View.VISIBLE
        } else {
            summaryView.visibility = View.GONE
        }
        
        val iconView = itemView.findViewById<ImageView>(R.id.pref_icon)
        if (iconRes != null) {
            iconView.setImageResource(iconRes)
            if (iconTint != null) {
                iconView.setColorFilter(iconTint)
            } else {
                iconView.clearColorFilter()
            }
            iconView.visibility = View.VISIBLE
        } else {
            iconView.visibility = View.GONE
        }
        
        val switchView = itemView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.pref_switch)
        switchView.isChecked = checked
        
        applyPremiumRipple(itemView, density, surfaceVariantColor, rippleColor, topRadius, bottomRadius)
        
        itemView.setPadding(
            (16 * density).toInt(),
            (14 * density).toInt(),
            (16 * density).toInt(),
            (14 * density).toInt()
        )
        
        itemView.setOnClickListener {
            val newChecked = !switchView.isChecked
            switchView.isChecked = newChecked
            onCheckedChange(newChecked)
        }
        
        parentCard.addView(itemView)
        return itemView
    }

    private fun rebuildList() {
        val container = binding.prefContainer
        container.removeAllViews()

        val inflater = LayoutInflater.from(activity)
        val lang = AppPreferences.getLanguage()
        
        val density = activity.density
        val isDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        
        // Dynamically resolve cohesive theme system tokens
        val surfaceVariantColor = activity.getColorFromAttr(com.google.android.material.R.attr.colorSurfaceVariant)
        val onSurfaceVariantColor = activity.getColorFromAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val dividerColor = if (isDark) 0xFF334155.toInt() else 0xFFE5E7EB.toInt()
        val rippleColorVal = if (isDark) 0x1AE5E7EB.toInt() else 0x141E2431.toInt()

        // Helper to add category headers with balanced padding
        fun addHeader(title: String) {
            container.addCategoryHeader(inflater, title)
            val headerView = container.getChildAt(container.childCount - 1)
            val params = headerView.layoutParams as LinearLayout.LayoutParams
            params.setMargins(
                (24 * density).toInt(), // Aligns perfectly with card content start
                (16 * density).toInt(),
                (24 * density).toInt(),
                (4 * density).toInt()
            )
            headerView.layoutParams = params
        }

        // 1. Vietnamese Typing Options Section (Direct 'w' & Old Tone Placement)
        addHeader(Loc.get("cat_typing_options", lang))
        val typingCard = createSectionCard(activity, density, surfaceVariantColor)
        
        addStyledSwitchToCard(
            typingCard,
            inflater,
            Loc.get("pref_direct_w_title", lang),
            Loc.get("pref_direct_w_desc", lang),
            R.drawable.ic_keyboard,
            onSurfaceVariantColor,
            AppPreferences.isDirectW(),
            { enabled ->
                AppPreferences.setDirectW(enabled)
            },
            16f * density,
            0f,
            density,
            surfaceVariantColor,
            rippleColorVal
        )
        
        addDivider(typingCard, density, dividerColor)
        
        addStyledSwitchToCard(
            typingCard,
            inflater,
            Loc.get("pref_old_tone_title", lang),
            Loc.get("pref_old_tone_desc", lang),
            R.drawable.ic_typing_style,
            onSurfaceVariantColor,
            AppPreferences.isOldTonePlacement(),
            { enabled ->
                AppPreferences.setOldTonePlacement(enabled)
            },
            0f,
            16f * density,
            density,
            surfaceVariantColor,
            rippleColorVal
        )
        
        container.addView(typingCard)

        // 2. Shortcuts & Macros Section
        addHeader(Loc.get("cat_macro", lang))
        val macroCard = createSectionCard(activity, density, surfaceVariantColor)
        val engineCfg = AppPreferences.getEngineConfig()

        addStyledSwitchToCard(
            macroCard,
            inflater,
            Loc.get("pref_cat_macro", lang),
            Loc.get("pref_feature_macro_desc", lang),
            R.drawable.ic_build,
            onSurfaceVariantColor,
            engineCfg.macroEnabled,
            { enabled ->
                AppPreferences.setEngineConfig(engineCfg.copy(macroEnabled = enabled, alwaysMacro = enabled))
            },
            16f * density,
            0f,
            density,
            surfaceVariantColor,
            rippleColorVal
        )

        addDivider(macroCard, density, dividerColor)

        addStyledItemToCard(
            macroCard,
            inflater,
            Loc.get("macro_title", lang),
            Loc.get("macro_desc", lang),
            R.drawable.ic_typing_style,
            onSurfaceVariantColor,
            null,
            { activity.showScreen("macro") },
            0f,
            16f * density,
            density,
            surfaceVariantColor,
            rippleColorVal
        )
        container.addView(macroCard)

        // 3. Info Section (Grouped into a single card block)
        addHeader(Loc.get("cat_info", lang))
        
        val infoCard = createSectionCard(activity, density, surfaceVariantColor)
        
        addStyledItemToCard(
            infoCard,
            inflater,
            Loc.get("about_title", lang),
            Loc.get("about_desc", lang),
            R.drawable.ic_info,
            onSurfaceVariantColor,
            null,
            { activity.showScreen("about") },
            16f * density,
            0f,
            density,
            surfaceVariantColor,
            rippleColorVal
        )
        
        addDivider(infoCard, density, dividerColor)
        
        addStyledItemToCard(
            infoCard,
            inflater,
            Loc.get("privacy_title", lang),
            Loc.get("privacy_desc", lang),
            R.drawable.ic_lock,
            onSurfaceVariantColor,
            null,
            { activity.showScreen("privacy") },
            0f,
            16f * density,
            density,
            surfaceVariantColor,
            rippleColorVal
        )
        
        container.addView(infoCard)
    }
}
