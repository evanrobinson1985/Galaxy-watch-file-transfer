package com.evanrobinson.watchapk.mobile

import android.content.Context
import android.os.UserManager
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Hands files from a copy of this app running in Samsung Secure Folder (or a work profile) to
 * the copy in the normal profile, which is the one connected to the watch.
 *
 * Profiles can't share files or intents, but they do share the device's loopback network, so
 * the normal copy's [RelayService] listens on 127.0.0.1 and ::1 port [PORT] and the Secure
 * Folder copy streams each file to it: int MAGIC, int VERSION, int index, int count, UTF name,
 * long size, bytes. The service answers one byte (1) once it has the whole file. Index 0 starts
 * a new batch; after index count-1 the service offers the batch for sending.
 */
object SecureFolderRelay {
    const val PORT = 47613
    const val MAGIC = 0x57415252 // "WARR"
    const val VERSION = 2

    /** Both loopback addresses: phones differ in which one "localhost" means. */
    val LOOPBACKS: List<InetAddress> = listOf(
        InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1)),
        InetAddress.getByAddress("ip6-localhost", ByteArray(16).also { it[15] = 1 }),
    )

    /** True in Secure Folder / work profiles, where the watch isn't reachable. */
    fun isInSecondaryProfile(context: Context): Boolean =
        !context.getSystemService(UserManager::class.java).isSystemUser

    /** Blocking. Throws [IOException] if the normal-profile app isn't listening. */
    fun send(index: Int, count: Int, name: String, size: Long, input: InputStream, onProgress: (Long) -> Unit) {
        connect().use { socket ->
            socket.soTimeout = 60_000
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 256 * 1024))
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(index)
            out.writeInt(count)
            out.writeUTF(name)
            out.writeLong(size)
            val buffer = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
                onProgress(total)
            }
            out.flush()
            socket.shutdownOutput()
            if (socket.getInputStream().read() != 1) {
                throw IOException("Watch APK Installer didn't confirm the file")
            }
        }
    }

    private fun connect(): Socket {
        val errors = mutableListOf<String>()
        for (address in LOOPBACKS) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, PORT), 2_000)
                return socket
            } catch (e: IOException) {
                runCatching { socket.close() }
                errors += "${address.hostAddress}: ${e.message}"
            }
        }
        throw IOException(errors.joinToString("; "))
    }
}
