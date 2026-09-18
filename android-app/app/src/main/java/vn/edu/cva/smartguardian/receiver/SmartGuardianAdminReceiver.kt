package vn.edu.cva.smartguardian.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class SmartGuardianAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(
            context,
            "CVA-SmartGuardian: Đã kích hoạt chế độ Quản trị viên chống gỡ bỏ trái phép!",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Cảnh báo hiển thị khi có ai cố tình hủy quyền Quản trị viên thiết bị để gỡ ứng dụng
        return "CẢNH BÁO: Đây là ứng dụng đồng hành bảo vệ học sinh THCS theo thỏa ước gia đình. Vui lòng nhập mã PIN của Phụ huynh nếu muốn tắt chế độ bảo vệ."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(
            context,
            "CVA-SmartGuardian: Quyền Quản trị viên đã bị vô hiệu hóa!",
            Toast.LENGTH_SHORT
        ).show()
    }
}
