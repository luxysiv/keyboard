package com.goviet.keyboard.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import com.goviet.R
import com.goviet.core.AppPreferences
import com.goviet.core.density
import com.goviet.core.dpPx
import com.goviet.keyboard.util.IconDrawer
import com.goviet.keyboard.VietnameseInputMethodService
import com.goviet.keyboard.clipboard.ClipboardEntity
import com.goviet.keyboard.engine.VietnameseInputEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class KeyboardRootView @JvmOverloads constructor(
    context: Context,
    val service: VietnameseInputMethodService,
    val onKeyPress: (String) -> Unit,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // Properties replacing remember { mutableStateOf(...) }
    var shiftState: Int = 0
    var keyboardMode: String = "QWERTY"
    var clipboardItems: List<ClipboardEntity> = emptyList()
    var languageMode: String = "VIE"
    var navigationBarHeightRaw: Int = 0

    var activeSymbolsTab: Int = 1
    var activeEmojiTab: Int = 0
    var showInputMethodMenu: Boolean = false
    var activePopupKeyOptions: List<String>? = null
    var showClipboardClearConfirmDialog: Boolean = false
    var isToolbarOpen: Boolean = true

    // Layout views
    private val mainContainer: LinearLayout
    private val headerView: UnifiedTopHeaderView
    private val panelContainer: FrameLayout

    // Panel Views
    private val standardLetterGrid: StandardLetterGridView
    private val symbolsPickerGrid: SymbolsPickerGridView
    private val traditionalSettingsView: TraditionalSettingsView
    private val traditionalClipboardView: TraditionalClipboardView
    private val traditionalEmojiView: TraditionalEmojiView
    private val traditionalEditPadView: TraditionalEditPadView
    private val traditionalTpadView: TraditionalTpadView

    // Animators
    private var keyboardHeightAnimator: ValueAnimator? = null
    private var currentAnimatedHeight: Int = -1
    private var currentTargetPanel: View? = null

    private var collectionJob: Job? = null

    init {
        // Build view hierarchy
        mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        headerView = UnifiedTopHeaderView(context, this)
        
        panelContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Initialize child panels
        standardLetterGrid = StandardLetterGridView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        
        symbolsPickerGrid = SymbolsPickerGridView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        
        traditionalSettingsView = TraditionalSettingsView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        
        traditionalClipboardView = TraditionalClipboardView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        
        traditionalEmojiView = TraditionalEmojiView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        
        traditionalEditPadView = TraditionalEditPadView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        
        traditionalTpadView = TraditionalTpadView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // Add to panel container
        panelContainer.addView(standardLetterGrid)
        panelContainer.addView(symbolsPickerGrid)
        panelContainer.addView(traditionalSettingsView)
        panelContainer.addView(traditionalClipboardView)
        panelContainer.addView(traditionalEmojiView)
        panelContainer.addView(traditionalEditPadView)
        panelContainer.addView(traditionalTpadView)

        mainContainer.addView(headerView)
        mainContainer.addView(panelContainer)
        addView(mainContainer)

        setupCallbacks()
        render()
    }

    private fun setupCallbacks() {
        // Setup TraditionalSettingsView callbacks
        traditionalSettingsView.onUpdateSettings = { macro, autoCap, dirW, oldTone ->
            service.inputEngine.savePreferences(
                context = context,
                macro = macro,
                alwaysMac = macro,
                autoCap = autoCap,
                dirW = dirW,
                oldTone = oldTone
            )
            render()
        }
        traditionalSettingsView.onKeyStyleChange = { style ->
            AppPreferences.setKeyStyle(style)
            render()
        }
        traditionalSettingsView.onThemeChange = {
            render()
        }
        traditionalSettingsView.onBottomPaddingChange = {
            render()
        }
        traditionalSettingsView.onOpenFullSettings = {
            service.openSettings()
        }

        // Setup TraditionalClipboardView callbacks
        traditionalClipboardView.onSelect = { text ->
            service.selectClipboard(text)
            service._keyboardMode.value = "QWERTY"
        }
        traditionalClipboardView.onDeleteItem = { item ->
            service.deleteClipboardItem(item)
        }


        // Setup TraditionalEmojiView callbacks
        traditionalEmojiView.onSelectEmoji = { emoji ->
            service.addRecentEmoji(emoji)
            val ic = service.currentInputConnection
            if (ic != null) {
                ic.beginBatchEdit()
                try {
                    service.inputProcessor.commitAndFinishing()
                    ic.commitText(emoji, 1)
                } finally {
                    ic.endBatchEdit()
                }
            }
        }
        traditionalEmojiView.onBackToLetters = {
            service._keyboardMode.value = "QWERTY"
        }
        traditionalEmojiView.onSwitchToSymbols = {
            service._keyboardMode.value = "SYMBOL_PICKER"
        }
        traditionalEmojiView.onKeyPress = { key ->
            onKeyPress(key)
        }

        // Setup TraditionalEditPadView callbacks
        traditionalEditPadView.onAction = { code ->
            when (code) {
                "CLOSE" -> {
                    service._keyboardMode.value = "QWERTY"
                }
                "BACKSPACE" -> {
                    service.handleKeyPress("BACKSPACE")
                }
                else -> {
                    service.handleEditAction(code)
                }
            }
        }

        // Setup TraditionalTpadView callbacks
        traditionalTpadView.onKey = { key ->
            if (key == "ABC" || key == "QWERTY") {
                service._keyboardMode.value = "QWERTY"
            } else if (key == "SYMBOLS") {
                service._keyboardMode.value = "SYMBOLS"
            } else {
                onKeyPress(key)
            }
        }
        traditionalTpadView.onSwitchToABC = {
            service._keyboardMode.value = "QWERTY"
        }
        traditionalTpadView.onSwitchToSymbols = {
            service._keyboardMode.value = "SYMBOLS"
        }
    }
    
    fun handleSettingsBack(): Boolean {
        if (keyboardMode == "SETTINGS" && traditionalSettingsView.activeSubMenu != TraditionalSettingsView.SubMenu.NONE) {
            traditionalSettingsView.goBackToMainMenu()
            return true
        }
        return false
    }

    fun handleSettingsReset() {
        traditionalSettingsView.activeSubMenu = TraditionalSettingsView.SubMenu.NONE
    }

    private val prefChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (AppPreferences.isThemeKey(key)) {
            render()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AppPreferences.registerGlobalPrefsListener(prefChangeListener)

        collectionJob = service.serviceScope.launch {
            launch {
                service._shiftState.collect { value ->
                    updateShiftOnly(value)
                }
            }
            launch {
                service._keyboardMode.collect { value ->
                    keyboardMode = value
                    if (value == "QWERTY") {
                        traditionalSettingsView.activeSubMenu = TraditionalSettingsView.SubMenu.NONE
                    }
                    render()
                }
            }
            launch {
                service._clipboardItems.collect { value ->
                    updateClipboardItemsOnly(value)
                }
            }
            launch {
                service._languageMode.collect { value ->
                    updateLanguageModeOnly(value)
                }
            }
            launch {
                service._navigationBarHeight.collect { value ->
                    updateNavPaddingOnly(value)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        AppPreferences.unregisterGlobalPrefsListener(prefChangeListener)
        collectionJob?.cancel()
        keyboardHeightAnimator?.cancel()
        standardLetterGrid.animate().cancel()
        symbolsPickerGrid.animate().cancel()
        traditionalSettingsView.animate().cancel()
        traditionalClipboardView.animate().cancel()
        traditionalEmojiView.animate().cancel()
        traditionalEditPadView.animate().cancel()
        traditionalTpadView.animate().cancel()
    }

    private fun updateShiftOnly(value: Int) {
        this.shiftState = value
        val isLetterGridActive = when (keyboardMode) {
            "SETTINGS", "CLIPBOARD", "EMOJI", "EDIT_PAD", "TPAD", "SYMBOL_PICKER" -> false
            else -> true
        }
        if (isLetterGridActive) {
            standardLetterGrid.shiftState = value
            standardLetterGrid.invalidate()
        }
    }

    private fun updateClipboardItemsOnly(value: List<ClipboardEntity>) {
        this.clipboardItems = value
        if (keyboardMode == "CLIPBOARD") {
            traditionalClipboardView.items = value
            traditionalClipboardView.invalidate()
        }
    }

    private fun updateLanguageModeOnly(value: String) {
        this.languageMode = value
        when (keyboardMode) {
            "EMOJI" -> {
                traditionalEmojiView.currentLanguageMode = value
                traditionalEmojiView.invalidate()
            }
            "SYMBOL_PICKER" -> {
                symbolsPickerGrid.currentLanguageMode = value
                symbolsPickerGrid.invalidate()
            }
            "SETTINGS", "CLIPBOARD", "EDIT_PAD", "TPAD" -> {
                // these panels do not use language mode directly or do not have languageMode fields
            }
            else -> {
                standardLetterGrid.languageMode = value
                standardLetterGrid.invalidate()
            }
        }
    }

    private fun updateNavPaddingOnly(value: Int) {
        this.navigationBarHeightRaw = value
        val navPaddingPx = getNavigationBarPaddingPx(context)
        mainContainer.setPadding(0, 0, 0, navPaddingPx)
    }

    private fun applyThemeAndHeight(): ResolvedTheme {
        // 1. Calculate bottom navigation padding
        val navPaddingPx = getNavigationBarPaddingPx(context)
        mainContainer.setPadding(0, 0, 0, navPaddingPx)

        // 2. Animate keyboard heights
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val isTablet = resources.configuration.screenWidthDp >= 600
        val baseKeyboardHeightDp = if (isTablet) {
            if (isLandscape) 205f else 280f
        } else {
            if (isLandscape) 175f else 255f
        }
        val targetHeightPx = baseKeyboardHeightDp.dpPx(context)
        animateHeightTo(targetHeightPx)

        // 3. Resolve Theme Colors dynamically
        val isDark = isDarkTheme(context)
        val themeMode = AppPreferences.getThemeMode()
        val resolved = KeyboardTheme.resolve(context, isDark, themeMode)

        mainContainer.setBackgroundColor(resolved.backgroundColor)
        headerView.updateTheme(isDark, resolved.theme.textColor, resolved.theme.subTextColor, resolved.theme.activeAccentColor, resolved.headerBg)
        headerView.updateUI()

        service.updateNavigationBarColor(resolved.backgroundColor, isDark)

        return resolved
    }

    private fun updatePanels(theme: KeyboardTheme, backgroundColor: Int, isDark: Boolean) {
        // 4. Update Panels visibility and configurations
        val allPanels: List<BaseKeyGridView> = listOf(
            standardLetterGrid,
            symbolsPickerGrid,
            traditionalSettingsView,
            traditionalClipboardView,
            traditionalEmojiView,
            traditionalEditPadView,
            traditionalTpadView
        )
        allPanels.forEach {
            it.panelBgColor = backgroundColor
            it.updateTheme(theme)
        }

        val targetPanel = when (keyboardMode) {
            "SETTINGS" -> traditionalSettingsView
            "CLIPBOARD" -> traditionalClipboardView
            "EMOJI" -> traditionalEmojiView
            "EDIT_PAD" -> traditionalEditPadView
            "TPAD" -> traditionalTpadView
            "SYMBOLS" -> standardLetterGrid
            "SYMBOL_PICKER" -> symbolsPickerGrid
            else -> standardLetterGrid
        }
        val oldPanels = allPanels.filter { it != targetPanel }

        when (keyboardMode) {
            "SETTINGS" -> {
                if (traditionalSettingsView.visibility != VISIBLE) {
                    traditionalSettingsView.activeSubMenu = TraditionalSettingsView.SubMenu.NONE
                }
                traditionalSettingsView.macroEnabled = service.inputEngine.macroEnabled
                traditionalSettingsView.alwaysMacro = service.inputEngine.macroEnabled
                traditionalSettingsView.autoCapitalize = service.inputEngine.autoCapitalize
                traditionalSettingsView.directW = service.inputEngine.directW
                traditionalSettingsView.oldTonePlacement = service.inputEngine.oldTonePlacement
                traditionalSettingsView.keyStyle = AppPreferences.getKeyStyle()
                traditionalSettingsView.themeMode = AppPreferences.getThemeMode()
                traditionalSettingsView.bottomPaddingLevel = AppPreferences.getBottomPaddingLevel()
            }
            "CLIPBOARD" -> {
                traditionalClipboardView.items = clipboardItems
            }
            "EMOJI" -> {
                traditionalEmojiView.currentImeOptions = service.currentInputEditorInfo?.imeOptions ?: 0
                traditionalEmojiView.currentInputType = service.currentInputEditorInfo?.inputType ?: 0
                traditionalEmojiView.currentLanguageMode = languageMode
                
                val rawEmojis = if (activeEmojiTab == 0) {
                    service._recentEmojis.value
                } else {
                    val offsetIndex = activeEmojiTab - 1
                    if (offsetIndex in PickerData.EMOJI_GROUPS.indices) {
                        PickerData.EMOJI_GROUPS[offsetIndex].second
                    } else {
                        emptyList()
                    }
                }
                traditionalEmojiView.emojisList = rawEmojis
            }
            "EDIT_PAD" -> {
                traditionalEditPadView.isSelecting = service.inputProcessor.isSelecting
                traditionalEditPadView.panelBgColor = if (isDark) 0xFF1E2431.toInt() else 0xFFF3F4F6.toInt()
                traditionalEditPadView.errorColor = if (isDark) 0xFFCF6679.toInt() else 0xFFB00020.toInt()
                traditionalEditPadView.keyStyle = AppPreferences.getKeyStyle()
            }
            "TPAD" -> {
                traditionalTpadView.keyStyle = AppPreferences.getKeyStyle()

                val editorInfo = service.currentInputEditorInfo
                val inputType = editorInfo?.inputType ?: 0
                val hintLower = editorInfo?.hintText?.toString()?.lowercase() ?: ""
                val fieldNameLower = editorInfo?.fieldName?.lowercase() ?: ""
                val isNumericPassword = (inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_NUMBER &&
                        (inputType and android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                val isOtp = isNumericPassword ||
                        hintLower.contains("otp") || hintLower.contains("code") || hintLower.contains("pin") || hintLower.contains("mã") || hintLower.contains("verify") ||
                        fieldNameLower.contains("otp") || fieldNameLower.contains("code") || fieldNameLower.contains("pin") || fieldNameLower.contains("mã") || fieldNameLower.contains("verify")

                traditionalTpadView.currentEditorInputType = inputType
                traditionalTpadView.isOtpField = isOtp
                traditionalTpadView.currentImeOptions = editorInfo?.imeOptions ?: 0
                traditionalTpadView.currentInputType = inputType
            }
            "SYMBOLS" -> {
                standardLetterGrid.keyboardMode = "SYMBOLS"
                standardLetterGrid.shiftState = shiftState
                standardLetterGrid.languageMode = languageMode
                standardLetterGrid.imeOptions = service.currentInputEditorInfo?.imeOptions ?: 0
                standardLetterGrid.inputType = service.currentInputEditorInfo?.inputType ?: 0
                standardLetterGrid.onKey = { key ->
                    if (key == "ABC" || key == "QWERTY") {
                        service._keyboardMode.value = "QWERTY"
                    } else if (key == "EMOJI") {
                        service._keyboardMode.value = "EMOJI"
                    } else if (key == "SYMBOL_PICKER") {
                        service._keyboardMode.value = "SYMBOL_PICKER"
                    } else if (key == "TPAD") {
                        service._keyboardMode.value = "TPAD"
                    } else {
                        onKeyPress(key)
                    }
                }
                standardLetterGrid.onSwitchToSymbols = {}
                standardLetterGrid.onSwitchToEmoji = {
                    service._keyboardMode.value = "EMOJI"
                }
                standardLetterGrid.onOpenSettings = {
                    service._keyboardMode.value = "SETTINGS"
                }
                standardLetterGrid.onToggleLanguage = {
                    service._languageMode.value = if (languageMode == "VIE") "ENG" else "VIE"
                    service.composingRaw.clear()
                    service.currentInputConnection?.finishComposingText()
                }
            }
            "SYMBOL_PICKER" -> {
                symbolsPickerGrid.service = service
                symbolsPickerGrid.activeTab = activeSymbolsTab
                symbolsPickerGrid.onTabChange = { tab ->
                    activeSymbolsTab = tab
                    render()
                }
                symbolsPickerGrid.onKey = { key ->
                    if (key == "ABC" || key == "QWERTY") {
                        service._keyboardMode.value = "QWERTY"
                    } else if (key == "EMOJI") {
                        service._keyboardMode.value = "EMOJI"
                    } else if (key == "SYMBOLS") {
                        service._keyboardMode.value = "SYMBOLS"
                    } else if (key == "BACKSPACE") {
                        service.handleKeyPress("BACKSPACE")
                    } else if (key == "DELETE_WORD") {
                        service.handleKeyPress("DELETE_WORD")
                    } else {
                        onKeyPress(key)
                    }
                }
                symbolsPickerGrid.symbolsList = if (activeSymbolsTab == 0) {
                    service._recentSymbols.value
                } else {
                    PickerData.SYMBOLS_MAP[activeSymbolsTab] ?: emptyList()
                }
                symbolsPickerGrid.activePage = activeSymbolsTab
                symbolsPickerGrid.pagesCount = 9
                symbolsPickerGrid.currentImeOptions = service.currentInputEditorInfo?.imeOptions ?: 0
                symbolsPickerGrid.currentInputType = service.currentInputEditorInfo?.inputType ?: 0
                symbolsPickerGrid.currentLanguageMode = languageMode
            }
            else -> {
                standardLetterGrid.keyboardMode = keyboardMode
                standardLetterGrid.shiftState = shiftState
                standardLetterGrid.languageMode = languageMode
                standardLetterGrid.imeOptions = service.currentInputEditorInfo?.imeOptions ?: 0
                standardLetterGrid.inputType = service.currentInputEditorInfo?.inputType ?: 0
                standardLetterGrid.onKey = { key ->
                    onKeyPress(key)
                }
                standardLetterGrid.onSwitchToSymbols = {
                    service._keyboardMode.value = "SYMBOLS"
                }
                standardLetterGrid.onSwitchToEmoji = {
                    service._keyboardMode.value = "EMOJI"
                }
                standardLetterGrid.onOpenSettings = {
                    service._keyboardMode.value = "SETTINGS"
                }
                standardLetterGrid.onToggleLanguage = {
                    service._languageMode.value = if (languageMode == "VIE") "ENG" else "VIE"
                    service.composingRaw.clear()
                    service.currentInputConnection?.finishComposingText()
                }
                standardLetterGrid.onOpenPopup = { options ->
                    activePopupKeyOptions = options
                    render()
                }
                standardLetterGrid.updateTheme(theme)
            }
        }

        crossfadeToPanel(targetPanel, oldPanels)
    }

    fun render() {
        val resolved = applyThemeAndHeight()
        updatePanels(resolved.theme, resolved.backgroundColor, resolved.theme.isDark)
    }

    private fun resetPanelToHidden(panel: View) {
        panel.visibility = GONE
        panel.alpha = 1f
        panel.translationY = 0f
    }

    private fun crossfadeToPanel(newPanel: View, oldPanels: List<View>) {

        val containerHeight = if (panelContainer.height > 0) panelContainer.height.toFloat() else 260f * context.density

        var hasVisibleOldPanel = false
        for (panel in oldPanels) {
            if (panel.visibility == VISIBLE || panel.alpha < 1f) {
                hasVisibleOldPanel = true
            }
        }
        if (newPanel.visibility == VISIBLE && !hasVisibleOldPanel && newPanel.alpha == 1f && newPanel.translationY == 0f) {
            // Correct panel already fully visible, no transition animation needed
            return
        }

        currentTargetPanel = newPanel

        newPanel.animate().cancel()
        for (panel in oldPanels) {
            panel.animate().cancel()
        }

        for (panel in oldPanels) {
            if (panel.visibility == VISIBLE) {
                panel.animate()
                    .alpha(0f)
                    .translationY(containerHeight)
                    .setDuration(200)
                    .setListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (panel != currentTargetPanel) {
                                resetPanelToHidden(panel)
                            }
                        }
                        override fun onAnimationCancel(animation: android.animation.Animator) {
                            if (panel != currentTargetPanel) {
                                resetPanelToHidden(panel)
                            }
                        }
                    })
                    .start()
            } else {
                panel.animate().setListener(null)
                resetPanelToHidden(panel)
            }
        }

        if (newPanel.visibility != VISIBLE) {
            newPanel.alpha = 0f
            newPanel.translationY = containerHeight
            newPanel.visibility = VISIBLE
        }
        newPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    if (newPanel != currentTargetPanel) {
                        resetPanelToHidden(newPanel)
                    }
                }
            })
            .start()
    }

    private fun animateHeightTo(targetHeight: Int) {
        if (currentAnimatedHeight == -1) {
            currentAnimatedHeight = targetHeight
            updateHeightLayoutParams(targetHeight)
            return
        }
        if (currentAnimatedHeight == targetHeight) return

        keyboardHeightAnimator?.cancel()
        keyboardHeightAnimator = ValueAnimator.ofInt(currentAnimatedHeight, targetHeight).apply {
            duration = 150
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                currentAnimatedHeight = value
                updateHeightLayoutParams(value)
            }
            start()
        }
    }

    private fun updateHeightLayoutParams(height: Int) {
        val lp = panelContainer.layoutParams
        if (lp != null) {
            lp.height = height
            panelContainer.layoutParams = lp
        }
    }

    private fun getNavigationBarPaddingPx(context: Context): Int {
        val densityValue = context.density
        val navigationBarHeightRawDp = navigationBarHeightRaw / densityValue
        val isGestureMode = isGestureNavigationEnabled(context) || (navigationBarHeightRawDp < 20f)
        
        val extraPadding = when (AppPreferences.getBottomPaddingLevel()) {
            1 -> 12f
            2 -> 24f
            3 -> 36f
            else -> 0f
        }

        val paddingDp = if (isGestureMode) {
            20f + extraPadding
        } else {
            val insetDp = navigationBarHeightRawDp
            val systemNavBarHeightDp = service.getNavigationBarHeight() / densityValue
            val rawPadding = if (insetDp > 0f) insetDp else systemNavBarHeightDp
            (rawPadding - 4f).coerceIn(24f, 48f) + extraPadding
        }
        return paddingDp.dpPx(context)
    }

    private fun isGestureNavigationEnabled(context: Context): Boolean {
        try {
            val mode = android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode")
            if (mode == 2) return true
        } catch (e: Exception) {}

        try {
            val xiaomiMode = android.provider.Settings.Global.getInt(context.contentResolver, "force_fsg_nav_bar")
            if (xiaomiMode == 1) return true
        } catch (e: Exception) {}

        try {
            val vivoMode = android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_gesture_on")
            if (vivoMode != 0) return true
        } catch (e: Exception) {}

        try {
            val resourceId = context.resources.getIdentifier("config_showNavigationBar", "bool", "android")
            if (resourceId > 0 && !context.resources.getBoolean(resourceId)) {
                return true
            }
        } catch (e: Exception) {}

        return false
    }

    private fun isDarkTheme(context: Context): Boolean {
        val themeMode = AppPreferences.getThemeMode()
        return when (themeMode) {
            "light" -> false
            "dark" -> true
            else -> {
                val uiMode = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

}

// Programmatic Top Header Layout
private enum class DrawerButton(val id: String, val mode: String) {
    CLIPBOARD("btn_clipboard", "CLIPBOARD"),
    EDIT_PAD("btn_editpad", "EDIT_PAD"),
    EMOJI("btn_emoji", "EMOJI"),
    LANGUAGE("btn_language", "LANGUAGE"),
    TPAD("btn_tpad", "TPAD"),
    SETTINGS("btn_settings", "SETTINGS");

    companion object {
        val ALL = entries
        fun toggleMode(currentMode: String, button: DrawerButton): String =
            if (currentMode == button.mode) "QWERTY" else button.mode
    }
}

class UnifiedTopHeaderView(context: Context, private val rootView: KeyboardRootView) : View(context) {

    enum class HeaderMode { SYMBOL_PICKER, EMOJI, STANDARD }

    private val currentHeaderMode: HeaderMode
        get() = when (rootView.keyboardMode) {
            "SYMBOL_PICKER" -> HeaderMode.SYMBOL_PICKER
            "EMOJI" -> HeaderMode.EMOJI
            else -> HeaderMode.STANDARD
        }

    private val density get() = context.density

    private var toolbarProgress: Float = 1f  // 0f = fully collapsed to divider, 1f = fully expanded
    private var toolbarAnimator: android.animation.ValueAnimator? = null

    private var toggleFlipProgress: Float = 1f
    private var toggleFlipAnimator: android.animation.ValueAnimator? = null

    private var wasBackMode = false
    private var backArrowAlpha: Int = 255
    private var backArrowAnimator: android.animation.ValueAnimator? = null

    private fun setToolbarOpen(open: Boolean) {
        toolbarAnimator?.cancel()
        val target = if (open) 1f else 0f
        toolbarAnimator = android.animation.ValueAnimator.ofFloat(toolbarProgress, target).apply {
            duration = 200
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                toolbarProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
        startToggleFlipAnimation()
    }

    private fun startToggleFlipAnimation() {
        toggleFlipAnimator?.cancel()
        toggleFlipAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                toggleFlipProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // Touch and Scroll State
    private var scrollOffsetX = 0f
    private var maxScrollX = 0
    private var isScrolling = false
    private var startX = 0f
    private var startScrollX = 0f
    private val touchSlop = 8f * density
    private var pressedButtonId: String? = null

    private val tabScroller = android.widget.OverScroller(context)
    private var tabVelocityTracker: android.view.VelocityTracker? = null

    // Theme state
    private var isDark: Boolean = false
    private var textColor: Int = Color.WHITE
    private var subTextColor: Int = Color.GRAY
    private var accentColor: Int = Color.BLUE
    private var headerBg: Int = Color.BLACK

    // Paints
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private data class ShortcutSpec(
        val id: String,
        val isActive: Boolean
    )

    init {
        // Initial setup
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (38 * density).toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val totalHeight = if (heightMode == MeasureSpec.EXACTLY) heightSize else (38 * density).toInt()
        setMeasuredDimension(totalWidth, totalHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scrollToActiveTab()
    }

    override fun computeScroll() {
        if (tabScroller.computeScrollOffset()) {
            scrollOffsetX = tabScroller.currX.toFloat()
            scrollOffsetX = scrollOffsetX.coerceIn(0f, maxScrollX.toFloat())
            postInvalidateOnAnimation()
        }
    }

    fun updateUI() {
        val isBackMode = (rootView.keyboardMode == "SETTINGS" || rootView.keyboardMode == "CLIPBOARD" || rootView.keyboardMode == "EDIT_PAD" || rootView.keyboardMode == "TPAD")
        if (isBackMode) {
            toolbarAnimator?.cancel()
            toolbarProgress = 0f
        } else {
            if (toolbarAnimator == null || !toolbarAnimator!!.isRunning) {
                toolbarProgress = if (rootView.isToolbarOpen) 1f else 0f
            }
        }
        if (isBackMode && !wasBackMode) {
            backArrowAnimator?.cancel()
            backArrowAnimator = android.animation.ValueAnimator.ofInt(0, 255).apply {
                duration = 150
                addUpdateListener { anim ->
                    backArrowAlpha = anim.animatedValue as Int
                    invalidate()
                }
                start()
            }
        }
        wasBackMode = isBackMode
        scrollToActiveTab()
        invalidate()
    }

    fun updateTheme(isDark: Boolean, textColor: Int, subTextColor: Int, accentColor: Int, headerBg: Int) {
        this.isDark = isDark
        this.textColor = textColor
        this.subTextColor = subTextColor
        this.accentColor = accentColor
        this.headerBg = headerBg
        invalidate()
    }

    private fun scrollToActiveTab() {
        val w = width
        if (w == 0) return
        val backBtnWidth = (44 * density).toInt()
        val availableWidth = w - backBtnWidth
        val activeTab = if (currentHeaderMode == HeaderMode.SYMBOL_PICKER) rootView.activeSymbolsTab else rootView.activeEmojiTab
        val tabCount = if (currentHeaderMode == HeaderMode.SYMBOL_PICKER) 9 else 10
        val tabWidth = (56 * density).toInt()
        val totalTabsWidth = tabCount * tabWidth
        maxScrollX = Math.max(0, totalTabsWidth - availableWidth)

        if (currentHeaderMode == HeaderMode.SYMBOL_PICKER || currentHeaderMode == HeaderMode.EMOJI) {
            val tabLeft = activeTab * tabWidth
            val tabRight = tabLeft + tabWidth
            if (tabLeft < scrollOffsetX) {
                scrollOffsetX = tabLeft.toFloat()
            } else if (tabRight > scrollOffsetX + availableWidth) {
                scrollOffsetX = (tabRight - availableWidth).toFloat()
            }
        } else {
            scrollOffsetX = 0f
        }
        scrollOffsetX = scrollOffsetX.coerceIn(0f, maxScrollX.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Draw background
        canvas.drawColor(headerBg)

        // 2. Draw bottom divider
        paint.color = if (isDark) 0x1AFFFFFF.toInt() else 0x1A000000
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, h - 1f * density, w, h, paint)

        val backBtnWidth = (44 * density).toInt()

        when (currentHeaderMode) {
            HeaderMode.SYMBOL_PICKER -> {
                // Back Button
                val isPressed = (pressedButtonId == "back")
                val cx = 22f * density
                val cy = h / 2f
                if (isPressed) {
                    paint.color = (textColor and 0x00FFFFFF) or (0x14 shl 24)
                    paint.style = Paint.Style.FILL
                    val rect = RectF(4f * density, 4f * density, 40f * density, h - 4f * density)
                    canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
                }
                IconDrawer.draw(canvas, context, "arrow_back", cx + 0.5f * density, cy, 18f * density, textColor)

                // Tabs area
                val labels = listOf("recent", "1?#", "()", "⇄", "±", "①", "◇", "₫", "©")
                val tabWidth = (56 * density).toInt()
                val inactiveColor = Color.argb(178, Color.red(textColor), Color.green(textColor), Color.blue(textColor))

                canvas.save()
                canvas.clipRect(backBtnWidth.toFloat(), 0f, w, h)
                canvas.translate(-scrollOffsetX, 0f)

                for (i in labels.indices) {
                    val label = labels[i]
                    val isActive = (rootView.activeSymbolsTab == i)
                    val tabLeft = backBtnWidth + i * tabWidth
                    val tabRight = tabLeft + tabWidth
                    val tabCx = (tabLeft + tabRight) / 2f
                    val tabCy = h / 2f

                    if (pressedButtonId == "symbol_tab_$i") {
                        paint.color = (textColor and 0x00FFFFFF) or (0x14 shl 24)
                        paint.style = Paint.Style.FILL
                        val rect = RectF(tabLeft + 2f * density, 4f * density, tabRight - 2f * density, h - 4f * density)
                        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
                    }

                    if (isActive) {
                        paint.color = accentColor
                        paint.style = Paint.Style.FILL
                        val barW = 18f * density
                        val barH = 2f * density
                        val rect = RectF(tabCx - barW / 2f, h - barH - 2f * density, tabCx + barW / 2f, h - 2f * density)
                        canvas.drawRoundRect(rect, 1f * density, 1f * density, paint)
                    }

                    if (label == "recent") {
                        val baseColor = if (isActive) accentColor else inactiveColor
                        iconPaint.color = baseColor
                        val baseAlpha = Color.alpha(baseColor)
                        iconPaint.alpha = baseAlpha
                        iconPaint.strokeWidth = 1.6f * density
                        IconDrawer.draw(canvas, context, "recent", tabCx, tabCy, 23f * density, iconPaint.color)
                    } else {
                        textPaint.color = if (isActive) accentColor else inactiveColor
                        textPaint.textSize = if (label == "1?#") 11f * density else 14f * density
                        textPaint.typeface = if (isActive) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                        val baseline = KeyboardUtils.centerBaselineY(tabCy, textPaint)
                        canvas.drawText(label, tabCx, baseline, textPaint)
                    }
                }
                canvas.restore()
            }
            HeaderMode.EMOJI -> {
                // Back Button
                val isPressed = (pressedButtonId == "back")
                val cx = 22f * density
                val cy = h / 2f
                if (isPressed) {
                    paint.color = (textColor and 0x00FFFFFF) or (0x14 shl 24)
                    paint.style = Paint.Style.FILL
                    val rect = RectF(4f * density, 4f * density, 40f * density, h - 4f * density)
                    canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
                }
                IconDrawer.draw(canvas, context, "arrow_back", cx + 0.5f * density, cy, 18f * density, textColor)

                // Tabs area
                val icons = listOf("recent", "smileys", "gestures", "animals", "food", "places", "activities", "objects", "symbols", "flags")
                val tabWidth = (56 * density).toInt()
                val inactiveColor = Color.argb(178, Color.red(textColor), Color.green(textColor), Color.blue(textColor))

                canvas.save()
                canvas.clipRect(backBtnWidth.toFloat(), 0f, w, h)
                canvas.translate(-scrollOffsetX, 0f)

                for (i in icons.indices) {
                    val icon = icons[i]
                    val isActive = (rootView.activeEmojiTab == i)
                    val tabLeft = backBtnWidth + i * tabWidth
                    val tabRight = tabLeft + tabWidth
                    val tabCx = (tabLeft + tabRight) / 2f
                    val tabCy = h / 2f

                    if (pressedButtonId == "emoji_tab_$i") {
                        paint.color = (textColor and 0x00FFFFFF) or (0x14 shl 24)
                        paint.style = Paint.Style.FILL
                        val rect = RectF(tabLeft + 2f * density, 4f * density, tabRight - 2f * density, h - 4f * density)
                        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
                    }

                    if (isActive) {
                        paint.color = accentColor
                        paint.style = Paint.Style.FILL
                        val barW = 18f * density
                        val barH = 2f * density
                        val rect = RectF(tabCx - barW / 2f, h - barH - 2f * density, tabCx + barW / 2f, h - 2f * density)
                        canvas.drawRoundRect(rect, 1f * density, 1f * density, paint)
                    }

                    val baseColor = if (isActive) accentColor else inactiveColor
                    iconPaint.color = baseColor
                    val baseAlpha = Color.alpha(baseColor)
                    iconPaint.alpha = baseAlpha
                    iconPaint.strokeWidth = 1.6f * density
                    IconDrawer.draw(canvas, context, icon, tabCx, tabCy, 23f * density, iconPaint.color)
                }
                canvas.restore()
            }
            HeaderMode.STANDARD -> {
                // Left Toggle Button
                val isBackMode = (rootView.keyboardMode == "SETTINGS" || rootView.keyboardMode == "CLIPBOARD" || rootView.keyboardMode == "EDIT_PAD" || rootView.keyboardMode == "TPAD")
                val toggleCx = 22f * density
                val toggleCy = h / 2f

                if (pressedButtonId == "toggle") {
                    paint.color = (textColor and 0x00FFFFFF) or (0x14 shl 24)
                    paint.style = Paint.Style.FILL
                    val rect = RectF(4f * density, 4f * density, 40f * density, h - 4f * density)
                    canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
                }

                if (isBackMode) {
                    val tintColor = Color.argb(backArrowAlpha, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
                    IconDrawer.draw(canvas, context, "arrow_back", toggleCx, toggleCy, 16f * density, tintColor)
                } else {
                    canvas.save()
                    val scaleXValue = if (toggleFlipProgress < 0.5f) {
                        1f - toggleFlipProgress * 2f
                    } else {
                        (toggleFlipProgress - 0.5f) * 2f
                    }
                    canvas.scale(scaleXValue, 1f, toggleCx, toggleCy)

                    val pointingRight = if (toggleFlipProgress < 0.5f) {
                        rootView.isToolbarOpen
                    } else {
                        !rootView.isToolbarOpen
                    }

                    val chevronId = if (pointingRight) "chevron_right" else "chevron_left"
                    IconDrawer.draw(canvas, context, chevronId, toggleCx, toggleCy, 16f * density, textColor)
                    canvas.restore()
                }

                // Right Hide Button
                val hideLeft = w - backBtnWidth
                val hideCx = hideLeft + 22f * density
                val hideCy = h / 2f

                if (pressedButtonId == "hide") {
                    paint.color = (textColor and 0x00FFFFFF) or (0x14 shl 24)
                    paint.style = Paint.Style.FILL
                    val rect = RectF(hideLeft + 4f * density, 4f * density, w - 4f * density, h - 4f * density)
                    canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
                }

                val hideScale = if (pressedButtonId == "hide") 0.92f else 1.0f
                canvas.save()
                canvas.scale(hideScale, hideScale, hideCx, hideCy)
                IconDrawer.draw(canvas, context, "expand_more", hideCx, hideCy, 16f * density, textColor)
                canvas.restore()

                // Shortcuts Area & Divider
                if (toolbarProgress > 0f && !isBackMode) {
                    val dividerLeft = 44f * density + 2f * density

                    // Divider
                    val dividerW = 1.5f * density
                    paint.color = textColor
                    paint.alpha = (toolbarProgress * 255).toInt().coerceIn(0, 255)
                    paint.style = Paint.Style.FILL
                    val dividerPadding = 10f * density
                    
                    val fullTop = dividerPadding
                    val fullBottom = h - dividerPadding
                    val midY = h / 2f
                    val top = midY - (midY - fullTop) * toolbarProgress
                    val bottom = midY + (fullBottom - midY) * toolbarProgress

                    canvas.drawRoundRect(
                        RectF(dividerLeft, top, dividerLeft + dividerW, bottom),
                        dividerW / 2f,
                        dividerW / 2f,
                        paint
                    )

                    // Shortcuts
                    val shortcutsLeft = 48f * density
                    val shortcutsRight = w - backBtnWidth
                    val availableWidth = shortcutsRight - shortcutsLeft
                    val itemWidth = availableWidth / 6f

                    val shortcuts = listOf(
                        ShortcutSpec("btn_clipboard", rootView.keyboardMode == "CLIPBOARD"),
                        ShortcutSpec("btn_editpad", rootView.keyboardMode == "EDIT_PAD"),
                        ShortcutSpec("btn_emoji", rootView.keyboardMode == "EMOJI"),
                        ShortcutSpec("btn_language", false),
                        ShortcutSpec("btn_tpad", rootView.keyboardMode == "TPAD"),
                        ShortcutSpec("btn_settings", rootView.keyboardMode == "SETTINGS")
                    )

                    val activeColor = accentColor
                    val inactiveColor = Color.argb(178, Color.red(textColor), Color.green(textColor), Color.blue(textColor))

                    for (i in shortcuts.indices) {
                        val spec = shortcuts[i]
                        val specLeft = shortcutsLeft + i * itemWidth
                        val specRight = specLeft + itemWidth
                        val specCx = (specLeft + specRight) / 2f
                        val specCy = h / 2f

                        val collapsedCx = dividerLeft
                        val expandedCx = specCx
                        val animatedCx = collapsedCx + (expandedCx - collapsedCx) * toolbarProgress

                        if (pressedButtonId == spec.id) {
                            paint.color = (textColor and 0x00FFFFFF) or (0x14 shl 24)
                            paint.style = Paint.Style.FILL
                            val rect = RectF(specLeft + 2f * density, 4f * density, specRight - 2f * density, h - 4f * density)
                            canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
                        }

                        val baseColor = if (spec.isActive) activeColor else inactiveColor
                        iconPaint.color = baseColor
                        val baseAlpha = Color.alpha(baseColor)
                        iconPaint.alpha = (toolbarProgress * baseAlpha).toInt().coerceIn(0, 255)
                        iconPaint.strokeWidth = if (spec.isActive) 1.9f * density else 1.6f * density

                        val shortcutId = spec.id.replace("btn_", "")
                        IconDrawer.draw(canvas, context, shortcutId, animatedCx, specCy, 22f * density, iconPaint.color)
                    }
                }
            }
        }
    }



    private fun findTouchedButtonId(x: Float, y: Float): String? {
        val h = height.toFloat()
        val w = width.toFloat()
        val backBtnWidth = (44 * density).toInt()

        when (currentHeaderMode) {
            HeaderMode.SYMBOL_PICKER -> {
                if (x < backBtnWidth) {
                    return "back"
                } else {
                    val tabWidth = (56 * density).toInt()
                    val scrollX = x + scrollOffsetX - backBtnWidth
                    val index = (scrollX / tabWidth).toInt()
                    if (index in 0 until 9) {
                        return "symbol_tab_$index"
                    }
                }
            }
            HeaderMode.EMOJI -> {
                if (x < backBtnWidth) {
                    return "back"
                } else {
                    val tabWidth = (56 * density).toInt()
                    val scrollX = x + scrollOffsetX - backBtnWidth
                    val index = (scrollX / tabWidth).toInt()
                    if (index in 0 until 10) {
                        return "emoji_tab_$index"
                    }
                }
            }
            HeaderMode.STANDARD -> {
                if (x < backBtnWidth) {
                    return "toggle"
                }
                if (x > w - backBtnWidth) {
                    return "hide"
                }

                val isBackMode = (rootView.keyboardMode == "SETTINGS" || rootView.keyboardMode == "CLIPBOARD" || rootView.keyboardMode == "EDIT_PAD" || rootView.keyboardMode == "TPAD")
                val shouldShowToolbar = toolbarProgress >= 0.99f && !isBackMode
                if (shouldShowToolbar) {
                    val shortcutsLeft = 48f * density
                    val shortcutsRight = w - backBtnWidth
                    if (x >= shortcutsLeft && x <= shortcutsRight) {
                        val availableWidth = shortcutsRight - shortcutsLeft
                        val itemWidth = availableWidth / 6f
                        val index = ((x - shortcutsLeft) / itemWidth).toInt()
                        if (index in DrawerButton.ALL.indices) {
                            return DrawerButton.ALL[index].id
                        }
                    }
                }
            }
        }
        return null
    }

    private fun handleButtonClick(id: String) {
        when (id) {
            "back" -> {
                rootView.service._keyboardMode.value = "QWERTY"
            }
            "toggle" -> {
                val km = rootView.keyboardMode
                if (km == "SETTINGS") {
                    if (!rootView.handleSettingsBack()) {
                        rootView.service._keyboardMode.value = "QWERTY"
                    }
                } else if (km == "CLIPBOARD" || km == "EDIT_PAD" || km == "TPAD") {
                    rootView.service._keyboardMode.value = "QWERTY"
                } else {
                    rootView.isToolbarOpen = !rootView.isToolbarOpen
                    setToolbarOpen(rootView.isToolbarOpen)
                }
            }
            "hide" -> {
                rootView.service.requestHideSelf(0)
            }
            in DrawerButton.ALL.map { it.id } -> {
                val btn = DrawerButton.ALL.first { it.id == id }
                if (btn == DrawerButton.LANGUAGE) {
                    rootView.service.switchToNextInputMethod()
                } else {
                    rootView.service._keyboardMode.value = DrawerButton.toggleMode(rootView.keyboardMode, btn)
                }
            }
            else -> {
                val tabIndex = when {
                    id.startsWith("symbol_tab_") -> id.substringAfter("symbol_tab_").toIntOrNull()
                    id.startsWith("emoji_tab_") -> id.substringAfter("emoji_tab_").toIntOrNull()
                    else -> null
                }
                if (tabIndex != null) {
                    if (id.startsWith("symbol_tab_")) {
                        rootView.activeSymbolsTab = tabIndex
                    } else {
                        rootView.activeEmojiTab = tabIndex
                    }
                    rootView.render()
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val backBtnWidth = (44 * density).toInt()

        val isTabScrollMode = (currentHeaderMode == HeaderMode.SYMBOL_PICKER || currentHeaderMode == HeaderMode.EMOJI)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                startScrollX = scrollOffsetX
                isScrolling = false

                tabScroller.forceFinished(true)
                if (isTabScrollMode) {
                    val w = width
                    val availableWidth = w - backBtnWidth
                    val tabCount = if (currentHeaderMode == HeaderMode.SYMBOL_PICKER) 9 else 10
                    val tabWidth = (56 * density).toInt()
                    val totalTabsWidth = tabCount * tabWidth
                    maxScrollX = Math.max(0, totalTabsWidth - availableWidth)

                    if (tabVelocityTracker == null) {
                        tabVelocityTracker = android.view.VelocityTracker.obtain()
                    } else {
                        tabVelocityTracker?.clear()
                    }
                    tabVelocityTracker?.addMovement(event)
                }

                pressedButtonId = findTouchedButtonId(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTabScrollMode && x >= backBtnWidth) {
                    tabVelocityTracker?.addMovement(event)
                    val dx = startX - x
                    if (!isScrolling && Math.abs(dx) > touchSlop) {
                        isScrolling = true
                        pressedButtonId = null
                    }
                    if (isScrolling) {
                        scrollOffsetX = (startScrollX + dx).coerceIn(0f, maxScrollX.toFloat())
                        invalidate()
                    } else {
                        val touchedId = findTouchedButtonId(x, y)
                        if (pressedButtonId != touchedId) {
                            pressedButtonId = touchedId
                            invalidate()
                        }
                    }
                } else {
                    val touchedId = findTouchedButtonId(x, y)
                    if (pressedButtonId != touchedId) {
                        pressedButtonId = touchedId
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isTabScrollMode) {
                    tabVelocityTracker?.addMovement(event)
                    if (isScrolling) {
                        tabVelocityTracker?.computeCurrentVelocity(1000)
                        val xVelocity = tabVelocityTracker?.xVelocity ?: 0f
                        tabScroller.fling(
                            scrollOffsetX.toInt(), 0,
                            -xVelocity.toInt(), 0,
                            0, maxScrollX,
                            0, 0
                        )
                        postInvalidateOnAnimation()
                        isScrolling = false
                    } else {
                        val touchedId = findTouchedButtonId(x, y)
                        if (touchedId != null && touchedId == pressedButtonId) {
                            handleButtonClick(touchedId)
                        }
                    }
                    tabVelocityTracker?.recycle()
                    tabVelocityTracker = null
                } else {
                    if (isScrolling) {
                        isScrolling = false
                    } else {
                        val touchedId = findTouchedButtonId(x, y)
                        if (touchedId != null && touchedId == pressedButtonId) {
                            handleButtonClick(touchedId)
                        }
                    }
                }
                pressedButtonId = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                isScrolling = false
                pressedButtonId = null
                tabVelocityTracker?.recycle()
                tabVelocityTracker = null
                invalidate()
            }
        }
        return true
    }
}
