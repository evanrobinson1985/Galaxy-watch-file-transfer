package com.evanrobinson.watchapk.mobile

import java.io.InputStream

/** An APK shipped inside this phone app (the GitHub edition bundles the watch app). */
class BundledApk(val name: String, val size: Long, val open: () -> InputStream)
