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
- **Bất biến phần cứng (Hardware Invariant)**:
  - Khi màn hình tắt (`ACTION_SCREEN_OFF`), NGAY LẬP TỨC ngắt trạng thái Online và chốt phiên đếm giờ của app hiện tại. Tuyệt đối cấm đếm giờ ảo khi máy tắt màn hình hoặc khóa máy.
  - Phân biệt triệt để ứng dụng chạy Foreground (chiếm màn hình) vs Background. Nhận diện cả process con mang tên `package:processName` có tầm quan trọng `IMPORTANCE_FOREGROUND` (100).
  - `LruSessionSet` kế thừa `LinkedHashSet<String>`, bị chặn tối đa 500 entries để chống rò rỉ bộ nhớ (OOM), đồng bộ toàn diện trên toàn bộ giao diện Collection (`size`, `isEmpty`, `contains`, `add`, `remove`, `clear`, `iterator`, `containsAll`, `addAll`, `removeAll`, `retainAll`, `equals`, `hashCode`, `removeIf`, `forEach`, `spliterator`, `toArray`, `clone`). Phương thức `addAll` được trang bị self-reference guard (`if (elements === this) return false`) để tránh biến đổi thứ tự ngoài ý muốn khi truyền chính nó.
  - Chuẩn ngữ nghĩa LRU trên cả thao tác đọc và ghi:
    - Khi `contains(token)` được gọi và token đã tồn tại, phần tử được di chuyển về cuối tập hợp (Most Recently Used - MRU) để không bị loại bỏ sớm.
    - Khi `add(token)` được gọi với token đã tồn tại, phần tử được di chuyển về cuối tập hợp và trả về `false` mà không loại bỏ bất kỳ phần tử nào khác.
    - Khi `add(token)` được gọi với token mới và dung lượng đạt `maxEntries` (500), phần tử cũ nhất ở đầu tập hợp (Least Recently Used) sẽ bị loại bỏ.
  - Snapshot an toàn luồng: Phương thức `iterator()` trả về snapshot bản sao qua `super.iterator()` trong khối `synchronized(lock)`, triệt tiêu hoàn toàn đệ quy vô tận (`StackOverflowError`), trong khi các hàm `removeAll`, `retainAll`, `removeIf` lặp trực tiếp `super.iterator()` để đột biến tập hợp gốc chính xác. Phương thức `clone()` tạo bản sao với monitor lock độc lập hoàn toàn.
  - Đồng bộ nguyên tử giữa RAM và đĩa: Thao tác `recordedSessionTokens.add()` và lưu SharedPreferences diễn ra nguyên tử trong cùng một khối `synchronized(statsLock)` qua hàm `recordAppSession` (được gọi độc quyền trên background coroutine `Dispatchers.IO` để đảm bảo không block luồng giao diện). Sử dụng `commit()` để đảm bảo ghi đĩa hoàn tất trước khi nhả lock; nếu `commit()` thất bại, hệ thống tự động rollback nguyên tử 100% cả in-memory SharedPreferences cache lẫn danh sách RAM tokens qua `restoreSnapshotRaw(backupTokens)` và hủy ghi nhận thời lượng, triệt tiêu hoàn toàn rủi ro dirty cache và mất đồng bộ RAM-Đĩa.
  - Khôi phục duy nhất một lần & An toàn lỗi: Biến `AtomicBoolean(false)` đảm bảo `restorePersistedSessionTokens` chỉ đánh dấu hoàn tất khi toàn bộ quá trình đọc và nạp từ SharedPreferences diễn ra thành công, ngăn chặn xáo trộn trật tự LRU và cho phép khôi phục lại nếu gặp lỗi I/O tạm thời.
  - Lưu trữ bền vững thứ tự: Danh sách token được lưu trữ vào SharedPreferences dưới dạng chuỗi JSON Array có thứ tự qua khóa `persisted_session_tokens_json`, bảo toàn chính xác trật tự LRU/FIFO.
- **An toàn, Fencing & Hiệu năng Telemetry**:
  - Ticker Heartbeat duy trì nhịp tim định kỳ (15s - 30s) khi màn hình sáng. Phát song song đồng thời qua HTTP/2 `async(Dispatchers.IO) { ... }.awaitAll()`.
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
  - Repository áp dụng bộ kiểm thử tự động `HardwareInvariantTest.kt` chạy trên JVM với Android Studio JBR.
  - Bộ kiểm thử này trực tiếp thực thi mã nguồn production và kiểm chứng toán học/luồng:
    1. Trạng thái tăng đơn điệu của `telemetryEpoch`.
    2. Hành vi hủy kết nối in-flight của `cancelActiveOnlineCalls()` và `cancelActiveOfflineCalls()`.
    3. Ngữ nghĩa LRU access-order của `LruSessionSet` trên cả thao tác đọc (`contains`) và ghi (`add`), cùng snapshot iterator chống đệ quy.
    4. An toàn đa luồng trên `sessionLock` và `statsLock`.
    5. Fencing logic kiểm tra điều kiện phần cứng (`evaluateHardwareOnline`, `shouldAllowTelemetryUpdate`).
  - *Đặc tả phần cứng thực tế*: Việc kiểm thử các lifecycle thực tế phụ thuộc hệ điều hành Android (`ACTION_SCREEN_OFF`, `ACTION_USER_PRESENT`, tối ưu hóa pin OEM) khi chạy trong môi trường CI không có thiết bị thật/emulator kết nối được bảo vệ bằng thiết kế phòng thủ theo chuẩn tài liệu Android Developers (defensive bounded timeouts 3000ms, non-blocking coroutine dispatch, 1-shot retry, và unregister receiver an toàn).
- **Tính toàn vẹn bản phát hành OTA**:
  - Tệp `version.json`, tệp binary `apk/CVA-SmartGuardian-v1.2.4.apk` và node `/app_release.json` trên Firebase RTDB trực tuyến bắt buộc phải đồng nhất 100% về `versionCode`, `versionName` và `sha256`.

---

## 3. TIÊU CHÍ NGHIỆM THU CỦA CODEX AUDITOR
- [ ] Mọi thay đổi mã nguồn phải thỏa mãn 100% các điều khoản trong mục 2.
- [ ] Không có bẫy logic hoặc hồi quy (regression) làm mất tính năng đã có.
- [ ] Tính toàn vẹn OTA được xác thực đồng thời trên cả tệp local và Firebase RTDB `/app_release.json`.
- [ ] Được Codex Auditor phê duyệt `[APPROVED]`. Nếu `[REJECTED]`, bắt buộc phải viết lại (Self-Healing Loop).
