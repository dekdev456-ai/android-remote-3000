package com.firework.remote3000

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

class FloatingBuddyService : Service() {

    companion object {
        const val ACTION_START = "com.firework.remote3000.FLOATING_START"
        const val ACTION_STOP = "com.firework.remote3000.FLOATING_STOP"
    }

    private lateinit var windowManager: WindowManager
    private var rootLayout: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var buddyView: BuddyView? = null
    private var popupPanel: LinearLayout? = null
    private var tvSpeech: TextView? = null
    private var etInput: EditText? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isExpanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep floating buddy alive over TikTok & other apps
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            ServiceState.floatingBuddyRunning.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (rootLayout == null) {
            initTts()
            createOverlay()
        }
        ServiceState.floatingBuddyRunning.value = true
        return START_STICKY
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("th", "TH"))
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) {
                    tts?.language = Locale.getDefault()
                    ttsReady = true
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        buddyView?.post { buddyView?.setMood(BuddyView.Mood.SPEAKING) }
                    }
                    override fun onDone(utteranceId: String?) {
                        buddyView?.post { buddyView?.setMood(BuddyView.Mood.IDLE) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        buddyView?.post { buddyView?.setMood(BuddyView.Mood.IDLE) }
                    }
                })
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun createOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        // Expandable mini chat bubble over TikTok/apps
        val panelBg = GradientDrawable().apply {
            setColor(Color.argb(235, 10, 16, 32))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.parseColor("#00F5D4"))
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(250), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = "✨ น้องพลุ (ลอยทับแอป)"
            setTextColor(Color.parseColor("#00F5D4"))
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closePanelBtn = TextView(this).apply {
            text = "✕ ซ่อน"
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 11.5f
            setPadding(dp(6), dp(2), dp(4), dp(2))
            setOnClickListener { toggleExpanded(false) }
        }
        headerRow.addView(titleTv)
        headerRow.addView(closePanelBtn)
        panel.addView(headerRow)

        val speech = TextView(this).apply {
            text = "✨ หวัดดีคู่หู! ผมลอยดู TikTok เป็นเพื่อนอยู่นะ กดหาเรื่องคุยหรือพิมพ์คุยได้เลย!"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 12f
            setPadding(dp(4), dp(6), dp(4), dp(8))
        }
        tvSpeech = speech
        panel.addView(speech)

        // Quick action row
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun makeChip(label: String, onClick: () -> Unit): Button = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 10.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(4) }
            setOnClickListener { onClick() }
        }
        quickRow.addView(makeChip("🎲 คุยเล่น") { speakReply(BuddyBrain.nextFunTopic()) })
        quickRow.addView(makeChip("✊ เป่ายิ้งฉุบ") { speakReply(BuddyBrain.playRps(null)) })
        quickRow.addView(makeChip("🚫 ปิดลอย") {
            removeOverlay()
            stopSelf()
        })
        panel.addView(quickRow)

        // Mini input row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val input = EditText(this).apply {
            hint = "พิมพ์คุยกับน้องพลุ..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.parseColor("#040814"))
            textSize = 12f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
        }
        etInput = input
        val sendBtn = Button(this).apply {
            text = "ส่ง"
            isAllCaps = false
            textSize = 11.5f
            setTextColor(Color.parseColor("#04101F"))
            setBackgroundColor(Color.parseColor("#00F5D4"))
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(38))
            setOnClickListener {
                val txt = input.text.toString().trim()
                if (txt.isNotEmpty()) {
                    input.setText("")
                    val ans = BuddyBrain.reply(txt)
                    tvSpeech?.text = "🧑 คุณ: $txt\n✨ น้องพลุ: $ans"
                    buddyView?.setMood(BuddyView.Mood.HAPPY)
                    speakOut(ans)
                }
            }
        }
        inputRow.addView(input)
        inputRow.addView(sendBtn)
        panel.addView(inputRow)

        popupPanel = panel
        container.addView(panel)

        // Floating draggable animated BuddyView
        val buddy = BuddyView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(78), dp(78))
        }
        buddyView = buddy
        container.addView(buddy)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(220)
        }
        layoutParams = params

        // Smooth drag & tap handler on BuddyView
        var downX = 0f
        var downY = 0f
        var initX = 0
        var initY = 0
        var dragged = false
        buddy.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    initX = params.x
                    initY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) dragged = true
                    params.x = initX + dx
                    params.y = initY + dy
                    try {
                        windowManager.updateViewLayout(container, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        buddy.triggerSparkles()
                        toggleExpanded(!isExpanded)
                        if (isExpanded) {
                            speakReply(BuddyBrain.onTap())
                        }
                    }
                    true
                }
                else -> false
            }
        }

        rootLayout = container
        windowManager.addView(container, params)
    }

    private fun toggleExpanded(expand: Boolean) {
        isExpanded = expand
        popupPanel?.visibility = if (expand) View.VISIBLE else View.GONE
        val p = layoutParams ?: return
        p.flags = if (expand) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        try {
            rootLayout?.let { windowManager.updateViewLayout(it, p) }
        } catch (_: Exception) {
        }
    }

    private fun speakReply(msg: String) {
        tvSpeech?.text = "✨ น้องพลุ: $msg"
        buddyView?.setMood(BuddyView.Mood.EXCITED)
        speakOut(msg)
    }

    private fun speakOut(msg: String) {
        if (!ttsReady) return
        val clean = msg.replace(Regex("[\\p{So}\\p{Cn}]"), "").trim()
        if (clean.isNotEmpty()) {
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "fb_${System.currentTimeMillis()}")
        }
    }

    private fun removeOverlay() {
        ServiceState.floatingBuddyRunning.value = false
        try {
            rootLayout?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
        }
        rootLayout = null
        buddyView = null
        popupPanel = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}
