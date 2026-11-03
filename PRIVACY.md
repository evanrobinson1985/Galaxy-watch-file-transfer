# Privacy Policy: Watch APK Installer

_Last updated: November 3, 2026_

Watch APK Installer ("the app") is a phone app and a Wear OS watch app that send files and apps from your phone to your own watch, and install apps on the watch. This policy explains what the app does with your data.

## Summary

**The app does not collect, store on any server, sell, or share your personal data.** It has no accounts, no analytics, no advertising and no tracking. Everything it transfers goes directly from your phone to your watch.

## What the app accesses, and why

| What | Why | Where it goes |
|---|---|---|
| **Files you choose** (or share to the app) | To send them to your watch | Directly to your watch only, over Bluetooth or your local Wi‑Fi network. They are never uploaded anywhere else. |
| **List of installed apps** (Choose installed apps) | To let you pick an installed app to send to your watch | Stays on your phone. Only the app you select is sent, and only to your watch. |
| **Installing apps** (watch app) | To install the APKs you send to the watch. Android asks you to confirm each install. | On your watch |
| **Saving files** (watch app) | Files that aren't apps are saved in the watch's Downloads › Watch Transfer folder | On your watch |
| **Network access** | To transfer files over your local Wi‑Fi network | Only between your phone and your watch, on your local network |
| **Notifications** | To show transfer progress and "Tap to install" | On your device |

### Wi‑Fi transfers

A Wi‑Fi transfer works like this:
- The phone starts a temporary server on your local network that accepts only that one transfer.
- The phone gives the watch a one-time key over the encrypted Bluetooth connection.
- The file is encrypted (AES) and integrity-checked (HMAC-SHA256) on its way to the watch.
- The server shuts down when the transfer ends.

### Bluetooth transfers

Bluetooth transfers use Google's Wear OS Data Layer, which is encrypted between your paired devices.

## Data collection and sharing

- No personal or device data is collected by the developer.
- No data is shared with third parties.
- No data leaves your devices, except files you send, which go directly from your phone to your own watch.

The app uses Google Play services (the Wearable API) to talk to your watch. That is part of Wear OS itself and is covered by [Google's Privacy Policy](https://policies.google.com/privacy).

## GitHub edition only

The edition you download from GitHub (not the Google Play edition) checks GitHub Releases for updates. To do that, it requests `api.github.com` and downloads the update file from GitHub. GitHub may log those requests under [GitHub's Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement). No personal data is sent.

## Children

The app is not directed at children and does not knowingly collect data from anyone.

## Changes

If this policy changes, the new version will be posted at this address, with a new "Last updated" date.

## Contact

Questions? Open an issue at https://github.com/evanrobinson1985/Galaxy-watch-file-transfer/issues
