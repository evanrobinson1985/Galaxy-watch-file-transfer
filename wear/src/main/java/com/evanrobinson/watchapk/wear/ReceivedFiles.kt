package com.evanrobinson.watchapk.wear

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Non-APK files are saved through MediaStore into Download/Watch Transfer, so the watch's own
 * apps (Gallery, music player, …) can find them and no storage permission is needed.
 */
object ReceivedFiles {

    val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/Watch Transfer/"
    const val FOLDER_LABEL = "Downloads › Watch Transfer"
    const val APK_MIME = "application/vnd.android.package-archive"

    data class Item(val uri: Uri, val name: String, val size: Long, val mime: String) {
        val isInstallable: Boolean
            get() = mime == APK_MIME || name.lowercase().let { it.endsWith(".apk") || it.endsWith(".apks") || it.endsWith(".xapk") }
    }

    private val collection: Uri get() = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "apk") return APK_MIME
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** Creates a hidden (pending) entry; call [publish] when the data is complete. */
    fun create(context: Context, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection, values)
            ?: throw IOException("Couldn't create $name in Downloads")
    }

    fun openOutput(context: Context, uri: Uri): OutputStream =
        context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Couldn't write $uri")

    fun publish(context: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    fun delete(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    /** Files this app saved, newest first. (Only our own entries are visible without permissions.) */
    fun list(context: Context): List<Item> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val items = mutableListOf<Item>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$RELATIVE_PATH%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (c.moveToNext()) {
                val displayName = c.getString(name) ?: "file"
                items += Item(
                    uri = ContentUris.withAppendedId(collection, c.getLong(id)),
                    name = displayName,
                    size = c.getLong(size),
                    mime = c.getString(mime) ?: mimeFor(displayName),
                )
            }
        }
        return items
    }

    /** Opens a saved file in whatever watch app handles its type. Returns false if none does. */
    fun open(context: Context, uri: Uri, mime: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Copies a saved APK into private storage so [ApkInstaller] can install it. Blocking. */
    fun copyForInstall(context: Context, item: Item): File {
        val dir = File(context.filesDir, ApkReceiverService.INCOMING_DIR).apply { mkdirs() }
        val file = File(dir, item.name.replace(Regex("[^A-Za-z0-9._ -]"), "_"))
        val input = context.contentResolver.openInputStream(item.uri) ?: throw IOException("Can't read ${item.name}")
        input.use { src -> file.outputStream().use { src.copyTo(it) } }
        return file
    }
}
