# ĐẶC TẢ TIÊU CHUẨN KỸ THUẬT DỰ ÁN CVA-SMARTGUARDIAN (SPEC.MD)
> **Nguồn chân lý duy nhất (Single Source of Truth)** cho toàn bộ mã nguồn Web Portal và Android App trong thư mục `APP KHKT`.
> Codex Auditor sẽ sử dụng tài liệu này để kiểm định, phê duyệt hoặc bắt viết lại toàn bộ mã nguồn vi phạm.

---

## 1. MỤC TIÊU DỰ ÁN
- Hệ thống Giám sát & Bảo vệ Kỹ thuật số cho Học sinh THCS/THPT (CVA-SmartGuardian).
- Gồm 2 thành phần chính:
  1. **Ứng dụng Android Native** (`android-app/`): Lắng nghe sự kiện hệ điều hành, đo lường thời gian sử dụng app, lọc web, phát hiện nguy cơ và gửi telemetry về máy chủ.
  2. **Cổng Web Portal Giám sát Phụ huynh** (`index.html`): Hiển thị dashboard thời gian thực, quản lý hạn mức, bản đồ số, lịch sử web, và điều khiển từ xa.

---

## 2. CÁC RÀNG BUỘC KỸ THUẬT BẮT BUỘC (HARD CONSTRAINTS)

### 2.1. Phía Android App (Kotlin Native):
- **Kiến trúc Giám sát Tiền cảnh & Chuẩn Google Screen Time (UsageStatsManager)**:
  - Hệ thống áp dụng kiến trúc phân tầng quyền hạn On-Demand: Mặc định sử dụng `UsageStatsManager` (`PACKAGE_USAGE_STATS` - Chuẩn Google Screen Time) để phát hiện và đo đạc thời lượng ứng dụng mà không cần bật quyền Trợ năng (Accessibility), loại bỏ hoàn toàn nguy cơ bị các giải pháp bảo mật RASP của ngân hàng quét và cảnh báo.
  - Quyền Trợ năng (Accessibility) chỉ đóng vai trò tùy chọn nâng cao khi phụ huynh cần tính năng can thiệp/chặn web trực tiếp trên trình duyệt.
  - **Loại trừ tuyệt đối ứng dụng Ngân hàng (RASP Bank Safety)**: Khai báo danh sách `BANK_PACKAGES` (Vietcombank, MB Bank, Techcombank, BIDV, VPBank, VietinBank, Agribank, TPBank, MoMo, Sacombank, SHB, ACB, HDBank, MyVIB, SeABank, OCB...) cùng các gói chứa tiền tố `mbanking`/`ebanking`. Khi phát hiện ứng dụng thuộc danh sách này, hệ thống lập tức thoát, không quét View Tree, không can thiệp, không thu thập telemetry nhạy cảm và chốt phiên an toàn.
- **Bất biến phần cứng (Hardware Invariant) & Cơ chế Chốt Phiên Polling Độc Lập**:
  - Khi màn hình tắt (`ACTION_SCREEN_OFF`), khóa máy Keyguard (`isKeyguardLocked`), hoặc dịch vụ bị hủy (`onDestroy`), NGAY LẬP TỨC ngắt trạng thái Online và chốt phiên đếm giờ của app hiện tại. Tuyệt đối cấm đếm giờ ảo khi máy tắt màn hình hoặc khóa máy.
    - **Non-blocking & Dispatchers.IO Invariant**: Hàm `closePolledSession()`, các luồng xử lý phần cứng (`ACTION_SCREEN_OFF`, `handleScreenOff`) cũng như `handleWindowStateChangedLocked()` (khi nhận HOME, KEYGUARD, hoặc chuyển đổi app) chỉ chụp snapshot trạng thái trong RAM và reset biến bộ đếm dưới `sessionLock` trong thời gian ngắn nhất (< 1ms). Tuyệt đối cấm gọi đồng bộ `recordAppSession()` hoặc `SharedPreferences.apply()` bên trong vùng đang giữ `telemetryMutex` để ngăn ngừa triệt để nghẽn cổ chai (head-of-line blocking). Mọi thao tác đĩa (`recordAppSession`) và cập nhật mạng (`reportActiveApp`) BẮT BUỘC phải được dispatch sang background coroutine `Dispatchers.IO`, kèm session token chống ghi nhận trùng lặp (`polled_${pkg}_${startTime}`) và stale epoch fencing.
    - **Monotonic Foreground Generation Fencing (Chống Race Out-Of-Order Khi Chuyển Đổi App Nhanh)**: Biến `UsageTrackerService.foregroundGeneration` (sử dụng `AtomicLong(0L)`) tăng đơn điệu mỗi lần chuyển đổi ứng dụng tiền cảnh hợp lệ. Mọi tác vụ upload mạng (`executeOnlineGuarded`, `executeOnlineHttpGuarded`, và outer lambda dispatch trong `handleWindowStateChanged`, `handleScreenOn`, `reportActiveApp`) đều được gắn kèm thế hệ phát sinh `currentGen`. Trước và sau khi gửi gói tin HTTP qua mạng, hệ thống kiểm tra đối chiếu `foregroundGeneration.get() == currentGen`: nếu phát hiện thế hệ đã bị vượt qua do người dùng chuyển đổi app nhanh chóng (A -> B -> C), toàn bộ tác vụ của app cũ bị hủy bỏ ngay lập tức, triệt tiêu hoàn toàn hiện tượng app cũ ghi đè trạng thái máy chủ sau app mới.
  - **Đồng bộ hóa & Chống Race Condition trong Polling**: State machine của polling (`lastPolledForegroundPkg`, `lastPolledForegroundStartTime`) được bảo vệ nguyên tử bằng `synchronized(statsLock)`. Trước khi ghi nhận state mới, bắt buộc phải double-check `isScreenOnState` và `telemetryEpoch` để ngăn ngừa race condition khi màn hình tắt giữa chu kỳ polling.
  - **Xác thực tiền cảnh nghiêm ngặt (Dual-Engine Foreground Verification & Fail-Closed)**: Hệ thống sử dụng phối hợp Accessibility Window Hierarchy và UsageStats Event-Driven (`ACTIVITY_RESUMED`). Khi chuyển cảnh cửa sổ (`activeRootPkg` tạm thời `null`), fallback sang UsageStats bắt buộc đối chiếu timestamp thời gian thực theo chuẩn Fail-Closed: bắt buộc `lastEventTime > 0L`, `now >= lastEventTime`, và `now - lastEventTime <= 15_000L`. Nếu timestamp thiếu hoặc `<= 0L`, bắt buộc phải từ chối an toàn (Fail-Closed, trả về false). Vượt qua kiểm định `GuardianAccessibilityService.evaluateForegroundEvidence()`. Nghiêm cấm nhận bừa stale package chỉ dựa vào `lastTimeUsed`.
  - Phân biệt triệt để ứng dụng chạy Foreground (chiếm màn hình) vs Background. Nhận diện cả process con mang tên `package:processName` hoặc `package:renderer` trong cả Active Window lẫn UsageStats. Cấm sử dụng các Dead API đã bị Android vô hiệu hóa.
  - `LruSessionSet` kế thừa `LinkedHashSet<String>`, bị chặn tối đa 500 entries để chống rò rỉ bộ nhớ (OOM), đồng bộ toàn diện trên toàn bộ giao diện Collection (`size`, `isEmpty`, `contains`, `add`, `remove`, `clear`, `iterator`, `containsAll`, `addAll`, `removeAll`, `retainAll`, `equals`, `hashCode`, `removeIf`, `forEach`, `spliterator`, `toArray`, `clone`). Phương thức `addAll` được trang bị self-reference guard (`if (elements === this) return false`) để tránh biến đổi thứ tự ngoài ý muốn khi truyền chính nó.
  - Chuẩn ngữ nghĩa LRU trên cả thao tác đọc và ghi:
    - Khi `contains(token)` được gọi và token đã tồn tại, phần tử được di chuyển về cuối tập hợp (Most Recently Used - MRU) để không bị loại bỏ sớm.
    - Khi `add(token)` được gọi với token đã tồn tại, phần tử được di chuyển về cuối tập hợp và trả về `false` mà không loại bỏ bất kỳ phần tử nào khác.
    - Khi `add(token)` được gọi với token mới và dung lượng đạt `maxEntries` (500), phần tử cũ nhất ở đầu tập hợp (Least Recently Used) sẽ bị loại bỏ.
  - Snapshot an toàn luồng: Phương thức `iterator()` trả về snapshot bản sao qua `super.iterator()` trong khối `synchronized(lock)`, triệt tiêu hoàn toàn đệ quy vô tận (`StackOverflowError`), trong khi các hàm `removeAll`, `retainAll`, `removeIf` lặp trực tiếp `super.iterator()` để đột biến tập hợp gốc chính xác. Phương thức `clone()` tạo bản sao với monitor lock độc lập hoàn toàn.
  - Đồng bộ nguyên tử giữa RAM và đĩa: Thao tác `recordedSessionTokens.add()` và lưu SharedPreferences diễn ra nguyên tử trong cùng một khối `synchronized(statsLock)` qua hàm `recordAppSession` (được gọi độc quyền trên background coroutine `Dispatchers.IO` để đảm bảo không block luồng giao diện). Sử dụng `commit()` để đảm bảo ghi đĩa hoàn tất trước khi nhả lock; nếu `commit()` thất bại, hệ thống tự động rollback nguyên tử 100% cả in-memory SharedPreferences cache lẫn danh sách RAM tokens qua `restoreSnapshotRaw(backupTokens)` và hủy ghi nhận thời lượng, triệt tiêu hoàn toàn rủi ro dirty cache và mất đồng bộ RAM-Đĩa.
  - Khôi phục duy nhất một lần & An toàn lỗi: Biến `AtomicBoolean(false)` đảm bảo `restorePersistedSessionTokens` chỉ đánh dấu hoàn tất khi toàn bộ quá trình đọc và nạp từ SharedPreferences diễn ra thành công, ngăn chặn xáo trộn trật tự LRU và cho phép khôi phục lại nếu gặp lỗi I/O tạm thời.
  - Lưu trữ bền vững thứ tự: Danh sách token được lưu trữ vào SharedPreferences dưới dạng chuỗi JSON Array có thứ tự qua khóa `persisted_session_tokens_json`, bảo toàn chính xác trật tự LRU/FIFO.
- **Bảo mật Xác thực & Triệt tiêu Mã PIN Mặc Định (Zero Default PIN Backdoor)**:
  - Loại bỏ hoàn toàn mã PIN mặc định ("1234", "2025", "0000") trong toàn bộ ứng dụng (bao gồm `BlockedActivity.kt`). Bắt buộc phụ huynh tự thiết lập mã PIN riêng (đúng 4 chữ số số học `^[0-9]{4}$`), băm SHA-256 + Salt ngẫu nhiên per-device trong SharedPreferences. Khi chưa thiết lập, mọi nỗ lực xác thực bị từ chối an toàn (Fail-Closed).
  - Khóa 30 giây sau 5 lần nhập sai lưu trực tiếp vào SharedPreferences (Persistent Rate-Limiting), chống hoàn toàn bypass bằng restart app.
  - Trên màn hình chặn `BlockedActivity`, cấm gọi `super.onBackPressed()` trước `goHomeSafe()`, triệt tiêu rủi ro lộ trang web cấm.
- **Đa Nền Tảng OEM-Agnostic & Giao Diện Tối Giản (Minimalist UI/UX)**:
  - CẤM TUYỆT ĐỐI hardcode tên hãng trên giao diện chung (như nút "Mở Khóa ⋮ (Xiaomi)"). Giao diện người dùng phải đồng nhất và trung tính 100% trên mọi dòng máy (Samsung, Xiaomi, Oppo, Vivo, Pixel).
  - Tự động điều hướng ngầm qua `OemPermissionHelper` theo cơ chế dự phòng 3 tầng độc lập: `Tầng 1: Intent Chuyên Biệt OEM` -> `Tầng 2: ACTION_APPLICATION_DETAILS_SETTINGS` -> `Tầng 3: ACTION_SETTINGS` (0% Crash).
  - Áp dụng nguyên tắc Micro-Copy MD3: 1 Mục tiêu - 1 Mô tả ngắn dưới 10 từ - 1 Nút hành động trực tiếp. Bố cục co giãn không tràn ngang (`match_parent`/`0dp`), `singleLine="true"` với `ellipsize="end"`, điểm chạm $\ge 48\text{dp}$, bọc `WindowInsetsCompat`.
- **An Toàn Bất Đồng Bộ Coroutine & Tiết Kiệm Năng Lượng (Async & Battery Optimization)**:
  - Bảo vệ điểm nối Coroutine (`Safe Continuation Invariant`): Khi dùng `suspendCancellableCoroutine` trong `LocationHelper`, BẮT BUỘC bọc `if (cont.isActive) { cont.resume(...) }`, triệt tiêu hoàn toàn lỗi crash `IllegalStateException: Already resumed`.
  - Triệt tiêu Polling đốt pin: CẤM TUYỆT ĐỐI vòng lặp spam HTTP 15s liên tục. Chuyển sang Event-Driven Sync (chỉ gửi khi đổi app/tắt màn hình) và giãn cách heartbeat 60s - 120s khi máy ở trạng thái tĩnh.
  - Tường lửa Web An Toàn: CẤM tạo giao diện TUN ảo rỗng trong `VpnService` làm mất mạng Internet của học sinh.
  - Lọc Web Chống False-Positive: Tự động giải mã percent-encoding URL và sử dụng biểu thức chính quy Regex với ranh giới ký tự Unicode `(?<![a-z0-9\p{L}])kw(?![a-z0-9\p{L}])` để ngăn chặn hoàn toàn việc chặn nhầm các trang web giáo dục (như Essex, JavaScript).
- **Đồng Bộ Phiên Bản Động 100% (Zero Hardcoded Version)**:
  - CẤM TUYỆT ĐỐI hardcode số phiên bản ("1.2.5", 25) trong mã nguồn. Mọi telemetry Heartbeat bắt buộc đọc trực tiếp từ `packageManager.getPackageInfo()` qua `UsageTrackerService.getDynamicPackageVersion()` trả về `Pair<String, Long>?` (Fail-Closed, không trả về phiên bản giả mạo khi thiếu metadata), luôn đồng bộ tức thời với `build.gradle.kts`.
- **An toàn, Fencing & Hiệu năng Telemetry**:
  - Ticker Heartbeat duy trì nhịp tim định kỳ khi màn hình sáng. Phát song song đồng thời qua HTTP/2 `async(Dispatchers.IO) { ... }.awaitAll()`.
  - Cấm nuốt ngoại lệ rỗng `catch (e) {}` trong toàn bộ mã nguồn.
  - Xử lý triệt để nullability trong Kotlin, cấm dùng `!!` bừa bãi.
  - Fencing mạng 2 tầng (Double-check guard): `executeOnlineGuarded` kiểm tra trạng thái phần cứng trước khi tạo Call và kiểm tra lại ngay sau khi đăng ký Call; nếu màn hình tắt giữa chừng, Call bị hủy lập tức.
  - Ngắt kết nối khẩn cấp: `sendUrgentOfflineStatus` phân bổ timeout độc lập riêng biệt (2000ms cho lần đầu và 2000ms cho 1-shot retry), đảm bảo retry không bị cạn kiệt thời gian do lần gửi đầu.
  - Thống kê phi blocking: `collectAndSave()` sử dụng `apply()` thay cho `commit()`, đồng thời bảo vệ bằng `targetEpoch` snapshot để hủy bỏ các request lỗi thời khi trạng thái phần cứng thay đổi giữa chừng.

### 2.2. Phía Web Portal (`index.html`):
- **Bảo toàn trạng thái người dùng (F5 Chaos & Polling Invariant)**:
  - Khi hàm `updateChildDashboardLive()` hoặc polling telemetry chạy, KHÔNG ĐƯỢC tự ý chuyển đổi tab mà người dùng đang chọn (`window.userHasChosenTab`, `window.currentCategoryTab`).
- **An toàn mã nguồn (Alibaba OCR Standards)**:
  - Cấm sử dụng từ khóa `var`, bắt buộc dùng `const` hoặc `let`.
  - Cấm so sánh lỏng `==`, bắt buộc dùng `===`.
  - Cấm nuốt ngoại lệ `catch (e) {}`.
  - Cấm dùng dữ liệu giả mạo (fake mock data) đánh lừa trạng thái online.

### 2.3. Tính Toàn Vẹn Bản Phát Hành & Phạm Vi Kiểm Thử (Verification Scope):
- **Phạm vi kiểm thử tự động của Repository (Automated CI/Local Verification Scope)**:
  - Repository áp dụng bộ kiểm thử tự động `HardwareInvariantTest.kt` chạy trên JVM với Android Studio JBR với tổng cộng **94 bài kiểm thử** (bao gồm 51 bài kiểm thử cốt lõi của SPEC và 43 bài kiểm thử tính năng & đối kháng Karl Popper mới).
  - Bộ kiểm thử này trực tiếp thực thi mã nguồn production và kiểm chứng toán học/luồng:
    1. Trạng thái tăng đơn điệu của `telemetryEpoch`.
    2. Hành vi hủy kết nối in-flight của `cancelActiveOnlineCalls()` và `cancelActiveOfflineCalls()`.
    3. Ngữ nghĩa LRU access-order của `LruSessionSet` trên cả thao tác đọc (`contains`) và ghi (`add`), cùng snapshot iterator chống đệ quy.
    4. An toàn đa luồng trên `sessionLock` và `statsLock`.
    5. Fencing logic kiểm tra điều kiện phần cứng (`evaluateHardwareOnline`, `shouldAllowTelemetryUpdate`).
    6. Kiểm chứng động cơ polling độc lập UsageStatsManager, tính năng chốt phiên khi tắt màn hình, loại trừ ứng dụng ngân hàng và fallback foreground process matching.
    7. Cơ chế 3 tầng fallback độc lập của `OemPermissionHelper`.
    8. Tính năng giải mã URL và bộ lọc Regex Unicode của `WebFilterList`.
    9. Tính năng trích xuất dynamic package version fail-closed bảo toàn 64-bit Long của `UsageTrackerService`.
    10. Fencing đơn điệu `foregroundGeneration` chống đảo lộn trật tự mạng khi chuyển ứng dụng nhanh.
    11. Tính chất non-blocking của `telemetryMutex` giải phóng tức thì dưới tải đĩa chậm.
  - *Đặc tả phần cứng thực tế*: Việc kiểm thử các lifecycle thực tế phụ thuộc hệ điều hành Android (`ACTION_SCREEN_OFF`, `ACTION_USER_PRESENT`, tối ưu hóa pin OEM) khi chạy trong môi trường CI không có thiết bị thật/emulator kết nối được bảo vệ bằng thiết kế phòng thủ theo chuẩn tài liệu Android Developers (defensive bounded timeouts 3000ms, non-blocking coroutine dispatch, 1-shot retry, và unregister receiver an toàn).
- **Kiến trúc Phân quyền Một Thiết Bị - Một Vai Trò (One-Device One-Role Architecture - Chuẩn Google Family Link & Apple Screen Time)**:
  - Một thiết bị đã ghép đôi bảo vệ con (Student Companion) TUYỆT ĐỐI không hiển thị đồng thời giao diện Phụ huynh để con tự ý can thiệp. Mặc định ẩn hoàn toàn thanh chuyển tab `[Phụ Huynh | Học Sinh]`.
  - Thiết bị cài đặt lần đầu hiển thị màn hình Onboarding chọn vai trò rõ ràng: "Thiết bị của Con" (`ROLE_CHILD`) hoặc "Thiết bị của Cha Mẹ" (`ROLE_PARENT`). Khi đã ghép đôi thành công, hệ thống tự động khóa chặt vai trò `ROLE_CHILD`.
  - Khi phụ huynh cần can thiệp cấu hình hoặc hủy ghép đôi trực tiếp trên máy của con, bắt buộc phải vượt qua chốt chặn xác thực mã PIN phụ huynh:
    + **Chốt Chặn Xác Thực Tường Minh (Zero Default PIN Backdoor)**: Loại bỏ hoàn toàn mã PIN mặc định 1234. Bắt buộc phụ huynh tự thiết lập mã PIN riêng (đúng 4 chữ số số học `^[0-9]{4}$`, đồng nhất 100% với giao diện bàn phím cảm ứng Numpad 4 dots) khi khởi tạo vai trò hoặc trong menu bảo mật. Khi chưa thiết lập, mọi nỗ lực xác thực bị từ chối an toàn (Fail-Closed).
    + **Bảo Vệ Mọi Luồng Hủy Ghép Đôi (Zero Unpair Bypass)**: Mọi luồng hủy ghép đôi trên thiết bị con (bao gồm cả nút Hủy ghép đôi trong menu Quản trị phụ huynh) đều BẮT BUỘC kích hoạt modal xác thực mã PIN phụ huynh `layoutPinConfirmModal` kèm persistent lockout. Tuyệt đối không có đường dẫn nào cho phép hủy ghép đôi mà không qua xác thực PIN.
    + **Khóa Nguyên Tử Toàn Cục (Global Atomic Synchronization)**: Mọi thao tác đọc-tăng-ghi rate-limiting và xác thực PIN được bảo vệ bằng khối đồng bộ nguyên tử `synchronized(PIN_LOCK)`, ngăn ngừa hoàn toàn hiện tượng race condition khi có nhiều luồng gọi đồng thời.
    + **An Toàn Thất Bại Đóng (Fail-Closed Persistence)**: Mọi thao tác ghi SharedPreferences (`setParentPin`, `resetPinLockout`) đều kiểm tra kết quả `commit()` và lập tức trả về `false` nếu ghi đĩa thất bại.
    + **Khóa Tạm Thời Bền Vững (Persistent Rate-Limiting)**: Khóa 30 giây sau 5 lần nhập sai lưu trực tiếp vào SharedPreferences, chống hoàn toàn việc bypass bằng cách restart app hoặc force-stop trên tất cả các luồng xác thực (bàn phím số mở tab phụ huynh, hộp thoại quản trị và modal hủy ghép đôi).
- **Nguyên tắc Đồng nhất Trạng thái Mạng (UI Anti-Contradiction & Zero-Phantom-Online Invariant)**:
  - Tuyệt đối cấm hiển thị trạng thái mâu thuẫn (Ví dụ: Tiêu đề báo `🔴 Ngoại tuyến` nhưng thẻ con lại báo `🟢 ONLINE Trình khởi chạy`).
  - Khi thiết bị con mất heartbeat quá thời gian chờ (> 45s) hoặc đã gửi trạng thái ngắt mạng khẩn cấp: Toàn bộ banner ứng dụng đang chạy chuyển sang chế độ snapshot tĩnh với nhãn `"LẦN CUỐI GHI NHẬN TRƯỚC KHI NGOẠI TUYẾN"`, nhãn trạng thái chuyển thành `[🔴 OFFLINE]` màu đỏ cảnh báo, tuyệt đối không giữ cờ online giả từ cache Firebase.
- **Tiêu chuẩn Thiết kế Giao diện Trực quan & Chống Rớt Dòng (Responsive Mobile Standards)**:
  - Tab phân loại trên dialog giám sát rút gọn nhãn để hiển thị trọn vẹn trên 1 dòng ở mọi kích thước màn hình: `"🌐 Mạng XH"`, `"📚 Học tập"`, `"🎮 Game"`, `"📱 Tất cả"`, kèm thuộc tính `singleLine="true"` và `ellipsize="end"`.
  - Hiển thị Empty State trực quan (`layoutDialogEmptyState`) khi danh mục ứng dụng được lọc trống rỗng, kèm nút dẫn hướng xem tất cả ứng dụng.
- **Tính toàn vẹn bản phát hành OTA & Chốt Chặn Tải File Trực Tuyến (Zero HTTP 404)**:
  - Tệp `version.json`, tệp binary `apk/CVA-SmartGuardian-v1.3.0.apk` và node `/app_release.json` trên Firebase RTDB trực tuyến bắt buộc phải đồng nhất 100% về `versionCode` (30), `versionName` ("1.3.0") và `sha256`.
  - Trường `changelog` trong `version.json` bắt buộc là mảng các chuỗi (`Array<String>`) để đảm bảo tính tương thích ngược tuyệt đối với toàn bộ các client và parser cũ.
  - **Cơ chế Tải Đa Nguồn Dự Phòng (Resilient Multi-Source Fallback)**: `AppUpdateManager` bắt buộc phải duyệt danh sách URL ứng viên (`apkUrl`, `apkFallbackUrl`, và raw GitHub mirror). Khi gặp lỗi HTTP 404 ở nguồn chính (do độ trễ deploy của GitHub Pages), hệ thống tự động fallback tức thời sang nguồn dự phòng mà không làm gián đoạn người dùng.
  - **Chốt Chặn Kiểm Thử Tải File Trực Tuyến (Live OTA Download Gatekeeper)**: Bắt buộc kịch bản kiểm định (`scripts/verify-ota-download.js` và `scripts/codex-audit.js`) phải thực hiện tải byte thực tế qua mạng, xác thực HTTP 200 và kiểm tra SHA-256 của luồng tải về. Nghiêm cấm đưa lên Git (`git push`) nếu việc tải file chưa thành công 100%.

---

## 3. TIÊU CHÍ NGHIỆM THU CỦA CODEX AUDITOR
- [ ] Mọi thay đổi mã nguồn phải thỏa mãn 100% các điều khoản trong mục 2.
- [ ] Không có bẫy logic hoặc hồi quy (regression) làm mất tính năng đã có.
- [ ] Toàn bộ tính năng mới và các bản sửa lỗi bắt buộc phải chạy debug và kiểm thử thành công 100% (Bảo toàn toàn bộ 51 bài kiểm thử gốc của SPEC và các bài kiểm thử mới, 0 failures, 0 errors, 0 skipped, compilation sạch, DOM runtime verification đạt).
- [ ] Tính toàn vẹn OTA được xác thực đồng thời trên cả tệp local và Firebase RTDB `/app_release.json`.
- [ ] Kiểm thử tải thực tế file APK qua mạng (Live Remote APK Download & Checksum Gatekeeper) đạt 100% thành công với HTTP 200, triệt tiêu hoàn toàn mã lỗi HTTP 404 trước khi push Git.
- [ ] Được Codex Auditor phê duyệt `[APPROVED]`. Nếu `[REJECTED]`, bắt buộc phải viết lại (Self-Healing Loop).

---

## 4. QUY CHUẨN KHOA HỌC KỸ THUẬT (KHKT) & GOOGLE PLAY STORE 2026 (THE 6 IRON LAWS)

> **Bổ sung bắt buộc cho dự án Cuộc thi Khoa học Kỹ thuật (KHKT) và Phát hành Google Play Store.**
> Mọi Agent khi sửa đổi bất kỳ mã nguồn nào BẮT BUỘC phải tuân thủ 6 Điều luật Sắt sau:

### 4.1. Chốt Chặn Biên Dịch Vật Lý (Artifact Freshness Gatekeeper):
- CẤM TUYỆT ĐỐI báo cáo PASS hoặc commit khi chưa biên dịch file APK thật.
- Nếu có bất kỳ thay đổi nào trong `android-app/app/src/`:
  - Bắt buộc phải thực thi lệnh đóng gói: `.\gradlew assembleRelease`.
  - Mốc thời gian ghi đĩa của file `apk/CVA-SmartGuardian-v1.x.x.apk` BẮT BUỘC PHẢI MỚI HƠN tất cả các file mã nguồn `.kt`, `.xml`, `.gradle.kts` vừa sửa.
  - Bắt buộc tăng `versionCode` và băm lại mã SHA-256 mới cập nhật vào `version.json`.
  - Mọi hành vi sửa code nhưng giữ file APK cũ để báo cáo hoàn thành đều bị coi là **Gian Dối Khoa Học (Fraudulent Bypass)** và bị chặn đứng bằng Exit Code 1.

### 4.2. Cấm API Chết & Bịa Đặt Mã Nguồn (Anti-Dead-API Law):
- CẤM TUYỆT ĐỐI sử dụng `ActivityManager.getRunningAppProcesses()` để xác định ứng dụng tiền cảnh của bên thứ ba (Google đã khóa bảo mật API này từ Android 10+).
- Nhận diện ứng dụng tiền cảnh BẮT BUỘC sử dụng kiến trúc chuẩn Google: Phối hợp giữa `AccessibilityEvent` (bắt buộc cấu hình XML có `android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds"`) và dòng sự kiện `UsageStatsManager.queryEvents()` (`ACTIVITY_RESUMED`).
- Xử lý phân nhánh tiến trình con (Sub-processes dạng `package:process`). Khi cửa sổ Accessibility chuyển cảnh tạm thời (`rootInActiveWindow == null`), cấm fail-closed mù quáng mà phải đối soát mốc thời gian sự kiện.

### 4.3. Tuân Thủ Chính Sách Google Play Store Về Quyền Trợ Năng (Accessibility Policy):
- CẤM TUYỆT ĐỐI gắn cờ `android:isAccessibilityTool="true"` trong Manifest hoặc XML (chỉ dành cho ứng dụng người khuyết tật, app quản lý trẻ em gắn cờ này sẽ bị Google Play từ chối 100%).
- Bắt buộc có Hộp thoại Minh bạch Độc lập (Prominent In-App Disclosure) trước khi xin quyền Accessibility.
- Cấm dùng Accessibility để tự ý nhấn nút hoặc ngăn cản người dùng gỡ cài đặt trái phép.

### 4.4. Tiêu Chuẩn Giao Diện Responsive & Thích Ứng Đa Màn Hình (Adaptive M3 Standards):
- CẤM TUYỆT ĐỐI gán cứng chiều cao cố định cho vùng nội dung (như `layout_height="280dp"`).
- Đối với danh sách chi tiết hoặc menu thao tác trên điện thoại, BẮT BUỘC sử dụng Modal Bottom Sheet (`BottomSheetDialogFragment`) thay vì Center Dialog chật chội.
- Trên màn hình lớn (Tablet/Foldable), Bottom Sheet hoặc Dialog bắt buộc phải giới hạn `maxWidth = 560dp` hoặc `640dp` và căn giữa.
- Mọi khối văn bản phải tự co giãn (`wrap_content`), hỗ trợ hoàn hảo chế độ phóng to chữ hệ thống (Font Scale 1.5x - 2.0x) mà không bị che lấp hoặc cắt chữ.

### 4.5. Thiết Kế Hộp Thoại & Thông Báo Chuẩn Khoa Học Nhận Thức (Cognitive Ergonomics):
- Mọi hộp thoại hoặc màn hình có tải mạng BẮT BUỘC phải có đầy đủ 4 trạng thái:
  1. `layoutLoading`: Shimmer Skeleton hoặc CircularProgressIndicator.
  2. `layoutContent`: Dữ liệu chính dạng Thẻ Squircle bo tròn 16dp.
  3. `layoutEmpty`: Vector minh họa + Lời giải thích lịch sự + Nút hành động.
  4. `layoutError`: Icon cảnh báo lỗi mạng + Nút [Thử lại] (`btnRetry`).
- Tiêu đề ngắn gọn dưới 7 từ. CẤM đưa các thuật ngữ kỹ sư (như Zero-Phantom-Time, Telemetry, Socket).
- Cặp nút bấm sử dụng ĐỘNG TỪ HÀNH ĐỘNG RÕ RÀNG (ví dụ: `[Thử Lại]`, `[Bật GPS]`, `[Đóng]`). CẤM nút `[OK]` mập mờ.
- CẤM TUYỆT ĐỐI dùng Emoji làm icon hệ thống. Bắt buộc dùng 100% Android Vector Drawables (Material Symbols).
- Thông báo gửi đến học sinh phải mang tính giáo dục, tích cực, sư phạm, tôn trọng tâm lý lứa tuổi học sinh, không dùng từ ngữ kiểm soát cực đoan.

### 4.6. Kiểm Định Đối Đầu Bác Bỏ Karl Popper (Codex Adversarial Review):
- CẤM đưa bất kỳ đoạn văn tự khen, tự giải trình kiến trúc nào của Agent vào Prompt gửi sang Codex.
- Codex bắt buộc đóng vai Kẻ phá hoại (Adversarial Auditor), chủ động tìm kịch bản gãy vụn thực tế (concurrency, race condition, stale state, dead API, UI overflow). Tìm thấy dù chỉ 1 lỗi $\rightarrow$ Bắt buộc trả về `[REJECTED]` và Agent phải sửa lại tận gốc.
