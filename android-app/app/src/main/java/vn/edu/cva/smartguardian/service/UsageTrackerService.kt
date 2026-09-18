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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.edu.cva.smartguardian.data.AppCategory
import vn.edu.cva.smartguardian.data.AppClassifier
import vn.edu.cva.smartguardian.ui.MainActivity
import java.util.Calendar

class UsageTrackerService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    companion object {
        const val CHANNEL_ID = "cva_smart_guardian_tracker"
        const val NOTIFICATION_ID = 1001
        const val ACTION_USAGE_UPDATED = "vn.edu.cva.smartguardian.ACTION_USAGE_UPDATED"
        const val PREFS_NAME = "cva_guardian_stats"

        fun start(context: Context) {
            val intent = Intent(context, UsageTrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
                collectAndSaveUsageStats()
                delay(30_000) // Cập nhật mỗi 30 giây
            }
        }
    }

    private fun collectAndSaveUsageStats() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return

        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        val startTime = calendar.timeInMillis

        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        if (usageStatsList.isNullOrEmpty()) return

        var studyTimeMs = 0L
        var gameTimeMs = 0L
        var socialTimeMs = 0L
        var utilityTimeMs = 0L

        val pm = packageManager

        for (stat in usageStatsList) {
            val totalTime = stat.totalTimeInForeground
            if (totalTime <= 0) continue

            val appLabel = try {
                val appInfo = pm.getApplicationInfo(stat.packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                stat.packageName
            }

            val metadata = AppClassifier.classify(stat.packageName, appLabel)
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
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("study_time_ms", studyTimeMs)
            .putLong("game_time_ms", gameTimeMs)
            .putLong("social_time_ms", socialTimeMs)
            .putLong("utility_time_ms", utilityTimeMs)
            .putLong("total_screen_time_ms", totalScreenTimeMs)
            .putInt("balance_score", balanceScore)
            .putLong("last_updated_at", System.currentTimeMillis())
            .apply()

        // Phát broadcast thông báo cho UI nếu đang mở
        val updateIntent = Intent(ACTION_USAGE_UPDATED)
        sendBroadcast(updateIntent)
    }
}
