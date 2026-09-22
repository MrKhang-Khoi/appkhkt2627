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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
    val isForceUpdate: Boolean,
    val apkFallbackUrl: String = ""
)

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    const val FIREBASE_VERSION_URL = "https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/app_release.json"
    const val GITHUB_VERSION_URL = "https://mrkhang-khoi.github.io/appkhkt2627/version.json"

    @Volatile
    internal var httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main

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
     * Lấy phiên bản cài đặt thực tế của ứng dụng hiện tại
     */
    fun getCurrentVersion(context: Context): Pair<String, Int> {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val vName = packageInfo.versionName ?: "1.4.1"
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
            Pair(vName, vCode)
        } catch (e: Exception) {
            Log.w(TAG, "Không đọc được package info: ${e.message}")
            Pair("1.4.1", 41)
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
            val apkFallbackUrl = json.optString("apkFallbackUrl", "")
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

            if (remoteVersionCode > currentVersionCode && (apkUrl.isNotEmpty() || apkFallbackUrl.isNotEmpty())) {
                return UpdateInfo(
                    versionCode = remoteVersionCode,
                    versionName = remoteVersionName,
                    apkUrl = apkUrl,
                    fileSize = fileSize,
                    sha256 = sha256,
                    changelog = changelogList,
                    isForceUpdate = isForceUpdate,
                    apkFallbackUrl = apkFallbackUrl
                )
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi phân tích update info JSON", e)
            return null
        }
    }

    /**
     * Danh sách nguồn tải dự phòng theo thứ tự ưu tiên nhằm triệt tiêu hoàn toàn lỗi HTTP 404
     */
    fun getCandidateDownloadUrls(updateInfo: UpdateInfo): List<String> {
        val candidates = LinkedHashSet<String>()
        if (updateInfo.apkUrl.isNotBlank()) {
            candidates.add(updateInfo.apkUrl.trim())
        }
        if (updateInfo.apkFallbackUrl.isNotBlank()) {
            candidates.add(updateInfo.apkFallbackUrl.trim())
        }
        val rawGitHubUrl = "https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/main/apk/CVA-SmartGuardian-v${updateInfo.versionName}.apk"
        val gitHubRedirectUrl = "https://github.com/MrKhang-Khoi/appkhkt2627/raw/main/apk/CVA-SmartGuardian-v${updateInfo.versionName}.apk"
        candidates.add(rawGitHubUrl)
        candidates.add(gitHubRedirectUrl)
        return candidates.toList()
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
     * Tải tệp APK và kiểm tra mã băm SHA-256 với cơ chế tự động chuyển nguồn tải khi gặp HTTP 404
     */
    suspend fun downloadAndVerifyApk(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val candidateUrls = getCandidateDownloadUrls(updateInfo)
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val destinationFile = File(downloadDir, "CVA-SmartGuardian-v${updateInfo.versionName}.apk")

        var lastError: Exception? = null

        for (candidateUrl in candidateUrls) {
            try {
                Log.d(TAG, "Đang thử tải APK từ nguồn: $candidateUrl")
                if (destinationFile.exists()) destinationFile.delete()

                val request = Request.Builder()
                    .url(candidateUrl)
                    .header("Cache-Control", "no-cache")
                    .build()

                val callSuccess = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Tải thất bại từ $candidateUrl, HTTP: ${response.code}")
                        lastError = Exception("Tải file thất bại từ $candidateUrl, HTTP: ${response.code}")
                        return@use false
                    }

                    val body = response.body
                    if (body == null) {
                        Log.w(TAG, "Phản hồi rỗng từ $candidateUrl")
                        lastError = Exception("Phản hồi rỗng từ $candidateUrl")
                        return@use false
                    }

                    val contentLength = body.contentLength()
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
                                    withContext(mainDispatcher) {
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
                            lastError = SecurityException("Mã băm SHA-256 không khớp! File từ $candidateUrl có thể bị hỏng hoặc đã bị sửa đổi.")
                            return@use false
                        }
                    }

                    true
                }

                if (callSuccess && destinationFile.exists() && destinationFile.length() > 0) {
                    Log.d(TAG, "Tải và kiểm tra APK thành công từ nguồn: $candidateUrl")
                    return@withContext Result.success(destinationFile)
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    if (destinationFile.exists()) destinationFile.delete()
                    throw e
                }
                Log.w(TAG, "Lỗi ngoại lệ khi tải từ $candidateUrl: ${e.message}")
                lastError = e
                if (destinationFile.exists()) destinationFile.delete()
            }
        }

        Result.failure(lastError ?: Exception("Không thể tải APK từ bất kỳ nguồn nào khả dụng (HTTP 404 hoặc mạng lỗi)"))
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
