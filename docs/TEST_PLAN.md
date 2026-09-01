# Test plan

## Automated checks

Run from the repository root:

```bash
python3 tools/verify_project.py
```

With a JDK installed:

```bash
mkdir -p build/core-test
javac -encoding UTF-8 -d build/core-test \
  core/src/main/java/com/unclebanana/pulseone/core/BleParsers.java \
  core/src/main/java/com/unclebanana/pulseone/core/JStyleFrame.java \
  tools/ProtocolSelfTest.java
java -cp build/core-test ProtocolSelfTest
```

With Android Studio/SDK installed, also run:

```bash
./gradlew :app:assembleDebug :app:lintDebug
```

## Redmi Note 13 Pro+ / Android 16 checks

### Permission and privacy

- Fresh install requests Nearby devices only.
- Denying permission leaves the app usable and explains how to enable it.
- Android's app details show no Internet, contacts, phone, SMS, or storage permission.
- Export opens the Android system document picker.
- Uninstall removes the private database.

### Connection

- nRF Connect closed: scan finds `PULSE ONE 0234` within 15 seconds.
- nRF Connect connected: app times out safely without resetting/unpairing.
- Bluetooth disabled: Android enable-Bluetooth flow appears.
- Reconnect after disconnect works without force-stopping the app.
- Rotation/resizing does not crash the app.

### Measurements

- HR agrees with nRF Connect `2A37` within the same update window.
- HR graph retains at most 60 points in memory.
- Database stores at most one row per type every five seconds.
- Battery matches nRF Connect `2A19`.
- RSC appears only while the device emits `2A53`.
- Missing PLX data remains `— %`; it must never invent or retain a stale value.
- Invalid/proprietary frames never become health measurements.

### CSV

- Header is `recorded_at_utc,type,value,unit,source`.
- Timestamps are ISO-8601 UTC.
- Values use a dot decimal separator regardless of Thai locale.
- Canceling the picker creates no file and shows no error.

### Endurance

- Keep the screen open and connected for 30 minutes.
- Verify no recurring disconnect loop.
- Check battery impact on phone and band.
- Confirm database count grows at the expected throttled rate.

## Release gate

Do not distribute the APK until `assembleDebug`, `lintDebug`, the protocol self
test, and the physical-device checks for permission, connection, HR, battery and
CSV all pass.
