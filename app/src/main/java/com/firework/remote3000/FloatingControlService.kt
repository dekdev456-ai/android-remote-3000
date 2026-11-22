package com.firework.remote3000

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Persistent draggable floating round overlay button (over all apps):
 * - When server is RUNNING -> Red circle ("⏻ ปิด"): tap to STOP server immediately
 * - When server is STOPPED -> Green circle ("▶ เปิด"): tap to START server immediately
 */
class FloatingControlService : Service() {

    companion object {
        const val ACTION_SHOW = "com.firework.remote3000.FLOATING_CTRL_SHOW"
        const val ACTION_HIDE = "com.firework.remote3000.FLOATING_CTRL_HIDE"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    private var windowManager: WindowManager? = null
    private var circleBox: LinearLayout? = null
    private var circleBg: GradientDrawable? = null
    private var iconTv: TextView? = null
    private var labelTv: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep floating toggle button alive over all apps even when app is swiped away
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (circleBox == null) {
            createOverlay()
        }
        updateButtonAppearance(ServiceState.isRunning.value)
        startObservingServerState()
        return START_STICKY
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm
            val btnSize = dp(58)

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6EF4444"))
                setStroke(dp(2), Color.parseColor("#FFFFFF"))
            }
            circleBg = bg

            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = bg
                elevation = dp(8).toFloat()
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            }

            val ic = TextView(this).apply {
                text = "⏻"
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            val lbl = TextView(this).apply {
                text = "ปิด"
                setTextColor(Color.WHITE)
                textSize = 9.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            iconTv = ic
            labelTv = lbl
            box.addView(ic)
            box.addView(lbl)

            val params = WindowManager.LayoutParams(
                btnSize,
                btnSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (resources.displayMetrics.widthPixels - btnSize - dp(12)).coerceAtLeast(dp(12))
                y = dp(140)
            }
            layoutParams = params

            var downX = 0f
            var downY = 0f
            var initX = 0
            var initY = 0
            var dragged = false

            box.setOnTouchListener { v: View, event: MotionEvent ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        initX = params.x
                        initY = params.y
                        dragged = false
                        v.scaleX = 0.90f
                        v.scaleY = 0.90f
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) dragged = true
                        if (dragged) {
                            params.x = initX + dx
                            params.y = initY + dy
                            try {
                                wm.updateViewLayout(v, params)
                            } catch (_: Exception) {
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.scaleX = 1.0f
                        v.scaleY = 1.0f
                        if (!dragged) {
                            onFloatingButtonTapped()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.scaleX = 1.0f
                        v.scaleY = 1.0f
                        true
                    }
                    else -> false
                }
            }

            wm.addView(box, params)
            circleBox = box
        } catch (_: Exception) {
        }
    }

    private fun onFloatingButtonTapped() {
        if (ServiceState.isRunning.value) {
            Toast.makeText(applicationContext, "⏹ ปิดเซิร์ฟเวอร์ :3000 แล้ว (แตะปุ่มเขียวเพื่อเปิดใหม่)", Toast.LENGTH_SHORT).show()
            val stopIntent = Intent(this, RemoteScreenService::class.java).apply {
                action = RemoteScreenService.ACTION_STOP
            }
            startService(stopIntent)
        } else {
            Toast.makeText(applicationContext, "▶ กำลังเปิดเซิร์ฟเวอร์ :3000...", Toast.LENGTH_SHORT).show()
            val openIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_AUTO_START_SERVER, true)
            }
            startActivity(openIntent)
        }
    }

    private fun startObservingServerState() {
        if (stateJob != null) return
        stateJob = scope.launch {
            ServiceState.isRunning.collect { running ->
                updateButtonAppearance(running)
            }
        }
    }

    private fun updateButtonAppearance(isRunning: Boolean) {
        if (isRunning) {
            circleBg?.setColor(Color.parseColor("#E6EF4444")) // Crimson Red = Stop
            circleBg?.setStroke(dp(2), Color.parseColor("#FFFFFF"))
            iconTv?.text = "⏻"
            labelTv?.text = "ปิด"
        } else {
            circleBg?.setColor(Color.parseColor("#E610B981")) // Emerald Green = Start
            circleBg?.setStroke(dp(2), Color.parseColor("#FFFFFF"))
            iconTv?.text = "▶"
            labelTv?.text = "เปิด"
        }
        circleBox?.invalidate()
    }

    private fun removeOverlay() {
        stateJob?.cancel()
        stateJob = null
        try {
            circleBox?.let { v -> windowManager?.removeView(v) }
        } catch (_: Exception) {
        }
        circleBox = null
        circleBg = null
        iconTv = null
        labelTv = null
        layoutParams = null
    }

    override fun onDestroy() {
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }
}
