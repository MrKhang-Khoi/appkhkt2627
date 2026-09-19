# Bản Vẽ Phác Thảo Kỹ Thuật 2D Tinh Gọn: Màn Hình Phụ Huynh & Học Sinh Sau Đăng Nhập
## (Alibaba Concept-to-Android D2C Blueprint Sheet - Compact & Tamper-Proof Architecture)

---

## 🎨 1. BẢN VẼ PHÁC THẢO KỸ THUẬT 2D MỚI (TỐI GIẢN CHUẨN APPLE SCREEN TIME & LINEAR)

| MÀN HÌNH 1: PHỤ HUYNH (MINIMALIST SCREEN TIME HUB) | MÀN HÌNH 2: HỌC SINH (STUDENT TAMPER-PROOF) |
| :---: | :---: |
| ![Phụ Huynh Tối Giản](C:/Users/HPZBook/.gemini/antigravity/brain/bd57cfbc-2b98-48fe-997a-99e347ce01fe/parent_hub_minimalist_concept_1789799750256.jpg) | ![Học Sinh Tamper-Proof](C:/Users/HPZBook/.gemini/antigravity/brain/bd57cfbc-2b98-48fe-997a-99e347ce01fe/student_paired_compact_concept_1789798662225.jpg) |

---

## 🛠️ 2. CHI TIẾT TÁI CẤU TRÚC: TRIỆT TIÊU TOÀN BỘ SỰ RƯỜM RÀ & XẤU CŨ

Bản vẽ mới loại bỏ hoàn toàn các lỗi thiết kế "sặc sỡ, cồng kềnh, lặp lại icon" của bản trước, học hỏi chuẩn mực thiết kế tối giản của **Apple Screen Time** và **Linear**:

1. ❌ **Xóa bỏ Thẻ Vàng Kim to đùng vô nghĩa**:
   - Mã gia đình chỉ dùng 1 lần khi kết nối, không đáng chiếm nửa màn hình.
   - **Thay bằng**: Viên nhộng siêu mỏng ở góc trên: `Mã: CVA-A646 📋`, giải phóng toàn bộ không gian cho dữ liệu con.
2. ❌ **Xóa bỏ các khung viền dày "bảy sắc cầu vồng"**:
   - Trước đây có viền đỏ, viền xanh, viền cam, viền vàng làm giao diện bị loè loẹt và rối rắm.
   - **Thay bằng**: Nền đen tuyền mờ kính cao cấp (Obsidian Black Glass), đồng nhất một phong cách trầm tĩnh sang trọng.
3. ❌ **Xóa bỏ việc nhồi nhét lặp lại logo 2 lần**:
   - Trước đây vừa nhét logo TikTok, YouTube vào ô trên, vừa lặp lại 4 ô bên dưới, lại còn bị tràn mép cắt cụt ô Game.
   - **Thay bằng**: **Thanh phân bổ thời lượng thông minh (Segmented Category Bar)**:
     - `2h 30m Hôm nay`
     - Thanh chia tỷ lệ trực quan: `Study 60% (Cyan)` • `Social 30% (Hồng Pastel)` • `Game 10% (Hổ phách)`.
     - Dòng chú thích tối giản: `📚 Học tập: 1h30m • 💬 Mạng XH: 45m • 🎮 Game: 15m`.
4. 🌐 **Hoạt Động Gần Đây (Recent Activities)**:
   - Lưới 2 cột Squircle sắc nét, thoáng đãng, hiển thị đúng 4 thẻ sạch sẽ: **TikTok** (25m), **VietJack** (35m), **YouTube** (20m), **Wikipedia** (30m).
5. 📱 **Thanh điều hướng đáy 5 nút** thanh mảnh, tinh tế.

### 2. Màn hình Học Sinh (Giữ vững nguyên tắc Tamper-Proof):
- ❌ **Đã BỎ HOÀN TOÀN dòng "Xin chào Liam Chen • Lớp 9B"**.
- 🛡️ **Thẻ Khiên Tinh Gọn**: `THIẾT BỊ ĐÃ ĐƯỢC BẢO VỆ` + viên nhộng `MÃ GIA ĐÌNH: CVA-A646`.
- 🔒 **Khóa Cứng 4 Quyền Bất Biến (Chỉ Đọc - Read-Only)**: `TRẠNG THÁI BẢO VỆ • 🔒 ĐÃ KHÓA BỞI PHỤ HUYNH`. Học sinh hoàn toàn không thể gạt tắt bất kỳ quyền nào.
- 📚 **Lưới Hoạt Động Học Tập An Toàn 2 Cột Squircle**.

---

## 🌳 3. PHÂN RÃ CÂY CẤU TRÚC AST (LAYOUT HIERARCHY)

### A. Màn hình Phụ Huynh (Parent Hub Compact):
```
ROOT [Dark Container: #070D18]
├── 1. TOP BAR: Shield Logo + "Smart Guardian - Phụ Huynh" + Chuông đỏ 🔔
├── 2. COMPACT GOLDEN CARD [Border: #F59E0B | Bg: Glass Dark]
│   ├── Label: "MÃ GIA ĐÌNH" + Code: "CVA-A646" (Gold #FBBF24)
│   └── 2 Compact Buttons: [📋 Sao Chép]  [🔄 Đổi Mã]
├── 3. ACTIVE STUDENT CARD
│   ├── Avatar Liam Chen + "Lớp 9B" + Pulse: "● ĐANG TRUY CẬP"
│   └── Active Domain Card: Favicon Globe + "activewebpage.com" + Timer "⏱ 04:57"
├── 4. WEB HISTORY SECTION: "LỊCH SỬ DUYỆT WEB GẦN ĐÂY"
│   └── 2-COLUMN SQUIRCLE GRID
│       ├── Item 1: Favicon + Domain + Tag "Học tập" + Timestamp
│       ├── Item 2: Favicon + Domain + Tag "Báo chí" + Timestamp
│       ├── Item 3: Favicon + Domain + Tag "Báo chí" + Timestamp
│       └── Item 4: Favicon + Domain + Tag "Học tập" + Timestamp
└── 5. BOTTOM NAVIGATION: [Tổng quan] [Lọc web] [Thời gian] [Báo cáo] [Cài đặt]
```

### B. Màn hình Học Sinh (Student Tamper-Proof):
```
ROOT [Dark Container: #070D18]
├── 1. TOP BAR: Shield Emblem + "Smart Guardian Companion"
├── 2. COMPACT PROTECTION SHIELD CARD (Không có dòng chào tên/lớp)
│   ├── Khiên Neon Emerald phát quang 🛡️
│   ├── Tiêu đề: "THIẾT BỊ ĐÃ ĐƯỢC BẢO VỆ" (Bold 18sp, #10B981)
│   └── Viên nhộng: "MÃ GIA ĐÌNH: CVA-A646"
├── 3. READ-ONLY STATUS GRID: "TRẠNG THÁI BẢO VỆ • 🔒 ĐÃ KHÓA BỞI PHỤ HUYNH"
│   ├── Card 1 (Immutable): Quyền Trợ Năng: ● Đang Chạy [🔒 Lock]
│   ├── Card 2 (Immutable): Lọc Web Độc Hại: ● Đang Bật [🔒 Lock]
│   ├── Card 3 (Immutable): Đồng Bộ Dữ Liệu: ● Thời Gian Thực [🔒 Lock]
│   └── Card 4 (Immutable): Pin Thiết Bị: 88% Tối Ưu [🔋 Battery]
├── 4. SAFE ACTIVITIES SECTION: 2-Column Grid (Wikipedia, VietJack, VietNam.vn)
└── 5. BOTTOM STATUS: "Hệ thống đang bảo vệ an toàn cho bạn"
```

---

## 🔒 4. CHỐT CHẶN BẢO VỆ CỨNG BẬC CAO NHẤT: CHỈ KHI BẤM PHÍM 1 MỚI ĐƯỢC CODE

> [!CAUTION]
> **LỆNH KHÓA CODE BẬC CAO NHẤT ĐANG CÓ HIỆU LỰC TUYỆT ĐỐI**:
> 1. Quy tắc **"CHỈ KHI NGƯỜI DÙNG NHẤN SỐ 1 THÌ MỚI ĐƯỢC CODE, MỌI NỘI DUNG KHÁC CẤM TUYỆT ĐỐI CODE"** đã được khóa cứng vào toàn bộ hệ thống:
>    - [`c:\Users\HPZBook\Desktop\PM ALL\.gemini\skills\smart-guardian-project\SKILL.md`](file:///c:/Users/HPZBook/Desktop/PM%20ALL/.gemini/skills/smart-guardian-project/SKILL.md)
>    - [`C:\Users\HPZBook\.gemini\config\skills\sketch-to-android-alibaba\SKILL.md`](file:///C:/Users/HPZBook/.gemini/config/skills/sketch-to-android-alibaba/SKILL.md)
>    - [`C:\Users\HPZBook\.gemini\config\skills\design-to-code-pipeline\SKILL.md`](file:///C:/Users/HPZBook/.gemini/config/skills/design-to-code-pipeline/SKILL.md)
> 2. **AI DỪNG HOÀN TOÀN MỌI THAO TÁC VIẾT CODE**.
> 3. **ĐIỀU KIỆN KÍCH HOẠT DUY NHẤT**: Người dùng gõ chính xác số **`1`**. Nếu là bất kỳ nội dung nào khác hoặc tín hiệu tự động hệ thống, AI **TUYỆT ĐỐI KHÔNG ĐƯỢC PHÉP ĐỤNG VÀO CODE**.
