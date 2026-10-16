package com.evanrobinson.watchapk.wear

/** Wire protocol shared by the phone and watch apps. Keep both copies identical. */
object Protocol {
    /** ChannelClient path used to stream the APK from phone to watch. */
    const val CHANNEL_PATH = "/apk-transfer"

    /** MessageClient path the watch uses to report status back to the phone. */
    const val RESULT_PATH = "/install-result"

    /** Capability advertised by the watch app (see wear/src/main/res/values/wear.xml). */
    const val WATCH_CAPABILITY = "watch_apk_installer"

    /**
     * Stream header, written with DataOutputStream before the file bytes:
     * int MAGIC, int VERSION, UTF fileName, long fileSize (-1 if unknown).
     */
    const val MAGIC = 0x57415049 // "WAPI"
    const val VERSION = 1
}
