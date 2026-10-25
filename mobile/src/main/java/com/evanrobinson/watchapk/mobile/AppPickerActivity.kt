package com.evanrobinson.watchapk.mobile

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pick installed apps (optionally system apps) to send to the watch. Returns package names. */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var search: EditText
    private lateinit var systemSwitch: MaterialSwitch
    private lateinit var list: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var doneButton: MaterialButton

    private var allApps: List<InstalledApps.App> = emptyList()
    private var shown: List<InstalledApps.App> = emptyList()
    private val selected = linkedSetOf<String>()
    private val icons = LruCache<String, Drawable>(150)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        setTitle(R.string.pick_apps_title)
        search = findViewById(R.id.appSearch)
        systemSwitch = findViewById(R.id.systemAppsSwitch)
        list = findViewById(R.id.appList)
        emptyText = findViewById(R.id.appEmpty)
        doneButton = findViewById(R.id.appDoneButton)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        systemSwitch.setOnCheckedChangeListener { _, _ -> applyFilter() }
        doneButton.setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PACKAGES, selected.toTypedArray()))
            finish()
        }
        updateDone()

        emptyText.visibility = View.VISIBLE
        emptyText.setText(R.string.loading_apps)
        lifecycleScope.launch {
            allApps = withContext(Dispatchers.IO) { InstalledApps.list(applicationContext) }
            applyFilter()
        }
    }

    private fun applyFilter() {
        val query = search.text.toString().trim().lowercase()
        shown = allApps.filter { app ->
            (systemSwitch.isChecked || !app.isSystem) &&
                (query.isEmpty() || app.label.lowercase().contains(query) || app.packageName.contains(query))
        }
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
        emptyText.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        emptyText.setText(R.string.no_apps_match)
    }

    private fun updateDone() {
        doneButton.isEnabled = selected.isNotEmpty()
        doneButton.text = if (selected.isEmpty()) {
            getString(R.string.pick_apps_none)
        } else {
            resources.getQuantityString(R.plurals.pick_apps_done, selected.size, selected.size)
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
        val info: TextView = view.findViewById(R.id.appInfo)
        val check: CheckBox = view.findViewById(R.id.appCheck)
    }

    private val adapter = object : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = shown[position]
            holder.label.text = app.label
            val parts = mutableListOf(app.packageName, Formatter.formatShortFileSize(this@AppPickerActivity, app.sizeBytes))
            if (app.splitCount > 0) parts += getString(R.string.app_split_count, app.splitCount + 1)
            if (app.isSystem) parts += getString(R.string.app_system)
            holder.info.text = parts.joinToString(" · ")
            holder.icon.setImageDrawable(
                icons.get(app.packageName) ?: runCatching { packageManager.getApplicationIcon(app.packageName) }
                    .getOrNull()?.also { icons.put(app.packageName, it) },
            )
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = app.packageName in selected
            val toggle = {
                if (!selected.remove(app.packageName)) selected += app.packageName
                holder.check.isChecked = app.packageName in selected
                updateDone()
            }
            holder.itemView.setOnClickListener { toggle() }
            holder.check.setOnClickListener {
                holder.check.isChecked = !holder.check.isChecked
                toggle()
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGES = "packages"
    }
}
