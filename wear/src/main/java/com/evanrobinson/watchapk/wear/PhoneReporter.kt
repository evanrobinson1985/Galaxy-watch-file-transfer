package com.evanrobinson.watchapk.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

/** Sends status text and progress updates back to the phone app (best effort). */
object PhoneReporter {
    private const val TAG = "PhoneReporter"

    private var lastPhase = -1
    private var lastPercent = -1

    fun send(context: Context, nodeId: String?, text: String) {
        sendRaw(context, nodeId, Protocol.RESULT_PATH, text.toByteArray(Charsets.UTF_8))
    }

    /** Throttled so we send at most one message per 2% step within a phase. */
    @Synchronized
    fun progress(context: Context, nodeId: String?, phase: Int, percent: Int) {
        val pct = percent.coerceIn(0, 100)
        val terminal = phase == Protocol.PHASE_DONE || phase == Protocol.PHASE_FAILED
        if (phase == lastPhase && !terminal && pct < lastPercent + 2 && !(pct == 100 && lastPercent != 100)) return
        lastPhase = phase
        lastPercent = pct
        sendRaw(context, nodeId, Protocol.PROGRESS_PATH, byteArrayOf(phase.toByte(), pct.toByte()))
    }

    fun sendVersion(context: Context, nodeId: String?) {
        val payload = "${BuildConfig.VERSION_CODE}|${BuildConfig.VERSION_NAME}"
        sendRaw(context, nodeId, Protocol.VERSION_PATH, payload.toByteArray(Charsets.UTF_8))
    }

    /**
     * Tells every connected phone that this app was just updated. Blocking; we don't know
     * which phone sent the update because the old process was killed during the install.
     */
    fun announceUpdate(context: Context) {
        val nodes = try {
            Tasks.await(Wearable.getNodeClient(context.applicationContext).connectedNodes, 5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't list connected nodes", e)
            return
        }
        for (node in nodes) {
            progress(context, node.id, Protocol.PHASE_DONE, 100)
            send(context, node.id, "watch app updated to v${BuildConfig.VERSION_NAME} ✅")
            sendVersion(context, node.id)
        }
    }

    private fun sendRaw(context: Context, nodeId: String?, path: String, data: ByteArray) {
        if (nodeId.isNullOrEmpty()) return
        Wearable.getMessageClient(context.applicationContext)
            .sendMessage(nodeId, path, data)
            .addOnFailureListener { Log.w(TAG, "Couldn't report to phone", it) }
    }
}
