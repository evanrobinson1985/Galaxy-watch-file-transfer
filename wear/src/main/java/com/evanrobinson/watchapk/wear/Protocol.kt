package com.evanrobinson.watchapk.wear

/** Wire protocol shared by the phone and watch apps. Keep both copies identical. */
object Protocol {
    /** ChannelClient path used to stream the APK from phone to watch. */
    const val CHANNEL_PATH = "/apk-transfer"

    /** MessageClient path the watch uses to send human-readable status text to the phone. */
    const val RESULT_PATH = "/install-result"

    /** MessageClient path for progress updates: payload is [phase, percent] (two bytes). */
    const val PROGRESS_PATH = "/progress"

    const val PHASE_RECEIVING = 1
    const val PHASE_INSTALLING = 2
    const val PHASE_WAITING_FOR_USER = 3
    const val PHASE_DONE = 4
    const val PHASE_FAILED = 5

    /** Deep link the phone uses (via RemoteActivityHelper) to bring the watch app on screen. */
    const val OPEN_WATCH_APP_URI = "watchapk://transfer"

    /** Capability advertised by the watch app (see wear/src/main/res/values/wear.xml). */
    const val WATCH_CAPABILITY = "watch_apk_installer"

    /**
     * Stream header, written with DataOutputStream before the file bytes:
     * int MAGIC, int VERSION, UTF fileName, long fileSize (-1 if unknown).
     * Unchanged since v1, so v1 and v2 apps can still talk to each other.
     */
    const val MAGIC = 0x57415049 // "WAPI"
    const val VERSION = 1
}
