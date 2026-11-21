package com.firework.remote3000

import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

object NetworkUtils {
    private val random = SecureRandom()

    fun generatePin(): String {
        val code = 100000 + random.nextInt(900000)
        return code.toString()
    }

    fun getLocalIpv4Address(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            var fallbackIp: String? = null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val isPreferred = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (isPreferred && addr.isSiteLocalAddress) {
                            return ip
                        }
                        if (fallbackIp == null && addr.isSiteLocalAddress) {
                            fallbackIp = ip
                        } else if (fallbackIp == null) {
                            fallbackIp = ip
                        }
                    }
                }
            }
            return fallbackIp ?: "127.0.0.1"
        } catch (_: Exception) {
            return "127.0.0.1"
        }
    }
}
