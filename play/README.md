# Publishing on Google Play

The app now comes in two **editions**, built from the same code:

| | GitHub edition (`github`) | Play edition (`play`) |
|---|---|---|
| Install from | GitHub Releases (sideload) | Google Play |
| Updates | Updates itself from GitHub Releases | Through Play |
| Watch app updates | Phone pushes the bundled watch app | Play Store on the watch (the phone can open the page) |
| Installed-apps list | All apps, including system apps (`QUERY_ALL_PACKAGES`) | Apps with a launcher icon |
| Secure Folder bridge | Included | Not included |
| Signing | Shared key in this repo | Your private upload key, then Google's app signing key |
| Everything else | Same | Same |

Play policy forbids apps updating themselves from outside Play, and restricts `QUERY_ALL_PACKAGES` and background services. The Play edition leaves that code out entirely (it lives in `mobile/src/github/`), so reviewers won't find it.

> ⚠️ The two editions are signed with different keys, so one can't update the other. Before installing from Play, **uninstall the GitHub edition from both the phone and the watch**.

---

## 1. Create your upload key (once)

Run this on your computer, in Android Studio's Terminal or any terminal:

```sh
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

It asks for a password and your name. **Back up `upload.jks` and the password somewhere safe.** Without them you can't publish updates (Google can reset an upload key, but that takes days). **Never commit this file.**

## 2. Let GitHub build signed Play bundles

In GitHub, go to **Settings → Secrets and variables → Actions → New repository secret** and add:

| Secret | Value |
|---|---|
| `UPLOAD_KEYSTORE_BASE64` | Output of `base64 -w0 upload.jks` (Mac: `base64 -i upload.jks`; Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload.jks"))`) |
| `UPLOAD_KEYSTORE_PASSWORD` | The keystore password |
| `UPLOAD_KEY_ALIAS` | `upload` |
| `UPLOAD_KEY_PASSWORD` | The key password (same as the keystore password unless you chose a different one) |

Every push then builds the **WatchApkInstaller-play-bundles** artifact (Actions → Build APKs → latest run → Artifacts):
- `WatchApkInstaller-phone.aab`: upload this to the phone release.
- `WatchApkInstaller-watch.aab`: upload this to the Wear OS release.

Without the secrets, the bundles are still built to prove they compile, but they are **unsigned and Play rejects them**.

**Building locally instead:** put these lines in `~/.gradle/gradle.properties` (not the project's), then run `.\gradlew :mobile:bundlePlayRelease :wear:bundleRelease`:
```
uploadKeystore=C:/path/to/upload.jks
uploadKeystorePassword=...
uploadKeyAlias=upload
uploadKeyPassword=...
```

## 3. Set up the app in Play Console

1. Create a developer account at https://play.google.com/console (a one-time fee of US$25, plus identity verification).
2. **Create app**: name *Watch APK Installer*, type *App*, *Free*. Accept the declarations.
3. Leave **Play App Signing** on (the default). Google holds the real signing key, and your upload key only proves uploads come from you.
4. **Store listing:** copy the text from [`listing.md`](listing.md) and upload the graphics from [`graphics/`](graphics/), plus your screenshots.
5. **App content:** answer using [`policy.md`](policy.md). This covers the privacy policy, Data safety, the install-packages declaration, content rating and target audience.
6. **Add Wear OS:** go to **Test and release → Advanced settings → Form factors → Add form factor → Wear OS**, and opt in to Wear OS. This creates separate Wear OS release tracks.

## 4. Test, then release

1. **Internal testing** (phone and Wear OS tracks): upload both `.aab` files, add yourself as a tester, and install from the opt-in link on your phone and watch. Check that a transfer and an install work.
2. **Closed testing:** personal developer accounts created after November 2023 must run a closed test with **at least 12 testers for 14 days in a row** before Google allows a production release. Friends with any Android phone can join, even without a watch.
3. **Production:** create a release on the phone track and the Wear OS track with the same version, then submit for review. Wear OS apps get an extra review for Wear quality.

## 5. Every later release

1. Bump `appVersionCode` (+1) and `appVersionName` in `gradle.properties`.
2. Push. Take both `.aab` files from the build artifacts and upload them to the phone and Wear OS tracks.

The watch app's version code is automatically `appVersionCode + 100000`, because Play needs a different code for each form factor.

## Things Play might still push back on

- **Installing apps on the watch** (`REQUEST_INSTALL_PACKAGES` in the watch app) is the core feature. It's allowed for companion apps of wearables, but it gets a manual review and the reviewer decides. If they reject it, reply using the wording in `policy.md` and attach a demo video.
- **Copying installed apps** (Choose installed apps) is allowed (APK extractor apps are on Play). The listing reminds users to only send apps they have the right to use.
- **Target API level:** both apps target Android 16 (API 36), which is what Play requires of new apps and updates in 2026.
