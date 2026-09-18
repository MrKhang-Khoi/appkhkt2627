# ĐỀ CƯƠNG NGHIÊN CỨU KHOA HỌC KỸ THUẬT (KHKT)
**DÀNH CHO HỌC SINH TRUNG HỌC CƠ SỞ (CẤP THCS)**
*Theo Quy chuẩn Thông tư số 06/2024/TT-BGDĐT của Bộ Giáo dục và Đào tạo*

---

## 1. TÊN ĐỀ TÀI
**"NGHIÊN CỨU, PHÁT TRIỂN HỆ SINH THÁI ỨNG DỤNG DI ĐỘNG KẾT HỢP NỀN TẢNG WEB HỖ TRỢ PHỤ HUYNH GIÁM SÁT THÔNG MINH VÀ RÈN LUYỆN NĂNG LỰC TỰ CHỦ KHÔNG GIAN MẠNG CHO HỌC SINH THCS"**

- **Lĩnh vực nghiên cứu chính**: Phần mềm hệ thống (Systems Software) & Công nghệ thông tin trong Giáo dục.
- **Đơn vị thực hiện**: Trường THCS Chu Văn An.
- **Đối tượng nghiên cứu**: Hành vi sử dụng điện thoại thông minh, thói quen chơi game, lướt mạng xã hội và khả năng tự chủ số của học sinh lứa tuổi 11 – 15 tuổi (Lớp 6 đến Lớp 9).

---

## 2. LÝ DO CHỌN ĐỀ TÀI (ĐẶT VẤN ĐỀ)

### 2.1. Bối cảnh thực tiễn tại trường THCS
1. **Sự bùng nổ của thiết bị thông minh học đường**:
   - Sau giai đoạn học tập trực tuyến và chuyển đổi số, hơn 85% học sinh THCS tại các đô thị và thị xã được phụ huynh trang bị hoặc cho phép sử dụng điện thoại thông minh để tra cứu bài học, nộp bài qua Azota, K12Online, tham gia nhóm lớp.
2. **Hệ lụy tiêu cực về sức khỏe và học tập**:
   - Tình trạng học sinh "học trực tuyến giả vờ, lén chơi game thật" diễn ra phổ biến (Liên Quân Mobile, Free Fire, Roblox, lướt TikTok/Reels thâu đêm).
   - Rối loạn giấc ngủ, suy giảm thị lực, sa sút kết quả học tập và gia tăng căng thẳng, xung đột trong gia đình giữa cha mẹ và con cái.
3. **Sự bất lực và lo âu của Phụ huynh học sinh (PHHS)**:
   - Phần lớn phụ huynh bận rộn đi làm, không thể ngồi cạnh con 24/7. Phụ huynh hoàn toàn không biết con mình mở máy ra để học hay để chơi game, lướt web độc hại.
   - Các ứng dụng kiểm soát hiện có trên thị trường (như Google Family Link, Qustodio) thường có giao diện phức tạp, thu phí đắt đỏ, chặn máy quá thô bạo khiến học sinh ức chế tìm cách phá khóa hoặc phản kháng tiêu cực.

### 2.2. Tính mới và ý nghĩa khoa học của đề tài
- Dự án giải quyết bài toán quản trị theo triết lý **"Minh bạch & Đồng hành"**:
  - Dựa trên **sự đồng thuận tự nguyện của Phụ huynh và Học sinh (Informed Consent)** theo đúng quy định của Luật Trẻ em 2016 và Nghị định 13/2023/NĐ-CP.
  - Áp dụng **Tâm lý học hành vi (Thuyết Cú hích - Nudge Theory)** và mô hình **"Thỏa ước số Gia đình" (Digital Contract)**: Thay vì cấm đoán thô bạo, hệ thống giúp con tự đặt ra hạn mức chơi game lành mạnh và nhận phần thưởng khi hoàn thành mục tiêu rèn luyện.
  - Ứng dụng **Trí tuệ nhân tạo biên (On-device Edge AI)** để phân loại ứng dụng và cảnh báo từ khóa nguy cơ cục bộ, tuyệt đối bảo vệ quyền riêng tư.

---

## 3. MỤC TIÊU NGHIÊN CỨU

1. **Mục tiêu kỹ thuật (Engineering Goals)**:
   - Xây dựng thành công ứng dụng nền (Mobile Agent) chạy ngầm siêu nhẹ, tiêu thụ < 2% pin, ghi nhận chính xác đến từng phút thời gian sử dụng theo 4 nhóm: Học tập, Game, Mạng xã hội, Tiện ích.
   - Phát triển bộ phân tích từ khóa nguy cơ an toàn mạng chạy 100% cục bộ trên máy con.
   - Xây dựng Bảng điều khiển Web PWA trực quan dành cho Phụ huynh, hiển thị biểu đồ thời gian thực và gửi báo cáo tóm tắt mỗi tối.
   - Thiết lập cơ chế Chống gỡ cài đặt (Anti-Tamper) bằng quyền Quản trị thiết bị (`DeviceAdminReceiver`).
2. **Mục tiêu giáo dục & xã hội (Social & Educational Goals)**:
   - Giúp giảm trung bình $\ge 40\%$ thời gian chơi game và lướt mạng vô bổ của học sinh tham gia thử nghiệm.
   - Triệt tiêu 100% tình trạng thức khuya dùng điện thoại sau 22h30.
   - Cải thiện mối quan hệ tin cậy, giảm xung đột giữa cha mẹ và con cái trong việc sử dụng công nghệ.

---

## 4. CÂU HỎI NGHIÊN CỨU VÀ GIẢ THUYẾT KHOA HỌC

- **Câu hỏi nghiên cứu 1**: *Làm thế nào để đo lường chính xác và tự động phân loại hoạt động của học sinh trên điện thoại mà không xâm phạm nội dung tin nhắn riêng tư?*
- **Câu hỏi nghiên cứu 2**: *Cơ chế Thỏa ước số kết hợp Thuyết Cú hích (Nudge) có thực sự giúp học sinh THCS giảm thời gian chơi game tự giác hơn so với biện pháp tịch thu/cấm đoán truyền thống không?*
- **Giả thuyết khoa học**: *Nếu phụ huynh có công cụ nắm bắt dữ liệu sử dụng khách quan và áp dụng cơ chế Thỏa ước số có thưởng/phạt minh bạch, thì học sinh THCS sẽ tự giác giảm ít nhất 35% thời gian chơi game vô bổ và tăng thời gian tự học có chất lượng.*

---

## 5. PHƯƠNG PHÁP NGHIÊN CỨU

1. **Phương pháp nghiên cứu lý thuyết**:
   - Nghiên cứu tài liệu về tâm lý học lứa tuổi THCS (11-15 tuổi), cơ chế kích thích Dopamine của trò chơi điện tử và thuật toán Nudge.
   - Nghiên cứu kiến trúc hệ điều hành Android (`UsageStatsManager`, `AccessibilityService`, `DevicePolicyManager`).
2. **Phương pháp thiết kế và kỹ thuật phần mềm (Engineering Design Process)**:
   - Khảo sát nhu cầu $\rightarrow$ Thiết lập tiêu chí kỹ thuật $\rightarrow$ Thiết kế kiến trúc $\rightarrow$ Lập trình nguyên mẫu $\rightarrow$ Thử nghiệm và Tinh chỉnh lặp (Iteration).
3. **Phương pháp thực nghiệm xã hội và thống kê toán học**:
   - Triển khai thử nghiệm có đối chứng trên 30 cặp Phụ huynh - Học sinh trường THCS Chu Văn An trong vòng 4 tuần.
   - Thu thập số liệu trước và sau can thiệp (Pre-test & Post-test).
   - Kiểm định độ tin cậy bằng các công cụ thống kê (Trung bình, Độ lệch chuẩn, Kiểm định Paired Student's t-test với mức ý nghĩa $p < 0.05$).

---

## 6. KẾ HOẠCH VÀ TIẾN ĐỘ THỰC HIỆN

| Giai đoạn | Thời gian | Nội dung công việc cụ thể | Sản phẩm dự kiến |
| :--- | :---: | :--- | :--- |
| **Giai đoạn 1** | Tuần 1 - 2 | Khảo sát thực trạng 100 học sinh & phụ huynh; Lập hồ sơ đề cương nghiên cứu. | Báo cáo số liệu khảo sát ban đầu; Đề cương KHKT. |
| **Giai đoạn 2** | Tuần 3 - 5 | Thiết kế kiến trúc hệ thống; Lập trình module Core và Dashboard Phụ huynh. | Bản thảo kiến trúc; Bộ mã nguồn nguyên mẫu (v1.0). |
| **Giai đoạn 3** | Tuần 6 - 8 | Thử nghiệm thực địa trên 30 gia đình; Thu thập dữ liệu sử dụng hàng ngày. | Nhật ký Logbook; Cơ sở dữ liệu đo đạc thực nghiệm. |
| **Giai đoạn 4** | Tuần 9 - 10 | Phân tích số liệu thống kê; Hoàn thiện Báo cáo 15 trang; Dựng Poster trưng bày. | Báo cáo KHKT hoàn chỉnh; Poster khoa học; Video demo. |
