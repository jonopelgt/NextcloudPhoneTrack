# Nextcloud PhoneTrack Android App

Android app that publishes device location to a Nextcloud PhoneTrack session and displays the last known positions of all devices in that session on a map.

See [SPEC.md](SPEC.md) for full requirements and acceptance criteria.

## Features (Rev 5 / app 0.5)
- Configurable Nextcloud URL, session token, device name, publish interval, and map refresh
- Saved sessions listed in the menu, with About and Connection Status
- Foreground service publishes location (battery, speed, bearing) and retries buffered points offline
- OSM map with a distinct marker for this device
- Same signing key on every build so updates install over the previous app

## Setup
1. Install PhoneTrack app on your Nextcloud.
2. Create a tracking session and note the session token. Enable public access if needed for the last-positions API.
3. Open project in Android Studio, sync, build, and run.
4. Enter URL, token, and device name; tap Save & Start Tracking.
5. Grant location permissions.

## Distro
Each revision is snapshotted under `distro/rev-N/`. Current version is in root `VERSION` file.

## License
MIT
