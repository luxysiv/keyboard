package com.goviet

import android.app.Application
import com.goviet.core.AppPreferences
import com.google.android.material.color.DynamicColors

class GoVietApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPreferences.init(this)
        if (AppPreferences.getThemeMode() == "dynamic") {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
    }
}
