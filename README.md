# DashCam

Android dashcam that records the road (main/rear-facing camera) and the cabin/rear window
(selfie camera) at the same time, with a stamp burned into every frame.

## Features
- **Dual camera** front + rear simultaneously (needs a phone that supports concurrent cameras;
  falls back to front-only with an on-screen notice if not).
- **Stamp:** date, time and zone (or UTC), camera label, GPS lat/lon ± accuracy, speed in mph and km/h,
  heading, altitude, satellite count, optional custom text (e.g. plate). GPS position is also written
  into the MP4 metadata.
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
- **Portrait or landscape:** video takes the shape the phone is mounted in. Orientation is locked
  while recording.
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
