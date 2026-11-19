package com.yg.busisland

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption

/**
 * 高德定位封装（Wi-Fi/基站/GPS 融合）。
 * 连续定位一小段时间，收集候选后挑最优，避免单次定位在 30m 与几百米之间跳。
 * 未配置 key 时 available=false，调用方回退系统 LocationManager。
 */
object AmapLocator {

    val available: Boolean get() = BuildConfig.AMAP_KEY.isNotBlank()

    private var privacyDone = false

    private fun ensurePrivacy(ctx: Context) {
        if (privacyDone) return
        runCatching {
            AMapLocationClient.updatePrivacyShow(ctx, true, true)
            AMapLocationClient.updatePrivacyAgree(ctx, true)
        }
        privacyDone = true
    }

    /**
     * 在 [timeoutMs] 内连续采样，达到 [targetAccuracyM] 或超时后回调一次。
     * 回调在主线程；结果为 GCJ-02 的 [LocationFix]。
     */
    fun bestFix(
        ctx: Context,
        timeoutMs: Long = 8_000L,
        targetAccuracyM: Float = 50f,
        onResult: (LocationFix?) -> Unit
    ) {
        if (!available) {
            onResult(null)
            return
        }
        ensurePrivacy(ctx)
        val main = Handler(Looper.getMainLooper())
        val fixes = ArrayList<LocationFix>()
        var finished = false
        var client: AMapLocationClient? = null

        fun finish() {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(null)
            runCatching { client?.stopLocation() }
            runCatching { client?.onDestroy() }
            val best = LocationQuality.best(fixes, System.currentTimeMillis(), 2 * 60_000L, 1000f)
            main.post { onResult(best) }
        }

        try {
            val option = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                // 连续定位：单次定位经常直接给缓存/网络点，精度会从 30m 跳到几百米。
                isOnceLocation = false
                isOnceLocationLatest = false
                isNeedAddress = false
                isLocationCacheEnable = false
                interval = 1000L
                httpTimeOut = timeoutMs
                isKillProcess = false
            }
            client = AMapLocationClient(ctx.applicationContext).apply {
                setLocationOption(option)
                setLocationListener { loc: AMapLocation? ->
                    if (finished) return@setLocationListener
                    if (loc == null || loc.errorCode != 0) return@setLocationListener
                    if (loc.latitude == 0.0 || loc.longitude == 0.0) return@setLocationListener
                    val timeMs = if (loc.time > 0L) loc.time else System.currentTimeMillis()
                    // 只接受 2 分钟内的点，避免把过期缓存当成当前位置。
                    if (System.currentTimeMillis() - timeMs > 2 * 60_000L) return@setLocationListener
                    fixes += LocationFix.fromGcj(
                        lat = loc.latitude,
                        lng = loc.longitude,
                        accuracyM = loc.accuracy,
                        timeMs = timeMs,
                        source = LocationFix.Source.AMAP
                    )
                    val best = LocationQuality.best(fixes, System.currentTimeMillis(), 2 * 60_000L, 1000f)
                    if (best != null && best.hasAccuracy && best.accuracyM <= targetAccuracyM) finish()
                }
            }
            client.startLocation()
        } catch (e: Throwable) {
            finish()
            return
        }

        main.postDelayed({ finish() }, timeoutMs + 2000)
    }
}
