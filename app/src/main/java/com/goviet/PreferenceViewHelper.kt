package com.goviet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial

fun ViewGroup.addCategoryHeader(inflater: LayoutInflater, title: String) {
    val view = inflater.inflate(R.layout.item_preference_category, this, false)
    val titleView = view.findViewById<TextView>(R.id.category_title)
    titleView.text = title
    this.addView(view)
}

fun ViewGroup.addPreferenceItem(
    inflater: LayoutInflater,
    title: String,
    summary: String?,
    iconRes: Int?,
    iconTint: Int?,
    trailingView: View?,
    onClick: () -> Unit
) {
    val view = inflater.inflate(R.layout.item_preference, this, false)
    
    val titleView = view.findViewById<TextView>(R.id.pref_title)
    titleView.text = title

    val summaryView = view.findViewById<TextView>(R.id.pref_summary)
    if (summary != null) {
        summaryView.text = summary
        summaryView.visibility = View.VISIBLE
    } else {
        summaryView.visibility = View.GONE
    }

    val iconView = view.findViewById<ImageView>(R.id.pref_icon)
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

    val trailingContainer = view.findViewById<FrameLayout>(R.id.trailing_container)
    trailingContainer.removeAllViews()
    if (trailingView != null) {
        trailingContainer.addView(trailingView)
        trailingContainer.visibility = View.VISIBLE
    } else {
        trailingContainer.visibility = View.GONE
    }

    view.setOnClickListener {
        onClick()
    }

    this.addView(view)
}

fun ViewGroup.addPreferenceSwitch(
    inflater: LayoutInflater,
    title: String,
    summary: String?,
    iconRes: Int?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val view = inflater.inflate(R.layout.item_preference_switch, this, false)

    val titleView = view.findViewById<TextView>(R.id.pref_title)
    titleView.text = title

    val summaryView = view.findViewById<TextView>(R.id.pref_summary)
    if (summary != null) {
        summaryView.text = summary
        summaryView.visibility = View.VISIBLE
    } else {
        summaryView.visibility = View.GONE
    }

    val iconView = view.findViewById<ImageView>(R.id.pref_icon)
    if (iconRes != null) {
        iconView.setImageResource(iconRes)
        iconView.visibility = View.VISIBLE
    } else {
        iconView.visibility = View.GONE
    }

    val switchView = view.findViewById<SwitchMaterial>(R.id.pref_switch)
    switchView.isChecked = checked

    view.setOnClickListener {
        val newChecked = !switchView.isChecked
        switchView.isChecked = newChecked
        onCheckedChange(newChecked)
    }

    this.addView(view)
}
