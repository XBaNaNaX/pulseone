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

## v0.2.6 confirmed Auto SpO2 history framing

Physical-device captures confirmed that command `0x66` streams zero or more
10-byte records after one mode `0x00` request. A notification can contain 24
aligned records (240 bytes), and the device can stream further notifications
without a continuation request. Version 0.2.6 therefore does not generate or
allowlist mode `0x02`.

Each aligned record is:

```text
66-IDlo-IDhi-YY-MM-DD-hh-mm-ss-SpO2
```

The record ID is unsigned little-endian. Date/time fields are BCD and SpO2 is
an unsigned percentage validated in the existing `1..100` range. The parser
never scans forward for another `0x66` after a bad boundary. It can buffer a
partial record across notifications, rejects invalid aligned records safely,
deduplicates by record ID plus timestamp, and caps one session at 512 records.
It also caps a session at 64 notifications and 20 seconds, so repeated duplicate
notifications cannot keep the session alive indefinitely.
`66-FF` is treated as completion only when it arrives as the confirmed standalone
two-byte packet during an active history session.

Command `0x41` appears in compatibility references, but this repository has no
confirmed WS01A request frame or response layout. Version 0.2.6 sends no `0x41`
command and does not decode or set device time. An unsolicited `0x41` notification,
if observed, is labelled `EXT-DIAG DEVICE-TIME RAW RX ... layout-unconfirmed`.
TODO: capture the exact request, response bytes, write callback ordering and a
host timestamp before adding device-clock decoding or drift calculation.

## v0.2.7 history capacity and terminal handling

The v0.2.6 collector had a confirmed local limit of 512 records. When a valid
24-record notification arrived at count 504, it accepted eight records
internally and then returned a failed empty batch for record nine. The Android
state machine consequently misreported local capacity as a malformed payload
and failed the diagnostic while the WS01A continued streaming notifications.

Version 0.2.7 makes `maxHistoryRecords` configurable with a default of 4096 and
a hard upper bound of 65,536. A packet crossing the configured limit is still
fully parsed and validated: remaining slots are accepted, valid overflow records
are counted as `droppedByLimit`, and malformed records retain their separate
count. The session then enters `DRAINING`, stores no more history records, and
only watches for the standalone `66-FF` completion marker until the existing
20-second absolute timeout. Notifications remain subscribed and no stop or
continuation command is generated. Ignored packets are counted and reported once
at terminal completion/timeout; raw history RX logging remains off by default.

Record ID remains identity/deduplication metadata only. Captures show that a
higher ID can have an older BCD timestamp, so chronological presentation must
sort by timestamp and must not infer recency from Record ID. Auto SpO2 history
is still diagnostic-only in this version, so there is no history UI to reorder.

## v0.2.8 completion semantics

A physical WS01A run delivered 50 aligned notifications containing 1,200 valid
records, with no duplicates, malformed records, buffered fragment, capacity
truncation, or standalone `66-FF`. The device then remained idle. This confirms
that a clean four-second inter-packet idle after valid data can terminate a sync,
but it does not prove that all device history was returned.

The collector now distinguishes first-response timeout (4 seconds), inter-packet
idle (4 seconds, reset after each accepted payload), and an absolute session
timeout (20 seconds). These values are bounded and configurable. A clean idle
after records yields `COMPLETED / IDLE_INFERRED / UNVERIFIED`; `66-FF` yields
`COMPLETED / EXPLICIT_MARKER / VERIFIED`; no first response yields
`FAILED / EMPTY_RESPONSE_TIMEOUT`; a pending fragment yields
`INCOMPLETE / STALLED_PARTIAL`; and capacity remains `TRUNCATED / RECORD_LIMIT`.
The absolute timer prevents a continuously active stream from running forever.

Record IDs observed as 0–1199 are session/source metadata, not proven permanent
identities. In-session deduplication remains `recordId + timestamp`. Any future
cross-session persistence must deduplicate using timestamp, SpO2, and other
confirmed measurement fields—not record ID alone. A future history UI must sort
by timestamp. Valid low historical readings such as 81% are retained as history
and must not update the current/live value or trigger a current alert.

Command `0x16` remains unconfirmed. Notifications with that command after a
history terminal event do not reopen or alter the history session and are not
given any new interpretation in v0.2.8.
