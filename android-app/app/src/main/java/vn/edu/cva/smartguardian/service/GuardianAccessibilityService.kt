package vn.edu.cva.smartguardian.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import vn.edu.cva.smartguardian.data.AppClassifier
import vn.edu.cva.smartguardian.data.WebFilterList
import vn.edu.cva.smartguardian.ui.BlockedActivity

internal data class ScreenOffTransition(
    val currentEpoch: Long,
    val closedPkg: String,
    val closedStart: Long,
    val sessionToken: String,
    val proceed: Boolean
)

internal data class ScreenOnTransition(
    val currentEpoch: Long,
    val currentPkg: String?,
    val proceed: Boolean
)

class GuardianAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GuardianAccessibilityService? = null
            private set

        @Volatile
        var isScreenOnState: Boolean = true

        val telemetryEpoch = java.util.concurrent.atomic.AtomicLong(0L)
        internal val sessionLock = Any()
        internal val hardwareTransitionLock = Any()
        const val PREF_LAST_WRITTEN_EPOCH = "last_written_epoch"

        val BANK_PACKAGES = setOf(
            "com.vcb",                    // Vietcombank
            "com.vcb.digibank",           // VCB Digibank
            "com.mbmobile",               // MB Bank
            "vn.com.techcombank.bb.app",  // Techcombank
            "com.vnpay.bidv",             // BIDV
            "com.vnpay.vpbankonline",     // VPBank
            "com.vietinbank.ipay",        // VietinBank iPay
            "com.vnpay.agribank",         // Agribank E-Mobile
            "com.tpb.mb.gprsandroid",     // TPBank Mobile
            "vn.momo.platform",           // MoMo
            "com.mservice.momotransfer",  // MoMo transfer
            "vn.com.sacombank.mbanking",  // Sacombank mBanking
            "com.shb.mobilebanking",      // SHB Mobile
            "com.acb.mobilebanking",      // ACB ONE
            "com.hdbank.mbanking",        // HDBank
            "com.vnpay.vib",              // MyVIB
            "com.seabank.mbanking",       // SeABank
            "com.vnpay.ocb"               // OCB OMNI
        )

        fun isBankPackage(pkg: String?): Boolean {
            if (pkg.isNullOrEmpty()) return false
            val lower = pkg.lowercase().trim()
            return BANK_PACKAGES.contains(lower) ||
                lower.startsWith("com.vcb") ||
                lower.contains("mbanking") ||
                lower.contains("ebanking") ||
                lower.contains("digibank") ||
                lower.contains("vietcombank") ||
                lower.contains("techcombank") ||
                lower.contains("momo")
        }

        @JvmStatic
        fun isPackageProcessOf(actual: String?, target: String): Boolean {
            if (actual.isNullOrEmpty() || target.isEmpty()) return false
            return actual == target ||
                actual.startsWith("$target:") ||
                (actual.contains(':') && actual.substringBefore(':') == target)
        }

        @JvmStatic
        fun evaluateForegroundEvidence(
            activeRootPkg: String?,
            usageStatsLastResumedPkg: String?,
            targetPkg: String,
            now: Long = System.currentTimeMillis(),
            lastEventTime: Long = 0L,
            maxEventAgeMs: Long = 15_000L,
            secondaryWindowPkg: String? = null,
            conflictingWindowPkg: String? = null,
            requireWindowEvidence: Boolean = false
        ): Boolean {
            if (targetPkg.isEmpty()) return false

            // Bất biến Đa cửa sổ / Split-Screen (Codex Strict Invariant):
            // Nếu phát hiện bất kỳ cửa sổ nào của ứng dụng khác đang active/focused (kể cả khi activeRootPkg khớp target),
            // tuyệt đối từ chối (Fail-Closed) ngay lập tức, bất kể có matching window hay UsageStats target mới đến đâu.
            if (!conflictingWindowPkg.isNullOrEmpty() && !isPackageProcessOf(conflictingWindowPkg, targetPkg)) {
                return false
            }

            // Xung đột cửa sổ tiền cảnh chính: Nếu activeRootPkg thuộc về ứng dụng KHÁC, tuyệt đối không được nhận diện là targetPkg
            if (!activeRootPkg.isNullOrEmpty() && !isPackageProcessOf(activeRootPkg, targetPkg)) {
                return false
            }

            // Xung đột cửa sổ phụ: Nếu secondaryWindowPkg thuộc về ứng dụng KHÁC, tuyệt đối không được nhận diện
            if (!secondaryWindowPkg.isNullOrEmpty() && !isPackageProcessOf(secondaryWindowPkg, targetPkg)) {
                return false
            }

            // 1. Accessibility Window Hierarchy (Cửa sổ tiền cảnh chính đang hiển thị khớp chính xác hoặc là tiến trình con)
            if (!activeRootPkg.isNullOrEmpty() && (activeRootPkg == targetPkg || isPackageProcessOf(activeRootPkg, targetPkg))) {
                // Nếu UsageStats ghi nhận ứng dụng KHÁC được resumed gần đây (< 3000ms) -> Xung đột trạng thái chuyển cảnh, fail-closed
                if (!usageStatsLastResumedPkg.isNullOrEmpty() && !isPackageProcessOf(usageStatsLastResumedPkg, targetPkg)) {
                    if (lastEventTime > 0L && now >= lastEventTime && now - lastEventTime < 3_000L) {
                        return false
                    }
                }
                return true
            }

            // 2. Trường hợp activeRootPkg là null (chuyển cảnh hoặc split screen transition):
            val isUsageStatsMatch = (usageStatsLastResumedPkg == targetPkg || isPackageProcessOf(usageStatsLastResumedPkg, targetPkg)) &&
                lastEventTime > 0L && now >= lastEventTime && now - lastEventTime <= maxEventAgeMs

            // Nếu dựa vào cửa sổ phụ (secondary window), BẮT BUỘC phải có sự đồng thuận từ UsageStatsManager (Dual-Engine Consensus).
            // Nếu cửa sổ phụ khớp nhưng UsageStats không khớp hoặc đã stale -> KHÓA NGAY (Fail-closed).
            if (!secondaryWindowPkg.isNullOrEmpty() && isPackageProcessOf(secondaryWindowPkg, targetPkg)) {
                return isUsageStatsMatch
            }

            // 3. Nếu không có bất kỳ cửa sổ nào của target trên màn hình (cả activeRootPkg và secondaryWindowPkg đều null/rỗng):
            // Bất biến chuyển tiếp Home/Launcher (Codex Anti-Ghost Invariant):
            // Khi 0 cửa sổ hiện diện, UsageStats cũ KHÔNG ĐỦ để chứng minh ứng dụng còn chiếm màn hình (nguy cơ nhận diện sai A -> Home).
            // Nếu requireWindowEvidence == true, bắt buộc FAIL-CLOSED trả về false!
            if (activeRootPkg.isNullOrEmpty() && secondaryWindowPkg.isNullOrEmpty()) {
                if (requireWindowEvidence) {
                    return false
                }
                return isUsageStatsMatch
            }

            return false
        }

        @Volatile
        internal var currentForegroundPackage: String = ""

        @Volatile
        internal var currentForegroundStartTime: Long = 0L

        @Volatile
        internal var lastActivePackage: String = ""

        @Volatile
        internal var lastActiveUploadTimestamp: Long = 0L

        internal data class WindowTransitionSnapshot(
            val shouldUpload: Boolean,
            val isDifferent: Boolean,
            val prevPkg: String,
            val prevStart: Long,
            val prevToken: String
        )

        @JvmStatic
        internal fun transitionAppSessionAtomic(
            packageName: String,
            expectedEpoch: Long,
            now: Long
        ): WindowTransitionSnapshot? {
            return synchronized(sessionLock) {
                if (!isScreenOnState || telemetryEpoch.get() != expectedEpoch) {
                    return null
                }

                val prevPkg = currentForegroundPackage
                val prevStart = currentForegroundStartTime
                val prevToken = if (prevPkg.isNotEmpty() && prevStart > 0L) "${prevPkg}_${prevStart}" else ""
                val isDiff = (prevPkg != packageName)

                if (isDiff) {
                    currentForegroundPackage = packageName
                    currentForegroundStartTime = now
                }

                val shouldDebounce = if (packageName == lastActivePackage && now - lastActiveUploadTimestamp < 10_000L) {
                    true
                } else {
                    lastActivePackage = packageName
                    lastActiveUploadTimestamp = now
                    false
                }

                WindowTransitionSnapshot(
                    shouldUpload = !shouldDebounce,
                    isDifferent = isDiff,
                    prevPkg = prevPkg,
                    prevStart = prevStart,
                    prevToken = prevToken
                )
            }
        }

        @JvmStatic
        internal fun transitionBankSessionAtomic(
            expectedEpoch: Long,
            now: Long
        ): WindowTransitionSnapshot? {
            return synchronized(sessionLock) {
                if (!isScreenOnState || telemetryEpoch.get() != expectedEpoch) {
                    return null
                }
                val prevPkg = currentForegroundPackage
                val prevStart = currentForegroundStartTime
                val prevToken = if (prevPkg.isNotEmpty() && prevStart > 0L) "${prevPkg}_${prevStart}" else ""
                val isDiff = prevPkg.isNotEmpty()
                currentForegroundPackage = ""
                currentForegroundStartTime = 0L
                val shouldUpload = if (lastActivePackage != "BANK_APP_PROTECTED") {
                    lastActivePackage = "BANK_APP_PROTECTED"
                    lastActiveUploadTimestamp = now
                    true
                } else {
                    false
                }
                WindowTransitionSnapshot(
                    shouldUpload = shouldUpload,
                    isDifferent = isDiff,
                    prevPkg = prevPkg,
                    prevStart = prevStart,
                    prevToken = prevToken
                )
            }
        }

        @JvmStatic
        internal fun transitionOfflineSessionAtomic(
            targetPackage: String,
            expectedEpoch: Long,
            now: Long
        ): WindowTransitionSnapshot? {
            return synchronized(sessionLock) {
                if (!isScreenOnState || telemetryEpoch.get() != expectedEpoch) {
                    return null
                }
                val prevPkg = currentForegroundPackage
                val prevStart = currentForegroundStartTime
                val prevToken = if (prevPkg.isNotEmpty() && prevStart > 0L) "${prevPkg}_${prevStart}" else ""
                val isDiff = prevPkg.isNotEmpty()
                currentForegroundPackage = ""
                currentForegroundStartTime = 0L
                val shouldUpload = if (lastActivePackage != targetPackage) {
                    lastActivePackage = targetPackage
                    lastActiveUploadTimestamp = now
                    true
                } else {
                    false
                }
                WindowTransitionSnapshot(
                    shouldUpload = shouldUpload,
                    isDifferent = isDiff,
                    prevPkg = prevPkg,
                    prevStart = prevStart,
                    prevToken = prevToken
                )
            }
        }
    }

    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.coccoc.trinhduyet",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.brave.browser",
        "com.mi.globalbrowser",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "com.google.android.googlequicksearchbox"
    )

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var heartbeatJob: Job? = null

    private var lastCheckedUrl: String = ""
    private var lastBlockTimestamp: Long = 0L
    private var lastWebActivityReportTimestamp: Long = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isHardwareOnline = (pm?.isInteractive == true && km?.isKeyguardLocked != true)
        isScreenOnState = isHardwareOnline

        WebFilterList.loadFromPreferences(this)
        try {
            UsageTrackerService.start(this)
        } catch (e: Exception) {
            Log.w("GuardianAccess", "Failed to start UsageTrackerService: ${e.message}")
        }

        if (isHardwareOnline) {
            startPeriodicHeartbeat()
        } else {
            handleScreenOff()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isHardwareOnline = (pm?.isInteractive == true && km?.isKeyguardLocked != true)
        isScreenOnState = isHardwareOnline

        try {
            UsageTrackerService.restorePersistedSessionTokens(this)
            UsageTrackerService.flushPendingSessions(this)
            UsageTrackerService.start(this)
        } catch (e: Exception) {
            Log.w("GuardianAccess", "Failed to start UsageTrackerService on connect: ${e.message}")
        }

        if (isHardwareOnline) {
            startPeriodicHeartbeat()
        } else {
            handleScreenOff()
        }
    }

    fun handleScreenOff(): Long {
        val transition = synchronized(hardwareTransitionLock) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isHardwareStillOff = (pm?.isInteractive != true || km?.isKeyguardLocked == true)
            if (!isHardwareStillOff) {
                Log.w("GuardianAccess", "Hủy bỏ handleScreenOff: Thiết bị đã trở lại Online trước khi chiếm lock!")
                return@synchronized ScreenOffTransition(telemetryEpoch.get(), "", 0L, "", false)
            }

            // 1. NGAY LẬP TỨC và ĐỒNG BỘ NGUYÊN TỬ: Ngắt cờ phần cứng, hủy heartbeat, tăng epoch và generation
            isScreenOnState = false
            heartbeatJob?.cancel()
            val currentEpoch = telemetryEpoch.incrementAndGet()
            UsageTrackerService.foregroundGeneration.incrementAndGet() // Triệt tiêu ngay lập tức mọi foreground telemetry in-flight
            UsageTrackerService.cancelActiveOnlineCalls()

            // 2. Chụp snapshot và reset biến bộ nhớ RAM ngay lập tức dưới sessionLock TRƯỚC MỌI THAO TÁC I/O HOẶC SERVICE
            val now = System.currentTimeMillis()
            val (closedPkg, closedStart) = synchronized(sessionLock) {
                val pkg = currentForegroundPackage
                val start = currentForegroundStartTime
                currentForegroundPackage = ""
                currentForegroundStartTime = 0L
                lastActivePackage = "SCREEN_OFF"
                lastActiveUploadTimestamp = now
                Pair(pkg, start)
            }
            val sessionToken = if (closedPkg.isNotEmpty() && closedStart > 0L) "${closedPkg}_${closedStart}" else ""

            ScreenOffTransition(currentEpoch, closedPkg, closedStart, sessionToken, true)
        }

        if (!transition.proceed) {
            return transition.currentEpoch
        }

        val currentEpoch = transition.currentEpoch
        val closedPkg = transition.closedPkg
        val closedStart = transition.closedStart
        val sessionToken = transition.sessionToken
        val now = System.currentTimeMillis()

        // 3. Chốt phiên polling độc lập (dispatches I/O to background coroutine)
        UsageTrackerService.closePolledSession(applicationContext, "SCREEN_OFF")

        // 4. Fast-path offline: Gửi ngay lập tức bản tin ngắt kết nối khẩn cấp lên Firebase (HOÀN TOÀN NGOÀI LOCK)
        UsageTrackerService.sendUrgentOfflineStatus(this, currentEpoch)

        // 5. Bất biến kế toán: Ghi nhận thời lượng phiên sử dụng ĐỘC LẬP (kèm session token chống duplicate)
        if (closedPkg.isNotEmpty() && closedStart > 0L && now - closedStart >= 1000L && sessionToken.isNotEmpty()) {
            val sessionDuration = now - closedStart
            serviceScope.launch(Dispatchers.IO) {
                // Tách fencing telemetry khỏi kế toán phiên (Codex Karl Popper Mandate):
                // Phiên sử dụng đã thực sự diễn ra trong thế giới thực và đã snapshot nguyên tử.
                // Phải ghi nhận vào SharedPreferences mà không phụ thuộc vào telemetryEpoch.
                UsageTrackerService.recordAppSession(applicationContext, closedPkg, sessionDuration, sessionToken)
            }
        }

        // 6. Telemetry offline state persistence: Áp dụng stale fencing riêng biệt cho trạng thái telemetry
        serviceScope.launch(Dispatchers.IO) {
            // FENCING BẤT BIẾN: Chỉ áp dụng hủy cập nhật trạng thái offline nếu màn hình đã bật lại
            if (telemetryEpoch.get() != currentEpoch || isScreenOnState) {
                Log.w("GuardianAccess", "Hủy bỏ screen off persist: Phát hiện SCREEN_ON trước khi ghi đĩa (currentEpoch=$currentEpoch, latestEpoch=${telemetryEpoch.get()})")
                return@launch
            }

            UsageTrackerService.persistDeviceOfflineState(applicationContext, currentEpoch)
        }
        return currentEpoch
    }

    fun handleScreenOn(): Long {
        val transition = synchronized(hardwareTransitionLock) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isHardwareOnline = (pm?.isInteractive == true && km?.isKeyguardLocked != true)
            if (!isHardwareOnline) {
                isScreenOnState = false
                heartbeatJob?.cancel()
                Log.d("GuardianAccess", "handleScreenOn: Thiết bị chưa online hoàn toàn (isInteractive=${pm?.isInteractive}, isKeyguardLocked=${km?.isKeyguardLocked}) -> Chưa kích hoạt heartbeat")
                return@synchronized ScreenOnTransition(telemetryEpoch.get(), null, false)
            }

            // Bất biến chuyển trạng thái phần cứng (Hardware State Transition Invariant - Codex Mandate):
            // Thực hiện đột biến RAM và giải phóng lock tức thì (< 1ms). CẤM giữ lock khi I/O đĩa hoặc mạng.
            val currentEpoch = telemetryEpoch.incrementAndGet()
            isScreenOnState = true
            UsageTrackerService.lastDispatchedOfflineEpoch.set(-1L)
            UsageTrackerService.cancelActiveOfflineCalls()
            startPeriodicHeartbeat()

            val currentPkg = try {
                rootInActiveWindow?.packageName?.toString()
            } catch (e: Exception) {
                null
            }

            ScreenOnTransition(currentEpoch, currentPkg, true)
        }

        if (!transition.proceed) {
            return transition.currentEpoch
        }

        val currentEpoch = transition.currentEpoch
        val currentPkg = transition.currentPkg

        // Dispatch disk persistence sang Dispatchers.IO HOÀN TOÀN NGOÀI hardwareTransitionLock
        serviceScope.launch(Dispatchers.IO) {
            UsageTrackerService.persistDeviceOnlineState(applicationContext, currentEpoch)
            UsageTrackerService.flushPendingSessions(applicationContext)
        }

        serviceScope.launch(Dispatchers.IO) {
            val (uploadAction, actionGen) = UsageTrackerService.telemetryMutex.withLock {
                if (!isScreenOnState || telemetryEpoch.get() != currentEpoch) {
                    return@withLock Pair(null, -1L)
                }
                synchronized(sessionLock) {
                    lastActivePackage = ""
                    currentForegroundPackage = ""
                    currentForegroundStartTime = 0L
                }

                val act = if (currentPkg != null) {
                    handleWindowStateChangedLocked(currentPkg, currentEpoch)
                } else {
                    null
                }
                Pair(act, UsageTrackerService.foregroundGeneration.get())
            }
            if (uploadAction != null && actionGen != -1L) {
                if (UsageTrackerService.foregroundGeneration.get() == actionGen && isScreenOnState && telemetryEpoch.get() == currentEpoch) {
                    uploadAction.invoke()
                }
            }
        }
        return currentEpoch
    }

    private fun startPeriodicHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            while (isActive) {
                delay(60_000L) // Nhịp tim nền 60s theo chuẩn tiết kiệm pin (event-driven đồng bộ tức thì khi đổi app)
                try {
                    val isInteractive = pm?.isInteractive ?: false
                    if (isInteractive && isScreenOnState) {
                        UsageTrackerService.sendHeartbeatPing(applicationContext)
                    }
                } catch (e: Exception) {
                    Log.w("GuardianAccess", "Periodic heartbeat error: ${e.message}")
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventPkg = event.packageName?.toString() ?: return

        // BẢO VỆ TUYỆT ĐỐI ỨNG DỤNG NGÂN HÀNG & VÍ ĐIỆN TỬ (CHUẨN RASP)
        // Khi mở app ngân hàng: chốt an toàn phiên ứng dụng trước đó qua state machine nguyên tử,
        // tuyệt đối không quét node, không đọc cây UI, không can thiệp
        if (isBankPackage(eventPkg)) {
            handleWindowStateChanged(eventPkg)
            return
        }

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (!isScreenOnState || (pm != null && !pm.isInteractive)) {
            return
        }

        // Bắt URL trình duyệt theo thời gian thực
        extractAndAuditBrowserUrl(event)

        // Chỉ lọc các sự kiện chuyển đổi cửa sổ tiền cảnh
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowStateChanged(eventPkg)
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Hỗ trợ bắt ngay ứng dụng đang mở nếu TYPE_WINDOW_STATE_CHANGED ban đầu bị hệ điều hành OEM (Xiaomi/HyperOS) làm trễ lúc chuyển cảnh
            val isCurrentDifferent = synchronized(sessionLock) {
                currentForegroundPackage != eventPkg && lastActivePackage != eventPkg
            }
            if (isCurrentDifferent && !isDefaultLauncher(eventPkg) && eventPkg != "com.android.systemui" && eventPkg != "android") {
                handleWindowStateChanged(eventPkg)
            }
        }
    }

    private fun extractAndAuditBrowserUrl(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (BROWSER_PACKAGES.contains(packageName)) {
            // Xác thực tiền cảnh: Chỉ audit URL nếu màn hình đang bật và root window khớp trình duyệt
            if (!isScreenOnState) return
            val activePkg = try { rootInActiveWindow?.packageName?.toString()?.trim() } catch (e: Exception) { null }
            if (activePkg != null && activePkg != packageName) return

            val rootNode = rootInActiveWindow ?: return
            inspectBrowserNodeForUrl(rootNode, packageName)
        }
    }

    private fun handleWindowStateChanged(packageName: String) {
        if (!isScreenOnState) return
        val currentEpoch = telemetryEpoch.get()
        val startGen = UsageTrackerService.foregroundGeneration.get()
        serviceScope.launch(Dispatchers.IO) {
            val (uploadAction, actionGen) = UsageTrackerService.telemetryMutex.withLock {
                // Kiểm tra nguyên tử: Nếu màn hình đã tắt hoặc epoch hoặc gen đã thay đổi, bỏ qua ngay
                if (!isScreenOnState || telemetryEpoch.get() != currentEpoch || UsageTrackerService.foregroundGeneration.get() != startGen) {
                    return@withLock Pair(null, -1L)
                }
                val act = handleWindowStateChangedLocked(packageName, currentEpoch, startGen)
                Pair(act, UsageTrackerService.foregroundGeneration.get())
            }
            if (uploadAction != null && actionGen != -1L) {
                if (UsageTrackerService.foregroundGeneration.get() == actionGen && isScreenOnState && telemetryEpoch.get() == currentEpoch) {
                    uploadAction.invoke()
                }
            }
        }
    }

    private fun isForegroundApp(packageName: String): Boolean {
        if (packageName.isEmpty()) return false

        // 1. Accessibility Window Hierarchy check: Cửa sổ tiền cảnh đang active
        var directRootPkg: String? = null
        var secondaryMatchingPkg: String? = null
        var conflictingWindowPkg: String? = null

        try {
            val root = rootInActiveWindow
            val rootPkg = root?.packageName?.toString()?.trim()
            if (isPackageProcessOf(rootPkg, packageName)) {
                directRootPkg = rootPkg
            } else if (!rootPkg.isNullOrEmpty()) {
                conflictingWindowPkg = rootPkg
            }

            // Chốt chặn Split-Screen / Multi-Window (Codex Decoupled Invariant):
            // Luôn quét TOÀN BỘ danh sách windows tương tác (TYPE_APPLICATION và isActive || isFocused),
            // bất kể rootInActiveWindow có khớp hay không.
            // Nếu có bất kỳ cửa sổ nào thuộc về package khác packageName, đó là xung đột đa cửa sổ / split-screen -> Bắt buộc fail-closed!
            val appWindows = windows?.filter { win ->
                win.type == AccessibilityWindowInfo.TYPE_APPLICATION && (win.isActive || win.isFocused)
            }

            val conflictingOther = appWindows?.firstOrNull { win ->
                val winPkg = win.root?.packageName?.toString()?.trim()
                !winPkg.isNullOrEmpty() && !isPackageProcessOf(winPkg, packageName)
            }
            if (conflictingOther != null) {
                val winPkg = conflictingOther.root?.packageName?.toString()?.trim()
                conflictingWindowPkg = if (!winPkg.isNullOrEmpty()) winPkg else "conflict.window.detected"
            }

            val matchingWindow = appWindows?.firstOrNull { win ->
                val winPkg = win.root?.packageName?.toString()?.trim()
                isPackageProcessOf(winPkg, packageName)
            }
            if (matchingWindow != null) {
                secondaryMatchingPkg = matchingWindow.root?.packageName?.toString()?.trim() ?: packageName
            }
        } catch (e: Exception) {
            Log.w("GuardianAccess", "Window hierarchy check failed: ${e.message}")
        }

        // Bất biến xung đột cửa sổ & Split-Screen: Nếu phát hiện bất kỳ cửa sổ nào thuộc về ứng dụng khác,
        // tuyệt đối từ chối targetPkg ngay lập tức (Fail-Closed).
        if (!conflictingWindowPkg.isNullOrEmpty()) {
            return false
        }

        // 2. UsageStatsManager Event-Driven check (Google Android 10+ Standard: ACTIVITY_RESUMED)
        var lastResumedPkg: String? = null
        var lastResumedTime: Long = 0L
        val now = System.currentTimeMillis()
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            val events = usm?.queryEvents(now - 15_000L, now)
            if (events != null) {
                var lastEventPkg = ""
                var lastEventType = -1
                var lastEventTime = 0L
                val eventOut = android.app.usage.UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(eventOut)
                    if (eventOut.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ||
                        eventOut.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
                    ) {
                        lastEventPkg = eventOut.packageName
                        lastEventType = eventOut.eventType
                        lastEventTime = eventOut.timeStamp
                    }
                }
                if (lastEventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED && now >= lastEventTime && now - lastEventTime < 15_000L) {
                    lastResumedPkg = lastEventPkg
                    lastResumedTime = lastEventTime
                }
            }
        } catch (e: Exception) {
            Log.w("GuardianAccess", "UsageStatsManager check failed: ${e.message}")
        }

        // Bất biến Fail-Closed (Codex Anti-Ghost / Home Invariant):
        // Bắt buộc phải có bằng chứng cửa sổ tương tác (directRootPkg hoặc secondaryMatchingPkg).
        // Nếu cả directRootPkg và secondaryMatchingPkg đều là null (0 cửa sổ của target trên màn hình),
        // tuyệt đối không được tin tưởng UsageStats cũ khi người dùng đã bấm Home hoặc chuyển app -> Fail-Closed!
        if (directRootPkg.isNullOrEmpty() && secondaryMatchingPkg.isNullOrEmpty()) {
            return false
        }

        // 3. Bất biến phần cứng & an toàn số: Ủy quyền cho evaluateForegroundEvidence xác minh
        // Nếu chỉ có secondaryMatchingPkg mà không có directRootPkg, evaluateForegroundEvidence
        // BẮT BUỘC phải yêu cầu UsageStatsManager đồng thuận trong vòng 15s (Fail-Closed nếu stale hoặc lệch)
        return evaluateForegroundEvidence(
            activeRootPkg = directRootPkg,
            usageStatsLastResumedPkg = lastResumedPkg,
            targetPkg = packageName,
            now = now,
            lastEventTime = lastResumedTime,
            maxEventAgeMs = 15_000L,
            secondaryWindowPkg = secondaryMatchingPkg,
            conflictingWindowPkg = conflictingWindowPkg,
            requireWindowEvidence = true
        )
    }

    internal suspend fun handleWindowStateChangedLocked(
        packageName: String,
        expectedEpoch: Long,
        expectedGen: Long = UsageTrackerService.foregroundGeneration.get()
    ): (suspend () -> Unit)? {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isInteractive = pm?.isInteractive ?: false
        val isLocked = km?.isKeyguardLocked ?: false

        if (!isScreenOnState || !isInteractive || telemetryEpoch.get() != expectedEpoch || UsageTrackerService.foregroundGeneration.get() != expectedGen) {
            // Bất biến phần cứng: Màn hình đang tắt, epoch hoặc generation đã thay đổi -> Bỏ qua ngay lập tức mọi sự kiện đổi cửa sổ đến muộn
            return null
        }

        val now = System.currentTimeMillis()
        val isHome = isDefaultLauncher(packageName)
        val isLock = packageName == "com.android.systemui" || packageName.contains("keyguard")
        val isBank = isBankPackage(packageName)

        // Ứng dụng ngân hàng / Ví điện tử (Chuẩn RASP): Chốt phiên an toàn và chuyển trạng thái bảo vệ nguyên tử
        if (isBank) {
            val transition = transitionBankSessionAtomic(expectedEpoch, now) ?: return null

            if (transition.prevPkg.isNotEmpty() && transition.prevStart > 0L && now - transition.prevStart >= 1000L && transition.prevToken.isNotEmpty()) {
                val sessionDuration = now - transition.prevStart
                serviceScope.launch(Dispatchers.IO) {
                    UsageTrackerService.recordAppSession(applicationContext, transition.prevPkg, sessionDuration, transition.prevToken)
                }
            }
            UsageTrackerService.closePolledSession(applicationContext, "BANK_APP_OPENED")
            serviceScope.launch(Dispatchers.IO) {
                if (telemetryEpoch.get() == expectedEpoch) {
                    val prefs = try { getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE) } catch (e: Exception) { null }
                    prefs?.edit()
                        ?.putString("last_foreground_pkg", "")
                        ?.putLong("last_foreground_start", 0L)
                        ?.apply()
                }
            }
            if (transition.shouldUpload) {
                if (UsageTrackerService.foregroundGeneration.get() != expectedGen || telemetryEpoch.get() != expectedEpoch || !isScreenOnState) {
                    return null
                }
                return UsageTrackerService.prepareActiveAppLocked(
                    context = this@GuardianAccessibilityService,
                    packageName = "BANK_APP_PROTECTED",
                    appName = "Ứng dụng Ngân hàng / Ví điện tử (Được bảo vệ)",
                    category = "OTHER",
                    categoryLabel = "Bảo mật",
                    isForeground = true,
                    expectedEpoch = expectedEpoch
                )
            }
            return null
        }

        // Màn hình khóa (Keyguard/Lockscreen) hoặc KeyguardManager báo đang khóa
        if (isLock || isLocked) {
            val transition = transitionOfflineSessionAtomic("SCREEN_OFF", expectedEpoch, now) ?: return null

            if (transition.prevPkg.isNotEmpty() && transition.prevStart > 0L && now - transition.prevStart >= 1500L && transition.prevToken.isNotEmpty()) {
                val sessionDuration = now - transition.prevStart
                serviceScope.launch(Dispatchers.IO) {
                    UsageTrackerService.recordAppSession(applicationContext, transition.prevPkg, sessionDuration, transition.prevToken)
                }
            }
            serviceScope.launch(Dispatchers.IO) {
                if (telemetryEpoch.get() == expectedEpoch) {
                    val prefs = try { getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE) } catch (e: Exception) { null }
                    prefs?.edit()
                        ?.putString("last_foreground_pkg", "")
                        ?.putLong("last_foreground_start", 0L)
                        ?.putBoolean("is_device_online", false)
                        ?.apply()
                }
            }

            if (transition.shouldUpload) {
                if (UsageTrackerService.foregroundGeneration.get() != expectedGen || telemetryEpoch.get() != expectedEpoch || !isScreenOnState) {
                    return null
                }
                return UsageTrackerService.prepareActiveAppLocked(
                    context = this@GuardianAccessibilityService,
                    packageName = "SCREEN_OFF",
                    appName = "Màn hình khóa / Màn hình tắt",
                    category = "OFFLINE",
                    categoryLabel = "Đã tắt màn hình",
                    isForeground = false,
                    expectedEpoch = expectedEpoch
                )
            }
            return null
        }

        if (isHome) {
            val transition = transitionOfflineSessionAtomic("HOME", expectedEpoch, now) ?: return null

            if (transition.prevPkg.isNotEmpty() && transition.prevStart > 0L && now - transition.prevStart >= 1500L && transition.prevToken.isNotEmpty()) {
                val sessionDuration = now - transition.prevStart
                serviceScope.launch(Dispatchers.IO) {
                    UsageTrackerService.recordAppSession(applicationContext, transition.prevPkg, sessionDuration, transition.prevToken)
                }
            }
            serviceScope.launch(Dispatchers.IO) {
                if (telemetryEpoch.get() == expectedEpoch) {
                    val prefs = try { getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE) } catch (e: Exception) { null }
                    prefs?.edit()
                        ?.putString("last_foreground_pkg", "")
                        ?.putLong("last_foreground_start", 0L)
                        ?.apply()
                }
            }

            if (transition.shouldUpload) {
                if (UsageTrackerService.foregroundGeneration.get() != expectedGen || telemetryEpoch.get() != expectedEpoch || !isScreenOnState) {
                    return null
                }
                return UsageTrackerService.prepareActiveAppLocked(
                    context = this@GuardianAccessibilityService,
                    packageName = packageName,
                    appName = "Màn hình chính / Màn hình khóa",
                    category = "HOME",
                    categoryLabel = "Màn hình chính",
                    isForeground = false,
                    expectedEpoch = expectedEpoch
                )
            }
            return null
        }

        // Kiểm tra xác thực cửa sổ tiền cảnh (Dual-Engine Foreground Verification) CHỈ áp dụng cho ứng dụng người dùng
        if (!isForegroundApp(packageName)) {
            return null
        }

        // Chốt chặn generation fencing sau khi isForegroundApp hoàn tất truy vấn IPC/IO
        if (UsageTrackerService.foregroundGeneration.get() != expectedGen || telemetryEpoch.get() != expectedEpoch || !isScreenOnState) {
            return null
        }

        // Bất biến nguyên tử dưới sessionLock:
        // Chặn đứng hoàn toàn race-condition khi màn hình đã tắt (SCREEN_OFF) hoặc epoch thay đổi trong lúc isForegroundApp đang truy vấn IPC/IO
        val appTransition = transitionAppSessionAtomic(packageName, expectedEpoch, now) ?: return null

        if (appTransition.isDifferent) {
            if (appTransition.prevPkg.isNotEmpty() && appTransition.prevStart > 0L && now - appTransition.prevStart >= 1500L && appTransition.prevToken.isNotEmpty()) {
                val sessionDuration = now - appTransition.prevStart
                serviceScope.launch(Dispatchers.IO) {
                    UsageTrackerService.recordAppSession(applicationContext, appTransition.prevPkg, sessionDuration, appTransition.prevToken)
                }
            }

            serviceScope.launch(Dispatchers.IO) {
                if (telemetryEpoch.get() == expectedEpoch) {
                    val prefs = try { getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE) } catch (e: Exception) { null }
                    prefs?.edit()
                        ?.putString("last_foreground_pkg", packageName)
                        ?.putLong("last_foreground_start", now)
                        ?.apply()
                }
            }
        }

        if (!appTransition.shouldUpload) {
            return null
        }

        if (UsageTrackerService.foregroundGeneration.get() != expectedGen || telemetryEpoch.get() != expectedEpoch || !isScreenOnState) {
            return null
        }

        val appInfo = try {
            packageManager.getApplicationInfo(packageName, 0)
        } catch (e: Exception) {
            null
        }
        val appLabel = appInfo?.let { packageManager.getApplicationLabel(it).toString() } ?: packageName
        val metadata = AppClassifier.classify(packageName, appLabel, appInfo)

        return UsageTrackerService.prepareActiveAppLocked(
            context = this@GuardianAccessibilityService,
            packageName = packageName,
            appName = metadata.appName.ifEmpty { appLabel },
            category = metadata.category.name,
            categoryLabel = metadata.category.displayName,
            isForeground = true,
            expectedEpoch = expectedEpoch
        )
    }

    private fun isDefaultLauncher(packageName: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(intent, 0)
            resolveInfo?.activityInfo?.packageName == packageName ||
                    packageName.contains("launcher") ||
                    packageName.contains("home")
        } catch (e: Exception) {
            false
        }
    }

    private fun getBrowserName(packageName: String): String {
        return when (packageName) {
            "com.android.chrome" -> "Google Chrome"
            "com.coccoc.trinhduyet" -> "Cốc Cốc"
            "com.sec.android.app.sbrowser" -> "Samsung Internet"
            "org.mozilla.firefox" -> "Mozilla Firefox"
            "com.microsoft.emmx" -> "Microsoft Edge"
            "com.opera.browser", "com.opera.mini.native" -> "Opera"
            "com.brave.browser" -> "Brave Browser"
            "com.mi.globalbrowser" -> "Mi Browser"
            "com.duckduckgo.mobile.android" -> "DuckDuckGo"
            "com.vivaldi.browser" -> "Vivaldi"
            "com.kiwibrowser.browser" -> "Kiwi Browser"
            else -> "Trình duyệt Web"
        }
    }

    private fun inspectBrowserNodeForUrl(node: AccessibilityNodeInfo, packageName: String) {
        val extractedUrl = findUrlFromNodeHierarchy(node)

        if (!extractedUrl.isNullOrBlank() && extractedUrl != lastCheckedUrl) {
            val now = System.currentTimeMillis()
            // Tránh spam ghi đè liên tục khi đang gõ từng ký tự (debounce 1.5s)
            if (now - lastWebActivityReportTimestamp < 1500L && extractedUrl.startsWith(lastCheckedUrl)) {
                return
            }

            lastCheckedUrl = extractedUrl
            lastWebActivityReportTimestamp = now

            val browserName = getBrowserName(packageName)
            val pageTitle = findPageTitleFromNodeHierarchy(node) ?: ""

            // Kiểm tra qua bộ lọc WebFilterList
            val result = WebFilterList.checkUrl(extractedUrl)

            // Ghi nhận và đồng bộ hoạt động web lên Firebase (Kênh thời gian thực & Kênh nhật ký)
            UsageTrackerService.reportWebActivity(
                context = this,
                browserPkg = packageName,
                browserName = browserName,
                url = extractedUrl,
                title = pageTitle,
                isBlocked = result.isBlocked
            )

            if (result.isBlocked) {
                // Ngăn chặn lặp vô hạn màn hình khóa (debounce 1.5s)
                if (now - lastBlockTimestamp > 1500L) {
                    lastBlockTimestamp = now
                    triggerBlockScreen(extractedUrl, result.category.title, result.reason)
                }
            }
        }
    }

    private fun findUrlFromNodeHierarchy(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        try {
            val queue = java.util.ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(Pair(rootNode, 0))
            var inspectedNodes = 0
            val maxNodes = 120
            val maxDepth = 10

            while (!queue.isEmpty() && inspectedNodes < maxNodes) {
                val (node, depth) = queue.poll() ?: break
                inspectedNodes++

                val viewId = node.viewIdResourceName?.lowercase() ?: ""
                val text = node.text?.toString()?.trim()

                // Bỏ qua các chuỗi gợi ý / placeholder mặc định của trình duyệt
                val isPlaceholder = text.isNullOrBlank() ||
                        text.equals("search or type url", ignoreCase = true) ||
                        text.equals("search or type web address", ignoreCase = true) ||
                        text.equals("tìm kiếm hoặc nhập địa chỉ web", ignoreCase = true) ||
                        text.equals("tìm kiếm hoặc nhập url", ignoreCase = true) ||
                        text.equals("tìm kiếm hoặc nhập tên web", ignoreCase = true) ||
                        text.equals("search", ignoreCase = true) ||
                        text.equals("tìm kiếm", ignoreCase = true)

                if (!isPlaceholder) {
                    // Kiểm tra viewId thanh địa chỉ phổ biến của Chrome, Cốc Cốc, Samsung, Firefox, Edge, Opera, Mi Browser
                    val isAddressBarId = viewId.contains("url_bar") ||
                            viewId.contains("search_box") ||
                            viewId.contains("location_bar") ||
                            viewId.contains("address_bar") ||
                            viewId.contains("url_box") ||
                            viewId.contains("omnibar") ||
                            viewId.contains("url_field") ||
                            viewId.contains("toolbar_url")

                    if (isAddressBarId && (text.contains(".") || text.contains("/"))) {
                        return text
                    }

                    // Heuristic dự phòng: Nếu là EditText/TextView và chuỗi bắt đầu bằng http://, https:// hoặc domain hợp lệ
                    val isUrlPattern = text.startsWith("http://", ignoreCase = true) ||
                            text.startsWith("https://", ignoreCase = true) ||
                            text.matches(Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?$"))

                    if (isUrlPattern && !text.contains(" ") && text.length >= 4) {
                        return text
                    }
                }

                if (depth < maxDepth) {
                    val childCount = try { node.childCount } catch (t: Throwable) { 0 }
                    for (i in 0 until childCount) {
                        if (inspectedNodes + queue.size >= maxNodes) break
                        try {
                            val child = node.getChild(i)
                            if (child != null) {
                                queue.add(Pair(child, depth + 1))
                            }
                        } catch (t: Throwable) {
                            // Guard against recycled / invalid nodes
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("GuardianAccess", "Safe URL hierarchy traversal error: ${t.message}")
        }
        return null
    }

    private fun findPageTitleFromNodeHierarchy(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        try {
            val queue = java.util.ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(Pair(rootNode, 0))
            var inspectedNodes = 0
            val maxNodes = 100
            val maxDepth = 8

            while (!queue.isEmpty() && inspectedNodes < maxNodes) {
                val (node, depth) = queue.poll() ?: break
                inspectedNodes++

                val viewId = node.viewIdResourceName?.lowercase() ?: ""
                if (viewId.contains("title") || viewId.contains("tab_title") || viewId.contains("page_title")) {
                    val t = node.text?.toString()?.trim()
                    if (!t.isNullOrBlank()) return t
                }

                if (depth < maxDepth) {
                    val childCount = try { node.childCount } catch (t: Throwable) { 0 }
                    for (i in 0 until childCount) {
                        if (inspectedNodes + queue.size >= maxNodes) break
                        try {
                            val child = node.getChild(i)
                            if (child != null) {
                                queue.add(Pair(child, depth + 1))
                            }
                        } catch (t: Throwable) {
                            // Guard against recycled nodes
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("GuardianAccess", "Safe title hierarchy traversal error: ${t.message}")
        }
        return null
    }

    private fun triggerBlockScreen(url: String, category: String, reason: String) {
        // 1. Thoát khỏi trình duyệt bằng phím HOME để học sinh không xem được trang web
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Mở màn hình cảnh báo BlockedActivity đè lên
        val intent = Intent(this, BlockedActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(BlockedActivity.EXTRA_BLOCKED_URL, url)
            putExtra(BlockedActivity.EXTRA_CATEGORY, category)
            putExtra(BlockedActivity.EXTRA_REASON, reason)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        // Được gọi khi hệ thống tạm ngắt dịch vụ
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        try {
            isScreenOnState = false
            heartbeatJob?.cancel()
            val finalEpoch = telemetryEpoch.incrementAndGet()
            UsageTrackerService.cancelActiveOnlineCalls()
            UsageTrackerService.sendUrgentOfflineStatus(applicationContext, finalEpoch)
            serviceJob.cancel()
        } catch (e: Exception) {
            android.util.Log.w("GuardianAccess", "Failed to cancel service jobs during onDestroy: ${e.message}")
        }
        super.onDestroy()
    }
}
