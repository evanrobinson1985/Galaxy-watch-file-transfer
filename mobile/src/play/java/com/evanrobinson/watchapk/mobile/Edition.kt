package com.evanrobinson.watchapk.mobile

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File

/**
 * Google Play edition. Play policy doesn't allow an app to update itself (or the watch app)
 * from outside Play, so there is no GitHub updater and no bundled watch app; both apps update
 * through the Play Store. The Secure Folder bridge (which needs a background service) is left out.
 */
object Edition {
    const val IS_PLAY = true

    fun redirectSecondaryProfile(activity: Activity): Boolean = false

    fun setUpSecureFolderBridge(activity: AppCompatActivity, switch: MaterialSwitch, hint: View, status: TextView) {
        switch.visibility = View.GONE
        hint.visibility = View.GONE
        status.visibility = View.GONE
    }

    fun relayedFiles(context: Context): List<File> = emptyList()

    fun setUpAppUpdates(activity: AppCompatActivity, button: MaterialButton, isBusy: () -> Boolean) {
        button.visibility = View.GONE
    }

    /** Null: the watch app comes from the Play Store on the watch. */
    fun bundledWatchApp(context: Context): BundledApk? = null
}
