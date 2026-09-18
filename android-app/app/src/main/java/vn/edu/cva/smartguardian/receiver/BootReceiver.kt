package vn.edu.cva.smartguardian.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import vn.edu.cva.smartguardian.service.SafeVpnFilterService
import vn.edu.cva.smartguardian.service.UsageTrackerService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Tự động khởi động dịch vụ đo lường và bảo vệ thời gian sử dụng
            UsageTrackerService.start(context)

            // Kiểm tra cấu hình VPN tự khởi chạy
            val prefs = context.getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
            val vpnEnabled = prefs.getBoolean("vpn_protection_enabled", false)
            if (vpnEnabled) {
                val vpnIntent = Intent(context, SafeVpnFilterService::class.java)
                context.startService(vpnIntent)
            }
        }
    }
}
