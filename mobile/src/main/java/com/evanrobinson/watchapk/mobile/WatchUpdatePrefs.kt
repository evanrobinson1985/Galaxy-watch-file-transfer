package com.evanrobinson.watchapk.mobile

import android.content.Context

/** "Remind me later" for the watch-app update prompt. */
object WatchUpdatePrefs {
    private const val PREFS = "watch_update"
    private const val KEY_REMIND_AFTER = "remind_after"
    private const val REMIND_LATER_MS = 24 * 60 * 60 * 1000L

    fun snoozed(context: Context): Boolean =
        System.currentTimeMillis() < context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_REMIND_AFTER, 0)

    fun remindLater(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_REMIND_AFTER, System.currentTimeMillis() + REMIND_LATER_MS).apply()
    }
}
