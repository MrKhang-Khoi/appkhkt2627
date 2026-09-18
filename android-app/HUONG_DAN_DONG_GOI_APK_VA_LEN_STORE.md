# CẨM NANG HƯỚNG DẪN ĐÓNG GÓI APK & LỘ TRÌNH PHÁT HÀNH STORE
## DỰ ÁN: CVA-SMARTGUARDIAN (HỆ THỐNG ĐỒNG HÀNH SỐ & BẢO VỆ HỌC SINH THCS)

---

## I. TỔNG QUAN DỰ ÁN DI ĐỘNG NATIVE
Dự án được xây dựng theo chuẩn công nghiệp **Android Native (Kotlin, Android SDK 35 - Android 15, Android Jetpack, Clean Architecture)**.

### Cấu Trúc Mã Nguồn Hoàn Chỉnh:
```
khkt-smart-guardian/android-app/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # Khai báo dịch vụ Foreground, Trợ năng, VPN, Device Admin
│   │   ├── java/vn/edu/cva/smartguardian/
│   │   │   ├── data/
│   │   │   │   ├── AppClassifier.kt     # Phân loại hơn 150 ứng dụng (Học tập, Game, MXH)
│   │   │   │   └── WebFilterList.kt     # Động cơ lọc tên miền & từ khóa lừa đảo, cờ bạc, khiêu dâm
│   │   │   ├── service/
│   │   │   │   ├── UsageTrackerService.kt          # Dịch vụ Foreground đo thời gian dùng qua UsageStatsManager
│   │   │   │   ├── GuardianAccessibilityService.kt # Dịch vụ Trợ năng bắt link URL trình duyệt & chặn ngay tức thì
│   │   │   │   └── SafeVpnFilterService.kt         # Tường lửa DNS mức HĐH chặn truy vấn độc hại
│   │   │   ├── receiver/
│   │   │   │   ├── SmartGuardianAdminReceiver.kt   # Chống gỡ cài đặt nếu thiếu mã PIN phụ huynh
│   │   │   │   └── BootReceiver.kt                 # Tự chạy ngầm ngay khi máy khởi động lại
│   │   │   └── ui/
│   │   │       ├── MainActivity.kt      # Màn hình phụ huynh thiết lập 4 quyền & xem thời gian
│   │   │       └── BlockedActivity.kt   # Màn hình cảnh báo sư phạm khi bị chặn + nhập PIN mở khóa
│   │   └── res/
│   │       ├── layout/
│   │       │   ├── activity_main.xml    # Bố cục giao diện thiết lập phụ huynh
│   │       │   └── activity_blocked.xml # Bố cục giao diện cảnh báo vi phạm
│   │       ├── values/
│   │       │   ├── colors.xml           # Bảng màu Dark Mode chuẩn hiện đại
│   │       │   ├── strings.xml          # Chuỗi văn bản tiếng Việt chuẩn mực
│   │       │   └── themes.xml           # Cấu hình Material3
│   │       └── xml/
│   │           ├── accessibility_service_config.xml # Cấu hình lắng nghe sự kiện cửa sổ trình duyệt
│   │           └── device_admin_policies.xml        # Chính sách quản trị viên thiết bị
│   ├── build.gradle.kts                 # Cấu hình biên dịch Gradle module
│   └── proguard-rules.pro               # Tối ưu hóa & bảo vệ mã nguồn khi xuất xưởng
├── gradle/
│   ├── libs.versions.toml               # Quản lý phiên bản thư viện tập trung (Version Catalog)
│   └── wrapper/
│       └── gradle-wrapper.properties    # Gradle 8.9 Wrapper
├── build.gradle.kts                     # Root build script
├── settings.gradle.kts                  # Cấu hình dự án
└── local.properties                     # Đường dẫn Android SDK
```

---

## II. QUY TRÌNH ĐÓNG GÓI FILE APK BẰNG ANDROID STUDIO

Môi trường **Android Studio 2024.3.2** và **Java 21** đã có sẵn trên máy tính tại đường dẫn:
`C:\Program Files\Android\Android Studio\bin\studio64.exe`

### Bước 1: Mở dự án trong Android Studio
1. Mở ứng dụng **Android Studio** từ Start Menu hoặc chạy `C:\Program Files\Android\Android Studio\bin\studio64.exe`.
2. Tại màn hình chào mừng, chọn **Open**.
3. Duyệt đến thư mục:
   `c:\Users\HPZBook\Desktop\PM ALL\khkt-smart-guardian\android-app`
4. Bấm **OK**. Android Studio sẽ tự động mở dự án và kích hoạt tiến trình đồng bộ Gradle (`Gradle Sync`).

### Bước 2: Xuất file APK Debug để cài đặt thử nghiệm ngay
1. Trên thanh menu trên cùng của Android Studio, chọn:
   **Build** $\rightarrow$ **Build Bundle(s) / APK(s)** $\rightarrow$ **Build APK(s)**.
2. Đợi thanh trạng thái Gradle ở góc dưới bên phải chạy xong (khoảng 30 giây - 1 phút).
3. Khi hoàn tất, một thông báo nhỏ hiện lên ở góc dưới bên phải:
   `APK(s) generated successfully for module 'app' with 1 build variant: locate`.
4. Nhấp vào chữ **locate**, thư mục chứa file APK sẽ tự động mở ra:
   `android-app\app\build\outputs\apk\debug\app-debug.apk`.

### Bước 3: Xuất file APK Release có chữ ký số (Signed APK)
Khi muốn phân phối file APK chính thức cho phụ huynh cài đặt ổn định:
1. Vào menu **Build** $\rightarrow$ **Generate Signed Bundle / APK...**
2. Chọn ô **APK** $\rightarrow$ bấm **Next**.
3. Tại mục **Key store path**:
   - Nếu chưa có khóa, bấm **Create new...** để tạo mới (đặt mật khẩu dễ nhớ, ví dụ: `cva2025`).
   - Điền các thông tin cơ bản (Tổ chức: CVA School, Tên: SmartGuardian).
4. Chọn biến thể `release`.
5. Đánh dấu tích vào ô `V1 (Jar Signature)` và `V2 (Full APK Signature)`.
6. Bấm **Finish**. File APK phát hành sẽ nằm tại:
   `android-app\app\release\app-release.apk`.

---

## III. HƯỚNG DẪN CÀI ĐẶT & THIẾT LẬP TRÊN ĐIỆN THOẠI HỌC SINH

### 1. Chép file APK vào điện thoại
- **Cách 1**: Cắm cáp USB nối điện thoại với máy tính $\rightarrow$ Chép file `app-debug.apk` vào thư mục `Download` của điện thoại.
- **Cách 2**: Gửi file APK qua Zalo (gửi dạng File) hoặc tải lên Google Drive rồi dùng điện thoại tải về.

### 2. Cài đặt file APK
1. Trên điện thoại học sinh, mở trình quản lý tệp (Files / File Manager) $\rightarrow$ Tìm file `app-debug.apk` và nhấn để cài đặt.
2. Nếu máy hiện cảnh báo: *"Bảo mật: Cho phép cài đặt ứng dụng từ nguồn này?"*, bật gạt công tắc sang **Cho phép (Allow)**.
3. Bấm **Cài đặt (Install)** $\rightarrow$ Bấm **Mở (Open)**.

### 3. Phụ huynh thực hiện cấp 4 quyền cốt lõi (Chỉ làm 1 lần duy nhất khi cài)
Khi mở ứng dụng **CVA-SmartGuardian**, giao diện thiết lập trực quan xuất hiện:
1. **Mục 1: Quyền Đo lường dữ liệu sử dụng (Usage Access)**:
   - Nhấn nút **Cấp Quyền** $\rightarrow$ Hệ thống chuyển sang màn hình Cài đặt của Android.
   - Tìm dòng chữ **CVA-SmartGuardian** $\rightarrow$ Bật công tắc sang **Cho phép truy cập dữ liệu sử dụng**.
2. **Mục 2: Quyền Trợ năng (Accessibility Service)**:
   - Nhấn nút **Cấp Quyền** $\rightarrow$ Hệ thống chuyển sang màn hình Trợ năng.
   - Tìm mục **Ứng dụng đã tải xuống (Downloaded Apps)** $\rightarrow$ Chọn **CVA-SmartGuardian** $\rightarrow$ Bật **Bật dịch vụ**.
   - *(Dịch vụ này giúp phát hiện đường link web xấu trên Chrome, Cốc Cốc để chặn ngay lập tức)*.
3. **Mục 3: Tường lửa DNS An toàn (VPN)**:
   - Nhấn nút **Bật Lá Chắn** $\rightarrow$ Hộp thoại Android hỏi: *"CVA-SmartGuardian muốn thiết lập kết nối VPN"* $\rightarrow$ Bấm **OK**.
   - *(Lá chắn DNS sẽ tự động lọc sạch các domain lừa đảo và người lớn ngay cả khi học sinh dùng tab ẩn danh)*.
4. **Mục 4: Chống gỡ cài đặt (Device Admin)**:
   - Nhấn nút **Kích Hoạt** $\rightarrow$ HĐH hiển thị cảnh báo kích hoạt người quản trị thiết bị $\rightarrow$ Bấm **Kích hoạt ứng dụng quản trị này**.
   - *(Từ lúc này, nếu học sinh vào phần Cài đặt gỡ app, máy sẽ yêu cầu mật khẩu phụ huynh và hiển thị cảnh báo)*.

### 4. Thiết lập chạy ngầm vĩnh viễn (Chống bị hệ điều hành tắt ngầm)
- **Trên máy Xiaomi / Redmi (MIUI / HyperOS)**:
  - Giữ icon ứng dụng ngoài màn hình $\rightarrow$ Chọn *Thông tin ứng dụng (App Info)*.
  - Bật mục: **Tự khởi chạy (Autostart)**.
  - Mục *Tiết kiệm pin (Battery Saver)*: Chọn **Không giới hạn (No restrictions)**.
- **Trên máy Samsung (OneUI)**:
  - Vào *Cài đặt* $\rightarrow$ *Chăm sóc thiết bị* $\rightarrow$ *Pin* $\rightarrow$ *Giới hạn sử dụng dưới nền* $\rightarrow$ Thêm CVA-SmartGuardian vào mục **Ứng dụng không bao giờ nghỉ (Never sleeping apps)**.
- **Trên máy Oppo / Realme (ColorOS)**:
  - Bật *Khởi động trong nền* và chuyển mức tiêu thụ pin sang *Cho phép hoạt động dưới nền tối đa*.

---

## IV. LỘ TRÌNH CÔNG NGHIỆP PHÁT HÀNH LÊN GOOGLE PLAY STORE (CH PLAY)

Khi phiên bản APK thử nghiệm đã ổn định và đạt giải KHKT, quy trình đưa lên CH Play chính thức gồm các bước sau:

### 1. Đăng ký tài khoản nhà phát triển (Google Play Console)
- Truy cập: `https://play.google.com/console/signup`
- Đăng ký bằng tài khoản Google (Phí thanh toán 1 lần duy nhất: $25).
- Định danh tài khoản (Cá nhân hoặc Trường THCS).

### 2. Tuân thủ chính sách Trẻ em & Gia đình (Google Play Families Policy)
Google Play quản lý ứng dụng cho trẻ em cực kỳ nghiêm ngặt:
1. **Đối tượng mục tiêu (Target Audience)**: Chọn độ tuổi học sinh (13–17 tuổi hoặc Dưới 13 tuổi nếu có giám sát).
2. **Chính sách Quyền Riêng Tư (Privacy Policy URL)**:
   - Bắt buộc có trang web công khai nêu rõ: Ứng dụng chỉ thu thập dữ liệu thời gian sử dụng để báo cáo trực tiếp cho Phụ huynh theo thỏa ước gia đình, hoàn toàn không bán dữ liệu cho bên thứ 3 và không thu thập vị trí GPS trái phép.
3. **Khai báo Quyền Trợ năng (Accessibility API Declaration)**:
   - Google chỉ cho phép 2 trường hợp sử dụng `AccessibilityService`:
     - Hỗ trợ người khuyết tật.
     - **Ứng dụng Kiểm soát Phụ huynh (Parental Control Apps)** $\rightarrow$ Khai báo chính xác danh mục này, đính kèm video mô tả tính năng chặn website bảo vệ học sinh để Google phê duyệt tự động.

### 3. Đóng gói Android App Bundle (.aab)
Từ năm 2021, Google Play không nhận file `.apk` thô khi tải lên mà yêu cầu định dạng `.aab`:
- Trong Android Studio: **Build** $\rightarrow$ **Generate Signed Bundle / APK...** $\rightarrow$ Chọn **Android App Bundle (.aab)**.
- File xuất ra: `app-release.aab`.

### 4. Quy trình Đăng ký & Xét duyệt
1. **Kiểm thử kín (Closed Testing)**: Mời ít nhất 20 phụ huynh / học sinh tham gia thử nghiệm trong 14 ngày (quy định chuẩn của Google Play).
2. **Xét duyệt (Store Review)**: Google kiểm duyệt tự động và phê duyệt trong vòng 2 - 5 ngày làm việc.
3. **Phát hành công khai (Production Release)**: Phụ huynh cả nước có thể tìm kiếm `CVA-SmartGuardian` trên CH Play và cài đặt chỉ với 1 chạm.

---

## V. LỘ TRÌNH KỸ THUẬT PHÁT HÀNH TRÊN APPLE APP STORE (iOS)

Hệ điều hành iOS của Apple có cơ chế bảo mật khép kín, không cho phép can thiệp bằng `Accessibility` như Android. Thay vào đó, Apple cung cấp bộ công cụ chuẩn mực tối tân:

### 1. Nền tảng Apple Screen Time API (iOS 15+)
Phiên bản iOS sẽ được xây dựng bằng **Swift / SwiftUI** tích hợp bộ ba Frameworks chính thức của Apple:
- **`FamilyControls`**: Cơ chế ủy quyền giám hộ của Apple. Phụ huynh cấp quyền bảo vệ thông qua tài khoản iCloud gia đình (Family Sharing) với mã FaceID/TouchID của cha mẹ.
- **`DeviceActivity`**: Lắng nghe và đo lường thời gian sử dụng thiết bị (học tập, mạng xã hội, game) ở mức nhân iOS mà không làm hao tốn pin.
- **`ManagedSettings`**: Cho phép khóa các ứng dụng game khi hết giờ, đặt tấm chắn cảnh báo (`Shield Configuration`), và kích hoạt bộ lọc nội dung Safari/Web mà không cần đọc màn hình của học sinh.

### 2. Tường Lửa Lọc Web trên iOS (`NetworkExtension`)
- Tích hợp `NEDNSProxyProvider` hoặc `NEFilterDataProvider`.
- Mọi truy vấn mạng tới danh sách domain cờ bạc, khiêu dâm, lừa đảo sẽ bị hệ thống iOS chặn ngay ở tầng giao vận mạng mà không làm suy giảm tốc độ lướt web.

### 3. Đăng ký Apple Developer Program
- Chi phí: $99/năm tại `developer.apple.com`.
- Gửi yêu cầu xin cấp quyền đặc biệt: `Family Controls Entitlement` (Apple phê duyệt cho các ứng dụng phục vụ mục đích giáo dục và an toàn trẻ em).
- Đóng gói qua Xcode và xuất bản lên **Apple App Store**.

---

## VI. KẾT LUẬN & ĐIỂM SÁNG TẠO DÀNH CHO BÁO CÁO KHKT
1. **Tính thực tiễn cao**: Đã có sẵn mã nguồn Native Android chạy thật, xuất được file APK cài ngay trên điện thoại học sinh để đo đạc số liệu thực nghiệm.
2. **Hợp chuẩn quốc tế**: Áp dụng đúng các API chính thức của Google (`UsageStatsManager`, `VpnService`) và thiết kế sẵn lộ trình tương thích với Apple Screen Time API.
3. **An toàn & Đạo đức**: Tôn trọng quyền riêng tư của học sinh, không lưu trữ trộm dữ liệu, vận hành trên nguyên tắc "Thỏa ước số Gia đình" và "Thuyết Cú Hích (Nudge Theory)".
