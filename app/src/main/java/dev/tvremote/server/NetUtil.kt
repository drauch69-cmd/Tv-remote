package dev.tvremote.server

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    /** The TV's LAN IPv4 address (Wi-Fi or Ethernet), or null if offline. */
    fun ipAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
