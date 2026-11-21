package com.firework.remote3000

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LocalHttpServer(
    private val context: Context,
    private val port: Int,
    private val frameProvider: FrameProvider,
    private val onSettingsChanged: (fps: Int?, quality: Int?) -> Unit
) {
    data class FramePacket(val jpegBytes: ByteArray, val seq: Long)

    interface FrameProvider {
        fun getLatestFrame(): FramePacket?
        fun waitForNextFrame(lastSeq: Long, timeoutMs: Long): FramePacket?
        fun getCaptureDimensions(): Pair<Int, Int>
        fun getTargetFps(): Int
        fun getJpegQuality(): Int
    }

    private val running = AtomicBoolean(false)
    private val activeStreams = AtomicInteger(0)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clientPool: ExecutorService = Executors.newCachedThreadPool()

    private val indexHtmlBytes: ByteArray by lazy {
        context.assets.open("web/index.html").use { it.readBytes() }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("0.0.0.0", port))
        }
        serverSocket = ss
        acceptThread = Thread({
            while (running.get() && !ss.isClosed) {
                try {
                    val socket = ss.accept()
                    clientPool.execute { handleClient(socket) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }, "HttpServerAccept-$port").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        clientPool.shutdownNow()
        activeStreams.set(0)
        ServiceState.connectedClients.value = 0
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readLineCrLf(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullTarget = parts[1]
            val qIdx = fullTarget.indexOf('?')
            val path = if (qIdx >= 0) fullTarget.substring(0, qIdx) else fullTarget
            val query = if (qIdx >= 0) parseQuery(fullTarget.substring(qIdx + 1)) else emptyMap()

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLineCrLf(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            if (method == "OPTIONS") {
                sendResponse(output, 204, "No Content", "text/plain", ByteArray(0))
                return
            }

            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 0
            val bodyBytes = if (contentLength > 0) readExact(input, contentLength) else ByteArray(0)
            val pinParam = headers["x-pin"] ?: query["pin"] ?: ""

            when {
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    sendResponse(output, 200, "OK", "text/html; charset=utf-8", indexHtmlBytes)
                }

                method == "GET" && path == "/api/info" -> {
                    val (w, h) = frameProvider.getCaptureDimensions()
                    val json = JSONObject().apply {
                        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("width", w)
                        put("height", h)
                        put("streaming", ServiceState.isStreaming.value)
                        put("accessibility", RemoteAccessibilityService.instance != null)
                        put("pinRequired", ServiceState.pinRequired.value)
                        put("fps", frameProvider.getTargetFps())
                        put("quality", frameProvider.getJpegQuality())
                        put("clients", activeStreams.get())
                    }
                    sendJson(output, 200, json)
                }

                method == "GET" && path == "/api/verify" -> {
                    val ok = isPinValid(pinParam)
                    sendJson(output, if (ok) 200 else 401, JSONObject().put("ok", ok))
                }

                method == "GET" && path == "/api/frame.jpg" -> {
                    if (!isPinValid(pinParam)) {
                        sendJson(output, 401, JSONObject().put("ok", false).put("error", "Invalid PIN"))
                        return
                    }
                    val frame = frameProvider.getLatestFrame()
                    if (frame != null) {
                        sendResponse(output, 200, "OK", "image/jpeg", frame.jpegBytes)
                    } else {
                        sendJson(output, 503, JSONObject().put("ok", false).put("error", "No frame yet"))
                    }
                }

                method == "GET" && path == "/stream.mjpeg" -> {
                    if (!isPinValid(pinParam)) {
                        sendJson(output, 401, JSONObject().put("ok", false).put("error", "Invalid PIN"))
                        return
                    }
                    socket.soTimeout = 0
                    streamMjpeg(output)
                }

                method == "POST" && path == "/api/control" -> {
                    val bodyStr = String(bodyBytes, StandardCharsets.UTF_8)
                    val json = try {
                        if (bodyStr.isBlank()) JSONObject() else JSONObject(bodyStr)
                    } catch (_: Exception) {
                        sendJson(output, 400, JSONObject().put("ok", false).put("error", "Invalid JSON"))
                        return
                    }
                    val pinToCheck = pinParam.ifEmpty { json.optString("pin", "") }
                    if (!isPinValid(pinToCheck)) {
                        sendJson(output, 401, JSONObject().put("ok", false).put("error", "Invalid PIN"))
                        return
                    }
                    val result = handleControlCommand(json)
                    sendJson(output, if (result.optBoolean("ok")) 200 else 400, result)
                }

                else -> {
                    sendJson(output, 404, JSONObject().put("ok", false).put("error", "Not Found"))
                }
            }
        } catch (_: Exception) {
            // Client disconnected
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun isPinValid(provided: String): Boolean {
        if (!ServiceState.pinRequired.value) return true
        return provided.trim() == ServiceState.pin.value
    }

    private fun handleControlCommand(json: JSONObject): JSONObject {
        val type = json.optString("type", "").lowercase()
        if (type == "settings") {
            val fps = if (json.has("fps")) json.optInt("fps", 15).coerceIn(5, 30) else null
            val quality = if (json.has("quality")) json.optInt("quality", 65).coerceIn(25, 92) else null
            onSettingsChanged(fps, quality)
            return JSONObject().put("ok", true)
        }

        val acc = RemoteAccessibilityService.instance
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "Accessibility Service ยังไม่ได้เปิดใช้งานบนมือถือ")

        val ok = when (type) {
            "tap" -> acc.tap(
                json.optDouble("x", 0.5).toFloat(),
                json.optDouble("y", 0.5).toFloat()
            )
            "longpress" -> acc.longPress(
                json.optDouble("x", 0.5).toFloat(),
                json.optDouble("y", 0.5).toFloat(),
                json.optLong("duration", 650L)
            )
            "swipe" -> acc.swipe(
                json.optDouble("x1", 0.5).toFloat(),
                json.optDouble("y1", 0.5).toFloat(),
                json.optDouble("x2", 0.5).toFloat(),
                json.optDouble("y2", 0.5).toFloat(),
                json.optLong("duration", 220L)
            )
            "action" -> acc.performGlobal(json.optString("action", ""))
            "text" -> acc.inputText(
                json.optString("text", ""),
                json.optBoolean("append", true)
            )
            else -> false
        }
        return JSONObject().put("ok", ok)
    }

    private fun streamMjpeg(output: OutputStream) {
        val count = activeStreams.incrementAndGet()
        ServiceState.connectedClients.value = count
        try {
            val header = (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                    "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(StandardCharsets.US_ASCII)
            output.write(header)
            output.flush()

            var lastSeq = -1L
            while (running.get()) {
                val packet = frameProvider.waitForNextFrame(lastSeq, 1000L)
                if (packet == null) {
                    val latest = frameProvider.getLatestFrame() ?: continue
                    writeMjpegPart(output, latest.jpegBytes)
                    lastSeq = latest.seq
                    continue
                }
                writeMjpegPart(output, packet.jpegBytes)
                lastSeq = packet.seq
            }
        } catch (_: Exception) {
            // Stream closed by client
        } finally {
            val remaining = activeStreams.decrementAndGet().coerceAtLeast(0)
            ServiceState.connectedClients.value = remaining
        }
    }

    private fun writeMjpegPart(output: OutputStream, jpeg: ByteArray) {
        val partHeader = (
            "--frame\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpeg.size}\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
        output.write(partHeader)
        output.write(jpeg)
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun sendJson(output: OutputStream, status: Int, json: JSONObject) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        sendResponse(
            output,
            status,
            reason,
            "application/json; charset=utf-8",
            json.toString().toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun sendResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray
    ) {
        val head = (
            "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Headers: Content-Type, X-Pin\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
        output.write(head)
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun readLineCrLf(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (buf.size() == 0) null else buf.toString("UTF-8")
            }
            if (b == '\n'.code) {
                val arr = buf.toByteArray()
                val len = if (prev == '\r'.code && arr.isNotEmpty()) arr.size - 1 else arr.size
                return String(arr, 0, len, StandardCharsets.UTF_8)
            }
            buf.write(b)
            prev = b
        }
    }

    private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(out, offset, length - offset)
            if (n <= 0) break
            offset += n
        }
        return if (offset == length) out else out.copyOf(offset)
    }

    private fun parseQuery(qs: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (pair in qs.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = URLDecoder.decode(if (eq >= 0) pair.substring(0, eq) else pair, "UTF-8")
            val v = URLDecoder.decode(if (eq >= 0) pair.substring(eq + 1) else "", "UTF-8")
            map[k] = v
        }
        return map
    }
}
