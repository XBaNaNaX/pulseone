# Pulse One Local v0.2.4 — อ่านผล SpO₂ หลังจบรอบ

## เหตุผลของการแก้ไข

ผล Diagnostic จาก WS01A ยืนยันว่า packet `28-03` มีรูปแบบดังนี้:

- byte 2 = อัตราการเต้นหัวใจ
- byte 3 = SpO₂
- byte 4–7 = ข้อมูลประกอบของอัลกอริทึม

ดังนั้นห้ามนำ byte 2 มาแสดงเป็น SpO₂ แม้ค่าจะอยู่ระหว่าง 70–100 ก็ตาม
ในรอบทดสอบ byte 3 ยังคงเป็นศูนย์จนได้รับ `28-FF` ซึ่งหมายถึงอุปกรณ์จบการวัด

v0.2.4 จึงส่งคำสั่งอ่านอย่างเดียว `0x60` หลังจบรอบ เพื่อขอผล SpO₂ แบบวัดเอง
ที่ firmware อาจบันทึกไว้แยกจาก packet ระหว่างวัด

## ไฟล์ที่ต้องแทนที่

1. `app/build.gradle`
2. `app/src/main/java/com/unclebanana/pulseone/ble/PulseBleManager.java`
3. `core/src/main/java/com/unclebanana/pulseone/core/JStyleFrame.java`
4. `tools/ProtocolSelfTest.java`
5. `tools/verify_project.py`

จากนั้นกด **Sync Project with Gradle Files**, ถอน v0.2.3 ออกจากโทรศัพท์ แล้วกด **Run**

## ผล Diagnostic ที่คาดหวัง

เมื่ออุปกรณ์ส่ง `28-FF` แอปควรแสดง:

```text
MEASUREMENT FINISHED 28-FF; request manual SpO2 history
TX FFF6 READ-HISTORY-60 result=0 60-00-00-00-00-00-00-00-00-00-00-00-00-00-00-60
WRITE FFF6 status=0
```

ถ้ามีผลที่บันทึกไว้ จะตามด้วย packet 10 bytes และข้อความ:

```text
RX FFF7 10B 60-...
HISTORY 60 SpO2=97%
```

ถ้าได้รับ `60-FF` หรือ timeout หมายถึง firmware ไม่ได้บันทึกผล SpO₂ ในรอบนั้น

## ขอบเขตความปลอดภัย

- เพิ่มเฉพาะคำสั่งอ่านประวัติ `60-00-...-60`
- ไม่ใช้โหมดลบข้อมูล `0x99`
- ไม่ส่งคำสั่ง reset, เปลี่ยนชื่อ, ตั้งค่า firmware หรือคำสั่ง vendor อื่น
- ไม่ตีความค่าหัวใจใน byte 2 เป็น SpO₂

