package com.firework.remote3000

import android.content.Context
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LocalHttpServer(
    private val context: Context,
    private val port: Int,
    private val delegate: ServerDelegate
) {
    data class FramePacket(val jpegBytes: ByteArray, val seq: Long)

    interface ServerDelegate {
        fun getLatestFrame(): FramePacket?
        fun waitForNextFrame(lastSeq: Long, timeoutMs: Long): FramePacket?
        fun getCaptureDimensions(): Pair<Int, Int>
        fun getTargetFps(): Int
        fun getJpegQuality(): Int
        fun getMaxEdgePx(): Int
        fun updateStreamSettings(fps: Int?, quality: Int?, maxEdge: Int?)
        fun wakeScreen(): Boolean
        fun adjustVolume(direction: String): Boolean
        fun onIncomingPcChat(text: String)
        fun installApkBytes(apkBytes: ByteArray): Pair<Boolean, String>
    }

    private class WsClient(val socket: Socket, val output: OutputStream) {
        private val writeLock = Object()
        fun sendText(text: String): Boolean {
            val payload = text.toByteArray(StandardCharsets.UTF_8)
            return sendFrame(0x81, payload)
        }
        fun sendPong(payload: ByteArray): Boolean = sendFrame(0x8A, payload)
        private fun sendFrame(firstByte: Int, payload: ByteArray): Boolean {
            return try {
                synchronized(writeLock) {
                    output.write(firstByte)
                    val len = payload.size
                    when {
                        len < 126 -> output.write(len)
                        len <= 65535 -> {
                            output.write(126)
                            output.write((len shr 8) and 0xFF)
                            output.write(len and 0xFF)
                        }
                        else -> {
                            output.write(127)
                            val l = len.toLong()
                            for (shift in 56 downTo 0 step 8) {
                                output.write(((l shr shift) and 0xFF).toInt())
                            }
                        }
                    }
                    output.write(payload)
                    output.flush()
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private val running = AtomicBoolean(false)
    private val activeStreams = AtomicInteger(0)
    private val wsClients = CopyOnWriteArrayList<WsClient>()
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
        for (c in wsClients) {
            try {
                c.socket.close()
            } catch (_: Exception) {
            }
        }
        wsClients.clear()
        clientPool.shutdownNow()
        activeStreams.set(0)
        ServiceState.connectedClients.value = 0
    }

    fun broadcastChat(msg: ServiceState.ChatMessage) {
        val payload = JSONObject()
            .put("type", "chat")
            .put("message", chatToJson(msg))
            .toString()
        val dead = mutableListOf<WsClient>()
        for (c in wsClients) {
            if (!c.sendText(payload)) dead.add(c)
        }
        if (dead.isNotEmpty()) wsClients.removeAll(dead.toSet())
    }

    private fun chatToJson(msg: ServiceState.ChatMessage): JSONObject =
        JSONObject()
            .put("id", msg.id)
            .put("sender", msg.sender)
            .put("text", msg.text)
            .put("timeMs", msg.timeMs)

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 20_000
            val input = BufferedInputStream(socket.getInputStream(), 32 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

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

            val pinParam = headers["x-pin"] ?: query["pin"] ?: ""

            // WebSocket upgrade for zero-latency controls + live chat
            if (method == "GET" && path == "/ws" &&
                headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
            ) {
                if (!isPinValid(pinParam)) {
                    sendJson(output, 401, JSONObject().put("ok", false).put("error", "Invalid PIN"))
                    return
                }
                val wsKey = headers["sec-websocket-key"] ?: return
                handleWebSocket(socket, input, output, wsKey)
                return
            }

            val maxBody = if (path == "/api/update-apk") 60_000_000 else 1_000_000
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, maxBody) ?: 0
            val bodyBytes = if (contentLength > 0) readExact(input, contentLength) else ByteArray(0)

            when {
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    sendResponse(output, 200, "OK", "text/html; charset=utf-8", indexHtmlBytes)
                }

                method == "GET" && path == "/api/info" -> {
                    val (w, h) = delegate.getCaptureDimensions()
                    val json = JSONObject().apply {
                        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("version", ServiceState.VERSION_NAME)
                        put("width", w)
                        put("height", h)
                        put("streaming", ServiceState.isStreaming.value)
                        put("accessibility", RemoteAccessibilityService.instance != null)
                        put("pinRequired", ServiceState.pinRequired.value)
                        put("fps", delegate.getTargetFps())
                        put("quality", delegate.getJpegQuality())
                        put("maxEdge", delegate.getMaxEdgePx())
                        put("clients", activeStreams.get())
                    }
                    sendJson(output, 200, json)
                }

                method == "GET" && path == "/api/verify" -> {
                    val ok = isPinValid(pinParam)
                    sendJson(output, if (ok) 200 else 401, JSONObject().put("ok", ok))
                }

                method == "GET" && path == "/api/chat" -> {
                    if (!isPinValid(pinParam)) {
                        sendJson(output, 401, JSONObject().put("ok", false).put("error", "Invalid PIN"))
                        return
                    }
                    val arr = JSONArray()
                    for (m in ServiceState.chatMessages.value) arr.put(chatToJson(m))
                    sendJson(output, 200, JSONObject().put("ok", true).put("messages", arr))
                }

                method == "POST" && path == "/api/chat" -> {
                    if (!isPinValid(pinParam)) {
                        sendJson(output, 401, JSONObject().put("ok", false).put("error", "Invalid PIN"))
                        return
                    }
                    val json = JSONObject(String(bodyBytes, StandardCharsets.UTF_8))
                    val txt = json.optString("text", "").trim()
                    if (txt.isEmpty()) {
                        sendJson(output, 400, JSONObject().put("ok", false).put("error", "Empty message"))
                        return
                    }
                    val msg = ServiceState.addChatMessage("PC", txt)
                    delegate.onIncomingPcChat(txt)
                    sendJson(output, 200, JSONObject().put("ok", true).put("message", chatToJson(msg)))
                }

                method == "POST" && path == "/api/update-apk" -> {
                    if (!isPinValid(pinParam)) {
                        sendJson(output, 401, JSONObject().put("ok", false).put("error", "Invalid PIN"))
                        return
                    }
                    val (ok, message) = delegate.installApkBytes(bodyBytes)
                    sendJson(output, if (ok) 200 else 400, JSONObject().put("ok", ok).put("message", message))
                }

                method == "GET" && path == "/api/frame.jpg" -> {
                    if (!isPinValid(pinParam)) {
                        sendJson(output, 401, JSONObject().put("ok", false).put("error", "Invalid PIN"))
                        return
                    }
                    val frame = delegate.getLatestFrame()
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

    private fun handleWebSocket(
        socket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        wsKey: String
    ) {
        val acceptRaw = MessageDigest.getInstance("SHA-1")
            .digest((wsKey.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII))
        val acceptKey = Base64.encodeToString(acceptRaw, Base64.NO_WRAP)

        val handshake = (
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
        output.write(handshake)
        output.flush()
        socket.soTimeout = 0

        val client = WsClient(socket, output)
        wsClients.add(client)

        try {
            // Send initial chat history
            val arr = JSONArray()
            for (m in ServiceState.chatMessages.value) arr.put(chatToJson(m))
            client.sendText(JSONObject().put("type", "chat_history").put("messages", arr).toString())

            while (running.get() && !socket.isClosed) {
                val b0 = input.read()
                if (b0 == -1) break
                val b1 = input.read()
                if (b1 == -1) break

                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    val h = input.read()
                    val l = input.read()
                    if (h == -1 || l == -1) break
                    len = ((h shl 8) or l).toLong()
                } else if (len == 127L) {
                    var l64 = 0L
                    for (i in 0 until 8) {
                        val b = input.read()
                        if (b == -1) return
                        l64 = (l64 shl 8) or (b.toLong() and 0xFF)
                    }
                    len = l64
                }
                if (len < 0 || len > 512_000) break

                val mask = if (masked) readExact(input, 4) else null
                val payload = readExact(input, len.toInt())
                if (mask != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }

                when (opcode) {
                    0x8 -> break // Close
                    0x9 -> client.sendPong(payload) // Ping -> Pong
                    0x1 -> { // Text JSON command
                        val txt = String(payload, StandardCharsets.UTF_8)
                        try {
                            val cmd = JSONObject(txt)
                            val type = cmd.optString("type", "").lowercase()
                            if (type == "chat") {
                                val msgTxt = cmd.optString("text", "").trim()
                                if (msgTxt.isNotEmpty()) {
                                    ServiceState.addChatMessage("PC", msgTxt)
                                    delegate.onIncomingPcChat(msgTxt)
                                }
                            } else if (type == "ping") {
                                client.sendText(JSONObject().put("type", "pong").put("t", cmd.optLong("t", 0L)).toString())
                            } else {
                                val res = handleControlCommand(cmd)
                                if (!res.optBoolean("ok")) {
                                    client.sendText(JSONObject().put("type", "error").put("error", res.optString("error", "Failed")).toString())
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            wsClients.remove(client)
        }
    }

    private fun isPinValid(provided: String): Boolean {
        if (!ServiceState.pinRequired.value) return true
        return provided.trim() == ServiceState.pin.value
    }

    private fun handleControlCommand(json: JSONObject): JSONObject {
        val type = json.optString("type", "").lowercase()
        when (type) {
            "settings" -> {
                val fps = if (json.has("fps")) json.optInt("fps", 24) else null
                val quality = if (json.has("quality")) json.optInt("quality", 55) else null
                val maxEdge = if (json.has("maxEdge")) json.optInt("maxEdge", 720) else null
                delegate.updateStreamSettings(fps, quality, maxEdge)
                return JSONObject().put("ok", true)
            }
            "wake" -> {
                return JSONObject().put("ok", delegate.wakeScreen())
            }
            "volume" -> {
                return JSONObject().put("ok", delegate.adjustVolume(json.optString("direction", "up")))
            }
        }

        val acc = RemoteAccessibilityService.instance
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "กรุณาเปิดสิทธิ์ Accessibility Service บนมือถือเพื่อควบคุม")

        val ok = when (type) {
            "tap" -> acc.tap(
                json.optDouble("x", 0.5).toFloat(),
                json.optDouble("y", 0.5).toFloat()
            )
            "longpress" -> acc.longPress(
                json.optDouble("x", 0.5).toFloat(),
                json.optDouble("y", 0.5).toFloat(),
                json.optLong("duration", 600L)
            )
            "swipe" -> acc.swipe(
                json.optDouble("x1", 0.5).toFloat(),
                json.optDouble("y1", 0.5).toFloat(),
                json.optDouble("x2", 0.5).toFloat(),
                json.optDouble("y2", 0.5).toFloat(),
                json.optLong("duration", 180L)
            )
            "action" -> acc.performGlobal(json.optString("action", ""))
            "text" -> acc.inputText(
                json.optString("text", ""),
                json.optBoolean("append", true)
            )
            "paste" -> acc.pasteText(json.optString("text", ""))
            "backspace" -> acc.sendBackspace()
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
                val packet = delegate.waitForNextFrame(lastSeq, 800L)
                if (packet == null) {
                    val latest = delegate.getLatestFrame() ?: continue
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

    private fun readLineCrLf(input: InputStream): String? {
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

    private fun readExact(input: InputStream, length: Int): ByteArray {
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
