package com.goviet.keyboard.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.goviet.R

object IconRegistry {
    val MAP: Map<String, Int> = mapOf(
        "clipboard" to R.drawable.ic_clipboard,
        "editpad" to R.drawable.ic_editpad,
        "emoji" to R.drawable.ic_emoji,
        "language" to R.drawable.ic_language,
        "typing_style" to R.drawable.ic_typing_style,
        "dialpad" to R.drawable.ic_tpad,
        "tpad" to R.drawable.ic_tpad,
        "settings" to R.drawable.ic_settings,
        "chevron_left" to R.drawable.ic_chevron_left,
        "chevron_right" to R.drawable.ic_chevron_right,
        "arrow_back" to R.drawable.ic_arrow_back,
        "expand_more" to R.drawable.ic_expand_more,
        "history" to R.drawable.ic_history,
        "recent" to R.drawable.ic_history,
        "smileys" to R.drawable.ic_emoji,
        "gestures" to R.drawable.ic_gestures,
        "animals" to R.drawable.ic_animals,
        "food" to R.drawable.ic_food,
        "places" to R.drawable.ic_places,
        "activities" to R.drawable.ic_activities,
        "objects" to R.drawable.ic_objects,
        "symbols" to R.drawable.ic_symbols,
        "flags" to R.drawable.ic_flags,
        "keyboard" to R.drawable.ic_keyboard,
        "border_style" to R.drawable.ic_border_style,
        "palette" to R.drawable.ic_palette,
        "height" to R.drawable.ic_height,
        "pin" to R.drawable.ic_pin,
        "pin_off" to R.drawable.ic_pin_off,
        "check_circle" to R.drawable.ic_check_circle,
        "lock" to R.drawable.ic_lock,
        "build" to R.drawable.ic_build,
        "contrast" to R.drawable.ic_contrast,
        "sun" to R.drawable.ic_sun,
        "moon" to R.drawable.ic_moon,
        "sparkle" to R.drawable.ic_sparkle,
        "delete" to R.drawable.ic_delete,
        "arrow_up" to R.drawable.ic_arrow_up,
        "arrow_down" to R.drawable.ic_arrow_down,
        "arrow_left" to R.drawable.ic_arrow_left,
        "arrow_right" to R.drawable.ic_arrow_right,
        "home_edge" to R.drawable.ic_home_edge,
        "end_edge" to R.drawable.ic_end_edge,
        "backspace" to R.drawable.ic_backspace
    )
}

object IconDrawer {
    private val cache = HashMap<Int, Drawable>()

    private fun getDrawable(context: Context, resId: Int): Drawable? {
        return cache.getOrPut(resId) {
            val d = ContextCompat.getDrawable(context, resId)?.mutate() ?: return null
            DrawableCompat.wrap(d)
        }
    }

    fun draw(canvas: Canvas, context: Context, id: String, cx: Float, cy: Float, sizePx: Float, tintColor: Int) {
        val resId = IconRegistry.MAP[id] ?: return
        draw(canvas, context, resId, cx, cy, sizePx, tintColor)
    }

    fun draw(canvas: Canvas, context: Context, resId: Int, cx: Float, cy: Float, sizePx: Float, tintColor: Int) {
        val drawable = getDrawable(context, resId) ?: return
        val half = (sizePx / 2f).toInt()
        drawable.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        DrawableCompat.setTint(drawable, tintColor)
        drawable.draw(canvas)
    }
}
