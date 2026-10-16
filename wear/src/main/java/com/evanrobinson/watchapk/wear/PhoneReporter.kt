package com.evanrobinson.watchapk.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable

/** Sends short status messages back to the phone app (best effort). */
object PhoneReporter {
    fun send(context: Context, nodeId: String?, text: String) {
        if (nodeId.isNullOrEmpty()) return
        Wearable.getMessageClient(context.applicationContext)
            .sendMessage(nodeId, Protocol.RESULT_PATH, text.toByteArray(Charsets.UTF_8))
            .addOnFailureListener { Log.w("PhoneReporter", "Couldn't report to phone", it) }
    }
}
