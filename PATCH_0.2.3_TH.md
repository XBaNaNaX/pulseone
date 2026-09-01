# Pulse One Local v0.2.3 Controlled Measurement

แพตช์นี้อัปเดตเฉพาะไฟล์ที่เกี่ยวข้อง ไม่ต้องสร้างโครงการใหม่

## ไฟล์ที่ต้องคัดลอกทับ

คัดลอกไฟล์จากแพตช์ไปยัง `E:\AI\PulseOne` โดยรักษาโครงสร้างเดิม:

1. `app/build.gradle`
2. `app/src/main/java/com/unclebanana/pulseone/MainActivity.java`
3. `app/src/main/java/com/unclebanana/pulseone/ble/PulseBleManager.java`
4. `core/src/main/java/com/unclebanana/pulseone/core/BleParsers.java`
5. `core/src/main/java/com/unclebanana/pulseone/core/JStyleFrame.java`

แนะนำให้คัดลอก `tools/ProtocolSelfTest.java` และ `tools/verify_project.py` ด้วย
เพื่อให้ชุดทดสอบตรงกับเวอร์ชันใหม่ ส่วน `docs/PROTOCOL_NOTES.md` เป็นเอกสารอ้างอิง

## Build และติดตั้ง

1. กด **Sync Project with Gradle Files**
2. กด **Build > Make Project**
3. เลือกโทรศัพท์ Xiaomi และ configuration `app`
4. กด **Run** เพื่อติดตั้งทับแอปเดิม ข้อมูลที่บันทึกไว้จะยังอยู่
5. ตัดการเชื่อมต่อและเชื่อมต่อ Pulse One ใหม่หนึ่งครั้ง

## ทดสอบ SpO2 ครั้งแรก

1. ใส่อุปกรณ์ให้แนบข้อมือ นั่งนิ่ง และวางแขนบนโต๊ะ
2. รอจนปุ่มเปลี่ยนเป็น **เริ่มวัด SpO₂ (30 วินาที)**
3. กดหนึ่งครั้งและอยู่นิ่งจนครบ 30 วินาที
4. หากต้องการยกเลิก กด **หยุดวัด SpO₂**
5. หลังจบ กด **คัดลอก Diagnostic Log** และเก็บผลไว้

ผลที่คาดหวัง:

```text
FOUND FFF6 Vendor write
TX FFF6 START result=0 28-03-01-00-1E-00-00-00-00-00-00-00-00-00-00-4A
WRITE FFF6 status=0
RX FFF7 16B 28-03-...
VENDOR SpO2=98%
TX FFF6 STOP result=0 28-03-00-00-FF-FF-00-00-00-00-00-00-00-00-00-29
```

อุปกรณ์อาจส่งค่าทาง `RX 2A5F` แทน `FFF7`; แอปรองรับทั้งสองทาง

## Guardrails

- เขียนได้เฉพาะ Start SpO2 30 วินาทีและ Stop SpO2 สอง frame เท่านั้น
- ไม่มีหน้าส่ง Hex และไม่มีคำสั่ง reset, ลบข้อมูล, เปลี่ยนชื่อ หรือ firmware
- หยุดอัตโนมัติหลัง 32 วินาที (30 วินาที + grace period)
- หากไม่มีผลลัพธ์ อย่ากดเริ่มซ้ำต่อเนื่อง ให้ส่ง Diagnostic Log กลับมาวิเคราะห์

ข้อมูลจากอุปกรณ์ใช้ติดตามสุขภาพทั่วไป ไม่ใช่เพื่อวินิจฉัยหรือรักษาโรค
