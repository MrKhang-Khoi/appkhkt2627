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
import android.util.Log
import androidx.core.content.ContextCompat
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

    suspend fun fetchCurrentLocation(context: Context): GuardianLocation? {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Chua duoc cap quyen vi tri!")
            return null
        }

        val battery = getBatteryLevel(context)

        // 1. Thu lay vi tri qua Google Play Services Fused Location (Tang 1)
        val fusedLoc = fetchFusedLocation(context)
        val targetLoc = fusedLoc ?: fetchNativeLocation(context)

        if (targetLoc == null) {
            Log.w(TAG, "Khong the lay duoc vi tri tu ca 2 provider")
            return null
        }

        // 2. Dich nguoc dia chi Geocoder tieng Viet
        val addressText = reverseGeocode(context, targetLoc.latitude, targetLoc.longitude)

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
            status = "SUCCESS"
        )
    }

    private suspend fun fetchFusedLocation(context: Context): Location? {
        return withContext(Dispatchers.IO) {
            try {
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                val cts = CancellationTokenSource()

                withTimeoutOrNull(8000L) {
                    suspendCancellableCoroutine { cont ->
                        try {
                            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                                .addOnSuccessListener { loc ->
                                    if (cont.isActive) {
                                        if (loc != null) {
                                            cont.resume(loc)
                                        } else {
                                            fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
                                                if (cont.isActive) cont.resume(lastLoc)
                                            }.addOnFailureListener {
                                                if (cont.isActive) cont.resume(null)
                                            }
                                        }
                                    }
                                }
                                .addOnFailureListener {
                                    if (cont.isActive) {
                                        cont.resume(null)
                                    }
                                }
                        } catch (e: SecurityException) {
                            Log.w(TAG, "SecurityException FusedLocation: ${e.message}")
                            if (cont.isActive) {
                                cont.resume(null)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "FusedLocation error: ${e.message}")
                null
            }
        }
    }

    private fun fetchNativeLocation(context: Context): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = lm.getProviders(true)
            var bestLoc: Location? = null
            for (p in providers) {
                try {
                    val l = lm.getLastKnownLocation(p) ?: continue
                    if (bestLoc == null || l.accuracy < bestLoc.accuracy) {
                        bestLoc = l
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

    private suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): String {
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
