package app.transformer.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** This device's own LAN IPv4 address, shown inside the pairing QR code. */
object LocalAddress {
    fun currentIPv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
