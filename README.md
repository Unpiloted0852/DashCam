# DashCam

Android dashcam that records the road (main/rear-facing camera) and the cabin/rear window
(selfie camera) at the same time, with a stamp burned into every frame.

## Features
- **Dual camera** front + rear simultaneously (needs a phone that supports concurrent cameras;
  falls back to front-only with an on-screen notice if not).
- **Configurable stamp:** date/time (24 h or 12 h, local or UTC), camera label, custom text, and
  each of GPS position ± accuracy, speed (mph / km/h / both), heading, altitude (ft / m), satellite
  count, optional place name (road and town), and an optional weather line from the nearest METAR
  (airport, temperature, dew point, humidity, wind/gusts, visibility, cloud base, rain/snow/fog,
  altimeter setting). Small / normal / large text. Every field has a fixed width so the stamp never
  changes shape. GPS position is also written into the MP4 metadata.
- **Accurate time:** like "atomic clock" apps, syncs with internet time servers (NTP, ±~10 ms,
  every 15 min) and falls back to GPS satellite time with no data. The stamp shows each frame's
  capture time, independent of the phone's own clock.
- **Altitude:** barometric, from the phone's pressure sensor, corrected with the altimeter setting
  (QNH) of the nearest METAR (aviationweather.gov; newest report per station, refreshed every
  15 min or after 25 km, reports over 3 h old skipped). Station changes go to the diagnostic log.
  Uses 29.92 until a METAR arrives; uses GPS altitude ("gps alt") if the phone has no barometer.
- **Lens choice** for the road camera (default: widest, e.g. the ultra-wide), including lenses some
  phones hide from apps.
- **Force far focus** (on by default for the road camera): lens locked at infinity, hyperfocal or a set
  distance so it never hunts onto the dash/windshield. On phones without manual focus it falls back to
  autofocus restricted to the road area. Quick toggle on the main screen.
- **Portrait or landscape:** video takes the shape the phone is held in. Turning the phone between
  portrait and landscape while recording continues in a new clip in the new shape, with the old
  and new clips overlapping by about a second so nothing is lost (front and rear stay paired); a
  180° flip just keeps the picture upright.
- **Background:** foreground service + wake lock; keeps recording with the screen off or another app
  (navigation) in front. Asks once for an unrestricted-battery exemption.
- **Loop recording** in 1–10 min clips with a size limit (oldest deleted first) and a 1 GB free-space floor.
- **Lock clip** (button or notification) protects the current and previous clip in `Movies/DashCam/Locked`.
- **Auto-start** every time the app is opened (setting).
- **End-of-drive detection:** after you've driven, recording stops when you walk away (step
  detector), when the car charger is unplugged while stopped, or (optional) when the car's Bluetooth
  disconnects — each with a short grace period that driving again cancels — with a "parked for N
  minutes" timer as a backup. The reason is logged and shown in the notification.
- **Clips screen** (🎞): every recording with front/rear thumbnails, length, size and lock state;
  "Locked only" filter; tap to play, long-press to lock/unlock, share or delete. The player shows
  Front, Rear or Both side by side (stacked in portrait), kept in sync with one seek bar.
- **Audio** is recorded into both the front and rear clips, so either file stands on its own.
- **Storage:** internal or SD card (`Movies/DashCam` on the chosen volume); falls back to internal storage if the card is missing.

- **Remote camera (second phone)** — *experimental, `remote-camera` branch*: an old phone (no SIM,
  internet or Google Play Services needed, LineageOS is fine) stuck to the rear window records as a
  remote camera controlled by the main phone over Bluetooth.
  - Setup: pair the phones once in Android's Bluetooth settings; in DashCam Settings → *Remote camera*
    set one phone to *Main phone, with a remote camera* and the other to *Remote camera for another
    phone*, and pick each other. The remote records one camera (back, using its Lens setting, or selfie).
  - Each phone records its own clips; footage never goes over the link, so a dropped link loses nothing.
  - The main phone starts/stops the remote, ends its drive (end-of-drive detection) and mirrors the
    stamp, clip length, quality, audio and auto-stop settings. Lock on either phone locks both;
    Record/Stop on the remote asks the main phone.
  - The main phone is the remote's clock: NTP-style ping exchange over the link (best of 12 round
    trips, refreshed every minute), so both stamps agree to within a few ms. It also sends its
    position, speed, heading, altitude, satellites, place name and METAR weather once a second for
    the remote's stamp.
  - Clips pair up by name: the remote uses the main phone's segment times, tagged `_remote`
    (e.g. `2026-11-07_21-54-55_remote.mp4` next to `…_front.mp4` and `…_rear.mp4`).
  - If the link drops the remote keeps recording with its own GPS (if any), the last weather it
    received, GPS time after 10 minutes, and its own end-of-drive detection; it rejoins automatically.
  - Android won't let a camera app start from the background, so open DashCam on the remote once after
    switching it on. It can then sit with the screen off: it turns its cameras and GPS off and waits
    for the main phone. "Turn off" in its notification stops it.
  - Both phones must run the same link protocol; otherwise both show "Versions don't match".

- **Updates:** checks this repo's GitHub Releases when the app opens (at most every 6 h, never while
  recording) and offers to download and install the new APK. Also "Check for updates now" in Settings.

## Install
Download the APK from [Releases](https://github.com/Unpiloted0852/DashCam/releases/latest) and open it
on the phone (allow installing from your browser/file manager when asked). Later versions install
from inside the app.

## Release a new version
```
./release.sh 1.0.1 "What changed"
```
Builds a signed APK, tags `v1.0.1`, pushes, and creates the GitHub Release. Every release must be
signed with the same key (`~/.android/dashcam-release.jks`, settings in `~/.gradle/gradle.properties`);
back both up — without them, installed copies can't be updated.

## Build from source
```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Experimental builds (`./gradlew assembleExp`) install as "DashCam exp" (`com.example.dashcam.exp`) next to
the real app and never self-update. Don't publish releases from experimental branches.
