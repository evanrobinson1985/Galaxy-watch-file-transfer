# Google Play policy declarations

Answers for the Play Console's **App content** section (Policy → App content).

## Sensitive permission: `REQUEST_INSTALL_PACKAGES` (watch app)

Play only allows this permission when installing apps is the app's core purpose. Declare it under **App content → Sensitive app permissions → Request install packages**.

**Core functionality:** select *Companion app for a wearable device* (if that option isn't listed, choose *File sharing / transfer*).

**Description to paste:**

> Watch APK Installer is a companion app for Wear OS smartwatches. Installing apps on the watch is its core feature. The user picks an APK (or an app installed on their phone) in the phone app and sends it to their own paired watch. The watch app then installs it with Android's PackageInstaller, and the system asks the user to confirm every install. The permission is only used to install apps the user explicitly sent from their own phone. The app never downloads or installs code by itself.

**Demo video:** Play usually asks for a short screen recording. Show this flow:
1. Pick an APK on the phone and tap Send.
2. The transfer progresses on the phone and the watch.
3. The watch shows Android's install confirmation, then "Installed".

## Permissions the Play edition does NOT use

The Play edition was built to avoid these. Only the GitHub edition uses them:
- `QUERY_ALL_PACKAGES`: the Play edition lists only apps with a launcher icon, through a `<queries>` element.
- `REQUEST_INSTALL_PACKAGES` **on the phone**: it was only used for updating from GitHub, which Play doesn't allow.
- `FOREGROUND_SERVICE_DATA_SYNC`: the Secure Folder bridge isn't included in the Play edition.

## Data safety form

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not applicable (nothing is collected). If the form insists, answer **Yes**: transfers between the user's own devices are encrypted. |
| Do you provide a way for users to request that their data is deleted? | Not applicable (nothing is collected) |

Why "No": files and apps go **directly** from the user's phone to the user's own watch, over Bluetooth (Wear OS Data Layer) or the local network. Play's definition of "collect" means data sent off the device to the developer or third parties, and that never happens.

## Other App content items

| Item | Answer |
|---|---|
| Privacy policy | URL of `PRIVACY.md` (see `listing.md`) |
| Ads | No ads |
| App access | All functionality is available without special access. Note for reviewers: "Requires a paired Wear OS watch with the watch app installed." |
| Content rating | Complete the questionnaire. Category: *Utility, Productivity, Communication, or Other*. Answer No to everything. Expected rating: Everyone. |
| Target audience | 18+ (or 13+). **Not** designed for children. |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |
