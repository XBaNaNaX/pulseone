# Pulse One Local v0.2.7 — History Capacity / Draining

## สิ่งที่แก้

- ยืนยันจาก source ว่า v0.2.6 จำกัดไว้ 512 records และจัด capacity exhaustion
  ผิดเป็น malformed/FAILED
- v0.2.7 ใช้ `maxHistoryRecords` แบบกำหนดค่าได้ ค่า default 4096 records และ
  hard upper bound 65,536 records
- packet ที่ข้าม limit จะรับเท่าที่เหลือและนับ `droppedByLimit` แยกจาก malformed
- เมื่อเต็มจะเข้า `DRAINING`, ไม่ parse/store packet ถัดไป, ไม่ส่ง continuation
  หรือ stop command และยังรอ `66-FF` จนถึง bounded timeout 20 วินาที
- ปิด raw history RX log โดย default และสรุป `ignoredPackets` ตอนปิด session
- Record ID ใช้สำหรับ identity/deduplication เท่านั้น; ถ้ามี UI ประวัติในอนาคต
  ต้องเรียงตาม timestamp

## Log ที่ขอจาก hardware รอบถัดไป

ส่งเฉพาะบรรทัดที่ขึ้นต้นด้วย:

```text
EXT-DIAG VERSION
EXT-DIAG HISTORY-START
EXT-DIAG HISTORY-LIMIT
EXT-DIAG HISTORY-DRAINING
EXT-DIAG HISTORY-COMPLETED
EXT-DIAG HISTORY-TRUNCATED
EXT-DIAG HISTORY-TIMEOUT
EXT-DIAG FAILED
```

ยังห้ามสรุปว่า hardware completion ผ่านจนกว่าจะทดสอบกับ WS01A จริง
