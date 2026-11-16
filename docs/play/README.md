# Google Play setup

Everything Play Console asks for, ready to paste. App: **4x4 Coach**, package `com.gshotwell.hr4x4`, free, no ads, no in-app purchases.

## 1. Developer account (one-time, covers every future app)

1. Use a Google account with 2-Step Verification on. A dedicated one keeps app mail separate.
2. Go to <https://play.google.com/console/signup>, choose **Yourself** (personal).
   - Personal is right for apps shared with friends. The 12-testers-for-14-days rule only applies if you later go public.
   - Organization needs a D-U-N-S number. Apps can be transferred to an organization account later if that ever matters.
3. Developer name: what Play shows as the publisher. Pick something you're happy reusing for future apps.
4. Pay the US$25 fee and upload a government ID. Approval usually takes 1–3 days.
5. Install the **Play Console** app on your Pixel and complete device verification when prompted.

## 2. Create the app

Play Console → **Create app**:

| Field | Value |
|---|---|
| App name | 4x4 Coach |
| Default language | English (Canada) or English (US) |
| App or game | App |
| Free or paid | Free |

## 3. First upload (by hand)

1. Download `hr4x4-coach.aab` from the [latest release](../../../releases/tag/latest).
2. **Testing → Internal testing → Create new release**. Accept **Play App Signing** (Google holds the final signing key; the key in this repo becomes the *upload key*).
3. Upload the `.aab`, release name anything, **Save → Review release → Start rollout**.
4. **Testers** tab → create an email list with your friends' Google account emails → copy the **opt-in link** and send it to them.

> Your phone currently has the sideloaded build. Play's copy is signed with a different key, so uninstall the sideloaded one first (settings are lost, re-pair the strap).

## 4. Automatic uploads after that (optional)

Each push to `main` can go straight to internal testing:

1. Play Console → **Setup → API access** → link a Google Cloud project (create one if asked).
2. In Google Cloud → IAM → **Service accounts** → create one (e.g. `play-uploader`) → **Keys → Add key → JSON** → download.
3. Back in Play Console → **Users and permissions → Invite new user** → the service account's email → app permissions for 4x4 Coach: **Release apps to testing tracks**.
4. GitHub repo → **Settings → Secrets and variables → Actions → New repository secret** → name `PLAY_SERVICE_ACCOUNT_JSON`, value: the entire JSON file.

The build workflow already contains the upload step; it switches on once the secret exists.

## 5. Forms Play requires before testers can install

### Store listing

- **Short description (80 chars):**
  Norwegian 4x4 interval coach for Polar H10: zone alerts, max HR, Health Connect

- **Full description:**

  4x4 Coach runs the Norwegian 4x4 interval workout (four 4-minute intervals at 85–95% of max heart rate with 3-minute recoveries) using a Polar H10 or any standard Bluetooth heart-rate strap.

  • Tone, voice and vibration alerts the moment your heart rate leaves the target zone
  • Ramp grace, hold time and hysteresis so it doesn't nag while your heart rate catches up
  • Keeps running with the screen locked
  • Learns your real max heart rate, with an optional all-out max test
  • Summary with time in zone, heart-rate chart and 1-minute recovery drop
  • Optional next-song command for your music app at each interval
  • Optional export to Health Connect

  Not a medical device. Check with a doctor before near-maximal exercise if you have a heart condition.

- **App icon** (512×512 PNG): `docs/play/icon-512.png`
- **Feature graphic** (1024×500 PNG): `docs/play/feature-graphic.png`
- **Phone screenshots** (at least 2): `docs/screenshots/workout.png`, `setup.png`, `summary.png`
- **Category:** Health & Fitness
- **Contact email:** your developer email
- **Privacy policy URL:** a public page with the text from [privacy-policy.md](privacy-policy.md) (see note there)

### App content

| Section | Answer |
|---|---|
| Privacy policy | URL as above |
| Ads | No ads |
| App access | All functionality available without special access. Note for reviewers: "Use Advanced → Simulated heart rate to try the workout without a strap." |
| Content rating | Questionnaire → Category "Utility, productivity, communication or other" → answer No to everything → rated Everyone |
| Target audience | 18 and over |
| News app | No |
| Government app | No |
| Financial features | None |
| Health apps | Select **Activity and fitness** |
| Data safety | See below |
| Foreground service permissions | See below |
| Health Connect | See below |

### Data safety

- Does your app collect or share any of the required user data types? **No.**
  - All heart-rate data stays on the device. Health Connect export is on-device, user-initiated, and goes to another app the user chooses. Under Play's definitions that isn't "collection" (nothing is sent off the device to the developer) or "sharing" with a third party.
- Is all user data encrypted in transit? **Not applicable** (no network transmission).
- Do you provide a way to request deletion? Users can delete all data by clearing app storage or uninstalling.

If Play's review pushes back, the conservative alternative is to declare **Health and fitness → Health info / Fitness info: collected, not shared, processed on device only (ephemeral: no), optional, purpose: App functionality.**

### Foreground service declaration

Play Console → App content → **Foreground service permissions**:

- Type: **Connected device** (`FOREGROUND_SERVICE_CONNECTED_DEVICE`)
- Description: *The app keeps a Bluetooth Low Energy connection to the user's heart-rate chest strap during a workout the user starts, so it can time the intervals and alert the user (tone, voice, vibration) when their heart rate leaves the target zone, including while the screen is off. The service stops when the workout ends or the user disconnects.*
- User impact if deferred: *Interval timing and heart-rate alerts would stop when the screen turns off, which defeats the purpose of the workout.*
- Video: a 20–30 s screen recording of starting a workout, locking the phone, and the notification showing the live timer and heart rate.

### Health Connect declaration

The app requests **write** access only: `WRITE_EXERCISE` and `WRITE_HEART_RATE`.

- Justification: *When the user turns on "Save to Health Connect", each finished workout is written as an exercise session (high-intensity interval training or the chosen activity) with its heart-rate samples recorded from the user's chest strap. This lets other apps the user chooses, such as their ring or watch app, include the workout. The app never reads Health Connect data.*

If the Health Connect form holds up the internal test, temporarily remove the two `android.permission.health.*` lines from the manifest for the Play build and add them back once approved.
