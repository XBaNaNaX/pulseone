# Pulse One Local v0.2.6 — Auto SpO₂ History Fix

## สรุปการเปลี่ยนแปลง

- อ่าน Record ID ของ `0x66` จาก byte 1–2 แบบ little-endian
- รองรับ 24 records ใน notification ขนาด 240 bytes และ fragment ที่ต่อกันตรง record boundary
- ส่ง history-start `0x66/0x00` เพียงครั้งเดียวต่อ session และไม่ส่ง continuation `0x66/0x02`
- deduplicate ด้วย Record ID ร่วมกับ BCD timestamp และจำกัดไม่เกิน 512 records ต่อ session
- จำกัดไม่เกิน 64 notifications และ 20 วินาทีต่อ session เพื่อหยุด duplicate flood
- แยก Auto SpO₂ history ออกจาก current/live SpO₂ และไม่บันทึกลงฐานข้อมูล measurements
- ลด raw history log โดยค่าเริ่มต้น เหลือ batch summary, timestamp range, duplicate/malformed count และ terminal state
- `0x41` ยังไม่มี layout ที่ยืนยัน จึงไม่มีการส่งคำสั่งหรือตีความเวลาอุปกรณ์

## Diagnostic ที่ต้องส่งกลับ

ทดสอบบนอุปกรณ์จริงหนึ่งรอบ แล้วคัดลอกเฉพาะบรรทัดตั้งแต่ `EXT-DIAG START` ถึง
`EXT-DIAG COMPLETED` หรือ `EXT-DIAG FAILED` โดยต้องมีบรรทัดต่อไปนี้ถ้าปรากฏ:

```text
EXT-DIAG VERSION ...
EXT-DIAG AUTO-CONFIG ...
EXT-DIAG DEVICE-TIME RAW RX ... layout-unconfirmed
EXT-DIAG HISTORY-START ...
EXT-DIAG HISTORY-RECORDS count=... new=... duplicates=... malformed=... first=id:...,time:...,SpO2:... last=...
EXT-DIAG HISTORY-FRAGMENT buffered=...
EXT-DIAG HISTORY-COMPLETED ...
EXT-DIAG HISTORY-TIMEOUT ...
EXT-DIAG FAILED ...
```

ไม่ต้องส่ง raw `0x66` ทั้ง 240 bytes เว้นแต่มี build ที่เปิด debug flag โดยเฉพาะ
และห้ามสรุปว่าผ่าน hardware test จนกว่าจะได้ทดสอบกับ WS01A จริง

Pulse One Local ใช้ติดตามสุขภาพทั่วไป ไม่ใช่เครื่องมือวินิจฉัยหรือรักษาโรค
