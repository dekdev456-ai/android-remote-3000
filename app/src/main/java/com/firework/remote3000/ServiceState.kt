package com.firework.remote3000

import kotlinx.coroutines.flow.MutableStateFlow

object ServiceState {
    const val DEFAULT_PORT = 3000

    val isRunning = MutableStateFlow(false)
    val isStreaming = MutableStateFlow(false)
    val accessibilityEnabled = MutableStateFlow(false)
    val lanIp = MutableStateFlow("127.0.0.1")
    val port = MutableStateFlow(DEFAULT_PORT)
    val pin = MutableStateFlow(NetworkUtils.generatePin())
    val pinRequired = MutableStateFlow(true)
    val connectedClients = MutableStateFlow(0)
    val lastError = MutableStateFlow<String?>(null)
}
