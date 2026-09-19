# Thiết Kế Giao Diện Đồng Nhất 100% (Web & Android M3)

Tài liệu đặc tả kiến trúc giao diện người dùng đồng nhất giữa **Web Hub (Phụ Huynh)** và **Ứng Dụng Android (Thiết bị Học sinh / Phụ huynh)** theo tiêu chuẩn quốc tế Material Design 3 và Apple Human Interface Guidelines (Dark Theme).

---

## 1. Bản Vẽ Thiết Kế Giao Diện Trực Quan (UI Mockup)

![Bản thiết kế giao diện đồng nhất Web & Android M3](C:/Users/HPZBook/.gemini/antigravity/brain/bd57cfbc-2b98-48fe-997a-99e347ce01fe/unified_app_ui_1789732184780.jpg)

---

## 2. Chi Tiết 3 Màn Hình Trọng Tâm

### Màn hình 1: Đăng Nhập Bảo Mật Phụ Huynh (Tab [ 👨‍👩‍👧 Phụ Huynh ] - Khóa PIN)
* **Thanh điều hướng đỉnh (Top Navigation)**:
  * Logo Khiên bảo vệ `🛡️ CVA-SmartGuardian` kèm chấm trạng thái mạng đám mây.
  * Bộ chuyển Tab 2 vai trò chuẩn M3 Segmented Control: `[ 👨‍👩‍👧 Phụ Huynh ]` (Active) và `[ 🎒 Học Sinh ]`.
* **Khung đăng nhập kính mờ (Frosted Glass Card)**:
  * Biểu tượng khóa bảo mật `🔐 Parent Security PIN`.
  * **4 Chấm PIN tròn phát sáng (Golden Glow Dots)**: Phản hồi rung nhẹ haptic feedback mỗi khi bấm số.
  * **Bàn phím số cảm ứng tròn (Touch Numpad)**: Kích thước nút $\ge 60\text{px}$ chuẩn M3, chống chạm nhầm:
    * Hàng 1-3: Các phím số từ `1` đến `9`.
    * Hàng 4: `Clear` (Xóa hết) | `0` | `⌫` (Xóa lùi từng ký tự).
  * Mã PIN mặc định khởi tạo: `1234`.

---

### Màn hình 2: Bảng Quản Lý Phụ Huynh (Tab [ 👨‍👩‍👧 Phụ Huynh ] - Đã Mở Khóa)
* **Khung hiển thị Mã gia đình (Family Pairing Card)**:
  * Nền thẻ mạ viền vàng Neon sang trọng.
  * Hiển thị mã ghép đôi gia đình: **`MÃ GIA ĐÌNH: CVA-8A20`** (kèm nút Sao chép và Đổi mã mới).
* **Thẻ giám sát thiết bị con (Paired Child Status)**:
  * Trạng thái kết nối thời gian thực: Tên thiết bị (ví dụ: `Xiaomi 25069PTEBG`), hệ điều hành `Android 16`, biểu tượng tích xanh an toàn `Ghép Đôi Thành Công`.
* **Nút Ngắt Kết Nối An Toàn (PIN-Protected Destructive Action)**:
  * Nút viền đỏ nổi bật: `🔒 Ngắt Kết Nối`.
  * Khi bấm: Bắt buộc yêu cầu nhập lại mã PIN 4 số của Phụ huynh để tránh việc học sinh tự ý ngắt kết nối.

---

### Màn hình 3: Chế Độ Học Sinh (Tab [ 🎒 Học Sinh ])
* **Trạng thái 3A - Chưa ghép đôi (Onboarding)**:
  * Ô nhập mã gia đình tối giản: `CVA-XXXX` (Tự động viết hoa, giãn cách chữ rộng dễ nhìn).
  * Nút bấm dạng viên thuốc phát sáng: `🔗 KẾT NỐI` (kèm vòng xoay loading khi kiểm tra trên Firebase).
  * Nút nhỏ gọn mở popup: `ℹ️ Hướng dẫn cấp quyền Xiaomi HyperOS / Android 14`.
* **Trạng thái 3B - Đã ghép đôi thành công**:
  * Tối giản câu từ tuyệt đối chuẩn quốc tế theo đúng yêu cầu:
    > **Xin chào ............**  
    > **ĐÃ GHÉP ĐÔI** (Kèm chấm xanh nhấp nháy đồng bộ đám mây)
  * Khi Phụ huynh nhấn "Ngắt kết nối" từ xa, màn hình học sinh lập tức hiện vòng loading đồng bộ và tự động trở về ô nhập mã ban đầu.

---

## 3. Bảng So Sánh Đồng Nhất Giữa Web và Android

| Thành phần giao diện | Trạng thái trên Web (`index.html`) | Trạng thái trên Android (`activity_main.xml`) |
| :--- | :--- | :--- |
| **Thanh 2 Tab Segmented Control** | ✅ Đã có (`#tabBtnParent`, `#tabBtnStudent`) | 🔄 Đang chờ đồng bộ vào Android Layout |
| **Màn hình PIN 4 số & Numpad** | ✅ Đã hoàn thiện (`#cardPinGate`) | 🔄 Đang chờ chuyển đổi thành ViewGroup XML |
| **Bảng Quản lý Phụ huynh** | ✅ Đã hoàn thiện (`#cardParentHub`) | 🔄 Đang chờ đồng bộ vào Android Activity |
| **Giao diện Học sinh tối giản** | ✅ Đã hoàn thiện | ✅ Đã có trong `activity_main.xml` |
| **Tự động cập nhật tức thời** | ✅ Trực tiếp qua Firebase REST | ✅ Đã tích hợp `AppUpdateManager` |
