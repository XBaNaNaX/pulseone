# Pulse One Local v0.2.5 — Read-only Extended SpO₂ Diagnostic

แพตช์นี้ใช้ตรวจว่า WS01A มีการตั้งค่าหรือประวัติ Auto SpO₂ หรือไม่ โดยส่งเฉพาะคำสั่งอ่านที่อยู่ใน allowlist แอปจะไม่แก้การตั้งค่า ไม่ลบข้อมูล ไม่รีเซ็ตอุปกรณ์ ไม่ทำ OTA และไม่ส่งข้อมูลสุขภาพออกจากโทรศัพท์

## ไฟล์ที่ต้องวางทับ

คัดลอกไฟล์ต่อไปนี้จากโฟลเดอร์ `PulseOne-v0.2.5` ไปวางทับในโปรเจกต์เดิม โดยรักษา directory structure:

1. `app/build.gradle`
2. `app/src/main/java/com/unclebanana/pulseone/ble/PulseBleManager.java`
3. `core/src/main/java/com/unclebanana/pulseone/core/JStyleFrame.java`
4. `tools/ProtocolSelfTest.java`
5. `tools/verify_project.py`
6. `PATCH_0.2.5_TH.md`

## Sync และ Run ใน Android Studio

1. ปิดแอป Pulse One Local ที่กำลังทำงาน แล้วสำรองโปรเจกต์เดิมหากต้องการย้อนกลับ
2. วางทับไฟล์ทั้งหกรายการด้านบนใน `E:\AI\PulseOne`
3. เปิดโปรเจกต์ใน Android Studio แล้วเลือก **File > Sync Project with Gradle Files**
4. ตรวจว่า Build Variant เป็น `debug` จากนั้นเลือก Redmi Note 13 Pro+ 5G แล้วกด **Run**
5. ตรวจหน้า App info ว่าเป็นเวอร์ชัน `0.2.5` แล้วเชื่อมต่อ WS01A
6. เริ่มวัด SpO₂ หนึ่งครั้งและรอให้ manual history จบหรือ timeout แอปจะเริ่ม Extended Diagnostic อัตโนมัติเมื่อไม่พบผล manual

ตรวจจาก command line ได้ด้วย:

```text
gradlew test
python tools/verify_project.py
```

## Diagnostic log ที่คาดหวัง

ลำดับหลักควรมีรูปแบบดังนี้ โดย bytes หลัง RX ต้องเก็บแบบ raw และยังไม่ควรตีความ field ที่ไม่ยืนยัน:

```text
HISTORY 60 END resultSeen=false
EXT-DIAG START reason=manual history ended without result
EXT-DIAG VERSION TX result=0 27-00-00-00-00-00-00-00-00-00-00-00-00-00-00-27
WRITE FFF6 status=0 action=EXT-DIAG VERSION TX
EXT-DIAG VERSION RX ...
EXT-DIAG AUTO-CONFIG TX result=0 2B-03-00-00-00-00-00-00-00-00-00-00-00-00-00-2E
WRITE FFF6 status=0 action=EXT-DIAG AUTO-CONFIG TX
EXT-DIAG AUTO-CONFIG RX ...
EXT-DIAG AUTO-HISTORY TX mode=00 result=0 66-00-00-00-00-00-00-00-00-00-00-00-00-00-00-66
WRITE FFF6 status=0 action=EXT-DIAG AUTO-HISTORY TX mode=00
```

ถ้าพบประวัติ Auto SpO₂ จะเห็นเฉพาะใน Diagnostic log และจะไม่ถูกบันทึกเป็นค่าปัจจุบัน:

```text
AUTO-HISTORY SpO2=97% time=2026-08-31 10:25:05 id=1
EXT-DIAG AUTO-HISTORY TX mode=02 request=1 result=0 66-02-00-00-00-00-00-00-00-00-00-00-00-00-00-68
AUTO-HISTORY END records=1
EXT-DIAG COMPLETED
```

ถ้าไม่มีประวัติ:

```text
RX FFF7 2B 66-FF
AUTO-HISTORY END records=0
EXT-DIAG COMPLETED
```

หาก response ผิดรูปแบบ, timeout, disconnect, GATT write ล้มเหลว หรือเกิดข้อผิดพลาด จะจบด้วย:

```text
EXT-DIAG FAILED reason=...
```

## ข้อมูลที่ต้องคัดลอกกลับมา

กด **คัดลอก Diagnostic Log** แล้วส่ง log ครบทุกบรรทัดตั้งแต่:

```text
EXT-DIAG START
```

จนถึงบรรทัดใดบรรทัดหนึ่งต่อไปนี้:

```text
EXT-DIAG COMPLETED
```

หรือ:

```text
EXT-DIAG FAILED
```

อย่าตัดบรรทัด `VERSION RX`, `AUTO-CONFIG RX`, `AUTO-HISTORY`, `RX FFF7`, `WRITE FFF6` หรือบรรทัด timeout ออก เพราะ raw bytes และลำดับ callback ใช้ตรวจพฤติกรรมจริงของอุปกรณ์

Pulse One Local ใช้ติดตามสุขภาพทั่วไปเท่านั้น ไม่ใช่เครื่องมือวินิจฉัย ป้องกัน หรือรักษาโรค และไม่ควรใช้แทนคำแนะนำจากบุคลากรทางการแพทย์
