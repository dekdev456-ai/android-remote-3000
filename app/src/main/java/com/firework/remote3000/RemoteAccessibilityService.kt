package com.firework.remote3000

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ServiceState.accessibilityEnabled.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for remote gesture injection
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        ServiceState.accessibilityEnabled.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        ServiceState.accessibilityEnabled.value = false
        super.onDestroy()
    }

    private fun getScreenBounds(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width().coerceAtLeast(1), b.height().coerceAtLeast(1))
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))
        }
    }

    fun tap(normX: Float, normY: Float): Boolean {
        val (w, h) = getScreenBounds()
        val x = (normX.coerceIn(0f, 1f) * w).coerceIn(1f, (w - 1).toFloat())
        val y = (normY.coerceIn(0f, 1f) * h).coerceIn(1f, (h - 1).toFloat())
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 35L))
            .build()
        mainHandler.post { dispatchGesture(gesture, null, null) }
        return true
    }

    fun longPress(normX: Float, normY: Float, durationMs: Long = 600L): Boolean {
        val (w, h) = getScreenBounds()
        val x = (normX.coerceIn(0f, 1f) * w).coerceIn(1f, (w - 1).toFloat())
        val y = (normY.coerceIn(0f, 1f) * h).coerceIn(1f, (h - 1).toFloat())
        val path = Path().apply { moveTo(x, y) }
        val dur = durationMs.coerceIn(300L, 2500L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, dur))
            .build()
        mainHandler.post { dispatchGesture(gesture, null, null) }
        return true
    }

    fun swipe(
        normX1: Float,
        normY1: Float,
        normX2: Float,
        normY2: Float,
        durationMs: Long = 180L
    ): Boolean {
        val (w, h) = getScreenBounds()
        val x1 = (normX1.coerceIn(0f, 1f) * w).coerceIn(1f, (w - 1).toFloat())
        val y1 = (normY1.coerceIn(0f, 1f) * h).coerceIn(1f, (h - 1).toFloat())
        val x2 = (normX2.coerceIn(0f, 1f) * w).coerceIn(1f, (w - 1).toFloat())
        val y2 = (normY2.coerceIn(0f, 1f) * h).coerceIn(1f, (h - 1).toFloat())
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val dur = durationMs.coerceIn(50L, 1200L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, dur))
            .build()
        mainHandler.post { dispatchGesture(gesture, null, null) }
        return true
    }

    fun performGlobal(actionName: String): Boolean {
        val actionId = when (actionName.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            "screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                GLOBAL_ACTION_TAKE_SCREENSHOT
            } else {
                return false
            }
            "lock" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                GLOBAL_ACTION_LOCK_SCREEN
            } else {
                return false
            }
            else -> return false
        }
        mainHandler.post { performGlobalAction(actionId) }
        return true
    }

    private fun getRealText(node: AccessibilityNodeInfo): String {
        val isHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        return if (isHint) "" else (node.text?.toString() ?: "")
    }

    private fun setTextAndMoveCursorToEnd(node: AccessibilityNodeInfo, newText: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        }
        return ok
    }

    fun inputText(text: String, append: Boolean = true): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val current = if (append) getRealText(focused) else ""
        val newText = current + text
        return setTextAndMoveCursorToEnd(focused, newText)
    }

    fun sendBackspace(): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val existing = getRealText(focused)
        if (existing.isEmpty()) return true
        val trimmed = existing.dropLast(1)
        return setTextAndMoveCursorToEnd(focused, trimmed)
    }

    fun sendEnter(): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                return true
            }
        }
        val current = getRealText(focused)
        return setTextAndMoveCursorToEnd(focused, current + "\n")
    }

    fun pasteText(text: String): Boolean {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("RemotePC", text))
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return true
        val current = getRealText(focused)
        return setTextAndMoveCursorToEnd(focused, current + text)
    }
}
