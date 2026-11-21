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
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatusBadge: TextView
    private lateinit var tvLocalUrl: TextView
    private lateinit var tvLanUrl: TextView
    private lateinit var tvPinCode: TextView
    private lateinit var tvClients: TextView
    private lateinit var tvAccStatus: TextView
    private lateinit var tvError: TextView
    private lateinit var switchPin: SwitchMaterial
    private lateinit var btnToggleServer: Button
    private lateinit var btnCopyLan: Button
    private lateinit var btnRefreshIp: Button
    private lateinit var btnNewPin: Button
    private lateinit var btnOpenAccessibility: Button

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestScreenCapture()
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
        tvAccStatus = findViewById(R.id.tvAccStatus)
        tvError = findViewById(R.id.tvError)
        switchPin = findViewById(R.id.switchPin)
        btnToggleServer = findViewById(R.id.btnToggleServer)
        btnCopyLan = findViewById(R.id.btnCopyLan)
        btnRefreshIp = findViewById(R.id.btnRefreshIp)
        btnNewPin = findViewById(R.id.btnNewPin)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)

        ServiceState.lanIp.value = NetworkUtils.getLocalIpv4Address()
        switchPin.isChecked = ServiceState.pinRequired.value

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
            ServiceState.pin.value = NetworkUtils.generatePin()
        }

        switchPin.setOnCheckedChangeListener { _, checked ->
            ServiceState.pinRequired.value = checked
        }

        btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        observeState()
    }

    override fun onResume() {
        super.onResume()
        ServiceState.lanIp.value = NetworkUtils.getLocalIpv4Address()
        ServiceState.accessibilityEnabled.value = RemoteAccessibilityService.instance != null
    }

    private fun startServerFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                tvClients.text = "คอมที่กำลังเชื่อมต่อ: ${snap.clients} เครื่อง"

                if (snap.running) {
                    tvStatusBadge.text = "● กำลังทำงาน (Port ${snap.port})"
                    tvStatusBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                    btnToggleServer.text = "⏹ หยุดเซิร์ฟเวอร์"
                    btnToggleServer.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this@MainActivity, R.color.accent_red)
                    )
                } else {
                    tvStatusBadge.text = "● หยุดทำงาน"
                    tvStatusBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
                    btnToggleServer.text = "▶ เปิดเซิร์ฟเวอร์ + แชร์หน้าจอ (:3000)"
                    btnToggleServer.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this@MainActivity, R.color.accent_green)
                    )
                }
            }
        }

        lifecycleScope.launch {
            ServiceState.accessibilityEnabled.collect { enabled ->
                if (enabled) {
                    tvAccStatus.text = "✅ เปิดสิทธิ์ควบคุมจากคอมแล้ว (แตะ / ปัด / ปุ่มนำทางได้)"
                    tvAccStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                } else {
                    tvAccStatus.text = "⚠️ ยังไม่ได้เปิดสิทธิ์ Accessibility (ดูจอได้อย่างเดียว — กดเปิดด้านล่างเพื่อควบคุมได้)"
                    tvAccStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_blue))
                }
            }
        }

        lifecycleScope.launch {
            ServiceState.lastError.collect { err ->
                tvError.text = err ?: ""
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
