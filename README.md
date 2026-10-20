# Watch APK Installer

Send an APK from your **Galaxy S23 Ultra** to your **Galaxy Watch Ultra 2** and install it there.

There are two apps:

| Module   | Runs on | What it does |
|----------|---------|--------------|
| `mobile` | Phone   | Pick an APK (or share one to it from My Files / Chrome downloads), then send it to the watch. Shows progress and the install result the watch reports. |
| `wear`   | Watch   | Receives the file, shows a "Tap to install" notification (or the install screen directly if the app is open), and installs it with Android's PackageInstaller. You confirm the install on the watch. |

Supported files: plain `.apk`, and split bundles `.apks` / `.xapk` (a zip of several APKs, installed together). OBB data inside an `.xapk` is ignored.

The transfer runs over the Wearable Data Layer (the same Bluetooth/Wi-Fi link Galaxy Wearable uses), so you don't need to set up Wi-Fi debugging every time you want to install something.

## What's new in 2.0

- **Progress bars.** The watch shows a ring around the edge of the screen with a percentage while it receives and while it installs. The phone shows two bars, **Transfer to watch** and **Install on watch**, using progress reported by the watch.
- **Screen stays awake** on both the phone and the watch while a transfer or install is running.
- **Accidental exits are blocked while busy.**
  - On the watch, the app ignores swipe-to-dismiss, the back button, wrist gestures, rotary/bezel input and screen touches until the transfer and install finish.
  - On the phone, the back gesture is blocked while sending, and rotating the phone no longer interrupts a transfer.
  - The **home button** (watch) and home gesture (phone) still work, because Android doesn't let apps block them. Even then, the transfer keeps running in the background on the watch, and a notification shows its progress.
- **The watch app opens by itself** when you tap Send on the phone, so you can watch the progress.

### Updating from 1.0

Version 2.0 uses the same package name and signing key, so it installs over 1.0 and keeps the install permission you granted. Update **both** apps.

From Android Studio's terminal, in this project, with the watch connected over wireless debugging:
```powershell
$env:ANDROID_SERIAL="<watch-ip>:<port>"
.\gradlew :wear:installDebug
Remove-Item Env:ANDROID_SERIAL   # then plug in the phone
.\gradlew :mobile:installDebug
```
Or install the new APKs from the latest GitHub Actions build (see below).

## Getting the APKs

Every push builds both apps with GitHub Actions:

1. On GitHub, open **Actions → Build APKs**, then pick the latest successful run.
2. Download the **WatchApkInstaller-apks** artifact (a zip) and unzip it. It contains:
   - `WatchApkInstaller-phone.apk`
   - `WatchApkInstaller-watch.apk`

To build them yourself instead, open the project in Android Studio, or run `./gradlew assembleDebug` with an Android SDK installed.

## One-time setup

### 1. Install the phone app
Copy `WatchApkInstaller-phone.apk` to the phone and open it. Allow "Install unknown apps" for My Files (or your browser) when the phone asks.

### 2. Install the watch app (needs ADB once)
Watches can't open APK files on their own, so the watch app has to be installed once over wireless debugging. You can do this from a computer with [platform-tools](https://developer.android.com/tools/releases/platform-tools), or from the phone itself with an ADB app such as *Bugjaeger*.

On the watch:
1. **Settings → About watch → Software information**, then tap **Software version** 5 times to turn on Developer options.
2. **Settings → Developer options**: turn on **ADB debugging** and **Wireless debugging**. Connect the watch to the same Wi-Fi network as your computer.
3. Tap **Wireless debugging → Pair new device**. Note the pairing code and the IP address and port it shows.

On the computer:
```sh
adb pair <watch-ip>:<pair-port>       # enter the pairing code
adb connect <watch-ip>:<debug-port>   # the IP:port shown on the main Wireless debugging screen
adb install WatchApkInstaller-watch.apk

# Allow the watch app to install other apps (Wear OS has no settings screen for this):
adb shell appops set com.evanrobinson.watchapk REQUEST_INSTALL_PACKAGES allow
```

Open **APK Installer** on the watch once and allow notifications.

You can turn wireless debugging off afterwards. Everything from here on runs over the normal phone–watch connection.

## Daily use

1. On the phone, open **Watch APK Installer**. It should show your watch's name with a ✅.
2. Tap **Choose APK** and pick the file. You can also share an APK to **Install on watch** from My Files.
3. Tap **Send to watch & install**.
4. The watch app opens and shows the transfer progress, then starts installing by itself. Confirm the install when the watch asks. If the watch app didn't come to the front, tap the **Tap to install** notification instead.
5. The phone shows the result, e.g. `Watch: installed com.example.app ✅`.

## Troubleshooting

- **"…is connected, but Watch APK Installer isn't installed on it"**: the watch app is missing, or it was signed with a different key. Both apps must have the same package name and the same signing key. That's why the repo includes `signing/shared-debug.keystore`. Always install the phone and watch APKs from the same build.
- **"This app isn't allowed to install apps yet"** on the watch: run the `adb shell appops set …` command above.
- **"Conflicts with an installed app"**: an app with the same package name is installed with a different signature. Uninstall it from the watch first.
- **"App isn't compatible with this watch"**: the APK targets a newer Android version than your watch runs, or it has no native code built for the watch's CPU (arm64-v8a / armeabi-v7a).
- **Nothing shows up on the watch**: open the watch app once so the system registers it, and check that notifications are allowed for it.

## Security note

`signing/shared-debug.keystore` (password `android`) is a debug key committed to the repo so that the CI builds of the phone and watch apps match each other. It's fine for personal sideloading. Don't use it to sign apps you distribute. If you want a private key, replace the keystore and update the `signingConfigs` in `mobile/build.gradle.kts` and `wear/build.gradle.kts`.
