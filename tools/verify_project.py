#!/usr/bin/env python3
"""Dependency-free policy checks that can run without Android SDK."""
from pathlib import Path
import hashlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
BLE = (ROOT / "app/src/main/java/com/unclebanana/pulseone/ble/PulseBleManager.java").read_text(encoding="utf-8")
CORE = ROOT / "core/src/main/java/com/unclebanana/pulseone/core"
ROOT_BUILD = (ROOT / "build.gradle").read_text(encoding="utf-8")
APP_BUILD = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
WRAPPER = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
JSTYLE = (ROOT / "core/src/main/java/com/unclebanana/pulseone/core/JStyleFrame.java").read_text(encoding="utf-8")

errors = []

if "android.permission.INTERNET" in MANIFEST:
    errors.append("Internet permission must not be present")
if 'android:allowBackup="false"' not in MANIFEST:
    errors.append("Android backup must be disabled for private health data")
for permission in ("READ_CONTACTS", "READ_SMS", "READ_PHONE_STATE", "MANAGE_EXTERNAL_STORAGE"):
    if permission in MANIFEST:
        errors.append(f"Forbidden permission present: {permission}")

if "JSTYLE_WRITE = uuid16(0xFFF6)" not in BLE:
    errors.append("FFF6 write characteristic is missing")
if "writeAllowlistedVendor" not in BLE or "isReadOnlyDiagnosticRequest(frame)" not in BLE:
    errors.append("FFF6 writes must pass the fixed vendor allowlist")
if "vendorWritePending" not in BLE or "onCharacteristicWrite" not in BLE:
    errors.append("Vendor GATT writes must be serialized through write callbacks")
if re.search(r"public\s+\w+(?:<[^>]+>)?\s+(?:sendHex|write|sendRaw|writeRaw)\s*\(", BLE):
    errors.append("Arbitrary public vendor write API is forbidden")
if "HEALTH_MEASUREMENT_COMMAND = 0x28" not in JSTYLE or "MEASUREMENT_SPO2 = 0x03" not in JSTYLE:
    errors.append("Fixed SpO2 measurement command is missing")
for dangerous in ("0x12", "0x2E", "0x3D"):
    command_assignment = rf"(?:COMMAND\s*=|frame\[0\]\s*=\s*\(byte\))\s*{dangerous}\b"
    if re.search(command_assignment, JSTYLE, re.IGNORECASE):
        errors.append(f"Dangerous vendor command present in JStyleFrame: {dangerous}")
for forbidden_api in ("factoryReset", "mcuReset", "setDeviceName", "SetPersonalInfo",
                      "SetDeviceInfo", "SetAutomaticMonitoring", "clearDeviceData",
                      "firmwareUpdate"):
    if forbidden_api.lower() in (BLE + JSTYLE).lower():
        errors.append(f"Forbidden vendor operation present: {forbidden_api}")
if re.search(r"\bOTA\b", BLE + JSTYLE, re.IGNORECASE):
    errors.append("Forbidden vendor operation present: OTA")
if "0x99" in BLE or "0x99" in JSTYLE:
    errors.append("History delete mode must not be present in production vendor code")
if "JSTYLE_NOTIFY = uuid16(0xFFF7)" not in BLE:
    errors.append("FFF7 notify characteristic is missing")
if "versionName '0.2.5'" not in APP_BUILD or "versionCode 6" not in APP_BUILD:
    errors.append("App version must be 0.2.5 (code 6)")
if "MANUAL_SPO2_HISTORY_COMMAND = 0x60" not in JSTYLE:
    errors.append("Read-only manual SpO2 history command is missing")
if "manualSpO2HistoryRequest()" not in BLE:
    errors.append("Manual SpO2 history must be requested after measurement")
diagnostic_requirements = {
    "VERSION_READ_COMMAND = 0x27": "Vendor version read command is missing",
    "AUTO_CONFIG_READ_COMMAND = 0x2B": "Auto SpO2 config read command is missing",
    "AUTO_SPO2_HISTORY_COMMAND = 0x66": "Auto SpO2 history read command is missing",
    "HISTORY_READ_START = 0x00": "Auto-history start mode is missing",
    "HISTORY_READ_CONTINUATION = 0x02": "Auto-history continuation mode is missing",
    "isReadOnlyDiagnosticRequest": "Read-only diagnostic allowlist is missing",
}
for marker, message in diagnostic_requirements.items():
    if marker not in JSTYLE:
        errors.append(message)
for request_call in ("versionReadRequest()", "autoSpO2ConfigReadRequest()",
                     "autoSpO2HistoryRequest(JStyleFrame.HISTORY_READ_START)",
                     "autoSpO2HistoryRequest(JStyleFrame.HISTORY_READ_CONTINUATION)"):
    if request_call not in BLE:
        errors.append(f"Read-only command does not flow through BLE state machine: {request_call}")
if "MAX_AUTO_HISTORY_CONTINUATIONS = 10" not in BLE:
    errors.append("Auto-history continuation must be capped at 10")
if "parseAutoSpO2Record" not in BLE or "listener.onSpO2(record" in BLE:
    errors.append("Auto-history must be parsed for diagnostic logging only")
if "version '8.13.2'" not in ROOT_BUILD:
    errors.append("AGP must be pinned to 8.13.2")
if "gradle-8.13-bin.zip" not in WRAPPER:
    errors.append("Gradle distribution must be pinned to 8.13")
if "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78" not in WRAPPER:
    errors.append("Gradle 8.13 distribution checksum is missing")
wrapper_jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
expected_wrapper_sha = "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
if not wrapper_jar.is_file():
    errors.append("Gradle wrapper JAR is missing")
elif hashlib.sha256(wrapper_jar.read_bytes()).hexdigest() != expected_wrapper_sha:
    errors.append("Gradle 8.13 wrapper JAR checksum does not match the official release")
for wrapper_script in (ROOT / "gradlew", ROOT / "gradlew.bat"):
    if not wrapper_script.is_file():
        errors.append(f"Missing Gradle wrapper script: {wrapper_script.name}")

observed = bytes([0x16, 0x09, 0x01] + [0] * 12 + [0x20])
if len(observed) != 16 or sum(observed[:15]) & 0xFF != observed[15]:
    errors.append("Observed protocol fixture checksum is invalid")

required = [
    ROOT / "app/src/main/java/com/unclebanana/pulseone/MainActivity.java",
    ROOT / "app/src/main/java/com/unclebanana/pulseone/data/MeasurementDb.java",
    CORE / "BleParsers.java",
    CORE / "JStyleFrame.java",
]
for path in required:
    if not path.is_file():
        errors.append(f"Missing required file: {path.relative_to(ROOT)}")

for path in (ROOT / "app/src/main").rglob("*.xml"):
    try:
        ET.parse(path)
    except ET.ParseError as error:
        errors.append(f"Invalid XML in {path.relative_to(ROOT)}: {error}")

if errors:
    print("verify_project: FAILED")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("verify_project: all policy and structure checks passed")
