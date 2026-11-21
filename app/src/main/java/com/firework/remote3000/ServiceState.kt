package com.firework.remote3000

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong

object ServiceState {
    const val DEFAULT_PORT = 3000
    const val VERSION_NAME = "2.0.0"

    data class ChatMessage(
        val id: Long,
        val sender: String, // "PC" or "Phone"
        val text: String,
        val timeMs: Long
    )

    private val chatIdGen = AtomicLong(0L)

    val isRunning = MutableStateFlow(false)
    val isStreaming = MutableStateFlow(false)
    val accessibilityEnabled = MutableStateFlow(false)
    val lanIp = MutableStateFlow("127.0.0.1")
    val port = MutableStateFlow(DEFAULT_PORT)
    val pin = MutableStateFlow(NetworkUtils.generatePin())
    val pinRequired = MutableStateFlow(true)
    val connectedClients = MutableStateFlow(0)
    val lastError = MutableStateFlow<String?>(null)
    val chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    @Volatile
    var onNewChatBroadcast: ((ChatMessage) -> Unit)? = null

    fun addChatMessage(sender: String, text: String): ChatMessage {
        val clean = text.trim()
        val msg = ChatMessage(
            id = chatIdGen.incrementAndGet(),
            sender = sender,
            text = clean,
            timeMs = System.currentTimeMillis()
        )
        val updated = (chatMessages.value + msg).takeLast(100)
        chatMessages.value = updated
        onNewChatBroadcast?.invoke(msg)
        return msg
    }
}
