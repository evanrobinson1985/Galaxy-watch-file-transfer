plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** Copies the built watch app into the phone app's assets so the phone can update the watch. */
abstract class CopyWatchApkTask : DefaultTask() {
    @get:InputFile
    abstract val watchApk: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        watchApk.get().asFile.copyTo(File(out, "watch-app.apk"), overwrite = true)
    }
}

val copyWatchApk = tasks.register<CopyWatchApkTask>("copyWatchApk") {
    dependsOn(":wear:assembleDebug")
    watchApk.set(rootProject.layout.projectDirectory.file("wear/build/outputs/apk/debug/wear-debug.apk"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyWatchApk, CopyWatchApkTask::outputDir)
    }
}

android {
    namespace = "com.evanrobinson.watchapk.mobile"
    compileSdk = 35

    defaultConfig {
        // The phone and watch apps MUST share the same applicationId and signing key,
        // otherwise the Wearable Data Layer refuses to connect them.
        applicationId = "com.evanrobinson.watchapk"
        minSdk = 26
        targetSdk = 35
        versionCode = (property("appVersionCode") as String).toInt()
        versionName = property("appVersionName") as String

        buildConfigField("int", "WATCH_VERSION_CODE", property("appVersionCode") as String)
        buildConfigField("String", "WATCH_VERSION_NAME", "\"${property("appVersionName")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // Store the bundled watch APK uncompressed so its size can be read directly.
        noCompress += "apk"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/shared-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.wear:wear-remote-interactions:1.1.0")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
