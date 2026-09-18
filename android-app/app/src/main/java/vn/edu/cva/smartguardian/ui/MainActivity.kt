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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import vn.edu.cva.smartguardian.R
import vn.edu.cva.smartguardian.receiver.SmartGuardianAdminReceiver
import vn.edu.cva.smartguardian.service.GuardianAccessibilityService
import vn.edu.cva.smartguardian.service.SafeVpnFilterService
import vn.edu.cva.smartguardian.service.UsageTrackerService

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
    }

    private fun setupListeners() {
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
            val dashboardUrl = "http://10.0.2.2:8100/khkt-smart-guardian/src/parent-dashboard/index.html"
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl))
            try {
                startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Không thể mở trình duyệt: $dashboardUrl", Toast.LENGTH_SHORT).show()
            }
        }
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
