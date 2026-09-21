package vn.edu.cva.smartguardian.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

object OemPermissionHelper {

    private const val TAG = "OemPermissionHelper"

    /**
     * Danh sách Intent đặc thù theo từng nhà sản xuất để mở màn hình Quản trị Quyền / Tự Chạy Ngầm
     */
    val OEM_AUTOSTART_INTENTS = listOf(
        // Xiaomi / Redmi / POCO (HyperOS, MIUI)
        Intent().apply { component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity") },
        Intent().apply { component = ComponentName("com.miui.securitycenter", "com.miui.securityscan.MainActivity") },

        // Samsung (OneUI)
        Intent().apply { component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity") },
        Intent().apply { component = ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity") },

        // OPPO / Realme (ColorOS / RealmeUI)
        Intent().apply { component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity") },
        Intent().apply { component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity") },
        Intent().apply { component = ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity") },

        // VIVO / iQOO (FuntouchOS / OriginOS)
        Intent().apply { component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity") },
        Intent().apply { component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity") },

        // Huawei / Honor (EMUI / MagicOS)
        Intent().apply { component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity") },
        Intent().apply { component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity") },

        // ASUS
        Intent().apply { component = ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity") }
    )

    /**
     * Mở cài đặt Quyền Dữ Liệu Sử Dụng (Usage Access) an toàn 100% trên mọi dòng máy
     */
    fun openUsageAccess(context: Context): Boolean {
        return tryLaunch(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }) || tryLaunch(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
           || openAppDetails(context)
    }

    /**
     * Mở cài đặt Trợ Năng (Accessibility) an toàn 100% trên mọi dòng máy
     */
    fun openAccessibility(context: Context): Boolean {
        return tryLaunch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            || openAppDetails(context)
    }

    /**
     * Mở cài đặt Tự chạy ngầm / Tiết kiệm pin theo chuỗi 3 tầng fallback độc lập:
     * - Tầng 1: Các Intent chuyên biệt của từng nhà sản xuất OEM (Xiaomi, Samsung, Oppo, Vivo, Huawei, Asus)
     * - Tầng 2: Màn hình Chi tiết ứng dụng chuẩn Android (ACTION_APPLICATION_DETAILS_SETTINGS)
     * - Tầng 3: Màn hình Cài đặt hệ thống gốc (ACTION_SETTINGS)
     */
    fun openOemBackgroundSettings(context: Context): Boolean {
        // Tầng 1: Thử duyệt danh sách Intent chuyên biệt của các hãng OEM
        for (intent in OEM_AUTOSTART_INTENTS) {
            if (tryLaunch(context, intent)) {
                return true
            }
        }
        // Tầng 2: Fallback sang màn hình Chi tiết ứng dụng chuẩn Android
        val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        if (tryLaunch(context, appDetailsIntent)) {
            return true
        }
        // Tầng 3: Fallback độc lập sang Cài đặt gốc hệ thống
        return tryLaunch(context, Intent(Settings.ACTION_SETTINGS))
    }

    /**
     * Mở màn hình Cài đặt chi tiết ứng dụng (App Info Settings) với fallback Cài đặt chung
     */
    fun openAppDetails(context: Context): Boolean {
        return tryLaunch(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }) || tryLaunch(context, Intent(Settings.ACTION_SETTINGS))
    }

    fun tryLaunch(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pm = context.packageManager
            if (pm != null && intent.resolveActivity(pm) != null) {
                context.startActivity(intent)
                true
            } else if (pm == null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi kích hoạt Intent ${intent.action ?: intent.component}: ${e.message}")
            false
        }
    }
}
