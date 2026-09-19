# BẢN PHÁC THẢO THIẾT KẾ KỸ THUẬT GIAO DIỆN (UI/UX CONCEPT BLUEPRINT)

> **Dự án**: CVA SmartGuardian – Nền tảng An toàn số & Cân bằng số cho Học sinh THCS  
> **Giai đoạn**: Phê duyệt Bản vẽ Phác thảo Kiến trúc Giao diện (2D Concept Gatekeeper)  
> **Tiêu chuẩn**: Chuẩn thiết kế Dribbble / Apple iOS Human Interface Guidelines & EdTech 2026  

---

## 1. PHÂN TÍCH THIẾU SÓT VÀ NGUYÊN NHÂN "GIAO DIỆN XẤU"

Sự phản ánh của bạn **hoàn toàn chính xác và chạm đúng điểm yếu cốt tử của quy trình phát triển phần mềm**:
1. **Thiếu sót quy trình (Skipped Blueprint Gate)**:
   - Khi nhảy thẳng vào viết code CSS/HTML mà không vẽ trước bản phác thảo (wireframe & mockup), tư duy lập trình viên bị cuốn vào việc *làm sao cho code chạy được* thay vì *người dùng cảm nhận thế nào*.
2. **Hậu quả trên giao diện thực tế**:
   - **Quá tải nhận thức (Cognitive Clutter)**: Nhồi nhét quá nhiều dòng chú thích phụ dạng chữ dài dòng `(Bấm để xem)`, `(Hôm nay chưa sử dụng)`, `0 ứng dụng` làm vỡ nhịp điệu thị giác.
   - **Thiếu icon thương hiệu chuẩn**: Dùng emoji (`🔴`, `🎵`, `🔵`) tạo cảm giác nghiệp dư, vụn vặt và không đạt chuẩn sản phẩm phần mềm chuyên nghiệp đi thi KHKT.
   - **Khối hộp thô cứng**: Các khung card phẳng, viền xám mờ đục, tỷ lệ padding hẹp khiến bố cục bị co cụm và tù túng.

---

## 2. BẢN VẼ PHÁC THẢO THIẾT KẾ ĐẠT CHUẨN (VISUAL CONCEPT MOCKUP)

Dưới đây là bản vẽ phác thảo giao diện hoàn chỉnh được thiết kế theo tỷ lệ chuẩn di động và màn hình phụ huynh:

![Bản Phác Thảo Thiết Kế Giao Diện Giám Sát Mạng Xã Hội CVA SmartGuardian](parent_dashboard_redesign_concept_1789790628888.jpg)

---

## 3. ĐẶC TẢ KIẾN TRÚC GIAO DIỆN MỚI (DESIGN SPECIFICATIONS)

### A. Thanh Chọn Nhóm Danh Mục Dạng Viên Thuốc (Pill Selector Bar)
Thay vì các dòng accordion dài dòng, bố cục chuyển thành **Hàng 3 Phím Tab Bo Tròn Hiện Đại**:
- `[ 📚 Học tập ]`: Màu xanh Cyan `#38bdf8`
- `[ 🎮 Game ]`: Màu vàng hổ phách `#fbbf24`
- `[ 💬 Mạng xã hội ]`: Màu đỏ san hô `#f43f5e` (Đang chọn kích hoạt)

### B. Thẻ Ứng Dụng Chuẩn Squircle & Đèn Neon (App Card Anatomy)
Mỗi ứng dụng (YouTube, TikTok, Facebook, Messenger, Zalo) được định nghĩa lại theo chuẩn thiết kế Apple iOS:

```
┌──────────────────────────────────────────────────────────────┐
│  ┌────┐                                                      │
│  │ ▶  │  YouTube                        ┌──────────────┐     │
│  └────┘  48 phút hôm nay                │ 🟢 Online    │     │
│  ═══════════════════════════════░░░░    └──────────────┘     │
└──────────────────────────────────────────────────────────────┘
```

1. **Icon thương hiệu hình khối bo góc (Squircle Icon 42x42px)**:
   - **YouTube**: Nền đỏ cờ `#ff0000`, nút Play tam giác trắng cân đối.
   - **TikTok**: Nền đen sâu `#010101`, nốt nhạc đa sắc Cyan `#00f2fe` & Magenta `#fe2c55`.
   - **Facebook**: Nền xanh lam chuẩn Meta `#1877f2`, chữ `f` sắc nét.
   - **Messenger**: Nền gradient tím - xanh đặc trưng.
   - **Zalo**: Nền xanh dương chuyên dụng `#0068ff`.
2. **Typography phân cấp (Visual Hierarchy)**:
   - **Tên app**: `font-size: 0.95rem; font-weight: 700; color: #ffffff;`
   - **Thời lượng**: `font-size: 0.78rem; font-weight: 600; color: #94a3b8;`
3. **Badge trạng thái Neon (Live Status Capsule)**:
   - Khi đang mở trực tiếp: Viền vi mạch phát sáng `box-shadow: 0 0 10px rgba(16, 185, 129, 0.4); background: rgba(16, 185, 129, 0.15); color: #34d399;` kèm đèn chấm tròn nhấp nháy 1.5s.
   - Khi đã đóng: `⚪ Đã đóng` màu trung tính `#64748b` nhẹ nhàng, không tranh chấp tầm nhìn.
4. **Thanh tiến trình bo tròn hai đầu (Smooth Pill Progress Bar)**:
   - Chiều cao `6px`, nền rãnh `rgba(255, 255, 255, 0.08)`.
   - Thanh phần trăm bo tròn `999px`, chuyển màu Gradient tinh tế theo màu chủ đạo của từng ứng dụng.

---

## 4. BẢNG SO SÁNH TRƯỚC VÀ SAU KHI PHÁC THẢO

| Yếu tố | Giao diện Cũ (Chưa phác thảo) | Giao diện Mới (Theo bản phác thảo phê duyệt) |
| :--- | :--- | :--- |
| **Bố cục chính** | Dòng accordion đen đặc, chèn nhiều text dài | Khối kính mờ (Glassmorphism), thoáng đãng, phân cấp rõ |
| **Biểu tượng App** | Emoji sơ sài (`🔴`, `🎵`, `🔵`) | **Icon Squircle chuẩn vector** của YouTube, TikTok, Facebook |
| **Trạng thái Online** | Text thường dễ lẫn | **Viên nhộng Neon phát sáng viền** chuẩn phong cách Cyber-Safety |
| **Thanh tiến trình** | Vạch mỏng 4px thô | Thanh pill 6px gradient bóng bẩy, bo tròn mượt mà |
| **Trải nghiệm UX** | Phải đọc nhiều chữ chú thích | Nhìn lướt qua trong 0.5s là nắm trọn thời lượng của con |

---

## 5. LỘ TRÌNH TRIỂN KHAI MÃ NGUỒN

1. **Cập nhật CSS Glassmorphism & Squircle Icons** vào `PM ALL/index.html` và `appkhkt2627/index.html`.
2. **Thay thế logic render icon emoji** bằng các biểu tượng vector SVG sắc nét chuẩn nhận diện thương hiệu quốc tế.
3. **Kiểm duyệt Alibaba OCR** (0 XSS, 0 syntax error).
4. **Đóng gói bản cài đặt APK mới** đồng bộ hoàn toàn với giao diện này.
