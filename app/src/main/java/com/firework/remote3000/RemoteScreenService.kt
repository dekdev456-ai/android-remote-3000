package com.firework.remote3000

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
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
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
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
        private const val NOTIF_ID = 3000
        private const val CHAT_NOTIF_ID = 3001
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var httpServer: LocalHttpServer? = null

    // Reusable buffers to eliminate GC stutter
    private var reusableRawBitmap: Bitmap? = null
    private var reusableCropBitmap: Bitmap? = null
    private val reusableBaos = ByteArrayOutputStream(96 * 1024)

    @Volatile
    private var captureWidth: Int = 540

    @Volatile
    private var captureHeight: Int = 960

    @Volatile
    private var maxEdgePx: Int = 720

    @Volatile
    private var targetFps: Int = 24

    @Volatile
    private var jpegQuality: Int = 55

    @Volatile
    private var lastEncodedAtMs: Long = 0L

    private var realDensityDpi: Int = 320

    private val frameSeq = AtomicLong(0L)
    private val latestFrame = AtomicReference<LocalHttpServer.FramePacket?>(null)
    private val frameLock = Object()
    private val captureConfigLock = Object()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopAllAndSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            .setContentTitle("Remote Screen :$port กำลังทำงาน")
            .setContentText("เปิดบนคอม: http://$ip:$port")
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

    private fun startServerAndCapture(resultCode: Int, resultData: Intent, port: Int) {
        try {
            stopCaptureOnly()
            httpServer?.stop()

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
            ServiceState.isRunning.value = true

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("ไม่สามารถเริ่ม MediaProjection ได้")
            mediaProjection = projection

            val thread = HandlerThread("ScreenCaptureThread").apply { start() }
            captureThread = thread
            val handler = Handler(thread.looper)
            captureHandler = handler

            // Required on Android 14+ BEFORE createVirtualDisplay
            projection.registerCallback(projectionCallback, handler)

            val (realW, realH, densityDpi) = getRealDisplayMetrics()
            realDensityDpi = densityDpi
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

    private fun computeScaledDimensions(realW: Int, realH: Int, edge: Int) {
        val clampedEdge = edge.coerceIn(360, 1280).toFloat()
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
            val minIntervalMs = (1000L / targetFps.coerceIn(5, 30))
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
                }
                val pixels = IntArray(w * h)
                rawBmp.getPixels(pixels, 0, w, 0, 0, w, h)
                crop.setPixels(pixels, 0, w, 0, 0, w, h)
                crop
            } else {
                rawBmp
            }

            reusableBaos.reset()
            encodeBmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(25, 92), reusableBaos)
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
            // Skip frame on resize transition
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

    override fun updateStreamSettings(fps: Int?, quality: Int?, maxEdge: Int?) {
        if (fps != null) targetFps = fps.coerceIn(5, 30)
        if (quality != null) jpegQuality = quality.coerceIn(25, 92)
        if (maxEdge != null) {
            val clamped = maxEdge.coerceIn(360, 1080)
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
            wl.acquire(3000L)
            mainHandler.postDelayed({
                if (wl.isHeld) {
                    try {
                        wl.release()
                    } catch (_: Exception) {
                    }
                }
            }, 3100L)
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

    override fun onIncomingPcChat(text: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("💬 ข้อความจากคอมพิวเตอร์")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(CHAT_NOTIF_ID, notif)
        mainHandler.post {
            Toast.makeText(applicationContext, "💬 PC: $text", Toast.LENGTH_LONG).show()
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
        ServiceState.isStreaming.value = false
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

        reusableRawBitmap?.recycle()
        reusableRawBitmap = null
        reusableCropBitmap?.recycle()
        reusableCropBitmap = null
    }

    private fun stopAllAndSelf() {
        ServiceState.onNewChatBroadcast = null
        stopCaptureOnly()
        try {
            httpServer?.stop()
        } catch (_: Exception) {
        }
        httpServer = null
        ServiceState.isRunning.value = false
        ServiceState.connectedClients.value = 0
        synchronized(frameLock) {
            frameLock.notifyAll()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ServiceState.onNewChatBroadcast = null
        stopCaptureOnly()
        httpServer?.stop()
        httpServer = null
        ServiceState.isRunning.value = false
        super.onDestroy()
    }
}
