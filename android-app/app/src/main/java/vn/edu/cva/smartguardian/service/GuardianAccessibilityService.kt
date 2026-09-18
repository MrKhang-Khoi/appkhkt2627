package vn.edu.cva.smartguardian.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // 1. Chỉ xử lý khi sự kiện đến từ các ứng dụng duyệt web
        if (BROWSER_PACKAGES.contains(packageName)) {
            val rootNode = rootInActiveWindow ?: return
            inspectBrowserNodeForUrl(rootNode)
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
