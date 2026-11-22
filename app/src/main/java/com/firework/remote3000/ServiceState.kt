package com.firework.remote3000

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

object ServiceState {
    const val DEFAULT_PORT = 3000
    const val VERSION_NAME = "3.2.0"

    data class ReceivedImage(
        val filePath: String,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val timeMs: Long
    )

    data class ChatMessage(
        val id: Long,
        val sender: String, // "PC" or "Phone"
        val text: String,
        val timeMs: Long
    )

    data class ConnectedDevice(
        val id: String,
        val ip: String,
        val deviceName: String,
        val connectedAtMs: Long
    )

    data class SecurityAlert(
        val id: Long,
        val ip: String,
        val deviceName: String,
        val message: String,
        val timeMs: Long
    )

    private val chatIdGen = AtomicLong(0L)
    private val alertIdGen = AtomicLong(0L)

    val isRunning = MutableStateFlow(false)
    val isStreaming = MutableStateFlow(false)
    val floatingBuddyRunning = MutableStateFlow(false)
    val accessibilityEnabled = MutableStateFlow(false)
    val controlAllowed = MutableStateFlow(true)
    val keepScreenAwake = MutableStateFlow(true)
    val showFloatingStopButton = MutableStateFlow(true)
    val lanIp = MutableStateFlow("127.0.0.1")
    val port = MutableStateFlow(DEFAULT_PORT)
    val pin = MutableStateFlow(NetworkUtils.generatePin())
    val pinRequired = MutableStateFlow(true)
    val connectedClients = MutableStateFlow(0)
    val connectedDevices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val securityAlerts = MutableStateFlow<List<SecurityAlert>>(emptyList())
    val lastError = MutableStateFlow<String?>(null)
    val chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val latestReceivedImage = MutableStateFlow<ReceivedImage?>(null)

    val blockedIps = CopyOnWriteArraySet<String>()

    @Volatile
    var onNewChatBroadcast: ((ChatMessage) -> Unit)? = null

    @Volatile
    var onClientsChangedBroadcast: (() -> Unit)? = null

    @Volatile
    var onKeepAwakeChanged: ((Boolean) -> Unit)? = null

    @Volatile
    var onFloatingStopSettingChanged: ((Boolean) -> Unit)? = null

    fun addChatMessage(sender: String, text: String): ChatMessage {
        val clean = text.trim()
        val msg = ChatMessage(
            id = chatIdGen.incrementAndGet(),
            sender = sender,
            text = clean,
            timeMs = System.currentTimeMillis()
        )
        chatMessages.value = (chatMessages.value + msg).takeLast(100)
        onNewChatBroadcast?.invoke(msg)
        return msg
    }

    fun addSecurityAlert(ip: String, deviceName: String, message: String): SecurityAlert {
        val alert = SecurityAlert(
            id = alertIdGen.incrementAndGet(),
            ip = ip,
            deviceName = deviceName,
            message = message,
            timeMs = System.currentTimeMillis()
        )
        securityAlerts.value = (securityAlerts.value + alert).takeLast(30)
        return alert
    }
}
