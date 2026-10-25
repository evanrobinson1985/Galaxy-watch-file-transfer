# Watch APK Installer

Send an APK from your **Galaxy S23 Ultra** to your **Galaxy Watch Ultra 2** and install it there.

There are two apps:

| Module   | Runs on | What it does |
|----------|---------|--------------|
| `mobile` | Phone   | Pick an APK (or share one to it from My Files / Chrome downloads), then send it to the watch. Shows progress and the install result the watch reports. |
| `wear`   | Watch   | Receives the file, shows a "Tap to install" notification (or the install screen directly if the app is open), and installs it with Android's PackageInstaller. You confirm the install on the watch. |

Supported files to install: plain `.apk`, and split bundles `.apks` / `.xapk` (a zip of several APKs, installed together). OBB data inside an `.xapk` is ignored. Any other file type is transferred and saved on the watch in Downloads › Watch Transfer (since 5.0).

The transfer runs over the Wearable Data Layer (the same Bluetooth/Wi-Fi link Galaxy Wearable uses), so you don't need to set up Wi-Fi debugging every time you want to install something.

## What's new in 8.2

- **Wi‑Fi first, always.** **Wi‑Fi (fast)** is now the default for every transfer, whatever the file size, and also for watch-app updates. If the watch can't join Wi‑Fi or reach the phone, the transfer switches to Bluetooth automatically.
- **The Bluetooth / Wi‑Fi switch is still your choice, and the app remembers it.** Pick Bluetooth and it stays on Bluetooth until you switch back.
- Bluetooth is used anyway (without changing your choice) when Wi‑Fi can't work: the watch app is older than 6.0, or the file's size is unknown.

## What's new in 8.1

- **Automatic updates from GitHub.** Every build of this branch is now also published as a [GitHub Release](https://github.com/evanrobinson1985/Galaxy-watch-file-transfer/releases), with both APKs attached as plain downloads (no zip, no sign-in).
- **Phone:** the phone app checks for a newer release when you open it (at most every 6 hours). You can also tap **Check for updates** at the bottom of the screen.
  - If there's a new version, it asks **Update now** or **Remind me later** (asks again in 24 hours).
  - **Update now** downloads it with a progress bar and installs it over the current app. The first time, Android asks you to allow installs from Watch APK Installer.
- **Watch:** the new phone app carries the matching watch app. If the watch is behind, the phone asks **Update now** or **Remind me later**, and Update now sends it to the watch the usual way.
- **One last manual install:** to get 8.1, install it by hand once. From then on, updates arrive by themselves.
- **Updates only install over each other because of the shared signing key.** Every build is signed with `signing/shared-debug.keystore`, so keep using it.

## What's new in 8.0

- **Send apps that are already installed on your phone.** Tap **Choose installed apps** for a list of every app on the phone.
  - Search by name or package, and turn on **Show system apps** to include system apps.
  - Tick as many apps as you like, then tap **Use N apps**. The phone copies out each app's APK. Apps split into several APKs are packed into an `.apks` bundle, which the watch installs as one.
  - Send them as usual (Bluetooth or Wi‑Fi, one after another). Everything else works the same.
- **Not every phone app works on a watch.** The install fails or the app won't open if it needs phone-only hardware (like telephony), a newer Android version than the watch has, or a CPU type the watch doesn't support. Apps built with Wear OS support work best.
- The app now has permission to see all installed apps (`QUERY_ALL_PACKAGES`), which Android requires for listing them.

## What's new in 7.2

- **Send several files at once.** **Choose files** now lets you pick more than one, and you can share several files to the app. They're sent one after another. The app waits for the watch to finish saving or installing each one before sending the next, and shows "Transfer to watch (2 of 5)".
- **Secure Folder handoff fixes:**
  - The normal app now listens on both of the phone's internal addresses (127.0.0.1 and ::1). Previously the two copies could end up on different ones, which caused "Connection refused".
  - Under **Allow sending from Secure Folder** there's now a status line, for example "Listening for Secure Folder files ✅", or the reason it couldn't start.
  - Several files can be handed over from Secure Folder at once.
  - **Update both copies** (inside and outside Secure Folder). The handoff format changed, and a mismatched pair reports that it needs updating.

## What's new in 7.0

- **Share from Secure Folder.** Secure Folder works like a separate phone inside your phone. Its share menu only lists apps installed *inside* Secure Folder, and those apps can't reach your watch. So this works in two steps:
  1. **One-time setup:**
     - **Inside Secure Folder:** add Watch APK Installer. Either use Secure Folder's **+ → Add apps** and pick it, or install `WatchApkInstaller-phone.apk` from within Secure Folder's My Files.
     - **Outside Secure Folder (in the normal app):** turn on **Allow sending from Secure Folder** at the bottom of the screen and allow notifications. A small "Ready for Secure Folder files" notification stays while it's on.
  2. **To send a file:**
     1. Inside Secure Folder, share it to **Watch APK Installer** (or open the app there and choose a file). It shows **Handing over…**, then **Done ✅**.
     2. Tap the **Tap to send to watch** notification. The normal app opens with the file already selected.
     3. Choose Bluetooth or Wi‑Fi, then tap **Send**.
- **Good to know:**
  - The handoff stays inside the phone (on its internal 127.0.0.1 connection) and never touches Wi‑Fi or the internet. But the file **does leave Secure Folder**: a copy sits in the normal app's private cache until the next handoff replaces it.
  - The copy inside Secure Folder is a separate install, so update it too when you update the phone app.
  - Android 15 limits this kind of background listener to about 6 hours a day. If the Secure Folder copy says it can't reach the normal app, open the normal app once and it starts listening again.

## What's new in 6.0

- **Fast Wi‑Fi transfers for big files.** After you pick a file, choose a **Transfer method**:
  - **Bluetooth** is the original method. It works anywhere the watch is connected, but it's slow: roughly 0.1–0.3 MB/s, so a 700 MB video takes over an hour.
  - **Wi‑Fi (fast)** has the watch download the file straight from the phone over your local network. Expect a few MB/s or more, so that 700 MB video takes a few minutes. It's picked automatically for files over 10 MB.
- **How Wi‑Fi mode works:** the phone starts a temporary server that only accepts this one transfer. Over Bluetooth, it tells the watch where to connect and passes it a one-time key. The watch turns on its Wi‑Fi, connects to the phone and downloads the file. The data is encrypted (AES) and checked for damage (HMAC), and the server shuts down when the transfer ends.
- **Requirements for Wi‑Fi:**
  - The phone and watch must be on the **same Wi‑Fi network**. If there's no Wi‑Fi around, turn on the phone's **hotspot** and connect the watch to it once.
  - The watch's Wi‑Fi must be on, or set to automatic.
  - **Watch app 6.0**. Tap **Update watch app** first.
- **Automatic fallback:** if the watch can't join Wi‑Fi or can't reach the phone, the phone says why and **sends over Bluetooth instead**.
- **Speed and time left** are shown on both the phone and the watch.
- The watch now **checks for free space** before receiving, so a big file fails right away instead of halfway through. The watch also stays awake through long Bluetooth transfers (previously the wake lock ran out after 10 minutes).

## What's new in 5.0

- **Send any file, not just APKs.** APK install is still the main feature. Choosing an `.apk`, `.apks` or `.xapk` works exactly as before.
  - **Any other file** (photos, music, videos, documents, …) is saved on the watch in **Downloads › Watch Transfer**. Apps on the watch, like Gallery and the music player, can find it there. The phone shows only the **Transfer** bar for these files, and the watch shows **Saved ✅** with an **Open** button.
  - **APKs** now have an **Install on the watch** switch. Turn it off to save the APK without installing it.
  - You can also share any file to **Send to watch** from My Files or the share menu.
- **Received files on the watch.** The watch app's main screen has a **Received files** button. It lists everything saved in Downloads › Watch Transfer.
  - **Tap** a file to open it, or to install it if it's an APK.
  - **Hold** a file to delete it.
- Saving files needs **watch app 5.0**. If the watch has an older version, the phone asks you to tap **Update watch app** first. APK installs still work with any watch app version.

## What's new in 4.0

- **Stop buttons.**
  - On the watch, **press and hold** the red **Hold to stop** button. Holding is required so a stray touch or wrist gesture can't stop a transfer by accident. Every other touch stays blocked while busy.
  - On the phone, tap **Stop** under the progress bars. The phone tells the watch to stop and shows **Stopped** once the watch confirms.
  - You can stop during the file transfer and the first part of the install, while the APK is still being handed to Android. After that, Android's installer is in control and the install can't be stopped, so the button goes away. Cancel on Android's confirmation screen instead.
- Update both apps as before: install the new phone app, then tap **Update watch app**.

## What's new in 3.0

- **Update the watch app from the phone (over the air).** The phone app carries a copy of the matching watch app. Under **Find watch**, it shows the watch app's version and an **Update watch app** button. Tap it and the phone sends the watch app to the watch, which installs it over itself. Confirm on the watch if it asks. The watch app closes during the update, then tells the phone it's on the new version.
- This works for watches still on 1.0 or 2.0. Those versions can't report their version number, so the phone shows "older than v3.0". **From now on you only need adb if you uninstall the watch app.** To update both apps, install the new phone app, then tap **Update watch app**.

## What's new in 2.0

- **Progress bars.** The watch shows a ring around the edge of the screen with a percentage while it receives and while it installs. The phone shows two bars, **Transfer to watch** and **Install on watch**, using progress reported by the watch.
- **Screen stays awake** on both the phone and the watch while a transfer or install is running.
- **Accidental exits are blocked while busy.**
  - On the watch, the app ignores swipe-to-dismiss, the back button, wrist gestures, rotary/bezel input and screen touches until the transfer and install finish.
  - On the phone, the back gesture is blocked while sending, and rotating the phone no longer interrupts a transfer.
  - The **home button** (watch) and home gesture (phone) still work, because Android doesn't let apps block them. Even then, the transfer keeps running in the background on the watch, and a notification shows its progress.
- **The watch app opens by itself** when you tap Send on the phone, so you can watch the progress.

### Updating from 1.0 (manual, before 3.0)

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
