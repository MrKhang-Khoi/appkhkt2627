package vn.edu.cva.smartguardian.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val fileSize: String,
    val sha256: String,
    val changelog: List<String>,
    val isForceUpdate: Boolean
)

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    const val FIREBASE_VERSION_URL = "https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/app_release.json"
    const val GITHUB_VERSION_URL = "https://mrkhang-khoi.github.io/appkhkt2627/version.json"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Kiểm tra bản cập nhật từ Firebase RTDB (tức thời) hoặc GitHub Pages qua HTTPS
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        // 1. Thử đọc từ Firebase RTDB trước (phản hồi trong <100ms, không bị dính cache)
        var json = fetchVersionJson(FIREBASE_VERSION_URL)
        if (json == null) {
            // 2. Fallback sang GitHub Pages
            json = fetchVersionJson(GITHUB_VERSION_URL)
        }
        if (json == null) return@withContext null

        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }

            val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }

            val updateInfo = parseUpdateInfo(json, currentVersionCode)
            if (updateInfo != null) {
                Log.d(TAG, "Phiên bản hiện tại: $currentVersionCode, Phát hiện bản mới từ xa: ${updateInfo.versionCode}")
            } else {
                Log.d(TAG, "Phiên bản hiện tại: $currentVersionCode, Đã ở bản mới nhất hoặc dữ liệu không hợp lệ")
            }
            updateInfo
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi kiểm tra bản cập nhật", e)
            null
        }
    }

    /**
     * Phân tích và xác thực payload thông tin cập nhật từ JSON đối chiếu với phiên bản hiện tại
     */
    fun parseUpdateInfo(json: JSONObject?, currentVersionCode: Int): UpdateInfo? {
        if (json == null) return null
        try {
            val remoteVersionCode = if (json.has("latestVersionCode")) {
                json.optInt("latestVersionCode", 0)
            } else {
                json.optInt("versionCode", 0)
            }
            val remoteVersionName = if (json.has("latestVersionName")) {
                json.optString("latestVersionName", "1.0.0")
            } else {
                json.optString("versionName", "1.0.0")
            }
            val apkUrl = json.optString("apkUrl", "")
            val fileSize = json.optString("fileSize", "")
            val sha256 = json.optString("sha256", "")
            val isForceUpdate = json.optBoolean("isForceUpdate", false)

            val changelogList = mutableListOf<String>()
            val changelogArray = json.optJSONArray("changelog")
            if (changelogArray != null) {
                for (i in 0 until changelogArray.length()) {
                    changelogList.add(changelogArray.getString(i))
                }
            }

            if (remoteVersionCode > currentVersionCode && apkUrl.isNotEmpty()) {
                return UpdateInfo(
                    versionCode = remoteVersionCode,
                    versionName = remoteVersionName,
                    apkUrl = apkUrl,
                    fileSize = fileSize,
                    sha256 = sha256,
                    changelog = changelogList,
                    isForceUpdate = isForceUpdate
                )
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi phân tích update info JSON", e)
            return null
        }
    }

    private fun fetchVersionJson(url: String): JSONObject? {
        return try {
            val cacheBusterUrl = if (url.contains("?")) "$url&t=${System.currentTimeMillis()}" else "$url?t=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(cacheBusterUrl)
                .header("Cache-Control", "no-cache")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string() ?: return null
                JSONObject(bodyStr)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi fetch version từ $url: ${e.message}")
            null
        }
    }

    /**
     * Tải tệp APK và kiểm tra mã băm SHA-256
     */
    suspend fun downloadAndVerifyApk(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(updateInfo.apkUrl)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Tải file thất bại, HTTP: ${response.code}"))
                }

                val body = response.body ?: return@withContext Result.failure(Exception("Phản hồi rỗng"))
                val contentLength = body.contentLength()

                val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir
                val destinationFile = File(downloadDir, "CVA-SmartGuardian-v${updateInfo.versionName}.apk")
                if (destinationFile.exists()) destinationFile.delete()

                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            if (contentLength > 0) {
                                val progress = ((totalBytesRead * 100) / contentLength).toInt()
                                withContext(Dispatchers.Main) {
                                    onProgress(progress)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                // Kiểm tra mã băm SHA-256 nếu có khai báo
                if (updateInfo.sha256.isNotBlank()) {
                    val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                    Log.d(TAG, "SHA-256 tính toán: $computedHash | Kỳ vọng: ${updateInfo.sha256}")
                    if (!computedHash.equals(updateInfo.sha256.trim(), ignoreCase = true)) {
                        destinationFile.delete()
                        return@withContext Result.failure(
                            SecurityException("Mã băm SHA-256 không khớp! File có thể bị hỏng hoặc đã bị sửa đổi.")
                        )
                    }
                }

                Result.success(destinationFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi trong quá trình tải hoặc kiểm tra APK", e)
            Result.failure(e)
        }
    }

    /**
     * Kích hoạt trình cài đặt gói hệ thống Android qua FileProvider
     */
    fun installApk(activity: Activity, apkFile: File) {
        // Kiểm tra quyền cài ứng dụng từ nguồn không xác định trên Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                return
            }
        }

        val authority = "${activity.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(activity, authority, apkFile)

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        activity.startActivity(installIntent)
    }
}
