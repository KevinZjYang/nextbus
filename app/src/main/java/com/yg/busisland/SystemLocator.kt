package com.yg.busisland

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * 系统 LocationManager 的兜底定位。它给的是 WGS-84，出口一律转 GCJ-02。
 * 不再把“最新一条缓存”当当前位置：缓存只做候选，仍要过时效与精度门槛。
 */
object SystemLocator {

    private val PROVIDERS = listOf(
        "fused",
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )

    fun lastGood(
        context: Context,
        maxAgeMs: Long = 10 * 60_000L,
        maxAccuracyM: Float = 1000f
    ): LocationFix? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val fixes = PROVIDERS.mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }.map { LocationFix.fromSystem(it) }
        return LocationQuality.best(fixes, System.currentTimeMillis(), maxAgeMs, maxAccuracyM)
    }

    /** 开一个短窗口收集 GPS/网络/fused 结果，达到 50m 或超时后回调一次。 */
    fun current(
        context: Context,
        timeoutMs: Long = 10_000L,
        onResult: (LocationFix?) -> Unit
    ) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            onResult(null)
            return
        }
        val main = Handler(Looper.getMainLooper())
        val collected = ArrayList<LocationFix>()
        var done = false
        lateinit var finish: () -> Unit

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done) return
                collected += LocationFix.fromSystem(location)
                val best = LocationQuality.best(collected, System.currentTimeMillis(), 60_000L, 1000f)
                if (best != null && best.hasAccuracy && best.accuracyM <= 50f) finish()
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        finish = {
            if (!done) {
                done = true
                main.removeCallbacksAndMessages(null)
                runCatching { lm.removeUpdates(listener) }
                main.post {
                    onResult(LocationQuality.best(collected, System.currentTimeMillis(), 60_000L, 1000f))
                }
            }
        }

        var requested = false
        for (provider in PROVIDERS) {
            if (provider != "fused" && provider !in lm.allProviders) continue
            val ok = runCatching {
                lm.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                true
            }.getOrDefault(false)
            requested = requested || ok
        }
        if (!requested) {
            finish()
            return
        }
        main.postDelayed({ finish() }, timeoutMs)
    }
}
