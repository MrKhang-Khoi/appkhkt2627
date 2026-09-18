# 🛡️ CVA-SmartGuardian (2026 - 2027)
### HỆ THỐNG GIÁM SÁT THÔNG MINH & ĐỒNG HÀNH TỰ CHỦ SỐ HỌC ĐƯỜNG
**Dự án Nghiên cứu Khoa học Kỹ thuật (KHKT) dành cho Học sinh Trung học**  
*Đơn vị: Trường THPT Chuyên Chu Văn An*  
*Lĩnh vực: Phần mềm hệ thống (Systems Software) & Công nghệ Giáo dục*

---

## 🌐 TRẢI NGHIỆM TRỰC TUYẾN & TẢI ỨNG DỤNG
* **Trang Web Trực Quan & Test Lab 2 Màn Hình (GitHub Pages)**:  
  👉 **[https://mrkhang-khoi.github.io/appkhkt2627/](https://mrkhang-khoi.github.io/appkhkt2627/)**
* **Tải Bản Cài Đặt Android APK Trực Tiếp (v1.0 - 6.5 MB)**:  
  📥 **[Tải CVA-SmartGuardian-v1.0.apk](https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/main/apk/CVA-SmartGuardian-v1.0.apk)**

### 📱 Quét Mã QR Tải Nhanh Trên Điện Thoại:
<p align="center">
  <img src="https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/main/apk/CVA-SmartGuardian-v1.0.apk" alt="QR Code Tải APK" width="200"/>
  <br/>
  <em>(Dùng Zalo, Camera hoặc Trình duyệt để quét mã tải APK trực tiếp)</em>
</p>

---

## 📋 HƯỚNG DẪN CÀI ĐẶT TRÊN ANDROID (ĐẶC BIỆT XIAOMI 15T / ANDROID 14+)
Khi cài đặt file APK trên các dòng máy đời mới (Xiaomi HyperOS, Samsung OneUI, Pixel...):
1. **Tải file APK**: Nhấn link tải phía trên hoặc quét mã QR.
2. **Vượt qua cảnh báo Google Play Protect**:
   * *Cách nhanh (1 click)*: Tại thông báo *"Đã chặn ứng dụng để bảo vệ thiết bị..."*, bấm vào chữ **"Chi tiết khác"** (More details) ➔ Chọn **"Vẫn cài đặt (không an toàn)"** (Install anyway).
   * *Cách tạm tắt quét*: Mở CH Play ➔ Bấm Avatar ➔ Chọn *Play Protect* ➔ Bấm bánh răng ⚙️ ➔ Tắt *"Quét ứng dụng bằng Play Protect"*. Cài đặt xong có thể bật lại.
3. **Kích hoạt 3 quyền bảo vệ**:
   * **Trợ năng (Accessibility Service)**: Giám sát trình duyệt Chrome/Edge để chặn web cờ bạc, nội dung xấu.
   * **Dữ liệu sử dụng (Usage Stats Service)**: Thống kê chính xác thời lượng học tập và chơi game.
   * **Quản trị viên thiết bị (Device Admin)**: Cơ chế chống học sinh tự ý gỡ ứng dụng khi chưa có mật mã phụ huynh.

---

## 🏗️ CẤU TRÚC DỰ ÁN ĐỘC LẬP
```
appkhkt2627/
├── index.html                               # Trang Web Portal chính thức (GitHub Pages)
├── apk/                                     # Thư mục chứa file cài đặt APK chính thức
│   └── CVA-SmartGuardian-v1.0.apk           # File APK v1.0 đã kiểm thử thực tế
├── android-app/                             # DỰ ÁN NATIVE ANDROID STUDIO HOÀN CHỈNH
│   ├── app/src/main/java/                   # Toàn bộ mã nguồn Kotlin (Clean Architecture)
│   │   ├── data/ (AppClassifier, WebFilterList)
│   │   ├── service/ (UsageTracker, Accessibility, SafeVpn)
│   │   ├── receiver/ (DeviceAdmin, BootReceiver)
│   │   └── ui/ (MainActivity, BlockedActivity)
│   ├── app/src/main/res/                    # Tài nguyên giao diện Material3 Dark Mode
│   └── build.gradle.kts                     # Cấu hình build Gradle Android SDK 34/35
├── docs/                                    # 5 BỘ TÀI LIỆU KHOA HỌC CHUẨN KHKT
│   ├── 01_DE_CUONG_NGHIEN_CUU_KHKT.md       # Đề cương NCKH chuẩn TT 06/2024/TT-BGDĐT
│   ├── 02_KIEN_TRUC_KY_THUAT_CHUYEN_SAU.md  # Bản thiết kế kiến trúc 4 tầng chi tiết
│   ├── 03_SO_TAY_NGHIEN_CUU_LOGBOOK.md      # Sổ tay nghiên cứu (Research Logbook)
│   ├── 04_PHIEU_KHAO_SAT_THUC_NGHIEM.md     # Phiếu khảo sát thực nghiệm định lượng
│   └── 05_KE_HOACH_CHUYEN_DOI_XCODE_IOS.md  # Kế hoạch chuyển đổi sang Apple iOS Xcode
├── src/                                     # MÃ NGUỒN NGUYÊN MẪU THỬ NGHIỆM (PROTOTYPE)
│   ├── core/                                # Thuật toán phân loại app, NLP, bộ lọc web
│   ├── mock-agent/                          # Trình giả lập thiết bị học sinh
│   └── parent-dashboard/                    # Web PWA bảng điều khiển phụ huynh
├── test/                                    # HỆ THỐNG TEST TỰ ĐỘNG (PASS 100%)
├── server.js                                # Máy chủ Node.js phục vụ chạy thử nghiệm cục bộ
└── Chay_Demo.bat                            # Script 1-click khởi động toàn bộ hệ thống
```

---

## ⚡ CHẠY THỬ NGHIỆM LOCAL TRÊN MÁY TÍNH
1. **Cách 1**: Nhấp đúp chuột vào file `Chay_Demo.bat` tại thư mục gốc.
2. **Cách 2**: Chạy lệnh Terminal:
   ```bash
   node server.js
   ```
   Truy cập: `http://localhost:8100` để mở Test Lab 2 Màn Hình thời gian thực.

---

## 👨‍💻 TÁC GIẢ & BẢN QUYỀN
* **Đề tài**: *Hệ thống Trợ lý AI Bảo vệ Học đường & Đồng hành Tự chủ Số*
* **Dự thi**: Cuộc thi Nghiên cứu Khoa học Kỹ thuật dành cho Học sinh Trung học năm học 2026 - 2027
* **Bản quyền**: Trường THPT Chuyên Chu Văn An
