package com.evanrobinson.watchapk.mobile

import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * One-shot TCP server for a fast Wi-Fi transfer: accepts the first connection that presents
 * the right token, and nothing else. Closed as soon as the transfer ends.
 */
class WifiSendServer : Closeable {

    private val server = ServerSocket(0)

    @Volatile
    private var client: Socket? = null

    val port: Int get() = server.localPort

    /** Blocks until the watch connects with [token]; throws on timeout or [close]. */
    fun awaitWatch(token: ByteArray, timeoutMs: Int): Socket {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw IOException("The watch didn't connect over Wi-Fi")
            server.soTimeout = left.toInt()
            val s = server.accept()
            try {
                s.soTimeout = 10_000
                val got = ByteArray(token.size)
                var off = 0
                while (off < got.size) {
                    val n = s.getInputStream().read(got, off, got.size - off)
                    if (n < 0) break
                    off += n
                }
                if (off == got.size && MessageDigest.isEqual(got, token)) {
                    s.soTimeout = 60_000
                    s.sendBufferSize = 1 shl 20
                    client = s
                    return s
                }
            } catch (e: IOException) {
                // Not our watch, or it gave up; keep waiting.
            }
            runCatching { s.close() }
        }
    }

    override fun close() {
        runCatching { client?.close() }
        runCatching { server.close() }
    }

    companion object {
        /** Interfaces that never reach the watch (mobile data, VPNs, …). */
        private val SKIPPED_PREFIXES = listOf("rmnet", "ccmni", "pdp", "v4-", "tun", "ppp", "dummy", "ipsec", "clat")

        /**
         * The phone's private IPv4 addresses on Wi-Fi or its own hotspot, Wi-Fi first. The watch
         * tries each in turn.
         */
        fun localAddresses(): List<String> {
            val interfaces = try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
            return interfaces
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .filterNot { iface -> SKIPPED_PREFIXES.any { iface.name.startsWith(it) } }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
        }
    }
}
