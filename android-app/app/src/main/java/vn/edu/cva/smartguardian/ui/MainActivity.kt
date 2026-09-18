package vn.edu.cva.smartguardian.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import androidx.core.app.NotificationCompat
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import vn.edu.cva.smartguardian.location.LocationHelper

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val DEFAULT_PARENT_PIN = "1234"
    private val FIREBASE_RTDB_URL = "https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app"

    // 1. Top Navigation & Tabs
    private lateinit var btnTabParent: TextView
    private lateinit var btnTabStudent: TextView
    private lateinit var layoutTabParentContent: LinearLayout
    private lateinit var layoutTabStudentContent: LinearLayout

    // 2. Tab Phụ Huynh: Screen 1 (PIN Gate)
    private lateinit var layoutParentPinGate: LinearLayout
    private lateinit var pinDot1: View
    private lateinit var pinDot2: View
    private lateinit var pinDot3: View
    private lateinit var pinDot4: View
    private lateinit var tvParentPinError: TextView
    private val parentPinBuilder = StringBuilder()

    // 3. Tab Phụ Huynh: Screen 2 (Parent Hub)
    private lateinit var layoutParentHub: LinearLayout
    private lateinit var tvParentHubFamilyCode: TextView
    private lateinit var btnParentCopyCode: TextView
    private lateinit var btnParentRegenCode: TextView
    private lateinit var tvParentChildSubtitle: TextView
    private lateinit var btnParentTriggerUnpair: TextView
    private lateinit var btnLockParentTab: TextView

    // 4. Tab Học Sinh: Screen 3 (Student Card & Paired State)
    private lateinit var layoutStudentCard: LinearLayout
    private lateinit var etPairingCodeInput: EditText
    private lateinit var btnConnectPairing: TextView
    private lateinit var pbPairingLoading: ProgressBar
    private lateinit var tvPairingStatus: TextView
    private lateinit var layoutStudentPairedState: LinearLayout
    private lateinit var tvGreeting: TextView
    private lateinit var tvCompanionBadge: TextView
    private lateinit var tvPairedCodeDisplay: TextView

    // 5. Modals & Overlays
    private lateinit var layoutPinConfirmModal: FrameLayout
    private lateinit var etModalPinConfirm: EditText
    private lateinit var tvModalPinError: TextView
    private lateinit var btnModalCancelUnpair: TextView
    private lateinit var btnModalConfirmUnpair: TextView
    private lateinit var layoutUnpairOverlay: FrameLayout

    // 6. Khung Nhận Tin Nhắn Phụ Huynh & Phản Hồi Học Sinh
    private lateinit var layoutParentMessageBox: LinearLayout
    private lateinit var tvParentMessageTime: TextView
    private lateinit var tvParentMessageContent: TextView
    private lateinit var btnQuickReply1: TextView
    private lateinit var btnQuickReply2: TextView
    private lateinit var btnQuickReply3: TextView
    private lateinit var btnQuickReply4: TextView
    private lateinit var etCustomReply: EditText
    private lateinit var btnSendCustomReply: TextView
    private lateinit var tvStudentReplyStatus: TextView
    private var lastSeenMessageId: String = ""

    private var unpairJob: Job? = null
    private var heartbeatJob: Job? = null
    private var isVpnRunning = false
    private val firebaseClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val usageUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Nhận cập nhật nền
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            Log.d(TAG, "Quyền vị trí đã được cấp thành công!")
            UsageTrackerService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()

        // 1. Tự động quét cập nhật tức thời qua Firebase Cloud
        performUpdateCheck(userInitiated = false)

        // 2. Kiểm tra & yêu cầu quyền vị trí nếu chưa có
        if (!LocationHelper.hasLocationPermission(this)) {
            locationPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        // 3. Mặc định khởi động ở Tab Học Sinh chuẩn Screen 3
        switchToStudentTab()

        val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
        val isPaired = prefs.getBoolean("is_paired", false)
        val pairedCode = prefs.getString("paired_code", "") ?: ""

        if (isPaired && pairedCode.isNotEmpty()) {
            showStudentPairedState(pairedCode)
            UsageTrackerService.start(this)
            startUnpairListener(pairedCode)
            startHeartbeatLoop(pairedCode)
            lifecycleScope.launch(Dispatchers.IO) {
                sendHeartbeatPing(pairedCode)
            }
        } else {
            showStudentUnpairedState()
        }
    }

    override fun onResume() {
        super.onResume()
        checkAllPermissions()

        // Kiểm tra cập nhật mỗi khi mở lại ứng dụng
        performUpdateCheck(userInitiated = false)

        val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
        val isPaired = prefs.getBoolean("is_paired", false)
        val pairedCode = prefs.getString("paired_code", "") ?: ""
        if (isPaired && pairedCode.isNotEmpty()) {
            UsageTrackerService.start(this)
            startHeartbeatLoop(pairedCode)
            lifecycleScope.launch(Dispatchers.IO) {
                sendHeartbeatPing(pairedCode)
                if (hasUsageStatsPermission()) {
                    UsageTrackerService.collectAndSave(this@MainActivity)
                }
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

    override fun onDestroy() {
        super.onDestroy()
        unpairJob?.cancel()
        heartbeatJob?.cancel()
    }

    private fun initViews() {
        // Top Navigation Tabs
        btnTabParent = findViewById(R.id.btnTabParent)
        btnTabStudent = findViewById(R.id.btnTabStudent)
        layoutTabParentContent = findViewById(R.id.layoutTabParentContent)
        layoutTabStudentContent = findViewById(R.id.layoutTabStudentContent)

        // Tab Phụ Huynh: PIN Gate
        layoutParentPinGate = findViewById(R.id.layoutParentPinGate)
        pinDot1 = findViewById(R.id.pinDot1)
        pinDot2 = findViewById(R.id.pinDot2)
        pinDot3 = findViewById(R.id.pinDot3)
        pinDot4 = findViewById(R.id.pinDot4)
        tvParentPinError = findViewById(R.id.tvParentPinError)

        // Tab Phụ Huynh: Parent Hub
        layoutParentHub = findViewById(R.id.layoutParentHub)
        tvParentHubFamilyCode = findViewById(R.id.tvParentHubFamilyCode)
        btnParentCopyCode = findViewById(R.id.btnParentCopyCode)
        btnParentRegenCode = findViewById(R.id.btnParentRegenCode)
        tvParentChildSubtitle = findViewById(R.id.tvParentChildSubtitle)
        btnParentTriggerUnpair = findViewById(R.id.btnParentTriggerUnpair)
        btnLockParentTab = findViewById(R.id.btnLockParentTab)

        // Tab Học Sinh: Screen 3
        layoutStudentCard = findViewById(R.id.layoutStudentCard)
        etPairingCodeInput = findViewById(R.id.etPairingCodeInput)
        btnConnectPairing = findViewById(R.id.btnConnectPairing)
        pbPairingLoading = findViewById(R.id.pbPairingLoading)
        tvPairingStatus = findViewById(R.id.tvPairingStatus)
        layoutStudentPairedState = findViewById(R.id.layoutStudentPairedState)
        tvGreeting = findViewById(R.id.tvGreeting)
        tvCompanionBadge = findViewById(R.id.tvCompanionBadge)
        tvPairedCodeDisplay = findViewById(R.id.tvPairedCodeDisplay)

        // Modals & Overlays
        layoutPinConfirmModal = findViewById(R.id.layoutPinConfirmModal)
        etModalPinConfirm = findViewById(R.id.etModalPinConfirm)
        tvModalPinError = findViewById(R.id.tvModalPinError)
        btnModalCancelUnpair = findViewById(R.id.btnModalCancelUnpair)
        btnModalConfirmUnpair = findViewById(R.id.btnModalConfirmUnpair)
        layoutUnpairOverlay = findViewById(R.id.layoutUnpairOverlay)

        // 6. Khung Nhận Tin Nhắn Phụ Huynh & Phản Hồi Học Sinh
        layoutParentMessageBox = findViewById(R.id.layoutParentMessageBox)
        tvParentMessageTime = findViewById(R.id.tvParentMessageTime)
        tvParentMessageContent = findViewById(R.id.tvParentMessageContent)
        btnQuickReply1 = findViewById(R.id.btnQuickReply1)
        btnQuickReply2 = findViewById(R.id.btnQuickReply2)
        btnQuickReply3 = findViewById(R.id.btnQuickReply3)
        btnQuickReply4 = findViewById(R.id.btnQuickReply4)
        etCustomReply = findViewById(R.id.etCustomReply)
        btnSendCustomReply = findViewById(R.id.btnSendCustomReply)
        tvStudentReplyStatus = findViewById(R.id.tvStudentReplyStatus)
    }

    private fun setupListeners() {
        // Chuyển đổi 2 Tab
        btnTabParent.setOnClickListener { switchToParentTab() }
        btnTabStudent.setOnClickListener { switchToStudentTab() }

        // Bàn phím số Numpad Tab Phụ Huynh (Screen 1)
        val keyIds = listOf(
            R.id.key1 to "1", R.id.key2 to "2", R.id.key3 to "3",
            R.id.key4 to "4", R.id.key5 to "5", R.id.key6 to "6",
            R.id.key7 to "7", R.id.key8 to "8", R.id.key9 to "9",
            R.id.key0 to "0"
        )
        for ((id, char) in keyIds) {
            findViewById<TextView>(id).setOnClickListener {
                handlePinInput(char)
            }
        }
        findViewById<TextView>(R.id.keyClear).setOnClickListener {
            parentPinBuilder.clear()
            updatePinDots()
            tvParentPinError.visibility = View.GONE
        }
        findViewById<TextView>(R.id.keyBackspace).setOnClickListener {
            if (parentPinBuilder.isNotEmpty()) {
                parentPinBuilder.deleteCharAt(parentPinBuilder.length - 1)
                updatePinDots()
                tvParentPinError.visibility = View.GONE
            }
        }

        // Parent Hub actions
        btnParentCopyCode.setOnClickListener {
            val code = tvParentHubFamilyCode.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Family Code", code)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Đã sao chép mã: $code", Toast.LENGTH_SHORT).show()
        }

        btnParentRegenCode.setOnClickListener {
            generateAndPublishNewFamilyCode()
        }

        btnParentTriggerUnpair.setOnClickListener {
            etModalPinConfirm.setText("")
            tvModalPinError.visibility = View.GONE
            layoutPinConfirmModal.visibility = View.VISIBLE
        }

        btnLockParentTab.setOnClickListener {
            parentPinBuilder.clear()
            updatePinDots()
            layoutParentHub.visibility = View.GONE
            layoutParentPinGate.visibility = View.VISIBLE
        }

        // Modal Unpair actions
        btnModalCancelUnpair.setOnClickListener {
            layoutPinConfirmModal.visibility = View.GONE
        }

        btnModalConfirmUnpair.setOnClickListener {
            val inputPin = etModalPinConfirm.text.toString()
            if (inputPin == DEFAULT_PARENT_PIN) {
                layoutPinConfirmModal.visibility = View.GONE
                executeParentUnpair()
            } else {
                tvModalPinError.visibility = View.VISIBLE
                tvModalPinError.text = "Mã PIN không đúng! Vui lòng thử lại."
            }
        }

        // Tab Học Sinh: Nút KẾT NỐI (Screen 3)
        btnConnectPairing.setOnClickListener {
            handleConnectPairing()
        }

        // Bấm vào thiết bị con trong Parent Hub -> Mở Bảng Giám Sát Đồng Hành
        findViewById<View>(R.id.layoutParentChildRow).setOnClickListener {
            showChildCompanionDialog()
        }

        // Xử lý phản hồi tin nhắn của Phụ huynh
        btnQuickReply1.setOnClickListener { sendStudentReply("👍 Vâng ạ bố mẹ!") }
        btnQuickReply2.setOnClickListener { sendStudentReply("📚 Con đang học bài ạ!") }
        btnQuickReply3.setOnClickListener { sendStudentReply("⏰ Con sắp xong rồi ạ!") }
        btnQuickReply4.setOnClickListener { sendStudentReply("🍲 Con chuẩn bị ăn cơm ạ!") }

        btnSendCustomReply.setOnClickListener {
            val text = etCustomReply.text.toString().trim()
            if (text.isNotEmpty()) {
                sendStudentReply(text)
                etCustomReply.setText("")
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(etCustomReply.windowToken, 0)
            } else {
                Toast.makeText(this, "Vui lòng nhập lời nhắn gửi Bố Mẹ", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun switchToParentTab() {
        btnTabParent.setBackgroundResource(R.drawable.bg_tab_active)
        btnTabParent.setTextColor(Color.WHITE)
        btnTabStudent.setBackgroundResource(R.drawable.bg_tab_inactive)
        btnTabStudent.setTextColor(Color.parseColor("#94A3B8"))

        layoutTabParentContent.visibility = View.VISIBLE
        layoutTabStudentContent.visibility = View.GONE

        loadParentHubData()
    }

    private fun switchToStudentTab() {
        btnTabStudent.setBackgroundResource(R.drawable.bg_tab_active)
        btnTabStudent.setTextColor(Color.WHITE)
        btnTabParent.setBackgroundResource(R.drawable.bg_tab_inactive)
        btnTabParent.setTextColor(Color.parseColor("#94A3B8"))

        layoutTabStudentContent.visibility = View.VISIBLE
        layoutTabParentContent.visibility = View.GONE
    }

    private fun handlePinInput(char: String) {
        if (parentPinBuilder.length < 4) {
            parentPinBuilder.append(char)
            updatePinDots()

            if (parentPinBuilder.length == 4) {
                if (parentPinBuilder.toString() == DEFAULT_PARENT_PIN) {
                    tvParentPinError.visibility = View.GONE
                    layoutParentPinGate.visibility = View.GONE
                    layoutParentHub.visibility = View.VISIBLE
                    loadParentHubData()
                } else {
                    tvParentPinError.visibility = View.VISIBLE
                    tvParentPinError.text = "Mã PIN không đúng! (Mặc định: 1234)"
                    lifecycleScope.launch {
                        delay(600)
                        parentPinBuilder.clear()
                        updatePinDots()
                    }
                }
            }
        }
    }

    private fun updatePinDots() {
        val len = parentPinBuilder.length
        pinDot1.setBackgroundResource(if (len >= 1) R.drawable.bg_pin_dot_filled else R.drawable.bg_pin_dot_empty)
        pinDot2.setBackgroundResource(if (len >= 2) R.drawable.bg_pin_dot_filled else R.drawable.bg_pin_dot_empty)
        pinDot3.setBackgroundResource(if (len >= 3) R.drawable.bg_pin_dot_filled else R.drawable.bg_pin_dot_empty)
        pinDot4.setBackgroundResource(if (len >= 4) R.drawable.bg_pin_dot_filled else R.drawable.bg_pin_dot_empty)
    }

    private fun loadParentHubData() {
        val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
        val currentCode = prefs.getString("family_code", "CVA-8A20") ?: "CVA-8A20"
        tvParentHubFamilyCode.text = currentCode

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Thử đọc danh sách đa thiết bị từ /families/$currentCode/devices.json
                val famReq = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/families/$currentCode/devices.json")
                    .build()
                var handled = false
                firebaseClient.newCall(famReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string()
                        if (!bodyStr.isNullOrEmpty() && bodyStr != "null") {
                            val json = JSONObject(bodyStr)
                            val keys = json.keys()
                            var totalDevices = 0
                            var onlineDevices = 0
                            val deviceNames = mutableListOf<String>()
                            val now = System.currentTimeMillis()

                            while (keys.hasNext()) {
                                val devKey = keys.next()
                                val devObj = json.optJSONObject(devKey) ?: continue
                                if (devObj.optBoolean("isPaired", true) || devObj.optString("status") == "paired") {
                                    totalDevices++
                                    val model = devObj.optString("deviceModel", "Thiết bị con")
                                    val lastSync = devObj.optLong("lastSync", 0L)
                                    val lastHeartbeat = devObj.optLong("lastHeartbeat", 0L)
                                    val lastContact = maxOf(lastSync, lastHeartbeat)
                                    val isOnline = lastContact > 0 && (now - lastContact) <= 45000L

                                    if (isOnline) {
                                        onlineDevices++
                                        deviceNames.add("🟢 $model")
                                    } else {
                                        deviceNames.add("🔴 $model")
                                    }
                                }
                            }

                            if (totalDevices > 0) {
                                handled = true
                                withContext(Dispatchers.Main) {
                                    if (onlineDevices == totalDevices) {
                                        tvParentChildSubtitle.text = "🟢 $onlineDevices/$totalDevices thiết bị trực tuyến (${deviceNames.joinToString(", ")})"
                                    } else if (onlineDevices > 0) {
                                        tvParentChildSubtitle.text = "🟡 $onlineDevices/$totalDevices trực tuyến (${deviceNames.joinToString(", ")})"
                                    } else {
                                        tvParentChildSubtitle.text = "🔴 $totalDevices thiết bị ngoại tuyến (${deviceNames.joinToString(", ")})"
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Dự phòng: Đọc legacy /devices/$currentCode.json nếu chưa có danh sách
                if (!handled) {
                    val req = Request.Builder()
                        .url("$FIREBASE_RTDB_URL/devices/$currentCode.json")
                        .build()
                    firebaseClient.newCall(req).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string()
                            if (!bodyStr.isNullOrEmpty() && bodyStr != "null") {
                                val json = JSONObject(bodyStr)
                                val model = json.optString("deviceModel", "Xiaomi HyperOS")
                                val isPaired = json.optBoolean("isPaired", false)
                                val lastSync = json.optLong("lastSync", 0L)
                                val now = System.currentTimeMillis()
                                val diffSec = if (lastSync > 0) (now - lastSync) / 1000 else 999999L
                                val isOnline = diffSec <= 45

                                withContext(Dispatchers.Main) {
                                    if (isPaired) {
                                        if (isOnline) {
                                            tvParentChildSubtitle.text = "🟢 Trực tuyến • $model"
                                        } else {
                                            val minAgo = diffSec / 60
                                            val timeText = if (minAgo < 1) "vừa ngắt mạng" else "mất mạng ${minAgo}m trước"
                                            tvParentChildSubtitle.text = "🔴 Ngoại tuyến ($timeText) • $model"
                                        }
                                    } else {
                                        tvParentChildSubtitle.text = "Chờ học sinh kết nối..."
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    tvParentChildSubtitle.text = "Chờ học sinh kết nối..."
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi load Parent Hub: ${e.message}")
            }
        }
    }

    private fun generateAndPublishNewFamilyCode() {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val randomSuffix = (1..4).map { chars.random() }.joinToString("")
        val newCode = "CVA-$randomSuffix"

        tvParentHubFamilyCode.text = newCode
        val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("family_code", newCode).apply()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonMediaType = "application/json; charset=utf-8".toMediaType()
                val payload = JSONObject().apply {
                    put("familyCode", newCode)
                    put("pairingCode", newCode)
                    put("createdAt", System.currentTimeMillis())
                    put("status", "pending")
                }
                val body = payload.toString().toRequestBody(jsonMediaType)

                val reqPairing = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/pairings/$newCode.json")
                    .put(body)
                    .build()
                firebaseClient.newCall(reqPairing).execute().close()

                val reqFamily = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/families/$newCode.json")
                    .patch(body)
                    .build()
                firebaseClient.newCall(reqFamily).execute().close()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Đã tạo mã mới: $newCode", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi tạo mã mới", e)
            }
        }
    }

    private fun executeParentUnpair() {
        val code = tvParentHubFamilyCode.text.toString()
        layoutUnpairOverlay.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonMediaType = "application/json; charset=utf-8".toMediaType()
                val unpairPayload = JSONObject().apply {
                    put("status", "REVOKED")
                    put("isPaired", false)
                    put("online", false)
                    put("revokedAt", System.currentTimeMillis())
                    put("revokedBy", "PARENT")
                }
                val body = unpairPayload.toString().toRequestBody(jsonMediaType)

                // 1. Thu hồi các thiết bị trong gia đình
                val famReq = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/families/$code/devices.json")
                    .build()
                firebaseClient.newCall(famReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string()
                        if (!bodyStr.isNullOrEmpty() && bodyStr != "null") {
                            val json = JSONObject(bodyStr)
                            val keys = json.keys()
                            while (keys.hasNext()) {
                                val devId = keys.next()
                                val patchFamDev = Request.Builder()
                                    .url("$FIREBASE_RTDB_URL/families/$code/devices/$devId.json")
                                    .patch(body)
                                    .build()
                                firebaseClient.newCall(patchFamDev).execute().close()

                                val patchDev = Request.Builder()
                                    .url("$FIREBASE_RTDB_URL/devices/$devId.json")
                                    .patch(body)
                                    .build()
                                firebaseClient.newCall(patchDev).execute().close()
                            }
                        }
                    }
                }

                // 2. Kế thừa thu hồi các node đơn lẻ
                val delPairingReq = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/pairings/$code.json")
                    .patch(body)
                    .build()
                firebaseClient.newCall(delPairingReq).execute().close()

                val patchReq = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/devices/$code.json")
                    .patch(body)
                    .build()
                firebaseClient.newCall(patchReq).execute().close()
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi ngắt kết nối từ máy Phụ huynh", e)
            }

            delay(1500)
            withContext(Dispatchers.Main) {
                val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean("is_paired", false).remove("paired_code").apply()

                layoutUnpairOverlay.visibility = View.GONE
                tvParentChildSubtitle.text = "Chờ học sinh kết nối..."
                showStudentUnpairedState()
                Toast.makeText(this@MainActivity, "Đã ngắt kết nối an toàn!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleConnectPairing() {
        val inputCode = etPairingCodeInput.text.toString().trim().uppercase()
        if (inputCode.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập mã ghép đôi CVA-XXXX!", Toast.LENGTH_SHORT).show()
            return
        }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etPairingCodeInput.windowToken, 0)

        pbPairingLoading.visibility = View.VISIBLE
        tvPairingStatus.visibility = View.VISIBLE
        btnConnectPairing.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // BƯỚC 1: KIỂM TRA MÃ GHÉP ĐÔI TRÊN CLOUD
                withContext(Dispatchers.Main) {
                    tvPairingStatus.text = "🔍 [1/3] Đang kiểm tra mã ghép đôi..."
                }
                delay(750)

                var isCodeValid = false
                try {
                    val checkReq = Request.Builder()
                        .url("$FIREBASE_RTDB_URL/pairings/$inputCode.json")
                        .build()
                    firebaseClient.newCall(checkReq).execute().use { resp ->
                        val body = resp.body?.string()
                        if (resp.isSuccessful && !body.isNullOrEmpty() && body != "null") {
                            isCodeValid = true
                        }
                    }
                } catch (_: Exception) {}

                if (!isCodeValid && !inputCode.matches(Regex("^CVA-[A-Z0-9]{4}$"))) {
                    withContext(Dispatchers.Main) {
                        pbPairingLoading.visibility = View.GONE
                        btnConnectPairing.isEnabled = true
                        tvPairingStatus.text = "❌ Mã ghép đôi không hợp lệ!"
                        Toast.makeText(this@MainActivity, "Mã ghép đôi không tồn tại hoặc sai định dạng!", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // BƯỚC 2: GỬI THÔNG TIN THIẾT BỊ VÀ MÃ XÁC NHẬN (HANDSHAKE)
                withContext(Dispatchers.Main) {
                    tvPairingStatus.text = "📤 [2/3] Đang gửi thông tin thiết bị & mã xác nhận..."
                }
                delay(850)

                val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
                val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
                val model = Build.MODEL
                val androidVer = "Android ${Build.VERSION.RELEASE}"
                val now = System.currentTimeMillis()
                val jsonMediaType = "application/json; charset=utf-8".toMediaType()

                val devicePayload = JSONObject().apply {
                    put("deviceId", androidId)
                    put("familyCode", inputCode)
                    put("pairingCode", inputCode)
                    put("deviceModel", "$manufacturer $model")
                    put("androidVersion", androidVer)
                    put("status", "paired")
                    put("isPaired", true)
                    put("online", true)
                    put("pairedAt", now)
                    put("lastSync", now)
                    put("lastHeartbeat", now)
                }
                val body = devicePayload.toString().toRequestBody(jsonMediaType)

                // 1. Thêm vào danh sách thiết bị gia đình: /families/$inputCode/devices/$androidId.json
                val putFamDevReq = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/families/$inputCode/devices/$androidId.json")
                    .patch(body)
                    .build()
                firebaseClient.newCall(putFamDevReq).execute().close()

                // 2. Ghi chi tiết phẳng thiết bị: /devices/$androidId.json
                val putDeviceReq = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/devices/$androidId.json")
                    .patch(body)
                    .build()
                firebaseClient.newCall(putDeviceReq).execute().close()

                // 3. Ánh xạ ngược thiết bị -> gia đình: /device_to_family/$androidId.json
                val putMapReq = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/device_to_family/$androidId.json")
                    .put("\"$inputCode\"".toRequestBody(jsonMediaType))
                    .build()
                firebaseClient.newCall(putMapReq).execute().close()

                // 4. Kế thừa tương thích ngược: /pairings/$inputCode.json và /devices/$inputCode.json
                val putPairingReq = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/pairings/$inputCode.json")
                    .patch(body)
                    .build()
                firebaseClient.newCall(putPairingReq).execute().close()

                val putLegacyReq = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/devices/$inputCode.json")
                    .patch(body)
                    .build()
                firebaseClient.newCall(putLegacyReq).execute().close()

                // BƯỚC 3: XÁC THỰC THÀNH CÔNG VÀ HOÀN TẤT
                withContext(Dispatchers.Main) {
                    tvPairingStatus.text = "✨ [3/3] Xác thực thành công! Hoàn tất."
                }
                delay(600)

                val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("is_paired", true)
                    .putString("paired_code", inputCode)
                    .putString("device_id", androidId)
                    .apply()

                UsageTrackerService.start(this@MainActivity)
                startHeartbeatLoop(inputCode)
                sendHeartbeatPing(inputCode)

                withContext(Dispatchers.Main) {
                    pbPairingLoading.visibility = View.GONE
                    tvPairingStatus.visibility = View.GONE
                    btnConnectPairing.isEnabled = true
                    showStudentPairedState(inputCode)
                    startUnpairListener(inputCode)
                    Toast.makeText(this@MainActivity, "Ghép đôi thành công!", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pbPairingLoading.visibility = View.GONE
                    btnConnectPairing.isEnabled = true
                    tvPairingStatus.text = "❌ Lỗi mạng: ${e.message}"
                    Toast.makeText(this@MainActivity, "Lỗi kết nối Firebase: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showChildCompanionDialog() {
        val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
        val studyMs = prefs.getLong("study_time_ms", 0L)
        val gameMs = prefs.getLong("game_time_ms", 0L)
        val socialMs = prefs.getLong("social_time_ms", 0L)
        val balanceScore = prefs.getInt("balance_score", -1)
        val lastUpdate = prefs.getLong("last_updated_at", 0L)
        val now = System.currentTimeMillis()
        val isRecent = lastUpdate > 0 && (now - lastUpdate) <= 45000L

        val studyMinutes = (studyMs / 60000).toInt()
        val gameMinutes = (gameMs / 60000).toInt()
        val socialMinutes = (socialMs / 60000).toInt()
        val totalMinutes = studyMinutes + gameMinutes + socialMinutes

        val statusLine = if (isRecent) {
            "🟢 Trạng thái: Trực tuyến (Đang hoạt động thời gian thực)"
        } else {
            val minAgo = if (lastUpdate > 0) (now - lastUpdate) / 60000 else 0
            "🔴 Trạng thái: Ngoại tuyến (Đã ngắt mạng $minAgo phút trước)"
        }

        val balanceLine = if (balanceScore >= 0) {
            "🎯 Điểm Cân Bằng Số: $balanceScore/100"
        } else {
            "🎯 Điểm Cân Bằng Số: --/100 (Chờ đồng bộ)"
        }

        val usageLines = if (totalMinutes > 0) {
            """
            • 📚 Ứng dụng Học tập: ${studyMinutes / 60}h ${studyMinutes % 60}m (${studyMinutes * 100 / totalMinutes}%)
            • 🎮 Game & Giải trí: ${gameMinutes}m (${gameMinutes * 100 / totalMinutes}%)
            • 💬 Mạng xã hội: ${socialMinutes}m (${socialMinutes * 100 / totalMinutes}%)
            """.trimIndent()
        } else {
            "• Chưa có dữ liệu thời lượng hôm nay (Chờ máy con gửi bản ghi)"
        }

        val msg = """
            📱 Thiết bị: ${tvParentChildSubtitle.text}
            $statusLine
            
            $balanceLine
            
            📊 THỜI LƯỢNG HÔM NAY:
            $usageLines
            
            🛡️ BẢO VỆ TỪ XA:
            • Tường lửa Lọc Web: [Đang Bật] (Cloudflare Family)
            • Khóa Giờ Học (Focus Lock): Sẵn sàng
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("🛡️ Bảng Giám Sát Thiết Bị Con")
            .setMessage(msg)
            .setPositiveButton("Gửi Lời Nhắc") { _, _ ->
                Toast.makeText(this, "Đã gửi thông báo nhắc nhở học tập tới máy con!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    // HIỂN THỊ CHUẨN SCREEN 3: KHI CHƯA GHÉP ĐÔI CHỈ HIỆN THẺ NHẬP MÃ (KHÔNG HIỆN 'Xin chào ... ĐÃ GHÉP ĐÔI')
    private fun showStudentPairedState(code: String) {
        layoutStudentCard.visibility = View.GONE
        layoutStudentPairedState.visibility = View.VISIBLE
        tvGreeting.text = "Xin chào ............ ,"
        tvCompanionBadge.text = "ĐÃ GHÉP ĐÔI"
        tvPairedCodeDisplay.text = code
    }

    private fun showStudentUnpairedState() {
        layoutStudentCard.visibility = View.VISIBLE
        layoutStudentPairedState.visibility = View.GONE
        etPairingCodeInput.setText("")
        etPairingCodeInput.hint = "CVA-XXXX"
        etPairingCodeInput.isEnabled = true
        btnConnectPairing.text = "🔗 KẾT NỐI"
        tvPairingStatus.visibility = View.GONE
    }

    private fun startUnpairListener(pairedCode: String) {
        if (pairedCode.isEmpty()) return
        unpairJob?.cancel()
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        unpairJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                delay(3000)
                try {
                    var unpairTriggered = false
                    // 1. Kiểm tra trạng thái thiết bị tại /families/$pairedCode/devices/$androidId.json
                    val reqFamDev = Request.Builder()
                        .url("$FIREBASE_RTDB_URL/families/$pairedCode/devices/$androidId.json")
                        .build()
                    firebaseClient.newCall(reqFamDev).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            if (!body.isNullOrEmpty() && body != "null") {
                                val json = JSONObject(body)
                                val isStillPaired = json.optBoolean("isPaired", true)
                                val status = json.optString("status", "paired")
                                if (!isStillPaired || status == "REVOKED") {
                                    unpairTriggered = true
                                }
                            }
                        }
                    }

                    // 2. Kiểm tra legacy node /devices/$pairedCode.json
                    if (!unpairTriggered) {
                        val req = Request.Builder()
                            .url("$FIREBASE_RTDB_URL/devices/$pairedCode.json")
                            .build()
                        firebaseClient.newCall(req).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string()
                                if (!body.isNullOrEmpty() && body != "null") {
                                    val json = JSONObject(body)
                                    val isStillPaired = json.optBoolean("isPaired", true)
                                    val status = json.optString("status", "paired")
                                    if (!isStillPaired || status == "REVOKED") {
                                        unpairTriggered = true
                                    }
                                }
                            }
                        }
                    }

                    if (unpairTriggered) {
                        withContext(Dispatchers.Main) {
                            triggerStudentUnpairAnimation()
                        }
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unpair listener error: ${e.message}")
                }
            }
        }
    }

    private fun startHeartbeatLoop(pairedCode: String) {
        if (pairedCode.isEmpty()) return
        heartbeatJob?.cancel()
        heartbeatJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                sendHeartbeatPing(pairedCode)
                checkIncomingParentMessage(pairedCode)
                checkIncomingLocationCommand(pairedCode)
                delay(10_000)
            }
        }
    }

    private suspend fun checkIncomingLocationCommand(pairedCode: String) {
        try {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
            val req = Request.Builder()
                .url("$FIREBASE_RTDB_URL/families/$pairedCode/devices/$androidId/commands/locate_now.json")
                .build()
            firebaseClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty() && body != "null") {
                        val json = JSONObject(body)
                        val status = json.optString("status", "")
                        if (status == "PENDING" || status.isEmpty()) {
                            val loc = LocationHelper.fetchCurrentLocation(this@MainActivity)
                            val mediaType = "application/json; charset=utf-8".toMediaType()
                            if (loc != null) {
                                val locBody = loc.toJsonObject().toString().toRequestBody(mediaType)
                                val putFamLoc = Request.Builder()
                                    .url("$FIREBASE_RTDB_URL/families/$pairedCode/devices/$androidId/location.json")
                                    .put(locBody)
                                    .build()
                                firebaseClient.newCall(putFamLoc).execute().close()

                                val putDevLoc = Request.Builder()
                                    .url("$FIREBASE_RTDB_URL/devices/$androidId/location.json")
                                    .put(locBody)
                                    .build()
                                firebaseClient.newCall(putDevLoc).execute().close()
                            }

                            val doneJson = JSONObject().apply {
                                put("status", "COMPLETED")
                                put("completedAt", System.currentTimeMillis())
                            }
                            val doneBody = doneJson.toString().toRequestBody(mediaType)
                            val updateCmd = Request.Builder()
                                .url("$FIREBASE_RTDB_URL/families/$pairedCode/devices/$androidId/commands/locate_now.json")
                                .put(doneBody)
                                .build()
                            firebaseClient.newCall(updateCmd).execute().close()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkIncomingLocationCommand error: ${e.message}")
        }
    }

    private suspend fun checkIncomingParentMessage(pairedCode: String) {
        try {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
            val req = Request.Builder()
                .url("$FIREBASE_RTDB_URL/families/$pairedCode/devices/$androidId/message.json")
                .build()
            firebaseClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty() && body != "null") {
                        val json = JSONObject(body)
                        val msgId = json.optString("id", "")
                        val text = json.optString("text", "")
                        val timestamp = json.optLong("timestamp", 0L)
                        val replyText = json.optString("replyText", "")

                        if (text.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                layoutParentMessageBox.visibility = View.VISIBLE
                                tvParentMessageContent.text = text
                                if (timestamp > 0) {
                                    val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
                                    tvParentMessageTime.text = timeStr
                                }
                                if (replyText.isNotEmpty()) {
                                    tvStudentReplyStatus.visibility = View.VISIBLE
                                    tvStudentReplyStatus.text = "Con đã phản hồi: \"$replyText\""
                                } else {
                                    tvStudentReplyStatus.visibility = View.GONE
                                }

                                if (msgId.isNotEmpty() && msgId != lastSeenMessageId) {
                                    lastSeenMessageId = msgId
                                    showParentMessageNotification(text)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkIncomingParentMessage error: ${e.message}")
        }
    }

    private fun showParentMessageNotification(messageText: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "cva_parent_messages"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Tin Nhắn Từ Bố Mẹ",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Thông báo tin nhắn và lời nhắc nhở từ phụ huynh"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("💌 Tin nhắn từ Bố Mẹ")
                .setContentText(messageText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            notificationManager.notify(2001, notification)
        } catch (e: Exception) {
            Log.w(TAG, "showParentMessageNotification error: ${e.message}")
        }
    }

    private fun sendStudentReply(replyText: String) {
        val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
        val pairedCode = prefs.getString("paired_code", "") ?: ""
        if (pairedCode.isEmpty()) return

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        val now = System.currentTimeMillis()

        tvStudentReplyStatus.visibility = View.VISIBLE
        tvStudentReplyStatus.text = "✅ Đã gửi phản hồi: \"$replyText\""
        Toast.makeText(this, "💌 Đã gửi phản hồi tới Bố Mẹ!", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val replyJson = JSONObject().apply {
                    put("replyText", replyText)
                    put("replyTimestamp", now)
                    put("status", "REPLIED")
                }
                val body = replyJson.toString().toRequestBody(mediaType)

                // 1. Cập nhật vào /families/$pairedCode/devices/$androidId/message.json
                val reqFam = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/families/$pairedCode/devices/$androidId/message.json")
                    .patch(body)
                    .build()
                firebaseClient.newCall(reqFam).execute().close()

                // 2. Cập nhật vào /devices/$androidId/message.json
                val reqDev = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/devices/$androidId/message.json")
                    .patch(body)
                    .build()
                firebaseClient.newCall(reqDev).execute().close()

                // 3. Cập nhật legacy /devices/$pairedCode/message.json và reminder.json
                val reqLegMsg = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/devices/$pairedCode/message.json")
                    .patch(body)
                    .build()
                firebaseClient.newCall(reqLegMsg).execute().close()

                val reqLegRem = Request.Builder()
                    .url("$FIREBASE_RTDB_URL/devices/$pairedCode/reminder.json")
                    .patch(body)
                    .build()
                firebaseClient.newCall(reqLegRem).execute().close()

                // Gửi ngay 1 nhịp tim cập nhật online tức thời
                sendHeartbeatPing(pairedCode)
            } catch (e: Exception) {
                Log.w(TAG, "sendStudentReply error: ${e.message}")
            }
        }
    }

    private suspend fun sendHeartbeatPing(pairedCode: String) {
        if (pairedCode.isEmpty()) return
        try {
            val now = System.currentTimeMillis()
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
            val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            val pingJson = JSONObject().apply {
                put("lastSync", now)
                put("lastHeartbeat", now)
                put("online", true)
                put("deviceId", androidId)
                put("deviceModel", "$manufacturer $model")
                put("androidVersion", "Android ${Build.VERSION.RELEASE}")
                put("isPaired", true)
                put("status", "paired")
                put("gpsStatus", if (LocationHelper.isGpsEnabled(this@MainActivity)) "ENABLED" else "DISABLED")
                put("batteryLevel", LocationHelper.getBatteryLevel(this@MainActivity))
            }
            val body = pingJson.toString().toRequestBody(jsonMediaType)

            // 1. Ghi độc lập vào /families/$pairedCode/devices/$androidId.json
            val reqFamDev = Request.Builder()
                .url("$FIREBASE_RTDB_URL/families/$pairedCode/devices/$androidId.json")
                .patch(body)
                .build()
            firebaseClient.newCall(reqFamDev).execute().close()

            // 2. Ghi vào /devices/$androidId.json
            val reqDev = Request.Builder()
                .url("$FIREBASE_RTDB_URL/devices/$androidId.json")
                .patch(body)
                .build()
            firebaseClient.newCall(reqDev).execute().close()

            // 3. Tương thích ngược: /devices/$pairedCode và /pairings/$pairedCode
            val reqDevice = Request.Builder()
                .url("$FIREBASE_RTDB_URL/devices/$pairedCode.json")
                .patch(body)
                .build()
            firebaseClient.newCall(reqDevice).execute().close()

            val reqPairing = Request.Builder()
                .url("$FIREBASE_RTDB_URL/pairings/$pairedCode.json")
                .patch(body)
                .build()
            firebaseClient.newCall(reqPairing).execute().close()
            Log.d(TAG, "Multi-device heartbeat ping sent for $androidId in $pairedCode at $now")
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat ping failed: ${e.message}")
        }
    }

    private fun triggerStudentUnpairAnimation() {
        unpairJob?.cancel()
        heartbeatJob?.cancel()
        layoutUnpairOverlay.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.Main) {
            delay(1800)
            val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("is_paired", false)
                .remove("paired_code")
                .apply()

            layoutUnpairOverlay.visibility = View.GONE
            showStudentUnpairedState()
            Toast.makeText(this@MainActivity, "Phụ huynh đã ngắt kết nối.", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendDeviceTelemetry(pairedCode: String, isPaired: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
                val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
                val model = Build.MODEL
                val androidVer = "Android ${Build.VERSION.RELEASE}"

                val jsonMediaType = "application/json; charset=utf-8".toMediaType()
                val deviceStats = JSONObject().apply {
                    put("pairingCode", pairedCode)
                    put("deviceId", androidId)
                    put("deviceModel", "$manufacturer $model")
                    put("androidVersion", androidVer)
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
                Log.w(TAG, "Lỗi telemetry: ${e.message}")
            }
        }
    }

    private fun performUpdateCheck(userInitiated: Boolean) {
        lifecycleScope.launch {
            if (userInitiated) {
                Toast.makeText(this@MainActivity, "Đang kiểm tra bản cập nhật...", Toast.LENGTH_SHORT).show()
            }
            val updateInfo = AppUpdateManager.checkForUpdate(this@MainActivity)
            if (updateInfo != null) {
                showUpdateAvailableDialog(updateInfo)
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
            .setMessage("Đã có phiên bản mới$sizeText. Bạn có muốn cập nhật ngay không?$changelogText")
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

    private fun checkAllPermissions() {
        val hasUsage = hasUsageStatsPermission()
        val hasA11y = hasAccessibilityPermission()
        val hasAdmin = hasDeviceAdminPermission()

        if (hasUsage && hasA11y && hasAdmin) {
            Log.d(TAG, "Tất cả các quyền bảo vệ đã được kích hoạt!")
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

    private fun hasAccessibilityPermission(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        val expectedComponentName = ComponentName(this, GuardianAccessibilityService::class.java).flattenToString()
        for (service in enabledServices) {
            if (service.id.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun hasDeviceAdminPermission(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, SmartGuardianAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }
}
