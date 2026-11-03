# Google Play store listing

Copy these into **Play Console → Grow → Store presence → Main store listing**.

## App name (max 30 characters)

```
Watch APK Installer
```

## Short description (max 80 characters)

```
Send apps and files from your phone to your Wear OS watch and install them.
```

## Full description (max 4,000 characters)

```
Install apps on your Wear OS watch straight from your phone. No computer, no cables and no ADB commands.

Watch APK Installer has two parts, a phone app and a watch app. Pick an APK on your phone, tap Send, and confirm the install on your watch.

INSTALL APPS ON YOUR WATCH
• Send any .apk file, including split bundles (.apks / .xapk), and install it on the watch
• Pick apps that are already installed on your phone and send them to the watch
• See live progress on both the phone and the watch
• Send several apps or files in one go

FAST TRANSFERS
• Wi‑Fi mode sends big files in minutes instead of hours
• Falls back to Bluetooth automatically if Wi‑Fi isn't available
• Wi‑Fi transfers are encrypted and integrity-checked

SEND ANY FILE
• Photos, music, videos and documents are saved on the watch in Downloads › Watch Transfer
• Open, install or delete received files from the watch
• Share files to the app from any app on your phone

BUILT FOR THE WRIST
• A progress ring around the edge of the watch screen
• The screen stays on during transfers
• Accidental swipes and wrist gestures are ignored while a transfer is running
• Hold-to-stop button on the watch, Stop button on the phone

PRIVATE BY DESIGN
• No accounts, no ads, no analytics
• Files go directly from your phone to your watch and nowhere else

NOTES
• Requires a Wear OS watch paired with your phone. Install the app on both the phone and the watch.
• Android asks you to confirm each install on the watch.
• Phone apps only work on the watch if they support Wear OS. Apps that need phone-only hardware may not install or run.
• Only install apps from sources you trust.
```

## Category and contact

- **Category:** Tools
- **Tags:** Wear OS, file transfer, app installer
- **Contact email:** your developer email
- **Privacy policy URL:** `https://github.com/evanrobinson1985/Galaxy-watch-file-transfer/blob/main/PRIVACY.md` (merge this branch into `main` first, or use the branch URL)

## Graphics

| Asset | File | Status |
|---|---|---|
| App icon, 512 × 512 PNG | `play/graphics/icon-512.png` | ✅ Ready |
| Feature graphic, 1024 × 500 | `play/graphics/feature-graphic-1024x500.png` | ✅ Ready |
| Phone screenshots (2–8, 16:9 or 9:16, min 320 px) | Take on your S23 Ultra | ❌ You need to take these |
| Wear OS screenshots (at least 1, square, min 384 × 384, no device frame) | Take on the watch | ❌ You need to take these |

**Suggested screenshots:**
- Phone: main screen with a file selected; transfer in progress with both progress bars; the installed-apps picker.
- Watch: the progress ring while receiving; "Installed ✅"; the Received files list.
- To capture the watch screen, press the home and back buttons together, or run `adb shell screencap` over wireless debugging.
