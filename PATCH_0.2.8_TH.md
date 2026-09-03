# Pulse One Local v0.2.8 — History Completion Semantics

## สิ่งที่แก้

- แยก first-response timeout 4 วินาที, inter-packet idle 4 วินาที และ absolute
  session timeout 20 วินาที โดยทุกค่ามี validation bounds
- หากมี valid records และ stream เงียบโดยไม่มี fragment ค้าง จะจบเป็น
  `HISTORY-COMPLETED reason=idle-inferred completeness=unverified`
- `66-FF` ยังจบเป็น `reason=explicit-marker completeness=verified`
- ไม่มี response แรกเป็น `HISTORY-FAILED reason=empty-response-timeout`
- fragment ค้างเป็น `HISTORY-INCOMPLETE reason=stalled-partial`
- terminal result emit ได้ครั้งเดียวและยกเลิก timer ทุกตัว
- ปิด raw 240-byte history และ per-packet summary โดย default
- ไม่เปลี่ยนความหมายหรือ parser ของ packet command `0x16`
- Record ID ใช้ deduplicate ภายใน session คู่กับ timestamp เท่านั้น ไม่ถือเป็น
  permanent identity ข้าม session

## Log ที่ขอจาก hardware รอบถัดไป

ส่งเฉพาะบรรทัดที่ขึ้นต้นด้วย:

```text
EXT-DIAG VERSION
EXT-DIAG HISTORY-START
EXT-DIAG HISTORY-COMPLETED
EXT-DIAG HISTORY-INCOMPLETE
EXT-DIAG HISTORY-TRUNCATED
EXT-DIAG HISTORY-FAILED
EXT-DIAG COMPLETED
```

`idle-inferred` มี completeness เป็น `unverified` เสมอ ห้ามสรุปว่าได้ history
ครบทั้งหมดจนกว่าจะได้รับ `66-FF` หรือมีหลักฐาน protocol เพิ่มเติม
