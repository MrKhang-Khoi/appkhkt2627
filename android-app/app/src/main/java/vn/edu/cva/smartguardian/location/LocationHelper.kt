package vn.edu.cva.smartguardian.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

data class GuardianLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double,
    val speed: Float,
    val timestamp: Long,
    val provider: String,
    val batteryLevel: Int,
    val address: String,
    val status: String
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("latitude", latitude)
            put("longitude", longitude)
            put("accuracy", accuracy)
            put("altitude", altitude)
            put("speed", speed)
            put("timestamp", timestamp)
            put("provider", provider)
            put("batteryLevel", batteryLevel)
            put("address", address)
            put("status", status)
        }
    }
}

object LocationHelper {
    private const val TAG = "LocationHelper"

    // Kế thừa chuẩn Google / Zenly / Life360:
    // Tọa độ GPS chỉ được coi là "tươi" nếu được ghi nhận trong vòng 30 giây gần nhất.
    const val MAX_FRESH_AGE_MS = 30_000L
    // Cửa sổ kết nối tối đa 10 giây để chip GPS vệ tinh chốt tọa độ thực tế của giây hiện tại.
    const val FRESH_FIX_TIMEOUT_MS = 10_000L
    // Ngưỡng phát hiện di chuyển Life360: tự động đẩy GPS khi học sinh di chuyển > 30m.
    const val DEFAULT_MOVEMENT_DISPLACEMENT_METERS = 30f

    fun hasLocationPermission(context: Context): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    fun isGpsEnabled(context: Context): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                    lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        } catch (e: Exception) {
            Log.w(TAG, "Loi kiem tra GPS provider: ${e.message}")
            false
        }
    }

    fun getBatteryLevel(context: Context): Int {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, ifilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                ((level.toFloat() / scale.toFloat()) * 100).toInt()
            } else {
                -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Loi doc pin: ${e.message}")
            -1
        }
    }

    fun isLocationTimeFresh(locationTimeMs: Long, nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = MAX_FRESH_AGE_MS): Boolean {
        if (locationTimeMs <= 0L) return false
        val ageMs = Math.abs(nowMs - locationTimeMs)
        return ageMs <= maxAgeMs
    }

    fun isLocationFresh(location: Location?, maxAgeMs: Long = MAX_FRESH_AGE_MS): Boolean {
        if (location == null) return false
        return isLocationTimeFresh(location.time, System.currentTimeMillis(), maxAgeMs)
    }

    fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (earthRadius * c).toFloat()
    }

    suspend fun fetchCurrentLocation(
        context: Context,
        maxAgeMs: Long = MAX_FRESH_AGE_MS,
        freshTimeoutMs: Long = FRESH_FIX_TIMEOUT_MS
    ): GuardianLocation? {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Chua duoc cap quyen vi tri!")
            return null
        }

        val battery = getBatteryLevel(context)

        // 1. Kế thừa Google/Zenly: Lấy vị trí GPS tươi (Fresh GPS Invariant)
        // Nếu cache cũ > 30s -> CẤM DÙNG, giữ kết nối tối đa 10s để chip GPS vệ tinh chốt tọa độ của giây hiện tại
        val freshLoc = fetchFreshFusedLocation(context, maxAgeMs, freshTimeoutMs)
        val targetLoc = freshLoc ?: fetchNativeFreshLocation(context, maxAgeMs)

        if (targetLoc == null) {
            Log.w(TAG, "Khong the chot duoc toa do ve tinh tuoi trong ${freshTimeoutMs / 1000}s")
            return null
        }

        // 2. Dich nguoc dia chi Geocoder tieng Viet
        val addressText = reverseGeocode(context, targetLoc.latitude, targetLoc.longitude)
        val isFresh = isLocationFresh(targetLoc, maxAgeMs)

        return GuardianLocation(
            latitude = targetLoc.latitude,
            longitude = targetLoc.longitude,
            accuracy = targetLoc.accuracy,
            altitude = targetLoc.altitude,
            speed = targetLoc.speed,
            timestamp = if (targetLoc.time > 0) targetLoc.time else System.currentTimeMillis(),
            provider = targetLoc.provider ?: "fused_gps",
            batteryLevel = battery,
            address = addressText,
            status = if (isFresh) "SUCCESS" else "CACHED"
        )
    }

    private suspend fun fetchFreshFusedLocation(
        context: Context,
        maxAgeMs: Long,
        timeoutMs: Long
    ): Location? {
        return withContext(Dispatchers.IO) {
            try {
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)

                // Bước 1: Kiểm tra nhanh getCurrentLocation xem có sẵn fix tươi trong vòng 30s không
                val cts = CancellationTokenSource()
                val quickFix = withTimeoutOrNull(2500L) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        try {
                            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                                .addOnSuccessListener { loc ->
                                    if (cont.isActive) cont.resume(loc)
                                }
                                .addOnFailureListener {
                                    if (cont.isActive) cont.resume(null)
                                }
                        } catch (e: SecurityException) {
                            Log.w(TAG, "SecurityException Fused quick fix: ${e.message}")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }

                if (quickFix != null && isLocationFresh(quickFix, maxAgeMs)) {
                    Log.i(TAG, "Nhan toa do tuoi tuc thi qua getCurrentLocation (age <= ${maxAgeMs}ms)")
                    return@withContext quickFix
                }

                // Bước 2: Kế thừa Google/Zenly Fresh GPS Invariant:
                // Nếu cache cũ > 30s -> CẤM DÙNG, giữ kết nối tối đa 10s để chip GPS vệ tinh chốt tọa độ thực tế của giây hiện tại
                Log.i(TAG, "Cache cu > 30s hoac chua co fix -> Bat Active Satellite Lock (toi da ${timeoutMs}ms)...")
                val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                    .setMinUpdateIntervalMillis(500L)
                    .setMaxUpdates(10)
                    .setDurationMillis(timeoutMs)
                    .build()

                val freshFix = withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        var resumed = false
                        val callback = object : LocationCallback() {
                            override fun onLocationResult(result: LocationResult) {
                                for (loc in result.locations) {
                                    if (loc != null && isLocationFresh(loc, maxAgeMs)) {
                                        if (!resumed && cont.isActive) {
                                            resumed = true
                                            try {
                                                fusedClient.removeLocationUpdates(this)
                                            } catch (e: Exception) {
                                                Log.w(TAG, "Loi removeLocationUpdates: ${e.message}")
                                            }
                                            cont.resume(loc)
                                            return
                                        }
                                    }
                                }
                            }
                        }

                        cont.invokeOnCancellation {
                            try {
                                fusedClient.removeLocationUpdates(callback)
                            } catch (e: Exception) {
                                Log.w(TAG, "Loi removeLocationUpdates khi huy: ${e.message}")
                            }
                        }

                        try {
                            fusedClient.requestLocationUpdates(
                                locationRequest,
                                callback,
                                Looper.getMainLooper()
                            ).addOnFailureListener { e ->
                                Log.w(TAG, "requestLocationUpdates failed: ${e.message}")
                                if (!resumed && cont.isActive) {
                                    resumed = true
                                    cont.resume(null)
                                }
                            }
                        } catch (se: SecurityException) {
                            Log.w(TAG, "SecurityException requestLocationUpdates: ${se.message}")
                            if (!resumed && cont.isActive) {
                                resumed = true
                                cont.resume(null)
                            }
                        }
                    }
                }

                freshFix
            } catch (e: Exception) {
                Log.w(TAG, "fetchFreshFusedLocation error: ${e.message}")
                null
            }
        }
    }

    private fun fetchNativeFreshLocation(context: Context, maxAgeMs: Long): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = lm.getProviders(true)
            var bestLoc: Location? = null
            for (p in providers) {
                try {
                    val l = lm.getLastKnownLocation(p) ?: continue
                    if (isLocationFresh(l, maxAgeMs)) {
                        if (bestLoc == null || l.accuracy < bestLoc.accuracy) {
                            bestLoc = l
                        }
                    }
                } catch (se: SecurityException) {
                    Log.w(TAG, "Native location security error on $p: ${se.message}")
                }
            }
            bestLoc
        } catch (e: Exception) {
            Log.w(TAG, "Native location error: ${e.message}")
            null
        }
    }

    // Bước 3: Kế thừa Life360 - Movement-Triggered Updates (>30m)
    // Thiết lập LocationRequest ngầm: Hễ học sinh di chuyển > 30m, kích hoạt callback
    fun startContinuousMovementListener(
        context: Context,
        minDistanceMeters: Float = DEFAULT_MOVEMENT_DISPLACEMENT_METERS,
        onLocationChanged: (Location) -> Unit
    ): LocationCallback? {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Chua duoc cap quyen vi tri de lang nghe di chuyen ngam")
            return null
        }
        return try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val movementRequest = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L
            )
                .setMinUpdateIntervalMillis(15_000L)
                .setMinUpdateDistanceMeters(minDistanceMeters)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    onLocationChanged(loc)
                }
            }

            fusedClient.requestLocationUpdates(
                movementRequest,
                callback,
                Looper.getMainLooper()
            ).addOnSuccessListener {
                Log.i(TAG, "Da dang ky thanh cong Movement-Triggered Updates (>${minDistanceMeters}m)")
            }.addOnFailureListener { e ->
                Log.w(TAG, "Dang ky Movement-Triggered Updates that bai: ${e.message}")
            }

            callback
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException khi startContinuousMovementListener: ${se.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Loi startContinuousMovementListener: ${e.message}")
            null
        }
    }

    fun stopContinuousMovementListener(context: Context, callback: LocationCallback) {
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            fusedClient.removeLocationUpdates(callback)
            Log.i(TAG, "Da huy dang ky Movement-Triggered Location Updates")
        } catch (e: Exception) {
            Log.w(TAG, "Loi stopContinuousMovementListener: ${e.message}")
        }
    }

    suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): String {
        return withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) {
                    return@withContext "Toạ độ: ${String.format(Locale.US, "%.5f, %.5f", lat, lng)}"
                }
                val geocoder = Geocoder(context, Locale("vi", "VN"))
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val lines = mutableListOf<String>()
                    val featureName = addr.featureName
                    val thoroughfare = addr.thoroughfare
                    val subLocality = addr.subLocality
                    val subAdminArea = addr.subAdminArea
                    val adminArea = addr.adminArea

                    if (!thoroughfare.isNullOrBlank()) {
                        if (!featureName.isNullOrBlank() && featureName != thoroughfare) {
                            lines.add("$featureName, $thoroughfare")
                        } else {
                            lines.add(thoroughfare)
                        }
                    } else if (!featureName.isNullOrBlank()) {
                        lines.add(featureName)
                    }

                    if (!subLocality.isNullOrBlank()) lines.add(subLocality)
                    if (!subAdminArea.isNullOrBlank()) lines.add(subAdminArea)
                    if (!adminArea.isNullOrBlank()) lines.add(adminArea)

                    if (lines.isNotEmpty()) {
                        return@withContext lines.joinToString(", ")
                    }

                    val fullAddress = addr.getAddressLine(0)
                    if (!fullAddress.isNullOrBlank()) {
                        return@withContext fullAddress
                    }
                }
                "Toạ độ: ${String.format(Locale.US, "%.5f, %.5f", lat, lng)}"
            } catch (e: Exception) {
                Log.w(TAG, "Loi Geocoder: ${e.message}")
                "Toạ độ: ${String.format(Locale.US, "%.5f, %.5f", lat, lng)}"
            }
        }
    }
}
