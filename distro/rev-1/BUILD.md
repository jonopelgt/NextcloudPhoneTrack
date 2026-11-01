# Rev 1 — First Version Distro Package

**Date:** 2026-11-01  
**Status:** Source-complete MVP matching SPEC.md Rev 1  
**APK:** Not pre-built in this environment (Android SDK not available in the build sandbox). Build locally or via the included GitHub Actions workflow.

## Contents
- Full source matching the current `main` branch
- SPEC.md snapshot
- Build instructions below

## How to Build the APK

### Local (Android Studio)
1. Clone: `git clone https://github.com/jonopelgt/NextcloudPhoneTrack.git`
2. Open the project in Android Studio (Arctic Fox+ / Ladybug recommended).
3. Sync Gradle.
4. Connect a device or start an emulator.
5. Run → Run 'app' (or Build → Build Bundle(s)/APK(s) → Build APK(s)).
6. APK output: `app/build/outputs/apk/debug/app-debug.apk` (or release after signing).

### Command line (with Android SDK)
```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions
A workflow is included (`.github/workflows/android.yml`). Push to `main` triggers a debug APK build. Download the artifact from the Actions tab.

## What's in Rev 1
- Configurable Nextcloud URL + session token + device name (persisted)
- Foreground location service posting to PhoneTrack `/logPost/`
- Periodic fetch of `/api/getlastpositions/` and OSM map markers for self + friends
- Battery level, speed, bearing included in published points
- Distro folder structure for future revisions

## Next
Rev 2 can add offline buffering, CI-signed release APKs, or Nextcloud login for session discovery.
