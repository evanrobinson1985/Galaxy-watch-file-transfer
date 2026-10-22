package com.evanrobinson.watchapk.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.net.Uri
import android.os.SystemClock
import java.io.File

/**
 * Process-wide state of the current transfer/install, written by the receiver service and
 * the installer, and rendered by [MainActivity].
 */
object TransferState {

    sealed interface Status {
        /** True while a transfer or install is running; the UI locks itself and stays awake. */
        val busy: Boolean get() = false

        /** True while the Stop button can still stop it. */
        val cancellable: Boolean get() = false

        data object Idle : Status

        data class Receiving(
            val name: String,
            val bytes: Long,
            val total: Long,
            val viaWifi: Boolean = false,
            /** Still joining Wi-Fi / connecting to the phone; no data yet. */
            val connecting: Boolean = false,
            /** Wi-Fi is up; now opening the connection to the phone. */
            val connectingToPhone: Boolean = false,
            /** elapsedRealtime when data started flowing, for the speed readout. */
            val startedAt: Long = SystemClock.elapsedRealtime(),
        ) : Status {
            override val busy: Boolean get() = true
            override val cancellable: Boolean get() = true
            val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
        }

        data class ReadyToInstall(val file: File, val nodeId: String?) : Status

        data class Installing(
            val name: String,
            val fraction: Float,
            val waitingForUser: Boolean = false,
            /** Handed to the system; from here on it can't be stopped. */
            val committed: Boolean = false,
        ) : Status {
            override val busy: Boolean get() = true
            override val cancellable: Boolean get() = !waitingForUser && !committed
        }

        data class Success(val name: String, val packageName: String?) : Status

        /** [wifiProblem]: the failure screen offers a shortcut to Wi-Fi settings. */
        data class Failure(val message: String, val wifiProblem: Boolean = false) : Status

        data class Cancelled(val message: String) : Status

        /** A non-APK file was saved to Downloads. */
        data class Saved(val name: String, val uri: Uri, val mime: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun set(status: Status) {
        _status.value = status
    }
}
