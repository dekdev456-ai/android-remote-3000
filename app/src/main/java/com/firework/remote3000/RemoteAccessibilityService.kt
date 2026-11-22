package com.firework.remote3000

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.LinkedHashMap

class RemoteAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val capturedChatMap = Collections.synchronizedMap(LinkedHashMap<String, JSONObject>())

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set

        fun isEnabledInSystemSettings(context: Context): Boolean {
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val target = ComponentName(context, RemoteAccessibilityService::class.java).flattenToString()
                val shortTarget = ComponentName(context, RemoteAccessibilityService::class.java).flattenToShortString()
                enabled.split(':').any { it.equals(target, ignoreCase = true) || it.equals(shortTarget, ignoreCase = true) }
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        RemotePrefs.loadIntoState(this)
        instance = this
        ServiceState.accessibilityEnabled.value = true
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Keep Accessibility Service alive when user swipes app away from Recents
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No heavy background work here; scraping runs on demand
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

    fun triggerNativePaste(): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.let { findEditableNode(it) }
            ?: return false
        focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    // ================= TIKTOK / ACTIVE APP CHAT BRIDGE =================

    fun clearCapturedChat() {
        capturedChatMap.clear()
    }

    fun openTikTokApp(): Pair<Boolean, String> {
        val pkgs = listOf("com.ss.android.ugc.trill", "com.zhiliaoapp.musically")
        for (pkg in pkgs) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return Pair(true, "เปิดแอป TikTok บนมือถือแล้ว")
            }
        }
        return Pair(false, "ไม่พบแอป TikTok บนมือถือเครื่องนี้")
    }

    fun findAndClickText(keyword: String): Pair<Boolean, String> {
        val root = rootInActiveWindow ?: return Pair(false, "ยังอ่านหน้าจอไม่ได้")
        val target = findNodeContainingText(root, keyword.trim())
            ?: return Pair(false, "ยังไม่เจอคำว่า '$keyword' บนหน้าจอตอนนี้ (กรุณาเปิดหน้ากล่องข้อความ TikTok ก่อน)")

        val rect = Rect()
        target.getBoundsInScreen(rect)
        val (w, h) = getScreenBounds()
        if (!rect.isEmpty && w > 0 && h > 0) {
            tap(rect.centerX().toFloat() / w, rect.centerY().toFloat() / h)
            return Pair(true, "แตะเข้าห้อง '$keyword' แล้ว")
        }
        var cur: AccessibilityNodeInfo? = target
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return Pair(true, "แตะเข้าห้อง '$keyword' แล้ว")
            }
            cur = cur.parent
        }
        return Pair(false, "เจอ '$keyword' แต่กดไม่สำเร็จ")
    }

    private fun findNodeContainingText(node: AccessibilityNodeInfo?, keyword: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val t = node.text?.toString() ?: ""
        val d = node.contentDescription?.toString() ?: ""
        if (t.contains(keyword, ignoreCase = true) || d.contains(keyword, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findNodeContainingText(node.getChild(i), keyword)
            if (found != null) return found
        }
        return null
    }

    fun scrapeActiveAppChat(): JSONObject {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: ""
        val isTikTok = pkg.contains("trill", true) || pkg.contains("musically", true) || pkg.contains("tiktok", true)

        // Strict TikTok-only guard: never scrape Home Screen, Settings, or any other app!
        if (!isTikTok || root == null) {
            return JSONObject()
                .put("ok", false)
                .put("isTikTok", false)
                .put("packageName", pkg)
                .put("error", "ดึงได้เฉพาะบนแอป TikTok เท่านั้น — กรุณาเปิดห้องแชทใน TikTok บนมือถือก่อน")
        }

        val (screenW, screenH) = getScreenBounds()

        // Find TikTok's exact chat message list RecyclerView (excludes top bar, menus, bottom tabs & input bar!)
        var chatContainer: AccessibilityNodeInfo? = null
        var bestContainerArea = 0
        fun findChatList(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("RecyclerView", true) || cls.contains("ListView", true)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (r.height() > (screenH * 0.35).toInt() && r.width() > (screenW * 0.75).toInt() && area > bestContainerArea) {
                    bestContainerArea = area
                    chatContainer = node
                }
            }
            for (i in 0 until node.childCount) findChatList(node.getChild(i))
        }
        findChatList(root)

        val containerRect = Rect()
        if (chatContainer != null) {
            chatContainer!!.getBoundsInScreen(containerRect)
        } else {
            // Safe fallback strictly inside middle chat band (excludes top header & bottom bar)
            containerRect.set(0, (screenH * 0.13).toInt(), screenW, (screenH * 0.86).toInt())
        }

        val chatTopRatio = (containerRect.top.toFloat() / screenH.toFloat()).coerceIn(0.08f, 0.35f)
        val chatBottomRatio = (containerRect.bottom.toFloat() / screenH.toFloat()).coerceIn(0.55f, 0.92f)

        val visibleItems = mutableListOf<Triple<Int, String, String>>() // (y, side, text)

        val menuIgnoreSet = setOf(
            "ส่งข้อความ...", "message...", "send a message...", "send", "ส่ง",
            "back", "ย้อนกลับ", "more", "เพิ่มเติม", "emoji", "sticker", "สติกเกอร์",
            "camera", "กล้อง", "หน้าหลัก", "home", "เพื่อน", "friends", "กล่องข้อความ",
            "inbox", "โปรไฟล์", "profile", "ร้านค้า", "shop", "ค้นหา", "search",
            "กำลังใช้งาน", "active now", "ใช้งานเมื่อสักครู่", "ดูทั้งหมด", "รายงาน",
            "วิดีโอคอล", "โทร", "เมนู", "menu", "ติดตาม", "follow", "กำลังติดตาม",
            "following", "ถูกใจ", "like", "แชร์", "share", "บันทึก", "save",
            "ตอบกลับ", "reply", "ส่งต่อ", "forward", "ลบ", "delete", "คัดลอก", "copy"
        )

        val walkRoot = chatContainer ?: root
        fun walkChatOnly(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val cls = node.className?.toString().orEmpty()
            // Skip EditText and navigation/menu Buttons completely
            if (node.isEditable || cls.contains("EditText", true) || cls.contains("Button", true)) return

            val r = Rect()
            node.getBoundsInScreen(r)
            val strictlyInsideChat = r.top >= containerRect.top && r.bottom <= containerRect.bottom && r.height() > 0

            if (strictlyInsideChat) {
                val txt = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()

                if (txt.isNotEmpty() && txt.length <= 600 && !menuIgnoreSet.contains(txt.lowercase())) {
                    // Exclude tiny profile initials / single icon glyphs at far edges
                    val isLikelyChatText = r.left > (screenW * 0.08).toInt() && r.right < (screenW * 0.96).toInt()
                    if (isLikelyChatText) {
                        val centerRatio = r.centerX().toFloat() / screenW.toFloat()
                        val side = when {
                            centerRatio > 0.56f -> "me"
                            centerRatio < 0.44f -> "them"
                            else -> "meta"
                        }
                        visibleItems.add(Triple(r.top, side, txt))
                    }
                } else if (cls.contains("Image", true)) {
                    // Only capture real TikTok Stickers / Chat Photos / Shared Videos (ignore small circular avatars <= 130px!)
                    val isAvatarLeft = r.left < (screenW * 0.14).toInt() && r.width() < (screenW * 0.16).toInt()
                    val isBigStickerOrMedia = !isAvatarLeft && r.width() >= (screenW * 0.18).toInt() && r.height() >= (screenW * 0.18).toInt()
                    if (isBigStickerOrMedia) {
                        val centerRatio = r.centerX().toFloat() / screenW.toFloat()
                        val side = when {
                            centerRatio > 0.55f -> "me"
                            centerRatio < 0.45f -> "them"
                            else -> "meta"
                        }
                        val label = when {
                            desc.contains("sticker", true) || desc.contains("สติกเกอร์", true) -> "🎨 [สติกเกอร์ TikTok]"
                            desc.contains("video", true) || desc.contains("วิดีโอ", true) -> "🎬 [วิดีโอ TikTok]"
                            else -> "🎨 [สติกเกอร์ / รูปภาพ]"
                        }
                        visibleItems.add(Triple(r.top, side, label))
                    }
                }
            }

            for (i in 0 until node.childCount) walkChatOnly(node.getChild(i))
        }
        walkChatOnly(walkRoot)

        visibleItems.sortBy { it.first }
        for ((_, side, txt) in visibleItems) {
            val key = "$side::$txt"
            if (!capturedChatMap.containsKey(key)) {
                capturedChatMap[key] = JSONObject()
                    .put("side", side)
                    .put("text", txt)
                if (capturedChatMap.size > 400) {
                    val firstKey = capturedChatMap.keys.firstOrNull()
                    if (firstKey != null) capturedChatMap.remove(firstKey)
                }
            }
        }

        val arr = JSONArray()
        synchronized(capturedChatMap) {
            for (v in capturedChatMap.values) arr.put(v)
        }

        return JSONObject()
            .put("ok", true)
            .put("packageName", pkg)
            .put("isTikTok", true)
            .put("roomTitle", "TikTok Chat")
            .put("chatTopRatio", chatTopRatio.toDouble())
            .put("chatBottomRatio", chatBottomRatio.toDouble())
            .put("visibleCount", visibleItems.size)
            .put("totalCount", arr.length())
            .put("messages", arr)
    }

    fun sendChatToActiveApp(text: String): Pair<Boolean, String> {
        val clean = text.trim()
        if (clean.isEmpty()) return Pair(false, "ข้อความว่าง")
        val root = rootInActiveWindow ?: return Pair(false, "ไม่พบหน้าต่างแอปที่เปิดอยู่")

        val editNode = findEditableNode(root)
            ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return Pair(false, "ไม่พบช่องพิมพ์ข้อความบนหน้าจอ (กรุณาเปิดห้องแชท TikTok ก่อน)")

        val editRect = Rect()
        editNode.getBoundsInScreen(editRect)
        editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val setOk = setTextAndMoveCursorToEnd(editNode, clean)
        if (!setOk) {
            pasteText(clean)
        }

        // Wait briefly for Send button to appear after typing, then tap Send!
        mainHandler.postDelayed({
            val freshRoot = rootInActiveWindow ?: return@postDelayed
            val sendBtn = findSendButton(freshRoot, editRect)
            if (sendBtn != null) {
                val r = Rect()
                sendBtn.getBoundsInScreen(r)
                val (w, h) = getScreenBounds()
                if (!sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK) && !r.isEmpty) {
                    tap(r.centerX().toFloat() / w, r.centerY().toFloat() / h)
                }
            } else {
                // Fallback: tap rightmost edge on the same row as the EditText (where TikTok's red Send arrow sits)
                val (w, h) = getScreenBounds()
                if (!editRect.isEmpty && w > 0 && h > 0) {
                    tap(0.93f, editRect.centerY().toFloat() / h)
                } else {
                    sendEnter()
                }
            }
        }, 180L)

        return Pair(true, "ส่งข้อความ '$clean' เข้าแอปบนมือถือแล้ว")
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        if (node.isEditable || node.className?.toString()?.contains("EditText", true) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findSendButton(root: AccessibilityNodeInfo?, editRect: Rect): AccessibilityNodeInfo? {
        if (root == null) return null
        var bestByLabel: AccessibilityNodeInfo? = null
        var bestByPos: AccessibilityNodeInfo? = null
        var bestRightX = -1

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val label = ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).lowercase()
            if (label.contains("ส่ง") || label.contains("send") || label.contains("โพสต์") || label.contains("post")) {
                if (node.isClickable) bestByLabel = node
            }
            if (node.isClickable && !node.isEditable && !editRect.isEmpty) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val sameRow = kotlin.math.abs(r.centerY() - editRect.centerY()) < (editRect.height().coerceAtLeast(60) * 1.4f)
                if (sameRow && r.left >= editRect.right - 20 && r.centerX() > bestRightX) {
                    bestRightX = r.centerX()
                    bestByPos = node
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return bestByLabel ?: bestByPos
    }
}
