package com.example.autochat.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Lấy tên tỉnh/thành phố từ GPS, dùng cho tham số location trong getQuickReplies().
 *
 * Flow:
 *  1. Kiểm tra quyền ACCESS_FINE_LOCATION hoặc ACCESS_COARSE_LOCATION
 *  2. FusedLocationProvider.getCurrentLocation() → lat/lng
 *  3. Geocoder.getFromLocation() → adminArea (tên tỉnh/thành)
 *  4. Trả về tên tỉnh, hoặc null nếu không có quyền / lỗi
 *
 * Dùng trong CarContext (Android Auto) — không cần Activity.
 */
object LocationHelper {

    private const val TAG = "LocationHelper"

    /**
     * Trả về tên tỉnh/thành phố tiếng Việt (vd: "Hà Nội", "Hồ Chí Minh").
     * Trả về null nếu không có quyền hoặc GPS lỗi.
     *
     * Phải gọi từ coroutine (suspend).
     */
    suspend fun getProvinceName(context: Context): String? {
        // Kiểm tra quyền
        val hasFine   = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            android.util.Log.w(TAG, "Không có quyền location")
            return null
        }

        return try {
            val location = getCurrentLocation(context) ?: return null
            reverseGeocode(context, location.first, location.second)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getProvinceName error: ${e.message}")
            null
        }
    }

    /**
     * Lấy lat/lng hiện tại qua FusedLocationProvider.
     * Trả về Pair(lat, lng) hoặc null nếu thất bại.
     */
    @Suppress("MissingPermission")
    private suspend fun getCurrentLocation(context: Context): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            val client = LocationServices.getFusedLocationProviderClient(context)
            val cts    = CancellationTokenSource()

            cont.invokeOnCancellation { cts.cancel() }

            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        cont.resume(Pair(loc.latitude, loc.longitude))
                    } else {
                        // getCurrentLocation trả null → thử lastLocation
                        client.lastLocation.addOnSuccessListener { last ->
                            if (last != null) cont.resume(Pair(last.latitude, last.longitude))
                            else cont.resume(null)
                        }.addOnFailureListener {
                            cont.resume(null)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    android.util.Log.e(TAG, "getCurrentLocation failed: ${e.message}")
                    cont.resume(null)
                }
        }

    /**
     * Reverse geocode lat/lng → tên tỉnh/thành (adminArea).
     * API 33+ dùng listener callback, thấp hơn dùng getFromLocation() đồng bộ.
     */
    private suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null

        val geocoder = Geocoder(context, Locale("vi", "VN"))

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: dùng callback bất đồng bộ
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lng, 1) { addresses ->
                    val province = addresses.firstOrNull()?.adminArea
                    android.util.Log.d(TAG, "Geocode result: $province (lat=$lat, lng=$lng)")
                    cont.resume(province)
                }
            }
        } else {
            // API < 33: dùng blocking call (chạy trên IO dispatcher)
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            val province  = addresses?.firstOrNull()?.adminArea
            android.util.Log.d(TAG, "Geocode result: $province (lat=$lat, lng=$lng)")
            province
        }
    }
}