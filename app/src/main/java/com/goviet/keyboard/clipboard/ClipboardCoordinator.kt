package com.goviet.keyboard.clipboard

import com.goviet.keyboard.VietnameseInputMethodService
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ClipboardCoordinator(
    private val service: VietnameseInputMethodService,
    private val clipboardRepository: ClipboardRepository,
    private val scope: CoroutineScope
) {
    private val TAG = "ClipboardCoordinator"

    fun initialize() {
        // Monitor system clipboard changes
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener {
            try {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString() ?: ""
                    if (text.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                clipboardRepository.insert(text)
                            } catch (e: Exception) {
                                // Suppress room error under background sync thread
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                // Suppress clipboard security exceptions when the IME is not default yet or doesn't have focus
            } catch (e: Exception) {
                // Background safety fallback
            }
        }

        // Collect clipboard list to expose to StateFlow
        scope.launch {
            clipboardRepository.allClipboardItems.collect { items ->
                service._clipboardItems.value = items
            }
        }
    }

    fun selectClipboard(text: String) {
        val ic: InputConnection = service.currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            service.inputProcessor.commitAndFinishing()
            ic.commitText(text, 1)
        } finally {
            ic.endBatchEdit()
        }
    }

    fun clearClipboardHistory() {
        scope.launch(Dispatchers.IO) {
            clipboardRepository.clear()
        }
    }

    fun deleteClipboardItem(item: ClipboardEntity) {
        scope.launch(Dispatchers.IO) {
            clipboardRepository.delete(item)
        }
    }
}
