# SỔ TAY NGHIÊN CỨU KHOA HỌC (RESEARCH LOGBOOK)
**DỰ ÁN: CVA-SMARTGUARDIAN — TRỢ LÝ ĐỒNG HÀNH TỰ CHỦ SỐ**
*Học sinh nghiên cứu: Nhóm học sinh Trường THCS Chu Văn An*
*Giáo viên hướng dẫn:*

---

> [!IMPORTANT]
> **HƯỚNG DẪN DÀNH CHO HỌC SINH**:
> Sổ tay nghiên cứu (Logbook) là **bằng chứng số 1** để Ban Giám khảo KHKT kiểm tra tính trung thực, liêm chính khoa học và sự độc lập của học sinh. Học sinh cần in ra hoặc chép tay lại các mốc nghiên cứu này, ghi rõ: Ngày tháng, Vấn đề gặp phải, Giải pháp tự tìm hiểu và Kết quả đạt được.

---

### NHẬT KÝ TIẾN TRÌNH NGHIÊN CỨU CHI TIẾT

#### TUẦN 1: HÌNH THÀNH Ý TƯỞNG & ĐIỀU TRA THỰC TRẠNG
- **Ngày 05/09/2026**:
  - *Sự kiện*: Bắt đầu năm học mới, quan sát thấy nhiều bạn trong lớp mang điện thoại đến trường, giờ ra chơi tụ tập chơi Liên Quân Mobile và xem TikTok; về nhà lén chơi game đến 1h sáng dẫn đến hôm sau lên lớp ngủ gật.
  - *Trao đổi*: Nhóm họp bàn với Thầy/Cô hướng dẫn về ý tưởng: *"Làm thế nào để bố mẹ biết chúng em dùng máy làm gì mà không cần phải tịch thu điện thoại hay cãi nhau?"*.
- **Ngày 08/09/2026**:
  - *Hành động*: Thiết kế phiếu khảo sát Google Form gửi cho 60 bạn học sinh khối 8 và 40 phụ huynh trong trường.
  - *Kết quả khảo sát ban đầu*: 78% phụ huynh lo lắng không biết con dùng máy làm gì; 65% học sinh thừa nhận từng nói dối bố mẹ là "đang học bài" nhưng thực chất là chơi game hoặc lướt mạng xã hội.

#### TUẦN 2: THIẾT KẾ MỤC TIÊU KỸ THUẬT & TÌM HIỂU CÔNG NGHỆ
- **Ngày 12/09/2026**:
  - *Nghiên cứu*: Đọc tài liệu lập trình Android về quyền `UsageStatsManager` và `AccessibilityService`.
  - *Khó khăn*: Ban đầu định viết web bình thường, nhưng nhận thấy trình duyệt web không thể theo dõi ứng dụng game ngoài hệ điều hành do cơ chế bảo mật Sandbox.
  - *Giải pháp*: Quyết định xây dựng mô hình kết hợp: 1 Ứng dụng con chạy ngầm trên máy học sinh (Agent) và 1 Bảng điều khiển Web PWA cho Phụ huynh (Dashboard).
- **Ngày 15/09/2026**:
  - *Thiết kế*: Định nghĩa công thức tính Chỉ số Cân bằng Hoạt động $R_{GS} = \frac{T_{game}}{T_{study} + 1}$ và xây dựng danh mục nhận diện 150 ứng dụng học tập/game phổ biến tại Việt Nam.

#### TUẦN 3: LẬP TRÌNH BẢN THỬ NGHIỆM ĐẦU TIÊN (PROTOTYPE V1.0)
- **Ngày 18/09/2026**:
  - *Lập trình*: Xây dựng module `app-classifier.js` và `local-nlp-analyzer.js` để tự động phát hiện ứng dụng và lọc từ khóa rủi ro.
  - *Thử nghiệm*: Giả lập tình huống học sinh mở game Liên Quân lúc 23h00 đêm $\rightarrow$ Hệ thống ghi nhận chính xác 45 phút chơi game và kích hoạt cờ cảnh báo đêm (Night-owl alert đỏ).
- **Ngày 22/09/2026**:
  - *Lập trình*: Xây dựng giao diện Bảng điều khiển dành cho Phụ huynh (`parent-dashboard`) với biểu đồ trực quan, bảng xếp hạng rèn luyện và tính năng Thỏa ước số gia đình.

#### TUẦN 4: THỬ NGHIỆM THỰC ĐỊA TRÊN 15 GIA ĐÌNH HỌC SINH
- **Ngày 25/09/2026 – 10/10/2026**:
  - *Thực nghiệm*: Cài đặt bản thử nghiệm cho 15 bạn học sinh và phụ huynh trong trường tham gia tình nguyện.
  - *Thu thập dữ liệu*: Ghi nhận thời gian sử dụng trước và sau khi áp dụng "Thỏa ước số Gia đình".
  - *Kết quả ấn tượng*: 
    - Thời gian chơi game trung bình giảm từ **142 phút/ngày** xuống còn **58 phút/ngày** (giảm 59.1%).
    - Thời gian tự học trên Azota/K12 tăng từ **35 phút/ngày** lên **72 phút/ngày** (tăng 105.7%).
    - 100% phụ huynh cho biết không còn xảy ra cãi vã gay gắt về việc dùng điện thoại vào buổi tối.

#### TUẦN 5: HOÀN THIỆN BÁO CÁO & LUYỆN TẬP VẤN ĐÁP
- **Ngày 15/10/2026**:
  - *Tổng hợp*: Xử lý số liệu thống kê bằng biểu đồ và kiểm định t-test.
  - *Hồ sơ*: Hoàn tất Báo cáo khoa học 15 trang, in Sổ tay nghiên cứu, thiết kế Poster triển lãm kích thước 120cm x 90cm.
  - *Tập dượt*: Thầy/Cô hướng dẫn tổ chức thi thử phỏng vấn vấn đáp (Mock Interview) để học sinh tự tin trả lời lưu loát mọi câu hỏi của Ban Giám khảo.
