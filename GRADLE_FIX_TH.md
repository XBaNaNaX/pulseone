# แก้ปัญหา ProjectTypeBinding

ข้อผิดพลาด:

```text
Unable to load class 'org.gradle.features.binding.ProjectTypeBinding'
```

หลักฐานปัจจุบันสอดคล้องกับสาเหตุว่าโปรเจกต์รุ่น `0.1.0` ใช้ Android Gradle Plugin 9.3 ซึ่งต้องทำงานกับ
Gradle 9.5 แต่ ZIP ไม่มี Gradle Wrapper ที่สมบูรณ์ ทำให้ Android Studio เลือก Gradle รุ่นอื่น
จากเครื่องและล้มก่อนเริ่ม compile source code

รุ่น `0.1.1` แก้โดยใช้ชุดที่รองรับ Android 16/API 36 และเข้ากันตรงกัน:

- Android Gradle Plugin 8.13.2
- Gradle 8.13
- JDK 17
- compileSdk/targetSdk 36
- Gradle Wrapper 8.13 ที่ตรวจ checksum จาก release ทางการ

## วิธี Sync ใหม่

1. ปิดโปรเจกต์เดิมใน Android Studio
2. แตก ZIP รุ่น `0.1.1` ลงโฟลเดอร์ใหม่ ห้ามแตกทับ `.gradle` ของรุ่นเดิม
3. เปิดโฟลเดอร์ `PulseOneLocal` จาก ZIP ใหม่
4. ไปที่ **File > Settings > Build, Execution, Deployment > Build Tools > Gradle**
5. เลือก Gradle distribution จาก Wrapper ของโปรเจกต์
6. ตั้ง **Gradle JDK = 17** หรือ `jbr-17`
7. กด **File > Sync Project with Gradle Files**

หาก Android Studio ถามให้ดาวน์โหลด Gradle 8.13 หรือ SDK 36 ให้กดอนุญาต

ไม่จำเป็นต้องลบ cache ทั้ง Android Studio และไม่จำเป็นต้อง kill Java process
ก่อนทดลองชุดเวอร์ชันที่แก้แล้ว เพราะข้อความ error เกิดก่อน compile และสอดคล้องกับ
toolchain mismatch โดยตรง

## สัญญาณว่าการแก้สำเร็จ

หน้าต่าง Build จะผ่านขั้นโหลด Gradle plugin และเริ่ม task เช่น:

```text
:core:compileJava
:app:compileDebugJavaWithJavac
:app:assembleDebug
```

หากยังล้ม ให้ส่งข้อมูล 3 รายการนี้:

1. **Help > About** ของ Android Studio
2. หน้า **Gradle settings** ที่แสดง Gradle JDK
3. Build Output ตั้งแต่บรรทัด `FAILURE: Build failed` ถึง `Caused by` บรรทัดสุดท้าย
