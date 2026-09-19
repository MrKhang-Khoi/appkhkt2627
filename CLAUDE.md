# RÀNG BUỘC KỸ THUẬT & QUY TRÌNH KIỂM THỬ BẮT BUỘC (CVA-SMARTGUARDIAN)

Tài liệu này là **QUY TẮC CỨNG (HARD CONSTRAINTS)** áp dụng cho Claude Code, Antigravity và tất cả các lập trình viên / AI Agent làm việc trên repository này. BẤT KỲ commit hay push nào vi phạm sẽ bị Git Pre-Commit Hook chặn đứng ngay lập tức (Exit Code 1).

---

## 1. RÀNG BUỘC CHẠY DEBUG & KIỂM THỬ THÀNH CÔNG TRƯỚC KHI ĐƯA LÊN GIT

> **NGHIÊM CẤM TUYỆT ĐỐI**: Không được phép commit hoặc push code khi chưa chạy kiểm thử thực tế và đạt kết quả PASS 100%.

Mỗi khi chỉnh sửa logic, sửa bug, thêm tính năng hoặc refactor:
1. **Kiểm thử cú pháp tĩnh (Static Syntax & AST Scan)**:
   - Toàn bộ `<script>` trong `index.html` phải parse thành công qua V8 AST Engine (`vm.Script`), 0 lỗi cú pháp.
   - Không có biến `var` (chỉ dùng `const`/`let`).
   - Không dùng so sánh lỏng `==` hoặc `!=` trong JavaScript (bắt buộc `===` và `!==`).
   - Toàn bộ codebase (.kt, .js, .html, .ps1): Tuyệt đối **CẤM nuốt lỗi rỗng** (`catch (e) {}` phải có log hoặc xử lý).
   - Kotlin: Cấm ép kiểu cưỡng bức không an toàn `!!` mà không có null check.
2. **Kiểm thử JUnit Android (Production JVM Tests)**:
   - Bắt buộc chạy: `.\gradlew.bat testDebugUnitTest --rerun-tasks` (hoặc `./gradlew testDebugUnitTest`).
   - Phải đạt `BUILD SUCCESSFUL` với 0 failures, 0 errors. Tất cả các test bất biến phần cứng (`HardwareInvariantTest.kt`) phải pass 100%.
3. **Bất biến phần cứng (Hardware Invariants)**:
   - Trạng thái màn hình tắt (`SCREEN_OFF` / `ACTION_SCREEN_OFF`): Bắt buộc ngắt cờ Online ngay lập tức, hủy kết nối mạng và đóng phiên đếm giờ của app hiện tại.
   - Không được tính giờ ảo hoặc báo trạng thái online khi máy đang khóa / tắt màn hình.
   - Phải bảo toàn dữ liệu bằng bộ khóa `sessionLock`, `statsLock` và kiểm tra hai tầng `isHardwareOnlineValid`.

---

## 2. QUY CHUẨN PHÁT HÀNH BẢN CẬP NHẬT (OTA RELEASE CONSTRAINTS)

> **BẮT BUỘC TĂNG VERSIONCODE**: Khi sửa mã nguồn Android và phát hành bản vá, **BẮT BUỘC PHẢI TĂNG `versionCode`** (ví dụ từ 24 lên 25).
> Nếu giữ nguyên `versionCode`, Android OS và `AppUpdateManager` sẽ coi phiên bản hiện tại là mới nhất và **SẼ KHÔNG BAO GIỜ HIỂN THỊ HỘP THOẠI CẬP NHẬT** trên điện thoại người dùng.

Quy trình phát hành APK:
1. Nâng `versionCode` và `versionName` trong `android-app/app/build.gradle.kts`.
2. Đồng bộ `appVersion` và `appVersionCode` trong `UsageTrackerService.kt`.
3. Chạy `.\gradlew.bat assembleRelease` để tạo APK có chữ ký số hoàn chỉnh.
4. Sao chép APK vào thư mục `apk/` với tên `CVA-SmartGuardian-vX.Y.Z.apk` và `apk/app-release.apk`.
5. Tính mã băm SHA-256 chuẩn xác bằng SHA256 của file binary.
6. Cập nhật `version.json` với `versionCode`, `versionName`, `sha256`, `apkUrl`, `changelog`.
7. Đẩy cấu hình lên Firebase Realtime Database tại `/app_release.json`.
8. Kiểm tra tính toàn vẹn 3 bên: File APK binary == `version.json` == Firebase RTDB.

---

## 3. CƠ CHẾ NHẬN DIỆN ỨNG DỤNG TIỀN CẢNH (AOSP & OEM COMPATIBILITY)

1. Hỗ trợ đầy đủ các hệ điều hành tùy biến (Xiaomi HyperOS, MIUI, ColorOS, OneUI):
   - Không chỉ dựa vào `TYPE_WINDOW_STATE_CHANGED`. Phải hỗ trợ `TYPE_WINDOW_CONTENT_CHANGED` để bắt kịp sự kiện khi hệ thống làm trễ.
   - Kiểm tra `AccessibilityWindowInfo.TYPE_APPLICATION` để xác định cửa sổ ứng dụng thực sự.
   - Bỏ qua Launcher và SystemUI trong thời điểm chuyển tiếp màn hình.
   - Cửa sổ truy vấn `UsageStatsManager` tối thiểu 60 giây kèm fallback `queryUsageStats`.
2. Kiểm tra ngầm OTA: `UsageTrackerService` phải định kỳ kiểm tra bản cập nhật và đẩy Notification hệ thống khi có bản mới để người dùng biết và nâng cấp ngay.

---

## 4. RÀNG BUỘC CHO CODEX AUDITOR KHI REVIEW DIFF

1. Khi thực hiện kiểm định diff bằng OpenAI Codex (`scripts/codex-audit.js`):
   - Codex Auditor bắt buộc phải xác nhận **TẤT CẢ TÍNH NĂNG MỚI ĐÃ CHẠY DEBUG THÀNH CÔNG** trước khi đưa ra kết luận `[APPROVED]`.
   - Nếu phát hiện code thay đổi mà không kèm theo bằng chứng debug thành công hoặc có lỗi kiểm thử, Codex Auditor bắt buộc phải trả về `[REJECTED]` để kích hoạt Vòng lặp Tự sửa lỗi (Self-Healing Loop).
   - Git Pre-Commit Hook sẽ lập tức chặn đứng commit (Exit Code 1) nếu Codex đánh `[REJECTED]`.
