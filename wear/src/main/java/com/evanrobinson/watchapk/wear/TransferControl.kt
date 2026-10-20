package com.evanrobinson.watchapk.wear

import android.content.Context
import com.google.android.gms.wearable.ChannelClient
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

    @Volatile
    private var channel: ChannelClient.Channel? = null
    private var appContext: Context? = null
    private var listener: MessageClient.OnMessageReceivedListener? = null

    @Synchronized
    fun begin(context: Context, channel: ChannelClient.Channel?) {
        cancelledBy = null
        this.channel = channel
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
        channel = null
        val app = appContext ?: return
        listener?.let { Wearable.getMessageClient(app).removeListener(it) }
        listener = null
    }

    /** Returns false if there is nothing that can be stopped right now. */
    fun requestCancel(by: String): Boolean {
        if (!TransferState.status.value.cancellable || cancelledBy != null) return false
        cancelledBy = by
        // Closing the channel unblocks a read that is waiting for data.
        val app = appContext
        val ch = channel
        if (app != null && ch != null) Wearable.getChannelClient(app).close(ch)
        return true
    }

    fun throwIfCancelled() {
        cancelledBy?.let { throw TransferCancelledException(it) }
    }

    fun cancelledMessage(): String =
        if (cancelledBy == BY_PHONE) "Stopped from phone" else "Stopped on watch"
}
