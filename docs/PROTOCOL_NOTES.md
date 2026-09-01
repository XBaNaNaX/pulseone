# WS01A protocol notes

## Observed GATT profile

The device advertises as `PULSE ONE 0234` and exposes Bluetooth SIG services for
heart rate, pulse oximetry, battery, and running speed/cadence. The user directly
observed a live `2A37` heart-rate value of 72 bpm and body sensor location `Wrist`.

The vendor service is:

| Direction | UUID | Property |
| --- | --- | --- |
| Phone to wearable | `FFF6` | Write / Write Without Response |
| Wearable to phone | `FFF7` | Notify |

An observed `FFF7` frame was:

```text
16-09-01-00-00-00-00-00-00-00-00-00-00-00-00-20
```

It is 16 bytes. The final byte equals the low byte of the sum of bytes 0 through
14: `0x16 + 0x09 + 0x01 = 0x20`. This matches the common JStyle/J2208 framing
rule. Related protocol references identify command `0x16` with VO2MAX control,
but its exact WS01A payload semantics have not been proven.

## v0.2.3 controlled-write policy

`PulseBleManager` subscribes to `FFF7`, validates frames, and exposes the command
identifier for diagnostics. It does not persist raw proprietary frames.

Device captures proved that `2A5F` subscription succeeds but the WS01A does not
emit PLX notifications without an explicit measurement request. Version 0.2.3
therefore permits exactly two `FFF6` frames generated in code:

```text
Start SpO2 for 30 seconds: 28-03-01-00-1E-00-00-00-00-00-00-00-00-00-00-4A
Stop SpO2:                 28-03-00-00-FF-FF-00-00-00-00-00-00-00-00-00-29
```

WS01A diagnostics showed that command `0x28` responses carry heart rate at
byte 2 and SpO2 at byte 3. A completed measurement is marked by `28-FF`.
Version 0.2.4 then issues the read-only manual SpO2 history request below:

```text
Read manual SpO2 history:  60-00-00-00-00-00-00-00-00-00-00-00-00-00-00-60
```

No delete mode (`0x99`) is implemented. Dynamic history records are 10 bytes
and store the SpO2 percentage at record byte 9; `60-FF` marks the end.

The format comes from `BleSDK.healthMeasurementWithDataType`: command `0x28`,
measurement type `0x03` for blood oxygen, byte 2 start/stop, bytes 4-5 duration
in seconds (little endian), followed by the additive checksum. JStyle/2208
responses use command `0x28`, type `0x03`, and byte 3 for the oxygen percentage.

The transport has no arbitrary-hex API. It must continue to deny commands
associated with factory reset (`0x12`), MCU reset (`0x2E`), device name changes
(`0x3D`), history deletion, and firmware-update flows.

Earlier safety prerequisites were:

1. A command allowlist based on at least two independent protocol references.
2. Captures from the original app or a verified compatible open-source client.
3. A parser fixture for every response variant.
4. Timeout and cancellation behavior.
5. A physical-device test that confirms no settings or stored history are changed.

Explicitly deny commands associated with factory reset (`0x12`), MCU reset
(`0x2E`), device name changes (`0x3D`), and firmware-update flows.

## Candidate second-phase reads

Related JStyle/J2208 devices use commands for total activity, detailed activity,
sleep, continuous heart rate, HRV, temperature and automatic SpO2 history. These
are compatibility clues only; they are not yet verified on WS01A.

The safest next phase is a desktop read-only collector that downloads existing
history without changing automatic measurement intervals. Once captured packets
are verified, the same allowlisted requests can be ported to Android.
