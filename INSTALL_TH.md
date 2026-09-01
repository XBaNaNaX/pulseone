# วิธีสร้างและติดตั้งบน Redmi Note 13 Pro+ 5G

โปรเจกต์นี้ยังเป็น source code เนื่องจากสภาพแวดล้อมที่สร้างไม่มี Android SDK
จึงต้องใช้ Android Studio สร้าง APK หนึ่งครั้งก่อนติดตั้ง

## สิ่งที่ต้องติดตั้งบนคอมพิวเตอร์

1. Android Studio เวอร์ชัน Stable ที่รองรับ Android Gradle Plugin 8.13.2
2. Android SDK Platform 36
3. Android SDK Build Tools 36.0.0
4. Gradle 8.13 และ JDK 17 ซึ่งปกติ Android Studio ดาวน์โหลด/มีมาให้

## เปิด Developer options บน Redmi

1. เปิด **Settings > About phone**
2. แตะ **OS version** หลายครั้งจนเปิด Developer options
3. ไปที่ **Additional settings > Developer options**
4. เปิด **USB debugging**
5. ต่อโทรศัพท์กับคอมพิวเตอร์และกดยอมรับลายนิ้วมือของคอมพิวเตอร์

## สร้างและติดตั้ง

1. แตกไฟล์ `PulseOneLocal.zip`
2. เปิด Android Studio แล้วเลือก **Open**
3. เลือกโฟลเดอร์ `PulseOneLocal`
4. เปิด **Settings > Build, Execution, Deployment > Build Tools > Gradle**
5. เลือกใช้ Gradle จาก `gradle-wrapper.properties` และเลือก **Gradle JDK 17**
6. กด **Sync Project with Gradle Files** และติดตั้ง SDK ที่ Android Studio แนะนำ
7. เลือก Redmi Note 13 Pro+ จากช่องอุปกรณ์ด้านบน
8. กดปุ่ม Run รูปสามเหลี่ยมสีเขียว

Android Studio จะสร้างและติดตั้ง Debug APK ให้โดยอัตโนมัติ

หากต้องการเก็บไฟล์ APK ให้เลือก **Build > Build APK(s)** แล้วเปิดไฟล์:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## ก่อนเชื่อม PULSE ONE

1. บังคับปิด nRF Connect และแอปสุขภาพอื่นที่อาจเชื่อมอุปกรณ์อยู่
2. สวม PULSE ONE ให้เซนเซอร์แนบข้อมือ
3. เปิด Pulse One Local
4. อนุญาต **Nearby devices**
5. กด **ค้นหาและเชื่อมต่อ**

หากไม่พบอุปกรณ์ภายใน 15 วินาที ให้ตรวจว่า nRF Connect ถูกตัดการเชื่อมต่อจริง
แต่ไม่ต้อง Unpair และห้าม Factory reset อุปกรณ์

## ความเป็นส่วนตัว

- แอปไม่มีสิทธิ์ใช้อินเทอร์เน็ต
- ไม่มีบัญชี โฆษณา หรือระบบวิเคราะห์ผู้ใช้
- ฐานข้อมูลอยู่ในพื้นที่ส่วนตัวของแอป
- Android cloud backup ถูกปิด
- การส่งออก CSV เกิดขึ้นเมื่อผู้ใช้กดและเลือกตำแหน่งไฟล์เองเท่านั้น
