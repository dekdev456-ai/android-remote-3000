package com.firework.remote3000

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatusBadge: TextView
    private lateinit var tvLocalUrl: TextView
    private lateinit var tvLanUrl: TextView
    private lateinit var tvPinCode: TextView
    private lateinit var tvClients: TextView
    private lateinit var tvClientsList: TextView
    private lateinit var tvSecurityAlerts: TextView
    private lateinit var tvAccStatus: TextView
    private lateinit var tvError: TextView
    private lateinit var tvChatLog: TextView
    private lateinit var etPhoneChat: EditText
    private lateinit var btnSendPhoneChat: Button
    private lateinit var etUpdateUrl: EditText
    private lateinit var btnDownloadUpdate: Button
    private lateinit var switchPin: SwitchMaterial
    private lateinit var switchAllowControl: SwitchMaterial
    private lateinit var switchKeepAwake: SwitchMaterial
    private lateinit var btnToggleServer: Button
    private lateinit var btnCopyLan: Button
    private lateinit var btnRefreshIp: Button
    private lateinit var btnNewPin: Button
    private lateinit var btnOpenAccessibility: Button
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnIgnoreBattery: Button
    private lateinit var btnAppSettings: Button

    // Companion Buddy UI
    private lateinit var buddyView: BuddyView
    private lateinit var tvBuddySpeech: TextView
    private lateinit var etBuddyInput: EditText
    private lateinit var btnBuddySend: Button
    private lateinit var btnBuddyMic: Button
    private lateinit var btnBuddyTopic: Button
    private lateinit var btnBuddyGame: Button
    private lateinit var btnToggleFloatingBuddy: Button
    private lateinit var switchBuddyVoice: SwitchMaterial
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val runtimePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        requestScreenCapture()
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        buddyView.setMood(BuddyView.Mood.IDLE)
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) {
                handleBuddyUserMessage(spoken)
            }
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svcIntent = Intent(this, RemoteScreenService::class.java).apply {
                action = RemoteScreenService.ACTION_START
                putExtra(RemoteScreenService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RemoteScreenService.EXTRA_RESULT_DATA, result.data)
                putExtra(RemoteScreenService.EXTRA_PORT, ServiceState.DEFAULT_PORT)
            }
            ContextCompat.startForegroundService(this, svcIntent)
        } else {
            Toast.makeText(this, "ยกเลิกการแชร์หน้าจอ", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvLocalUrl = findViewById(R.id.tvLocalUrl)
        tvLanUrl = findViewById(R.id.tvLanUrl)
        tvPinCode = findViewById(R.id.tvPinCode)
        tvClients = findViewById(R.id.tvClients)
        tvClientsList = findViewById(R.id.tvClientsList)
        tvSecurityAlerts = findViewById(R.id.tvSecurityAlerts)
        tvAccStatus = findViewById(R.id.tvAccStatus)
        tvError = findViewById(R.id.tvError)
        tvChatLog = findViewById(R.id.tvChatLog)
        etPhoneChat = findViewById(R.id.etPhoneChat)
        btnSendPhoneChat = findViewById(R.id.btnSendPhoneChat)
        etUpdateUrl = findViewById(R.id.etUpdateUrl)
        btnDownloadUpdate = findViewById(R.id.btnDownloadUpdate)
        switchPin = findViewById(R.id.switchPin)
        switchAllowControl = findViewById(R.id.switchAllowControl)
        switchKeepAwake = findViewById(R.id.switchKeepAwake)
        btnToggleServer = findViewById(R.id.btnToggleServer)
        btnCopyLan = findViewById(R.id.btnCopyLan)
        btnRefreshIp = findViewById(R.id.btnRefreshIp)
        btnNewPin = findViewById(R.id.btnNewPin)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        btnIgnoreBattery = findViewById(R.id.btnIgnoreBattery)
        btnAppSettings = findViewById(R.id.btnAppSettings)

        // Load persistent saved settings so closing & reopening NEVER turns off control or resets PIN
        RemotePrefs.loadIntoState(this)

        // Buddy views
        buddyView = findViewById(R.id.buddyView)
        tvBuddySpeech = findViewById(R.id.tvBuddySpeech)
        etBuddyInput = findViewById(R.id.etBuddyInput)
        btnBuddySend = findViewById(R.id.btnBuddySend)
        btnBuddyMic = findViewById(R.id.btnBuddyMic)
        btnBuddyTopic = findViewById(R.id.btnBuddyTopic)
        btnBuddyGame = findViewById(R.id.btnBuddyGame)
        btnToggleFloatingBuddy = findViewById(R.id.btnToggleFloatingBuddy)
        switchBuddyVoice = findViewById(R.id.switchBuddyVoice)

        initBuddyTts()
        setupBuddyInteractions()

        ServiceState.lanIp.value = NetworkUtils.getLocalIpv4Address()
        switchPin.isChecked = ServiceState.pinRequired.value
        switchAllowControl.isChecked = ServiceState.controlAllowed.value
        switchKeepAwake.isChecked = ServiceState.keepScreenAwake.value

        btnToggleServer.setOnClickListener {
            if (ServiceState.isRunning.value) {
                stopServer()
            } else {
                startServerFlow()
            }
        }

        btnCopyLan.setOnClickListener {
            val url = "http://${ServiceState.lanIp.value}:${ServiceState.port.value}"
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("Remote URL", url))
            Toast.makeText(this, "คัดลอกลิงก์ $url แล้ว", Toast.LENGTH_SHORT).show()
        }

        btnRefreshIp.setOnClickListener {
            ServiceState.lanIp.value = NetworkUtils.getLocalIpv4Address()
            Toast.makeText(this, "อัปเดต IP แล้ว: ${ServiceState.lanIp.value}", Toast.LENGTH_SHORT).show()
        }

        btnNewPin.setOnClickListener {
            RemotePrefs.setPin(this, NetworkUtils.generatePin())
        }

        switchPin.setOnCheckedChangeListener { _, checked ->
            RemotePrefs.setPinRequired(this, checked)
        }

        switchAllowControl.setOnCheckedChangeListener { _, checked ->
            RemotePrefs.setControlAllowed(this, checked)
            ServiceState.onClientsChangedBroadcast?.invoke()
            val msg = if (checked) {
                "✅ เปิดให้คอมพิวเตอร์ควบคุมหน้าจอได้ตลอด (บันทึกถาวร)"
            } else {
                "🛡️ ปิดการควบคุมจอแล้ว — หากมีคนพยายามแตะ/คุม จะแจ้งเตือนทันที!"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        switchKeepAwake.setOnCheckedChangeListener { _, checked ->
            RemotePrefs.setKeepAwake(this, checked)
            ServiceState.onKeepAwakeChanged?.invoke(checked)
        }

        btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnIgnoreBattery.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        btnAppSettings.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        btnSendPhoneChat.setOnClickListener {
            val text = etPhoneChat.text.toString().trim()
            if (text.isNotEmpty()) {
                ServiceState.addChatMessage("Phone", text)
                etPhoneChat.setText("")
            }
        }

        btnDownloadUpdate.setOnClickListener {
            val urlStr = etUpdateUrl.text.toString().trim()
            if (urlStr.startsWith("http://") || urlStr.startsWith("https://")) {
                downloadAndInstallApk(urlStr)
            } else {
                Toast.makeText(this, "กรุณาวางลิงก์ .apk ที่ขึ้นต้นด้วย http:// หรือ https://", Toast.LENGTH_SHORT).show()
            }
        }

        observeState()
    }

    private fun initBuddyTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val th = Locale("th", "TH")
                val res = tts?.setLanguage(th)
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) {
                    tts?.language = Locale.getDefault()
                    ttsReady = true
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        runOnUiThread { buddyView.setMood(BuddyView.Mood.SPEAKING) }
                    }
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread { buddyView.setMood(BuddyView.Mood.IDLE) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { buddyView.setMood(BuddyView.Mood.IDLE) }
                    }
                })
            }
        }
    }

    private fun setupBuddyInteractions() {
        buddyView.onBuddyTap = {
            buddySay(BuddyBrain.onTap(), BuddyView.Mood.EXCITED)
        }
        btnBuddyTopic.setOnClickListener {
            buddySay(BuddyBrain.nextFunTopic(), BuddyView.Mood.HAPPY)
        }
        btnBuddyGame.setOnClickListener {
            buddySay(BuddyBrain.playRps(null), BuddyView.Mood.EXCITED)
        }
        btnBuddySend.setOnClickListener {
            val txt = etBuddyInput.text.toString().trim()
            if (txt.isNotEmpty()) {
                etBuddyInput.setText("")
                handleBuddyUserMessage(txt)
            }
        }
        btnBuddyMic.setOnClickListener {
            startVoiceRecognition()
        }
        btnToggleFloatingBuddy.setOnClickListener {
            if (ServiceState.floatingBuddyRunning.value) {
                startService(Intent(this, FloatingBuddyService::class.java).apply {
                    action = FloatingBuddyService.ACTION_STOP
                })
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "กรุณาอนุญาต 'แสดงทับแอปอื่น' เพื่อให้คู่หูลอยบน TikTok ได้", Toast.LENGTH_LONG).show()
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                } else {
                    startService(Intent(this, FloatingBuddyService::class.java).apply {
                        action = FloatingBuddyService.ACTION_START
                    })
                }
            }
        }
    }

    private fun handleBuddyUserMessage(userText: String) {
        val reply = BuddyBrain.reply(userText)
        tvBuddySpeech.text = "🧑 คุณ: $userText\n\n✨ น้องพลุ: $reply"
        buddyView.setMood(BuddyView.Mood.HAPPY)
        speakBuddy(reply)
    }

    private fun buddySay(message: String, mood: BuddyView.Mood) {
        tvBuddySpeech.text = "✨ น้องพลุ: $message"
        buddyView.setMood(mood)
        speakBuddy(message)
    }

    private fun speakBuddy(text: String) {
        if (!switchBuddyVoice.isChecked || !ttsReady) return
        val clean = text.replace(Regex("[\\p{So}\\p{Cn}]"), "").trim()
        if (clean.isNotEmpty()) {
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "buddy_${System.currentTimeMillis()}")
        }
    }

    private fun startVoiceRecognition() {
        try {
            buddyView.setMood(BuddyView.Mood.LISTENING)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "พูดคุยกับน้องพลุได้เลย! ✨")
            }
            speechLauncher.launch(intent)
        } catch (_: Exception) {
            buddyView.setMood(BuddyView.Mood.IDLE)
            Toast.makeText(this, "เครื่องนี้ยังไม่รองรับระบบแปลงเสียงพูด (พิมพ์คุยแทนได้เลย!)", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        ServiceState.lanIp.value = NetworkUtils.getLocalIpv4Address()
        val liveRunning = RemoteAccessibilityService.instance != null
        val enabledInSys = RemoteAccessibilityService.isEnabledInSystemSettings(this)
        ServiceState.accessibilityEnabled.value = liveRunning
        if (!liveRunning && enabledInSys) {
            tvAccStatus.text = "⚠️ ระบบประหยัดแบตของมือถือตัดสิทธิ์คุมจอชั่วคราว — กด 'ปิดประหยัดแบต' ด้านล่าง แล้วปิด-เปิด Accessibility 1 ครั้งเพื่อให้ติดถาวร"
            tvAccStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
        }
        refreshBatteryStatus()
    }

    private fun refreshBatteryStatus() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        if (ignoring) {
            tvBatteryStatus.text = "✅ ปิดการประหยัดแบตแล้ว (ระบบจะไม่แอบปิดสิทธิ์คุมจอเอง!)"
            tvBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            tvBatteryStatus.text = "⚠️ แนะนำสำหรับ Huawei/Android: กดปุ่มด้านล่างเพื่อกันระบบแอบปิดสิทธิ์คุมจอตอนปิดแอป"
            tvBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "กรุณาตั้งค่า แบตเตอรี่ -> การเปิดแอป -> จัดการด้วยตัวเอง ใน Settings ของเครื่อง", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun startServerFlow() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) {
            runtimePermLauncher.launch(needed.toTypedArray())
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopServer() {
        val stopIntent = Intent(this, RemoteScreenService::class.java).apply {
            action = RemoteScreenService.ACTION_STOP
        }
        startService(stopIntent)
    }

    private fun downloadAndInstallApk(urlStr: String) {
        btnDownloadUpdate.isEnabled = false
        btnDownloadUpdate.text = "⏳ กำลังโหลดอัปเดต..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 15_000
                        readTimeout = 30_000
                    }
                    val bytes = conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    if (bytes.size < 10_000) throw IllegalStateException("ไฟล์ไม่สมบูรณ์")
                    val dir = File(cacheDir, "updates").apply { mkdirs() }
                    val file = File(dir, "update.apk")
                    file.writeBytes(bytes)
                    Result.success(file)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            btnDownloadUpdate.isEnabled = true
            btnDownloadUpdate.text = "⬇️ โหลดและอัปเดต APK"
            result.onSuccess { apkFile ->
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "$packageName.fileprovider",
                    apkFile
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, "โหลดไม่สำเร็จ: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            combine(
                ServiceState.isRunning,
                ServiceState.lanIp,
                ServiceState.port,
                ServiceState.pin,
                ServiceState.connectedClients
            ) { running, ip, port, pin, clients ->
                UiSnap(running, ip, port, pin, clients)
            }.collect { snap ->
                tvLocalUrl.text = "http://localhost:${snap.port}"
                tvLanUrl.text = "http://${snap.ip}:${snap.port}"
                tvPinCode.text = snap.pin
                tvClients.text = "อุปกรณ์ที่กำลังเปิดดู/คุมพร้อมกัน: ${snap.clients} เครื่อง (รองรับ PC + Mac พร้อมกัน)"

                if (snap.running) {
                    tvStatusBadge.text = "● กำลังทำงาน (Port ${snap.port} • 60 FPS / 4K) • v${ServiceState.VERSION_NAME}"
                    tvStatusBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                    btnToggleServer.text = "⏹ หยุดเซิร์ฟเวอร์"
                    btnToggleServer.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this@MainActivity, R.color.accent_red)
                    )
                } else {
                    tvStatusBadge.text = "● หยุดทำงาน • v${ServiceState.VERSION_NAME}"
                    tvStatusBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
                    btnToggleServer.text = "▶ เปิดเซิร์ฟเวอร์ + แชร์หน้าจอ (:3000)"
                    btnToggleServer.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this@MainActivity, R.color.accent_green)
                    )
                }
            }
        }

        lifecycleScope.launch {
            ServiceState.connectedDevices.collect { devices ->
                if (devices.isEmpty()) {
                    tvClientsList.text = "ยังไม่มีเครื่องอื่นเชื่อมต่อเข้ามา"
                } else {
                    tvClientsList.text = devices.joinToString("\n") { d ->
                        val t = timeFmt.format(Date(d.connectedAtMs))
                        "🟢 ${d.deviceName} • IP: ${d.ip} (เข้าเมื่อ $t)"
                    }
                }
            }
        }

        lifecycleScope.launch {
            ServiceState.securityAlerts.collect { alerts ->
                if (alerts.isEmpty()) {
                    tvSecurityAlerts.text = "ปกติ — ยังไม่มีการพยายามคุมจอขณะปิดสิทธิ์"
                } else {
                    tvSecurityAlerts.text = alerts.takeLast(8).reversed().joinToString("\n") { a ->
                        val t = timeFmt.format(Date(a.timeMs))
                        "🚨 [$t] ${a.message}"
                    }
                }
            }
        }

        lifecycleScope.launch {
            ServiceState.accessibilityEnabled.collect { enabled ->
                if (enabled) {
                    tvAccStatus.text = "✅ เปิดสิทธิ์ควบคุมจากคอมแล้ว (แตะ / ปัด / พิมพ์สดได้)"
                    tvAccStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                } else {
                    tvAccStatus.text = "⚠️ ยังไม่ได้เปิดสิทธิ์ Accessibility (ดูจอได้อย่างเดียว — กดเปิดด้านล่าง)"
                    tvAccStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_blue))
                }
            }
        }

        lifecycleScope.launch {
            ServiceState.chatMessages.collect { msgs ->
                if (msgs.isEmpty()) {
                    tvChatLog.text = "ยังไม่มีข้อความ — พิมพ์คุยกับคอมพิวเตอร์แบบ UltraViewer ได้ที่นี่"
                } else {
                    tvChatLog.text = msgs.takeLast(12).joinToString("\n") { m ->
                        val t = timeFmt.format(Date(m.timeMs))
                        val tag = if (m.sender == "Phone") "📱 มือถือ" else "💻 ${m.sender}"
                        "[$t] $tag: ${m.text}"
                    }
                }
            }
        }

        lifecycleScope.launch {
            ServiceState.lastError.collect { err ->
                tvError.text = err ?: ""
            }
        }

        lifecycleScope.launch {
            ServiceState.floatingBuddyRunning.collect { running ->
                btnToggleFloatingBuddy.text = if (running) {
                    "🚫 ปิดคู่หูลอยบนหน้าจอ"
                } else {
                    "✨ เปิดคู่หูลอยทับทุกแอป (TikTok / เกม / ทุกหน้า)"
                }
            }
        }
    }

    private data class UiSnap(
        val running: Boolean,
        val ip: String,
        val port: Int,
        val pin: String,
        val clients: Int
    )
}
