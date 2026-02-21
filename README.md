# Bootstrap Agent — Android 8.1 (API 27)

Production-ready embedded-device agent that automatically keeps the **Translation App** (`com.client.translation`) up-to-date after every device boot.

---

## Architecture

```
com.bootstrap.agent
├── Constants.kt        — All configuration constants (URLs, timeouts, package names)
├── BootReceiver.kt     — BroadcastReceiver: listens for BOOT_COMPLETED, starts UpdateService
├── WifiMonitor.kt      — ConnectivityManager wrapper: checks Wi-Fi state
├── UpdateService.kt    — Foreground Service: orchestrates the full update workflow
├── ApkDownloader.kt    — DownloadManager wrapper: downloads + SHA-256 verifies APK
├── ApkInstaller.kt     — FileProvider + ACTION_VIEW: launches system package installer
└── MainActivity.kt     — Minimal diagnostic screen; starts service manually if needed

res/
├── layout/activity_main.xml          — Minimal 2-line status screen
├── values/strings.xml                — String resources
├── values/styles.xml                 — Minimal no-animation theme
└── xml/file_provider_paths.xml       — Exposes Downloads/ for FileProvider
```

---

## Update Workflow

```
BOOT_COMPLETED
     │
     ▼
BootReceiver ──► startForegroundService(UpdateService)
                          │
                          ▼
                  Wait for Wi-Fi (retry every 30s)
                          │
                          ▼
                  GET /version.json
                  { "version_code": 5, "apk_url": "...", "sha256": "..." }
                          │
                          ▼
                  Compare with installed versionCode
                  ┌─ Up-to-date ──► stopSelf()
                  └─ Outdated / Not installed
                          │
                          ▼
                  DownloadManager ──► /Download/translation.apk
                          │
                          ▼
                  SHA-256 verification (if server provides hash)
                          │
                          ▼
                  FileProvider + ACTION_VIEW ──► System Installer UI
                          │
                          ▼
                  Delete APK (10s after install launch)
                          │
                          ▼
                  stopSelf()
```

---

## Configuration

Edit [`Constants.kt`](app/src/main/java/com/bootstrap/agent/Constants.kt):

```kotlin
const val VERSION_URL   = "https://yourserver.com/version.json"
const val TARGET_PACKAGE = "com.client.translation"
```

### Server `version.json` format

```json
{
  "version_code": 5,
  "apk_url": "https://yourserver.com/translation.apk",
  "sha256": "a3f1c2..."
}
```

- `sha256` is **optional** but strongly recommended for production.
- `version_code` must match the `versionCode` in the Translation App's `build.gradle`.

---

## Required Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Fetch version.json and download APK |
| `ACCESS_NETWORK_STATE` | Check connectivity type |
| `ACCESS_WIFI_STATE` | Confirm Wi-Fi |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on boot |
| `REQUEST_INSTALL_PACKAGES` | Install downloaded APK |
| `WRITE_EXTERNAL_STORAGE` | Save APK to Downloads directory |
| `FOREGROUND_SERVICE` | Keep service alive on API 26+ |

---

## Build Instructions

### Prerequisites

- JDK 11 or 17
- Android SDK with Build Tools **27.0.3**
- Android SDK Platform **API 27**
- Gradle 7.5 (included via wrapper)

### 1 — Clone and open the project

```bash
git clone <repo-url>
cd BootstrapAgent
```

### 2 — Sync dependencies

```bash
./gradlew dependencies
```

### 3 — Build debug APK

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### 4 — Build release APK (signed)

Create `keystore.jks` and add signing config to `app/build.gradle`:

```groovy
signingConfigs {
    release {
        storeFile file("keystore.jks")
        storePassword "your_password"
        keyAlias "agent_key"
        keyPassword "your_password"
    }
}
buildTypes {
    release {
        signingConfig signingConfigs.release
        ...
    }
}
```

Then:

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

### 5 — Install on device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 6 — Grant "Install Unknown Apps" (one-time, first deploy only)

On the device:

```
Settings → Apps → Bootstrap Agent → Install unknown apps → Allow
```

Or via ADB (requires device to be rooted or in engineering mode):

```bash
adb shell pm grant com.bootstrap.agent android.permission.REQUEST_INSTALL_PACKAGES
```

### 7 — Test boot trigger manually

```bash
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED \
    -n com.bootstrap.agent/.BootReceiver
```

### 8 — View logs

```bash
adb logcat -s BootstrapAgent
```

---

## Memory Footprint

| Component | Technique | Benefit |
|---|---|---|
| HTTP client | `HttpURLConnection` (built-in) | No OkHttp 2.5 MB overhead |
| JSON parsing | `org.json` (built-in) | No Gson/Moshi overhead |
| Threading | Kotlin coroutines | Tiny compared to RxJava |
| APK download | `DownloadManager` (system) | Zero custom I/O threads |
| Context leaks | Application context only | No Activity/Service leaks |
| Coroutine scope | Cancelled in `onDestroy` | No leaking coroutines |

Expected APK size: **< 2 MB** (debug) / **< 1 MB** (release minified).

---

## Advanced: Silent Install (System App)

For fully silent background install without user interaction:

1. Place the APK in `/system/priv-app/BootstrapAgent/`
2. Set permissions: `chmod 644`
3. Add to `/etc/permissions/` whitelist:
   ```xml
   <privapp-permissions package="com.bootstrap.agent">
       <permission name="android.permission.INSTALL_PACKAGES"/>
   </privapp-permissions>
   ```
4. Use `PackageInstaller` API with `SessionParams` instead of `ACTION_VIEW`.

---

## Production Hardening Checklist

- [ ] Replace `https://yourserver.com` with your real server URL
- [ ] Enable HTTPS certificate pinning (add public key hash check in `fetchVersionInfo`)
- [ ] Set `android:debuggable="false"` for release builds
- [ ] Enable ProGuard (`minifyEnabled true` in release build type)
- [ ] Add SHA-256 checksum to `version.json` and ensure server sets it
- [ ] Test reboot recovery: kill service mid-download, reboot, verify it retries
- [ ] Set `android:allowBackup="false"` (already set in manifest)
- [ ] Verify Wi-Fi-only download restriction works (no cellular fallback)
- [ ] Test with corrupt APK to confirm checksum rejection + cleanup
