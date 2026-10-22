package com.evanrobinson.watchapk.mobile

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Everything the watch needs to fetch a file from the phone over Wi-Fi. It is sent over the
 * (encrypted) Bluetooth Data Layer, so the token and key never travel over Wi-Fi in the clear.
 * Keep both copies of this file identical.
 */
data class WifiOffer(
    val name: String,
    val size: Long,
    val install: Boolean,
    val hosts: List<String>,
    val port: Int,
    val token: ByteArray,
    val key: ByteArray,
) {
    fun toJson(): String = JSONObject()
        .put("v", 1)
        .put("name", name)
        .put("size", size)
        .put("install", install)
        .put("hosts", JSONArray(hosts))
        .put("port", port)
        .put("token", b64(token))
        .put("key", b64(key))
        .toString()

    /** AES-128-CTR; encryption and decryption are the same operation. */
    fun newCipher(encrypt: Boolean): Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, 0, 16, "AES"),
            IvParameterSpec(key, 16, 16),
        )
    }

    /** HMAC-SHA256 over the ciphertext, appended after the data. */
    fun newMac(): Mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key, 32, 32, "HmacSHA256"))
    }

    companion object {
        const val MAC_LENGTH = 32

        fun create(name: String, size: Long, install: Boolean, hosts: List<String>, port: Int): WifiOffer {
            val random = SecureRandom()
            return WifiOffer(
                name, size, install, hosts, port,
                token = ByteArray(16).also(random::nextBytes),
                key = ByteArray(64).also(random::nextBytes),
            )
        }

        fun fromJson(json: String): WifiOffer {
            val o = JSONObject(json)
            val hosts = o.getJSONArray("hosts")
            return WifiOffer(
                name = o.getString("name"),
                size = o.getLong("size"),
                install = o.getBoolean("install"),
                hosts = List(hosts.length()) { hosts.getString(it) },
                port = o.getInt("port"),
                token = unb64(o.getString("token")),
                key = unb64(o.getString("key")),
            )
        }

        private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)
    }
}
