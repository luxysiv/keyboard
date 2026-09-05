package com.goviet

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.goviet.databinding.FragmentAboutBinding
import com.goviet.core.AppPreferences

import com.goviet.core.density

class AboutScreenView(private val activity: MainActivity) : ScreenView {

    private val binding: FragmentAboutBinding

    init {
        val inflater = LayoutInflater.from(activity)
        binding = FragmentAboutBinding.inflate(inflater)

        val lang = AppPreferences.getLanguage()

        binding.toolbar.title = Loc.get("about_title", lang)
        binding.toolbar.setNavigationOnClickListener {
            activity.showScreen("home")
        }

        rebuildList(lang)
    }

    override fun getView(): View {
        return binding.root
    }

    private fun rebuildList(lang: String) {
        val container = binding.aboutContainer
        container.removeAllViews()

        val inflater = LayoutInflater.from(activity)
        val surfaceVariantColor = activity.getColorFromAttr(com.google.android.material.R.attr.colorSurfaceVariant)
        val onSurfaceVariantColor = activity.getColorFromAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val density = activity.density
        val isDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dividerColor = if (isDark) 0xFF334155.toInt() else 0xFFE5E7EB.toInt()

        val version = try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }

        // Section header matching homescreen style
        container.addCategoryHeader(inflater, Loc.get("about_title", lang))
        val headerView = container.getChildAt(container.childCount - 1)
        val headerParams = headerView.layoutParams as LinearLayout.LayoutParams
        headerParams.setMargins(
            (24 * density).toInt(),
            (16 * density).toInt(),
            (24 * density).toInt(),
            (4 * density).toInt()
        )
        headerView.layoutParams = headerParams

        val aboutCard = LinearLayout(activity).apply {
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
                    (16 * density).toInt()
                )
            }
        }

        fun applyCardRadii(view: View, topRadius: Float, bottomRadius: Float) {
            val radii = floatArrayOf(
                topRadius, topRadius,
                topRadius, topRadius,
                bottomRadius, bottomRadius,
                bottomRadius, bottomRadius
            )
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = radii
                setColor(surfaceVariantColor)
            }
        }

        fun addStyledRow(
            title: String,
            summary: String,
            iconRes: Int,
            topRadius: Float,
            bottomRadius: Float
        ) {
            val itemView = inflater.inflate(R.layout.item_preference, aboutCard, false)
            
            val titleView = itemView.findViewById<android.widget.TextView>(R.id.pref_title)
            titleView.text = title
            
            val summaryView = itemView.findViewById<android.widget.TextView>(R.id.pref_summary)
            summaryView.text = summary
            summaryView.visibility = View.VISIBLE
            
            val iconView = itemView.findViewById<ImageView>(R.id.pref_icon)
            iconView.setImageResource(iconRes)
            iconView.setColorFilter(onSurfaceVariantColor)
            iconView.visibility = View.VISIBLE
            
            applyCardRadii(itemView, topRadius, bottomRadius)
            
            itemView.setPadding(
                (16 * density).toInt(),
                (14 * density).toInt(),
                (16 * density).toInt(),
                (14 * density).toInt()
            )
            
            itemView.isClickable = false
            itemView.isFocusable = false
            
            aboutCard.addView(itemView)
        }

        fun addDivider() {
            val divider = View(activity).apply {
                setBackgroundColor(dividerColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt()
                ).apply {
                    setMargins((56 * density).toInt(), 0, 0, 0)
                }
            }
            aboutCard.addView(divider)
        }

        // 1. App Name
        addStyledRow(
            title = Loc.get("about_app_name", lang),
            summary = "GoViet",
            iconRes = R.drawable.ic_settings,
            topRadius = 16f * density,
            bottomRadius = 0f
        )
        addDivider()

        // 2. Version
        addStyledRow(
            title = Loc.get("about_version", lang),
            summary = version,
            iconRes = R.drawable.ic_info,
            topRadius = 0f,
            bottomRadius = 0f
        )
        addDivider()

        // 3. Supported Keyboards
        addStyledRow(
            title = Loc.get("about_keyboards", lang),
            summary = Loc.get("about_keyboards_val", lang),
            iconRes = R.drawable.ic_keyboard,
            topRadius = 0f,
            bottomRadius = 0f
        )
        addDivider()

        // 4. Development
        addStyledRow(
            title = Loc.get("about_dev", lang),
            summary = Loc.get("about_dev_val", lang),
            iconRes = R.drawable.ic_build,
            topRadius = 0f,
            bottomRadius = 16f * density
        )

        container.addView(aboutCard)
    }
}
