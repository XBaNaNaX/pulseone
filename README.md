# Pulse One Local

Current diagnostic release: `0.2.8` (`versionCode 9`). Auto SpO2 history is
parsed as timestamped historical data in a bounded, deduplicated diagnostic
session. Explicit `66-FF` completion is verified; clean inter-packet idle after
valid data is completed with unverified completeness. History is never routed
to the current SpO2 UI/database.

Android app for reading a Pulse Series One / WS01A wearable without the vendor
cloud. The app was designed from the GATT profile observed on the user's device:

- Heart Rate Service `180D`, measurement `2A37`
- Pulse Oximeter Service `1822`, continuous measurement `2A5F`
- Battery Service `180F`, level `2A19`
- Running Speed and Cadence Service `1814`, measurement `2A53`
- JStyle-compatible service `FFF0`, write `FFF6`, notify `FFF7`

Version `0.1.1` is intentionally read-only. It subscribes to `FFF7` for protocol
observation but contains no code path that writes to `FFF6`.

## What works in the MVP

- Finds devices whose advertised name starts with `PULSE ONE`
- Connects with Android Bluetooth LE APIs
- Displays and records live heart rate
- Displays SpO2 when the firmware emits standard PLX notifications
- Reads and records battery level
- Displays running cadence when the firmware emits RSC notifications
- Validates 16-byte JStyle notifications and shows the command identifier
- Stores measurements in private SQLite storage
- Shows the latest 60 saved/live heart-rate points
- Exports all measurements to CSV through Android's system file picker

The app has **no Internet permission**, no analytics, no account, and no SDKs.
Android backup is disabled so health trends are not silently copied to cloud
backup. CSV export is always initiated by the user.

## Requirements

- Android Studio compatible with Android Gradle Plugin 8.13.2
- JDK 17
- Android SDK 36 / Build Tools 36.0.0
- Gradle 8.13
- Android device with Bluetooth LE (tested target: Android 16)

## Open and build

1. Install the current stable Android Studio.
2. Open this `PulseOneLocal` folder as a project.
3. When prompted, install Android SDK 36 and Build Tools 36.0.0.
4. In **Settings > Build, Execution, Deployment > Build Tools > Gradle**, select
   the wrapper configuration and Gradle JDK 17.
5. Connect the Redmi Note 13 Pro+ by USB and enable **Developer options > USB debugging**.
6. Select the phone in Android Studio and press **Run**.

To create an installable debug APK, use **Build > Build APK(s)**. The output is
normally `app/build/outputs/apk/debug/app-debug.apk`.

The included Gradle 8.13 wrapper JAR is checked against the SHA-256 published by
Gradle, and the Gradle distribution ZIP is also checksum-pinned.

## First device test

1. Force-stop nRF Connect so it does not hold the only BLE connection.
2. Wear and wake the PULSE ONE.
3. Open Pulse One Local and accept the **Nearby devices** permission.
4. Tap **ค้นหาและเชื่อมต่อ**.
5. Confirm heart rate appears within 60 seconds.
6. Walk for one minute to check cadence.
7. Leave the app connected for several minutes to see whether the firmware emits
   standard SpO2 notifications.
8. Tap **ส่งออก CSV** and inspect the file.

Do not pair the band with two apps at the same time. If connection fails, fully
close nRF Connect/JCVitalPro and retry; do not factory-reset the wearable.

## Data model

SQLite table `measurements` stores:

| Column | Meaning |
| --- | --- |
| `recorded_at` | Unix epoch milliseconds |
| `type` | `heart_rate`, `spo2`, `battery`, or `cadence` |
| `value` | Numeric measurement |
| `unit` | `bpm`, `percent`, or `spm` |
| `source` | `bluetooth_standard` in the MVP |

High-frequency notifications are persisted at most once every five seconds per
measurement type. Live values can refresh faster than this.

## Safety boundaries

- No proprietary command writes in v0.1.1
- No factory reset, firmware update, name change, or sampling-rate changes
- No diagnosis, alerts, or medical recommendations
- SpO2 is shown only when the wearable supplies a parsable value
- Data collection stops when the app/activity is closed

See [docs/TEST_PLAN.md](docs/TEST_PLAN.md) and
[docs/PROTOCOL_NOTES.md](docs/PROTOCOL_NOTES.md) before extending the app.

## Known limitations

- Sleep, HRV, temperature, and non-SpO2 history still require validated protocol
  captures. Auto SpO2 history is diagnostic-only and is not persisted.
- The MVP does not run a foreground service, so it does not continuously collect
  while closed.
- The pure-Java protocol tests, Gradle test task, Android lint, and debug APK
  assembly pass in the current project environment. Hardware behavior still
  requires validation against a physical WS01A.

Health readings are for wellness trends only and are not a substitute for a
validated medical device or professional care.
