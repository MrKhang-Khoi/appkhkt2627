package vn.edu.cva.smartguardian.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import vn.edu.cva.smartguardian.data.AppClassifier
import vn.edu.cva.smartguardian.data.WebFilterList
import vn.edu.cva.smartguardian.ui.BlockedActivity

class GuardianAccessibilityService : AccessibilityService() {

    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.coccoc.trinhduyet",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.brave.browser",
        "com.duckduckgo.mobile.android",
        "com.google.android.googlequicksearchbox"
    )

    private var lastCheckedUrl: String = ""
    private var lastBlockTimestamp: Long = 0L
    private var lastHeartbeatTimestamp: Long = 0L
    private var lastActivePackage: String = ""
    private var lastActiveUploadTimestamp: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Gửi nhịp tim định kỳ (tối đa 1 lần mỗi 20s) khi học sinh đang tương tác với thiết bị
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatTimestamp > 20_000L) {
            lastHeartbeatTimestamp = now
            UsageTrackerService.sendHeartbeatPing(this)
        }

        val packageName = event.packageName?.toString() ?: return

        // 1. Bắt sự kiện chuyển cửa sổ (TYPE_WINDOW_STATE_CHANGED) để giám sát ĐANG MỞ CÁI GÌ
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowStateChanged(packageName)
        }

        // 2. Chỉ xử lý khi sự kiện đến từ các ứng dụng duyệt web để lọc web độc hại
        if (BROWSER_PACKAGES.contains(packageName)) {
            val rootNode = rootInActiveWindow ?: return
            inspectBrowserNodeForUrl(rootNode)
        }
    }

    private fun handleWindowStateChanged(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastActivePackage && now - lastActiveUploadTimestamp < 10_000L) {
            return
        }

        // Bỏ qua nếu là chính app CVA-SmartGuardian hoặc bàn phím gõ chữ
        if (packageName == applicationContext.packageName ||
            packageName.contains("inputmethod") ||
            packageName.contains("keyboard") ||
            packageName == "com.google.android.inputmethod.latin" ||
            packageName == "com.vng.inputmethod.labankey"
        ) {
            return
        }

        // Bắt sự kiện màn hình khóa (Lockscreen / Keyguard) của Android
        if (packageName == "com.android.systemui" || packageName.contains("keyguard")) {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val isInteractive = pm?.isInteractive ?: true
            if (!isInteractive || lastActivePackage != "SCREEN_OFF") {
                lastActivePackage = "SCREEN_OFF"
                lastActiveUploadTimestamp = now
                UsageTrackerService.reportActiveApp(
                    context = this,
                    packageName = "SCREEN_OFF",
                    appName = "Màn hình khóa / Màn hình tắt",
                    category = "OFFLINE",
                    categoryLabel = "Đã tắt màn hình",
                    isForeground = false
                )
            }
            return
        }

        val isHome = isDefaultLauncher(packageName)
        if (isHome) {
            if (lastActivePackage != "HOME") {
                lastActivePackage = "HOME"
                lastActiveUploadTimestamp = now
                UsageTrackerService.reportActiveApp(
                    context = this,
                    packageName = packageName,
                    appName = "Màn hình chính / Màn hình khóa",
                    category = "HOME",
                    categoryLabel = "Màn hình chính",
                    isForeground = false
                )
            }
            return
        }

        lastActivePackage = packageName
        lastActiveUploadTimestamp = now

        var appInfo: android.content.pm.ApplicationInfo? = null
        val appLabel = try {
            appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        val metadata = AppClassifier.classify(packageName, appLabel, appInfo)

        UsageTrackerService.reportActiveApp(
            context = this,
            packageName = packageName,
            appName = metadata.appName.ifEmpty { appLabel },
            category = metadata.category.name,
            categoryLabel = metadata.category.displayName,
            isForeground = true
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

    private fun inspectBrowserNodeForUrl(node: AccessibilityNodeInfo) {
        val extractedUrl = findUrlFromNodeHierarchy(node)

        if (!extractedUrl.isNullOrBlank() && extractedUrl != lastCheckedUrl) {
            lastCheckedUrl = extractedUrl

            // Kiểm tra qua bộ lọc WebFilterList
            val result = WebFilterList.checkUrl(extractedUrl)
            if (result.isBlocked) {
                val now = System.currentTimeMillis()
                // Ngăn chặn lặp vô hạn màn hình khóa (debounce 1.5s)
                if (now - lastBlockTimestamp > 1500) {
                    lastBlockTimestamp = now
                    triggerBlockScreen(extractedUrl, result.category.title, result.reason)
                }
            }
        }
    }

    private fun findUrlFromNodeHierarchy(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.trim()

        // Tìm kiếm các ID thanh địa chỉ phổ biến của Chrome, Cốc Cốc, Samsung Internet
        if (viewId.contains("url_bar") ||
            viewId.contains("search_box") ||
            viewId.contains("location_bar") ||
            viewId.contains("address_bar") ||
            viewId.contains("url_box")
        ) {
            if (!text.isNullOrBlank() && (text.contains(".") || text.contains("/"))) {
                return text
            }
        }

        // Kiểm tra đệ quy các node con
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findUrlFromNodeHierarchy(child)
            if (found != null) return found
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
}
