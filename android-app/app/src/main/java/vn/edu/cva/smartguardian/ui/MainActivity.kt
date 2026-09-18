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
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import vn.edu.cva.smartguardian.R
import vn.edu.cva.smartguardian.receiver.SmartGuardianAdminReceiver
import vn.edu.cva.smartguardian.service.GuardianAccessibilityService
import vn.edu.cva.smartguardian.service.SafeVpnFilterService
import vn.edu.cva.smartguardian.service.UsageTrackerService
import vn.edu.cva.smartguardian.update.AppUpdateManager
import vn.edu.cva.smartguardian.update.UpdateInfo

class MainActivity : AppCompatActivity() {

    // 1. Màn hình Onboarding
    private lateinit var layoutOnboarding: View
    private lateinit var etPairingCodeInput: EditText
    private lateinit var btnConnectPairing: Button
    private lateinit var pbPairingLoading: ProgressBar
    private lateinit var tvPairingStatus: TextView
    private lateinit var tvMyDevicePin: TextView
    private lateinit var btnXiaomiHelp: TextView

    // 2. Màn hình Dashboard Chính
    private lateinit var layoutDashboard: View
    private lateinit var tvGreeting: TextView
    private lateinit var tvCompanionBadge: TextView
    private lateinit var tvLiveStatusBadge: TextView
    private lateinit var tvStudyTime: TextView
    private lateinit var tvGameTime: TextView
    private lateinit var tvBalanceScore: TextView
    private lateinit var tvTotalScreenTime: TextView
    private lateinit var btnSyncNow: Button

    private lateinit var cardSetupPermissions: View
    private lateinit var btnPermissionUsage: Button
    private lateinit var btnPermissionAccessibility: Button
    private lateinit var btnToggleVpn: Button
    private lateinit var btnPermissionAdmin: Button
    private lateinit var btnDashboardXiaomiHelp: TextView
    private lateinit var btnCheckUpdate: Button

    private var isVpnRunning = false
    private val firebaseClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val FIREBASE_RTDB_URL = "https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app"

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

        // 1. Kiểm tra cập nhật ngay lập tức khi mở app
        performUpdateCheck(userInitiated = false)

        // 2. Kiểm tra trạng thái ghép đôi
        val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
        val isPaired = prefs.getBoolean("is_paired", false)

        if (isPaired) {
            showDashboardScreen()
            if (hasUsageStatsPermission()) {
                UsageTrackerService.start(this)
            }
        } else {
            showOnboardingScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        checkAllPermissions()

        val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("is_paired", false)) {
            if (hasUsageStatsPermission()) {
                UsageTrackerService.start(this)
                lifecycleScope.launch(Dispatchers.IO) {
                    UsageTrackerService.collectAndSave(this@MainActivity)
                    withContext(Dispatchers.Main) {
                        loadUsageStatsFromPrefs()
                    }
                }
            } else {
                loadUsageStatsFromPrefs()
            }
        }

        val filter = IntentFilter(UsageTrackerService.ACTION_USAGE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usageUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usageUpdateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(usageUpdateReceiver)
        } catch (_: Exception) {}
    }

    private fun initViews() {
        layoutOnboarding = findViewById(R.id.layoutOnboarding)
        etPairingCodeInput = findViewById(R.id.etPairingCodeInput)
        btnConnectPairing = findViewById(R.id.btnConnectPairing)
        pbPairingLoading = findViewById(R.id.pbPairingLoading)
        tvPairingStatus = findViewById(R.id.tvPairingStatus)
        tvMyDevicePin = findViewById(R.id.tvMyDevicePin)
        btnXiaomiHelp = findViewById(R.id.btnXiaomiHelp)

        layoutDashboard = findViewById(R.id.layoutDashboard)
        tvGreeting = findViewById(R.id.tvGreeting)
        tvCompanionBadge = findViewById(R.id.tvCompanionBadge)
        tvLiveStatusBadge = findViewById(R.id.tvLiveStatusBadge)
        tvStudyTime = findViewById(R.id.tvStudyTime)
        tvGameTime = findViewById(R.id.tvGameTime)
        tvBalanceScore = findViewById(R.id.tvBalanceScore)
        tvTotalScreenTime = findViewById(R.id.tvTotalScreenTime)
        btnSyncNow = findViewById(R.id.btnSyncNow)

        cardSetupPermissions = findViewById(R.id.cardSetupPermissions)
        btnPermissionUsage = findViewById(R.id.btnPermissionUsage)
        btnPermissionAccessibility = findViewById(R.id.btnPermissionAccessibility)
        btnToggleVpn = findViewById(R.id.btnToggleVpn)
        btnPermissionAdmin = findViewById(R.id.btnPermissionAdmin)
        btnDashboardXiaomiHelp = findViewById(R.id.btnDashboardXiaomiHelp)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)

        val myPin = getMyDevicePin()
        tvMyDevicePin.text = "Mã thiết bị của máy này: $myPin"
    }

    private fun getMyDevicePin(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "DEVICE"
        val shortId = if (androidId.length >= 4) androidId.takeLast(4).uppercase() else "8A20"
        return "CVA-$shortId"
    }

    private fun showOnboardingScreen() {
        layoutOnboarding.visibility = View.VISIBLE
        layoutDashboard.visibility = View.GONE
    }

    private fun showDashboardScreen() {
        layoutOnboarding.visibility = View.GONE
        layoutDashboard.visibility = View.VISIBLE

        val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
        val pairedCode = prefs.getString("paired_code", getMyDevicePin()) ?: getMyDevicePin()
        tvCompanionBadge.text = "🛡️ Đã kết nối Phụ huynh ($pairedCode) • Đang đồng hành"
        tvLiveStatusBadge.text = "ĐANG ĐỒNG HÀNH"

        loadUsageStatsFromPrefs()
    }

    private fun setupListeners() {
        // Bấm nút Kết Nối Ghép Đôi Siêu Tốc
        btnConnectPairing.setOnClickListener {
            handleConnectPairing()
        }

        // Bấm nút Đồng Bộ Nhanh
        btnSyncNow.setOnClickListener {
            Toast.makeText(this, "Đang đồng bộ dữ liệu thời gian thực...", Toast.LENGTH_SHORT).show()
            if (hasUsageStatsPermission()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    UsageTrackerService.collectAndSave(this@MainActivity)
                    withContext(Dispatchers.Main) {
                        loadUsageStatsFromPrefs()
                        Toast.makeText(this@MainActivity, "Đã cập nhật số liệu mới nhất!", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                loadUsageStatsFromPrefs()
            }
        }

        // Bấm Trợ giúp Xiaomi
        btnXiaomiHelp.setOnClickListener { showXiaomiHelpDialog() }
        btnDashboardXiaomiHelp.setOnClickListener { showXiaomiHelpDialog() }

        btnPermissionUsage.setOnClickListener {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        }

        btnPermissionAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnToggleVpn.setOnClickListener {
            toggleVpn()
        }

        btnPermissionAdmin.setOnClickListener {
            requestDeviceAdmin()
        }

        btnCheckUpdate.setOnClickListener {
            performUpdateCheck(userInitiated = true)
        }
    }

    private fun handleConnectPairing() {
        val inputCode = etPairingCodeInput.text.toString().trim().uppercase()
        if (inputCode.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập mã ghép đôi do Phụ huynh cung cấp!", Toast.LENGTH_SHORT).show()
            return
        }

        // Ẩn bàn phím
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etPairingCodeInput.windowToken, 0)

        pbPairingLoading.visibility = View.VISIBLE
        btnConnectPairing.isEnabled = false
        tvPairingStatus.visibility = View.VISIBLE
        tvPairingStatus.setTextColor(getColor(R.color.accent_gold))
        tvPairingStatus.text = "Đang kết nối đám mây..."

        lifecycleScope.launch(Dispatchers.IO) {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "DEVICE"
            val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            val androidVer = "Android ${Build.VERSION.RELEASE}"
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()

            try {
                // Ghi nhận ghép đôi thành công ngay lên Firebase (< 200ms)
                val pairingData = JSONObject().apply {
                    put("pairingCode", inputCode)
                    put("studentDeviceId", androidId)
                    put("deviceModel", "$manufacturer $model")
                    put("androidVersion", androidVer)
                    put("status", "paired")
                    put("pairedAt", System.currentTimeMillis())
                    put("lastSeen", System.currentTimeMillis())
                }
                val putPairingReq = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/pairings/$inputCode.json")
                    .put(pairingData.toString().toRequestBody(jsonMediaType))
                    .build()

                firebaseClient.newCall(putPairingReq).execute().close()

                // Lưu trạng thái đã ghép đôi vào máy
                val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("is_paired", true)
                    .putString("paired_code", inputCode)
                    .apply()

                withContext(Dispatchers.Main) {
                    pbPairingLoading.visibility = View.GONE
                    btnConnectPairing.isEnabled = true
                    tvPairingStatus.setTextColor(getColor(R.color.success))
                    tvPairingStatus.text = "✅ Ghép đôi thành công!"

                    Toast.makeText(this@MainActivity, "Ghép đôi thành công!", Toast.LENGTH_SHORT).show()

                    // Chuyển sang Màn hình chính
                    showDashboardScreen()

                    if (hasUsageStatsPermission()) {
                        UsageTrackerService.start(this@MainActivity)
                    }
                    loadUsageStatsFromPrefs()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pbPairingLoading.visibility = View.GONE
                    btnConnectPairing.isEnabled = true
                    tvPairingStatus.setTextColor(getColor(R.color.danger))
                    tvPairingStatus.text = "Lỗi kết nối: ${e.message}"
                    Toast.makeText(this@MainActivity, "Lỗi kết nối: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showXiaomiHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Hướng Dẫn Cấp Quyền Cho Xiaomi / Android 14")
            .setMessage("Do cơ chế bảo mật của Android 14 và Xiaomi HyperOS đối với ứng dụng cài đặt ngoài CH Play:\n\n" +
                    "1. Bấm nút dưới để mở 'Thông Tin Ứng Dụng'.\n" +
                    "2. Bấm vào biểu tượng dấu 3 chấm (⋮) ở góc trên bên phải màn hình.\n" +
                    "3. Chọn 'Cho phép cài đặt bị hạn chế' (Allow restricted settings).\n" +
                    "4. Xác nhận 10 giây bảo mật.\n" +
                    "5. Sau đó quay lại ứng dụng này để gạt bật quyền Trợ năng và Đo lường bình thường 100%!")
            .setPositiveButton("Mở Cài Đặt Ứng Dụng") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Không thể mở cài đặt", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Đã Hiểu", null)
            .show()
    }

    private fun checkAllPermissions() {
        val hasUsage = hasUsageStatsPermission()
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasAdmin = isDeviceAdminActive()

        if (hasUsage) {
            btnPermissionUsage.text = "✓ Đã Cấp"
            btnPermissionUsage.isEnabled = false
            btnPermissionUsage.setBackgroundColor(getColor(R.color.success))
        } else {
            btnPermissionUsage.text = "Cấp Quyền"
            btnPermissionUsage.isEnabled = true
            btnPermissionUsage.setBackgroundColor(getColor(R.color.primary))
        }

        if (hasAccessibility) {
            btnPermissionAccessibility.text = "✓ Đã Bật"
            btnPermissionAccessibility.isEnabled = false
            btnPermissionAccessibility.setBackgroundColor(getColor(R.color.success))
        } else {
            btnPermissionAccessibility.text = "Cấp Quyền"
            btnPermissionAccessibility.isEnabled = true
            btnPermissionAccessibility.setBackgroundColor(getColor(R.color.primary))
        }

        if (hasAdmin) {
            btnPermissionAdmin.text = "✓ Đã Bật"
            btnPermissionAdmin.isEnabled = false
            btnPermissionAdmin.setBackgroundColor(getColor(R.color.success))
        } else {
            btnPermissionAdmin.text = "Kích Hoạt"
            btnPermissionAdmin.isEnabled = true
            btnPermissionAdmin.setBackgroundColor(getColor(R.color.primary))
        }

        if (hasUsage && hasAccessibility && hasAdmin) {
            cardSetupPermissions.visibility = View.GONE
        } else {
            cardSetupPermissions.visibility = View.VISIBLE
        }
    }

    private fun loadUsageStatsFromPrefs() {
        val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
        val studyMs = prefs.getLong("study_time_ms", 0L)
        val gameMs = prefs.getLong("game_time_ms", 0L)
        val socialMs = prefs.getLong("social_time_ms", 0L)
        val totalMs = prefs.getLong("total_screen_time_ms", 0L)
        val score = prefs.getInt("balance_score", 100)

        val studyMinutes = (studyMs / 1000 / 60).toInt()
        val gameAndSocialMinutes = ((gameMs + socialMs) / 1000 / 60).toInt()
        val totalMinutes = (totalMs / 1000 / 60).toInt()

        tvStudyTime.text = "${studyMinutes}p"
        tvGameTime.text = "${gameAndSocialMinutes}p"
        tvBalanceScore.text = "$score"

        val hours = totalMinutes / 60
        val remainingMinutes = totalMinutes % 60
        tvTotalScreenTime.text = "Tổng thời gian sáng màn hình: ${hours} giờ ${remainingMinutes} phút"

        syncWithFirebase()
    }

    private fun syncWithFirebase() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
                val isPaired = prefs.getBoolean("is_paired", false)
                val pairedCode = prefs.getString("paired_code", getMyDevicePin()) ?: getMyDevicePin()
                val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "DEVICE"
                val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
                val model = Build.MODEL
                val androidVer = "Android ${Build.VERSION.RELEASE}"

                val studyMs = prefs.getLong("study_time_ms", 0L)
                val gameMs = prefs.getLong("game_time_ms", 0L)
                val socialMs = prefs.getLong("social_time_ms", 0L)
                val totalMs = prefs.getLong("total_screen_time_ms", 0L)
                val score = prefs.getInt("balance_score", 100)

                val studyMinutes = (studyMs / 1000 / 60).toInt()
                val gameAndSocialMinutes = ((gameMs + socialMs) / 1000 / 60).toInt()
                val totalMinutes = (totalMs / 1000 / 60).toInt()

                val jsonMediaType = "application/json; charset=utf-8".toMediaType()

                // Đẩy thống kê thời gian thực lên Firebase node /devices/$pairedCode.json
                val deviceStats = JSONObject().apply {
                    put("pairingCode", pairedCode)
                    put("deviceId", androidId)
                    put("deviceModel", "$manufacturer $model")
                    put("androidVersion", androidVer)
                    put("totalMinutes", totalMinutes)
                    put("studyMinutes", studyMinutes)
                    put("gameAndSocialMinutes", gameAndSocialMinutes)
                    put("balanceScore", score)
                    put("isPaired", isPaired)
                    put("lastSync", System.currentTimeMillis())
                    put("online", true)
                }
                val putStatsReq = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/devices/$pairedCode.json")
                    .put(deviceStats.toString().toRequestBody(jsonMediaType))
                    .build()
                firebaseClient.newCall(putStatsReq).execute().close()
            } catch (e: Exception) {
                Log.w("MainActivity", "Firebase sync telemetry warning: ${e.message}")
            }
        }
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
            @Suppress("DEPRECATION")
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
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        val myServiceName = ComponentName(this, GuardianAccessibilityService::class.java).flattenToString()
        for (service in enabledServices) {
            if (service.id.equals(myServiceName, ignoreCase = true)) {
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

    private fun requestDeviceAdmin() {
        val adminComponent = ComponentName(this, SmartGuardianAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "CVA-SmartGuardian cần quyền Quản trị viên để ngăn học sinh tự ý gỡ bỏ ứng dụng khi chưa có sự đồng ý của cha mẹ."
            )
        }
        startActivity(intent)
    }

    private fun toggleVpn() {
        if (isVpnRunning) {
            stopSafeVpn()
        } else {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnLauncher.launch(vpnIntent)
            } else {
                startSafeVpn()
            }
        }
    }

    private fun startSafeVpn() {
        val intent = Intent(this, SafeVpnFilterService::class.java).apply {
            action = SafeVpnFilterService.ACTION_START_VPN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isVpnRunning = true
        btnToggleVpn.text = "✓ Đang Chặn"
        btnToggleVpn.setBackgroundColor(getColor(R.color.success))
        Toast.makeText(this, "Đã kích hoạt tường lửa lọc DNS an toàn!", Toast.LENGTH_SHORT).show()
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

    private fun performUpdateCheck(userInitiated: Boolean) {
        lifecycleScope.launch {
            if (userInitiated) {
                Toast.makeText(this@MainActivity, "Đang kết nối máy chủ kiểm tra cập nhật...", Toast.LENGTH_SHORT).show()
            }
            val updateInfo = AppUpdateManager.checkForUpdate(this@MainActivity)
            if (updateInfo != null) {
                showUpdateAvailableDialog(updateInfo)
            } else if (userInitiated) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Thông Báo")
                    .setMessage("Ứng dụng CVA-SmartGuardian của bạn đang là phiên bản mới nhất (v1.0.2)!")
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
            .setMessage("Đã có phiên bản mới$sizeText. Bạn có muốn tải về và cài đặt ngay không?$changelogText")
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
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Đang Tải Bản Cập Nhật")
            .setMessage("Vui lòng đợi trong giây lát...\nTiến độ: 0%")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val result = AppUpdateManager.downloadAndVerifyApk(
                context = this@MainActivity,
                updateInfo = updateInfo,
                onProgress = { progress ->
                    progressDialog.setMessage("Vui lòng đợi trong giây lát...\nTiến độ: $progress%")
                }
            )

            progressDialog.dismiss()

            result.onSuccess { apkFile ->
                AppUpdateManager.installApk(this@MainActivity, apkFile)
            }.onFailure { e ->
                Toast.makeText(
                    this@MainActivity,
                    "Tải bản cập nhật thất bại: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
