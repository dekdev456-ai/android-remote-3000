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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class RemoteScreenService : Service(), LocalHttpServer.FrameProvider {

    companion object {
        const val ACTION_START = "com.firework.remote3000.ACTION_START"
        const val ACTION_STOP = "com.firework.remote3000.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PORT = "port"

        private const val CHANNEL_ID = "remote_screen_3000_channel"
        private const val NOTIF_ID = 3000
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var httpServer: LocalHttpServer? = null

    @Volatile
    private var captureWidth: Int = 540

    @Volatile
    private var captureHeight: Int = 960

    @Volatile
    private var targetFps: Int = 15

    @Volatile
    private var jpegQuality: Int = 65

    @Volatile
    private var lastEncodedAtMs: Long = 0L

    private val frameSeq = AtomicLong(0L)
    private val latestFrame = AtomicReference<LocalHttpServer.FramePacket?>(null)
    private val frameLock = Object()

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
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Remote Screen Server (:3000)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "กำลังเปิดเซิร์ฟเวอร์แชร์และควบคุมหน้าจอบนพอร์ต $port"
        }
        nm.createNotificationChannel(channel)

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
                frameProvider = this,
                onSettingsChanged = { fps, quality ->
                    if (fps != null) targetFps = fps
                    if (quality != null) jpegQuality = quality
                }
            )
            server.start()
            httpServer = server
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
            val maxEdge = 960f
            val scale = (maxEdge / maxOf(realW, realH).toFloat()).coerceIn(0.25f, 1.0f)
            captureWidth = ((realW * scale).toInt() / 2 * 2).coerceAtLeast(240)
            captureHeight = ((realH * scale).toInt() / 2 * 2).coerceAtLeast(240)

            val reader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2
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
            val rowPadding = rowStride - pixelStride * captureWidth
            val paddedWidth = captureWidth + rowPadding / pixelStride

            val rawBitmap = Bitmap.createBitmap(paddedWidth, captureHeight, Bitmap.Config.ARGB_8888)
            rawBitmap.copyPixelsFromBuffer(buffer)

            val finalBitmap = if (paddedWidth != captureWidth) {
                Bitmap.createBitmap(rawBitmap, 0, 0, captureWidth, captureHeight).also {
                    rawBitmap.recycle()
                }
            } else {
                rawBitmap
            }

            val baos = ByteArrayOutputStream(64 * 1024)
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(25, 92), baos)
            finalBitmap.recycle()

            val packet = LocalHttpServer.FramePacket(
                jpegBytes = baos.toByteArray(),
                seq = frameSeq.incrementAndGet()
            )
            latestFrame.set(packet)
            synchronized(frameLock) {
                frameLock.notifyAll()
            }
        } catch (_: Exception) {
            // Skip broken frame
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
    }

    private fun stopAllAndSelf() {
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
        stopCaptureOnly()
        httpServer?.stop()
        httpServer = null
        ServiceState.isRunning.value = false
        super.onDestroy()
    }
}
