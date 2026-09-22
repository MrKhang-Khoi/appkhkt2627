package vn.edu.cva.smartguardian.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import vn.edu.cva.smartguardian.ui.MainActivity

class SafeVpnFilterService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var drainThread: Thread? = null

    companion object {
        const val VPN_CHANNEL_ID = "cva_smart_guardian_vpn"
        const val VPN_NOTIFICATION_ID = 1002
        const val ACTION_START_VPN = "vn.edu.cva.smartguardian.START_VPN"
        const val ACTION_STOP_VPN = "vn.edu.cva.smartguardian.STOP_VPN"

        // DNS Cloudflare Family 1.1.1.3 tự động chặn Malware + Adult content
        private const val CLOUDFLARE_FAMILY_DNS_PRIMARY = "1.1.1.3"
        private const val CLOUDFLARE_FAMILY_DNS_SECONDARY = "1.0.0.3"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_VPN) {
            stopVpn()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        startVpn()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                VPN_CHANNEL_ID,
                "Tường Lửa Lọc Mạng An Toàn",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Chặn tên miền độc hại, lừa đảo và khiêu dâm trên toàn thiết bị"
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

        val notification: Notification = NotificationCompat.Builder(this, VPN_CHANNEL_ID)
            .setContentTitle("Tường lửa CVA-SmartGuardian")
            .setContentText("Đang kích hoạt lá chắn bảo vệ DNS chống lừa đảo & nội dung xấu")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(VPN_NOTIFICATION_ID, notification)
    }

    private fun startVpn() {
        try {
            stopDrainThread()
            vpnInterface?.close()

            val builder = Builder()
                .setSession("CVA Safe DNS Shield")
                .addAddress("10.1.10.1", 24)
                .addDnsServer(CLOUDFLARE_FAMILY_DNS_PRIMARY)
                .addDnsServer(CLOUDFLARE_FAMILY_DNS_SECONDARY)
                .setBlocking(false)

            vpnInterface = builder.establish()

            // Drain TUN fd to prevent packet buffer overflow
            vpnInterface?.let { pfd ->
                drainThread = Thread {
                    val buffer = ByteArray(32767)
                    val inputStream = java.io.FileInputStream(pfd.fileDescriptor)
                    try {
                        while (!Thread.currentThread().isInterrupted) {
                            val length = inputStream.read(buffer)
                            if (length <= 0) break
                            // DNS queries are handled at system level by Cloudflare Family;
                            // packets arriving here are safely discarded.
                        }
                    } catch (_: java.io.IOException) {
                        // VPN interface closed or thread interrupted — expected on shutdown
                    } catch (e: Exception) {
                        Log.w("SafeVpnFilter", "VPN drain thread error: ${e.message}")
                    }
                }.apply {
                    isDaemon = true
                    name = "CVA-VPN-Drain"
                    start()
                }
            }
        } catch (e: Exception) {
            Log.w("SafeVpnFilter", "Failed to start VPN: ${e.message}")
        }
    }

    private fun stopDrainThread() {
        drainThread?.interrupt()
        drainThread = null
    }

    private fun stopVpn() {
        try {
            stopDrainThread()
            vpnInterface?.close()
            vpnInterface = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            Log.w("SafeVpnFilter", "Failed to stop VPN: ${e.message}")
        }
    }

    override fun onDestroy() {
        stopDrainThread()
        stopVpn()
        super.onDestroy()
    }
}
