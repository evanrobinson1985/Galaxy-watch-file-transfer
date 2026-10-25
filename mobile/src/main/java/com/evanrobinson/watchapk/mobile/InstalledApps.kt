package com.evanrobinson.watchapk.mobile

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Lists the phone's installed apps and turns one into a file the watch can install. */
object InstalledApps {

    data class App(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val sizeBytes: Long,
        val splitCount: Int,
    )

    /** Blocking; includes system apps (filter on [App.isSystem]). */
    fun list(context: Context): List<App> {
        val pm = context.packageManager
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        return apps
            .filter { it.packageName != context.packageName && it.sourceDir != null }
            .map { info ->
                val files = apkFiles(info)
                App(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    isSystem = info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    sizeBytes = files.sumOf { it.length() },
                    splitCount = files.size - 1,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Returns a readable file for [packageName]: its APK as-is, or (for apps split into several
     * APKs) an uncompressed .apks bundle of all the pieces, written to [outDir]. Blocking.
     */
    fun export(context: Context, packageName: String, outDir: File): Pair<File, String> {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        val label = info.loadLabel(pm).toString()
        val version = runCatching { pm.getPackageInfo(packageName, 0).versionName }.getOrNull()
        val baseName = listOfNotNull(label, version).joinToString("-").replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim().ifEmpty { packageName }

        val files = apkFiles(info)
        if (files.size == 1) return files[0] to "$baseName.apk"

        outDir.mkdirs()
        val bundle = File(outDir, "$baseName.apks")
        ZipOutputStream(bundle.outputStream().buffered(256 * 1024)).use { zip ->
            // APKs are already compressed; don't waste time compressing them again.
            zip.setLevel(Deflater.NO_COMPRESSION)
            val used = mutableSetOf<String>()
            for (file in files) {
                var entryName = if (file == files[0]) "base.apk" else file.name
                while (!used.add(entryName)) entryName = "x$entryName"
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zip, 256 * 1024) }
                zip.closeEntry()
            }
        }
        return bundle to "$baseName.apks"
    }

    private fun apkFiles(info: ApplicationInfo): List<File> =
        listOf(File(info.sourceDir)) + info.splitSourceDirs.orEmpty().map(::File)
}
