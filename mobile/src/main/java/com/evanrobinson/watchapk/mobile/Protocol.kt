package com.evanrobinson.watchapk.mobile

/** Wire protocol shared by the phone and watch apps. Keep both copies identical. */
object Protocol {
    /** ChannelClient path for an APK the watch should install. */
    const val CHANNEL_PATH = "/apk-transfer"

    /** ChannelClient path for any other file, which the watch saves to Downloads (watch app 5.0+). */
    const val FILE_CHANNEL_PATH = "/file-transfer"

    /** First watch app version that accepts [FILE_CHANNEL_PATH]. */
    const val MIN_WATCH_VERSION_FOR_FILES = 5

    /** MessageClient path the watch uses to send human-readable status text to the phone. */
    const val RESULT_PATH = "/install-result"

    /** MessageClient path for progress updates: payload is [phase, percent] (two bytes). */
    const val PROGRESS_PATH = "/progress"

    const val PHASE_RECEIVING = 1
    const val PHASE_INSTALLING = 2
    const val PHASE_WAITING_FOR_USER = 3
    const val PHASE_DONE = 4
    const val PHASE_FAILED = 5
    const val PHASE_CANCELLED = 6

    /** Phone asks the watch to stop the current transfer or install (empty payload). */
    const val CANCEL_PATH = "/cancel"

    /** Phone asks the watch app for its version (empty payload)... */
    const val VERSION_REQUEST_PATH = "/watch-version-request"

    /** ...and the watch answers with "versionCode|versionName" (UTF-8). Also sent after an update. */
    const val VERSION_PATH = "/watch-version"

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
