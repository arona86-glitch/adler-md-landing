# Hatzolah Air CRM — Android app

A native Android app (Kotlin) for the Hatzolah Air CRM at
[app.hatzolahair.org.il](https://app.hatzolahair.org.il).

It wraps the live CRM, so **every function of the web app is in the app and stays in step with it** —
cases, inquiries, intake, missions and pricing, documents (MEDIF, Fit-to-Fly, agreements), staff,
equipment, logistics, Flight Day, tools, settings and admin, plus the family / escort / logistics /
signing portals when their links are opened. Nothing is re-implemented, so there is nothing to drift.

## What the app adds on top of the website

- **Native tab bar** — Dashboard · Cases · Inquiries · Intake · More. The tab follows the page,
  including in-page navigation. "More" is a bottom sheet with New case, Staff, Equipment, Logistics,
  Flight Day, Tools, Settings and Admin.
- **Unlock screen** (optional) — fingerprint / face / screen lock when returning after 30 s away.
- **Hides content in screenshots and the recents preview** (on by default; toggle in More).
- **Camera & uploads** — take a photo or pick a file for attachments, receipts and PCRs. Camera
  photos are shrunk so they stay under the upload limits.
- **Documents** — generated PDFs/CSVs (including the page's blob downloads) open in your PDF viewer
  or share sheet. They are kept in the app's private cache only, and wiped on every launch.
- **Deep links** — `https://app.hatzolahair.org.il/...` links open in the app (portal, escort,
  signing and case links from emails). For Android to open them without asking, host
  `/.well-known/assetlinks.json` on the domain with this app's package name and signing-key
  fingerprint.
- Pull-to-refresh, offline / error screen that recovers by itself, `tel:` / `mailto:` / WhatsApp
  hand-off, external links in a Custom Tab, sign out & clear data.

## Getting the APK

The **Android APK** GitHub Actions workflow builds it on every push to `android/**`
(or run it manually from the Actions tab). Download the `hatzolah-air-crm-apk` artifact:

- `release/app-release.apk` — what to install (minified).
- `debug/app-debug.apk` — same app with debugging on.

Open the `.apk` on the phone and allow "install unknown apps" for the browser/files app when asked.
Requires Android 8.0 (API 26) or newer.

### Signing

Without configuration the release APK is signed with the debug key — fine for installing on your own
devices, but updates only install over builds signed with the same key. To sign with your own key,
add these repository secrets: `HAI_KEYSTORE_B64` (base64 of the `.jks`), `HAI_KEYSTORE_PASSWORD`,
`HAI_KEY_ALIAS`, `HAI_KEY_PASSWORD`.

## Building locally

```bash
cd android
./gradlew assembleRelease     # needs JDK 17 and the Android SDK (compileSdk 35)
```

## Layout

| Path | What |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | WebView host, tab bar sync, uploads, downloads, lock |
| `.../MoreSheet.kt` | The "More" bottom sheet |
| `.../FileTransfer.kt` | Document downloads, camera-photo shrinking |
| `.../Config.kt` | Host, which URLs stay in-app, lock timeout |
| `app/src/main/res/` | Layouts, theme, icons (launcher icon is the Hatzolah Air shield) |

To point the app at another host, change `Config.HOST` and the host in `AndroidManifest.xml`.
