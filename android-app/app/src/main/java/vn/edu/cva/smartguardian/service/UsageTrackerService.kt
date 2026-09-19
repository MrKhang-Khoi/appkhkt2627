package vn.edu.cva.smartguardian.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.edu.cva.smartguardian.data.AppCategory
import vn.edu.cva.smartguardian.data.AppClassifier
import vn.edu.cva.smartguardian.ui.MainActivity
import android.provider.Settings
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Calendar
import vn.edu.cva.smartguardian.location.LocationHelper

class UsageTrackerService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    companion object {
        const val CHANNEL_ID = "cva_smart_guardian_tracker"
        const val NOTIFICATION_ID = 1001
        const val ACTION_USAGE_UPDATED = "vn.edu.cva.smartguardian.ACTION_USAGE_UPDATED"
        const val PREFS_NAME = "cva_guardian_stats"

        private val sharedHttpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
        private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        fun start(context: Context) {
            val intent = Intent(context, UsageTrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun sendHeartbeatPing(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pairedCode = prefs.getString("paired_code", "") ?: ""
            if (pairedCode.isEmpty()) return
            val androidId = prefs.getString("device_id", "")?.takeIf { it.isNotEmpty() }
                ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "UNKNOWN"

            syncScope.launch {
                try {
                    val now = System.currentTimeMillis()
                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
                    val model = Build.MODEL
                    val pingJson = JSONObject().apply {
                        put("lastSync", now)
                        put("lastHeartbeat", now)
                        put("online", true)
                        put("deviceId", androidId)
                        put("deviceModel", "$manufacturer $model")
                        put("androidVersion", "Android ${Build.VERSION.RELEASE}")
                        put("isPaired", true)
                        put("status", "paired")
                    }
                    val body = pingJson.toString().toRequestBody(mediaType)

                    // 1. Ghi độc lập vào danh sách thiết bị gia đình: /families/$pairedCode/devices/$androidId
                    val reqFamDev = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId.json")
                        .patch(body)
                        .build()
                    sharedHttpClient.newCall(reqFamDev).execute().close()

                    // 2. Ghi chi tiết thiết bị phẳng: /devices/$androidId
                    val reqDevice = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$androidId.json")
                        .patch(body)
                        .build()
                    sharedHttpClient.newCall(reqDevice).execute().close()

                    // 3. Tương thích ngược: /devices/$pairedCode và /pairings/$pairedCode
                    val reqLegacyDev = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$pairedCode.json")
                        .patch(body)
                        .build()
                    sharedHttpClient.newCall(reqLegacyDev).execute().close()

                    val reqPairing = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/pairings/$pairedCode.json")
                        .patch(body)
                        .build()
                    sharedHttpClient.newCall(reqPairing).execute().close()
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "sendHeartbeatPing failed: ${e.message}")
                }
            }
        }

        fun checkLocationRequest(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pairedCode = prefs.getString("paired_code", "") ?: ""
            if (pairedCode.isEmpty()) return
            val androidId = prefs.getString("device_id", "")?.takeIf { it.isNotEmpty() }
                ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "UNKNOWN"

            syncScope.launch {
                try {
                    val req = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId/commands/locate_now.json")
                        .build()
                    sharedHttpClient.newCall(req).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            if (!body.isNullOrEmpty() && body != "null") {
                                val json = JSONObject(body)
                                val status = json.optString("status", "")
                                if (status == "PENDING" || status.isEmpty()) {
                                    val loc = LocationHelper.fetchCurrentLocation(context)
                                    val mediaType = "application/json; charset=utf-8".toMediaType()
                                    if (loc != null) {
                                        val locBody = loc.toJsonObject().toString().toRequestBody(mediaType)

                                        val putFamLoc = okhttp3.Request.Builder()
                                            .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId/location.json")
                                            .put(locBody)
                                            .build()
                                        sharedHttpClient.newCall(putFamLoc).execute().close()

                                        val putDevLoc = okhttp3.Request.Builder()
                                            .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$androidId/location.json")
                                            .put(locBody)
                                            .build()
                                        sharedHttpClient.newCall(putDevLoc).execute().close()
                                    }

                                    val doneJson = JSONObject().apply {
                                        put("status", "COMPLETED")
                                        put("completedAt", System.currentTimeMillis())
                                    }
                                    val doneBody = doneJson.toString().toRequestBody(mediaType)
                                    val updateCmd = okhttp3.Request.Builder()
                                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId/commands/locate_now.json")
                                        .put(doneBody)
                                        .build()
                                    sharedHttpClient.newCall(updateCmd).execute().close()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "checkLocationRequest error: ${e.message}")
                }
            }
        }

        fun reportActiveApp(
            context: Context,
            packageName: String,
            appName: String,
            category: String,
            categoryLabel: String,
            isForeground: Boolean
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pairedCode = prefs.getString("paired_code", "") ?: ""
            if (pairedCode.isEmpty()) return
            val androidId = prefs.getString("device_id", "")?.takeIf { it.isNotEmpty() }
                ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "UNKNOWN"

            syncScope.launch {
                try {
                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val activeJson = JSONObject().apply {
                        put("packageName", packageName)
                        put("appName", appName)
                        put("category", category)
                        put("categoryLabel", categoryLabel)
                        put("timestamp", System.currentTimeMillis())
                        put("isForeground", isForeground)
                    }
                    val body = activeJson.toString().toRequestBody(mediaType)

                    val reqFam = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId/active_app.json")
                        .put(body)
                        .build()
                    sharedHttpClient.newCall(reqFam).execute().close()

                    val reqDev = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$androidId/active_app.json")
                        .put(body)
                        .build()
                    sharedHttpClient.newCall(reqDev).execute().close()
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "reportActiveApp failed: ${e.message}")
                }
            }
        }

        fun recordAppSession(context: Context, packageName: String, durationMs: Long) {
            if (durationMs < 1000L) return
            val isSystemOrSelf = packageName == context.packageName ||
                    packageName == "com.android.systemui" ||
                    packageName.contains("inputmethod") ||
                    packageName.contains("keyboard") ||
                    packageName.contains("launcher") ||
                    packageName == "SCREEN_OFF" ||
                    packageName == "HOME"
            if (isSystemOrSelf) return

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            val appKey = "session_${todayStr}_$packageName"
            val curMs = prefs.getLong(appKey, 0L)
            val newMs = curMs + durationMs

            var appInfo: android.content.pm.ApplicationInfo? = null
            val appLabel = try {
                appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }
            val metadata = AppClassifier.classify(packageName, appLabel, appInfo)

            prefs.edit()
                .putLong(appKey, newMs)
                .putString("app_name_$packageName", metadata.appName.ifEmpty { appLabel })
                .putString("app_cat_$packageName", metadata.category.name)
                .putString("app_cat_label_$packageName", metadata.category.displayName)
                .putLong("app_last_used_$packageName", System.currentTimeMillis())
                .apply()

            // Đồng bộ ngay thống kê lên Firebase
            collectAndSave(context)
        }

        fun collectAndSave(context: Context) {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

            val calendar = Calendar.getInstance()
            val endTime = calendar.timeInMillis
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startTime = calendar.timeInMillis

            val statsMap = usageStatsManager?.queryAndAggregateUsageStats(startTime, endTime)
            val statsCollection = if (!statsMap.isNullOrEmpty()) {
                statsMap.values
            } else {
                usageStatsManager?.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    startTime,
                    endTime
                ) ?: emptyList()
            }

            var studyTimeMs = 0L
            var gameTimeMs = 0L
            var socialTimeMs = 0L
            var utilityTimeMs = 0L

            val pm = context.packageManager

            data class AppHistoryRecord(
                val packageName: String,
                val appName: String,
                val category: String,
                val categoryLabel: String,
                val totalTimeMs: Long,
                val durationMinutes: Int,
                val lastTimeUsed: Long
            )
            val appList = mutableListOf<AppHistoryRecord>()

            for (stat in statsCollection) {
                val totalTime = stat.totalTimeInForeground
                if (totalTime <= 0) continue

                var appInfo: android.content.pm.ApplicationInfo? = null
                val appLabel = try {
                    appInfo = pm.getApplicationInfo(stat.packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    stat.packageName
                }

                val metadata = AppClassifier.classify(stat.packageName, appLabel, appInfo)
                when (metadata.category) {
                    AppCategory.STUDY -> studyTimeMs += totalTime
                    AppCategory.GAME -> gameTimeMs += totalTime
                    AppCategory.SOCIAL -> socialTimeMs += totalTime
                    AppCategory.UTILITY, AppCategory.OTHER -> utilityTimeMs += totalTime
                }

                val isSystemOrSelf = stat.packageName == context.packageName ||
                        stat.packageName == "com.android.systemui" ||
                        stat.packageName.contains("inputmethod") ||
                        stat.packageName.contains("keyboard") ||
                        stat.packageName.contains("launcher")

                if (!isSystemOrSelf && totalTime >= 15_000L) {
                    appList.add(
                        AppHistoryRecord(
                            packageName = stat.packageName,
                            appName = appLabel,
                            category = metadata.category.name,
                            categoryLabel = metadata.category.displayName,
                            totalTimeMs = totalTime,
                            durationMinutes = (totalTime / 60000L).toInt().coerceAtLeast(1),
                            lastTimeUsed = stat.lastTimeUsed
                        )
                    )
                }
            }

            // Hợp nhất dữ liệu phiên tích lũy từ Accessibility (Dual-Tracking Engine)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            val allPrefs = prefs.all
            for ((key, value) in allPrefs) {
                if (key.startsWith("session_${todayStr}_") && value is Long && value > 0L) {
                    val pkg = key.removePrefix("session_${todayStr}_")
                    val existingIndex = appList.indexOfFirst { it.packageName == pkg }
                    val appName = prefs.getString("app_name_$pkg", pkg) ?: pkg
                    val catName = prefs.getString("app_cat_$pkg", "OTHER") ?: "OTHER"
                    val catLabel = prefs.getString("app_cat_label_$pkg", "Khác") ?: "Khác"
                    val lastUsed = prefs.getLong("app_last_used_$pkg", System.currentTimeMillis())

                    if (existingIndex >= 0) {
                        val old = appList[existingIndex]
                        if (value > old.totalTimeMs) {
                            val diff = value - old.totalTimeMs
                            when (old.category) {
                                "STUDY" -> studyTimeMs += diff
                                "GAME" -> gameTimeMs += diff
                                "SOCIAL" -> socialTimeMs += diff
                                else -> utilityTimeMs += diff
                            }
                            appList[existingIndex] = old.copy(
                                totalTimeMs = value,
                                durationMinutes = (value / 60000L).toInt().coerceAtLeast(1)
                            )
                        }
                    } else {
                        appList.add(
                            AppHistoryRecord(
                                packageName = pkg,
                                appName = appName,
                                category = catName,
                                categoryLabel = catLabel,
                                totalTimeMs = value,
                                durationMinutes = (value / 60000L).toInt().coerceAtLeast(1),
                                lastTimeUsed = lastUsed
                            )
                        )
                        when (catName) {
                            "STUDY" -> studyTimeMs += value
                            "GAME" -> gameTimeMs += value
                            "SOCIAL" -> socialTimeMs += value
                            else -> utilityTimeMs += value
                        }
                    }
                }
            }

            appList.sortByDescending { it.totalTimeMs }
            val topApps = appList.take(15)
            val appHistoryJsonArray = org.json.JSONArray().apply {
                for (item in topApps) {
                    put(JSONObject().apply {
                        put("packageName", item.packageName)
                        put("appName", item.appName)
                        put("category", item.category)
                        put("categoryLabel", item.categoryLabel)
                        put("totalTimeMs", item.totalTimeMs)
                        put("durationMinutes", item.durationMinutes)
                        put("lastTimeUsed", item.lastTimeUsed)
                    })
                }
            }

            val totalScreenTimeMs = studyTimeMs + gameTimeMs + socialTimeMs + utilityTimeMs

            // Tính điểm cân bằng số (Balance Score từ 0 đến 100)
            val balanceScore = if (totalScreenTimeMs > 0) {
                val studyRatio = studyTimeMs.toDouble() / totalScreenTimeMs
                val gameRatio = gameTimeMs.toDouble() / totalScreenTimeMs
                val score = ((studyRatio * 1.0 + (1.0 - gameRatio) * 0.5) * 100).toInt()
                score.coerceIn(10, 100)
            } else {
                100
            }

            // Lưu vào SharedPreferences
            prefs.edit()
                .putLong("study_time_ms", studyTimeMs)
                .putLong("game_time_ms", gameTimeMs)
                .putLong("social_time_ms", socialTimeMs)
                .putLong("utility_time_ms", utilityTimeMs)
                .putLong("total_screen_time_ms", totalScreenTimeMs)
                .putInt("balance_score", balanceScore)
                .putLong("last_updated_at", System.currentTimeMillis())
                .apply()

            // Đồng bộ trực tiếp nhịp tim & thống kê lên Firebase để Parent Hub theo dõi thời gian thực
            val pairedCode = prefs.getString("paired_code", "") ?: ""
            if (pairedCode.isNotEmpty()) {
                val androidId = prefs.getString("device_id", "")?.takeIf { it.isNotEmpty() }
                    ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    ?: "UNKNOWN"

                syncScope.launch {
                    try {
                        val now = System.currentTimeMillis()
                        val mediaType = "application/json; charset=utf-8".toMediaType()
                        val usageJson = JSONObject().apply {
                            put("studyTimeMinutes", (studyTimeMs / 60000).toInt())
                            put("gameTimeMinutes", (gameTimeMs / 60000).toInt())
                            put("socialTimeMinutes", (socialTimeMs / 60000).toInt())
                            put("utilityTimeMinutes", (utilityTimeMs / 60000).toInt())
                            put("totalScreenTimeMinutes", (totalScreenTimeMs / 60000).toInt())
                            put("balanceScore", balanceScore)
                            put("lastSync", now)
                            put("lastHeartbeat", now)
                            put("appHistory", appHistoryJsonArray)
                        }

                        val historyBody = appHistoryJsonArray.toString().toRequestBody(mediaType)
                        val reqFamHist = okhttp3.Request.Builder()
                            .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId/app_history.json")
                            .put(historyBody)
                            .build()
                        sharedHttpClient.newCall(reqFamHist).execute().close()

                        // Cập nhật đồng thời nhánh device: online = true, lastSync = now, kèm usage
                        val devicePatch = JSONObject().apply {
                            put("online", true)
                            put("lastSync", now)
                            put("lastHeartbeat", now)
                            put("usage", usageJson)
                            put("app_history", appHistoryJsonArray)
                        }
                        val patchBody = devicePatch.toString().toRequestBody(mediaType)

                        // 1. Ghi vào danh sách thiết bị gia đình: /families/$pairedCode/devices/$androidId
                        val reqFamDev = okhttp3.Request.Builder()
                            .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId.json")
                            .patch(patchBody)
                            .build()
                        sharedHttpClient.newCall(reqFamDev).execute().close()

                        // 2. Ghi vào chi tiết thiết bị phẳng: /devices/$androidId
                        val reqDevice = okhttp3.Request.Builder()
                            .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$androidId.json")
                            .patch(patchBody)
                            .build()
                        sharedHttpClient.newCall(reqDevice).execute().close()

                        // 3. Tương thích ngược: /devices/$pairedCode và /pairings/$pairedCode
                        val reqLegacyDev = okhttp3.Request.Builder()
                            .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$pairedCode.json")
                            .patch(patchBody)
                            .build()
                        sharedHttpClient.newCall(reqLegacyDev).execute().close()

                        val reqPairing = okhttp3.Request.Builder()
                            .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/pairings/$pairedCode.json")
                            .patch(patchBody)
                            .build()
                        sharedHttpClient.newCall(reqPairing).execute().close()
                    } catch (e: Exception) {
                        Log.w("UsageTrackerService", "collectAndSave sync failed: ${e.message}")
                    }
                }
            }

            // Phát broadcast thông báo cho UI nếu đang mở
            val updateIntent = Intent(ACTION_USAGE_UPDATED)
            context.sendBroadcast(updateIntent)
        }
    }

    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ctx = context ?: return
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("UsageTrackerService", "Phát hiện tắt màn hình (SCREEN_OFF): Đóng băng bộ đếm và cập nhật active_app")
                    reportActiveApp(
                        context = ctx,
                        packageName = "SCREEN_OFF",
                        appName = "Màn hình tắt / Khóa máy",
                        category = "OFFLINE",
                        categoryLabel = "Đã tắt màn hình",
                        isForeground = false
                    )
                }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                    Log.d("UsageTrackerService", "Phát hiện mở màn hình: Tiếp tục giám sát đồng hành")
                    sendHeartbeatPing(ctx)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundNotification()

        val screenFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, screenFilter)

        startTrackingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {}
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Giám Sát & Bảo Vệ Học Sinh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Theo dõi thời gian sử dụng điện thoại và bảo vệ an toàn trực tuyến"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CVA-SmartGuardian đang bảo vệ")
            .setContentText("Hệ thống đồng hành và bảo vệ số đang hoạt động tích cực")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val fgsType = if (LocationHelper.hasLocationPermission(this)) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    fgsType
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startTrackingLoop() {
        serviceScope.launch {
            while (isActive) {
                // 1. Luôn gửi nhịp tim sống còn (Heartbeat) dù có quyền Usage hay không
                sendHeartbeatPing(this@UsageTrackerService)

                // 2. Kiểm tra lệnh định vị tức thì từ phụ huynh
                checkLocationRequest(this@UsageTrackerService)

                // 3. Thu thập thống kê chi tiết nếu được cấp quyền
                try {
                    collectAndSave(this@UsageTrackerService)
                } catch (_: Exception) {}

                delay(10_000) // Nhịp tim kiểm tra 10 giây
            }
        }
    }
}
