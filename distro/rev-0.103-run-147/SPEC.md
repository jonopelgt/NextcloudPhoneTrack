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
- Each session has its own buffer. A failed send stays in that session's buffer and is retried there. It does not block another session, and the buffered count is only that session's points.
- Show persistent notification while tracking.

### FR-3: Position Retrieval & Display
- Every 30 s (configurable) GET:
  `https://{base}/apps/phonetrack/api/getlastpositions/{token}`
- Parse JSON and render markers for every device in the session shown on the map. Only one session is shown at a time. More than one session can be enabled, and each enabled session receives this phone's position. Turning the shown session off removes its markers and shows another enabled session when one is on. A position response that arrives after the session is turned off is discarded. PhoneTrack encodes a session with no points as an empty array under the session token. That response is zero devices, not a failed map update. This phone is drawn at its latest local fix when that fix is newer than the point the server has. Every fix is still posted, including while offline. None are dropped. Each position is its own push, so a stopped send does not hold the others. There are ten sending threads, and each has its own connection. A new position rotates to the next thread. When the network is down, those ten threads block and no more sockets are opened. The coordinates and time are copied when the fix arrives.
- The map has three follow modes. Group, also called session zoom, is the default: it uses the closest zoom that still shows every user in the enabled session. Activating a session sets group view when more than one current position is known, and follows that one person when only one current position is known. Tapping a person on Friends follows that person and does not change the zoom. It also selects that person's session for the map. User follow also starts when that person is tapped on their map marker. The + and − keys are high contrast and each step changes the zoom by a quarter level without leaving follow. A tap is one step. Holding a key keeps zooming until it is released. Between the + and − keys is a zoom slide. It frames the current zoom number, with a handle sticking out to the left and right, a triangle above the number pointing up, and a triangle below pointing down. The slide moves with the finger without waiting for the map. Its range is zoom 6 through zoom 19, and the same finger distance changes the zoom by the same amount up or down from the current zoom. In follow mode the slide changes the zoom, and the followed person stays centered in the map above the bottom status banners. Zoom all fits everyone into that same area. Tiles already on screen are scaled while the finger is down. When the finger is released, the scaled tiles are discarded and the sharp tiles for that zoom are loaded. The distance from where the touch started sets the zoom, and a full slide reaches the closest zoom upward or zoom 6 downward. The slide does not zoom out past zoom 6. Releasing it snaps the control back to center. The map stays at that zoom. Releasing the zoom slide or a zoom key puts the followed device back in the center and loads the tiles for that zoom. While the finger is down, tiles already on screen stay scaled. The + key, the zoom slide, and the − key change the zoom only and do not change who is followed. Pan and pinch can still leave follow. The + key, the zoom slide, and the − key stay visible in every follow mode while the map is the page on screen. The All button sits just above the follow-user control at the bottom right. It is gray while follow-all is on, and it looks normal when the map is following one device or not following. They hide when another page covers the map. Manual starts when the map is panned or pinched, and the map then stays where it was left. The All key fits every active user in the map window. It does not move the camera again while those users stay in view. An active user is one whose position has not reached the age-out time. It uses the closest zoom that still keeps every active user inside the window. A location control at the bottom right of the map uses a reticle icon. When the map is not following a person, the reticle is empty. When it is following a person, the reticle has a center circle in the same color as that person's map marker. The reticle circle is slightly larger. Pressing it cycles through the devices in the session and follows that device. If it is already on this phone, the next press follows the next device, then the others, and then returns to this phone. If this phone has no last position and there is no other device to follow, it shows unknown. The + key, the zoom slide, and the − key stay visible in every follow mode while the map is the page on screen. The All button sits just above the follow-user control at the bottom right. It is gray while follow-all is on, and it looks normal when the map is following one device or not following. They hide when another page covers the map. Debug logging for a map update records the map center, zoom, follow, sharing mode, and position mode. Follow is All, the followed person's name, or Manual. Each person is logged with age, age-out, whether the dot was drawn or why it was skipped, and whether the dot is on screen.
- Show device name, accuracy, and age of fix on marker info. Age is shown in hours and minutes.

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
  - Maps. This opens the Maps page. The page lists Organic Maps, OsmAnd, Magic Earth, OpenAndroMaps, and USGS topo. Each name shows that map's maximum zoom, such as z19 or z17. The selected one is the base map. A refresh slider sets how often the map reads positions. The steps are 0.5, 1, 2, 3, 4, 5, 10, 15, 30, 60, 300, and 600 seconds. The default is 3 seconds. Under that is a state search and an Update button. Choosing a state opens a table of map type by zoom, from zoom 6 through 19. A zoom that map cannot supply says N/A. Each cell shows that map's size, or OK if it is already stored. The user selects maps individually. The selected size updates immediately, including a total for each map row and each zoom column. Confirm queues each selected map separately. The downloaded list has a Sort button for state or map type. Each row shows its status, or a Download button if it is not current. A download tick updates that map's progress and does not rebuild the list. After a download finishes, the size is the sum of the tile files on disk. A saved size of 0 is measured again. Closer than the stored zoom, the map scales that local tile and does not wait on the network when a stored tile covers the spot. Any tile downloaded while the map is viewed is stored in the local cache. Whenever the map is adjusted, a cached tile is shown first. If that tile is more than 30 days old, a newer tile is checked for in the background. The downloaded list starts with a local-cache line showing the size and a Clear button. Clear removes the tiles saved while viewing and leaves the downloaded states. Downloaded states are listed under the search. Each state is two lines: the name, then the status and control. The status is a compact date, the stored zoom, and that state's saved size, such as 11/4/26 z12 (293M) OK. A slow connection does not freeze the app. The download uses its own connection, and progress is shown while a tile is still transferring. Only one state downloads at a time. Any others wait and show queued. The state that is downloading shows its recent download rate for all five maps, then the percent complete, then a progress bar, on the line under its name. Tiles already saved do not lower that rate. Each state has a button on the right: Cancel while it is queued or downloading, Delete when the copy is OK, and Update when it is older than a month. The app does not ask to download the state where this phone is if that state is already queued. Downloaded states are listed under the search with a compact date, such as 11/3/26, and that state's saved size in parentheses, such as (12M). The size is measured when the download finishes and kept until that state is updated or deleted. The Downloaded heading shows the total of those saved sizes. The Maps page opens right away. The date is followed by OK when the download is less than a month old. Update downloads again every state that is not OK. A metered connection asks before any download starts. If no state is downloaded, the app asks once to download the state where this phone is. After that question has been shown, it does not ask again. Organic Maps uses OpenStreetMap tiles, OsmAnd uses OsmAnd tiles, Magic Earth uses free Voyager street tiles because Magic Earth does not publish its own, OpenAndroMaps uses OpenTopoMap tiles through zoom 17 and no higher. Zooming closer than that scales those tiles instead of requesting a finer zoom. USGS topo uses US Geological Survey topo tiles.
  - Friends (each session, with that session's users indented below it). The session shown on the map has a radio button on the left and is listed first. Tapping a session name selects that session and sets the map zoom to All. A session turned off says disabled. A session whose users cannot be loaded says unavailable. Each person has a color that can be changed. Each person shows the time they were last seen in parentheses. A fix from the last 24 hours is the clock time, such as jon (10:32 AM). A fix from the last year is the day and month, such as jon (Nov 3). An older fix is the year, such as jon (2024). A person with no position has no parentheses. The Friends page opens immediately and shows the last known device list for each session. The latest list loads in the background and replaces that list when it finishes. The selected session is expanded and shows its users. A session that is not selected shows only its name. Its users still load in the background and stay hidden. The first tap expands that session. A tap on a session name that is already expanded selects it for the map. Each session loads on its own thread. This phone's user defaults to blue. Other people default to colors that are different from each other and from blue. Each device has a tracks switch. The word Tracks is shown once at the top of the Friends page, above those switches, and is not repeated on each switch. Tracks are off until turned on. Selecting a person who is not currently shown on the map places a gray dot at that person's last known location and zooms to the map's maximum zoom on that spot. A bubble shows the user and the last-seen time for 20 seconds. The gray dot fades after 30 seconds and is then gone. A person already shown on the map is unchanged.
  - Location (position update mode and location spoof). This page is separate from Settings.
  - Sessions (list of saved PhoneTrack sessions). A radio button on the left shows which session is on the map. Choosing a radio shows that session. More than one session can stay on. The Nextcloud user who created the session is shown next to the session name.
  - Connection Status (shows logging state, last update)
  - Settings (Nextcloud server, username, and app password, with a button to clear the login, a Location sharing switch, a home-screen widget of the same name, a Quick Settings tile of the same name, a Required permissions section, and a section listing accepted self-signed certificates). Location sharing on shows a status-bar icon of a map pin with a hollow center and broadcast waves while this phone is sharing its position. That notification stays until sharing is turned off. It cannot be swiped away or removed with Clear all. Off stops sharing and removes that icon. The map still reads positions from the server. A server address without http:// or https:// is saved as https://. Required permissions lists each permission this phone must grant. The status is OK or not granted. A Settings link on the right opens that permission's own page. Location opens the location permission screen. Notifications open notification settings.
  - About (app info, version, purpose)
- If login fails because the server certificate is not trusted, the app shows that certificate for review and does not continue until the user accepts or cancels.
- The main map shows the displayed session name in the bottom notification area. It also shows a bottom overlay when that session cannot be read, cannot be updated because the network failed, or this phone has no position. The message includes the session name. It says Connection fail read, Connection fail push, Connection fail read and push, or local position unavailable on a gray background. Connection fail push is only a network failure while sending a position. The overlay hides when none of those are true. If every session is turned off, that overlay says No active sessions instead, and it stacks with the other map banners.
- If the Nextcloud login has not been verified, the map also shows a Not logged in banner stacked with any other banner. A Settings button on the right opens the Settings page. Removing an accepted self-signed certificate clears the verified login, and the map shows the Not logged in banner.
- Connection Status lists every saved session with a radio button for the session shown on the map. Tapping a session selects it for viewing. Connection Status checks the network for every saved session and shows each session's connection status. Connection Status labels the columns Session name, Creator, and Status, and those columns line up with the values. The logging line, last update, buffered points, and log are for the selected session. Buffered points is every position for that session that has not been sent, including a fix still waiting to be sent. A session that is turned off shows Off. OK is green when the selected session can be read and its position updates are succeeding. Waiting means that session is on but this phone has not finished the first read or send. An empty position list is a successful read, not a failed one. A read stays OK while the map is updating, even when the separate network check is older than two refresh intervals. Buffered points for a session that is off, or no longer in the list, are dropped instead of retried. If this phone has no position, the message is local position unavailable on a gray background. A failed read says Connection fail read. Connection fail push means the network failed while sending a position. If both the read and the network send failed, it says Connection fail read and push. The same words, with the session name, are shown on the map banner. A session that is turned off shows Off. Its log puts the latest message at the bottom and auto-scrolls to that message. A verbosity slider above the log chooses Quiet, Normal, or Verbose. Quiet shows problems and state changes. Normal also shows sends and session actions. Verbose also shows polls, coordinates, and parsed responses. Debug, the highest step, shows every session plus the request URL, the response body, and the fields that were posted. The slider applies to the selected session's log, to the copy icon, and to Export log. The copy icon is to the left of Clear. Clear empties the log. Export log is to the right of Clear. An Export log button writes the current log to a text file and opens the share sheet so it can be saved or opened. Auto-scroll stops if the user moves away from the bottom, and it starts again when the user scrolls back to the bottom. While Connection Status is open, it is drawn above every other control, including the mode button, zoom keys, and map banners. Tapping the map closes Connection Status and returns to the map.
- Sessions list displays the sessions that are on the PhoneTrack server for the logged-in account. More than one session can be enabled at the same time. Tapping a session shows that session on the map and leaves the others on. The map shows only that one session. A session saved on this phone that is not on the server is not listed. The app names those sessions and deletes them from this phone only after the user confirms. Each listed session has an enable switch. New session opens a full-screen builder that creates the session on the PhoneTrack server and shares it with the selected Nextcloud users. Sharing is chosen from a search list: typing filters the users, and selecting one adds that person. Selected people are shown and can be removed. The new session is saved turned off until the user enables it.
- The session builder also shows a public switch. When the session is public, an accordion directly under that switch shows the public URL with a copy icon next to the link. When it is not public, that accordion is hidden. It shows a switch labeled so it is clear this enables or disables the session on the server. Auto purge is a dropdown with the server's choices: off, daily, weekly, and monthly. The builder lists each device in the session with its name, color, and a tracks switch. The color can be changed. For an existing session, the builder shows that session's token under the name. The token can be selected and copied. A Creator field directly under the session name shows the Nextcloud user who created the session. The fields are labeled Session name and Session creator. A Posted name field sets the name this phone sends. The default is the Nextcloud username, a hyphen, and the device name, such as jon-Pixel 6a, and it can be changed. The same Nextcloud user can be logged in on more than one device at the same time, and each device posts under its own name. The builder shows the session owner. Only the owner can change the session on the server, including sharing, public, enabled, auto purge, and delete. Someone who is not the owner can open the session and change the local view, but cannot save server changes.
- The session builder has a Local view section. Its controls affect only this phone's map and are not sent to the server. It includes a position age-out time, labeled Age out. The number is shown with the unit minutes. The default is 15 minutes. On the map, a marker starts fully opaque and becomes more transparent as the fix gets older. It is fully invisible at the age-out time. When a device's tracks switch is on, each earlier position is a small dot in the same color as that device's latest position, with no outline. Each dot fades on its own age and disappears at the same age-out time. Tracks are off until turned on. Turning a device's tracks switch off removes that device's dots from the map immediately.
- Tapping a device on the map opens its status bubble. The bubble fades out after 5 seconds. Tapping a track dot opens a bubble that shows that point's age in hours and minutes, and that bubble fades out after 5 seconds.
- Hold a session to open the session builder for that session, or to delete it. Delete removes the session from the PhoneTrack server for the logged-in account, then removes it from this phone. If the server refuses, the session stays. The old session configuration form is not used.
- Location, opened from the drawer, sets how this phone publishes its position. When position updates are Off, this phone does not send its position. The map still reads and shows positions from the server, and the map banner says local position unavailable. Manual sends only when the user asks, except that connecting to a session sends one position immediately when sharing is on. Significant is the low-power mode. It sends one position when selected, then takes a short GPS fix after the phone starts moving and sends only if that fix is about 300 meters from the last sent point. Walk uses a high-accuracy fix and sends after about 30 meters. Move sends the current position every 5 seconds. Rapid sends a position every second. A fix already queued or already accepted is not stored again, and a successful send removes every copy of that point. The Location page lists each mode's rule in a bulleted list. The map has two buttons at the top right. Position is the right button: Manual, Low power, Walk, Move, Rapid, and Spoof. Sharing is the button to its left: Off, 2 hours, 6 hours, 1 day, 4 days, and On. Each time choice uses a black map pin with a white hourglass inside and black broadcast waves to the right, and starts that share. When the timer ends, sharing turns off. The Location page can set the timer to 15 minutes, 30 minutes, 1 hour, 2 hours, 4 hours, 6 hours, 8 hours, 12 hours, 1 day, 2 days, or 4 days. Choosing a duration does not start the timer. Reset starts that duration from now. Opening the Location page does not change a running timer. Turning sharing on or off does not change the time left and does not change the position mode. The Position button stays on Manual, Significant, Move, Rapid, or Spoof. Only the Sharing button changes. The Location page countdown shows days, hours, minutes, and seconds, and updates every second. On leaves sharing on. Off stops sharing. The Quick Settings tile and the home-screen widget stay on or off, and they show the time left while a timer is running. Tapping either one while sharing is on stops sharing and does not reset the timer. Each mode in this menu uses the same icon as the map control: Manual a wrench, Off a map pin with a circle-slash, Significant a walking person, Move a car, and Rapid a rocket. A location spoof option, when on, publishes a chosen position instead of the phone's GPS and sends it at the normal Move rate. Its icon is a spy hat. Switching into spoof and switching out of spoof each ask the user to confirm before the change. Turning spoof on immediately sends one position update. A long-press on the map marks that latitude and longitude, switches the map to manual follow, and turns location spoof on at that point.
- Position and Sharing are separate buttons at the top right of the map. Position is on the right. Sharing is on its left. Each mode has an icon: Manual is a wrench, Off is a map pin with a circle-slash, Significant is a battery with one bar of three, Walk is a stickman, Move is a car, Rapid is a rocket, Spoof is a spy hat, Timer is a black map pin with a white hourglass inside and black broadcast waves to the right of the pin. On is the broadcast map pin. The home-screen icon is always the location-share pin. It does not change for on, timer, or off. A long press on Position opens only the position choices. Spoof is one of those choices only in superuser mode. Superuser mode is entered by tapping the session banner 15 times. The 15th tap shows a popup that says superuser mode is active. Taps on the other bottom banners do not count. It lasts until the app is closed and restarted, and there is no other way to leave it. A long tap on the map does nothing until superuser mode is on. After that, a long tap turns spoof on at that point. A short tap on Position does not open that menu. A short tap sends the current position once, except in Off, which sends nothing. A long press on Sharing opens Off, 2 hours, 6 hours, 1 day, 4 days, and On. A short tap on Sharing does nothing. Choosing On turns the timer off and leaves sharing on. While a timer is running, the bottom session banner shows the session name and the time left in parentheses, including when sharing stops. A stop today is a clock time, such as ohio (3h 12m, until 2:53 PM). When more than 24 hours remain, the time left is days and hours with no minutes, such as ohio (1d 2h, until Friday 2:53 PM). A stop on the next day says tomorrow, such as until tomorrow 2:53 PM. A later stop uses the day of the week, such as until Friday 2:53 PM. Each button icon updates as soon as its mode changes.

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

## 7. Problem reports
Problem reports are kept in [PRS.xlsx](PRS.xlsx) at the root of this repository, on the PRs sheet. Each report has a number, the problem, the fix, the status, and the build that implemented it. Status is Open or Implemented. Reports through 158 were imported from the revision history below, and the problem and the fix are that history line. New reports continue after that. PR 159 is implemented in 0.73 run 113. A report is added when it is filed. Each build updates that report's fix, status, and build, and updates the requirements in this specification with the behavior that shipped. The build cell is the published revision and run, such as 0.72 run 111. TODOs are not problem reports. They are kept on the TODOs sheet in the same file, with their own numbers. A TODO has a number, the task, the notes, and the status. Status is Open or Done.

## 8. Revision History
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
- Rev 60 (2026-11-02): The Connection Status log shows the latest message at the bottom. Auto-scroll stops when the user leaves the bottom and starts again when the user scrolls back to the bottom.
- Rev 61 (2026-11-02): The location popup lists every mode and shows each mode's icon.
- Rev 62 (2026-11-02): The Manual position mode icon is a wrench.
- Rev 63 (2026-11-02): Selecting a new location mode updates the mode control on the map immediately. It does not.
- Rev 64 (2026-11-02): Switching into location spoof and switching out of it each ask the user to confirm.
- Rev 65 (2026-11-02): Turning a device's tracks switch off removes that device's dots from the map immediately. They do not.
- Rev 66 (2026-11-02): The location mode control opens on a long press. A short tap does not open it.
- Rev 67 (2026-11-02): A tap on the location mode control sends the current position once. Off sends nothing.
- Rev 68 (2026-11-02): Track and marker age is shown in hours and minutes.
- Rev 69 (2026-11-02): The All zoom control is hidden while the map is already in All mode.
- Rev 70 (2026-11-02): A reticle at the bottom right of the map centers on this phone's user and switches zoom and track to follow that user.
- Rev 71 (2026-11-02): Connection Status auto-scrolls to the latest message. It does not.
- Rev 72 (2026-11-02): Spoof is a choice in the map location control menu, with the spy hat icon. It is not.
- Rev 73 (2026-11-02): The session config shows the session owner. Only the owner can update the session on the server.
- Rev 74 (2026-11-02): Track dots have no outline.
- Rev 75 (2026-11-02): Turning location spoof on immediately sends one position update.
- Rev 76 (2026-11-02): Connection Status has an Export log button.
- Rev 43 (2026-11-02): Removing an accepted self-signed certificate clears the verified login. The map then shows the Not logged in banner.
- Rev 44 (2026-11-02): The session builder Local view section labels the age-out field Age out.
- Rev 45 (2026-11-02): The drawer has a Location page, separate from Settings. It sets an OwnTracks-style position update mode: Quiet, Manual, Significant, or Move. It also has a location spoof option.
- Rev 46 (2026-11-02): Activating a session uses group view when more than one current position is known, and follows that one person when only one is known.
- Rev 77 (2026-11-02): A last-positions response whose session value is an empty array is zero devices. It does not fail the map update.
- Rev 78 (2026-11-02): The session builder shows the session token under the name. The token can be selected and copied.
- Rev 79 (2026-11-02): Offline points were dropped. The buffer kept only 30 points, and a retry could erase points saved while it was sending. A point is stored before it is uploaded and kept across a long outage. Stored points upload a few at a time, oldest first, when the connection returns.
- Rev 80 (2026-11-02): Connection Status Export log writes the current log to a text file and opens the share sheet so it can be saved or opened.
- Rev 81 (2026-11-02): Turning on a session sends a position immediately. If Play location does not start, the phone location provider is used. A session with no position sent yet says Waiting, not Failed. Location off is reported as location off.
- Rev 82 (2026-11-02): The connection message says Connection fail read, Connection fail push, or Connection fail read and push. It no longer says Failed for both.
- Rev 83 (2026-11-02): Settings has a Required permissions section. Each permission shows OK or not granted, with a Settings link on the right.
- Rev 84 (2026-11-02): The Settings link opens that permission. A permission that is not granted opens its system prompt. If the prompt will not show, or it is already granted, the link opens that permission's own settings page.
- Rev 85 (2026-11-02): All zoom fits every active user in the map window. It no longer follows only the newest position. An active user is one whose position has not reached the age-out time.
- Rev 86 (2026-11-02): The Friends page lists the active session first.
- Rev 87 (2026-11-02): Track history dots are solid color with no white outline. Current-position markers still have an outline.
- Rev 88 (2026-11-02): Connection fail push means the network failed while sending a position. If this phone has no position, the message is local position unavailable.
- Rev 89 (2026-11-02): The session list shows only sessions that are on the PhoneTrack server. A session saved on this phone that is not on the server is not listed. The app names it and deletes it from this phone only after the user confirms.
- Rev 90 (2026-11-02): The local position unavailable message uses a gray background on the map banner and on the session row. A connection failure stays red.
- Rev 91 (2026-11-02): The Settings link opens that permission's own page. Location opens the location permission screen. Notifications open notification settings. It does not stop at the system prompt or the general app page when the phone can open the specific page.
- Rev 92 (2026-11-02): The session Age out field shows the unit minutes next to the number.
- Rev 93 (2026-11-02): More than one session can be enabled at the same time, and each enabled session receives this phone's position. The map shows only the session the user tapped. That session's name is shown in the bottom notification area. Connection and map messages include the session name.
- Rev 94 (2026-11-02): On the Friends page, the word Tracks is shown once at the top, above the track switches. It is not repeated on each switch.
- Rev 95 (2026-11-02): The drawer item is Maps and opens the Maps page. A base map can be Organic Maps, OsmAnd, Magic Earth, OpenAndroMaps, or USGS topo. A state search downloads all five for offline use. Downloaded states show the date and OK when newer than a month. Update refreshes the ones that are not OK. A metered connection asks before the download.
- Rev 96 (2026-11-02): If no state is downloaded, the app asks to download the state where this phone is.
- Rev 97 (2026-11-02): The map user button centers on this phone's last known position, wherever that position was. If there is no last position, it shows unknown.
- Rev 98 (2026-11-02): The Maps page downloads one state at a time. Waiting states show queued. The state being downloaded shows a progress bar.
- Rev 99 (2026-11-02): The download rate is shown to the left of the progress bar.
- Rev 100 (2026-11-02): The percent complete is shown to the right of the download rate. A state already queued is not asked for again. Each state has Cancel, Delete, or Update on the right.
- Rev 101 (2026-11-02): The Sessions page has a radio button showing the session selected for viewing. Choosing it shows that session on the map.
- Rev 102 (2026-11-02): OpenAndroMaps stops at zoom 17. Zooming closer scales those tiles and does not request a higher zoom.
- Rev 103 (2026-11-02): Connection Status has a radio button for the session shown on the map. Tapping a session selects it. The status messages are for that session.
- Rev 104 (2026-11-02): Friends shows the session on the map with a radio button on the left. That session is at the top of the list.
- Rev 105 (2026-11-03): The Sessions page shows the Nextcloud user who created each session next to the session name.
- Rev 106 (2026-11-03): The session configure page has a Creator field directly under the session name.
- Rev 107 (2026-11-03): Tapping a person on Friends keeps the current zoom and follow behavior, and selects that person's session.
- Rev 108 (2026-11-03): Connection Status checks the network for every session. Only the selected session's messages and log are shown.
- Rev 109 (2026-11-03): With position updates Off, the map still reads and shows server positions, and the banner says local position unavailable.
- Rev 110 (2026-11-03): Connection Status shows each session's connection status and keeps updating it.
- Rev 111 (2026-11-03): Tapping a session name on Friends selects that session and sets the map zoom to All.
- Rev 112 (2026-11-03): Each session's connection status does not repeat the session name.
- Rev 113 (2026-11-03): Connection Status labels Session name and Session creator, and shows the creator for each session.
- Rev 114 (2026-11-03): The session configure page labels Session name and Session creator. A copy icon next to the public link copies it when the session is public.
- Rev 115 (2026-11-03): Connection Status has a verbosity slider for the selected session's log: Quiet, Normal, or Verbose.
- Rev 116 (2026-11-03): Maps has a refresh slider from 0.5 seconds to 600 seconds.
- Rev 117 (2026-11-03): The map refresh defaults to 3 seconds.
- Rev 118 (2026-11-03): Connection Status has a copy icon to the left of Export log. It copies the current log.
- Rev 119 (2026-11-03): The log verbosity slider adds Debug above Verbose. Debug shows every session, the request URL, the response body, and the posted fields.
- Rev 120 (2026-11-03): Connection Status has a Clear button between the copy icon and Export log. Clear empties the log.
- Rev 121 (2026-11-03): Maps shows a compact download date and the state's disk size in parentheses. The Downloaded heading shows the total size.
- Rev 122 (2026-11-03): A successful empty session read is not Connection fail read. Buffered points for a session that is off or gone are dropped.
- Rev 123 (2026-11-03): Each session has its own buffer. A failed send does not block the other sessions.
- Rev 124 (2026-11-03): The Maps page opens without waiting to measure the downloaded files.
- Rev 125 (2026-11-03): Choosing a state to download does not freeze the app on a slow connection.
- Rev 126 (2026-11-03): A state's map size is measured when its download finishes and kept until that state is updated or deleted.
- Rev 127 (2026-11-03): Pressing and holding a map zoom key keeps zooming until the key is released.
- Rev 128 (2026-11-03): The app icon is a map pin with broadcast waves.
- Rev 129 (2026-11-03): Settings has a Location sharing switch. While sharing is on, the status bar shows a map pin with broadcast waves. Off removes that icon and stops sharing.
- Rev 130 (2026-11-03): The position-broadcast status icon is not a silent notification, so it stays in the status bar while sharing is on.
- Rev 131 (2026-11-03): The app icon is Nextcloud blue. The broadcast waves sit lower so they fit inside the round icon.
- Rev 132 (2026-11-03): The status-bar broadcast icon fills its slot. The pin is solid and the waves are thicker.
- Rev 133 (2026-11-03): A home-screen widget named Location sharing turns sharing on or off.
- Rev 134 (2026-11-03): The status-bar pin has a small hollow circle, sized like a standard map pin.
- Rev 135 (2026-11-03): A Quick Settings tile named Location sharing turns sharing on or off.
- Rev 136 (2026-11-03): Only the session owner can change that session on the server. A non-owner can still change the local view.
- Rev 137 (2026-11-03): Turning location spoof on sends that position immediately.
- Rev 138 (2026-11-03): Friends shows the last-seen time in parentheses next to each user.
- Rev 139 (2026-11-03): The position-broadcast notification cannot be cleared while sharing is on.
- Rev 140 (2026-11-03): The Quick Settings tile updates the open map, Settings switch, and Location page.
- Rev 141 (2026-11-03): Friends opens immediately. Each session loads on its own thread and fills in when its data arrives.
- Rev 142 (2026-11-03): Last seen is the time if it is within a day, the day and month if it is within a year, and the year if it is older.
- Rev 143 (2026-11-03): Split screen no longer crashes the map drawer. The activity keeps its layout when the window is resized.
- Rev 144 (2026-11-03): Turning location sharing on or off from the Quick Settings tile no longer crashes the app.
- Rev 145 (2026-11-03): Friends shows users only for an expanded session. The selected session starts expanded. The first tap on a collapsed session expands it. A tap on an expanded session name selects it. Users for collapsed sessions still load in the background.
- Rev 146 (2026-11-03): A location result that arrives after the sharing service has stopped is ignored, so it no longer crashes the app.
- Rev 147 (2026-11-03): Sharing can be Off, On, or Timer. Timer defaults to 4 hours and can be set up to 4 days. It turns sharing off when it ends.
- Rev 148 (2026-11-03): The Location page countdown shows seconds and updates every second.
- Rev 149 (2026-11-03): Turning sharing on or off does not reset the timer.
- Rev 150 (2026-11-03): Position and Sharing are two map buttons. Position is on the right and Sharing is on its left. Each opens only its own menu.
- Rev 151 (2026-11-03): Opening the Location page does not restart the timer. Reset is the button that starts the chosen duration.
- Rev 152 (2026-11-03): Connecting to a session sends one position immediately when sharing is on, including Manual and Significant.
- Rev 153 (2026-11-03): Turning sharing on or off does not change the position mode. Only the Sharing button changes.
- Rev 154 (2026-11-03): The Sharing menu opens on press and hold. Choosing On turns the timer off. The session banner shows the hours and minutes left.
- Rev 155 (2026-11-03): The session banner puts the time left in parentheses. Zoom All does not move the camera when the users are already in view.
- Rev 156 (2026-11-03): On in the Sharing menu uses the broadcast map pin.
- Rev 157 (2026-11-03): The Sharing menu offers 2 hours, 6 hours, 1 day, and 4 days. The map draws positions without waiting for the track download, and it does not start another read while one is still running.
- Rev 158 (2026-11-04): Track dots stay on the map while it refreshes. After the first download, only points newer than the newest stored point are requested.
- Rev 159 (2026-11-04): A downloaded state shows immediately past the stored zoom by scaling the local tile, with no network wait where a local tile covers the spot. A download asks for zoom 12 through 16 and shows the estimated size. The status line includes the zoom. The self-track button is an empty reticle, or a reticle with a center dot in the tracked person's color.
- Rev 160 (2026-11-04): The self-track reticle circle is larger. A zoom slide between the + and − keys shows the zoom number. Dragging it sets the zoom, then it snaps back to center.
- Rev 161 (2026-11-04): The zoom control stays visible in every follow mode. The zoom slide has a longer travel and does not zoom out past zoom 6. A Debug map update records the map center, zoom, follow, sharing mode, position mode, and why each person was drawn or skipped.
- Rev 162 (2026-11-04): Friends shows the last known device list immediately and replaces it when the latest load finishes. The zoom keys and zoom slide do not change follow. The follow reticle uses the followed person's color. The zoom slide travel is a little shorter.
- Rev 163 (2026-11-04): The zoom slide moves freely. The map zoom follows, and tiles already on screen are scaled until the finger is released. The follow button's center circle uses the followed device's marker color.
- Rev 164 (2026-11-04): Timer mode also shows the clock time sharing stops. The Debug log records the phone clock, fix time, elapsed age, provider, posted timestamp, and buffer depth. The zoom slide keeps a followed person centered, and sharp tiles replace the scaled tiles after the finger is released.
- Rev 165 (2026-11-04): Zoom and follow stay in the map above the bottom status banners. The zoom slide runs from zoom 6 to zoom 19 and scales travel from the current zoom, including while following a person. A timer stop says tomorrow or the weekday when it is not today. The sessions page labels the columns Session and Creator.
- Rev 164 (2026-11-04): Releasing the zoom slide or a zoom key recenters on the followed device and loads the tiles for that zoom. Scaled tiles are only a stand-in while the finger is down.
- Rev 166 (2026-11-04): Time left of more than 24 hours reads in days and hours with no minutes. Each Maps state is two lines, the name and then the status and control. The download rate is the recent speed of all five maps.
- Rev 167 (2026-11-04): The follow control cycles through the devices. If it is already on this phone, the next press follows the next device.
- Rev 168 (2026-11-04): The same Nextcloud user can post from more than one device. The posted name defaults to the username, a hyphen, and the device name, and Session settings can change it.
- Rev 169 (2026-11-04): A tile downloaded while viewing the map is kept in the local cache. The map shows a cached tile first and refreshes one older than 30 days in the background. The Maps page shows the cache size and a Clear button.
- Rev 170 (2026-11-04): The All button sits just above the follow-user control. It is gray while follow-all is on.
- Rev 171 (2026-11-04): Each map name shows its maximum zoom. This phone is drawn at its latest local fix, and no fix is dropped. Buffered points counts every position that has not been sent.
- Rev 172 (2026-11-04): Each position is its own push. A stopped send does not hold the others. No position is dropped.
- Rev 173 (2026-11-04): Ten sending threads each keep one connection. New positions rotate through them, so a dead network does not open more sockets.
- Rev 174 (2026-11-04): A download tick updates that state's progress and does not rebuild the Maps list. Sizes are measured once, off the main thread.
- Rev 175 (2026-11-04): The state chooser is a table of map type by zoom. Each selected map is queued on its own. The downloaded list sorts by state or map type.
- Rev 176 (2026-11-04): The chooser lists zoom 6 through 19. Unavailable zooms say N/A. Selected size totals update live for the whole selection, each map row, and each zoom column.
- Rev 177 (2026-11-04): After a download finishes, the displayed size is the sum of the tile files on disk. A saved size of 0 is measured again.
- Rev 178 (2026-11-04): A user's track dots use the same color as that user's latest position dot.
- Rev 179 (2026-11-04): Connection status columns are Session name, Creator, and Status, and they line up.
- Rev 180 (2026-11-04): The timer sharing icon is an hourglass, a map pin, and broadcast lines.
- Rev 181 (2026-11-04): The timer icon matches the On pin and broadcast lines, with an hourglass inside the pin.
- Rev 182 (2026-11-05): Low power sends after about 300 meters and uses a one-bar battery. Walk sends after about 30 meters and uses a stickman. Move sends every 5 seconds. An accepted point is not sent again. The Location page lists each mode.
- Rev 183 (2026-11-05): The local-state download question is asked once. Changing the home-screen icon does not close the app. Spoof is shown only in superuser mode, entered by tapping the bottom banner 15 times, and that mode ends when the app restarts.
- Rev 184 (2026-11-05): Only the session banner counts toward superuser mode, and it stays in front of the map. A long tap on the map does not turn spoof on until superuser mode is on.
- Rev 185 (2026-11-05): The 15th session-banner tap shows a popup that superuser mode is active. Switching from On to a timer does not close the app. The icon that started this run stays until the next time the app is opened.
- Rev 186 (2026-11-05): The home-screen icon stays the location-share pin. Timer icons are a black map pin, a white hourglass inside, and black broadcast waves to the right.
- Rev 187 (2026-11-05): Selecting a friend who is not on the map shows a gray last-seen dot, zooms to the maximum, and shows a bubble for 20 seconds. The dot fades after 30 seconds.
