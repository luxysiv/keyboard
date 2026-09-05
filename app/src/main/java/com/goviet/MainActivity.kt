package com.goviet

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.goviet.databinding.ActivityMainBinding
import com.goviet.keyboard.engine.VietnameseInputEngine
import com.goviet.core.AppPreferences
import com.goviet.core.dpPx

interface ScreenView {
    fun getView(): View
    fun onResume() {}
    fun onPause() {}
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentScreen = "home"
    private var activeScreenView: ScreenView? = null
    private val autoSetupHandler = Handler(Looper.getMainLooper())

    fun applyLocale(context: Context, lang: String) {
        val locale = if (lang == "en") java.util.Locale.ENGLISH else java.util.Locale.forLanguageTag("vi")
        java.util.Locale.setDefault(locale)
        val localeList = androidx.core.os.LocaleListCompat.create(locale)
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(localeList)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPreferences.init(applicationContext)
        // Load Preferences initially
        VietnameseInputEngine().loadPreferences(this)

        val themeMode = AppPreferences.getThemeMode()

        when (themeMode) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "dynamic" -> {
                // Dynamic colors are managed at the Application level via DynamicColors.applyToActivitiesIfAvailable.
                // Night mode defaults to follow system theme so dynamic colors adapt to light/dark modes automatically.
                // DynamicColors library handles API compatibility internally and falls back to system theme on API < 31 without crashes.
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        
        val lang = AppPreferences.getLanguage()
        applyLocale(this, lang)

        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this) {
            if (currentScreen != "home") {
                showScreen("home")
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }

        showScreen("home")
    }

    override fun onResume() {
        super.onResume()
        activeScreenView?.onResume()
        autoCheckAndPromptKeyboardSetup()
    }

    override fun onPause() {
        super.onPause()
        autoSetupHandler.removeCallbacksAndMessages(null)
        activeScreenView?.onPause()
    }

    fun autoCheckAndPromptKeyboardSetup() {
        autoSetupHandler.removeCallbacksAndMessages(null)
        autoSetupHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (!isKeyboardEnabled()) {
                openIMESettings()
            } else if (!isKeyboardSelected()) {
                showIMEPicker()
            }
        }, 300)
    }

    fun showScreen(screen: String) {
        currentScreen = screen
        activeScreenView?.onPause()
        binding.screenContainer.removeAllViews()

        val screenView: ScreenView = when (screen) {
            "home" -> HomeScreenView(this)
            "about" -> AboutScreenView(this)
            "privacy" -> PrivacyScreenView(this)
            "macro" -> MacroScreenView(this)
            else -> {
                object : ScreenView {
                    override fun getView(): View {
                        return TextView(this@MainActivity).apply {
                            text = "Screen: $screen (Place Holder)"
                            textSize = 20f
                            val padding = 16.dpPx(this@MainActivity)
                            setPadding(padding, padding, padding, padding)
                        }
                    }
                }
            }
        }
        activeScreenView = screenView
        binding.screenContainer.addView(screenView.getView())
        screenView.onResume()
    }

    fun openIMESettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun showIMEPicker() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isKeyboardEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledImes = imm.enabledInputMethodList
        val packageName = packageName
        return enabledImes.any { it.packageName == packageName }
    }

    fun isKeyboardSelected(): Boolean {
        val currentIme = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        return currentIme != null && (currentIme.startsWith(packageName) || currentIme.contains(packageName))
    }

    fun getColorFromAttr(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}

object Loc {
    fun get(key: String, lang: String): String {
        val context = AppPreferences.appContext
        val resId = context.resources.getIdentifier(key, "string", context.packageName)
        if (resId == 0) return ""
        if (lang.isNotEmpty()) {
            val locale = if (lang == "en") java.util.Locale.ENGLISH else java.util.Locale.forLanguageTag("vi")
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(locale)
            val localizedContext = context.createConfigurationContext(config)
            return localizedContext.getString(resId)
        }
        return context.getString(resId)
    }
}
