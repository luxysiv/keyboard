package com.goviet.keyboard

import com.goviet.keyboard.engine.ImeInputConnectionController
import com.goviet.keyboard.engine.VietnameseInputEngine
import com.goviet.keyboard.engine.BoundaryClassifier
import com.goviet.keyboard.clipboard.ClipboardCoordinator
import com.goviet.keyboard.clipboard.ClipboardDatabase
import com.goviet.keyboard.clipboard.ClipboardRepository
import com.goviet.keyboard.clipboard.ClipboardEntity
import com.goviet.keyboard.ui.KeyboardUIManager
import com.goviet.core.AppPreferences
import android.util.Log
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.goviet.keyboard.ui.KeyboardRootView
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.goviet.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class VietnameseInputMethodService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val TAG = "VietnameseKeyboard"

    // Manage standard Lifecycle Owners inside standard InputMethodService
    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    private val store by lazy { ViewModelStore() }
    private val savedStateRegistryController by lazy { SavedStateRegistryController.create(this) }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    lateinit var clipboardRepository: ClipboardRepository
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var keyboardRootView: KeyboardRootView? = null
    val inputEngine = VietnameseInputEngine()

    // Specialized delegated sub-engines
    lateinit var inputProcessor: ImeInputConnectionController
    lateinit var clipboardCoordinator: ClipboardCoordinator
    lateinit var keyboardUIManager: KeyboardUIManager

    // Backward-compatible delegator for composing raw buffer
    val composingRaw: java.lang.StringBuilder
        get() = inputProcessor.composingRaw

    var lastCommittedWord: String? = null
    var currentSelStart = 0
    var currentSelEnd = 0

    // StateFlow states which UI will read and re-compose upon
    val _languageMode = MutableStateFlow("VIE") // "VIE" or "ENG"
    val shiftController = com.goviet.keyboard.engine.ShiftStateController()
    val _shiftState: StateFlow<Int> get() = shiftController.state
    val _keyboardMode = MutableStateFlow("QWERTY") // "QWERTY", "TPAD", "SYMBOLS", "EMOJI", "CLIPBOARD"
    val _clipboardItems = MutableStateFlow<List<ClipboardEntity>>(emptyList())
    val _navigationBarHeight = MutableStateFlow(0)
    val _recentEmojis = MutableStateFlow<List<String>>(emptyList())
    val _recentSymbols = MutableStateFlow<List<String>>(emptyList())

    // Dynamic Fcitx5-like input methods list and active state
    val inputMethods = listOf("Vietnamese", "Bamboo", "English", "Pinyin")
    val currentInputMethodIndex = MutableStateFlow(0) // 0: Vietnamese, 1: Bamboo, 2: English, 3: Pinyin

    private fun applyInputMethod(index: Int) {
        currentInputMethodIndex.value = index
        _languageMode.value = if (index == 0 || index == 1) "VIE" else "ENG"
        inputProcessor.clearState()
        currentInputConnection?.finishComposingText()
    }

    fun switchToNextInputMethod() {
        applyInputMethod((currentInputMethodIndex.value + 1) % inputMethods.size)
    }

    fun switchToPrevInputMethod() {
        applyInputMethod((currentInputMethodIndex.value - 1 + inputMethods.size) % inputMethods.size)
    }

    fun setInputMethod(index: Int) {
        applyInputMethod(index.coerceIn(0, inputMethods.size - 1))
    }

    override fun onCreate() {
        AppPreferences.init(applicationContext)
        setTheme(com.goviet.R.style.Theme_MyApplication)
        
        inputEngine.loadPreferences(this)
        
        // Instantiate specialized engine delegators
        inputProcessor = ImeInputConnectionController(this, inputEngine)
        keyboardUIManager = KeyboardUIManager(this)

        super.onCreate()
        window?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Initialize Databases & Repositories
        val db = ClipboardDatabase.getInstance(this)
        clipboardRepository = ClipboardRepository(db.clipboardDao())
        
        // Load recent emojis & symbols from SharedPreferences
        _recentEmojis.value = AppPreferences.getRecentEmojis()
        _recentSymbols.value = AppPreferences.getRecentSymbols()
        
        clipboardCoordinator = ClipboardCoordinator(this, clipboardRepository, serviceScope)
        clipboardCoordinator.initialize()
    }

    fun addRecentEmoji(emoji: String) {
        val current = _recentEmojis.value.toMutableList()
        current.remove(emoji)
        current.add(0, emoji)
        val truncated = current.take(24)
        _recentEmojis.value = truncated
        AppPreferences.setRecentEmojis(truncated)
    }

    fun addRecentSymbol(symbol: String) {
        val current = _recentSymbols.value.toMutableList()
        current.remove(symbol)
        current.add(0, symbol)
        val truncated = current.take(24)
        _recentSymbols.value = truncated
        AppPreferences.setRecentSymbols(truncated)
    }

    override fun onConfigureWindow(win: android.view.Window?, isInputViewShow: Boolean, isCandidatesKeyValue: Boolean) {
        super.onConfigureWindow(win, isInputViewShow, isCandidatesKeyValue)
        win?.let { w ->
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            }
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
    }

    override fun onDestroy() {
        inputEngine.cleanup()
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        serviceScope.cancel()
    }

    override fun onCreateInputView(): View {
        return keyboardUIManager.onCreateInputView()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        currentSelStart = info?.initialSelStart ?: 0
        currentSelEnd = info?.initialSelEnd ?: 0
        val packageName = info?.packageName ?: "unknown"
        val fieldId = info?.fieldId ?: 0
        val inputType = info?.inputType ?: 0
        super.onStartInputView(info, restarting)

        inputProcessor.updateTypingMode(info)

        // Ensure owners are propagated to the decor and view tree members upon focus
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        keyboardRootView?.let { krv ->
            krv.requestApplyInsets()
        }

        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            return
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        inputEngine.loadPreferences(this)
        inputEngine.reloadMacroStore(this)
        keyboardRootView?.render()
        
        inputProcessor.clearState()

        // Auto-switch to TPAD mode if input is numeric, password/number, or hints OTP/verification code
        val classType = inputType and EditorInfo.TYPE_MASK_CLASS
        val isNumeric = (classType == EditorInfo.TYPE_CLASS_NUMBER) || 
                        (classType == EditorInfo.TYPE_CLASS_PHONE) ||
                        (classType == EditorInfo.TYPE_CLASS_DATETIME)
        
        val hintLower = info?.hintText?.toString()?.lowercase() ?: ""
        val isOTPHint = hintLower.contains("otp") || hintLower.contains("code") || hintLower.contains("pin") || hintLower.contains("mã") || hintLower.contains("verify")
        
        val fieldNameLower = info?.fieldName?.lowercase() ?: ""
        val isOTPFieldId = fieldNameLower.contains("otp") || fieldNameLower.contains("code") || fieldNameLower.contains("pin") || fieldNameLower.contains("mã") || fieldNameLower.contains("verify")

        val isNumericPassword = (inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_NUMBER &&
                (inputType and EditorInfo.TYPE_MASK_VARIATION) == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD

        if (isNumeric || isOTPHint || isOTPFieldId || isNumericPassword) {
            _keyboardMode.value = "TPAD"
        } else {
            _keyboardMode.value = "QWERTY"
        }

        evaluateAutoShift()
    }

    var isSentenceStartCached: Boolean = true

    fun notifySentenceStateAfterKey(key: String) {
        if (BoundaryClassifier.isSentenceTerminator(key)) {
            isSentenceStartCached = true
            return
        }
        if (key == "SPACE" || key == " " || (key.length == 1 && BoundaryClassifier.isWhitespace(key[0]))) {
            // Keep the current isSentenceStartCached state
            return
        }
        if (key.length == 1 && (key[0].isLetterOrDigit() || BoundaryClassifier.isWordSeparator(key[0]))) {
            isSentenceStartCached = false
        }
    }

    fun evaluateAutoShift(forceIpc: Boolean = false) {
        if (shiftController.isCapsLock) return

        val info = currentInputEditorInfo
        if (isTerminalOrBrowserInput(info)) {
            shiftController.onSentenceStartLost()
            return
        }

        if (!inputEngine.autoCapitalize) return

        if (inputProcessor.composingRaw.isNotEmpty()) return

        val now = System.currentTimeMillis()
        if (!forceIpc && (now - inputProcessor.lastKeyPressTime < 350L)) {
            // Fast in-memory evaluation during continuous typing bursts without Binder IPC
            if (isSentenceStartCached) {
                shiftController.onSentenceStartDetected()
            } else {
                shiftController.onSentenceStartLost()
            }
            return
        }

        val ic = currentInputConnection
        if (ic != null) {
            if (isAtStartOfSentence(ic)) {
                isSentenceStartCached = true
                shiftController.onSentenceStartDetected()
            } else {
                isSentenceStartCached = false
                shiftController.onSentenceStartLost()
            }
        }
    }

    fun isTerminalOrBrowserInput(info: EditorInfo?): Boolean {
        if (info == null) return false
        val inputType = info.inputType
        if (inputType == EditorInfo.TYPE_NULL) return true

        val classType = inputType and EditorInfo.TYPE_MASK_CLASS
        if (classType == 0) return true

        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION

        // Non-capitalizing variations (URI, passwords, emails, filters)
        if (variation == EditorInfo.TYPE_TEXT_VARIATION_URI ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_FILTER ||
            variation == 224
        ) {
            return true
        }

        // Terminal apps
        val pkg = info.packageName?.lowercase() ?: ""
        if (pkg.contains("termux") || pkg.contains("terminal") || pkg.contains("connectbot") ||
            pkg.contains("ssh") || pkg.contains("juicessh") || pkg.contains("vnc") ||
            pkg.contains("console") || pkg.contains("shell") || pkg.contains("cmd")
        ) {
            return true
        }

        // Browser Native URL / Omnibox / Search bar detection
        if (pkg.contains("chrome") || pkg.contains("firefox") || pkg.contains("browser") ||
            pkg.contains("opera") || pkg.contains("duckduckgo") || pkg.contains("brave") ||
            pkg.contains("edge") || pkg.contains("sbrowser") || pkg.contains("yandex") ||
            pkg.contains("via") || pkg.contains("kiwi") || pkg.contains("uc") ||
            pkg.contains("baidu") || pkg.contains("samsung")
        ) {
            // WEB_EDIT_TEXT is an HTML input/textarea element on a web page -> ALLOW auto-cap!
            if (variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT) {
                return false
            }

            val fieldNameLower = info.fieldName?.lowercase() ?: ""
            val hintLower = info.hintText?.toString()?.lowercase() ?: ""
            val actionMasked = info.imeOptions and EditorInfo.IME_MASK_ACTION

            // Explicit address / URL / search bar fields in browser native UI
            val isExplicitUrlOrSearch = fieldNameLower.contains("url") ||
                    fieldNameLower.contains("omnibox") ||
                    fieldNameLower.contains("location") ||
                    fieldNameLower.contains("address") ||
                    fieldNameLower.contains("search_box") ||
                    fieldNameLower.contains("url_bar") ||
                    hintLower.contains("url") ||
                    hintLower.contains("address") ||
                    hintLower.contains("search or type") ||
                    hintLower.contains("tìm kiếm hoặc nhập") ||
                    hintLower.contains("nhập địa chỉ")

            if (isExplicitUrlOrSearch) {
                return true
            }

            if (actionMasked == EditorInfo.IME_ACTION_GO || actionMasked == EditorInfo.IME_ACTION_SEARCH) {
                return true
            }
        }

        return false
    }

    fun isAtStartOfSentence(ic: InputConnection?): Boolean {
        if (ic == null) return false
        if (inputProcessor.composingRaw.isNotEmpty()) return false

        val textBefore = ic.getTextBeforeCursor(32, 0)?.toString() ?: return true
        if (textBefore.isBlank()) return true

        val lastNewline = maxOf(textBefore.lastIndexOf('\n'), textBefore.lastIndexOf('\r'))
        if (lastNewline != -1) {
            val textAfterNewline = textBefore.substring(lastNewline + 1)
            if (textAfterNewline.isBlank()) {
                return true
            }
        }

        val trimmed = textBefore.trimEnd()
        if (trimmed.isEmpty()) return true

        val lastChar = trimmed.last()
        if (BoundaryClassifier.isSentenceTerminator(lastChar)) {
            val hasSpaceAfterPunctuation = textBefore.length > trimmed.length
            if (hasSpaceAfterPunctuation) {
                val len = trimmed.length
                if (len >= 2) {
                    val prevChar = trimmed[len - 2]
                    if (prevChar.isDigit() || prevChar == '@') {
                        return false
                    }
                }
                return true
            }
        }

        return false
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        
        inputProcessor.clearState()
        _keyboardMode.value = "QWERTY"
        keyboardRootView?.handleSettingsReset()
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        val superResult = super.onEvaluateFullscreenMode()
        return superResult
    }

    override fun onEvaluateInputViewShown(): Boolean {
        val superResult = super.onEvaluateInputViewShown()
        return true
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        currentSelStart = newSelStart
        currentSelEnd = newSelEnd
        inputProcessor.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        // Dynamically trace the word right before the cursor to keep suggestion context alive and accurate
        if (inputProcessor.composingRaw.isNotEmpty()) {
            lastCommittedWord = inputProcessor.lastSetComposingText ?: inputProcessor.compileComposingText()
        }

        val now = System.currentTimeMillis()
        val isIdle = (now - inputProcessor.lastKeyPressTime >= 350L)
        if (newSelStart == newSelEnd && inputProcessor.composingRaw.isEmpty()) {
            evaluateAutoShift(forceIpc = isIdle)
        }
    }

    internal fun getLastWordFromText(text: String): String? {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return null
        val lastSpace = trimmed.lastIndexOf(' ')
        val rawWord = if (lastSpace == -1) trimmed else trimmed.substring(lastSpace + 1)
        val cleaned = rawWord.filter { it.isLetter() || it.isDigit() }
        return if (cleaned.isNotEmpty()) cleaned else null
    }

    // Direct delegation APIs matching UI expectations perfectly
    fun handleKeyPress(key: String) {
        inputProcessor.handleKeyPress(key)
    }

    fun handleEditAction(action: String) {
        inputProcessor.handleEditAction(action)
    }

    fun selectClipboard(text: String) {
        clipboardCoordinator.selectClipboard(text)
    }

    fun clearClipboardHistory() {
        clipboardCoordinator.clearClipboardHistory()
    }

    fun deleteClipboardItem(item: ClipboardEntity) {
        clipboardCoordinator.deleteClipboardItem(item)
    }

    fun updateNavigationBarColor(color: Int, isDark: Boolean) {
        keyboardUIManager.updateNavigationBarColor(color, isDark)
    }

    fun getNavigationBarHeight(): Int {
        return keyboardUIManager.getNavigationBarHeight()
    }

    fun commitAndReset() {
        inputProcessor.commitAndReset()
    }

    fun commitAndFinishing() {
        commitAndReset()
    }

    fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        keyboardUIManager.notifyConfigurationChanged()
    }
}
