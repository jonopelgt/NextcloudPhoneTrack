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
- User can load the PhoneTrack sessions available to a Nextcloud account. The app calls the server session list with that user's app password and adds each session name and logging token. Newly imported sessions are disabled until the user turns one on. This does not list sessions belonging to other users.

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
- Parse JSON and render markers for every device in the enabled session. Turning that session off removes its markers. A position response that arrives after the session is turned off is discarded. PhoneTrack encodes a session with no points as an empty array under the session token. That response is zero devices, not a failed map update.
- The map has three follow modes. Group, also called session zoom, is the default: it uses the closest zoom that still shows every user in the enabled session. Activating a session sets group view when more than one current position is known, and follows that one person when only one current position is known. User follow also starts when that person is tapped on Friends or on their map marker. The + and − keys are high contrast and each step changes the zoom by a quarter level without leaving follow. Manual starts when the map is panned or pinched, and the map then stays where it was left. The All key zooms to the last observed device, the one whose position is the newest, and follows that device. The zoom controls are visible only while the map is the selected context.
- Show device name, accuracy, and age of fix on marker info.

### FR-4: Map
- Use OSMDroid with OpenStreetMap tiles.
- Multi-touch zoom/pan.
- Distinct marker for own device. Each person has a selectable color, shown on the map and on Friends. This phone's user defaults to blue. Each other person defaults to a different color. A chosen color is kept.

### FR-5: Permissions & Lifecycle
- Request fine location, coarse location, background location (Android 10+), notifications.
- Survive process death via START_STICKY service.
- Graceful handling of permission denial.

### FR-6: Menu
- Navigation drawer menu, from the top:
  - Map
  - Friends (each session, with that session's users indented below it). A session turned off says disabled. A session whose users cannot be loaded says unavailable. Each person has a color that can be changed. This phone's user defaults to blue. Other people default to colors that are different from each other and from blue. Each device has a tracks switch. Tracks are off until turned on.
  - Location (position update mode and location spoof). This page is separate from Settings.
  - Sessions (list of saved PhoneTrack sessions)
  - Connection Status (shows logging state, last update)
  - Settings (Nextcloud server, username, and app password, with a button to clear the login, and a section listing accepted self-signed certificates). A server address without http:// or https:// is saved as https://.
  - About (app info, version, purpose)
- If login fails because the server certificate is not trusted, the app shows that certificate for review and does not continue until the user accepts or cancels.
- The main map shows a bottom overlay that says Not connected when the server connection is down, and also when the active session's status is Failed. The overlay hides while that session's position updates are succeeding. If every session is turned off, that overlay says No active sessions instead, and it stacks with the other map banners.
- If the Nextcloud login has not been verified, the map also shows a Not logged in banner stacked with any other banner. A Settings button on the right opens the Settings page. Removing an accepted self-signed certificate clears the verified login, and the map shows the Not logged in banner.
- Connection Status lists every saved session with Off, OK, or Failed. OK is green while that session's position updates are succeeding. Failed is red when they are not. A session that is turned off shows Off. While Connection Status is open, it is drawn above every other control, including the mode button, zoom keys, and map banners. Tapping the map closes Connection Status and returns to the map.
- Sessions list displays saved configs. Each session has an enable switch. New session opens a full-screen builder that creates the session on the PhoneTrack server and shares it with the selected Nextcloud users. Sharing is chosen from a search list: typing filters the users, and selecting one adds that person. Selected people are shown and can be removed. The new session is saved turned off until the user enables it.
- The session builder also shows a public switch. When the session is public, an accordion directly under that switch shows the public URL. When it is not public, that accordion is hidden. It shows a switch labeled so it is clear this enables or disables the session on the server. Auto purge is a dropdown with the server's choices: off, daily, weekly, and monthly. The builder lists each device in the session with its name, color, and a tracks switch. The color can be changed.
- The session builder has a Local view section. Its controls affect only this phone's map and are not sent to the server. It includes a position age-out time, labeled Age out. The default is 15 minutes. On the map, a marker starts fully opaque and becomes more transparent as the fix gets older. It is fully invisible at the age-out time. When a device's tracks switch is on, each earlier position is a small dot in that device's color. Each dot fades on its own age and disappears at the same age-out time. Tracks are off until turned on. When the switch is off, those dots are hidden.
- Tapping a device on the map opens its status bubble. The bubble fades out after 5 seconds. Tapping a track dot opens a bubble that shows that point's age, and that bubble fades out after 5 seconds.
- Hold a session to open the session builder for that session, or to delete it. Delete removes the session from the PhoneTrack server for the logged-in account, then removes it from this phone. If the server refuses, the session stays. The old session configuration form is not used.
- Location, opened from the drawer, sets how this phone publishes its position. The modes are Off, which sends nothing on its own, Manual, which sends only when the user asks, Significant, which sends on a significant location change, and Move, which sends while the phone is moving. Rapid sends a position every second. Each mode in this menu uses the same icon as the map control: Manual a map pin, Off a map pin with a circle-slash, Significant a walking person, Move a car, and Rapid a rocket. A location spoof option, when on, publishes a chosen position instead of the phone's GPS and sends it at the normal Move rate. Its icon is a spy hat. A long-press on the map marks that latitude and longitude, switches the map to manual follow, and turns location spoof on at that point.
- The position update mode is also a control at the top right of the map. Each mode has an icon: Manual is a map pin, Off is a map pin with a circle-slash, Significant is a walking person, Move is a car, and Rapid is a rocket. Tapping the icon opens a small window listing the other modes so one can be selected.

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
- Rev 8 (2026-11-01): Configure is no longer a drawer item. Session setup is opened from Sessions.
- Rev 9 (2026-11-01): Hold a session to open a menu with Configure and Delete.
- Rev 10 (2026-11-01): Each session on the sessions page has an enable switch. Only one session is enabled at a time. Turning it off stops sharing.
- Rev 11 (2026-11-01): Sessions page can load the available PhoneTrack session list for a Nextcloud user.
- Rev 12 (2026-11-01): Position updates use the Nextcloud username as the device id when that username is saved.
- Rev 13 (2026-11-01): Connection Status shows OK in green while position updates are succeeding, and Failed in red when they are not.
- Rev 14 (2026-11-01): The sessions list shows each session's name from the PhoneTrack server.
- Rev 15 (2026-11-01): Friends page lists every saved session and indents the users reporting in that session.
- Rev 16 (2026-11-01): A session turned off on the Sessions page is gray and unavailable on Friends, and its users are still listed.
- Rev 17 (2026-11-01): Map positions use the logged-in user endpoint, or the public view token. The logging token is not sent to the public positions API, which was returning 404 and then HTTP 429. Rate limits pause requests, and buffered points are sent a few at a time.
- Rev 18 (2026-11-02): Settings page in the menu stores the Nextcloud login and can clear it.
- Rev 19 (2026-11-02): The main map shows a bottom overlay that says Not connected when the phone is not logged in or the server connection is down.
- Rev 20 (2026-11-02): If the Nextcloud login is not verified, a Not logged in banner stacks with the other map banners. Its Settings button opens Settings.
- Rev 21 (2026-11-02): Nextcloud login entered in Settings or From server is saved before the dialog closes and is still there the next time Settings is opened.
- Rev 22 (2026-11-02): Settings lists accepted self-signed certificates. Login that needs one shows the certificate for review before it is accepted.
- Rev 23 (2026-11-02): Settings treats a server address with no scheme as https://.
- Rev 24 (2026-11-02): New session on the Sessions page creates a PhoneTrack session and shares it with the usernames entered. The map menu control is a transparent hamburger. Settings opens full screen. Sessions no longer has a From server button.
- Rev 25 (2026-11-02): The sessions list stays open while it loads names or a session is turned on or off. It no longer closes and reopens.
- Rev 26 (2026-11-02): Turning off the session that is on the map clears its markers. A late position response does not put them back.
- Rev 27 (2026-11-02): Connection Status lists every session as Off, OK, or Failed. Configure opens the session builder for that session. The old session configuration form is removed.
- Rev 28 (2026-11-02): On Friends, a session turned off says disabled. A session that is on but whose users cannot be loaded says unavailable.
- Rev 29 (2026-11-02): The map defaults to group view. Tapping a person follows that person, with transparent zoom keys. Panning or pinching switches to manual. All returns to group view.
- Rev 30 (2026-11-02): Tapping a session on the Sessions page turns that session on if needed and sets the map to session zoom.
- Rev 31 (2026-11-02): Tapping the map while Connection Status is open closes that page and returns to the map.
- Rev 32 (2026-11-02): The map shows a No active sessions banner when every session is turned off. It stacks with the other map banners.
- Rev 33 (2026-11-02): Deleting a session deletes it on the PhoneTrack server for the logged-in account, then removes it from the phone.
- Rev 34 (2026-11-02): The map zoom keys are high contrast. Each tap changes the zoom by a quarter level.
- Rev 35 (2026-11-02): Each person has a selectable color. This phone's user defaults to blue. Other people default to unique colors.
- Rev 36 (2026-11-02): The session builder shares with people chosen from a search list that filters as you type. Selected people can be removed.
- Rev 37 (2026-11-02): The session builder shows a public switch, and the public URL in an accordion directly under it when public. It has a server enabled/disabled switch, an auto-purge dropdown of off, daily, weekly, or monthly, and each device's name and selectable color.
- Rev 38 (2026-11-02): The session builder has a Local view section for this phone only. Position age-out defaults to 15 minutes and fades a map marker with age until it vanishes at that time.
- Rev 39 (2026-11-02): Each device on Friends and in the session builder has a tracks switch. Tracks are off until turned on. When on, earlier positions are small dots in that device's color, each faded by its own age until the age-out time.
- Rev 40 (2026-11-02): Tapping a device on the map opens its status bubble. The bubble fades out after 5 seconds.
- Rev 41 (2026-11-02): If the active session's status is Failed, the map status banner says Not connected.
- Rev 42 (2026-11-02): The map zoom controls stay above every context window.
- Rev 47 (2026-11-02): The zoom controls are visible only while the map is the selected context. They are hidden for every other context window.
- Rev 48 (2026-11-02): Position update mode includes Rapid, which sends a position every second.
- Rev 49 (2026-11-02): The position update mode that sends nothing is labeled Off.
- Rev 50 (2026-11-02): The position update mode is a control at the top right of the map. Manual is a map pin, Off is a map pin with a circle-slash, Significant is a walking person, Move is a car, and Rapid is a rocket. Tapping it opens a small window of the other modes.
- Rev 51 (2026-11-02): The Location settings menu uses those same mode icons.
- Rev 52 (2026-11-02): The drawer order from the top is Map, Friends, Location, Connection Status, Settings, About, then Sessions.
- Rev 53 (2026-11-02): Turning a device's tracks switch on must show that device's earlier positions on the map. They are not showing.
- Rev 54 (2026-11-02): Tapping a track dot opens a bubble that shows that point's age. The bubble fades out after 5 seconds.
- Rev 55 (2026-11-02): The All zoom key zooms to the last observed device, the one with the newest position, and follows that device.
- Rev 56 (2026-11-02): In the drawer, Sessions is directly under Location and above Connection Status.
- Rev 57 (2026-11-02): The Rapid position mode icon is a rocket, not a plane.
- Rev 58 (2026-11-02): While Connection Status is open, it is drawn above every other control.
- Rev 59 (2026-11-02): A long-press on the map marks that latitude and longitude, switches the map to manual follow, and turns location spoof on at that point. Spoof uses a spy hat icon and sends at the normal Move rate.
- Rev 43 (2026-11-02): Removing an accepted self-signed certificate clears the verified login. The map then shows the Not logged in banner.
- Rev 44 (2026-11-02): The session builder Local view section labels the age-out field Age out.
- Rev 45 (2026-11-02): The drawer has a Location page, separate from Settings. It sets an OwnTracks-style position update mode: Quiet, Manual, Significant, or Move. It also has a location spoof option.
- Rev 46 (2026-11-02): Activating a session uses group view when more than one current position is known, and follows that one person when only one is known.
- Rev 60 (2026-11-02): A last-positions response whose session value is an empty array is zero devices. It does not fail the map update.
