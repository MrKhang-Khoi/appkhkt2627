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

class GuardianAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GuardianAccessibilityService? = null
            private set

        @Volatile
        var isScreenOnState: Boolean = true

        val telemetryEpoch = java.util.concurrent.atomic.AtomicLong(0L)
        internal val sessionLock = Any()

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
        fun evaluateForegroundEvidence(
            activeRootPkg: String?,
            usageStatsLastResumedPkg: String?,
            targetPkg: String,
            now: Long = System.currentTimeMillis(),
            lastEventTime: Long = 0L,
            maxEventAgeMs: Long = 15_000L
        ): Boolean {
            if (targetPkg.isEmpty()) return false

            fun isPackageMatch(pkg: String?): Boolean {
                if (pkg.isNullOrEmpty()) return false
                return pkg == targetPkg ||
                    pkg.startsWith("$targetPkg:") ||
                    (pkg.contains(":") && pkg.substringBefore(":") == targetPkg)
            }

            // Xung đột cửa sổ: Nếu activeRootPkg thuộc về ứng dụng KHÁC, tuyệt đối không được nhận diện là targetPkg
            if (!activeRootPkg.isNullOrEmpty() && !isPackageMatch(activeRootPkg)) {
                return false
            }

            // 1. Accessibility Window Hierarchy (Cửa sổ tiền cảnh đang hiển thị khớp chính xác hoặc là tiến trình con)
            if (!activeRootPkg.isNullOrEmpty() && (activeRootPkg == targetPkg || isPackageMatch(activeRootPkg))) {
                return true
            }

            // 2. UsageStatsManager: Chỉ chấp nhận khi activeRootPkg tạm thời là null (quá trình chuyển cảnh cửa sổ)
            // VÀ event ACTIVITY_RESUMED khớp targetPkg trong khoảng thời gian hợp lệ (chống Stale Evidence)
            if (activeRootPkg.isNullOrEmpty() && (usageStatsLastResumedPkg == targetPkg || isPackageMatch(usageStatsLastResumedPkg))) {
                if (lastEventTime <= 0L || (now >= lastEventTime && now - lastEventTime <= maxEventAgeMs)) {
                    return true
                }
            }

            return false
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
    private var lastHeartbeatTimestamp: Long = 0L
    private var lastActivePackage: String = ""
    private var lastActiveUploadTimestamp: Long = 0L
    private var lastWebActivityReportTimestamp: Long = 0L
    private var currentForegroundPackage: String = ""
    private var currentForegroundStartTime: Long = 0L

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

    fun handleScreenOff(passedEpoch: Long = -1L) {
        // 1. NGAY LẬP TỨC và ĐỒNG BỘ: Ngắt cờ phần cứng, hủy heartbeat, xác lập epoch duy nhất và ngắt socket mạng online in-flight
        isScreenOnState = false
        heartbeatJob?.cancel()
        val currentEpoch = if (passedEpoch != -1L) passedEpoch else telemetryEpoch.incrementAndGet()
        UsageTrackerService.cancelActiveOnlineCalls()
        UsageTrackerService.closePolledSession(applicationContext, "SCREEN_OFF")

        // 2. Chụp snapshot và reset biến bộ nhớ RAM ngay lập tức dưới sessionLock (Thread-safe atomic closing)
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

        // 3. Fast-path offline: Gửi ngay lập tức bản tin ngắt kết nối khẩn cấp lên Firebase
        UsageTrackerService.sendUrgentOfflineStatus(this, currentEpoch)

        // 4. Bất biến kế toán: Ghi nhận thời lượng phiên sử dụng ĐỘC LẬP (kèm session token chống duplicate)
        if (closedPkg.isNotEmpty() && closedStart > 0L && now - closedStart >= 1000L && sessionToken.isNotEmpty()) {
            val sessionDuration = now - closedStart
            serviceScope.launch(Dispatchers.IO) {
                if (telemetryEpoch.get() == currentEpoch) {
                    UsageTrackerService.recordAppSession(applicationContext, closedPkg, sessionDuration, sessionToken, currentEpoch)
                }
            }
        }

        // 5. Telemetry offline state persistence: Áp dụng stale fencing riêng biệt cho trạng thái telemetry
        serviceScope.launch(Dispatchers.IO) {
            // FENCING BẤT BIẾN: Chỉ áp dụng hủy cập nhật trạng thái offline nếu màn hình đã bật lại
            if (telemetryEpoch.get() != currentEpoch || isScreenOnState) {
                Log.w("GuardianAccess", "Hủy bỏ screen off persist: Phát hiện SCREEN_ON trước khi ghi đĩa (currentEpoch=$currentEpoch, latestEpoch=${telemetryEpoch.get()})")
                return@launch
            }

            val prefs = try { getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE) } catch (e: Exception) { null }
            val lastEpoch = prefs?.getLong("last_written_epoch", -1L) ?: -1L
            if (currentEpoch >= lastEpoch) {
                prefs?.edit()
                    ?.putLong("last_written_epoch", currentEpoch)
                    ?.putBoolean("is_device_online", false)
                    ?.putString("last_foreground_pkg", "")
                    ?.putLong("last_foreground_start", 0L)
                    ?.apply()
            }
        }
    }

    fun handleScreenOn(passedEpoch: Long = -1L) {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isHardwareOnline = (pm?.isInteractive == true && km?.isKeyguardLocked != true)

        if (!isHardwareOnline) {
            isScreenOnState = false
            heartbeatJob?.cancel()
            Log.d("GuardianAccess", "handleScreenOn: Thiết bị chưa online hoàn toàn (isInteractive=${pm?.isInteractive}, isKeyguardLocked=${km?.isKeyguardLocked}) -> Chưa kích hoạt heartbeat")
            return
        }

        // Tăng epoch trước khi bật cờ isScreenOnState để đảm bảo mọi coroutine SCREEN_OFF trước đó lập tức bị stale
        val currentEpoch = if (passedEpoch != -1L) passedEpoch else telemetryEpoch.incrementAndGet()
        isScreenOnState = true
        UsageTrackerService.lastDispatchedOfflineEpoch.set(-1L)
        UsageTrackerService.cancelActiveOfflineCalls()
        startPeriodicHeartbeat()

        val currentPkg = try {
            val root = rootInActiveWindow
            val p = root?.packageName?.toString()?.trim()
            if (!p.isNullOrEmpty() && p != applicationContext.packageName) p else null
        } catch (e: Exception) {
            Log.w("GuardianAccess", "Error inspecting rootInActiveWindow on screen on: ${e.message}")
            null
        }

        serviceScope.launch(Dispatchers.IO) {
            val uploadAction = UsageTrackerService.telemetryMutex.withLock {
                if (!isScreenOnState || telemetryEpoch.get() != currentEpoch) {
                    return@withLock null
                }
                synchronized(sessionLock) {
                    lastActivePackage = ""
                    currentForegroundPackage = ""
                    currentForegroundStartTime = 0L
                }

                if (currentPkg != null) {
                    handleWindowStateChangedLocked(currentPkg, currentEpoch)
                } else {
                    null
                }
            }
            uploadAction?.invoke()
        }
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
        // Khi mở app ngân hàng: chốt an toàn phiên ứng dụng trước đó, tuyệt đối không quét node, không đọc cây UI, không can thiệp
        if (isBankPackage(eventPkg)) {
            val now = System.currentTimeMillis()
            val (closedPkg, closedStart) = synchronized(sessionLock) {
                val pkg = currentForegroundPackage
                val start = currentForegroundStartTime
                currentForegroundPackage = ""
                currentForegroundStartTime = 0L
                lastActivePackage = "BANK_APP_PROTECTED"
                Pair(pkg, start)
            }

            if (closedPkg.isNotEmpty() && closedStart > 0L && now - closedStart >= 1000L) {
                val sessionDuration = now - closedStart
                val sessionToken = "${closedPkg}_${closedStart}"
                val currentEpoch = telemetryEpoch.get()
                serviceScope.launch(Dispatchers.IO) {
                    if (telemetryEpoch.get() == currentEpoch) {
                        UsageTrackerService.recordAppSession(applicationContext, closedPkg, sessionDuration, sessionToken, currentEpoch)
                    }
                }
            }
            UsageTrackerService.closePolledSession(applicationContext, "BANK_APP_OPENED")
            return
        }

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (!isScreenOnState || (pm != null && !pm.isInteractive)) {
            return
        }

        // Gửi nhịp tim định kỳ (tối đa 1 lần mỗi 20s) khi học sinh đang tương tác với thiết bị
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatTimestamp > 20_000L) {
            lastHeartbeatTimestamp = now
            UsageTrackerService.sendHeartbeatPing(this)
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
        serviceScope.launch(Dispatchers.IO) {
            val uploadAction = UsageTrackerService.telemetryMutex.withLock {
                // Kiểm tra nguyên tử: Nếu màn hình đã tắt hoặc epoch đã thay đổi, bỏ qua ngay
                if (!isScreenOnState || telemetryEpoch.get() != currentEpoch) {
                    return@withLock null
                }
                handleWindowStateChangedLocked(packageName, currentEpoch)
            }
            uploadAction?.invoke()
        }
    }

    private fun isForegroundApp(packageName: String): Boolean {
        if (packageName.isEmpty()) return false

        // 1. Accessibility Window Hierarchy check: Cửa sổ tiền cảnh đang active
        val rawActivePkg = try {
            val root = rootInActiveWindow
            val rootPkg = root?.packageName?.toString()?.trim()
            if (rootPkg == packageName) {
                packageName
            } else {
                // Kiểm tra danh sách windows tương tác nếu rootInActiveWindow chưa kịp cập nhật lúc chuyển cảnh
                val hasAppWindow = windows?.any { win ->
                    win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    win.root?.packageName?.toString()?.trim() == packageName
                } ?: false
                if (hasAppWindow) packageName else null
            }
        } catch (e: Exception) {
            Log.w("GuardianAccess", "rootInActiveWindow check failed: ${e.message}")
            null
        }

        val activePkg = rawActivePkg

        // Nếu root window hoặc cửa sổ tương tác đã xác nhận chính xác packageName -> 100% Foreground
        if (activePkg == packageName) {
            return true
        }

        // Bất biến xung đột cửa sổ: Nếu active root window thuộc về ứng dụng khác (kể cả Launcher/SystemUI),
        // tuyệt đối từ chối targetPkg để chống stale UsageStats khi bấm Home hoặc đổi app.
        if (!activePkg.isNullOrEmpty() && activePkg != packageName) {
            return false
        }

        // 2. UsageStatsManager Event-Driven check (Google Android 10+ Standard: ACTIVITY_RESUMED)
        // Triệt tiêu hoàn toàn Dead API ActivityManager.getRunningAppProcesses()
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

        // 3. Bất biến phần cứng & an toàn số: Ủy quyền cho evaluateForegroundEvidence xác minh
        return evaluateForegroundEvidence(
            activeRootPkg = activePkg,
            usageStatsLastResumedPkg = lastResumedPkg,
            targetPkg = packageName,
            now = now,
            lastEventTime = lastResumedTime,
            maxEventAgeMs = 15_000L
        )
    }

    private suspend fun handleWindowStateChangedLocked(packageName: String, expectedEpoch: Long): (suspend () -> Unit)? {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isInteractive = pm?.isInteractive ?: false
        val isLocked = km?.isKeyguardLocked ?: false

        if (!isScreenOnState || !isInteractive || telemetryEpoch.get() != expectedEpoch) {
            // Bất biến phần cứng: Màn hình đang tắt hoặc epoch đã thay đổi -> Bỏ qua ngay lập tức mọi sự kiện đổi cửa sổ đến muộn
            return null
        }

        val now = System.currentTimeMillis()
        val (prevPkg, prevStart, prevToken) = synchronized(sessionLock) {
            val p = currentForegroundPackage
            val s = currentForegroundStartTime
            val t = if (p.isNotEmpty() && s > 0L) "${p}_${s}" else ""
            Triple(p, s, t)
        }

        val isHome = isDefaultLauncher(packageName)
        val isLock = packageName == "com.android.systemui" || packageName.contains("keyguard")

        // Màn hình khóa (Keyguard/Lockscreen) hoặc KeyguardManager báo đang khóa
        if (isLock || isLocked) {
            val shouldUploadOff = synchronized(sessionLock) {
                currentForegroundPackage = ""
                currentForegroundStartTime = 0L
                if (lastActivePackage != "SCREEN_OFF") {
                    lastActivePackage = "SCREEN_OFF"
                    lastActiveUploadTimestamp = now
                    true
                } else {
                    false
                }
            }
            if (prevPkg.isNotEmpty() && prevStart > 0L && now - prevStart >= 1500L) {
                UsageTrackerService.recordAppSession(applicationContext, prevPkg, now - prevStart, prevToken)
            }
            val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("last_foreground_pkg", "")
                .putLong("last_foreground_start", 0L)
                .putBoolean("is_device_online", false)
                .apply()

            if (shouldUploadOff) {
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
            val shouldUploadHome = synchronized(sessionLock) {
                currentForegroundPackage = ""
                currentForegroundStartTime = 0L
                if (lastActivePackage != "HOME") {
                    lastActivePackage = "HOME"
                    lastActiveUploadTimestamp = now
                    true
                } else {
                    false
                }
            }
            if (prevPkg.isNotEmpty() && prevStart > 0L && now - prevStart >= 1500L) {
                UsageTrackerService.recordAppSession(applicationContext, prevPkg, now - prevStart, prevToken)
            }
            val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("last_foreground_pkg", "").putLong("last_foreground_start", 0L).apply()

            if (shouldUploadHome) {
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

        // Ghi nhận ứng dụng tiền cảnh thông thường
        if (currentForegroundPackage != packageName) {
            if (prevPkg.isNotEmpty() && prevStart > 0L && now - prevStart >= 1500L) {
                UsageTrackerService.recordAppSession(applicationContext, prevPkg, now - prevStart, prevToken)
            }
            synchronized(sessionLock) {
                currentForegroundPackage = packageName
                currentForegroundStartTime = now
            }

            val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("last_foreground_pkg", packageName).putLong("last_foreground_start", now).apply()
        }

        val shouldDebounce = synchronized(sessionLock) {
            if (packageName == lastActivePackage && now - lastActiveUploadTimestamp < 10_000L) {
                true
            } else {
                lastActivePackage = packageName
                lastActiveUploadTimestamp = now
                false
            }
        }
        if (shouldDebounce) {
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
