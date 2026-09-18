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
                    val pingJson = JSONObject().apply {
                        put("lastSync", now)
                        put("lastHeartbeat", now)
                        put("online", true)
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

        fun collectAndSave(context: Context) {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return

            val calendar = Calendar.getInstance()
            val endTime = calendar.timeInMillis
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startTime = calendar.timeInMillis

            val statsMap = usageStatsManager.queryAndAggregateUsageStats(startTime, endTime)
            val statsCollection = if (!statsMap.isNullOrEmpty()) {
                statsMap.values
            } else {
                usageStatsManager.queryUsageStats(
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
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
                        }

                        // Cập nhật đồng thời nhánh device: online = true, lastSync = now, kèm usage
                        val devicePatch = JSONObject().apply {
                            put("online", true)
                            put("lastSync", now)
                            put("lastHeartbeat", now)
                            put("usage", usageJson)
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundNotification()
        startTrackingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
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

                // 2. Thu thập thống kê chi tiết nếu được cấp quyền
                try {
                    collectAndSave(this@UsageTrackerService)
                } catch (_: Exception) {}

                delay(10_000) // Nhịp tim kiểm tra 10 giây
            }
        }
    }
}
