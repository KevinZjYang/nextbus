package com.yg.busisland

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption

/**
 * 高德定位封装（Wi-Fi/基站定位）。
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
     * 单次高精度定位。
     * @param timeoutMs 超时（毫秒）
     * @param onResult 主线程回调；失败为 null
     */
    fun once(ctx: Context, timeoutMs: Long = 15000, onResult: (Location?) -> Unit) {
        if (!available) {
            onResult(null)
            return
        }
        ensurePrivacy(ctx)
        val main = Handler(Looper.getMainLooper())
        var finished = false
        var client: AMapLocationClient? = null

        fun finish(loc: Location?) {
            if (finished) return
            finished = true
            runCatching { client?.stopLocation() }
            runCatching { client?.onDestroy() }
            main.post { onResult(loc) }
        }

        try {
            val option = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isOnceLocationLatest = true
                isNeedAddress = true
                httpTimeOut = timeoutMs
                isKillProcess = false
            }
            client = AMapLocationClient(ctx.applicationContext).apply {
                setLocationOption(option)
                setLocationListener { loc: AMapLocation? ->
                    if (loc != null && loc.errorCode == 0 && loc.latitude != 0.0) {
                        val out = Location("amap").apply {
                            latitude = loc.latitude
                            longitude = loc.longitude
                            accuracy = loc.accuracy
                            time = loc.time
                            if (loc.hasAltitude()) altitude = loc.altitude
                        }
                        finish(out)
                    } else {
                        finish(null)
                    }
                }
            }
            client.startLocation()
        } catch (e: Throwable) {
            finish(null)
            return
        }

        main.postDelayed({ finish(null) }, timeoutMs + 3000)
    }
}
