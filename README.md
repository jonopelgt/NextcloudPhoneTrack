# Nextcloud PhoneTrack Android App

Android app that publishes device location to a Nextcloud PhoneTrack session and displays the last known positions of all devices in that session on a map.

See [SPEC.md](SPEC.md) for full requirements and acceptance criteria.

## Features (Rev 1)
- Configurable Nextcloud URL, session token, and device name (persisted)
- Foreground service for reliable location publishing (includes battery, speed, bearing)
- Periodic fetch of last positions via public API
- OSM map with markers for self and friends
- Distro folder for revision snapshots

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
