# Báo Cáo Đối Chiếu Thị Giác: Bản Phác Thảo 2D vs Giao Diện Thực Tế Đang Chạy

## 📸 1. HÌNH ẢNH THỰC TẾ CHỤP TRỰC TIẾP TỪ ỨNG DỤNG ĐANG CHẠY

Dưới đây là các ảnh chụp màn hình thực tế được ghi nhận trực tiếp từ ứng dụng qua kịch bản kiểm thử tự động độc lập Playwright (chuẩn độ phân giải điện thoại Xiaomi / Pixel: $412 \times 915$):

| Bản Vẽ Phác Thảo Kỹ Thuật 2D (Concept Blueprint) | Giao Diện Tab Duyệt Web Thực Tế (Hiện Có) |
| :---: | :---: |
| ![Bản vẽ 2D](/C:/Users/HPZBook/.gemini/antigravity/brain/bd57cfbc-2b98-48fe-997a-99e347ce01fe/web_monitoring_concept_1789794295213.jpg) | ![Giao diện Thực tế Web Tab](/C:/Users/HPZBook/.gemini/antigravity/brain/bd57cfbc-2b98-48fe-997a-99e347ce01fe/screen4_actual_web_tab.png) |

| Màn Hình PIN Bảo Mật Phụ Huynh (Thực Tế) | Màn Hình Parent Hub Quản Lý (Thực Tế) |
| :---: | :---: |
| ![Màn hình PIN](/C:/Users/HPZBook/.gemini/antigravity/brain/bd57cfbc-2b98-48fe-997a-99e347ce01fe/actual_parent_pin_screen.png) | ![Parent Hub](/C:/Users/HPZBook/.gemini/antigravity/brain/bd57cfbc-2b98-48fe-997a-99e347ce01fe/screen2_parent_hub.png) |

---

## 🔍 2. BẢNG PHÂN TÍCH ĐỐI CHIẾU CHI TIẾT (GAP ANALYSIS)

Nhìn thẳng vào sự thật theo tinh thần **Rule 2 (Zero-Guesswork)** và **Rule 7 (2D Design-to-Code Pipeline)**, giao diện đã code hiện tại còn tồn tại **5 sai lệch rất lớn** so với Bản phác thảo 2D:

| Thành Phần UI | Bản Phác Thảo Kỹ Thuật 2D | Giao Diện Đang Chạy Thực Tế | Đánh Giá & Nguyên Nhân |
| :--- | :--- | :--- | :---: |
| **Bố Cục Toàn Màn Hình (Form Factor)** | **Màn hình ứng dụng độc lập** (Standalone Screen), chiếm trọn tầm nhìn điện thoại, có Status bar và không gian thoáng đãng. | Bị nhét vào trong **Modal Popup cuộn dọc** (`#childDashboardModal`) của trang web ghép đôi, không gian bị co cụm chật hẹp. | ❌ **Sai lệch hình thái**: Chưa tách thành trang Dashboard riêng biệt. |
| **Bố Cục Danh Sách Lịch Sử Web** | **LƯỚI 2 CỘT SQUIRCLE (2-Column Grid)** cân xứng, hiển thị 6-8 trang web trên cùng màn hình mà không cần cuộn nhiều. | **Danh sách tuyến tính 1 cột dọc (Single Column List)** kiểu cũ, mỗi trang web chiếm trọn 1 hàng ngang to đùng. | ❌ **Sai lệch nghiêm trọng**: Code đang dùng `flex-direction: column` thay vì `grid-template-columns: repeat(2, 1fr)`. |
| **Biểu Tượng Nhận Diện (Favicon vs Browser Icon)** | Hiển thị **Favicon/Logo của chính trang web đó** (StackOverflow cam, Khan Academy xanh lá, NatGeo viền vàng, Reddit cam...). | Toàn bộ đều dùng **Logo của Trình duyệt** (vòng tròn Chrome, Edge, Firefox). Vào 10 web Chrome thì 10 logo giống hệt nhau! | ❌ **Thiếu trải nghiệm thị giác**: Chưa kéo Favicon thật của domain về hiển thị. |
| **Thẻ Phiên Đang Mở (Live Active Session)** | Có **Avatar học sinh** (`Liam Chen - Grade 9B`), chip `🟢 LIVE`, thời lượng phiên `04:57`, thanh tiến trình xanh ngọc, nhãn `Academic`. | Chỉ là một **Khung chữ nhật đơn điệu** ghi tên tab Chrome và tiêu đề bài viết Wikipedia thô sơ, không có thanh thời lượng. | ❌ **Thiếu tính năng sân khấu**: Chưa có thanh tiến trình và thông tin học sinh. |
| **Điều Hướng & Phân Loại** | Có nhãn danh mục trên từng thẻ (`Academic`, `Leisure`, `Restricted`), có **Bottom Navigation Bar** 5 nút chuẩn mobile. | Chỉ có một badge đơn điệu `🟢 An toàn` hoặc `⛔ Đã chặn`, không có Bottom Navigation. | ❌ **Thiếu phân tầng dữ liệu**. |

---

## 💡 3. TRẢ LỜI CÂU HỎI: TẠI SAO BẢN PHÁC THẢO ĐẸP MÀ ỨNG DỤNG LẠI VIẾT CHƯA ĐÚNG?

Nguyên nhân gốc rễ gồm 3 yếu tố cốt lõi:

1. **Tư duy "Tiện tay nhét thêm" vào Modal cũ**:
   - Thay vì dựng một màn hình Dashboard độc lập đúng tỷ lệ như bản phác thảo 2D, lập trình viên lại tận dụng ngay cái Hộp thoại Popup con (`#childDashboardModal` trên Web và `dialog_child_companion.xml` trên Android) rồi chèn thêm một Tab thứ 4 vào. 
   - Không gian modal bị giới hạn chiều cao nên đã "lười" không code lưới 2 cột mà dùng danh sách 1 cột dọc đơn giản cho nhanh.

2. **Chưa triển khai Dynamic Favicon Fetcher**:
   - Khi bóc tách URL từ Android Accessibility Service, hệ thống mới chỉ lấy được chuỗi URL (`https://stackoverflow.com/...`) và tên gói trình duyệt (`com.android.chrome`).
   - Phía hiển thị chỉ map `browserPkg` sang icon trình duyệt mà chưa dùng API Favicon (ví dụ: `https://www.google.com/s2/favicons?domain=...&sz=64`) để tải logo thật của website, dẫn đến giao diện đơn điệu toàn icon Chrome.

3. **Phía App Android Native (`android-app`) chưa được cập nhật Layout Tab Web**:
   - Phiên bản APK v1.1.9 mới chỉ nâng cấp dịch vụ ngầm (`GuardianAccessibilityService.kt` và `UsageTrackerService.kt`) để gửi dữ liệu về máy chủ.
   - Trong khi đó, giao diện phụ huynh trên điện thoại (`dialog_child_companion.xml`) vẫn chỉ có 3 tab cũ (`Mạng xã hội`, `Học tập`, `Game`) và hoàn toàn chưa có Tab Duyệt Web!

---

## 🚀 4. KẾ HOẠCH HÀNH ĐỘNG NÂNG CẤP NGAY (ACTION PLAN TO 100% MATCH)

Để đưa giao diện thực tế chuẩn khớp $100\%$ với Bản phác thảo 2D Blueprint, chúng ta sẽ thực hiện 3 bước kỹ thuật dứt khoát:

### Bước 1: Tái cấu trúc Web Portal Tab "🌐 Duyệt Web" sang Lưới 2 Cột Squircle
1. Chuyển đổi container danh sách sang CSS Grid:
   ```css
   .web-history-grid {
     display: grid;
     grid-template-columns: repeat(2, 1fr);
     gap: 10px;
   }
   ```
2. Tích hợp dynamic Favicon tự động theo tên miền:
   ```html
   <img src="https://www.google.com/s2/favicons?domain=${item.domain}&sz=64" class="web-favicon" />
   ```
3. Bổ sung Avatar học sinh (`Grade 9B`), chip `🟢 LIVE` phát sáng viền neon, thanh tiến trình xanh ngọc và bộ đếm thời lượng `04:57`.
4. Gán nhãn phân loại thông minh: `Academic` (Học tập), `Leisure` (Giải trí), `Restricted` (Bị hạn chế/Đã chặn).

### Bước 2: Nâng cấp Giao diện Android Native (`dialog_child_companion.xml` & `MainActivity.kt`)
1. Thêm tab thứ 4 `🌐 Duyệt Web` vào thanh segmented tab trong Android app.
2. Thiết kế layout thẻ con dạng Grid/Card Squircle với đầy đủ favicon và badge trạng thái.

### Bước 3: Kiểm thử Tự Động Playwright Đo Đạc Độ Trùng Khớp
1. Chụp lại ảnh nghiệm thu sau khi refactor.
2. So sánh tỷ lệ tương đồng hình học và bố cục đạt $\ge 95\%$ trước khi đóng gói APK mới.
