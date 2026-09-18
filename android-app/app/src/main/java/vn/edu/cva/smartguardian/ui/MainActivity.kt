package vn.edu.cva.smartguardian.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import vn.edu.cva.smartguardian.R
import vn.edu.cva.smartguardian.data.AppCategory
import vn.edu.cva.smartguardian.data.AppClassifier
import vn.edu.cva.smartguardian.receiver.SmartGuardianAdminReceiver
import vn.edu.cva.smartguardian.service.GuardianAccessibilityService
import vn.edu.cva.smartguardian.service.SafeVpnFilterService
import vn.edu.cva.smartguardian.service.UsageTrackerService
import vn.edu.cva.smartguardian.update.AppUpdateManager
import vn.edu.cva.smartguardian.update.UpdateInfo

class MainActivity : AppCompatActivity() {

    private lateinit var tvStudyTime: TextView
    private lateinit var tvGameTime: TextView
    private lateinit var tvBalanceScore: TextView
    private lateinit var tvTotalScreenTime: TextView
    private lateinit var tvLiveStatusBadge: TextView

    private lateinit var btnPermissionUsage: Button
    private lateinit var btnPermissionAccessibility: Button
    private lateinit var btnToggleVpn: Button
    private lateinit var btnPermissionAdmin: Button
    private lateinit var btnOpenDashboard: Button
    private lateinit var btnOpenAppInfo: Button
    private lateinit var tvDeviceIdentityInfo: TextView
    private lateinit var btnViewInstalledApps: Button
    private lateinit var btnCheckUpdate: Button

    private var isVpnRunning = false

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startSafeVpn()
        } else {
            Toast.makeText(this, "Phụ huynh từ chối cấp quyền VPN!", Toast.LENGTH_SHORT).show()
        }
    }

    private val usageUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadUsageStatsFromPrefs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()

        // Khởi động dịch vụ đếm giờ nếu đã có quyền
        if (hasUsageStatsPermission()) {
            UsageTrackerService.start(this)
        }

        // Tự động kiểm tra bản cập nhật mới từ GitHub trong nền
        performUpdateCheck(userInitiated = false)
    }

    override fun onResume() {
        super.onResume()
        checkAllPermissions()
        loadUsageStatsFromPrefs()

        val filter = IntentFilter(UsageTrackerService.ACTION_USAGE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usageUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usageUpdateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(usageUpdateReceiver)
    }

    private fun initViews() {
        tvStudyTime = findViewById(R.id.tvStudyTime)
        tvGameTime = findViewById(R.id.tvGameTime)
        tvBalanceScore = findViewById(R.id.tvBalanceScore)
        tvTotalScreenTime = findViewById(R.id.tvTotalScreenTime)
        tvLiveStatusBadge = findViewById(R.id.tvLiveStatusBadge)

        btnPermissionUsage = findViewById(R.id.btnPermissionUsage)
        btnPermissionAccessibility = findViewById(R.id.btnPermissionAccessibility)
        btnToggleVpn = findViewById(R.id.btnToggleVpn)
        btnPermissionAdmin = findViewById(R.id.btnPermissionAdmin)
        btnOpenDashboard = findViewById(R.id.btnOpenDashboard)
        btnOpenAppInfo = findViewById(R.id.btnOpenAppInfo)
        tvDeviceIdentityInfo = findViewById(R.id.tvDeviceIdentityInfo)
        btnViewInstalledApps = findViewById(R.id.btnViewInstalledApps)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)

        updateDeviceIdentityUI()
    }

    private fun updateDeviceIdentityUI() {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val androidVer = "Android ${Build.VERSION.RELEASE}"
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)?.take(6)?.uppercase() ?: "CVA"
        tvDeviceIdentityInfo.text = "Thiết bị: $manufacturer $model ($androidVer)\nMã ghép đôi học sinh: CVA-8A2 | Device ID: #$androidId"
    }

    private fun setupListeners() {
        btnOpenAppInfo.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Không thể mở cài đặt: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnViewInstalledApps.setOnClickListener {
            showInstalledAppsDialog()
        }

        btnPermissionUsage.setOnClickListener {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        }

        btnPermissionAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnToggleVpn.setOnClickListener {
            if (!isVpnRunning) {
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    vpnLauncher.launch(prepareIntent)
                } else {
                    startSafeVpn()
                }
            } else {
                stopSafeVpn()
            }
        }

        btnPermissionAdmin.setOnClickListener {
            val adminComponent = ComponentName(this, SmartGuardianAdminReceiver::class.java)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.admin_receiver_desc)
                )
            }
            startActivity(intent)
        }

        btnOpenDashboard.setOnClickListener {
            val dashboardUrl = "https://mrkhang-khoi.github.io/appkhkt2627/?tab=dashboard"
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl))
            try {
                startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Không thể mở trình duyệt: $dashboardUrl", Toast.LENGTH_SHORT).show()
            }
        }

        btnCheckUpdate.setOnClickListener {
            performUpdateCheck(userInitiated = true)
        }
    }

    private fun performUpdateCheck(userInitiated: Boolean) {
        lifecycleScope.launch {
            if (userInitiated) {
                Toast.makeText(this@MainActivity, "Đang kết nối máy chủ GitHub kiểm tra cập nhật...", Toast.LENGTH_SHORT).show()
            }
            val updateInfo = AppUpdateManager.checkForUpdate(this@MainActivity)
            if (updateInfo != null) {
                showUpdateAvailableDialog(updateInfo)
            } else if (userInitiated) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Thông Báo")
                    .setMessage("Ứng dụng CVA-SmartGuardian của bạn đang là phiên bản mới nhất!")
                    .setPositiveButton("Đóng", null)
                    .show()
            }
        }
    }

    private fun showUpdateAvailableDialog(updateInfo: UpdateInfo) {
        val changelogText = if (updateInfo.changelog.isNotEmpty()) {
            "\n\n📋 TÍNH NĂNG MỚI:\n" + updateInfo.changelog.joinToString("\n") { "• $it" }
        } else ""

        val sizeText = if (updateInfo.fileSize.isNotEmpty()) " (${updateInfo.fileSize})" else ""

        val builder = AlertDialog.Builder(this)
            .setTitle("🚀 Có Bản Cập Nhật Mới: v${updateInfo.versionName}")
            .setMessage("Đã có phiên bản mới trên GitHub$sizeText. Bạn có muốn tải về và cài đặt ngay không?$changelogText")
            .setPositiveButton("Cập Nhật Ngay") { _, _ ->
                startDownloadAndInstall(updateInfo)
            }

        if (!updateInfo.isForceUpdate) {
            builder.setNegativeButton("Để Sau", null)
        } else {
            builder.setCancelable(false)
        }

        builder.show()
    }

    private fun startDownloadAndInstall(updateInfo: UpdateInfo) {
        val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            setPadding(40, 24, 40, 24)
        }

        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Đang Tải Bản Cập Nhật v${updateInfo.versionName}")
            .setMessage("Vui lòng đợi giây lát trong khi tải gói cài đặt từ GitHub...")
            .setView(progressBar)
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            val result = AppUpdateManager.downloadAndVerifyApk(this@MainActivity, updateInfo) { percent ->
                progressBar.progress = percent
            }
            progressDialog.dismiss()

            result.onSuccess { apkFile ->
                Toast.makeText(this@MainActivity, "Tải bản cập nhật thành công! Đang mở trình cài đặt...", Toast.LENGTH_SHORT).show()
                AppUpdateManager.installApk(this@MainActivity, apkFile)
            }.onFailure { error ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Cập Nhật Thất Bại")
                    .setMessage("Không thể tải hoặc xác minh tệp cập nhật:\n${error.message}")
                    .setPositiveButton("Đóng", null)
                    .show()
            }
        }
    }

    private fun showInstalledAppsDialog() {
        val pm = packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        var studyCount = 0
        var gameCount = 0
        var socialCount = 0
        var utilityCount = 0
        val sampleList = mutableListOf<String>()

        for (app in installedApps) {
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val appLabel = pm.getApplicationLabel(app).toString()
            val metadata = AppClassifier.classify(app.packageName, appLabel)

            if (!isSystem || metadata.category != AppCategory.UTILITY) {
                when (metadata.category) {
                    AppCategory.STUDY -> {
                        studyCount++
                        if (sampleList.size < 15) sampleList.add("📚 $appLabel (Học tập)")
                    }
                    AppCategory.GAME -> {
                        gameCount++
                        if (sampleList.size < 15) sampleList.add("🎮 $appLabel (Game)")
                    }
                    AppCategory.SOCIAL -> {
                        socialCount++
                        if (sampleList.size < 15) sampleList.add("🌐 $appLabel (MXH/Video)")
                    }
                    AppCategory.UTILITY, AppCategory.OTHER -> {
                        utilityCount++
                    }
                }
            }
        }

        val totalCustomApps = studyCount + gameCount + socialCount + utilityCount
        val message = StringBuilder()
        message.append("📊 TỔNG SỐ ỨNG DỤNG ĐÃ CÀI: $totalCustomApps\n\n")
        message.append("• 📚 Học tập (Study): $studyCount ứng dụng\n")
        message.append("• 🎮 Trò chơi (Game): $gameCount ứng dụng\n")
        message.append("• 🌐 Mạng xã hội / Video: $socialCount ứng dụng\n")
        message.append("• ⚙️ Tiện ích & Công cụ: $utilityCount ứng dụng\n\n")
        message.append("📋 MỘT SỐ ỨNG DỤNG TIÊU BIỂU TRÊN MÁY:\n")
        if (sampleList.isEmpty()) {
            message.append("(Hệ thống đang hoạt động với các app mặc định)")
        } else {
            sampleList.forEach { message.append("$it\n") }
        }

        AlertDialog.Builder(this)
            .setTitle("Thống Kê Ứng Dụng Thiết Bị")
            .setMessage(message.toString())
            .setPositiveButton("Đóng", null)
            .show()
    }

    private fun checkAllPermissions() {
        // 1. Kiểm tra Quyền Usage Stats
        val hasUsage = hasUsageStatsPermission()
        if (hasUsage) {
            btnPermissionUsage.text = "✓ Đã Bật"
            btnPermissionUsage.isEnabled = false
            btnPermissionUsage.setBackgroundColor(getColor(R.color.success))
        } else {
            btnPermissionUsage.text = "Cấp Quyền"
            btnPermissionUsage.isEnabled = true
            btnPermissionUsage.setBackgroundColor(getColor(R.color.primary))
        }

        // 2. Kiểm tra Quyền Accessibility
        val hasAccessibility = isAccessibilityServiceEnabled()
        if (hasAccessibility) {
            btnPermissionAccessibility.text = "✓ Đã Bật"
            btnPermissionAccessibility.isEnabled = false
            btnPermissionAccessibility.setBackgroundColor(getColor(R.color.success))
        } else {
            btnPermissionAccessibility.text = "Cấp Quyền"
            btnPermissionAccessibility.isEnabled = true
            btnPermissionAccessibility.setBackgroundColor(getColor(R.color.primary))
        }

        // 3. Kiểm tra Quyền Device Admin
        val hasAdmin = isDeviceAdminActive()
        if (hasAdmin) {
            btnPermissionAdmin.text = "✓ Đã Bảo Vệ"
            btnPermissionAdmin.isEnabled = false
            btnPermissionAdmin.setBackgroundColor(getColor(R.color.success))
        } else {
            btnPermissionAdmin.text = "Kích Hoạt"
            btnPermissionAdmin.isEnabled = true
            btnPermissionAdmin.setBackgroundColor(getColor(R.color.primary))
        }

        // Cập nhật trạng thái tổng thể
        if (hasUsage && hasAccessibility) {
            tvLiveStatusBadge.text = "ĐANG BẢO VỆ"
            tvLiveStatusBadge.setTextColor(getColor(R.color.success))
        } else {
            tvLiveStatusBadge.text = "CHƯA HOÀN TẤT"
            tvLiveStatusBadge.setTextColor(getColor(R.color.warning))
        }
    }

    private fun startSafeVpn() {
        val intent = Intent(this, SafeVpnFilterService::class.java).apply {
            action = SafeVpnFilterService.ACTION_START_VPN
        }
        startService(intent)
        isVpnRunning = true
        btnToggleVpn.text = "✓ Đang Chặn"
        btnToggleVpn.setBackgroundColor(getColor(R.color.success))
        Toast.makeText(this, "Đã kích hoạt tường lửa DNS lọc nội dung độc hại!", Toast.LENGTH_SHORT).show()
    }

    private fun stopSafeVpn() {
        val intent = Intent(this, SafeVpnFilterService::class.java).apply {
            action = SafeVpnFilterService.ACTION_STOP_VPN
        }
        startService(intent)
        isVpnRunning = false
        btnToggleVpn.text = "Bật Lá Chắn"
        btnToggleVpn.setBackgroundColor(getColor(R.color.primary))
        Toast.makeText(this, "Đã tắt tường lửa lọc DNS!", Toast.LENGTH_SHORT).show()
    }

    private fun loadUsageStatsFromPrefs() {
        val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
        val studyMs = prefs.getLong("study_time_ms", 0L)
        val gameMs = prefs.getLong("game_time_ms", 0L)
        val totalMs = prefs.getLong("total_screen_time_ms", 0L)
        val score = prefs.getInt("balance_score", 100)

        val studyMinutes = (studyMs / 1000 / 60).toInt()
        val gameMinutes = (gameMs / 1000 / 60).toInt()
        val totalMinutes = (totalMs / 1000 / 60).toInt()

        tvStudyTime.text = "${studyMinutes}p"
        tvGameTime.text = "${gameMinutes}p"
        tvBalanceScore.text = "$score"

        val hours = totalMinutes / 60
        val remainingMinutes = totalMinutes % 60
        tvTotalScreenTime.text = "Tổng thời gian sáng màn hình: ${hours} giờ ${remainingMinutes} phút"
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedComponentName = ComponentName(this, GuardianAccessibilityService::class.java).flattenToString()

        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName &&
                service.id.contains(expectedComponentName)
            ) {
                return true
            }
        }
        return false
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, SmartGuardianAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }
}
