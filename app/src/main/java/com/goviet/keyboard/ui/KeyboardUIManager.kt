package com.goviet.keyboard.ui

import com.goviet.keyboard.VietnameseInputMethodService
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

@Suppress("DEPRECATION")
class KeyboardUIManager(private val service: VietnameseInputMethodService) {
    private val TAG = "KeyboardUIManager"
    private var rootView: KeyboardRootView? = null

    fun notifyConfigurationChanged() {
        rootView?.render()
    }

    fun onCreateInputView(): View {
        service.window?.window?.let { win ->
            win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        
        val rootContainer = FrameLayout(service).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        service.window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(service)
            decor.setViewTreeViewModelStoreOwner(service)
            decor.setViewTreeSavedStateRegistryOwner(service)
        }
        
        rootContainer.setOnApplyWindowInsetsListener { v, insets ->
            val navBarHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val navInsets = insets.getInsets(android.view.WindowInsets.Type.navigationBars())
                navInsets.bottom
            } else {
                insets.systemWindowInsetBottom
            }

            val tappableBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(android.view.WindowInsets.Type.tappableElement()).bottom
            } else {
                0
            }

            val effectivePadding = maxOf(navBarHeight, tappableBottom)
            service._navigationBarHeight.value = effectivePadding

            insets
        }

        val krv = KeyboardRootView(
            context = service,
            service = service,
            onKeyPress = { key ->
                if (key == "LEFT_MOVE") {
                    service.handleEditAction("LEFT")
                } else if (key == "RIGHT_MOVE") {
                    service.handleEditAction("RIGHT")
                } else {
                    service.handleKeyPress(key)
                }
            }
        ).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        
        rootContainer.addView(krv)
        rootView = krv
        service.keyboardRootView = krv
        return rootContainer
    }

    fun updateNavigationBarColor(color: Int, isDark: Boolean) {
        val win = service.window?.window ?: return
        win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        win.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        win.navigationBarColor = color
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            win.isNavigationBarContrastEnforced = false
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            win.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
        }
        
        val decor = win.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = win.insetsController
            if (controller != null) {
                if (!isDark) {
                    controller.setSystemBarsAppearance(
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    )
                } else {
                    controller.setSystemBarsAppearance(
                        0,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    )
                }
            }
        }
        
        // Always apply legacy systemUiVisibility flags for maximum OEM compatibility (Honor, Huawei, Xiaomi, Samsung)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var flags = decor.systemUiVisibility
            if (!isDark) {
                flags = flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags = flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
            decor.systemUiVisibility = flags
        }
    }

    fun getNavigationBarHeight(): Int {
        val resourceId = service.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            service.resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }
}
