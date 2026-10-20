package com.evanrobinson.watchapk.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable

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

    private fun sendRaw(context: Context, nodeId: String?, path: String, data: ByteArray) {
        if (nodeId.isNullOrEmpty()) return
        Wearable.getMessageClient(context.applicationContext)
            .sendMessage(nodeId, path, data)
            .addOnFailureListener { Log.w(TAG, "Couldn't report to phone", it) }
    }
}
