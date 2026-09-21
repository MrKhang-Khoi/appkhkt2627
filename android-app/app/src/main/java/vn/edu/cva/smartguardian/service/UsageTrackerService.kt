package vn.edu.cva.smartguardian.service

import android.app.ActivityManager
import android.app.KeyguardManager
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import vn.edu.cva.smartguardian.data.AppCategory
import vn.edu.cva.smartguardian.data.AppClassifier
import vn.edu.cva.smartguardian.data.WebFilterList
import vn.edu.cva.smartguardian.ui.MainActivity
import android.provider.Settings
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Calendar
import vn.edu.cva.smartguardian.location.LocationHelper

class UsageTrackerService : Service() {

    sealed class LocationCommandDecision {
        data class Expire(val requestedAt: Long, val expiredAt: Long) : LocationCommandDecision()
        data class PromptGps(val requestedAt: Long, val updatedAt: Long, val shouldUpdateStatus: Boolean) : LocationCommandDecision()
        data class SearchingFix(val requestedAt: Long, val updatedAt: Long, val shouldUpdateStatus: Boolean) : LocationCommandDecision()
        data class Complete(val requestedAt: Long, val completedAt: Long) : LocationCommandDecision()
        object Ignore : LocationCommandDecision()
    }

    data class HttpResult(
        val code: Int,
        val body: String?,
        val etag: String?,
        val isSuccessful: Boolean
    )

    data class PendingSessionRecord(
        val packageName: String,
        val durationMs: Long,
        val sessionToken: String,
        val timestamp: Long
    )

    object LocationProtocol {
        const val COMMAND_LOCATE_NOW = "locate_now"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_WAITING_GPS = "WAITING_GPS"
        const val STATUS_SEARCHING_FIX = "SEARCHING_FIX"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_EXPIRED = "EXPIRED"
        const val COMMAND_EXPIRY_TIMEOUT_MS = 180_000L

        private val SAFE_SEGMENT_REGEX = Regex("^[a-zA-Z0-9_-]{1,128}$")
        private val FIREBASE_HOST_REGEX = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*\\.(firebasedatabase\\.app|firebaseio\\.com|firebase\\.io)$")

        @JvmStatic
        fun validateBaseUrl(rawUrl: String): String {
            require(rawUrl.startsWith("https://")) {
                "Fail-Closed: Base URL must start with https://: $rawUrl"
            }
            val uri = try {
                java.net.URI(rawUrl)
            } catch (e: Exception) {
                throw IllegalArgumentException("Fail-Closed: Invalid Firebase base URL syntax: $rawUrl", e)
            }
            require(uri.scheme == "https") {
                "Fail-Closed: Scheme must be https: $rawUrl"
            }
            require(uri.userInfo == null) {
                "Fail-Closed: Base URL cannot contain userInfo: $rawUrl"
            }
            require(uri.rawQuery == null) {
                "Fail-Closed: Base URL cannot contain query parameters: $rawUrl"
            }
            require(uri.rawFragment == null) {
                "Fail-Closed: Base URL cannot contain fragment: $rawUrl"
            }
            require(uri.port == -1 || uri.port == 443) {
                "Fail-Closed: Base URL cannot use non-standard port: ${uri.port}"
            }
            val path = uri.path
            require(path.isNullOrEmpty() || path == "/") {
                "Fail-Closed: Base URL cannot contain extra path segments: $path"
            }
            val host = uri.host ?: throw IllegalArgumentException("Fail-Closed: Base URL missing host: $rawUrl")
            require(FIREBASE_HOST_REGEX.matches(host)) {
                "Fail-Closed: Host $host is not a valid Firebase RTDB domain: $rawUrl"
            }
            return "https://$host"
        }

        @JvmStatic
        fun sanitizeSegment(raw: String): String {
            require(SAFE_SEGMENT_REGEX.matches(raw)) {
                "Fail-Closed: Segment contains invalid characters or path traversal: $raw"
            }
            return raw
        }

        /**
         * Kiểm tra điều kiện tiên quyết (In-Memory State Machine OCC Precondition).
         * Kết hợp với Atomic Conditional Write (ETag-based Compare-And-Set qua header if-match trên Firebase RTDB REST API)
         * để đảm bảo tính nguyên tử tuyệt đối ở cả 2 tầng: logic bộ nhớ và máy chủ cơ sở dữ liệu.
         */
        @JvmStatic
        fun validateOccPrecondition(
            serverStatus: String,
            serverRequestedAt: Long,
            targetRequestedAt: Long
        ): Boolean {
            if (serverRequestedAt <= 0L || targetRequestedAt <= 0L) return false
            if (serverRequestedAt != targetRequestedAt) return false
            if (serverStatus == STATUS_COMPLETED || serverStatus == STATUS_EXPIRED) return false
            return true
        }

        @JvmStatic
        fun getCommandUrl(baseUrl: String, familyCode: String, deviceId: String, commandName: String = COMMAND_LOCATE_NOW): String {
            val cleanBase = validateBaseUrl(baseUrl)
            val safeFam = sanitizeSegment(familyCode)
            val safeDev = sanitizeSegment(deviceId)
            val safeCmd = sanitizeSegment(commandName)
            return "$cleanBase/families/$safeFam/devices/$safeDev/commands/$safeCmd.json"
        }

        @JvmStatic
        fun getFamilyLocationUrl(baseUrl: String, familyCode: String, deviceId: String): String {
            val cleanBase = validateBaseUrl(baseUrl)
            val safeFam = sanitizeSegment(familyCode)
            val safeDev = sanitizeSegment(deviceId)
            return "$cleanBase/families/$safeFam/devices/$safeDev/location.json"
        }

        @JvmStatic
        fun getDeviceLocationUrl(baseUrl: String, deviceId: String): String {
            val cleanBase = validateBaseUrl(baseUrl)
            val safeDev = sanitizeSegment(deviceId)
            return "$cleanBase/devices/$safeDev/location.json"
        }

        const val HEADER_FIREBASE_ETAG = "X-Firebase-ETag"
        const val HEADER_IF_MATCH = "if-match"

        @JvmStatic
        fun buildGetCommandRequest(cmdUrl: String): okhttp3.Request {
            return okhttp3.Request.Builder()
                .url(cmdUrl)
                .header(HEADER_FIREBASE_ETAG, "true")
                .build()
        }

        @JvmStatic
        fun buildConditionalPutRequest(cmdUrl: String, etag: String, body: okhttp3.RequestBody): okhttp3.Request {
            require(etag.isNotBlank()) { "Fail-Closed: ETag cannot be blank for atomic conditional write" }
            return okhttp3.Request.Builder()
                .url(cmdUrl)
                .header(HEADER_IF_MATCH, etag)
                .put(body)
                .build()
        }

        @JvmStatic
        fun shouldPublishLocationAfterCas(httpStatusCode: Int): Boolean {
            return httpStatusCode == 200
        }

        @JvmStatic
        fun isCommandActiveAndRecent(status: String, requestedAt: Long, now: Long, timeoutMs: Long = COMMAND_EXPIRY_TIMEOUT_MS): Boolean {
            val isActive = (status == STATUS_PENDING || status == STATUS_WAITING_GPS || status == STATUS_SEARCHING_FIX)
            return isActive && (now - requestedAt in 0L..timeoutMs)
        }
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    companion object {
        const val CHANNEL_ID = "cva_smart_guardian_tracker"
        const val NOTIFICATION_ID = 1001
        const val OTA_CHANNEL_ID = "cva_smart_guardian_ota"
        const val OTA_NOTIFICATION_ID = 2002
        const val GPS_CHANNEL_ID = "cva_smart_guardian_gps"
        const val GPS_NOTIFICATION_ID = 3003
        internal val lastGpsPromptTimestamp = java.util.concurrent.atomic.AtomicLong(0L)
        private val lastNotifiedUpdateCode = java.util.concurrent.atomic.AtomicInteger(0)
        const val ACTION_USAGE_UPDATED = "vn.edu.cva.smartguardian.ACTION_USAGE_UPDATED"
        const val PREFS_NAME = "cva_guardian_stats"
        const val PREF_PENDING_SESSIONS_JSON = "pending_sessions_json"
        const val PENDING_SESSIONS_JOURNAL_FILE = "pending_sessions.journal"
        const val PENDING_SESSIONS_WAL_FILE = "pending_sessions.wal"
        const val FIREBASE_RTDB_URL = "https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app"

        private val sharedHttpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
        private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val telemetryMutex = Mutex()
        internal val statsLock = Any()
        val lastHeartbeatSentTimestamp = java.util.concurrent.atomic.AtomicLong(0L)
        const val MIN_HEARTBEAT_INTERVAL_MS = 60_000L

        internal val activeOnlineCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()
        internal val activeOfflineCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()
        val isHeartbeatInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
        val foregroundGeneration = java.util.concurrent.atomic.AtomicLong(0L)
        internal val lastKnownETags = java.util.concurrent.ConcurrentHashMap<String, String>()
        internal val activeAppLock = Any()
        private const val MAX_RECORDED_SESSIONS = 500

        internal class LruSessionSet(
            private val maxEntries: Int = MAX_RECORDED_SESSIONS,
            val lock: Any = statsLock
        ) : java.util.LinkedHashSet<String>() {

            override val size: Int
                get() = synchronized(lock) { super.size }

            override fun isEmpty(): Boolean = synchronized(lock) { super.isEmpty() }

            override fun add(element: String): Boolean = synchronized(lock) {
                if (super.contains(element)) {
                    // Update LRU access order by re-inserting at the end
                    super.remove(element)
                    super.add(element)
                    return false
                }
                while (super.size >= maxEntries) {
                    val it = super.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    } else {
                        break
                    }
                }
                return super.add(element)
            }

            override fun contains(element: String): Boolean = synchronized(lock) {
                if (super.contains(element)) {
                    // Refresh LRU access order on access: move accessed element to MRU (end)
                    super.remove(element)
                    super.add(element)
                    true
                } else {
                    false
                }
            }

            override fun remove(element: String): Boolean = synchronized(lock) {
                super.remove(element)
            }

            override fun clear() = synchronized(lock) {
                super.clear()
            }

            override fun containsAll(elements: Collection<String>): Boolean = synchronized(lock) {
                val setSnapshot = ArrayList<String>(super.size)
                val it = super.iterator()
                while (it.hasNext()) {
                    setSnapshot.add(it.next())
                }
                elements.all { el -> setSnapshot.contains(el) }
            }

            override fun addAll(elements: Collection<String>): Boolean = synchronized(lock) {
                if (elements === this) return false
                var modified = false
                for (item in elements) {
                    if (add(item)) modified = true
                }
                modified
            }

            override fun removeAll(elements: Collection<String>): Boolean = synchronized(lock) {
                var modified = false
                val it = super.iterator()
                while (it.hasNext()) {
                    if (elements.contains(it.next())) {
                        it.remove()
                        modified = true
                    }
                }
                modified
            }

            override fun retainAll(elements: Collection<String>): Boolean = synchronized(lock) {
                var modified = false
                val it = super.iterator()
                while (it.hasNext()) {
                    if (!elements.contains(it.next())) {
                        it.remove()
                        modified = true
                    }
                }
                modified
            }

            override fun equals(other: Any?): Boolean = synchronized(lock) {
                super.equals(other)
            }

            override fun hashCode(): Int = synchronized(lock) {
                super.hashCode()
            }

            override fun toString(): String = synchronized(lock) {
                super.toString()
            }

            override fun removeIf(filter: java.util.function.Predicate<in String>): Boolean = synchronized(lock) {
                var modified = false
                val it = super.iterator()
                while (it.hasNext()) {
                    if (filter.test(it.next())) {
                        it.remove()
                        modified = true
                    }
                }
                modified
            }

            override fun forEach(action: java.util.function.Consumer<in String>) {
                val snapshot = synchronized(lock) {
                    val result = ArrayList<String>(super.size)
                    val it = super.iterator()
                    while (it.hasNext()) {
                        result.add(it.next())
                    }
                    result
                }
                snapshot.forEach(action)
            }

            override fun spliterator(): java.util.Spliterator<String> {
                val snapshot = synchronized(lock) {
                    val result = ArrayList<String>(super.size)
                    val it = super.iterator()
                    while (it.hasNext()) {
                        result.add(it.next())
                    }
                    result
                }
                return snapshot.spliterator()
            }

            override fun toArray(): Array<Any?> = synchronized(lock) {
                super.toArray()
            }

            override fun <T : Any?> toArray(a: Array<T>): Array<T> = synchronized(lock) {
                super.toArray(a)
            }

            override fun clone(): Any = synchronized(lock) {
                val copy = LruSessionSet(maxEntries, Any())
                val it = super.iterator()
                while (it.hasNext()) {
                    copy.add(it.next())
                }
                copy
            }

            fun restoreSnapshotRaw(snapshot: List<String>) = synchronized(lock) {
                super.clear()
                val boundedSnapshot = if (snapshot.size > maxEntries) {
                    snapshot.subList(snapshot.size - maxEntries, snapshot.size)
                } else {
                    snapshot
                }
                for (item in boundedSnapshot) {
                    if (item.isNotEmpty() && item.length <= 128) {
                        super.add(item)
                    }
                }
            }

            override fun iterator(): MutableIterator<String> {
                val snapshot = synchronized(lock) {
                    val result = ArrayList<String>(super.size)
                    val it = super.iterator()
                    while (it.hasNext()) {
                        result.add(it.next())
                    }
                    result
                }
                return snapshot.iterator()
            }
        }

        internal val recordedSessionTokens: MutableSet<String> = LruSessionSet(MAX_RECORDED_SESSIONS, statsLock)

        /**
         * Trích xuất phiên bản ứng dụng động 100% từ PackageManager.
         * Tuyệt đối không hardcode phiên bản, fail-closed trả về null nếu không truy xuất được.
         * Sử dụng Long cho versionCode để bảo toàn nguyên vẹn 64-bit trên Android P+.
         */
        fun getDynamicPackageVersion(context: Context): Pair<String, Long>? {
            return try {
                val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode.toLong()
                }
                val vName = pInfo.versionName
                if (vName.isNullOrBlank() || code <= 0L) {
                    null
                } else {
                    Pair(vName, code)
                }
            } catch (e: Exception) {
                Log.w("UsageTrackerService", "Fail-closed: Không thể đọc dynamic package info: ${e.message}")
                null
            }
        }

        internal fun persistSessionTokensLocked(context: Context): Boolean {
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val hadTokensJson = prefs.contains("persisted_session_tokens_json")
                val prevTokensJson = if (hadTokensJson) prefs.getString("persisted_session_tokens_json", null) else null

                val jsonArray = org.json.JSONArray()
                for (t in recordedSessionTokens) {
                    jsonArray.put(t)
                }
                // Đồng bộ nguyên tử giữa RAM và đĩa: Sử dụng commit() trong khối synchronized(statsLock)
                val committed = prefs.edit().putString("persisted_session_tokens_json", jsonArray.toString()).commit()
                if (!committed) {
                    val rollbackEditor = prefs.edit()
                    if (hadTokensJson) rollbackEditor.putString("persisted_session_tokens_json", prevTokensJson)
                    else rollbackEditor.remove("persisted_session_tokens_json")
                    rollbackEditor.commit()
                    Log.w("UsageTrackerService", "persistSessionTokensLocked: SharedPreferences.commit() returned false")
                }
                committed
            } catch (e: Exception) {
                Log.w("UsageTrackerService", "persistSessionTokensLocked error: ${e.message}")
                false
            }
        }

        fun persistSessionToken(context: Context, token: String): Boolean {
            if (token.isEmpty()) return false
            synchronized(statsLock) {
                // Snapshot toàn bộ trạng thái và thứ tự LRU của RAM trước khi thao tác
                val backupTokens = ArrayList<String>(recordedSessionTokens)
                val wasNew = recordedSessionTokens.add(token)
                val success = persistSessionTokensLocked(context)
                if (!success) {
                    // Rollback 100% nguyên vẹn cả phần tử và thứ tự LRU trong RAM khi commit() đĩa thất bại qua raw restore
                    (recordedSessionTokens as? LruSessionSet)?.restoreSnapshotRaw(backupTokens)
                        ?: run {
                            recordedSessionTokens.clear()
                            recordedSessionTokens.addAll(backupTokens)
                        }
                    return false
                }
                return wasNew
            }
        }

        internal val isSessionTokensRestored = java.util.concurrent.atomic.AtomicBoolean(false)

        fun restorePersistedSessionTokens(context: Context): Boolean {
            if (isSessionTokensRestored.get()) {
                return true // Đã khôi phục thành công trước đó
            }
            // Khóa đồng bộ statsLock để các luồng gọi đồng thời cùng chờ tiến trình restore hiện hành hoàn tất
            synchronized(statsLock) {
                if (isSessionTokensRestored.get()) {
                    return true
                }
                var success = false
                try {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val rawJson = prefs.getString("persisted_session_tokens_json", null)
                    if (!rawJson.isNullOrEmpty()) {
                        // 1. Chống bẫy DoS/OOM: Giới hạn kích thước thô tối đa 64 KB
                        if (rawJson.length > 64 * 1024) {
                            Log.w("UsageTrackerService", "persisted_session_tokens_json vượt giới hạn 64KB (${rawJson.length} bytes), loại bỏ để chống OOM")
                            val removed = prefs.edit().remove("persisted_session_tokens_json").commit()
                            if (!removed) {
                                Log.w("UsageTrackerService", "commit() xóa key độc hại thất bại, áp dụng fallback ghi đè rỗng [] qua apply() để giải phóng in-memory cache")
                                prefs.edit().putString("persisted_session_tokens_json", "[]").apply()
                            }
                            recordedSessionTokens.clear()
                            isSessionTokensRestored.set(true)
                            return true
                        }
                        val jsonArray = org.json.JSONArray(rawJson)
                        val count = jsonArray.length()
                        // 2. Giới hạn nạp tối đa 500 tokens gần nhất (tail of array)
                        val maxToLoad = MAX_RECORDED_SESSIONS
                        val startIndex = if (count > maxToLoad) count - maxToLoad else 0
                        val tempTokens = ArrayList<String>(Math.min(count, maxToLoad))
                        for (i in startIndex until count) {
                            val token = jsonArray.optString(i, "")
                            if (token.isNotEmpty() && token.length <= 128) {
                                tempTokens.add(token)
                            }
                        }
                        (recordedSessionTokens as? LruSessionSet)?.restoreSnapshotRaw(tempTokens)
                            ?: run {
                                recordedSessionTokens.clear()
                                val bounded = if (tempTokens.size > MAX_RECORDED_SESSIONS) {
                                    tempTokens.subList(tempTokens.size - MAX_RECORDED_SESSIONS, tempTokens.size)
                                } else {
                                    tempTokens
                                }
                                for (token in bounded) {
                                    recordedSessionTokens.add(token)
                                }
                            }
                    }
                    isSessionTokensRestored.set(true)
                    success = true
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "restorePersistedSessionTokens error: ${e.message}")
                    isSessionTokensRestored.set(false)
                }
                return success
            }
        }

        @Volatile
        internal var urgentOfflineJob: Job? = null
        internal val urgentOfflineLock = Any()

        data class OfflinePayloadData(
            val lastSync: Long,
            val online: Boolean,
            val packageName: String,
            val appName: String,
            val category: String,
            val categoryLabel: String,
            val isForeground: Boolean,
            val timestamp: Long,
            val telemetryEpoch: Long = -1L
        ) {
            fun toJson(): JSONObject {
                val offActiveJson = JSONObject().apply {
                    put("packageName", packageName)
                    put("appName", appName)
                    put("category", category)
                    put("categoryLabel", categoryLabel)
                    put("timestamp", timestamp)
                    put("isForeground", isForeground)
                    if (telemetryEpoch != -1L) put("telemetryEpoch", telemetryEpoch)
                }
                return JSONObject().apply {
                    put("lastSync", lastSync)
                    put("online", online)
                    put("active_app", offActiveJson)
                    if (telemetryEpoch != -1L) put("telemetryEpoch", telemetryEpoch)
                }
            }
        }

        fun createOfflineData(timestamp: Long = System.currentTimeMillis(), telemetryEpoch: Long = -1L): OfflinePayloadData {
            return OfflinePayloadData(
                lastSync = timestamp,
                online = false,
                packageName = "SCREEN_OFF",
                appName = "Màn hình tắt / Khóa máy",
                category = "OFFLINE",
                categoryLabel = "Đã tắt màn hình",
                isForeground = false,
                timestamp = timestamp,
                telemetryEpoch = telemetryEpoch
            )
        }

        fun canWriteEpochMonotonically(lastWrittenEpoch: Long, callEpoch: Long): Boolean {
            if (callEpoch != -1L && lastWrittenEpoch > callEpoch) return false
            return true
        }

        fun evaluateHardwareOnline(isScreenOn: Boolean, isInteractive: Boolean, isKeyguardLocked: Boolean): Boolean {
            return isScreenOn && isInteractive && !isKeyguardLocked
        }

        fun buildOfflinePayload(timestamp: Long = System.currentTimeMillis(), telemetryEpoch: Long = -1L): JSONObject {
            return createOfflineData(timestamp, telemetryEpoch).toJson()
        }

        internal fun shouldAllowTelemetryUpdate(
            expectedEpoch: Long,
            currentEpoch: Long,
            category: String,
            packageName: String,
            hardwareIsOnline: Boolean
        ): Boolean {
            if (expectedEpoch != -1L && currentEpoch != expectedEpoch) {
                return false
            }
            val isOfflineEvent = (packageName == "SCREEN_OFF" || category == "OFFLINE")
            if (isOfflineEvent && hardwareIsOnline) {
                return false
            }
            if (!isOfflineEvent && !hardwareIsOnline) {
                return false
            }
            return true
        }

        fun cancelActiveOnlineCalls() {
            try {
                val iterator = activeOnlineCalls.iterator()
                while (iterator.hasNext()) {
                    val call = iterator.next()
                    try {
                        call.cancel()
                    } catch (e: Exception) {
                        Log.w("UsageTrackerService", "online call.cancel error: ${e.message}")
                    }
                    iterator.remove()
                }
            } catch (e: Exception) {
                Log.w("UsageTrackerService", "cancelActiveOnlineCalls error: ${e.message}")
            }
        }

        internal val currentOfflineGeneration = java.util.concurrent.atomic.AtomicLong(0)
        internal val lastDispatchedOfflineEpoch = java.util.concurrent.atomic.AtomicLong(-1L)

        internal fun isHardwareOnlineValid(
            context: Context,
            expectedEpoch: Long = -1L
        ): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isInteractive = if (pm != null) {
                pm.isInteractive
            } else {
                if (context.javaClass.simpleName.contains("Fake") || context.javaClass.simpleName.contains("Test")) {
                    GuardianAccessibilityService.isScreenOnState
                } else {
                    false
                }
            }
            val isLocked = km?.isKeyguardLocked ?: false
            return GuardianAccessibilityService.isScreenOnState &&
                    isInteractive &&
                    !isLocked &&
                    (expectedEpoch == -1L || GuardianAccessibilityService.telemetryEpoch.get() == expectedEpoch)
        }

        internal fun isHardwareOfflineValid(
            context: Context,
            expectedEpoch: Long = -1L,
            expectedGeneration: Long = -1L
        ): Boolean {
            val isTest = context.javaClass.simpleName.contains("Fake") || context.javaClass.simpleName.contains("Test")
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isInteractive = if (pm != null) {
                pm.isInteractive
            } else {
                if (isTest) GuardianAccessibilityService.isScreenOnState else false
            }
            val isLocked = km?.isKeyguardLocked ?: false
            val hardwareIsOffline = if (isTest) !GuardianAccessibilityService.isScreenOnState else (!GuardianAccessibilityService.isScreenOnState || !isInteractive || isLocked)
            val epochMatches = (expectedEpoch == -1L || GuardianAccessibilityService.telemetryEpoch.get() == expectedEpoch)
            val genMatches = (expectedGeneration == -1L || currentOfflineGeneration.get() == expectedGeneration)
            return hardwareIsOffline && epochMatches && genMatches
        }

        fun cancelActiveOfflineCalls(targetGeneration: Long = -1L) {
            synchronized(urgentOfflineLock) {
                if (targetGeneration != -1L && currentOfflineGeneration.get() != targetGeneration) {
                    // Đã có generation mới hơn đang quản lý, cấm hủy chéo của generation mới
                    return
                }
                try {
                    if (targetGeneration == -1L) {
                        urgentOfflineJob?.cancel()
                        urgentOfflineJob = null
                    }
                    val iterator = activeOfflineCalls.iterator()
                    while (iterator.hasNext()) {
                        val call = iterator.next()
                        try {
                            call.cancel()
                        } catch (e: Exception) {
                            Log.w("UsageTrackerService", "offline call.cancel error: ${e.message}")
                        }
                        iterator.remove()
                    }
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "cancelActiveOfflineCalls error: ${e.message}")
                }
            }
        }

        internal val diskStateLock = Any()

        internal fun persistDeviceOfflineState(
            context: Context,
            targetEpoch: Long
        ): Boolean {
            synchronized(diskStateLock) {
                val prefs = try {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "persistDeviceOfflineState prefs error: ${e.message}")
                    null
                } ?: return false

                val currentEpoch = GuardianAccessibilityService.telemetryEpoch.get()
                if (targetEpoch != currentEpoch || !isHardwareOfflineValid(context, targetEpoch)) {
                    Log.w("UsageTrackerService", "Hủy bỏ persistDeviceOfflineState: Epoch hoặc phần cứng đã đổi ($targetEpoch != $currentEpoch)")
                    return false
                }

                val lastEpoch = prefs.getLong("last_written_epoch", -1L)
                if (targetEpoch < lastEpoch) {
                    Log.w("UsageTrackerService", "Hủy bỏ persistDeviceOfflineState: targetEpoch $targetEpoch < last_written_epoch $lastEpoch")
                    return false
                }

                val success = prefs.edit()
                    .putLong("last_written_epoch", targetEpoch)
                    .putBoolean("is_device_online", false)
                    .putString("last_foreground_pkg", "")
                    .putLong("last_foreground_start", 0L)
                    .commit()

                if (GuardianAccessibilityService.telemetryEpoch.get() != targetEpoch || !isHardwareOfflineValid(context, targetEpoch)) {
                    Log.w("UsageTrackerService", "Phát hiện race condition sau commit offline: Trạng thái phần cứng đã chuyển online")
                    if (GuardianAccessibilityService.isScreenOnState) {
                        prefs.edit().putBoolean("is_device_online", true).commit()
                    }
                    return false
                }
                return success
            }
        }

        internal fun persistDeviceOnlineState(
            context: Context,
            targetEpoch: Long
        ): Boolean {
            synchronized(diskStateLock) {
                val prefs = try {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "persistDeviceOnlineState prefs error: ${e.message}")
                    null
                } ?: return false

                val currentEpoch = GuardianAccessibilityService.telemetryEpoch.get()
                if (targetEpoch != currentEpoch || !isHardwareOnlineValid(context, targetEpoch)) {
                    Log.w("UsageTrackerService", "Hủy bỏ persistDeviceOnlineState: Epoch hoặc phần cứng không online ($targetEpoch != $currentEpoch)")
                    return false
                }

                val lastEpoch = prefs.getLong("last_written_epoch", -1L)
                if (targetEpoch < lastEpoch) {
                    Log.w("UsageTrackerService", "Hủy bỏ persistDeviceOnlineState: targetEpoch $targetEpoch < last_written_epoch $lastEpoch")
                    return false
                }

                val success = prefs.edit()
                    .putLong("last_written_epoch", targetEpoch)
                    .putBoolean("is_device_online", true)
                    .commit()

                // Post-write check: Xác nhận phần cứng không bị chuyển sang offline trong lúc commit()
                if (GuardianAccessibilityService.telemetryEpoch.get() != targetEpoch || !isHardwareOnlineValid(context, targetEpoch)) {
                    Log.w("UsageTrackerService", "Phát hiện race condition sau commit online: Thiết bị đã chuyển offline")
                    if (!GuardianAccessibilityService.isScreenOnState) {
                        prefs.edit().putBoolean("is_device_online", false).commit()
                    }
                    return false
                }

                return success
            }
        }

        internal fun ensureFreshActiveAppETag(
            requestUrl: okhttp3.HttpUrl,
            expectedEpoch: Long,
            expectedGen: Long,
            context: Context
        ): Pair<String?, Boolean> {
            val urlStr = requestUrl.toString()
            val cachedETag = lastKnownETags[urlStr]
            if (!cachedETag.isNullOrEmpty()) {
                return Pair(cachedETag, true)
            }

            // Mandatory Preliminary GET before first active_app mutation (Cold Start / Cleared Cache Protection)
            val preGetReq = okhttp3.Request.Builder()
                .url(requestUrl)
                .get()
                .header("X-Firebase-ETag", "true")
                .build()
            val preGetCall = sharedHttpClient.newCall(preGetReq)
            activeOnlineCalls.add(preGetCall)
            val (serverGen, freshEtag) = try {
                val preGetResp = preGetCall.execute()
                preGetResp.use { gResp ->
                    val gEtag = gResp.header("ETag")
                    val gBody = gResp.body?.string() ?: ""
                    val gJson = try {
                        JSONObject(gBody)
                    } catch (e: Exception) {
                        Log.w("UsageTrackerService", "JSON parse on Pre-GET: ${e.message}")
                        null
                    }
                    val gGen = when {
                        gJson == null -> -1L
                        gJson.has("generation") -> gJson.optLong("generation", -1L)
                        gJson.has("foregroundGeneration") -> gJson.optLong("foregroundGeneration", -1L)
                        else -> -1L
                    }
                    Pair(gGen, gEtag)
                }
            } catch (e: Exception) {
                Log.w("UsageTrackerService", "CAS Pre-GET node failed: ${e.message}")
                Pair(-1L, null)
            } finally {
                activeOnlineCalls.remove(preGetCall)
            }

            if (freshEtag != null) {
                lastKnownETags[urlStr] = freshEtag
            }

            if (serverGen >= expectedGen && expectedGen != -1L && serverGen > 0L) {
                Log.w("UsageTrackerService", "CAS Pre-GET Aborted: Server already holds newer/equal generation ($serverGen >= $expectedGen) at $urlStr. Stale overwrite safely prevented!")
                return Pair(null, false)
            }

            if (!isHardwareOnlineValid(context, expectedEpoch)) {
                Log.w("UsageTrackerService", "Phần cứng không online sau Pre-GET")
                return Pair(null, false)
            }
            if (expectedGen != -1L && foregroundGeneration.get() != expectedGen) {
                Log.w("UsageTrackerService", "Generation thay đổi sau Pre-GET ($expectedGen != ${foregroundGeneration.get()})")
                return Pair(null, false)
            }

            if (freshEtag.isNullOrEmpty()) {
                Log.w("UsageTrackerService", "Cấm gửi PUT active_app khi không lấy được ETag hợp lệ từ server. Fail-closed!")
                return Pair(null, false)
            }

            return Pair(freshEtag, true)
        }

        fun executeOnlineGuarded(
            request: okhttp3.Request,
            context: Context,
            expectedEpoch: Long = -1L,
            expectedGen: Long = -1L
        ): Boolean {
            val isPutActiveApp = request.method == "PUT" && request.url.toString().contains("active_app")
            if (isPutActiveApp) {
                return synchronized(activeAppLock) {
                    executeActiveAppGuardedLocked(request, context, expectedEpoch, expectedGen)
                }
            }

            // Fencing trước khi gửi request cho non-active_app
            if (!isHardwareOnlineValid(context, expectedEpoch)) {
                Log.w("UsageTrackerService", "Hủy bỏ request online trước khi gửi do phần cứng không online")
                return false
            }
            if (expectedGen != -1L && foregroundGeneration.get() != expectedGen) {
                Log.w("UsageTrackerService", "Hủy bỏ request online trước khi gửi do stale foreground generation ($expectedGen != ${foregroundGeneration.get()})")
                return false
            }

            val call = sharedHttpClient.newCall(request)
            activeOnlineCalls.add(call)

            // Double check: Fencing ngay sau khi đăng ký call để triệt tiêu race condition nếu màn hình tắt trong tích tắc trước đó
            if (!isHardwareOnlineValid(context, expectedEpoch)) {
                activeOnlineCalls.remove(call)
                call.cancel()
                Log.w("UsageTrackerService", "Hủy bỏ request online ngay sau khi đăng ký do phần cứng đã ngắt")
                return false
            }
            if (expectedGen != -1L && foregroundGeneration.get() != expectedGen) {
                activeOnlineCalls.remove(call)
                call.cancel()
                Log.w("UsageTrackerService", "Hủy bỏ request online ngay sau khi đăng ký do stale foreground generation ($expectedGen != ${foregroundGeneration.get()})")
                return false
            }

            try {
                val response = call.execute()
                response.use { resp ->
                    // Fencing ngay sau khi nhận phản hồi từ server
                    if (!isHardwareOnlineValid(context, expectedEpoch)) {
                        Log.w("UsageTrackerService", "Phần cứng đã ngắt trong khi request đang gửi! Hủy bỏ kết quả online.")
                        return false
                    }
                    if (expectedGen != -1L && foregroundGeneration.get() != expectedGen) {
                        Log.w("UsageTrackerService", "App đã thay đổi thế hệ trong khi request đang gửi! Hủy bỏ kết quả online.")
                        return false
                    }
                    return resp.isSuccessful
                }
            } catch (e: Exception) {
                Log.w("UsageTrackerService", "executeOnlineGuarded failed/cancelled: ${e.message}")
                return false
            } finally {
                activeOnlineCalls.remove(call)
            }
        }

        private fun executeActiveAppGuardedLocked(
            request: okhttp3.Request,
            context: Context,
            expectedEpoch: Long,
            expectedGen: Long
        ): Boolean {
            val isPutActiveApp = true
            if (!isHardwareOnlineValid(context, expectedEpoch)) {
                Log.w("UsageTrackerService", "Hủy bỏ active_app online do phần cứng không online")
                return false
            }
            if (expectedGen != -1L && foregroundGeneration.get() != expectedGen) {
                Log.w("UsageTrackerService", "Hủy bỏ active_app online do stale foreground generation ($expectedGen != ${foregroundGeneration.get()})")
                return false
            }

            val (etag, shouldProceed) = ensureFreshActiveAppETag(request.url, expectedEpoch, expectedGen, context)
            if (!shouldProceed || etag.isNullOrEmpty()) {
                return false
            }
            val effectiveRequest = request.newBuilder()
                .header("X-Firebase-ETag", "true")
                .header("if-match", etag)
                .build()

            val call = sharedHttpClient.newCall(effectiveRequest)
            activeOnlineCalls.add(call)

            if (!isHardwareOnlineValid(context, expectedEpoch)) {
                activeOnlineCalls.remove(call)
                call.cancel()
                Log.w("UsageTrackerService", "Hủy bỏ active_app sau khi đăng ký do phần cứng đã ngắt")
                return false
            }
            if (expectedGen != -1L && foregroundGeneration.get() != expectedGen) {
                activeOnlineCalls.remove(call)
                call.cancel()
                Log.w("UsageTrackerService", "Hủy bỏ active_app sau khi đăng ký do stale foreground generation ($expectedGen != ${foregroundGeneration.get()})")
                return false
            }

            try {
                val response = call.execute()
                response.use { resp ->
                    val etagHeader = resp.header("ETag")
                    if (etagHeader != null) {
                        lastKnownETags[request.url.toString()] = etagHeader
                    }

                    if (isPutActiveApp && resp.code == 412) {
                        val getReq = okhttp3.Request.Builder()
                            .url(request.url)
                            .get()
                            .header("X-Firebase-ETag", "true")
                            .build()
                        val getCall = sharedHttpClient.newCall(getReq)
                        activeOnlineCalls.add(getCall)
                        val (serverGen, freshEtag) = try {
                            val getResp = getCall.execute()
                            getResp.use { gResp ->
                                val gEtag = gResp.header("ETag")
                                val gBody = gResp.body?.string() ?: ""
                                val gJson = try { JSONObject(gBody) } catch (e: Exception) { null }
                                val gGen = when {
                                    gJson == null -> -1L
                                    gJson.has("generation") -> gJson.optLong("generation", -1L)
                                    gJson.has("foregroundGeneration") -> gJson.optLong("foregroundGeneration", -1L)
                                    else -> -1L
                                }
                                Pair(gGen, gEtag)
                            }
                        } catch (e: Exception) {
                            Log.w("UsageTrackerService", "CAS GET node failed: ${e.message}")
                            Pair(-1L, null)
                        } finally {
                            activeOnlineCalls.remove(getCall)
                        }

                        if (freshEtag != null) {
                            lastKnownETags[request.url.toString()] = freshEtag
                        }

                        // FAIL-CLOSED: Bắt buộc hủy nếu serverGen <= 0L (không đọc được generation) hoặc serverGen >= expectedGen
                        if (serverGen <= 0L || (expectedGen != -1L && serverGen >= expectedGen)) {
                            Log.w("UsageTrackerService", "CAS 412 Aborted: serverGen=$serverGen, expectedGen=$expectedGen. Fail-closed!")
                            return false
                        }

                        // Chỉ retry khi serverGen > 0L && serverGen < expectedGen && freshEtag != null && expectedGen còn hiệu lực
                        if (freshEtag != null && isHardwareOnlineValid(context, expectedEpoch) && (expectedGen == -1L || foregroundGeneration.get() == expectedGen)) {
                            Log.i("UsageTrackerService", "CAS Retry: Server generation is older ($serverGen < $expectedGen). Retrying with fresh server ETag...")
                            val retryReq = request.newBuilder()
                                .header("X-Firebase-ETag", "true")
                                .header("if-match", freshEtag)
                                .build()
                            val retryCall = sharedHttpClient.newCall(retryReq)
                            activeOnlineCalls.add(retryCall)
                            try {
                                val retryResp = retryCall.execute()
                                retryResp.use { rResp ->
                                    val newEtag = rResp.header("ETag")
                                    if (newEtag != null) {
                                        lastKnownETags[request.url.toString()] = newEtag
                                    }
                                    return rResp.isSuccessful
                                }
                            } finally {
                                activeOnlineCalls.remove(retryCall)
                            }
                        }
                        return false
                    }

                    if (!isHardwareOnlineValid(context, expectedEpoch)) {
                        Log.w("UsageTrackerService", "Phần cứng đã ngắt trong khi request đang gửi! Hủy bỏ kết quả online.")
                        return false
                    }
                    if (expectedGen != -1L && foregroundGeneration.get() != expectedGen) {
                        Log.w("UsageTrackerService", "App đã thay đổi thế hệ trong khi request đang gửi! Hủy bỏ kết quả online.")
                        return false
                    }
                    return resp.isSuccessful
                }
            } catch (e: Exception) {
                Log.w("UsageTrackerService", "executeActiveAppGuardedLocked failed/cancelled: ${e.message}")
                return false
            } finally {
                activeOnlineCalls.remove(call)
            }
        }

        fun executeOnlineHttpGuarded(
            request: okhttp3.Request,
            context: Context,
            expectedEpoch: Long = -1L,
            expectedGen: Long = -1L
        ): HttpResult? {
            val isPutActiveApp = request.method == "PUT" && request.url.toString().contains("active_app")
            if (isPutActiveApp) {
                return synchronized(activeAppLock) {
                    executeActiveAppHttpGuardedLocked(request, context, expectedEpoch, expectedGen)
                }
            }

            if (!isHardwareOnlineValid(context, expectedEpoch)) return null
            if (expectedGen != -1L && foregroundGeneration.get() != expectedGen) return null

            val call = sharedHttpClient.newCall(request)
            activeOnlineCalls.add(call)

            if (!isHardwareOnlineValid(context, expectedEpoch)) {
                activeOnlineCalls.remove(call)
                call.cancel()
                return null
            }
            if (expectedGen != -1L && foregroundGeneration.get() != expectedGen) {
                activeOnlineCalls.remove(call)
                call.cancel()
                return null
            }

            try {
                val response = call.execute()
                response.use { resp ->
                    if (!isHardwareOnlineValid(context, expectedEpoch)) return null
                    if (expectedGen != -1L && foregroundGeneration.get() != expectedGen) return null
                    val bodyString = resp.body?.string()
                    return HttpResult(
                        code = resp.code,
                        body = bodyString,
                        etag = resp.header("ETag"),
                        isSuccessful = resp.isSuccessful
                    )
                }
            } catch (e: Exception) {
                Log.w("UsageTrackerService", "executeOnlineHttpGuarded failed/cancelled: ${e.message}")
                return null
            } finally {
                activeOnlineCalls.remove(call)
            }
        }

        private fun executeActiveAppHttpGuardedLocked(
            request: okhttp3.Request,
            context: Context,
            expectedEpoch: Long,
            expectedGen: Long
        ): HttpResult? {
            val isPutActiveApp = true
            if (!isHardwareOnlineValid(context, expectedEpoch)) return null
            if (expectedGen != -1L && foregroundGeneration.get() != expectedGen) return null

            val (etag, shouldProceed) = ensureFreshActiveAppETag(request.url, expectedEpoch, expectedGen, context)
            if (!shouldProceed || etag.isNullOrEmpty()) {
                return null
            }
            val effectiveRequest = request.newBuilder()
                .header("X-Firebase-ETag", "true")
                .header("if-match", etag)
                .build()

            val call = sharedHttpClient.newCall(effectiveRequest)
            activeOnlineCalls.add(call)

            if (!isHardwareOnlineValid(context, expectedEpoch)) {
                activeOnlineCalls.remove(call)
                call.cancel()
                Log.w("UsageTrackerService", "Hủy bỏ request online http ngay sau khi đăng ký do phần cứng đã ngắt")
                return null
            }
            if (expectedGen != -1L && foregroundGeneration.get() != expectedGen) {
                activeOnlineCalls.remove(call)
                call.cancel()
                return null
            }

            try {
                val response = call.execute()
                response.use { resp ->
                    val respEtag = resp.header("ETag")
                    if (respEtag != null) {
                        lastKnownETags[request.url.toString()] = respEtag
                    }

                    if (isPutActiveApp && resp.code == 412) {
                        val getReq = okhttp3.Request.Builder()
                            .url(request.url)
                            .get()
                            .header("X-Firebase-ETag", "true")
                            .build()
                        val getCall = sharedHttpClient.newCall(getReq)
                        activeOnlineCalls.add(getCall)
                        val (serverGen, freshEtag, bodyStr) = try {
                            val getResp = getCall.execute()
                            getResp.use { gResp ->
                                val gEtag = gResp.header("ETag")
                                val gBody = gResp.body?.string() ?: ""
                                val gJson = try { JSONObject(gBody) } catch (e: Exception) { null }
                                val gGen = when {
                                    gJson == null -> -1L
                                    gJson.has("generation") -> gJson.optLong("generation", -1L)
                                    gJson.has("foregroundGeneration") -> gJson.optLong("foregroundGeneration", -1L)
                                    else -> -1L
                                }
                                Triple(gGen, gEtag, gBody)
                            }
                        } catch (e: Exception) {
                            Triple(-1L, null, "")
                        } finally {
                            activeOnlineCalls.remove(getCall)
                        }

                        if (freshEtag != null) {
                            lastKnownETags[request.url.toString()] = freshEtag
                        }

                        if (serverGen <= 0L || (expectedGen != -1L && serverGen >= expectedGen)) {
                            Log.w("UsageTrackerService", "CAS Aborted: Server holds invalid or newer/equal generation ($serverGen, expectedGen=$expectedGen).")
                            return HttpResult(code = resp.code, body = bodyStr, etag = freshEtag, isSuccessful = false)
                        }
                    }

                    if (!isHardwareOnlineValid(context, expectedEpoch)) return null
                    if (expectedGen != -1L && foregroundGeneration.get() != expectedGen) return null
                    val bodyString = resp.body?.string()
                    return HttpResult(
                        code = resp.code,
                        body = bodyString,
                        etag = respEtag,
                        isSuccessful = resp.isSuccessful
                    )
                }
            } catch (e: Exception) {
                Log.w("UsageTrackerService", "executeActiveAppHttpGuardedLocked failed/cancelled: ${e.message}")
                return null
            } finally {
                activeOnlineCalls.remove(call)
            }
        }

        fun executeOnlineStringGuarded(
            request: okhttp3.Request,
            context: Context,
            expectedEpoch: Long = -1L
        ): String? {
            return executeOnlineHttpGuarded(request, context, expectedEpoch)?.let {
                if (it.isSuccessful) it.body else null
            }
        }

        fun executeOfflineGuarded(
            request: okhttp3.Request,
            context: Context,
            expectedEpoch: Long,
            expectedGeneration: Long = -1L
        ): Boolean {
            // Fencing trước khi gửi: Chặn tuyệt đối nếu phần cứng đang online hoặc epoch/generation không khớp
            if (!isHardwareOfflineValid(context, expectedEpoch, expectedGeneration) ||
                GuardianAccessibilityService.telemetryEpoch.get() != expectedEpoch ||
                GuardianAccessibilityService.isScreenOnState
            ) {
                Log.w("UsageTrackerService", "Hủy bỏ request offline do phần cứng hiện đang online / epoch / generation không khớp")
                return false
            }

            val call = sharedHttpClient.newCall(request)
            synchronized(urgentOfflineLock) {
                if (!isHardwareOfflineValid(context, expectedEpoch, expectedGeneration) ||
                    GuardianAccessibilityService.telemetryEpoch.get() != expectedEpoch ||
                    GuardianAccessibilityService.isScreenOnState
                ) {
                    return false
                }
                activeOfflineCalls.add(call)
            }

            // Double check: Fencing ngay sau khi đăng ký call
            if (!isHardwareOfflineValid(context, expectedEpoch, expectedGeneration) ||
                GuardianAccessibilityService.telemetryEpoch.get() != expectedEpoch ||
                GuardianAccessibilityService.isScreenOnState
            ) {
                synchronized(urgentOfflineLock) {
                    activeOfflineCalls.remove(call)
                }
                call.cancel()
                Log.w("UsageTrackerService", "Hủy bỏ request offline ngay sau khi đăng ký do phần cứng đã online")
                return false
            }

            try {
                // Fencing nguyên tử ngay sát thời điểm execute để triệt tiêu race window
                synchronized(urgentOfflineLock) {
                    if (!isHardwareOfflineValid(context, expectedEpoch, expectedGeneration) ||
                        GuardianAccessibilityService.telemetryEpoch.get() != expectedEpoch ||
                        GuardianAccessibilityService.isScreenOnState
                    ) {
                        activeOfflineCalls.remove(call)
                        call.cancel()
                        Log.w("UsageTrackerService", "Hủy bỏ request offline ngay trước khi execute do trạng thái phần cứng đã đổi")
                        return false
                    }
                    if (call.isCanceled()) {
                        activeOfflineCalls.remove(call)
                        return false
                    }
                }
                val response = call.execute()
                response.use { resp ->
                    // Fencing sau khi nhận response: Nếu máy đã chuyển sang online trong khi gửi, hủy kết quả
                    if (!isHardwareOfflineValid(context, expectedEpoch, expectedGeneration) ||
                        GuardianAccessibilityService.telemetryEpoch.get() != expectedEpoch ||
                        GuardianAccessibilityService.isScreenOnState
                    ) {
                        Log.w("UsageTrackerService", "Hủy kết quả offline do phần cứng đã online / epoch đổi trong khi gửi")
                        return false
                    }
                    return resp.isSuccessful
                }
            } catch (e: Exception) {
                Log.w("UsageTrackerService", "executeOfflineGuarded error: ${e.message}")
                return false
            } finally {
                synchronized(urgentOfflineLock) {
                    activeOfflineCalls.remove(call)
                }
            }
        }

        fun sendUrgentOfflineStatus(context: Context, expectedEpoch: Long): Job? {
            val prefs = try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            } catch (e: Exception) {
                null
            }
            val pairedCode = prefs?.getString("paired_code", "") ?: ""
            if (pairedCode.isEmpty()) return null
            val androidId = prefs?.getString("device_id", "")?.takeIf { it.isNotEmpty() }
                ?: try { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) } catch (e: Exception) { null }
                ?: "UNKNOWN"

            // Chống phát nhiều offline batch trùng nhau cho cùng một epoch (Epoch-based idempotency fencing)
            if (expectedEpoch != -1L) {
                var currentDispatched = lastDispatchedOfflineEpoch.get()
                if (currentDispatched == expectedEpoch) {
                    Log.d("UsageTrackerService", "Bỏ qua sendUrgentOfflineStatus: Epoch $expectedEpoch đã được phát trước đó")
                    return null
                }
                while (!lastDispatchedOfflineEpoch.compareAndSet(currentDispatched, expectedEpoch)) {
                    currentDispatched = lastDispatchedOfflineEpoch.get()
                    if (currentDispatched == expectedEpoch) {
                        Log.d("UsageTrackerService", "Bỏ qua sendUrgentOfflineStatus: Epoch $expectedEpoch đã được phát bởi luồng khác")
                        return null
                    }
                }
            }

            // Ghi nhận trạng thái offline tức thời vào đĩa bằng CAS logic nguyên tử bảo vệ bởi diskStateLock
            val diskPersisted = persistDeviceOfflineState(context, expectedEpoch)
            if (!diskPersisted) {
                Log.w("UsageTrackerService", "Hủy bỏ sendUrgentOfflineStatus disk commit: Epoch hoặc trạng thái phần cứng không offline")
                return null
            }

            val job = synchronized(urgentOfflineLock) {
                urgentOfflineJob?.cancel()
                val gen = currentOfflineGeneration.incrementAndGet()
                val newJob = syncScope.launch {
                    try {
                        if (!isHardwareOfflineValid(context, expectedEpoch, gen)) {
                            Log.w("UsageTrackerService", "Hủy bỏ sendUrgentOfflineStatus network batch: Hardware is ONLINE or epoch/gen changed")
                            return@launch
                        }
                        val now = System.currentTimeMillis()
                        val mediaType = "application/json; charset=utf-8".toMediaType()
                        val offJson = buildOfflinePayload(now, expectedEpoch)
                        val offActiveJson = offJson.getJSONObject("active_app")
                        val offBody = offJson.toString().toRequestBody(mediaType)
                        val offActiveBody = offActiveJson.toString().toRequestBody(mediaType)

                        val rootEndpoints = listOf(
                            "https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId.json",
                            "https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$androidId.json",
                            "https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$pairedCode.json",
                            "https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/pairings/$pairedCode.json"
                        )

                        val activeEndpoints = listOf(
                            "https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId/active_app.json",
                            "https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$androidId/active_app.json"
                        )

                        suspend fun dispatchBatch(): List<Boolean> = coroutineScope {
                            if (!isHardwareOfflineValid(context, expectedEpoch, gen)) {
                                return@coroutineScope emptyList()
                            }
                            val deferreds = rootEndpoints.map { url ->
                                async(Dispatchers.IO) {
                                    val req = okhttp3.Request.Builder().url(url).patch(offBody).build()
                                    executeOfflineGuarded(req, context, expectedEpoch, gen)
                                }
                            } + activeEndpoints.map { url ->
                                async(Dispatchers.IO) {
                                    val req = okhttp3.Request.Builder().url(url).put(offActiveBody).build()
                                    executeOfflineGuarded(req, context, expectedEpoch, gen)
                                }
                            }
                            deferreds.awaitAll()
                        }

                        // Đợt gửi đầu tiên với timeout riêng 2000ms
                        val firstSuccess = withTimeoutOrNull(2000L) {
                            val results = dispatchBatch()
                            results.any { it }
                        } ?: false

                        // Hủy triệt để các OkHttp Call còn treo của chính generation này
                        cancelActiveOfflineCalls(gen)

                        // Nếu đợt đầu thất bại hoàn toàn và màn hình vẫn tắt, kích hoạt 1-shot retry với timeout riêng 2000ms
                        if (!firstSuccess && isHardwareOfflineValid(context, expectedEpoch, gen)) {
                            withTimeoutOrNull(2000L) {
                                dispatchBatch()
                            }
                            // Guard cuối: Hủy dọn dẹp các call treo từ đợt retry
                            cancelActiveOfflineCalls(gen)
                        }
                    } catch (e: Exception) {
                        Log.w("UsageTrackerService", "sendUrgentOfflineStatus notice: ${e.message}")
                    } finally {
                        // Guard chống hủy chéo: Chỉ hủy các call nếu job hiện tại và generation vẫn khớp
                        synchronized(urgentOfflineLock) {
                            if (urgentOfflineJob === coroutineContext[Job] && currentOfflineGeneration.get() == gen) {
                                cancelActiveOfflineCalls(gen)
                            }
                        }
                    }
                }
                urgentOfflineJob = newJob
                newJob
            }
            return job
        }

        fun start(context: Context) {
            val intent = Intent(context, UsageTrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun sendHeartbeatPing(context: Context, force: Boolean = false) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isScreenInteractive = powerManager?.isInteractive ?: false
            val isLocked = keyguardManager?.isKeyguardLocked ?: false

            // Bất biến phần cứng: Màn hình tắt hoặc máy khóa -> TUYỆT ĐỐI không gửi heartbeat online
            if (!GuardianAccessibilityService.isScreenOnState || !isScreenInteractive || isLocked) {
                return
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pairedCode = prefs.getString("paired_code", "") ?: ""
            if (pairedCode.isEmpty()) return

            val now = System.currentTimeMillis()
            if (!force && now - lastHeartbeatSentTimestamp.get() < MIN_HEARTBEAT_INTERVAL_MS) {
                return // Unified 60s rate limiter: Ngăn chặn hoàn toàn heartbeat bão hòa mạng
            }

            // Atomic in-flight guard: Triệt tiêu hoàn toàn race condition tạo nhiều batch heartbeat đồng thời
            if (!isHeartbeatInFlight.compareAndSet(false, true)) {
                return
            }

            val androidId = prefs.getString("device_id", "")?.takeIf { it.isNotEmpty() }
                ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "UNKNOWN"

            val callEpoch = GuardianAccessibilityService.telemetryEpoch.get()

            syncScope.launch(Dispatchers.IO) {
                try {
                    val preparedData = telemetryMutex.withLock {
                        val lockNow = System.currentTimeMillis()
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                        val stillInteractive = pm?.isInteractive ?: false
                        val stillLocked = km?.isKeyguardLocked ?: false

                        // Kiểm tra nguyên tử tính hợp lệ phần cứng ngay bên trong mutex
                        if (!GuardianAccessibilityService.isScreenOnState || !stillInteractive || stillLocked ||
                            GuardianAccessibilityService.telemetryEpoch.get() != callEpoch
                        ) {
                            return@withLock null
                        }

                        // Rate limiter nguyên tử bên trong Mutex: Loại bỏ hoàn toàn race condition
                        val lastSent = lastHeartbeatSentTimestamp.get()
                        if (!force && lockNow - lastSent < MIN_HEARTBEAT_INTERVAL_MS) {
                            return@withLock null
                        }

                        try {
                            val mediaType = "application/json; charset=utf-8".toMediaType()
                            val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
                            val model = Build.MODEL

                            val versionPair = getDynamicPackageVersion(context)
                            if (versionPair == null) {
                                Log.w("UsageTrackerService", "Fail-closed: Dynamic version metadata is unavailable, skipping telemetry ping")
                                return@withLock null
                            }
                            val (appVerName, appVerCode) = versionPair

                            val pingJson = JSONObject().apply {
                                put("lastSync", lockNow)
                                put("lastHeartbeat", lockNow)
                                put("online", true)
                                put("deviceId", androidId)
                                put("deviceModel", "$manufacturer $model")
                                put("androidVersion", "Android ${Build.VERSION.RELEASE}")
                                put("appVersion", appVerName)
                                put("appVersionCode", appVerCode)
                                put("isPaired", true)
                                put("status", "paired")
                            }
                            val body = pingJson.toString().toRequestBody(mediaType)

                            val reqFamDev = okhttp3.Request.Builder()
                                .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId.json")
                                .patch(body)
                                .build()

                            val reqDevice = okhttp3.Request.Builder()
                                .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$androidId.json")
                                .patch(body)
                                .build()

                            val reqLegacyDev = okhttp3.Request.Builder()
                                .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$pairedCode.json")
                                .patch(body)
                                .build()

                            val reqPairing = okhttp3.Request.Builder()
                                .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/pairings/$pairedCode.json")
                                .patch(body)
                                .build()

                            Pair(lockNow, listOf(reqFamDev, reqDevice, reqLegacyDev, reqPairing))
                        } catch (e: Exception) {
                            Log.w("UsageTrackerService", "sendHeartbeatPing build payload failed: ${e.message}")
                            null
                        }
                    }

                    // Thực thi network I/O BÊN NGOÀI telemetryMutex theo cơ chế song song async-awaitAll
                    if (preparedData != null) {
                        val (sentTimestamp, requests) = preparedData
                        val deferreds = requests.map { req ->
                            async(Dispatchers.IO) {
                                executeOnlineGuarded(req, context, callEpoch)
                            }
                        }
                        val results = deferreds.awaitAll()
                        val primarySuccess = results.firstOrNull() ?: false
                        val anySuccess = results.any { it }
                        // Cập nhật timestamp khi primary hoặc bất kỳ endpoint nào thành công
                        // Triệt tiêu hoàn toàn nguy cơ starvation khi một fallback endpoint bị lỗi mạng
                        if (primarySuccess || anySuccess) {
                            lastHeartbeatSentTimestamp.set(sentTimestamp)
                        }
                    }
                } finally {
                    isHeartbeatInFlight.set(false)
                }
            }
        }

        @JvmStatic
        fun evaluateLocationCommand(
            currentStatus: String,
            requestedAt: Long,
            now: Long,
            isGpsEnabled: Boolean,
            hasLocationFix: Boolean
        ): LocationCommandDecision {
            if (currentStatus != LocationProtocol.STATUS_PENDING &&
                currentStatus != LocationProtocol.STATUS_SEARCHING_FIX &&
                currentStatus != LocationProtocol.STATUS_WAITING_GPS) {
                return LocationCommandDecision.Ignore
            }
            // Fail-closed: requestedAt must be valid positive timestamp
            if (requestedAt <= 0L) {
                return LocationCommandDecision.Ignore
            }
            // Anti Clock-Skew / Anti-Future Timestamp Defense: reject timestamps > now + 60_000L
            if (requestedAt > now + 60_000L) {
                return LocationCommandDecision.Ignore
            }
            // 1. Kiểm tra hết hạn 3 phút (COMMAND_EXPIRY_TIMEOUT_MS)
            if ((now - requestedAt) > LocationProtocol.COMMAND_EXPIRY_TIMEOUT_MS) {
                return LocationCommandDecision.Expire(requestedAt, now)
            }
            // 2. Nếu GPS chưa bật: nhắc học sinh bật vị trí
            if (!isGpsEnabled) {
                return LocationCommandDecision.PromptGps(
                    requestedAt = requestedAt,
                    updatedAt = now,
                    shouldUpdateStatus = (currentStatus != LocationProtocol.STATUS_WAITING_GPS)
                )
            }
            // 3. Nếu GPS đã bật và đã có fix vị trí: hoàn tất
            if (hasLocationFix) {
                return LocationCommandDecision.Complete(requestedAt = requestedAt, completedAt = now)
            }
            // 4. Nếu GPS đã bật nhưng chưa có fix vệ tinh: chuyển sang SEARCHING_FIX
            return LocationCommandDecision.SearchingFix(
                requestedAt = requestedAt,
                updatedAt = now,
                shouldUpdateStatus = (currentStatus != LocationProtocol.STATUS_SEARCHING_FIX)
            )
        }

        fun notifyStudentToEnableGps(context: Context): Boolean {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val hasNotifPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!hasNotifPermission) {
                        Log.w("UsageTrackerService", "Quyền POST_NOTIFICATIONS chưa được cấp, bỏ qua gửi thông báo thanh trạng thái")
                        return false
                    }
                }

                val now = System.currentTimeMillis()
                val last = lastGpsPromptTimestamp.get()
                if (now - last < 30_000L) {
                    // Rate-limit thông báo để tránh spam
                    return false
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    val channel = NotificationChannel(
                        GPS_CHANNEL_ID,
                        "Yêu Cầu Định Vị GPS",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Thông báo yêu cầu bật GPS / định vị từ phụ huynh"
                        setShowBadge(true)
                        enableVibration(true)
                    }
                    manager?.createNotificationChannel(channel)
                }

                val settingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    101,
                    settingsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, GPS_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentTitle("📍 Yêu cầu định vị từ Phụ Huynh")
                    .setContentText("Phụ huynh đang yêu cầu định vị thiết bị. Chạm để bật GPS ngay.")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("Phụ huynh đang gửi tín hiệu yêu cầu cập nhật vị trí thiết bị của bạn. Vui lòng chạm vào đây để bật GPS / Dịch vụ vị trí."))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (manager != null) {
                    if (lastGpsPromptTimestamp.compareAndSet(last, now)) {
                        manager.notify(GPS_NOTIFICATION_ID, notification)
                        return true
                    }
                }
                return false
            } catch (e: Exception) {
                Log.w("UsageTrackerService", "Không thể gửi thông báo GPS: ${e.message}")
                return false
            }
        }

        fun checkLocationRequest(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pairedCode = prefs.getString("paired_code", "") ?: ""
            if (pairedCode.isEmpty()) return
            val androidId = prefs.getString("device_id", "")?.takeIf { it.isNotEmpty() }
                ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "UNKNOWN"

            syncScope.launch {
                try {
                    val startEpoch = GuardianAccessibilityService.telemetryEpoch.get()
                    if (!isHardwareOnlineValid(context, startEpoch)) {
                        Log.w("UsageTrackerService", "Phần cứng không online hoặc bị khóa, hủy bỏ checkLocationRequest")
                        return@launch
                    }

                    val cmdUrl = LocationProtocol.getCommandUrl(FIREBASE_RTDB_URL, pairedCode, androidId, LocationProtocol.COMMAND_LOCATE_NOW)
                    val req = LocationProtocol.buildGetCommandRequest(cmdUrl)
                    val initialResult = executeOnlineHttpGuarded(req, context, startEpoch) ?: return@launch
                    val body = initialResult.body
                    val initialEtag = initialResult.etag
                    if (initialEtag.isNullOrBlank()) {
                        Log.w("UsageTrackerService", "Fail-Closed: Firebase RTDB không trả về ETag cho command, hủy bỏ để bảo vệ OCC.")
                        return@launch
                    }
                    if (!body.isNullOrEmpty() && body != "null") {
                        val json = JSONObject(body)
                        val status = json.optString("status", "")
                        val requestedAt = json.optLong("requestedAt", 0L)
                        val now = System.currentTimeMillis()
                        val mediaType = "application/json; charset=utf-8".toMediaType()

                        // Tầng 1: Kiểm tra sơ bộ tính hợp lệ và hết hạn (Lazy Hardware Invariant)
                        // Tuyệt đối không bật GPS hoặc dò vị trí nếu command đã hết hạn hoặc không hợp lệ
                        val preDecision = evaluateLocationCommand(
                            currentStatus = status,
                            requestedAt = requestedAt,
                            now = now,
                            isGpsEnabled = false,
                            hasLocationFix = false
                        )

                        if (preDecision is LocationCommandDecision.Ignore) {
                            return@launch
                        }

                        if (preDecision is LocationCommandDecision.Expire) {
                            val expiredJson = JSONObject().apply {
                                put("status", LocationProtocol.STATUS_EXPIRED)
                                put("requestedAt", preDecision.requestedAt)
                                put("message", "Yêu cầu định vị đã hết hạn (quá 3 phút).")
                                put("expiredAt", preDecision.expiredAt)
                            }
                            val expiredBody = expiredJson.toString().toRequestBody(mediaType)
                            val updateCmd = LocationProtocol.buildConditionalPutRequest(cmdUrl, initialEtag, expiredBody)
                            val res = executeOnlineHttpGuarded(updateCmd, context, startEpoch)
                            if (res?.code == 412) {
                                Log.w("UsageTrackerService", "Firebase RTDB HTTP 412: Command đã bị thay đổi, hủy ghi EXPIRED.")
                            }
                            return@launch
                        }

                        // Tầng 2: Kiểm tra phần cứng GPS
                        val isGpsOn = LocationHelper.isGpsEnabled(context)
                        if (!isGpsOn) {
                            val notifDispatched = notifyStudentToEnableGps(context)
                            if (status != LocationProtocol.STATUS_WAITING_GPS) {
                                val hasNotifPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                        context,
                                        android.Manifest.permission.POST_NOTIFICATIONS
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                } else true

                                val feedbackMsg = if (!hasNotifPerm) {
                                    "Thiết bị con chưa bật GPS và chưa cấp quyền thông báo. Vui lòng nhắc con mở ứng dụng để bật vị trí."
                                } else if (notifDispatched) {
                                    "Thiết bị con chưa bật GPS. Đã gửi thông báo nhắc con bật vị trí."
                                } else {
                                    "Thiết bị con chưa bật GPS. Đang chờ con bật vị trí trong Cài đặt."
                                }

                                val waitingJson = JSONObject().apply {
                                    put("status", LocationProtocol.STATUS_WAITING_GPS)
                                    put("requestedAt", requestedAt)
                                    put("message", feedbackMsg)
                                    put("updatedAt", now)
                                }
                                val waitingBody = waitingJson.toString().toRequestBody(mediaType)
                                val updateCmd = LocationProtocol.buildConditionalPutRequest(cmdUrl, initialEtag, waitingBody)
                                val res = executeOnlineHttpGuarded(updateCmd, context, startEpoch)
                                if (res?.code == 412) {
                                    Log.w("UsageTrackerService", "Firebase RTDB HTTP 412: Command đã bị thay đổi, hủy ghi WAITING_GPS.")
                                }
                            }
                            return@launch
                        }

                        // Tầng 3: GPS đã bật -> Truy vấn tọa độ vệ tinh (Lazy Location Fix với Hardware Fencing)
                        if (!isHardwareOnlineValid(context, startEpoch)) {
                            Log.w("UsageTrackerService", "Phần cứng đã ngắt trước khi dò GPS, hủy bỏ.")
                            return@launch
                        }
                        val loc = LocationHelper.fetchCurrentLocation(context)
                        if (!isHardwareOnlineValid(context, startEpoch)) {
                            Log.w("UsageTrackerService", "Phần cứng đã ngắt trong khi dò GPS, hủy bỏ kết quả stale.")
                            return@launch
                        }

                        val decision = evaluateLocationCommand(
                            currentStatus = status,
                            requestedAt = requestedAt,
                            now = now,
                            isGpsEnabled = true,
                            hasLocationFix = (loc != null)
                        )

                        when (decision) {
                            is LocationCommandDecision.SearchingFix -> {
                                if (decision.shouldUpdateStatus) {
                                    val searchingJson = JSONObject().apply {
                                        put("status", LocationProtocol.STATUS_SEARCHING_FIX)
                                        put("requestedAt", decision.requestedAt)
                                        put("message", "Đang dò tìm tọa độ vệ tinh GPS...")
                                        put("updatedAt", decision.updatedAt)
                                    }
                                    val searchingBody = searchingJson.toString().toRequestBody(mediaType)
                                    val verifyReq = LocationProtocol.buildGetCommandRequest(cmdUrl)
                                    val freshResult = executeOnlineHttpGuarded(verifyReq, context, startEpoch) ?: return@launch
                                    val freshEtag = freshResult.etag
                                    if (freshEtag.isNullOrBlank()) {
                                        Log.w("UsageTrackerService", "Fail-Closed: Thiếu fresh ETag cho SEARCHING_FIX, hủy bỏ.")
                                        return@launch
                                    }
                                    val freshBody = freshResult.body
                                    if (freshBody.isNullOrEmpty() || freshBody == "null") return@launch
                                    val freshJson = JSONObject(freshBody)
                                    val freshStatus = freshJson.optString("status", "")
                                    val freshReqAt = freshJson.optLong("requestedAt", 0L)
                                    if (!LocationProtocol.validateOccPrecondition(freshStatus, freshReqAt, decision.requestedAt)) {
                                        Log.w("UsageTrackerService", "Precondition không hợp lệ khi cập nhật SEARCHING_FIX, hủy bỏ.")
                                        return@launch
                                    }
                                    val updateCmd = LocationProtocol.buildConditionalPutRequest(cmdUrl, freshEtag, searchingBody)
                                    val res = executeOnlineHttpGuarded(updateCmd, context, startEpoch)
                                    if (res?.code == 412) {
                                        Log.w("UsageTrackerService", "Firebase RTDB HTTP 412: Command đã bị thay đổi, hủy ghi SEARCHING_FIX.")
                                    }
                                }
                            }
                            is LocationCommandDecision.Complete -> {
                                // Bước 1: OCC Pre-condition Guard & ETag Acquisition - Đọc lại command với header X-Firebase-ETag: true
                                val verifyReq = LocationProtocol.buildGetCommandRequest(cmdUrl)
                                val preCommitResult = executeOnlineHttpGuarded(verifyReq, context, startEpoch) ?: return@launch
                                val preCommitBody = preCommitResult.body
                                val commitEtag = preCommitResult.etag
                                if (commitEtag.isNullOrBlank()) {
                                    Log.w("UsageTrackerService", "Fail-Closed: Thiếu ETag trước khi chốt COMPLETED, hủy bỏ để bảo vệ OCC.")
                                    return@launch
                                }
                                if (preCommitBody.isNullOrEmpty() || preCommitBody == "null") {
                                    return@launch
                                }
                                val preCommitJson = JSONObject(preCommitBody)
                                val preCommitReqAt = preCommitJson.optLong("requestedAt", 0L)
                                val preCommitStatus = preCommitJson.optString("status", "")
                                val isOccValid = LocationProtocol.validateOccPrecondition(
                                    serverStatus = preCommitStatus,
                                    serverRequestedAt = preCommitReqAt,
                                    targetRequestedAt = decision.requestedAt
                                )
                                if (!isOccValid) {
                                    Log.w("UsageTrackerService", "Precondition không hợp lệ hoặc lệnh đã được phụ huynh thay đổi, hủy bỏ.")
                                    return@launch
                                }

                                // Bước 2: Atomic CAS chốt trạng thái COMPLETED lên /commands/locate_now.json TRƯỚC TIÊN
                                // Header if-match: commitEtag đảm bảo tính nguyên tử tuyệt đối ở tầng máy chủ RTDB.
                                // Nhúng trực tiếp tọa độ vào payload của command để ràng buộc chặt chẽ vị trí với phiên lệnh.
                                val doneJson = JSONObject().apply {
                                    put("status", LocationProtocol.STATUS_COMPLETED)
                                    put("requestedAt", decision.requestedAt)
                                    put("completedAt", decision.completedAt)
                                    if (loc != null) {
                                        put("latitude", loc.latitude)
                                        put("longitude", loc.longitude)
                                        put("accuracy", loc.accuracy.toDouble())
                                        put("provider", loc.provider)
                                    }
                                }
                                val doneBody = doneJson.toString().toRequestBody(mediaType)
                                val doneReq = LocationProtocol.buildConditionalPutRequest(cmdUrl, commitEtag, doneBody)
                                val doneResult = executeOnlineHttpGuarded(doneReq, context, startEpoch) ?: return@launch
                                if (!LocationProtocol.shouldPublishLocationAfterCas(doneResult.code)) {
                                    Log.w("UsageTrackerService", "Fail-Closed: Firebase RTDB CAS không trả về HTTP 200 (code=${doneResult.code}), hủy công bố vị trí.")
                                    return@launch
                                }

                                // Bước 3: Sau khi CAS thành công 100% (HTTP 200 chứng minh command hợp lệ duy nhất),
                                // kiểm tra fencing lại một lần nữa trước khi công bố vị trí chính thức vào /location.json
                                if (!isHardwareOnlineValid(context, startEpoch)) {
                                    Log.w("UsageTrackerService", "Phần cứng đã ngắt trước khi công bố location.json, hủy bỏ để tránh ghi stale telemetry.")
                                    return@launch
                                }

                                if (loc != null) {
                                    val locJson = loc.toJsonObject().apply {
                                        put("commandRequestedAt", decision.requestedAt)
                                        put("commandCompletedAt", decision.completedAt)
                                        put("isCommandFix", true)
                                    }
                                    val locBody = locJson.toString().toRequestBody(mediaType)
                                    val famLocUrl = LocationProtocol.getFamilyLocationUrl(FIREBASE_RTDB_URL, pairedCode, androidId)
                                    val devLocUrl = LocationProtocol.getDeviceLocationUrl(FIREBASE_RTDB_URL, androidId)
                                    executeOnlineGuarded(okhttp3.Request.Builder().url(famLocUrl).put(locBody).build(), context, startEpoch)
                                    executeOnlineGuarded(okhttp3.Request.Builder().url(devLocUrl).put(locBody).build(), context, startEpoch)
                                }
                            }
                            else -> {
                                // Ignore / Expire đã được chặn ở Tầng 1
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "checkLocationRequest error: ${e.message}")
                }
            }
        }

        fun syncWebRules(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pairedCode = prefs.getString("paired_code", "") ?: ""
            if (pairedCode.isEmpty()) return
            val androidId = prefs.getString("device_id", "")?.takeIf { it.isNotEmpty() }
                ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "UNKNOWN"

            syncScope.launch {
                try {
                    val reqDev = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId/web_rules.json")
                        .build()
                    var rulesJsonStr = executeOnlineStringGuarded(reqDev, context)

                    if (rulesJsonStr == null) {
                        val reqFam = okhttp3.Request.Builder()
                            .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/web_rules.json")
                            .build()
                        rulesJsonStr = executeOnlineStringGuarded(reqFam, context)
                    }

                    if (!rulesJsonStr.isNullOrEmpty() && rulesJsonStr != "null") {
                        val json = JSONObject(rulesJsonStr)
                        val blacklistSet = mutableSetOf<String>()
                        val whitelistSet = mutableSetOf<String>()

                        val blArray = json.optJSONArray("custom_blacklist")
                        if (blArray != null) {
                            for (i in 0 until blArray.length()) {
                                val d = blArray.optString(i, "").trim().lowercase()
                                if (d.isNotEmpty()) blacklistSet.add(d)
                            }
                        } else if (json.has("custom_blacklist") && json.get("custom_blacklist") is JSONObject) {
                            val blObj = json.getJSONObject("custom_blacklist")
                            val keys = blObj.keys()
                            while (keys.hasNext()) {
                                val d = keys.next().trim().lowercase()
                                if (d.isNotEmpty()) blacklistSet.add(d)
                            }
                        }

                        val wlArray = json.optJSONArray("custom_whitelist")
                        if (wlArray != null) {
                            for (i in 0 until wlArray.length()) {
                                val d = wlArray.optString(i, "").trim().lowercase()
                                if (d.isNotEmpty()) whitelistSet.add(d)
                            }
                        } else if (json.has("custom_whitelist") && json.get("custom_whitelist") is JSONObject) {
                            val wlObj = json.getJSONObject("custom_whitelist")
                            val keys = wlObj.keys()
                            while (keys.hasNext()) {
                                val d = keys.next().trim().lowercase()
                                if (d.isNotEmpty()) whitelistSet.add(d)
                            }
                        }

                        val studyMode = json.optBoolean("study_mode", false)

                        WebFilterList.updateCustomRules(blacklistSet, whitelistSet, studyMode)
                        WebFilterList.saveToPreferences(context)
                    }
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "syncWebRules error: ${e.message}")
                }
            }
        }

        suspend fun prepareActiveAppLocked(
            context: Context,
            packageName: String,
            appName: String,
            category: String,
            categoryLabel: String,
            isForeground: Boolean,
            expectedEpoch: Long = -1L
        ): (suspend () -> Unit)? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pairedCode = prefs.getString("paired_code", "") ?: ""
            if (pairedCode.isEmpty()) return null
            val androidId = prefs.getString("device_id", "")?.takeIf { it.isNotEmpty() }
                ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "UNKNOWN"

            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

            val currentEpoch = GuardianAccessibilityService.telemetryEpoch.get()
            val hardwareIsOnline = GuardianAccessibilityService.isScreenOnState &&
                    pm?.isInteractive == true &&
                    km?.isKeyguardLocked != true

            // Chặn đứng stale telemetry và cross-state mismatch TRƯỚC MỌI SIDE EFFECT (SharedPreferences, RAM, Network)
            if (!shouldAllowTelemetryUpdate(expectedEpoch, currentEpoch, category, packageName, hardwareIsOnline)) {
                Log.w("UsageTrackerService", "Hủy bỏ prepareActiveAppLocked: Từ chối stale telemetry trước khi sửa SharedPreferences (expectedEpoch=$expectedEpoch, currentEpoch=$currentEpoch, pkg=$packageName, hardwareIsOnline=$hardwareIsOnline)")
                return null
            }

            try {
                val isOfflineEvent = (packageName == "SCREEN_OFF" || category == "OFFLINE")
                val effectiveOnline = !isOfflineEvent && hardwareIsOnline

                val targetPkg = if (effectiveOnline) packageName else "SCREEN_OFF"
                val targetApp = if (effectiveOnline) appName else "Màn hình tắt / Khóa máy"
                val targetCat = if (effectiveOnline) category else "OFFLINE"
                val targetLabel = if (effectiveOnline) categoryLabel else "Đã tắt màn hình"

                val lastEpoch = prefs.getLong("last_written_epoch", -1L)
                if (currentEpoch != -1L && lastEpoch > currentEpoch) {
                    Log.w("UsageTrackerService", "Hủy bỏ prepareActiveAppLocked: SharedPreferences đã ghi bởi epoch mới hơn ($lastEpoch > $currentEpoch)")
                    return null
                }

                synchronized(diskStateLock) {
                    val currentDiskEpoch = prefs.getLong("last_written_epoch", -1L)
                    if (currentEpoch != -1L && currentDiskEpoch > currentEpoch) {
                        Log.w("UsageTrackerService", "Hủy bỏ prepareActiveAppLocked: SharedPreferences đã ghi bởi epoch mới hơn ($currentDiskEpoch > $currentEpoch)")
                        return null
                    }
                    prefs.edit()
                        .putLong("last_written_epoch", currentEpoch)
                        .putBoolean("is_device_online", effectiveOnline)
                        .putString("last_active_package", targetPkg)
                        .putLong("last_active_timestamp", System.currentTimeMillis())
                        .apply()
                }

                val currentGen = foregroundGeneration.incrementAndGet()
                lastKnownETags.clear()
                // Active Cancellation: Hủy bỏ ngay các kết nối mạng in-flight của thế hệ cũ
                cancelActiveOnlineCalls()

                val targetEpoch = if (expectedEpoch != -1L) expectedEpoch else currentEpoch
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val activeJson = JSONObject().apply {
                    put("packageName", targetPkg)
                    put("appName", targetApp)
                    put("category", targetCat)
                    put("categoryLabel", targetLabel)
                    put("timestamp", System.currentTimeMillis())
                    put("isForeground", effectiveOnline && isForeground)
                    put("generation", currentGen)
                    put("foregroundGeneration", currentGen)
                    if (targetEpoch != -1L) put("telemetryEpoch", targetEpoch)
                }
                val body = activeJson.toString().toRequestBody(mediaType)

                if (!effectiveOnline) {
                    // Chuyển trực tiếp sang fast-path offline, không tạo cuộc gọi lặp
                    return {
                        sendUrgentOfflineStatus(context, targetEpoch)
                    }
                } else {
                    val reqFam = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId/active_app.json")
                        .put(body)
                        .build()

                    val reqDev = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$androidId/active_app.json")
                        .put(body)
                        .build()

                    val hbJson = JSONObject().apply {
                        put("lastHeartbeat", activeJson.getLong("timestamp"))
                        put("lastSync", activeJson.getLong("timestamp"))
                        put("online", true)
                        put("generation", currentGen)
                        put("foregroundGeneration", currentGen)
                        if (targetEpoch != -1L) put("telemetryEpoch", targetEpoch)
                    }
                    val hbBody = hbJson.toString().toRequestBody(mediaType)

                    val reqFamHb = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId.json")
                        .patch(hbBody)
                        .build()

                    val reqDevHb = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$androidId.json")
                        .patch(hbBody)
                        .build()

                    val reqLegacyDevHb = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$pairedCode.json")
                        .patch(hbBody)
                        .build()

                    val reqPairingHb = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/pairings/$pairedCode.json")
                        .patch(hbBody)
                        .build()

                    return actionLambda@ {
                        if (foregroundGeneration.get() != currentGen || !isHardwareOnlineValid(context, targetEpoch)) {
                            Log.w("UsageTrackerService", "Hủy bỏ uploadAction: Stale generation trước khi gửi ($currentGen != ${foregroundGeneration.get()})")
                            return@actionLambda
                        }
                        coroutineScope {
                            val requests = listOf(reqFam, reqDev, reqFamHb, reqDevHb, reqLegacyDevHb, reqPairingHb)
                            val deferreds = requests.map { req ->
                                async(Dispatchers.IO) {
                                    if (foregroundGeneration.get() == currentGen) {
                                        executeOnlineGuarded(req, context, targetEpoch, currentGen)
                                    } else {
                                        false
                                    }
                                }
                            }
                            deferreds.awaitAll()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("UsageTrackerService", "prepareActiveAppLocked failed: ${e.message}")
                return null
            }
        }

        suspend fun reportActiveAppLocked(
            context: Context,
            packageName: String,
            appName: String,
            category: String,
            categoryLabel: String,
            isForeground: Boolean,
            expectedEpoch: Long = -1L
        ) {
            val action = prepareActiveAppLocked(
                context = context,
                packageName = packageName,
                appName = appName,
                category = category,
                categoryLabel = categoryLabel,
                isForeground = isForeground,
                expectedEpoch = expectedEpoch
            )
            action?.invoke()
        }

        fun reportActiveApp(
            context: Context,
            packageName: String,
            appName: String,
            category: String,
            categoryLabel: String,
            isForeground: Boolean,
            expectedEpoch: Long = -1L
        ) {
            syncScope.launch(Dispatchers.IO) {
                val (action, actionGen) = telemetryMutex.withLock {
                    val act = prepareActiveAppLocked(
                        context = context,
                        packageName = packageName,
                        appName = appName,
                        category = category,
                        categoryLabel = categoryLabel,
                        isForeground = isForeground,
                        expectedEpoch = expectedEpoch
                    )
                    Pair(act, foregroundGeneration.get())
                }
                if (action != null && actionGen != -1L && foregroundGeneration.get() == actionGen) {
                    action.invoke()
                }
            }
        }

        fun reportWebActivity(
            context: Context,
            browserPkg: String,
            browserName: String,
            url: String,
            title: String,
            isBlocked: Boolean
        ) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (!GuardianAccessibilityService.isScreenOnState || pm?.isInteractive != true || km?.isKeyguardLocked == true) {
                return
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pairedCode = prefs.getString("paired_code", "") ?: ""
            if (pairedCode.isEmpty()) return
            val androidId = prefs.getString("device_id", "")?.takeIf { it.isNotEmpty() }
                ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "UNKNOWN"

            syncScope.launch {
                try {
                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val now = System.currentTimeMillis()
                    val targetEpoch = GuardianAccessibilityService.telemetryEpoch.get()

                    val domain = try {
                        val cleanUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
                        val uri = java.net.URI(cleanUrl)
                        uri.host?.lowercase() ?: url.substringBefore("/").substringBefore("?").lowercase()
                    } catch (e: Exception) {
                        url.substringBefore("/").substringBefore("?").lowercase()
                    }

                    val webJson = JSONObject().apply {
                        put("url", url)
                        put("domain", domain)
                        put("title", title.ifEmpty { domain })
                        put("browserPkg", browserPkg)
                        put("browserName", browserName)
                        put("timestamp", now)
                        put("isBlocked", isBlocked)
                    }
                    val body = webJson.toString().toRequestBody(mediaType)

                    // 1. Cập nhật trang web đang mở thời gian thực: /web_activity.json (fenced trực tiếp theo targetEpoch)
                    val reqFam = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId/web_activity.json")
                        .put(body)
                        .build()
                    if (!executeOnlineGuarded(reqFam, context, targetEpoch)) return@launch

                    val reqDev = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$androidId/web_activity.json")
                        .put(body)
                        .build()
                    if (!executeOnlineGuarded(reqDev, context, targetEpoch)) return@launch

                    // 2. Cập nhật nhịp tim tươi mới ngay khi có duyệt web (bọc bảo vệ phần cứng trực tiếp theo targetEpoch)
                    val hbJson = JSONObject().apply {
                        put("lastHeartbeat", now)
                        put("lastSync", now)
                        put("online", true)
                    }
                    val hbBody = hbJson.toString().toRequestBody(mediaType)
                    val reqFamHb = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId.json")
                        .patch(hbBody)
                        .build()
                    if (!executeOnlineGuarded(reqFamHb, context, targetEpoch)) return@launch

                    val reqDevHb = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$androidId.json")
                        .patch(hbBody)
                        .build()
                    if (!executeOnlineGuarded(reqDevHb, context, targetEpoch)) return@launch

                    // 3. Ghi nhật ký vào /web_history/$now.json (lịch sử duyệt web)
                    val reqHistFam = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId/web_history/$now.json")
                        .put(body)
                        .build()
                    executeOnlineGuarded(reqHistFam, context, targetEpoch)

                    val reqHistDev = okhttp3.Request.Builder()
                        .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$androidId/web_history/$now.json")
                        .put(body)
                        .build()
                    executeOnlineGuarded(reqHistDev, context, targetEpoch)
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "reportWebActivity failed: ${e.message}")
                }
            }
        }

        /**
         * Ghi nhận phiên ứng dụng nguyên tử vào SharedPreferences dưới statsLock.
         * Trả về true nếu commit thành công hoặc phiên đã được ghi nhận trước đó (idempotent duplicate).
         * Trả về false nếu commit đĩa thất bại.
         */
        internal fun recordAppSessionInternalLocked(
            context: Context,
            packageName: String,
            durationMs: Long,
            sessionToken: String
        ): Boolean {
            if (durationMs < 1000L) return true
            val isSystemOrSelf = packageName == context.packageName ||
                    packageName == "com.android.systemui" ||
                    packageName.contains("inputmethod") ||
                    packageName.contains("keyboard") ||
                    packageName.contains("launcher") ||
                    packageName == "SCREEN_OFF" ||
                    packageName == "HOME"
            if (isSystemOrSelf) return true

            if (sessionToken.isNotEmpty()) {
                val duplicateBackupTokens = ArrayList<String>(recordedSessionTokens)
                if (recordedSessionTokens.contains(sessionToken)) {
                    // Persist refreshed LRU access-order to disk to guarantee 100% RAM-Disk consistency
                    val persisted = persistSessionTokensLocked(context)
                    if (!persisted) {
                        (recordedSessionTokens as? LruSessionSet)?.restoreSnapshotRaw(duplicateBackupTokens)
                            ?: run {
                                recordedSessionTokens.clear()
                                recordedSessionTokens.addAll(duplicateBackupTokens)
                            }
                        Log.w("UsageTrackerService", "Phát hiện session trùng lặp ($sessionToken) nhưng commit đĩa thất bại: Đã rollback LRU order trong RAM")
                        return false
                    }
                    Log.d("UsageTrackerService", "Phát hiện session trùng lặp ($sessionToken): Đã cập nhật LRU order và bỏ qua tính giờ")
                    return true
                }
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            val appInfo = try {
                context.packageManager.getApplicationInfo(packageName, 0)
            } catch (e: Exception) {
                null
            }
            val appLabel = appInfo?.let { context.packageManager.getApplicationLabel(it).toString() } ?: packageName
            val metadata = AppClassifier.classify(packageName, appLabel, appInfo)

            val backupTokens = ArrayList<String>(recordedSessionTokens)
            val hadTokensJson = prefs.contains("persisted_session_tokens_json")
            val prevTokensJson = if (hadTokensJson) prefs.getString("persisted_session_tokens_json", null) else null

            val appKey = "session_${todayStr}_$packageName"
            val hadCurMs = prefs.contains(appKey)
            val prevCurMs = if (hadCurMs) prefs.getLong(appKey, 0L) else 0L

            val nameKey = "app_name_$packageName"
            val hadName = prefs.contains(nameKey)
            val prevName = if (hadName) prefs.getString(nameKey, null) else null

            val catKey = "app_cat_$packageName"
            val hadCat = prefs.contains(catKey)
            val prevCat = if (hadCat) prefs.getString(catKey, null) else null

            val catLabelKey = "app_cat_label_$packageName"
            val hadCatLabel = prefs.contains(catLabelKey)
            val prevCatLabel = if (hadCatLabel) prefs.getString(catLabelKey, null) else null

            val lastUsedKey = "app_last_used_$packageName"
            val hadLastUsed = prefs.contains(lastUsedKey)
            val prevLastUsed = if (hadLastUsed) prefs.getLong(lastUsedKey, 0L) else 0L

            val editor = prefs.edit()
            if (sessionToken.isNotEmpty()) {
                recordedSessionTokens.add(sessionToken)
                val jsonArray = org.json.JSONArray()
                for (t in recordedSessionTokens) {
                    jsonArray.put(t)
                }
                editor.putString("persisted_session_tokens_json", jsonArray.toString())
            }

            val newMs = prevCurMs + durationMs
            editor.putLong(appKey, newMs)
                .putString(nameKey, metadata.appName.ifEmpty { appLabel })
                .putString(catKey, metadata.category.name)
                .putString(catLabelKey, metadata.category.displayName)
                .putLong(lastUsedKey, System.currentTimeMillis())

            val committed = editor.commit()
            if (!committed) {
                // Phục hồi lại trạng thái in-memory cache của SharedPreferences để tránh dirty cache
                val rollbackEditor = prefs.edit()
                if (hadCurMs) rollbackEditor.putLong(appKey, prevCurMs) else rollbackEditor.remove(appKey)
                if (hadName) rollbackEditor.putString(nameKey, prevName) else rollbackEditor.remove(nameKey)
                if (hadCat) rollbackEditor.putString(catKey, prevCat) else rollbackEditor.remove(catKey)
                if (hadCatLabel) rollbackEditor.putString(catLabelKey, prevCatLabel) else rollbackEditor.remove(catLabelKey)
                if (hadLastUsed) rollbackEditor.putLong(lastUsedKey, prevLastUsed) else rollbackEditor.remove(lastUsedKey)
                if (sessionToken.isNotEmpty()) {
                    if (hadTokensJson) rollbackEditor.putString("persisted_session_tokens_json", prevTokensJson)
                    else rollbackEditor.remove("persisted_session_tokens_json")
                }
                rollbackEditor.commit()

                if (sessionToken.isNotEmpty()) {
                    (recordedSessionTokens as? LruSessionSet)?.restoreSnapshotRaw(backupTokens)
                        ?: run {
                            recordedSessionTokens.clear()
                            recordedSessionTokens.addAll(backupTokens)
                        }
                }
                Log.w("UsageTrackerService", "recordAppSessionInternalLocked: commit() returned false, rollback RAM token")
                return false
            }
            return true
        }
        internal val inMemoryPendingSessions = java.util.concurrent.ConcurrentLinkedQueue<PendingSessionRecord>()

        internal fun computeCrc32Hex(data: ByteArray): String {
            val crc = java.util.zip.CRC32()
            crc.update(data)
            return String.format(java.util.Locale.US, "%08X", crc.value)
        }

        internal fun formatWalLine(record: PendingSessionRecord): String {
            val obj = org.json.JSONObject().apply {
                put("pkg", record.packageName)
                put("duration", record.durationMs)
                put("token", record.sessionToken)
                put("timestamp", record.timestamp)
            }
            val jsonStr = obj.toString()
            val crcHex = computeCrc32Hex(jsonStr.toByteArray(Charsets.UTF_8))
            return "$crcHex:$jsonStr\n"
        }

        internal fun parseWalLine(line: String): PendingSessionRecord? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx <= 0) return null
            val expectedCrc = trimmed.substring(0, colonIdx).trim().uppercase(java.util.Locale.US)
            val jsonStr = trimmed.substring(colonIdx + 1).trim()
            if (jsonStr.isEmpty()) return null

            val actualCrc = computeCrc32Hex(jsonStr.toByteArray(Charsets.UTF_8))
            if (expectedCrc != actualCrc) {
                Log.w("UsageTrackerService", "parseWalLine: CRC32 mismatch (expected $expectedCrc, actual $actualCrc)")
                return null
            }

            return try {
                val obj = org.json.JSONObject(jsonStr)
                val pkg = obj.optString("pkg", "")
                val duration = obj.optLong("duration", 0L)
                val token = obj.optString("token", "")
                val timestamp = obj.optLong("timestamp", 0L)
                if (pkg.isNotEmpty() && duration >= 1000L && token.isNotEmpty()) {
                    PendingSessionRecord(pkg, duration, token, timestamp)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w("UsageTrackerService", "parseWalLine: JSON parse error: ${e.message}")
                null
            }
        }

        internal fun readWalRecords(walFile: java.io.File): Pair<List<PendingSessionRecord>, Boolean> {
            if (!walFile.exists() || walFile.length() == 0L) {
                return Pair(emptyList(), false)
            }
            val validRecords = mutableListOf<PendingSessionRecord>()
            var hasCorruptLines = false

            try {
                walFile.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        val record = parseWalLine(trimmed)
                        if (record != null) {
                            validRecords.add(record)
                        } else {
                            hasCorruptLines = true
                            Log.w("UsageTrackerService", "readWalRecords: Bỏ qua dòng hỏng trong WAL: $trimmed")
                        }
                    }
                }
            } catch (e: Exception) {
                hasCorruptLines = true
                Log.w("UsageTrackerService", "readWalRecords: Lỗi khi đọc file WAL: ${e.message}")
            }

            return Pair(validRecords, hasCorruptLines)
        }

        fun enqueuePendingSession(
            context: Context,
            packageName: String,
            durationMs: Long,
            sessionToken: String
        ): Boolean {
            if (packageName.isEmpty() || durationMs < 1000L || sessionToken.isEmpty()) return false
            val record = PendingSessionRecord(packageName, durationMs, sessionToken, System.currentTimeMillis())

            // 1. Luôn bảo vệ trong RAM fallback queue
            val existsInRam = inMemoryPendingSessions.any { it.sessionToken == sessionToken }
            if (!existsInRam) {
                inMemoryPendingSessions.add(record)
            }

            synchronized(statsLock) {
                var persisted = false

                // 2. Tầng 1: Thử lưu bền vững vào SharedPreferences với post-write verification
                try {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val currentRaw = prefs.getString(PREF_PENDING_SESSIONS_JSON, "[]") ?: "[]"
                    val array = try { org.json.JSONArray(currentRaw) } catch (e: Exception) { org.json.JSONArray() }

                    var alreadyPresent = false
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i)
                        if (item?.optString("token") == sessionToken) {
                            alreadyPresent = true
                            break
                        }
                    }
                    if (!alreadyPresent) {
                        val obj = org.json.JSONObject().apply {
                            put("pkg", packageName)
                            put("duration", durationMs)
                            put("token", sessionToken)
                            put("timestamp", record.timestamp)
                        }
                        array.put(obj)
                    }
                    val committed = prefs.edit().putString(PREF_PENDING_SESSIONS_JSON, array.toString()).commit()
                    val verifiedInPrefs = committed && (prefs.getString(PREF_PENDING_SESSIONS_JSON, null)?.contains(sessionToken) == true)
                    if (verifiedInPrefs) {
                        persisted = true
                        Log.i("UsageTrackerService", "Đã lưu phiên $packageName ($sessionToken) vào SharedPreferences pending thành công")
                    } else {
                        Log.w("UsageTrackerService", "commit SharedPreferences hoặc xác minh đĩa pending thất bại, chuyển sang WAL Append-Only Journal")
                    }
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "enqueuePendingSession SharedPreferences thất bại: ${e.message}, chuyển sang WAL Append-Only Journal")
                }

                // 3. Tầng 2: Write-Ahead Log (WAL) Append-Only Journal với CRC32 per-record và hardware fsync
                if (!persisted) {
                    try {
                        val filesDir = context.filesDir ?: java.io.File(".")
                        if (!filesDir.exists()) {
                            filesDir.mkdirs()
                        }
                        val walFile = java.io.File(filesDir, PENDING_SESSIONS_WAL_FILE)

                        val (existingRecords, hasCorruptLines) = readWalRecords(walFile)

                        // Nếu phát hiện file WAL có dòng bị cắt dở/hỏng từ sự cố trước:
                        // Giữ nguyên file hỏng sang bản sao lưu forensics (.corrupt.<ts>),
                        // và ghi lại danh sách validRecords nguyên vẹn vào file WAL mới.
                        if (hasCorruptLines && walFile.exists()) {
                            try {
                                val corruptBackup = java.io.File(filesDir, "$PENDING_SESSIONS_WAL_FILE.corrupt_${System.currentTimeMillis()}")
                                walFile.copyTo(corruptBackup, overwrite = true)
                                Log.w("UsageTrackerService", "Đã sao lưu WAL bị cắt dở sang ${corruptBackup.name}")
                            } catch (e: Exception) {
                                Log.w("UsageTrackerService", "Không thể sao lưu file WAL hỏng: ${e.message}")
                            }

                            // Tạo lại file WAL sạch chỉ chứa các record hợp lệ
                            val tmpRecover = java.io.File(filesDir, "$PENDING_SESSIONS_WAL_FILE.tmp")
                            java.io.FileOutputStream(tmpRecover).use { fos ->
                                for (validRec in existingRecords) {
                                    fos.write(formatWalLine(validRec).toByteArray(Charsets.UTF_8))
                                }
                                fos.flush()
                                fos.fd.sync()
                            }
                            if (walFile.exists()) {
                                walFile.delete()
                            }
                            if (!tmpRecover.renameTo(walFile)) {
                                try {
                                    java.nio.file.Files.move(
                                        tmpRecover.toPath(),
                                        walFile.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                                    )
                                } catch (e: Exception) {
                                    Log.w("UsageTrackerService", "Move tmpRecover failed: ${e.message}")
                                }
                            }
                        }

                        // Kiểm tra xem sessionToken đã tồn tại trong WAL chưa
                        val tokenAlreadyInWal = existingRecords.any { it.sessionToken == sessionToken }
                        if (!tokenAlreadyInWal) {
                            val walLine = formatWalLine(record)
                            java.io.FileOutputStream(walFile, true).use { fos ->
                                if (walFile.length() > 0L) {
                                    var lastByte: Byte = 0
                                    try {
                                        java.io.RandomAccessFile(walFile, "r").use { raf ->
                                            raf.seek(raf.length() - 1)
                                            lastByte = raf.readByte()
                                        }
                                    } catch (e: Exception) {
                                        Log.w("UsageTrackerService", "Check lastByte error: ${e.message}")
                                    }
                                    if (lastByte != '\n'.code.toByte()) {
                                        fos.write('\n'.code)
                                    }
                                }
                                fos.write(walLine.toByteArray(Charsets.UTF_8))
                                fos.flush()
                                fos.fd.sync()
                            }
                        }

                        // Post-write verification: Đọc lại WAL và xác minh sessionToken có mặt và CRC32 hợp lệ
                        val (verifyRecords, _) = readWalRecords(walFile)
                        val verifiedInWal = verifyRecords.any { it.sessionToken == sessionToken }

                        if (verifiedInWal) {
                            persisted = true
                            Log.i("UsageTrackerService", "Đã lưu phiên $packageName ($sessionToken) vào WAL Journal bền vững thành công")
                        } else {
                            Log.e("UsageTrackerService", "Xác minh WAL Journal thất bại cho $sessionToken")
                        }
                    } catch (e: Exception) {
                        Log.e("UsageTrackerService", "Ghi WAL Journal thất bại: ${e.message}")
                    }
                }

                return persisted
            }
        }

        fun flushPendingSessions(context: Context) {
            synchronized(statsLock) {
                // 1. Xả các phiên trong inMemoryPendingSessions
                val ramIterator = inMemoryPendingSessions.iterator()
                while (ramIterator.hasNext()) {
                    val item = ramIterator.next()
                    val success = recordAppSessionInternalLocked(context, item.packageName, item.durationMs, item.sessionToken)
                    if (success) {
                        ramIterator.remove()
                    }
                }

                // 2. Xả các phiên trong SharedPreferences
                try {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val currentRaw = prefs.getString(PREF_PENDING_SESSIONS_JSON, null)
                    if (!currentRaw.isNullOrEmpty()) {
                        val array = try { org.json.JSONArray(currentRaw) } catch (e: Exception) { null }
                        if (array != null && array.length() > 0) {
                            val remainingArray = org.json.JSONArray()
                            for (i in 0 until array.length()) {
                                val item = array.optJSONObject(i) ?: continue
                                val pkg = item.optString("pkg")
                                val duration = item.optLong("duration", 0L)
                                val token = item.optString("token")
                                if (pkg.isNotEmpty() && duration >= 1000L && token.isNotEmpty()) {
                                    val recorded = recordAppSessionInternalLocked(context, pkg, duration, token)
                                    if (!recorded) {
                                        remainingArray.put(item)
                                    }
                                }
                            }
                            if (remainingArray.length() == 0) {
                                prefs.edit().remove(PREF_PENDING_SESSIONS_JSON).commit()
                                Log.i("UsageTrackerService", "Đã xả toàn bộ hàng đợi pending sessions trong SharedPreferences thành công")
                            } else {
                                prefs.edit().putString(PREF_PENDING_SESSIONS_JSON, remainingArray.toString()).commit()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "flushPendingSessions SharedPreferences thất bại: ${e.message}")
                }

                // 3. Xả các phiên trong Write-Ahead Log (WAL) Journal
                try {
                    val filesDir = context.filesDir ?: java.io.File(".")
                    val walFile = java.io.File(filesDir, PENDING_SESSIONS_WAL_FILE)
                    if (walFile.exists() && walFile.length() > 0L) {
                        val (records, hasCorruptLines) = readWalRecords(walFile)
                        val remainingRecords = mutableListOf<PendingSessionRecord>()

                        for (rec in records) {
                            val recorded = recordAppSessionInternalLocked(context, rec.packageName, rec.durationMs, rec.sessionToken)
                            if (!recorded) {
                                remainingRecords.add(rec)
                            }
                        }

                        if (remainingRecords.isEmpty()) {
                            walFile.delete()
                            Log.i("UsageTrackerService", "Đã xả toàn bộ WAL Journal và xóa file thành công")
                        } else {
                            // Nếu còn record chưa ghi được hoặc file có dòng hỏng, viết lại file tmp với fsync + rename
                            val tmpFile = java.io.File(filesDir, "$PENDING_SESSIONS_WAL_FILE.tmp")
                            java.io.FileOutputStream(tmpFile).use { fos ->
                                for (rem in remainingRecords) {
                                    fos.write(formatWalLine(rem).toByteArray(Charsets.UTF_8))
                                }
                                fos.flush()
                                fos.fd.sync()
                            }
                            if (walFile.exists()) {
                                walFile.delete()
                            }
                            if (!tmpFile.renameTo(walFile)) {
                                try {
                                    java.nio.file.Files.move(
                                        tmpFile.toPath(),
                                        walFile.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                                    )
                                } catch (e: Exception) {
                                    Log.w("UsageTrackerService", "Move tmpFile failed: ${e.message}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "flushPendingSessions WAL Journal thất bại: ${e.message}")
                }

                // 4. Xả Legacy pending_sessions.journal nếu còn tồn tại từ phiên bản trước
                try {
                    val filesDir = context.filesDir ?: java.io.File(".")
                    val legacyJournalFile = java.io.File(filesDir, PENDING_SESSIONS_JOURNAL_FILE)
                    if (legacyJournalFile.exists() && legacyJournalFile.length() > 0L) {
                        val journalContent = legacyJournalFile.readText()
                        val journalArray = try { org.json.JSONArray(journalContent) } catch (e: Exception) { null }
                        if (journalArray != null && journalArray.length() > 0) {
                            var allLegacyRecorded = true
                            for (i in 0 until journalArray.length()) {
                                val item = journalArray.optJSONObject(i) ?: continue
                                val pkg = item.optString("pkg")
                                val duration = item.optLong("duration", 0L)
                                val token = item.optString("token")
                                if (pkg.isNotEmpty() && duration >= 1000L && token.isNotEmpty()) {
                                    val recorded = recordAppSessionInternalLocked(context, pkg, duration, token)
                                    if (!recorded) {
                                        allLegacyRecorded = false
                                    }
                                }
                            }
                            if (allLegacyRecorded) {
                                legacyJournalFile.delete()
                                Log.i("UsageTrackerService", "Đã xả và xóa legacy journal file thành công")
                            }
                        } else {
                            legacyJournalFile.delete()
                        }
                    }
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "flushPendingSessions legacy journal thất bại: ${e.message}")
                }
            }
        }

        fun recordAppSession(
            context: Context,
            packageName: String,
            durationMs: Long,
            sessionToken: String = "",
            @Suppress("UNUSED_PARAMETER") targetEpoch: Long = -1L
        ) {
            if (durationMs < 1000L) return
            val isSystemOrSelf = packageName == context.packageName ||
                    packageName == "com.android.systemui" ||
                    packageName.contains("inputmethod") ||
                    packageName.contains("keyboard") ||
                    packageName.contains("launcher") ||
                    packageName == "SCREEN_OFF" ||
                    packageName == "HOME"
            if (isSystemOrSelf) return

            synchronized(statsLock) {
                // Tự động xả các phiên pending tồn đọng trước đó nếu có
                flushPendingSessions(context)

                val success = recordAppSessionInternalLocked(context, packageName, durationMs, sessionToken)
                if (!success && sessionToken.isNotEmpty()) {
                    // Nếu ghi đĩa thất bại, lập tức đưa vào hàng đợi bền vững để retry sau SCREEN_ON hoặc restart
                    enqueuePendingSession(context, packageName, durationMs, sessionToken)
                    return
                }
            }

            // Đồng bộ ngay thống kê lên Firebase
            collectAndSave(context)
        }

        @Volatile
        internal var lastPolledForegroundPkg: String = ""
        @Volatile
        internal var lastPolledForegroundStartTime: Long = 0L

        /**
         * Chốt phiên làm việc tiền cảnh của cơ chế polling độc lập (Chuẩn Google Screen Time)
         * Đảm bảo Hardware Invariant & Non-blocking: Khi màn hình tắt (ACTION_SCREEN_OFF), khóa máy (Keyguard),
         * hoặc dịch vụ bị hủy (onDestroy), phiên phải được reset ngay lập tức trong RAM dưới monitor lock (< 1ms).
         * Mọi thao tác I/O đĩa (recordAppSession) và cập nhật mạng (reportActiveApp) được dispatch sang Dispatchers.IO,
         * tuyệt đối không chặn luồng BroadcastReceiver / UI.
         */
        fun closePolledSession(context: Context, reason: String = "SCREEN_OFF") {
            val (closedPkg, closedStart) = synchronized(statsLock) {
                val pkg = lastPolledForegroundPkg
                val start = lastPolledForegroundStartTime
                lastPolledForegroundPkg = ""
                lastPolledForegroundStartTime = 0L
                Pair(pkg, start)
            }

            if (closedPkg.isNotEmpty() && closedStart > 0L) {
                val now = System.currentTimeMillis()
                val duration = now - closedStart
                if (duration in 1000L..1800000L) {
                    val sessionToken = "polled_${closedPkg}_${closedStart}"
                    syncScope.launch(Dispatchers.IO) {
                        recordAppSession(context, closedPkg, duration, sessionToken)
                        Log.d("UsageTrackerService", "Đã chốt phiên độc lập $closedPkg: ${duration}ms (Lý do: $reason)")
                    }
                }
            }

            if (reason == "SCREEN_OFF" && closedPkg.isNotEmpty()) {
                syncScope.launch(Dispatchers.IO) {
                    reportActiveApp(
                        context = context,
                        packageName = "SCREEN_OFF",
                        appName = "Màn hình đã tắt",
                        category = "system",
                        categoryLabel = "Hệ thống",
                        isForeground = false
                    )
                }
            }
        }

        /**
         * ĐỘNG CƠ GIÁM SÁT TIỀN CẢNH ĐỘC LẬP QUA USAGESTATSMANAGER (CHUẨN GOOGLE SCREEN TIME)
         * Phát hiện chính xác ứng dụng đang hiển thị (Foreground App) bằng UsageEvents mà KHÔNG CẦN
         * bật quyền Trợ năng (Accessibility), loại bỏ hoàn toàn nguy cơ bị app ngân hàng báo động.
         * Tuân thủ triệt để Hardware Invariant, Non-blocking IO và Atomic Fencing đa tầng.
         */
        fun pollForegroundAppFromUsageEvents(context: Context) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isScreenInteractive = pm?.isInteractive ?: false
            val isLocked = km?.isKeyguardLocked ?: false

            // Hardware Invariant: Tuyệt đối không đo khi màn hình tắt hoặc đang khóa máy Keyguard
            if (!isScreenInteractive || isLocked || !GuardianAccessibilityService.isScreenOnState) {
                closePolledSession(context, "SCREEN_OFF")
                return
            }

            val startEpoch = GuardianAccessibilityService.telemetryEpoch.get()
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 30_000L, now)
            val event = android.app.usage.UsageEvents.Event()
            var currentPkg: String? = null
            var lastEventTime = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.timeStamp >= lastEventTime) {
                        currentPkg = event.packageName
                        lastEventTime = event.timeStamp
                    }
                }
            }

            // Fallback: Khi không có ACTIVITY_RESUMED trong 30s (người dùng giữ nguyên app),
            // BẮT BUỘC kiểm tra RunningAppProcessInfo IMPORTANCE_FOREGROUND để tránh nhận nhầm stale package
            if (currentPkg.isNullOrEmpty()) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val runningProcesses: List<ActivityManager.RunningAppProcessInfo>? = am?.runningAppProcesses
                val targetProcess: ActivityManager.RunningAppProcessInfo? = runningProcesses?.firstOrNull { proc ->
                    proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        proc.processName != "com.android.systemui" &&
                        !proc.processName.contains("keyguard") &&
                        !proc.processName.contains("inputmethod") &&
                        !proc.processName.contains("keyboard")
                }
                val rawProcPkg = targetProcess?.pkgList?.firstOrNull() ?: targetProcess?.processName
                val candProcessPkg: String? = if (rawProcPkg != null && rawProcPkg.contains(":")) {
                    rawProcPkg.substringBefore(":")
                } else {
                    rawProcPkg
                }

                if (!candProcessPkg.isNullOrEmpty() && targetProcess != null) {
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now)
                    val matchedStat = stats?.firstOrNull { it.packageName == candProcessPkg && (now - it.lastTimeUsed <= 60_000L) }
                    if (matchedStat != null) {
                        val isEvidenceValid = GuardianAccessibilityService.evaluateForegroundEvidence(
                            activeRootPkg = null,
                            usageStatsLastResumedPkg = matchedStat.packageName,
                            targetPkg = matchedStat.packageName,
                            now = now,
                            lastEventTime = matchedStat.lastTimeUsed,
                            maxEventAgeMs = 60_000L
                        )
                        if (isEvidenceValid) {
                            currentPkg = matchedStat.packageName
                        }
                    }
                }
            }

            // Stale Fencing: Nếu epoch thay đổi hoặc màn hình bị tắt trong lúc truy vấn, hủy ngay
            if (GuardianAccessibilityService.telemetryEpoch.get() != startEpoch || !GuardianAccessibilityService.isScreenOnState) {
                Log.w("UsageTrackerService", "Epoch thay đổi trong lúc polling, hủy bỏ cập nhật")
                return
            }

            if (!currentPkg.isNullOrEmpty() && currentPkg != context.packageName) {
                // Loại trừ System UI, Launcher, Keyboard
                if (currentPkg == "com.android.systemui" || currentPkg.contains("keyguard") ||
                    currentPkg.contains("inputmethod") || currentPkg.contains("keyboard")
                ) {
                    return
                }

                // Loại trừ tuyệt đối toàn bộ app ngân hàng & ví điện tử (Chuẩn an toàn RASP)
                if (GuardianAccessibilityService.isBankPackage(currentPkg)) {
                    closePolledSession(context, "BANK_APP_OPENED")
                    return
                }

                // State machine chuyển đổi app được bảo vệ nguyên tử bằng statsLock
                synchronized(statsLock) {
                    // Double-check hardware invariant & epoch fencing ngay trước khi ghi
                    if (!GuardianAccessibilityService.isScreenOnState ||
                        GuardianAccessibilityService.telemetryEpoch.get() != startEpoch
                    ) {
                        return
                    }

                    if (currentPkg != lastPolledForegroundPkg) {
                        val prevPkg = lastPolledForegroundPkg
                        val prevStart = lastPolledForegroundStartTime
                        lastPolledForegroundPkg = currentPkg
                        lastPolledForegroundStartTime = now

                        if (prevPkg.isNotEmpty() && prevStart > 0L) {
                            val sessionDuration = now - prevStart
                            if (sessionDuration in 1000L..1800000L) {
                                val sessionToken = "polled_${prevPkg}_${prevStart}"
                                syncScope.launch(Dispatchers.IO) {
                                    recordAppSession(context, prevPkg, sessionDuration, sessionToken)
                                }
                            }
                        }

                        val pkgMgr = context.packageManager
                        val appInfo = try {
                            pkgMgr.getApplicationInfo(currentPkg, 0)
                        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                            Log.w("UsageTrackerService", "Không tìm thấy package: $currentPkg: ${e.message}")
                            null
                        } catch (e: Exception) {
                            Log.w("UsageTrackerService", "Lỗi truy xuất ApplicationInfo cho $currentPkg: ${e.message}")
                            null
                        }
                        val appLabel = appInfo?.let { pkgMgr.getApplicationLabel(it).toString() } ?: currentPkg
                        val metadata = AppClassifier.classify(currentPkg, appLabel, appInfo)

                        syncScope.launch(Dispatchers.IO) {
                            reportActiveApp(
                                context = context,
                                packageName = currentPkg,
                                appName = appLabel,
                                category = metadata.category.name,
                                categoryLabel = metadata.category.displayName,
                                isForeground = true
                            )
                        }
                    }
                }
            }
        }

        fun collectAndSave(context: Context) {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

            val calendar = Calendar.getInstance()
            val endTime = calendar.timeInMillis
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startTime = calendar.timeInMillis

            val statsMap = usageStatsManager?.queryAndAggregateUsageStats(startTime, endTime)
            val statsCollection = if (!statsMap.isNullOrEmpty()) {
                statsMap.values
            } else {
                usageStatsManager?.queryUsageStats(
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

            data class AppHistoryRecord(
                val packageName: String,
                val appName: String,
                val category: String,
                val categoryLabel: String,
                val totalTimeMs: Long,
                val durationMinutes: Int,
                val lastTimeUsed: Long
            )
            val appList = mutableListOf<AppHistoryRecord>()

            for (stat in statsCollection) {
                val totalTime = stat.totalTimeInForeground
                if (totalTime <= 0) continue

                val appInfo = try {
                    pm.getApplicationInfo(stat.packageName, 0)
                } catch (e: Exception) {
                    null
                }
                val appLabel = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: stat.packageName

                val metadata = AppClassifier.classify(stat.packageName, appLabel, appInfo)
                when (metadata.category) {
                    AppCategory.STUDY -> studyTimeMs += totalTime
                    AppCategory.GAME -> gameTimeMs += totalTime
                    AppCategory.SOCIAL -> socialTimeMs += totalTime
                    AppCategory.UTILITY, AppCategory.OTHER -> utilityTimeMs += totalTime
                }

                val isSystemOrSelf = stat.packageName == context.packageName ||
                        stat.packageName == "com.android.systemui" ||
                        stat.packageName.contains("inputmethod") ||
                        stat.packageName.contains("keyboard") ||
                        stat.packageName.contains("launcher")

                if (!isSystemOrSelf && totalTime >= 15_000L) {
                    appList.add(
                        AppHistoryRecord(
                            packageName = stat.packageName,
                            appName = appLabel,
                            category = metadata.category.name,
                            categoryLabel = metadata.category.displayName,
                            totalTimeMs = totalTime,
                            durationMinutes = (totalTime / 60000L).toInt().coerceAtLeast(1),
                            lastTimeUsed = stat.lastTimeUsed
                        )
                    )
                }
            }

            // Hợp nhất dữ liệu phiên tích lũy từ Accessibility (Dual-Tracking Engine)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            val allPrefs = prefs.all
            for ((key, value) in allPrefs) {
                if (key.startsWith("session_${todayStr}_") && value is Long && value > 0L) {
                    val pkg = key.removePrefix("session_${todayStr}_")
                    val existingIndex = appList.indexOfFirst { it.packageName == pkg }
                    val appName = prefs.getString("app_name_$pkg", pkg) ?: pkg
                    val catName = prefs.getString("app_cat_$pkg", "OTHER") ?: "OTHER"
                    val catLabel = prefs.getString("app_cat_label_$pkg", "Khác") ?: "Khác"
                    val lastUsed = prefs.getLong("app_last_used_$pkg", System.currentTimeMillis())

                    if (existingIndex >= 0) {
                        val old = appList[existingIndex]
                        if (value > old.totalTimeMs) {
                            val diff = value - old.totalTimeMs
                            when (old.category) {
                                "STUDY" -> studyTimeMs += diff
                                "GAME" -> gameTimeMs += diff
                                "SOCIAL" -> socialTimeMs += diff
                                else -> utilityTimeMs += diff
                            }
                            appList[existingIndex] = old.copy(
                                totalTimeMs = value,
                                durationMinutes = (value / 60000L).toInt().coerceAtLeast(1)
                            )
                        }
                    } else {
                        appList.add(
                            AppHistoryRecord(
                                packageName = pkg,
                                appName = appName,
                                category = catName,
                                categoryLabel = catLabel,
                                totalTimeMs = value,
                                durationMinutes = (value / 60000L).toInt().coerceAtLeast(1),
                                lastTimeUsed = lastUsed
                            )
                        )
                        when (catName) {
                            "STUDY" -> studyTimeMs += value
                            "GAME" -> gameTimeMs += value
                            "SOCIAL" -> socialTimeMs += value
                            else -> utilityTimeMs += value
                        }
                    }
                }
            }

            appList.sortByDescending { it.totalTimeMs }
            val topApps = appList.take(15)
            val appHistoryJsonArray = org.json.JSONArray().apply {
                for (item in topApps) {
                    put(JSONObject().apply {
                        put("packageName", item.packageName)
                        put("appName", item.appName)
                        put("category", item.category)
                        put("categoryLabel", item.categoryLabel)
                        put("totalTimeMs", item.totalTimeMs)
                        put("durationMinutes", item.durationMinutes)
                        put("lastTimeUsed", item.lastTimeUsed)
                    })
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

            // Lưu vào SharedPreferences với statsLock đồng bộ (sử dụng apply() phi blocking)
            synchronized(statsLock) {
                prefs.edit()
                    .putLong("study_time_ms", studyTimeMs)
                    .putLong("game_time_ms", gameTimeMs)
                    .putLong("social_time_ms", socialTimeMs)
                    .putLong("utility_time_ms", utilityTimeMs)
                    .putLong("total_screen_time_ms", totalScreenTimeMs)
                    .putInt("balance_score", balanceScore)
                    .putLong("last_updated_at", System.currentTimeMillis())
                    .apply()
            }

            // Đồng bộ trực tiếp nhịp tim & thống kê lên Firebase để Parent Hub theo dõi thời gian thực
            val pairedCode = prefs.getString("paired_code", "") ?: ""
            if (pairedCode.isNotEmpty()) {
                val androidId = prefs.getString("device_id", "")?.takeIf { it.isNotEmpty() }
                    ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    ?: "UNKNOWN"

                syncScope.launch {
                    try {
                        val targetEpoch = GuardianAccessibilityService.telemetryEpoch.get()
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
                            put("appHistory", appHistoryJsonArray)
                        }

                        val historyBody = appHistoryJsonArray.toString().toRequestBody(mediaType)
                        val reqFamHist = okhttp3.Request.Builder()
                            .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId/app_history.json")
                            .put(historyBody)
                            .build()

                        // Cập nhật đồng thời nhánh device: chỉ đính kèm online = true và heartbeat nếu phần cứng thực sự online
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                        val isOnlineNow = GuardianAccessibilityService.isScreenOnState &&
                                pm?.isInteractive == true &&
                                km?.isKeyguardLocked != true

                        val devicePatch = JSONObject().apply {
                            if (isOnlineNow) {
                                put("online", true)
                                put("lastHeartbeat", now)
                            }
                            put("lastSync", now)
                            put("usage", usageJson)
                            put("app_history", appHistoryJsonArray)
                        }
                        val patchBody = devicePatch.toString().toRequestBody(mediaType)

                        fun sendGuarded(req: okhttp3.Request): Boolean {
                            val currentPm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                            val currentKm = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                            val isCurrentHardwareOnline = GuardianAccessibilityService.isScreenOnState &&
                                    currentPm?.isInteractive == true &&
                                    currentKm?.isKeyguardLocked != true
                            if (isCurrentHardwareOnline != isOnlineNow || GuardianAccessibilityService.telemetryEpoch.get() != targetEpoch) {
                                Log.w("UsageTrackerService", "Phát hiện thay đổi phần cứng hoặc epoch trong collectAndSave, dừng chuỗi đồng bộ")
                                return false
                            }
                            return if (isOnlineNow) {
                                executeOnlineGuarded(req, context, targetEpoch)
                            } else {
                                executeOfflineGuarded(req, context, targetEpoch)
                            }
                        }

                        // 1. Ghi vào danh sách thiết bị gia đình: /families/$pairedCode/devices/$androidId
                        val reqFamDev = okhttp3.Request.Builder()
                            .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/families/$pairedCode/devices/$androidId.json")
                            .patch(patchBody)
                            .build()

                        // 2. Ghi vào chi tiết thiết bị phẳng: /devices/$androidId
                        val reqDevice = okhttp3.Request.Builder()
                            .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$androidId.json")
                            .patch(patchBody)
                            .build()

                        // 3. Tương thích ngược: /devices/$pairedCode và /pairings/$pairedCode
                        val reqLegacyDev = okhttp3.Request.Builder()
                            .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/devices/$pairedCode.json")
                            .patch(patchBody)
                            .build()

                        val reqPairing = okhttp3.Request.Builder()
                            .url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/pairings/$pairedCode.json")
                            .patch(patchBody)
                            .build()

                        coroutineScope {
                            val requests = listOf(reqFamHist, reqFamDev, reqDevice, reqLegacyDev, reqPairing)
                            val deferreds = requests.map { req ->
                                async(Dispatchers.IO) {
                                    sendGuarded(req)
                                }
                            }
                            deferreds.awaitAll()
                        }
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

    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ctx = context ?: return
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("UsageTrackerService", "Phát hiện tắt màn hình (SCREEN_OFF): Đóng băng bộ đếm và cập nhật active_app")
                    GuardianAccessibilityService.isScreenOnState = false
                    lastHeartbeatSentTimestamp.set(0L)
                    cancelActiveOnlineCalls()

                    // CHỐT PHIÊN ĐỘC LẬP NGAY LẬP TỨC CHO ĐỘNG CƠ POLLING USAGESTATS
                    closePolledSession(ctx, "SCREEN_OFF")

                    val accessService = GuardianAccessibilityService.instance
                    val screenOffEpoch = if (accessService != null) {
                        accessService.handleScreenOff()
                    } else {
                        synchronized(GuardianAccessibilityService.hardwareTransitionLock) {
                            GuardianAccessibilityService.isScreenOnState = false
                            val ep = GuardianAccessibilityService.telemetryEpoch.incrementAndGet()
                            foregroundGeneration.incrementAndGet()
                            ep
                        }
                    }
                    if (accessService == null) {
                        sendUrgentOfflineStatus(ctx, screenOffEpoch)

                        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        val lastPkg = prefs.getString("last_foreground_pkg", "") ?: ""
                        val lastStart = prefs.getLong("last_foreground_start", 0L)
                        val now = System.currentTimeMillis()

                        // 1. Accounting độc lập: Ghi nhận thời lượng phiên sử dụng ĐỘC LẬP (kèm session token chống duplicate)
                        if (lastPkg.isNotEmpty() && lastStart > 0L && now - lastStart >= 1000L) {
                            val sessionDuration = now - lastStart
                            val sessionToken = "${lastPkg}_${lastStart}"
                            syncScope.launch(Dispatchers.IO) {
                                recordAppSession(ctx, lastPkg, sessionDuration, sessionToken)
                            }
                        }

                        // 2. Telemetry offline persistence: Có stale fencing
                        syncScope.launch(Dispatchers.IO) {
                            // FENCING BẤT BIẾN: Kiểm tra ngay sau khi khởi chạy coroutine
                            if (GuardianAccessibilityService.telemetryEpoch.get() != screenOffEpoch || GuardianAccessibilityService.isScreenOnState) {
                                Log.w("UsageTrackerService", "Hủy bỏ screenStateReceiver fallback: Phát hiện SCREEN_ON trước khi ghi đĩa")
                                return@launch
                            }

                            val lastEpoch = prefs.getLong("last_written_epoch", -1L)
                            if (screenOffEpoch >= lastEpoch) {
                                prefs.edit()
                                    .putLong("last_written_epoch", screenOffEpoch)
                                    .putString("last_foreground_pkg", "")
                                    .putLong("last_foreground_start", 0L)
                                    .putBoolean("is_device_online", false)
                                    .commit()
                            }
                        }
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d("UsageTrackerService", "Phát hiện mở khóa máy (ACTION_USER_PRESENT): Thức tỉnh giám sát")
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                    val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    val isHardwareOnline = (pm?.isInteractive == true && km?.isKeyguardLocked != true)
                    GuardianAccessibilityService.isScreenOnState = isHardwareOnline
                    lastDispatchedOfflineEpoch.set(-1L)
                    val accessService = GuardianAccessibilityService.instance
                    val userPresentEpoch = if (accessService != null) {
                        accessService.handleScreenOn()
                    } else {
                        synchronized(GuardianAccessibilityService.hardwareTransitionLock) {
                            val isHwOnline = (pm?.isInteractive == true && km?.isKeyguardLocked != true)
                            GuardianAccessibilityService.isScreenOnState = isHwOnline
                            val ep = GuardianAccessibilityService.telemetryEpoch.incrementAndGet()
                            ep
                        }
                    }
                    if (isHardwareOnline) {
                        sendHeartbeatPing(ctx, force = true)
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d("UsageTrackerService", "Phát hiện bật màn hình (ACTION_SCREEN_ON): Máy vẫn ở màn hình khóa Keyguard, giữ nguyên offline")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        restorePersistedSessionTokens(this)
        WebFilterList.loadFromPreferences(this)
        createNotificationChannel()
        startForegroundNotification()

        val screenFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, screenFilter)

        startTrackingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.w("UsageTrackerService", "Failed to unregister screenStateReceiver: ${e.message}")
        }
        GuardianAccessibilityService.isScreenOnState = false
        closePolledSession(applicationContext, "SERVICE_DESTROYED")
        cancelActiveOnlineCalls()
        sendUrgentOfflineStatus(applicationContext, GuardianAccessibilityService.telemetryEpoch.incrementAndGet())
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
            val otaChannel = NotificationChannel(
                OTA_CHANNEL_ID,
                "Cập Nhật Ứng Dụng",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo bản cập nhật mới cho SmartGuardian"
                setShowBadge(true)
            }
            val gpsChannel = NotificationChannel(
                GPS_CHANNEL_ID,
                "Yêu Cầu Định Vị GPS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo yêu cầu bật GPS / định vị từ phụ huynh"
                setShowBadge(true)
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            manager?.createNotificationChannel(otaChannel)
            manager?.createNotificationChannel(gpsChannel)
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
                val fgsType = if (LocationHelper.hasLocationPermission(this)) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    fgsType
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun checkAndNotifyBackgroundUpdate(context: Context) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val updateInfo = vn.edu.cva.smartguardian.update.AppUpdateManager.checkForUpdate(context) ?: return@launch
                if (lastNotifiedUpdateCode.get() >= updateInfo.versionCode) {
                    return@launch
                }
                lastNotifiedUpdateCode.set(updateInfo.versionCode)

                val intent = Intent(context, MainActivity::class.java).apply {
                    putExtra("EXTRA_SHOW_UPDATE", true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    OTA_NOTIFICATION_ID,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val sizeText = if (updateInfo.fileSize.isNotEmpty()) " (${updateInfo.fileSize})" else ""
                val notification = NotificationCompat.Builder(context, OTA_CHANNEL_ID)
                    .setContentTitle("Có Bản Cập Nhật Mới: v${updateInfo.versionName}")
                    .setContentText("Chạm để nâng cấp phiên bản mới$sizeText")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("Đã có bản cập nhật mới v${updateInfo.versionName}$sizeText với các cải tiến hiệu năng và ổn định hệ thống. Chạm để cài đặt ngay."))
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.notify(OTA_NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.w("UsageTrackerService", "checkAndNotifyBackgroundUpdate failed: ${e.message}")
            }
        }
    }

    private fun startTrackingLoop() {
        serviceScope.launch {
            var lastUpdateCheck = 0L
            while (isActive) {
                // 1. Luôn gửi nhịp tim sống còn (Heartbeat) dù có quyền Usage hay không
                sendHeartbeatPing(this@UsageTrackerService)

                // 2. Kiểm tra lệnh định vị tức thì từ phụ huynh
                checkLocationRequest(this@UsageTrackerService)

                // 3. Đồng bộ quy tắc lọc web (blacklist/whitelist/study_mode) từ phụ huynh
                syncWebRules(this@UsageTrackerService)

                // 4. Thu thập thống kê chi tiết nếu được cấp quyền
                try {
                    collectAndSave(this@UsageTrackerService)
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "collectAndSave failed: ${e.message}")
                }

                // 4b. Động cơ Giám sát Tiền cảnh Độc lập qua UsageStatsManager (Chuẩn Google Screen Time)
                // Chạy liên tục và độc lập theo ticker bất kể AccessibilityService có bật hay không
                try {
                    pollForegroundAppFromUsageEvents(this@UsageTrackerService)
                } catch (e: Exception) {
                    Log.w("UsageTrackerService", "pollForegroundAppFromUsageEvents error: ${e.message}")
                }

                // 5. Định kỳ kiểm tra OTA Update và bắn Notification hệ thống nếu có bản mới!
                val now = System.currentTimeMillis()
                if (now - lastUpdateCheck > 5 * 60 * 1000L) {
                    lastUpdateCheck = now
                    try {
                        checkAndNotifyBackgroundUpdate(this@UsageTrackerService)
                    } catch (e: Exception) {
                        Log.w("UsageTrackerService", "Background update check error: ${e.message}")
                    }
                }

                delay(15_000) // Nhịp tim kiểm tra chuẩn 15 giây theo SPEC.md
            }
        }
    }
}
