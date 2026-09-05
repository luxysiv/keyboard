package com.goviet.core

import android.content.Context
import android.view.View

/**
 * Utility extensions for density and DP/PX conversions using actual Context or View resources,
 * ensuring correct scaling across secondary displays or foldable screens with custom density.
 */
val Context.density: Float
    get() = resources.displayMetrics.density

val View.density: Float
    get() = context.resources.displayMetrics.density

fun Int.dpPx(context: Context): Int = (this * context.density).toInt()
fun Float.dpPx(context: Context): Int = (this * context.density).toInt()

