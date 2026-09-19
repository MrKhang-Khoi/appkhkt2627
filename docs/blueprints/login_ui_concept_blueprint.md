# Bản Vẽ Phác Thảo Kỹ Thuật 2D: Màn Hình Đăng Nhập & Bảo Mật PIN
## (Alibaba Concept-to-Android D2C Blueprint Sheet)

---

## 🎨 1. BẢN VẼ PHÁC THẢO KỸ THUẬT 2D (CONCEPT BLUEPRINT)

![Bản vẽ Phác thảo Màn hình Đăng nhập](/C:/Users/HPZBook/.gemini/antigravity/brain/bd57cfbc-2b98-48fe-997a-99e347ce01fe/login_screen_concept_1789797191674.jpg)

---

## 📐 2. HỆ THỐNG DESIGN TOKENS CHUẨN MỰC (ALIBABA TOKENS)

| Phân Loại Token | Giá Trị Hex / DP | Ý Nghĩa Kỹ Thuật & Hiệu Ứng Thị Giác |
| :--- | :--- | :--- |
| **Màu nền chính (Canvas Background)** | `#070B14` | Nền đen tuyền Obsidian kết hợp sắc lam sâu (Deep Midnight Navy), tạo chiều sâu Cyberpunk. |
| **Màu bề mặt kính (Glass Surface)** | `rgba(17, 24, 39, 0.75)` | Hiệu ứng Frosted Glass (Kính mờ) với `backdrop-filter: blur(16px)`. |
| **Viền phát quang (Neon Cyan Border)** | `#00F2FE` / `#38BDF8` | Viền vi mạch phát sáng neon `box-shadow: 0 0 12px rgba(56, 189, 248, 0.35)`. |
| **Màu chữ chính (Primary Text)** | `#F8FAFC` | Trắng tinh khiết tương phản cao đạt chuẩn WCAG AAA ($\ge 7:1$). |
| **Màu chữ phụ (Muted Text)** | `#94A3B8` | Xám bạc dịu mắt cho subtitle và hướng dẫn. |
| **Độ cong Squircle (Superellipse)** | `18dp` (Thẻ chính) / `14dp` (Phím số) | Bo góc mượt mà kiểu siêu elip, triệt tiêu góc nhọn thô ráp. |
| **Kích thước phím bấm (Touch Target)** | `64dp × 64dp` | Vượt chuẩn công nghiệp ($\ge 48\text{dp}$), đảm bảo thao tác 1 tay chuẩn xác. |

---

## 🌳 3. PHÂN RÃ CÂY CẤU TRÚC LAYOUT AST (LAYOUT TREE HIERARCHY)

### A. Phân rã cấu trúc thị giác (Visual Tree):
```
ROOT [Fullscreen Container: #070B14]
├── 1. STATUS BAR (14:30 | Dynamic Island | 5G | Battery)
├── 2. APP BRANDING HEADER
│   ├── Logo Shield Glow (Khiên bảo mật phát quang Cyan Neon)
│   ├── Typography: "CVA-SmartGuardian" (Bold 20sp, #F8FAFC)
│   └── Subtitle: "Hệ Sinh Thái Đồng Hành Số" (12sp, #94A3B8)
├── 3. SEGMENTED CONTROL PILL (Thanh chọn 2 đối tượng cân xứng)
│   ├── Pill 1 [ACTIVE]: "Phụ Huynh" (Nền viền Cyan Neon, chữ trắng)
│   └── Pill 2 [INACTIVE]: "Học Sinh" (Nền xám mờ #1E293B, chữ xám)
├── 4. SECURITY PIN CARD (Thẻ kính trung tâm)
│   ├── Lock Glyph Glow (Biểu tượng khóa neon phát sáng)
│   ├── Title: "Parent Security PIN" (SemiBold 16sp)
│   ├── Subtitle: "Nhập mã PIN 4 số để vào bảng điều khiển" (11sp)
│   └── 4-Dot Indicators (● ● ● ● - Chấm Cyan phát sáng khi nhập số)
├── 5. 3x4 TOUCH NUMERIC KEYPAD (Bàn phím cảm ứng Squircle cân đối)
│   ├── Row 1: [1]  [2]  [3]  (Viền neon mảnh, hiệu ứng ripple khi chạm)
│   ├── Row 2: [4]  [5]  [6]
│   ├── Row 3: [7]  [8]  [9]
│   └── Row 4: [Clear]  [0]  [⌫]
└── 6. FOOTER SECURITY BADGE
    ├── Biometric Fingerprint Glyph (Chạm vân tay bảo mật)
    └── Security Notice: "✔ AES-256 Encrypted • v1.2.0" (10sp, #64748B)
```

---

## 💻 4. ÁNH XẠ MÃ NGUỒN CHUẨN XÁC (CODE MAPPING)

### A. Cho Android Native XML Layout:
- Thẻ trung tâm: Sử dụng `<LinearLayout>` với background `@drawable/bg_cyber_card_glow.xml`.
- 4 chấm PIN: `<LinearLayout>` gồm 4 `<View>` kích thước `14dp × 14dp`, background đổi giữa `bg_pin_dot_empty` (vòng tròn xám) và `bg_pin_dot_cyan_neon` (chấm xanh ngọc phát sáng).
- Bàn phím: Lưới 3 cột cân xứng gồm 12 `<TextView>` squircle kích thước `64dp × 64dp`, margin `8dp`, ripple effect khi nhấn.

### B. Cho Web Portal (`index.html`):
- CSS Grid cho bàn phím:
  ```css
  .numpad-grid {
    display: grid;
    grid-template-columns: repeat(3, 68px);
    gap: 12px;
    justify-content: center;
  }
  .num-key-squircle {
    width: 68px;
    height: 68px;
    border-radius: 16px;
    background: rgba(15, 23, 42, 0.8);
    border: 1.5px solid rgba(56, 189, 248, 0.4);
    box-shadow: 0 0 10px rgba(56, 189, 248, 0.15);
    color: #f8fafc;
    font-size: 1.5rem;
    font-weight: 700;
    transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  }
  .num-key-squircle:active {
    transform: scale(0.92);
    border-color: #00f2fe;
    box-shadow: 0 0 16px rgba(0, 242, 254, 0.6);
  }
  ```

---

## 🚦 5. TIÊU CHÍ NGHIỆM THU GATE 1 -> GATE 2
1. Bản vẽ 2D đã sẵn sàng và được kiểm duyệt lưu trữ.
2. Design Tokens đã được định nghĩa rõ ràng, không có màu sắc mập mờ hoặc font chữ ước lệ.
3. Không sử dụng emoji trong các thành phần bảo mật; 100% biểu tượng (Khiên, Khóa, Vân tay, Backspace) phải là Vector SVG hoặc Vector Drawable.
