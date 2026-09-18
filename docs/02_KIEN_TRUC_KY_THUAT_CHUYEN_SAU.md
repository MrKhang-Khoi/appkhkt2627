# ĐẶC TẢ KIẾN TRÚC KỸ THUẬT CHUYÊN SÂU
**HỆ THỐNG CVA-SMARTGUARDIAN (PHIÊN BẢN 1.0)**

---

## 1. MÔ HÌNH KIẾN TRÚC HỆ THỐNG 4 TẦNG (4-TIER ARCHITECTURE)

```
┌────────────────────────────────────────────────────────────────────────┐
│                        TẦNG 1: THIẾT BỊ HỌC SINH (AGENT)               │
│  • UsageStatsManager (Ghi nhận mili-giây thời gian foreground)        │
│  • AccessibilityService (Bắt tiêu đề app, URL trình duyệt, video)      │
│  • DeviceAdminReceiver (Chống gỡ cài đặt nếu thiếu mã PIN phụ huynh)  │
│  • Persistent Foreground Service (Khởi động cùng máy, chống tắt app)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        TẦNG 2: XỬ LÝ DỮ LIỆU CỤC BỘ (LOCAL AI)         │
│  • App Classifier Engine (Nhận diện hơn 150 package app tại VN)       │
│  • Mini-NLP Risk Analyzer (Bộ lọc từ khóa nguy cơ bạo lực, cờ bạc)     │
│  • Data Aggregator (Nén dữ liệu thành biểu đồ giờ, tính chỉ số R_GS)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        TẦNG 3: ĐỒNG BỘ BẢO MẬT (SECURE SYNC BUS)       │
│  • Kênh 1: Firebase Realtime Database (Bảo mật Rules theo mã gia đình) │
│  • Kênh 2: Báo cáo P2P qua QR Code nén vi sai (0 đồng chi phí server)  │
│  • Kênh 3: Webhook thông báo tự động về Zalo/Telegram Bot của PHHS     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        TẦNG 4: BẢNG ĐIỀU KHIỂN PHỤ HUYNH (DASHBOARD)   │
│  • Giao diện Web PWA chuẩn sư phạm (Mở trên điện thoại PH & máy tính) │
│  • Biểu đồ phân tích trực quan: Tỷ lệ Học/Chơi, Cảnh báo dùng đêm     │
│  • Trình quản lý "Thỏa ước số Gia đình" (Đặt giới hạn giờ & Thưởng)   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. CHI TIẾT TẦNG 1: CƠ CHẾ NATIVE ANDROID OS

### 2.1. Đo lường thời gian bằng `UsageStatsManager`
- Quyền yêu cầu: `android.permission.PACKAGE_USAGE_STATS`.
- Truy vấn sự kiện thời gian thực theo chu kỳ:
  ```java
  UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
  long endTime = System.currentTimeMillis();
  long startTime = getStartOfDayMillis();
  List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime);
  ```
- **Ưu điểm**: Được quản lý bởi nhân Linux/Android, không bao giờ bị học sinh đánh lừa bằng việc chạy ẩn ứng dụng.

### 2.2. Nhận diện ngữ cảnh sâu bằng `AccessibilityService`
- Lắng nghe sự kiện `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED`:
  - Lấy `packageName`: Xác định chính xác ứng dụng đang chạy.
  - Lấy `className`: Phân biệt học sinh đang xem bảng điểm hay đang chat trong ứng dụng.
  - Đọc `contentDescription` / `text`: Bắt từ khóa tìm kiếm trên Google/YouTube để phát hiện nguy cơ bạo lực hoặc trang web độc hại.

### 2.3. Cơ chế Chống gỡ cài đặt (Anti-Tamper Device Admin)
- Đăng ký `DeviceAdminReceiver` trong `AndroidManifest.xml`:
  ```xml
  <receiver android:name=".receivers.SmartGuardianAdminReceiver"
            android:permission="android.permission.BIND_DEVICE_ADMIN">
      <meta-data android:name="android.app.device_admin"
                 android:resource="@xml/device_admin_policies" />
      <intent-filter>
          <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
      </intent-filter>
  </receiver>
  ```
- Khi học sinh bấm gỡ cài đặt trong Settings, Android sẽ gọi hàm `onDisableRequested()`:
  - Hệ thống lập tức yêu cầu nhập **Mã PIN Phụ huynh** (Parent Security PIN).
  - Nếu nhập sai, lệnh gỡ cài đặt bị hủy bỏ và ứng dụng lập tức gửi cảnh báo về máy phụ huynh: *"Phát hiện hành vi cố ý gỡ cài đặt trên máy con!"*.

---

## 3. CHI TIẾT TẦNG 2: MÔ HÌNH TOÁN HỌC & BỘ PHÂN LOẠI ỨNG DỤNG

### 3.1. Chỉ số Cân bằng Hoạt động (Game-to-Study Ratio - $R_{GS}$)
Để cung cấp cho phụ huynh một con số định lượng khoa học, hệ thống định nghĩa **Chỉ số Cân bằng Hoạt động**:
$$R_{GS} = \frac{T_{game}}{T_{study} + 1}$$

- $T_{game}$: Tổng số phút chơi game và lướt mạng xã hội giải trí trong ngày.
- $T_{study}$: Tổng số phút sử dụng ứng dụng học tập (Azota, K12Online, OLM, Từ điển, Sách giáo khoa điện tử).
- **Thang đánh giá tự động**:
  - $R_{GS} \le 0.5$: **Mức Xanh (Rất tốt)** — Học sinh dành nhiều thời gian cho học tập.
  - $0.5 < R_{GS} \le 1.0$: **Mức Vàng (Cân bằng)** — Thời gian học và giải trí tương đương.
  - $R_{GS} > 1.0$: **Mức Đỏ (Báo động)** — Thời gian chơi game vượt quá thời gian học bài.

### 3.2. Bộ phân loại ứng dụng tự động (App Classifier)
Hệ thống tích hợp bảng cơ sở dữ liệu định danh gói ứng dụng phổ biến tại trường học Việt Nam:

| Danh mục | Mã định danh Package tiêu biểu (Android ID) | Tác động đánh giá |
| :--- | :--- | :--- |
| **HỌC TẬP** | `vn.azota.app`, `vn.k12online.app`, `vn.olm.app`, `com.duolingo`, `com.google.android.apps.classroom` | Tích cực (+ Điểm tự chủ) |
| **TRÒ CHƠI (GAME)** | `com.garena.game.kgvn` (Liên Quân), `com.dts.freefireth` (Free Fire), `com.roblox.client` (Roblox), `com.miHoYo.GenshinImpact` | Cần kiểm soát giờ hạn |
| **MẠNG XÃ HỘI** | `com.zhiliaoapp.musically` (TikTok), `com.facebook.katana`, `com.instagram.android`, `com.google.android.youtube` | Cảnh báo khi dùng quá 45p |
| **TIỆN ÍCH HỆ THỐNG** | `com.android.settings`, `com.google.android.dialer`, `com.android.calculator2` | Không tính vào rủi ro |

---

## 4. CHI TIẾT TẦNG 3 & 4: DASHBOARD PHỤ HUYNH & THỎA ƯỚC SỐ

1. **Cấu trúc Thỏa ước số Gia đình (Family Digital Contract)**:
   - Thời gian chơi game tối đa ngày thường: 45 phút/ngày.
   - Thời gian chơi game tối đa cuối tuần: 90 phút/ngày.
   - Giờ giới nghiêm ban đêm (Bedtime Lock): Tự động nhắc nhở sau 22h30.
2. **Cơ chế Thưởng điểm Rèn luyện (Gamified Positive Reinforcement)**:
   - Học sinh duy trì $R_{GS} \le 0.8$ liên tục 5 ngày $\rightarrow$ Đạt danh hiệu *"Chiến binh Tự chủ số"*.
   - Đề xuất phụ huynh tặng phần thưởng khuyến khích (thay vì áp đặt hình phạt).
