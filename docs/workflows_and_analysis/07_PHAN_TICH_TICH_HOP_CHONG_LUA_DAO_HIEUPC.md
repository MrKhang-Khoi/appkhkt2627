# PHÂN TÍCH CHUYÊN SÂU TÍCH HỢP TÍNH NĂNG CHỐNG LỪA ĐẢO (CHONGLUADAO - HIEUPC)
**Dự án**: CVA-SmartGuardian (KHKT 2026-2027)  
**Tác giả phân tích**: Antigravity AI Pair Programmer & Ban Kỹ Thuật KHKT  
**Lưu trữ tại**: `C:\Users\HPZBook\Desktop\APP KHKT\docs\workflows_and_analysis\07_PHAN_TICH_TICH_HOP_CHONG_LUA_DAO_HIEUPC.md`

---

## I. TỔNG QUAN HỆ SINH THÁI CHỐNG LỪA ĐẢO (CHONGLUADAO - HIEUPC)

Dự án **Chống Lừa Đảo (chongluadao.vn)** do chuyên gia an ninh mạng Ngô Minh Hiếu (Hiếu PC) và các cộng sự sáng lập là một hệ thống phòng thủ phi lợi nhuận nhằm bảo vệ người dùng Việt Nam trước các hiểm họa trực tuyến: lừa đảo tài chính, trang web giả mạo (phishing), mã độc (malware), cờ bạc trực tuyến, và nội dung xấu độc.

### 1. Nguyên lý vận hành cốt lõi của ChongLuaDao
Hệ thống hoạt động dựa trên 3 trụ cột:
1. **Cơ sở dữ liệu tập trung (Crowdsourced + Verified Threat Intelligence)**: Tiếp nhận báo cáo từ cộng đồng, sau đó qua bước thẩm định nghiêm ngặt (Manual Audit + Tự động quét bằng VirusTotal, Google Safe Browsing, heuristic analysis).
2. **Cập nhật theo thời gian thực (Real-time Blacklist/Whitelist Feeds)**: Danh sách đen được liên tục đồng bộ qua API công khai và kho dữ liệu mở trên GitHub.
3. **Phân loại mối nguy theo cấp độ (Risk Classification)**:
   - `malicious`: Tấn công chiếm quyền, phát tán virus/trojan.
   - `phishing`: Giả mạo ngân hàng, cổng thông tin chính phủ, mạng xã hội, ví điện tử.
   - `scam`: Lừa đảo việc làm, đầu tư tài chính ảo, tuyển cộng tác viên Shopee/Lazada.
   - `fake / unrated`: Trang web tin tức giả, thông tin sai lệch chưa được xác minh.

---

## II. LÀM SAO ĐỂ LẤY DANH SÁCH CÁC TRANG ĐEN VỀ ĐỂ CHẶN?

Có 3 phương thức kỹ thuật để lấy dữ liệu từ ChongLuaDao về ứng dụng CVA-SmartGuardian:

### 1. Phương thức 1: Sử dụng ChongLuaDao Public REST API
- **Endpoint kiểm tra đơn lẻ**:
  `GET https://api.chongluadao.vn/v2/check?url={domain_or_url}`
- **Endpoint lấy danh sách cập nhật (Bulk / Delta)**:
  API cung cấp hash hoặc danh sách các domain mới được định danh trong 24h qua.
- **Ưu điểm**: Dữ liệu luôn mới nhất từng phút, do server ChongLuaDao phân tích trực tiếp.
- **Nhược điểm**: Phụ thuộc 100% vào kết nối mạng; nếu học sinh truy cập web khi mạng chập chờn hoặc API giới hạn lượt gọi (Rate Limit), độ trễ tải trang web sẽ tăng cao.

### 2. Phương thức 2: Đồng bộ trực tiếp từ GitHub Repository mở của ChongLuaDao
- ChongLuaDao duy trì kho dữ liệu công khai trên GitHub (ví dụ: `chongluadao/whitelist-blacklist` hoặc các tệp `blacklist.json`, `domains.txt`).
- Cấu trúc tệp chứa hàng chục nghìn tên miền kèm nhãn phân loại:
  ```json
  [
    { "url": "nganhang-techcom-fake.com", "type": "phishing", "subType": "bank" },
    { "url": "nhanquafreefire-garena-lua.vn", "type": "scam", "subType": "game" }
  ]
  ```
- **Ưu điểm**: Hoàn toàn miễn phí, không bị giới hạn API key, có thể tải về dạng tệp nén nhẹ.
- **Nhược điểm**: Độ trễ cập nhật theo commit của nhóm tác giả (thường là vài giờ đến 1 ngày một lần).

### 3. Phương thức 3: Kiến trúc Lai Tối Ưu (Hybrid Edge-Cloud Architecture - KHUYẾN NGHỊ CHO KHKT)
- **Tầng 1 (Local Room Database Mirror)**: Ứng dụng Android duy trì một bảng SQLite/Room DB chứa top các domain lừa đảo phổ biến nhất tại Việt Nam (~30.000 tên miền chỉ tốn ~1.5 MB dung lượng). Việc đối soát URL diễn ra **ngay lập tức trong RAM máy (< 2ms)**, không gây giật lag trình duyệt của học sinh.
- **Tầng 2 (Delta Sync Worker)**: Định kỳ mỗi ngày 1 lần (qua Android `WorkManager` khi thiết bị cắm sạc và có WiFi), ứng dụng tải gói cập nhật chênh lệch (Delta update) từ GitHub/API của ChongLuaDao để nạp thêm các trang web mới xuất hiện.
- **Tầng 3 (Cloud Fallback on Unknown URL)**: Nếu một tên miền có dấu hiệu nghi vấn (tên miền lạ `.xyz`, `.top`, chứa từ khóa `nhanqua`, `dangnhap`) nhưng chưa có trong Local DB, ứng dụng mới gửi một truy vấn nhẹ lên API ChongLuaDao để xác thực.

---

## III. PHƯƠNG ÁN TRIỂN KHAI TRÊN HỆ THỐNG CVA-SMARTGUARDIAN

Để chặn các trang lừa đảo trên thiết bị học sinh mà không can thiệp thô bạo làm đơ máy hay hao pin, chúng ta cần triển khai theo lộ trình kỹ thuật sau:

### 1. Cơ chế bắt URL trên Android (URL Interception Engine)
Hiện tại CVA-SmartGuardian đã có sẵn `GuardianAccessibilityService`. Ta có 2 cách chặn:

* **Phương án A: Chặn qua AccessibilityService (Nhẹ nhàng, không cần quyền VPN)**
  - `AccessibilityService` lắng nghe sự kiện khi học sinh mở các trình duyệt phổ biến (Chrome, Cốc Cốc, Samsung Internet, Firefox, Edge).
  - Đọc trường thanh địa chỉ (`Address Bar` ID: `com.android.chrome:id/url_bar`).
  - Trích xuất tên miền gốc (Domain).
  - So khớp ngay lập tức với Local Blacklist Database:
    - Nếu trùng khớp: Gửi lệnh `GLOBAL_ACTION_BACK` hoặc hiển thị ngay một cửa sổ nổi cảnh báo (`Overlay Alert Dialog`) che toàn màn hình:  
      *⚠️ CẢNH BÁO: TRANG WEB LỪA ĐẢO ĐÃ BỊ CHẶN BỞI CVA-SMARTGUARDIAN THEO DỮ LIỆU CHONGLUADAO!*
    - Gửi gói tin khẩn cấp lên Firebase Realtime Database: `devices/{familyCode}/alerts` để máy Phụ huynh rung chuông báo động ngay lập tức.

* **Phương án B: Chặn qua Local VPN Service (Toàn diện, chặn ở tầng DNS/Packet)**
  - Thiết lập một `VpnService` nội bộ ảo (Loopback VPN trên thiết bị, không cần máy chủ VPN ngoài).
  - Chặn ở tầng DNS: Khi học sinh truy cập `lua-dao-abc.com`, VPN trả về địa chỉ IP cục bộ `127.0.0.1` hoặc dẫn hướng tới trang cảnh báo nội bộ `blocked.html`.
  - **Ưu điểm**: Hoạt động với 100% ứng dụng, kể cả khi bấm link trong Zalo, Facebook, Messenger.
  - **Nhược điểm**: Yêu cầu người dùng bật quyền VPN (có thể gây cản trở trên một số dòng máy Xiaomi/Oppo).

*👉 Đề xuất cho cuộc thi KHKT: Sử dụng **Phương án A (AccessibilityService URL Reader)** kết hợp với cơ chế cảnh báo Overlay. Đây là phương án khả thi nhất, tận dụng triệt để nền tảng trợ năng đã được cấp quyền, không yêu cầu thêm quyền VPN phức tạp.*

### 2. Luồng cảnh báo thời gian thực về Portal Phụ huynh
Khi phát hiện học sinh truy cập trang độc hại:
1. Điện thoại học sinh: Bị chặn đứng ngay trong $\le 50\text{ms}$, màn hình chuyển sang trạng thái cảnh báo an toàn.
2. Thiết bị gửi Telemetry lên Firebase:
   ```json
   {
     "type": "MALICIOUS_URL_BLOCKED",
     "url": "http://scam-vietinbank-otp.com",
     "threatType": "Phishing Ngân Hàng",
     "source": "ChongLuaDao Threat Feed",
     "timestamp": 1789805000000
   }
   ```
3. Web Portal của Phụ huynh:
   - Thẻ trạng thái trên Parent Hub nhấp nháy đỏ báo động.
   - Thêm bản ghi vào tab **"Duyệt Web"** với huy hiệu đỏ rực: `[ĐÃ CHẶN] scam-vietinbank-otp.com (Lừa đảo ngân hàng)`.
   - Lưu trữ nhật ký an toàn để cha mẹ có cơ sở trao đổi, nhắc nhở con em.

---

## IV. KẾ HOẠCH BẢO VỆ ĐỀ TÀI TRƯỚC BAN GIÁM KHẢO KHKT

1. **Tính nhân văn & Xã hội**: Đề tài không chỉ bảo vệ máy móc mà còn giáo dục nhận thức an toàn số cho học sinh trung học trước vấn nạn lừa đảo không gian mạng đang nhức nhối tại Việt Nam.
2. **Tính độc lập & Tự chủ**: Tích hợp dữ liệu từ một tổ chức uy tín hàng đầu trong nước (ChongLuaDao của Hiếu PC) chứng minh tính thực tiễn cao và khả năng kế thừa công nghệ cộng đồng.
3. **Hiệu năng & Bảo mật dữ liệu**: Sử dụng mô hình kiểm duyệt cục bộ trên máy (Edge AI / Local DB), không lưu trữ thông tin nhạy cảm của học sinh lên máy chủ bên ngoài, tuân thủ Nghị định 13/2023/NĐ-CP về bảo vệ dữ liệu cá nhân.
