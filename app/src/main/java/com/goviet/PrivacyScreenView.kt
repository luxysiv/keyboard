package com.goviet

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.goviet.databinding.FragmentPrivacyBinding
import com.goviet.core.AppPreferences

import com.goviet.core.density

class PrivacyScreenView(private val activity: MainActivity) : ScreenView {

    private val binding: FragmentPrivacyBinding

    init {
        val inflater = LayoutInflater.from(activity)
        binding = FragmentPrivacyBinding.inflate(inflater)

        val lang = AppPreferences.getLanguage()

        val density = activity.density
        val primaryColor = activity.getColorFromAttr(com.google.android.material.R.attr.colorPrimary)
        val surfaceVariantColor = activity.getColorFromAttr(com.google.android.material.R.attr.colorSurfaceVariant)
        val onSurfaceVariantColor = activity.getColorFromAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)

        // Toolbar and Title setup
        binding.toolbar.title = Loc.get("privacy_title", lang)
        binding.toolbar.setNavigationOnClickListener {
            activity.showScreen("home")
        }

        // 1. Main Commitment Header
        binding.privacyComm.apply {
            text = Loc.get("privacy_comm", lang)
            setTextColor(primaryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            
            val params = layoutParams as LinearLayout.LayoutParams
            params.setMargins(0, 0, 0, (16 * density).toInt())
            layoutParams = params
        }

        // Helper to apply beautiful card styling on paragraph TextViews
        fun styleParagraphCard(textView: TextView, textValue: String) {
            textView.apply {
                text = textValue
                setTextColor(onSurfaceVariantColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                
                // Breezy line-height spacing for modern readability
                setLineSpacing(6 * density, 1.0f)

                // Consistent 16dp rounded corner surface-variant background
                val bgDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f * density
                    setColor(surfaceVariantColor)
                }
                background = bgDrawable

                // Generous inner card padding
                setPadding(
                    (16 * density).toInt(),
                    (16 * density).toInt(),
                    (16 * density).toInt(),
                    (16 * density).toInt()
                )

                // Outer layout spacing/margins
                val params = layoutParams as LinearLayout.LayoutParams
                params.setMargins(0, (8 * density).toInt(), 0, (8 * density).toInt())
                layoutParams = params

                // Elegant lock icon at the start of each paragraph (tinted neutral slate-gray)
                val lockDrawable = ContextCompat.getDrawable(context, R.drawable.ic_lock)?.mutate()?.apply {
                    setTint(onSurfaceVariantColor)
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(lockDrawable, null, null, null)
                compoundDrawablePadding = (14 * density).toInt()
            }
        }

        styleParagraphCard(binding.privacyP1, Loc.get("privacy_p1", lang))
        styleParagraphCard(binding.privacyP2, Loc.get("privacy_p2", lang))
        styleParagraphCard(binding.privacyP3, Loc.get("privacy_p3", lang))
    }

    override fun getView(): View {
        return binding.root
    }
}
