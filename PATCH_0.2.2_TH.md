# Pulse One Local v0.2.2 Diagnostic — วิธีอัปเดตเฉพาะไฟล์

ปิดแอปบนโทรศัพท์ก่อน แล้วคัดลอกไฟล์จากแพตช์นี้ไปทับไฟล์ชื่อเดียวกันใน
โครงการ `E:\AI\PulseOne` โดยรักษาโครงสร้างโฟลเดอร์เดิม:

1. `app/build.gradle`
2. `app/src/main/java/com/unclebanana/pulseone/MainActivity.java`
3. `app/src/main/java/com/unclebanana/pulseone/ble/PulseBleManager.java`
4. `core/src/main/java/com/unclebanana/pulseone/core/BleParsers.java`

ไฟล์ `tools/ProtocolSelfTest.java` เป็นชุดทดสอบประกอบ ไม่กระทบตัวแอป แต่ควร
คัดลอกทับด้วยเพื่อให้ source test ตรงกับ parser รุ่นใหม่

จาก Android Studio:

1. กด **Sync Project with Gradle Files**
2. เลือกอุปกรณ์ Xiaomi และ configuration `app`
3. กด **Run** เพื่ออัปเดตแอปเดิม ข้อมูลในฐานข้อมูลไม่ถูกลบ
4. กด `ตัดการเชื่อมต่อ` แล้ว `ค้นหาและเชื่อมต่อ` ใหม่
5. รออย่างน้อย 15 วินาทีขณะใส่อุปกรณ์แนบข้อมือ

## ผลที่ต้องดูใน BLE Diagnostic

- `CCCD 2A5F status=0` — เปิด notification สำเร็จ
- `RX 2A5F 5B ...` — ได้รับ packet SpO2 มาตรฐาน
- `PLX parsed ... SpO2=...` — parser อ่านค่าได้
- `PARSE ERROR 2A5F ...` — packet ไม่ตรงรูปแบบ ให้กด `คัดลอก Diagnostic Log`
- มี `CCCD 2A5F status=0` แต่ไม่มี `RX 2A5F` — อุปกรณ์ไม่ส่ง SpO2 เอง อาจต้อง
  ศึกษาคำสั่ง vendor `FFF6` ในเฟสถัดไป

ส่งข้อความที่ได้จากปุ่ม `คัดลอก Diagnostic Log` กลับมาเพื่อวิเคราะห์ต่อได้

## ความปลอดภัย

รุ่นนี้ยังไม่เขียนคำสั่งใดไปยัง `FFF6` และไม่แก้ firmware/การตั้งค่าของอุปกรณ์
ข้อมูลสุขภาพนี้ใช้ติดตามทั่วไป ไม่ใช่เพื่อวินิจฉัยหรือรักษาโรค
