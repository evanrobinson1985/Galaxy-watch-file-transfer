plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Google Play upload key, from environment variables (CI secrets) or ~/.gradle/gradle.properties.
 * It must never be committed. Without it, Play release builds come out unsigned.
 */
fun uploadSetting(env: String, prop: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(prop).orNull?.takeIf { it.isNotBlank() }

val uploadKeystore = uploadSetting("UPLOAD_KEYSTORE", "uploadKeystore")

android {
    namespace = "com.evanrobinson.watchapk.wear"
    compileSdk = 36

    defaultConfig {
        // The phone and watch apps MUST share the same applicationId and signing key,
        // otherwise the Wearable Data Layer refuses to connect them.
        applicationId = "com.evanrobinson.watchapk"
        minSdk = 30
        targetSdk = 36
        // Different from the phone app's code, as Google Play requires for each form factor.
        versionCode = (property("appVersionCode") as String).toInt() +
            (property("wearVersionCodeOffset") as String).toInt()
        versionName = property("appVersionName") as String
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/shared-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = uploadSetting("UPLOAD_KEYSTORE_PASSWORD", "uploadKeystorePassword")
                keyAlias = uploadSetting("UPLOAD_KEY_ALIAS", "uploadKeyAlias")
                keyPassword = uploadSetting("UPLOAD_KEY_PASSWORD", "uploadKeyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Release builds are for Google Play: upload key, or unsigned without it. (The
            // GitHub edition bundles the debug build, signed with the shared key.)
            signingConfig = signingConfigs.findByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
