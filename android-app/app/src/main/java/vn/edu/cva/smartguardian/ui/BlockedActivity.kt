package vn.edu.cva.smartguardian.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import vn.edu.cva.smartguardian.R
import vn.edu.cva.smartguardian.service.UsageTrackerService

class BlockedActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BLOCKED_URL = "extra_blocked_url"
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_REASON = "extra_reason"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked)

        // CHỐT CHẶN: Vô hiệu hóa phím Back cử chỉ/vật lý, ép buộc quay về màn hình chính an toàn
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHomeSafe()
            }
        })

        val tvBlockedUrl = findViewById<TextView>(R.id.tvBlockedUrl)
        val tvCategory = findViewById<TextView>(R.id.tvCategory)
        val tvReason = findViewById<TextView>(R.id.tvReason)
        val btnBackSafe = findViewById<Button>(R.id.btnBackSafe)
        val btnUnlockPin = findViewById<Button>(R.id.btnUnlockPin)

        val url = intent.getStringExtra(EXTRA_BLOCKED_URL) ?: "Trang web không xác định"
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "Nội dung không an toàn"
        val reason = intent.getStringExtra(EXTRA_REASON) ?: "Bị chặn theo thỏa ước an toàn số gia đình."

        tvBlockedUrl.text = url
        tvCategory.text = category
        tvReason.text = reason

        btnBackSafe.setOnClickListener {
            goHomeSafe()
        }

        btnUnlockPin.setOnClickListener {
            showPinDialog()
        }
    }

    private fun goHomeSafe() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(homeIntent)
        finish()
    }

    private fun showPinDialog() {
        val prefs = getSharedPreferences(UsageTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // 1. Kiểm tra trạng thái khóa tạm thời (Lockout)
        val remainSec = MainActivity.getPinLockoutRemainingSeconds(prefs, now)
        if (remainSec > 0) {
            Toast.makeText(this, "Đang bị tạm khóa! Vui lòng thử lại sau $remainSec giây.", Toast.LENGTH_LONG).show()
            return
        }

        // 2. Kiểm tra phụ huynh đã thiết lập mã PIN chưa
        if (!MainActivity.hasParentPin(prefs)) {
            Toast.makeText(this, "Mã PIN phụ huynh chưa được thiết lập trên thiết bị!", Toast.LENGTH_LONG).show()
            return
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Nhập mã PIN 4 số của Phụ huynh"
            textAlignment = EditText.TEXT_ALIGNMENT_CENTER
        }

        AlertDialog.Builder(this)
            .setTitle("Xác Thực Phụ Huynh")
            .setMessage("Nhập mã PIN 4 số của phụ huynh để mở khóa tạm thời:")
            .setView(input)
            .setPositiveButton("Mở Khóa") { _, _ ->
                val enteredPin = input.text.toString().trim()
                val authResult = MainActivity.authenticateParentPinAtomic(prefs, enteredPin, System.currentTimeMillis())

                when (authResult) {
                    is MainActivity.PinAuthResult.Success -> {
                        Toast.makeText(this, "Mã PIN chính xác. Cho phép mở khóa tạm thời!", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    is MainActivity.PinAuthResult.LockedOut -> {
                        Toast.makeText(this, "Nhập sai quá 5 lần! Tạm khóa trong ${authResult.remainingSeconds} giây.", Toast.LENGTH_LONG).show()
                    }
                    is MainActivity.PinAuthResult.IncorrectPin -> {
                        if (authResult.isNowLockedOut) {
                            Toast.makeText(this, "Nhập sai 5 lần! Hệ thống tạm khóa 30 giây.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "Mã PIN không đúng! Còn lại ${authResult.remainingAttempts} lần thử.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    is MainActivity.PinAuthResult.StorageError -> {
                        Toast.makeText(this, authResult.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }
}

