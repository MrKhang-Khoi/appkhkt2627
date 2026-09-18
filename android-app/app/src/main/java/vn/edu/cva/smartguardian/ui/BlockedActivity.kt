package vn.edu.cva.smartguardian.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import vn.edu.cva.smartguardian.R

class BlockedActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BLOCKED_URL = "extra_blocked_url"
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_REASON = "extra_reason"
        private const val DEFAULT_PARENT_PIN = "2025"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked)

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
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun showPinDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Nhập mã PIN 4 số của Phụ huynh"
        }

        AlertDialog.Builder(this)
            .setTitle("Xác Thực Phụ Huynh")
            .setMessage("Chỉ phụ huynh mới có quyền mở khóa truy cập tạm thời cho học sinh.")
            .setView(input)
            .setPositiveButton("Mở Khóa") { _, _ ->
                val enteredPin = input.text.toString().trim()
                if (enteredPin == DEFAULT_PARENT_PIN) {
                    Toast.makeText(this, "Mã PIN chính xác. Cho phép mở khóa tạm thời!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Mã PIN không đúng. Tiếp tục chặn!", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Chặn phím Back không cho học sinh quay lại trang web cấm
        super.onBackPressed()
        goHomeSafe()
    }
}
