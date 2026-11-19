package com.yg.busisland

import android.app.Activity

/**
 * 定位总入口：高德 + 系统定位 + 最近一次有效缓存，统一挑最优。
 * 高德可用时先等高德；精度不够再补 6 秒系统定位。
 */
object LocationProvider {

    fun best(activity: Activity, onResult: (LocationFix?) -> Unit) {
        val results = ArrayList<LocationFix>()
        var done = false

        fun finish() {
            if (done) return
            done = true
            onResult(LocationQuality.best(results, System.currentTimeMillis(), 2 * 60_000L, 1000f))
        }

        fun addAndMaybeFinish(fix: LocationFix?) {
            if (fix != null) results += fix
            finish()
        }

        SystemLocator.lastGood(activity)?.let { results += it }

        if (AmapLocator.available) {
            AmapLocator.bestFix(activity, 8_000L, 50f) { amap ->
                if (amap != null) results += amap
                val bestNow = LocationQuality.best(results, System.currentTimeMillis(), 2 * 60_000L, 1000f)
                if (bestNow != null && bestNow.hasAccuracy && bestNow.accuracyM <= LocationFix.COARSE_ACCURACY_M) {
                    finish()
                } else {
                    SystemLocator.current(activity, 6_000L) { system -> addAndMaybeFinish(system) }
                }
            }
        } else {
            SystemLocator.current(activity, 10_000L) { system -> addAndMaybeFinish(system) }
        }
    }
}
