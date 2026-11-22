package com.firework.remote3000

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class RemoteScreenService : Service(), LocalHttpServer.ServerDelegate {

    companion object {
        const val ACTION_START = "com.firework.remote3000.ACTION_START"
        const val ACTION_STOP = "com.firework.remote3000.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PORT = "port"

        private const val CHANNEL_ID = "remote_screen_3000_channel"
        private const val CHAT_CHANNEL_ID = "remote_screen_3000_chat"
        private const val ALERT_CHANNEL_ID = "remote_screen_3000_alert"
        private const val NOTIF_ID = 3000
        private const val CHAT_NOTIF_ID = 3001
        private const val ALERT_NOTIF_ID = 3002
        private const val AUDIO_SAMPLE_RATE = 44100
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var httpServer: LocalHttpServer? = null

    // Wake locks: PARTIAL keeps server alive while locked; optional screen lock keeps display awake
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var keepAwakeLock: PowerManager.WakeLock? = null

    // Reusable video buffers + Canvas (ZERO per-frame IntArray allocation -> smooth 60 FPS!)
    private var reusableRawBitmap: Bitmap? = null
    private var reusableCropBitmap: Bitmap? = null
    private var reusableCropCanvas: Canvas? = null
    private val reusableBaos = ByteArrayOutputStream(256 * 1024)

    // Audio playback capture (Android 10+)
    @Volatile
    private var audioOutputMode: String = "phone"
    private val audioCapturing = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private val audioSubscribers = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()

    @Volatile
    private var captureWidth: Int = 1080

    @Volatile
    private var captureHeight: Int = 1920

    @Volatile
    private var lastRealW: Int = 0

    @Volatile
    private var lastRealH: Int = 0

    // Default: 60 FPS + 4K (native up to 2160p/3840p)
    @Volatile
    private var maxEdgePx: Int = 2160

    @Volatile
    private var targetFps: Int = 60

    @Volatile
    private var jpegQuality: Int = 78

    @Volatile
    private var lastEncodedAtMs: Long = 0L

    private val frameSeq = AtomicLong(0L)
    private val latestFrame = AtomicReference<LocalHttpServer.FramePacket?>(null)
    private val frameLock = Object()
    private val captureConfigLock = Object()
    private val lastAlertByIp = ConcurrentHashMap<String, Long>()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopAllAndSelf()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            checkOrientationAndResize()
        }
    }

    override fun onCreate() {
        super.onCreate()
        RemotePrefs.loadIntoState(this)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep foreground screen server alive even if user closes MainActivity from Recents
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        checkOrientationAndResize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAllAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val port = intent.getIntExtra(EXTRA_PORT, ServiceState.DEFAULT_PORT)

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    ServiceState.lastError.value = "ไม่ได้รับสิทธิ์แชร์หน้าจอ (MediaProjection)"
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundWithNotification(port)
                startServerAndCapture(resultCode, resultData, port)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(port: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Remote Screen Server (:3000)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "กำลังเปิดเซิร์ฟเวอร์แชร์และควบคุมหน้าจอบนพอร์ต $port"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHAT_CHANNEL_ID,
                "Remote Chat Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "แจ้งเตือนข้อความแชทที่ส่งมาจากคอมพิวเตอร์"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "Security Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "แจ้งเตือนเมื่อมีอุปกรณ์พยายามควบคุมหน้าจอขณะปิดการควบคุม"
            }
        )

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RemoteScreenService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val ip = NetworkUtils.getLocalIpv4Address()
        ServiceState.lanIp.value = ip

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Remote Screen :$port กำลังทำงาน (60 FPS)")
            .setContentText("เปิดบน PC/Mac: http://$ip:$port")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "หยุดเซิร์ฟเวอร์", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (cpuWakeLock == null) {
            cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteScreen3000:CpuAlive").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        updateKeepScreenAwake(ServiceState.keepScreenAwake.value)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout")
    private fun updateKeepScreenAwake(enabled: Boolean) {
        if (enabled) {
            if (keepAwakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                keepAwakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                    "RemoteScreen3000:KeepScreen"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } else {
            try {
                if (keepAwakeLock?.isHeld == true) keepAwakeLock?.release()
            } catch (_: Exception) {
            }
            keepAwakeLock = null
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (keepAwakeLock?.isHeld == true) keepAwakeLock?.release()
        } catch (_: Exception) {
        }
        keepAwakeLock = null
        try {
            if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release()
        } catch (_: Exception) {
        }
        cpuWakeLock = null
    }

    private fun startServerAndCapture(resultCode: Int, resultData: Intent, port: Int) {
        try {
            stopCaptureOnly()
            httpServer?.stop()
            acquireWakeLocks()

            ServiceState.lastError.value = null
            ServiceState.port.value = port
            ServiceState.lanIp.value = NetworkUtils.getLocalIpv4Address()

            val server = LocalHttpServer(
                context = applicationContext,
                port = port,
                delegate = this
            )
            server.start()
            httpServer = server
            ServiceState.onNewChatBroadcast = { msg -> server.broadcastChat(msg) }
            ServiceState.onClientsChangedBroadcast = { server.broadcastClients() }
            ServiceState.onKeepAwakeChanged = { enabled -> updateKeepScreenAwake(enabled) }
            ServiceState.isRunning.value = true

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("ไม่สามารถเริ่ม MediaProjection ได้")
            mediaProjection = projection

            val thread = HandlerThread("ScreenCaptureThread").apply { start() }
            captureThread = thread
            val handler = Handler(thread.looper)
            captureHandler = handler

            projection.registerCallback(projectionCallback, handler)

            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.registerDisplayListener(displayListener, handler)

            val (realW, realH, densityDpi) = getRealDisplayMetrics()
            lastRealW = realW
            lastRealH = realH
            computeScaledDimensions(realW, realH, maxEdgePx)

            val reader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                3
            )
            imageReader = reader
            reader.setOnImageAvailableListener({ r -> onNewImage(r) }, handler)

            virtualDisplay = projection.createVirtualDisplay(
                "RemoteScreen3000Display",
                captureWidth,
                captureHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            ServiceState.isStreaming.value = true
        } catch (e: Exception) {
            ServiceState.lastError.value = "เริ่มเซิร์ฟเวอร์ไม่สำเร็จ: ${e.message}"
            stopAllAndSelf()
        }
    }

    private fun checkOrientationAndResize() {
        val handler = captureHandler ?: return
        handler.post {
            val (realW, realH, _) = getRealDisplayMetrics()
            if (realW != lastRealW || realH != lastRealH) {
                lastRealW = realW
                lastRealH = realH
                reconfigureVirtualDisplay()
            }
        }
    }

    private fun computeScaledDimensions(realW: Int, realH: Int, edge: Int) {
        val clampedEdge = edge.coerceIn(360, 3840).toFloat()
        val scale = (clampedEdge / maxOf(realW, realH).toFloat()).coerceIn(0.2f, 1.0f)
        captureWidth = ((realW * scale).toInt() / 2 * 2).coerceAtLeast(240)
        captureHeight = ((realH * scale).toInt() / 2 * 2).coerceAtLeast(240)
    }

    private fun onNewImage(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return

        try {
            val now = System.currentTimeMillis()
            val minIntervalMs = (1000L / targetFps.coerceIn(10, 60))
            if (now - lastEncodedAtMs < minIntervalMs && latestFrame.get() != null) {
                return
            }
            lastEncodedAtMs = now

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val w = captureWidth
            val h = captureHeight
            val rowPadding = rowStride - pixelStride * w
            val paddedWidth = w + rowPadding / pixelStride

            var rawBmp = reusableRawBitmap
            if (rawBmp == null || rawBmp.isRecycled || rawBmp.width != paddedWidth || rawBmp.height != h) {
                rawBmp?.recycle()
                rawBmp = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888)
                reusableRawBitmap = rawBmp
            }
            rawBmp.copyPixelsFromBuffer(buffer)

            val encodeBmp = if (paddedWidth != w) {
                var crop = reusableCropBitmap
                if (crop == null || crop.isRecycled || crop.width != w || crop.height != h) {
                    crop?.recycle()
                    crop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    reusableCropBitmap = crop
                    reusableCropCanvas = Canvas(crop)
                }
                val rect = Rect(0, 0, w, h)
                reusableCropCanvas?.drawBitmap(rawBmp, rect, rect, null)
                crop
            } else {
                rawBmp
            }

            reusableBaos.reset()
            encodeBmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(25, 95), reusableBaos)
            val jpegBytes = reusableBaos.toByteArray()

            val packet = LocalHttpServer.FramePacket(
                jpegBytes = jpegBytes,
                seq = frameSeq.incrementAndGet()
            )
            latestFrame.set(packet)
            synchronized(frameLock) {
                frameLock.notifyAll()
            }
        } catch (_: Exception) {
            // Skip transitional frame during rotation/resize
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun getRealDisplayMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), dm.densityDpi)
        } else {
            val out = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(out)
            Triple(out.widthPixels, out.heightPixels, out.densityDpi)
        }
    }

    // ================= Audio Playback Capture (Android 10+) =================
    @SuppressLint("MissingPermission")
    private fun startAudioCaptureIfPossible(): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Pair(false, "ส่งเสียงไปคอมรองรับตั้งแต่ Android 10 ขึ้นไป")
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Pair(false, "กรุณาอนุญาตสิทธิ์ไมโครโฟน/เสียงในแอปบนมือถือก่อน")
        }
        val projection = mediaProjection
            ?: return Pair(false, "ยังไม่ได้เริ่มแชร์หน้าจอ")

        if (audioCapturing.get()) return Pair(true, "กำลังส่งเสียงไปคอมอยู่แล้ว")

        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            audioRecord = record
            audioCapturing.set(true)
            record.startRecording()

            audioThread = Thread({
                val chunk = ByteArray(2048)
                while (audioCapturing.get()) {
                    val read = try {
                        record.read(chunk, 0, chunk.size)
                    } catch (_: Exception) {
                        -1
                    }
                    if (read > 0 && audioSubscribers.isNotEmpty()) {
                        val copy = chunk.copyOf(read)
                        for (q in audioSubscribers) {
                            if (q.size > 12) q.poll()
                            q.offer(copy)
                        }
                    } else if (read < 0) {
                        break
                    }
                }
            }, "AudioCaptureThread").apply {
                isDaemon = true
                start()
            }
            Pair(true, "เปิดส่งเสียงไปยังคอมพิวเตอร์แล้ว")
        } catch (e: Exception) {
            stopAudioCapture()
            Pair(false, "เปิดจับเสียงไม่สำเร็จ: ${e.message}")
        }
    }

    private fun stopAudioCapture() {
        audioCapturing.set(false)
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        audioThread = null
    }

    override fun getAudioOutputMode(): String = audioOutputMode
    override fun isAudioCaptureSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun setAudioOutputMode(mode: String): Pair<Boolean, String> {
        val m = mode.lowercase()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (m) {
            "phone" -> {
                audioOutputMode = "phone"
                stopAudioCapture()
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {
                }
                Pair(true, "สลับเสียงออกที่มือถือแล้ว")
            }
            "pc" -> {
                val (ok, msg) = startAudioCaptureIfPossible()
                if (!ok) return Pair(false, msg)
                audioOutputMode = "pc"
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                } catch (_: Exception) {
                }
                Pair(true, "สลับเสียงออกที่คอมพิวเตอร์แล้ว (หากบางรุ่นเงียบ ให้เลือก 'ทั้งคู่')")
            }
            "both" -> {
                val (ok, msg) = startAudioCaptureIfPossible()
                if (!ok) return Pair(false, msg)
                audioOutputMode = "both"
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {
                }
                Pair(true, "เปิดเสียงออกทั้งคอมและมือถือแล้ว")
            }
            else -> Pair(false, "โหมดเสียงไม่ถูกต้อง")
        }
    }

    override fun subscribeAudio(): LinkedBlockingQueue<ByteArray> {
        val q = LinkedBlockingQueue<ByteArray>(20)
        audioSubscribers.add(q)
        return q
    }

    override fun unsubscribeAudio(queue: LinkedBlockingQueue<ByteArray>) {
        audioSubscribers.remove(queue)
    }

    override fun getLatestFrame(): LocalHttpServer.FramePacket? = latestFrame.get()

    override fun waitForNextFrame(lastSeq: Long, timeoutMs: Long): LocalHttpServer.FramePacket? {
        val current = latestFrame.get()
        if (current != null && current.seq > lastSeq) return current
        synchronized(frameLock) {
            val checkAgain = latestFrame.get()
            if (checkAgain != null && checkAgain.seq > lastSeq) return checkAgain
            try {
                frameLock.wait(timeoutMs)
            } catch (_: InterruptedException) {
                return null
            }
        }
        val after = latestFrame.get()
        return if (after != null && after.seq > lastSeq) after else null
    }

    override fun getCaptureDimensions(): Pair<Int, Int> = Pair(captureWidth, captureHeight)
    override fun getTargetFps(): Int = targetFps
    override fun getJpegQuality(): Int = jpegQuality
    override fun getMaxEdgePx(): Int = maxEdgePx

    override fun getScreenLockStatus(): Pair<Boolean, Boolean> {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return Pair(pm.isInteractive, km.isKeyguardLocked)
    }

    override fun updateStreamSettings(fps: Int?, quality: Int?, maxEdge: Int?) {
        if (fps != null) targetFps = fps.coerceIn(10, 60)
        if (quality != null) jpegQuality = quality.coerceIn(25, 95)
        if (maxEdge != null) {
            val clamped = maxEdge.coerceIn(360, 3840)
            if (clamped != maxEdgePx) {
                maxEdgePx = clamped
                captureHandler?.post { reconfigureVirtualDisplay() }
            }
        }
    }

    private fun reconfigureVirtualDisplay() {
        synchronized(captureConfigLock) {
            val vd = virtualDisplay ?: return
            val handler = captureHandler ?: return
            val (realW, realH, dpi) = getRealDisplayMetrics()
            computeScaledDimensions(realW, realH, maxEdgePx)

            val oldReader = imageReader
            val newReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3)
            newReader.setOnImageAvailableListener({ r -> onNewImage(r) }, handler)
            imageReader = newReader
            vd.resize(captureWidth, captureHeight, dpi)
            vd.surface = newReader.surface
            try {
                oldReader?.close()
            } catch (_: Exception) {
            }
            httpServer?.broadcastOrientation(captureWidth, captureHeight)
        }
    }

    @Suppress("DEPRECATION")
    override fun wakeScreen(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "RemoteScreen3000:Wake"
            )
            wl.acquire(4000L)
            mainHandler.postDelayed({
                if (wl.isHeld) {
                    try {
                        wl.release()
                    } catch (_: Exception) {
                    }
                }
            }, 4100L)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun adjustVolume(direction: String): Boolean {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val dir = when (direction.lowercase()) {
                "up" -> AudioManager.ADJUST_RAISE
                "down" -> AudioManager.ADJUST_LOWER
                "mute" -> AudioManager.ADJUST_TOGGLE_MUTE
                else -> return false
            }
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun toggleFloatingBuddy(enabled: Boolean): Pair<Boolean, String> {
        return try {
            if (enabled) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    val permIntent = Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    mainHandler.post { startActivity(permIntent) }
                    return Pair(false, "กรุณากดอนุญาต 'แสดงทับแอปอื่น' บนมือถือก่อน")
                }
                val i = Intent(this, FloatingBuddyService::class.java).apply {
                    action = FloatingBuddyService.ACTION_START
                }
                startService(i)
                Pair(true, "เปิดคู่หูลอย 'น้องพลุ' ทับทุกแอปแล้ว ✨")
            } else {
                val i = Intent(this, FloatingBuddyService::class.java).apply {
                    action = FloatingBuddyService.ACTION_STOP
                }
                startService(i)
                Pair(true, "ซ่อนคู่หูลอยแล้ว")
            }
        } catch (e: Exception) {
            Pair(false, "เปิดคู่หูลอยไม่สำเร็จ: ${e.message}")
        }
    }

    override fun onUnauthorizedControlAttempt(clientIp: String, deviceName: String) {
        val now = System.currentTimeMillis()
        val prev = lastAlertByIp[clientIp] ?: 0L
        if (now - prev < 3000L) return
        lastAlertByIp[clientIp] = now

        val msg = "มีคนกำลังพยายามคุมจอของคุณ! ($deviceName • IP: $clientIp)"
        ServiceState.addSecurityAlert(clientIp, deviceName, msg)

        val openIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("🚨 มีคนกำลังพยายามคุมจอของคุณ!")
            .setContentText("$deviceName • IP: $clientIp")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALERT_NOTIF_ID, notif)

        mainHandler.post {
            Toast.makeText(applicationContext, "🚨 $msg", Toast.LENGTH_LONG).show()
        }
    }

    override fun onIncomingPcChat(text: String, senderLabel: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("💬 ข้อความจาก $senderLabel")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(CHAT_NOTIF_ID, notif)
        mainHandler.post {
            Toast.makeText(applicationContext, "💬 $senderLabel: $text", Toast.LENGTH_LONG).show()
        }
    }

    override fun installApkBytes(apkBytes: ByteArray): Pair<Boolean, String> {
        return try {
            if (apkBytes.size < 10_000) {
                return Pair(false, "ไฟล์ APK เล็กเกินไปหรือไม่ถูกต้อง")
            }
            val dir = File(cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(dir, "update.apk")
            apkFile.writeBytes(apkBytes)
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            mainHandler.post {
                wakeScreen()
                startActivity(installIntent)
            }
            Pair(true, "ส่งไฟล์อัปเดตไปยังมือถือแล้ว (กดยืนยันอัปเดตบนหน้าจอมือถือ)")
        } catch (e: Exception) {
            Pair(false, "อัปเดตไม่สำเร็จ: ${e.message}")
        }
    }

    private fun stopCaptureOnly() {
        stopAudioCapture()
        ServiceState.isStreaming.value = false
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null

        try {
            captureThread?.quitSafely()
        } catch (_: Exception) {
        }
        captureThread = null
        captureHandler = null

        reusableCropCanvas = null
        reusableRawBitmap?.recycle()
        reusableRawBitmap = null
        reusableCropBitmap?.recycle()
        reusableCropBitmap = null
    }

    private fun stopAllAndSelf() {
        ServiceState.onNewChatBroadcast = null
        ServiceState.onClientsChangedBroadcast = null
        ServiceState.onKeepAwakeChanged = null
        stopCaptureOnly()
        releaseWakeLocks()
        try {
            httpServer?.stop()
        } catch (_: Exception) {
        }
        httpServer = null
        ServiceState.isRunning.value = false
        ServiceState.connectedClients.value = 0
        ServiceState.connectedDevices.value = emptyList()
        synchronized(frameLock) {
            frameLock.notifyAll()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ServiceState.onNewChatBroadcast = null
        ServiceState.onClientsChangedBroadcast = null
        ServiceState.onKeepAwakeChanged = null
        stopCaptureOnly()
        releaseWakeLocks()
        httpServer?.stop()
        httpServer = null
        ServiceState.isRunning.value = false
        super.onDestroy()
    }
}
