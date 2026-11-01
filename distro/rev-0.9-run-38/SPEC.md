# Nextcloud PhoneTrack Android App — Specification (Rev 6)

## 1. Purpose
Android application that connects to a self-hosted Nextcloud instance running the PhoneTrack app, publishes the device’s location to a PhoneTrack tracking session, and displays the last known positions of the local device and all other devices (“friends”) in the same session on a map.

## 2. Scope
- Background location publishing to PhoneTrack HTTP endpoint.
- Periodic fetch of last positions via public API.
- Map display of all devices in the session.
- Navigation drawer menu with About, Sessions, and Connection Status.
- Persistent configuration of sessions (URL, token, device) across restarts.
- Foreground service for reliable location updates.
- Revision-based distro packaging for each build.
- All builds signed with the same fixed keystore.

## 3. Functional Requirements

### FR-1: Configuration
- User can enter and save:
  - Nextcloud base URL (https://example.com)
  - PhoneTrack session token
  - Device name (defaults to device model)
- Values stored in SharedPreferences.
- Validation of URL format and non-empty token.
- Multiple sessions remembered; listed in menu.
- User can accept a self-signed HTTPS certificate after reviewing its SHA-256 fingerprint. The accepted fingerprint is stored and used for later publish and fetch calls. The user can forget it and return to the Android trust store.

### FR-2: Location Publishing
- Foreground service requests high-accuracy location updates.
- On each fix (configurable interval, default 60 s), POST to:
  `https://{base}/index.php/apps/phonetrack/logPost/{token}/{deviceName}`
  with parameters: lat, lon, timestamp (epoch), acc, alt, bat (battery %), speed, bearing.
- Buffer failed uploads and retry when network available.
- Show persistent notification while tracking.

### FR-3: Position Retrieval & Display
- Every 30 s (configurable) GET:
  `https://{base}/apps/phonetrack/api/getlastpositions/{token}`
- Parse JSON and render markers for every device.
- Center map on own device when available.
- Show device name, accuracy, and age of fix on marker info.

### FR-4: Map
- Use OSMDroid with OpenStreetMap tiles.
- Multi-touch zoom/pan.
- Distinct marker for own device.

### FR-5: Permissions & Lifecycle
- Request fine location, coarse location, background location (Android 10+), notifications.
- Survive process death via START_STICKY service.
- Graceful handling of permission denial.

### FR-6: Menu
- Navigation drawer menu with:
  - About (app info, version, purpose)
  - Sessions (list of saved PhoneTrack sessions)
  - Connection Status (shows logging state, last update)
  - Map
- Sessions list displays saved configs.

### FR-7: Revision Distribution
- Each software update is tagged with a revision (rev-N).
- Source snapshot or build notes placed in `distro/rev-N/`.
- Root contains current source; distro/ holds historical revisions.
- VERSION file tracks current revision.
- All builds signed with the same consistent keystore (debugkey / android) for updateability.

## 4. Non-Functional Requirements
- Min SDK 24, Target SDK 34.
- Minimal battery impact (fused location provider).
- No Google Play Services dependency beyond location (optional fallback later).
- Open source, MIT license.
- Offline-tolerant (buffer positions).

## 5. Out of Scope (Future)
- Full Nextcloud OAuth / app-password login and dynamic session listing from server.
- Multiple simultaneous active sessions.
- Geofencing / proximity alerts.
- Offline map tiles.
- SMS remote control.

## 6. Acceptance Criteria
- App starts, requests permissions, starts tracking.
- Positions appear on PhoneTrack web UI.
- Other devices in the same session appear as markers.
- Configuration and sessions survive app restart.
- Menu provides access to About, Sessions, and Connection Status.
- Distro folder contains rev-N snapshot matching VERSION.
- Builds signed consistently for over-the-air style updates.

## 7. Revision History
- Rev 1 (2026-11-01): Initial MVP scaffold matching this specification.
- Rev 2 (2026-11-01): Added navigation menu with Connection Status and Phone Track Sessions pages; persistent multi-session configs; status display. Built from updated requirements.
- Rev 3 (2026-11-01): Menu updated to include About, Sessions, and Connection Status. Consistent signing key for updates.
- Rev 4 (2026-11-01): Confirmed same fixed keystore used for all builds going forward.
- Rev 5 (2026-11-01): Closed remaining Rev 4 gaps. URL format is validated. Publish interval (default 60 s) and map refresh (default 30 s) are configurable. Failed log posts are buffered and retried when the network returns. Own device uses a distinct marker. Fine, coarse, background, and notification permissions are requested, with a clear message if location is denied. The foreground service reloads saved config after process death. Publishes and fetches use the accepted server certificate.
- Rev 6 (2026-11-01): Configure screen can inspect the server certificate and accept a self-signed one by SHA-256 fingerprint, or forget a previously accepted certificate.
- Rev 7 (2026-11-01): A pasted PhoneTrack page link is reduced to the Nextcloud server address before logging or fetching positions.
