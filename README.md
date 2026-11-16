# 4x4 Coach

Android app for Norwegian 4x4 interval training with a Polar H10 (or any standard BLE heart-rate strap).

- Warm-up → 4 × (4 min work / 3 min recovery) → cool-down, all configurable
- Per-phase target zones as % of max HR
- Tone + voice + vibration whenever HR crosses a zone boundary, with ramp grace, hold time and hysteresis to stop nagging
- Runs as a foreground service: keeps working with the screen locked
- Optional "next song" media command at the start of each interval / recovery (works with Tidal, Spotify, etc.)
- Optional Health Connect export (HIIT exercise session + heart-rate samples)
- Per-interval summary; CSV saved to `Android/data/com.gshotwell.hr4x4/files/sessions/` and shareable

## Install

Every push to `main` builds a signed APK and attaches it to the **latest** release. Open the release on your phone and tap `hr4x4-coach.apk` (allow installs from your browser when prompted). New builds install over old ones because the signing key is committed (`app/signing.keystore`) — fine for a personal sideloaded app, don't reuse it for anything published.
