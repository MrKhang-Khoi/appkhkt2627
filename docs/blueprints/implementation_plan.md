# Kế Hoạch Xây Dựng Tính Năng Giám Sát Web Đa Trình Duyệt (Multi-Browser Web Guardian)

## 📋 Mô Tả Mục Tiêu
Xây dựng tính năng **Giám sát Mở Web Thời Gian Thực & Lịch Sử Truy Cập Đa Trình Duyệt** cho hệ sinh thái CVA-SmartGuardian.
Tính năng cho phép phụ huynh theo dõi con đang xem trang web nào (URL, Domain, Tiêu đề trang) và trên trình duyệt nào (Google Chrome, Cốc Cốc, Microsoft Edge, Samsung Internet, Mozilla Firefox, Opera, Brave, Xiaomi Mi Browser...), đồng thời lưu lại nhật ký các trang web đã truy cập và trạng thái An Toàn / Bị Chặn.

Toàn bộ quá trình triển khai tuân thủ nghiêm ngặt **Quy chuẩn Kỹ thuật Android (Rule 6)** và **Quy chuẩn Ràng buộc Cứng 2D Design-to-Code Pipeline (Rule 7)**.

---

## 🎨 BẢN VẼ PHÁC THẢO KỸ THUẬT 2D (GATE 1 BLUEPRINT GATEKEEPER)

Trước khi viết bất kỳ dòng mã nào, bản vẽ phác thảo kỹ thuật 2D đã được thiết lập định hình tỷ lệ, biểu tượng và bố cục:

![Bản vẽ Phác thảo Giám sát Web 2D](/C:/Users/HPZBook/.gemini/antigravity/brain/bd57cfbc-2b98-48fe-997a-99e347ce01fe/web_monitoring_concept_1789794295213.jpg)

### Design Tokens đã chuẩn hóa:
1. **Khối Web Đang Mở (Live Web Session Capsule)**:
   - Thẻ Squircle bo góc `12px` với viền phát quang Cyan/Neon Emerald.
   - Nhận diện Icon trình duyệt gốc: Chrome (Vàng/Đỏ/Xanh), Cốc Cốc (Xanh lá), Edge (Xanh gradient), Firefox (Cam lửa), Samsung Internet (Xanh tím), Mi Browser (Cam Xiaomi).
   - Hiển thị: Tiêu đề trang, Tên miền (`domain`), URL đầy đủ, Thời gian bắt đầu mở và nhãn phân loại (Học tập, Tra cứu, Bị hạn chế).
2. **Thanh Pill Tab Mở Rộng 4 Cột Cân Đối**:
   - `display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px;`
   - Bổ sung Tab thứ 4: **🌐 Duyệt Web** bên cạnh `Mạng Xã Hội`, `Học Tập`, `Trò Chơi`.
3. **Danh Sách Nhật Ký Web (Browsing History List)**:
   - Thẻ Squircle `border-radius: 10px - 14px`, hiển thị Favicon/SVG domain, huy hiệu trình duyệt đã dùng, thời điểm truy cập, và badge trạng thái (🟢 An toàn / 🔴 Đã chặn).

---

## ⚙️ NGHIÊN CỨU TÀI LIỆU KỸ THUẬT ANDROID (GATE 2 DETERMINISTIC LOGIC)

### 1. Cơ chế bóc tách URL Đa Trình Duyệt qua `AccessibilityService`:
Học sinh có thể sử dụng nhiều trình duyệt khác nhau để tìm kiếm thông tin hoặc né tránh bộ lọc. Vì Android không có API hệ thống chung để lấy URL từ các ứng dụng bên thứ ba, giải pháp chuẩn công nghiệp là sử dụng **Accessibility Service** với cờ `canRetrieveWindowContent="true"`:

```
[Accessibility Event: TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOW_CONTENT_CHANGED]
                                ⬇️
               [Kiểm tra Gói ứng dụng thuộc BROWSER_PACKAGES]
   (Chrome, Cốc Cốc, Samsung, Firefox, Edge, Opera, Brave, Mi Browser, DuckDuckGo)
                                ⬇️
       [Duyệt cây AccessibilityNodeInfo theo Resource ID & Heuristics]
   • Chrome / Cốc Cốc / Edge / Brave: url_bar, search_box_text, location_bar
   • Samsung Internet: location_bar_edit_text
   • Firefox: url_bar_title, mozac_browser_toolbar_url_view
   • Opera: url_field
   • Mi Browser: url_bar, address_bar
   • Heuristic Fallback: Tìm EditText/TextView có text chứa tên miền hoặc "http"
                                ⬇️
                   [Bộ Lọc Debounce & Làm Sạch (Sanitization)]
   • Bỏ qua chuỗi gõ phím dở dang (< 1.5s)
   • Bỏ qua chuỗi mặc định ("Tìm kiếm hoặc nhập URL...")
   • Bóc tách: Domain, Path, Title, Timestamp, Browser Info
                                ⬇️
                      [Bảo Vệ Quyền Riêng Tư (Privacy Guard)]
   • Tự động che giấu tham số URL nhạy cảm (tokens, passwords)
                                ⬇️
            [Đồng Bộ Hai Kênh Lên Firebase Realtime Database]
   1. /families/{code}/devices/{id}/web_activity.json (Trực tiếp thời gian thực)
   2. /families/{code}/devices/{id}/web_history/{timestamp}.json (Lịch sử đã truy cập)
```

---

## 📂 CHI TIẾT CÁC THAY ĐỔI DỰ KIẾN (PROPOSED CHANGES)

### 1. Phía Android App (`appkhkt2627/android-app`)

#### [MODIFY] [`GuardianAccessibilityService.kt`](file:///C:/Users/HPZBook/Desktop/appkhkt2627/android-app/app/src/main/java/vn/edu/cva/smartguardian/service/GuardianAccessibilityService.kt)
- Bổ sung danh sách đầy đủ tất cả các trình duyệt phổ biến tại Việt Nam vào `BROWSER_PACKAGES`:
  - Thêm Xiaomi Mi Browser (`com.mi.globalbrowser`), Opera Mini, Vivaldi, Kiwi Browser, DuckDuckGo.
- Tối ưu thuật toán đệ quy `findUrlFromNodeHierarchy`:
  - Nhận diện chính xác Resource ID của từng trình duyệt cụ thể.
  - Bộ nhận diện thông minh (Regex Domain matcher) phòng khi trình duyệt cập nhật layout.
  - Lấy Tiêu đề trang web (`title`) từ AccessibilityNodeInfo.
- Tích hợp gọi `UsageTrackerService.reportWebActivity(...)` khi phát hiện học sinh tải trang web mới (có debounce 1.5s tránh ghi đè liên tục khi đang gõ phím).

#### [MODIFY] [`UsageTrackerService.kt`](file:///C:/Users/HPZBook/Desktop/appkhkt2627/android-app/app/src/main/java/vn/edu/cva/smartguardian/service/UsageTrackerService.kt)
- Thêm hàm `reportWebActivity(context, browserPkg, browserName, url, title, isBlocked)`:
  - Tách `domain` từ URL (`youtube.com`, `wikipedia.org`, `azota.vn`...).
  - Ghi lên Firebase Realtime Database:
    - Kênh 1: `/families/{pairedCode}/devices/{deviceId}/web_activity.json` (Trực tiếp trang đang xem).
    - Kênh 2: `/families/{pairedCode}/devices/{deviceId}/web_history/{timestamp}.json` (Nhật ký duyệt web).
    - Giới hạn tự động dọn dẹp nhật ký cũ để không làm nặng database.

#### [MODIFY] [`build.gradle.kts`](file:///C:/Users/HPZBook/Desktop/appkhkt2627/android-app/app/build.gradle.kts)
- Tăng phiên bản ứng dụng:
  - `versionCode = 19`
  - `versionName = "1.1.9"`
- Build Release APK có chữ ký số Keystore: `app-release.apk`.

---

### 2. Phía Bảng Điều Khiển Phụ Huynh (`PM ALL` và `appkhkt2627/index.html`)

#### [MODIFY] [`index.html`](file:///c:/Users/HPZBook/Desktop/PM%20ALL/index.html)
- **Bố cục Pill Bar**: Chuyển đổi thành 4 cột cân xứng:
  - `Mạng Xã Hội` | `Học Tập` | `Trò Chơi` | `🌐 Duyệt Web`.
- **Thẻ Web Đang Mở Thời Gian Thực (Live Web Session)**:
  - Hiển thị tên trang web, tên miền, badge trình duyệt (`Google Chrome`, `Cốc Cốc`...), thời gian mở.
- **Bảng Nhật Ký Lịch Sử Duyệt Web (Web Browsing History)**:
  - Liệt kê các website vừa xem với icon SVG trình duyệt, nhãn an toàn/cảnh báo, thời gian xem chi tiết.
- **Module JavaScript**:
  - Viết hàm `renderWebBrowsingBreakdown(deviceData)` chuẩn ngữ nghĩa HTML5, an toàn XSS (Alibaba OCR).
  - Tích hợp bộ lắng nghe Firebase thời gian thực cho `/web_activity` và `/web_history`.

---

## 🧪 KẾ HOẠCH KIỂM THỬ (VERIFICATION PLAN)

### 1. Kiểm Thử Cú Pháp & Kiểm Định Mã Nguồn (Alibaba OCR):
- Chạy `@alibaba-group/open-code-review v1.12.5` trên cả hai repo:
  - Android: Null-safety, Structured Concurrency (`syncScope`), quản lý tài nguyên.
  - Web: 0 `innerHTML` không an toàn, strict equality `===`, 0 syntax error.

### 2. Kiểm Thử Đóng Gói Release APK Cho Điện Thoại:
- Chạy `./gradlew assembleRelease` với JDK 21 của Android Studio.
- Kiểm tra file đầu ra: `app/build/outputs/apk/release/app-release.apk`.
- Cập nhật thông tin phiên bản `1.1.9` lên Firebase `/app_release.json` để học sinh có thể cập nhật OTA trực tiếp.

### 3. Kiểm Thử Thị Giác Tự Động (Playwright Visual Regression Test):
- Chạy script Playwright mở `index.html`:
  - Kiểm tra chuyển sang Tab `🌐 Duyệt Web`.
  - Hiển thị đầy đủ Live Card và Lịch sử duyệt web.
  - Đảm bảo **0 lỗi Console F12**, không có bẫy tràn ngang (`scrollWidth === clientWidth`).
  - Chụp ảnh màn hình nghiệm thu thực tế trên mobile ($412 \times 915$).

---

## ❓ CÂU HỎI THẢO LUẬN & XÁC NHẬN (USER REVIEW REQUIRED)

> [!IMPORTANT]
> 1. **Bố cục Tab**: Chúng tôi đề xuất chuyển thanh Pill từ 3 cột sang **4 cột cân xứng** (`Mạng Xã Hội`, `Học Tập`, `Trò Chơi`, `Duyệt Web`). Bạn có đồng ý với bố cục này theo đúng bản phác thảo 2D không?
> 2. **Chế độ Lịch sử Web**: Lịch sử web sẽ lưu tối đa 50 lượt truy cập gần nhất để tối ưu tốc độ tải và dung lượng Firebase.
