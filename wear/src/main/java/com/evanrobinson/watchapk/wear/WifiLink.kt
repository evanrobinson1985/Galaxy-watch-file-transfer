package com.evanrobinson.watchapk.wear

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A Wi-Fi setup problem the user can fix (shown with a Wi-Fi settings button). */
class WifiProblemException(message: String) : IOException(message)

/**
 * Brings up the watch's Wi-Fi (Wear OS normally routes traffic over Bluetooth through the
 * phone) and opens a socket to the phone on it. [abort] can be called from any thread to
 * stop a pending connect or an ongoing read.
 */
class WifiLink(context: Context) : Closeable {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val wifiLock = wifiManager
        .createWifiLock(@Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WatchApk:wifi")
        .apply { setReferenceCounted(false) }

    private var callback: ConnectivityManager.NetworkCallback? = null
    private val networkReady = CountDownLatch(1)

    @Volatile
    private var network: Network? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var aborted = false

    /** Blocks until Wi-Fi is up. */
    fun requestNetwork(timeoutMs: Long): Network {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                networkReady.countDown()
            }

            override fun onUnavailable() {
                networkReady.countDown()
            }
        }
        callback = cb
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivity.requestNetwork(request, cb, timeoutMs.toInt())
        networkReady.await(timeoutMs + 2_000, TimeUnit.MILLISECONDS)
        if (aborted) throw IOException("Stopped")
        val n = network ?: throw WifiProblemException(
            if (!wifiManager.isWifiEnabled) {
                "Wi-Fi is turned off on the watch. Turn it on in Settings > Connections > Wi-Fi, then send again."
            } else {
                "The watch couldn't connect to a Wi-Fi network within ${timeoutMs / 1000} s. Make sure your " +
                    "Wi-Fi is saved on the watch (Settings > Connections > Wi-Fi) and in range, or connect " +
                    "the watch to your phone's hotspot."
            },
        )
        runCatching { wifiLock.acquire() }
        return n
    }

    /** Tries each of the phone's addresses on the Wi-Fi network. */
    fun connect(network: Network, hosts: List<String>, port: Int): Socket {
        var lastError: Exception? = null
        for (host in hosts) {
            if (aborted) throw IOException("Stopped")
            val s = Socket()
            try {
                network.bindSocket(s)
                socket = s
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.soTimeout = READ_TIMEOUT_MS
                s.receiveBufferSize = 1 shl 20
                return s
            } catch (e: Exception) {
                lastError = e
                runCatching { s.close() }
            }
        }
        throw WifiProblemException(
            "The watch joined Wi-Fi but couldn't reach the phone (${hosts.joinToString()}). Make sure both are " +
                "on the same network. Some routers, mesh and guest networks block devices from reaching each " +
                "other; your phone's hotspot avoids that. (${lastError?.message})",
        )
    }

    fun abort() {
        aborted = true
        networkReady.countDown()
        runCatching { socket?.close() }
    }

    override fun close() {
        runCatching { socket?.close() }
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        runCatching { if (wifiLock.isHeld) wifiLock.release() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
