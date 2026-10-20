package com.evanrobinson.watchapk.wear

import android.app.AlertDialog
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lists files received from the phone (Downloads › Watch Transfer): tap to open/install, hold to delete. */
class FilesActivity : ComponentActivity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var items: List<ReceivedFiles.Item> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
            val item = items[position]
            view.findViewById<TextView>(R.id.name).text = item.name
            val size = Formatter.formatShortFileSize(this@FilesActivity, item.size)
            view.findViewById<TextView>(R.id.info).text = if (item.isInstallable) "$size · tap to install" else size
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> onTap(items[position]) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(items[position])
            true
        }
        Toast.makeText(this, R.string.files_hint, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            items = withContext(Dispatchers.IO) { runCatching { ReceivedFiles.list(applicationContext) }.getOrDefault(emptyList()) }
            adapter.notifyDataSetChanged()
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun onTap(item: ReceivedFiles.Item) {
        if (item.isInstallable) {
            if (TransferState.status.value.busy) {
                Toast.makeText(this, R.string.busy_toast, Toast.LENGTH_SHORT).show()
                return
            }
            lifecycleScope.launch {
                try {
                    val file = withContext(Dispatchers.IO) { ReceivedFiles.copyForInstall(applicationContext, item) }
                    startActivity(MainActivity.installIntent(this@FilesActivity, file, null))
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this@FilesActivity, e.message ?: "Couldn't read file", Toast.LENGTH_LONG).show()
                }
            }
        } else if (!ReceivedFiles.open(this, item.uri, item.mime)) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(item: ReceivedFiles.Item) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_file_title, item.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { ReceivedFiles.delete(applicationContext, item.uri) }
                    reload()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
