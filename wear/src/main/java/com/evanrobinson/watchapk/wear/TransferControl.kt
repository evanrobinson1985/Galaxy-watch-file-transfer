package com.evanrobinson.watchapk.wear

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import java.io.IOException

class TransferCancelledException(by: String) : IOException("Stopped on $by")

/**
 * Lets the user stop the running transfer or install, from the watch's Stop button or from the
 * phone. While a job runs we listen for the phone's cancel message with a live listener, because
 * WearableListenerService callbacks queue up behind the transfer that is still running.
 */
object TransferControl {
    const val BY_WATCH = "watch"
    const val BY_PHONE = "phone"

    @Volatile
    var cancelledBy: String? = null
        private set

    val isCancelled: Boolean get() = cancelledBy != null

    /** Unblocks whatever the running job is waiting on (closes its channel or socket). */
    @Volatile
    private var onCancel: (() -> Unit)? = null
    private var appContext: Context? = null
    private var listener: MessageClient.OnMessageReceivedListener? = null

    @Synchronized
    fun begin(context: Context, onCancel: (() -> Unit)?) {
        cancelledBy = null
        this.onCancel = onCancel
        val app = context.applicationContext
        appContext = app
        if (listener == null) {
            val l = MessageClient.OnMessageReceivedListener { event ->
                if (event.path == Protocol.CANCEL_PATH && !requestCancel(BY_PHONE)) {
                    PhoneReporter.send(app, event.sourceNodeId, "too late to stop, the install is finishing")
                }
            }
            Wearable.getMessageClient(app).addListener(l)
            listener = l
        }
    }

    @Synchronized
    fun end() {
        onCancel = null
        val app = appContext ?: return
        listener?.let { Wearable.getMessageClient(app).removeListener(it) }
        listener = null
    }

    /** Returns false if there is nothing that can be stopped right now. */
    fun requestCancel(by: String): Boolean {
        if (!TransferState.status.value.cancellable || cancelledBy != null) return false
        cancelledBy = by
        onCancel?.let { runCatching(it) }
        return true
    }

    fun throwIfCancelled() {
        cancelledBy?.let { throw TransferCancelledException(it) }
    }

    fun cancelledMessage(): String =
        if (cancelledBy == BY_PHONE) "Stopped from phone" else "Stopped on watch"
}
