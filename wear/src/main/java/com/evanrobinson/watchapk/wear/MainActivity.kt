package com.evanrobinson.watchapk.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var installPermButton: Button
    private lateinit var notifPermButton: Button

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        installPermButton = findViewById(R.id.installPermButton)
        notifPermButton = findViewById(R.id.notifPermButton)

        installPermButton.setOnClickListener {
            if (!ApkInstaller.openInstallPermissionSettings(this)) {
                statusText.text = getString(R.string.install_permission_adb)
            }
        }
        notifPermButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (savedInstanceState == null && needsNotificationPermission()) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        refresh()
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    private fun refresh() {
        val canInstall = ApkInstaller.canInstall(this)
        statusText.text = if (canInstall) getString(R.string.ready) else getString(R.string.install_permission_missing)
        installPermButton.visibility = if (canInstall) View.GONE else View.VISIBLE
        notifPermButton.visibility = if (needsNotificationPermission()) View.VISIBLE else View.GONE
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    companion object {
        /** Lets the receiver service open the install screen directly while the app is on screen. */
        @Volatile
        var isVisible = false
            private set
    }
}
