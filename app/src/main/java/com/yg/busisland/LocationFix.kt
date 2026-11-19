package com.yg.busisland

import android.location.Location

/**
 * 一次定位结果，坐标统一为 GCJ-02。
 * accuracyM <= 0 表示来源没有给出精度。
 */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val timeMs: Long,
    val source: Source
) {
    enum class Source { AMAP, SYSTEM, PLACE }

    val hasAccuracy: Boolean get() = accuracyM > 0f

    /** 精度未知或超过 150m：只能当“大致位置”，不足以判断上车站。 */
    val isCoarse: Boolean get() = !hasAccuracy || accuracyM > COARSE_ACCURACY_M

    companion object {
        const val COARSE_ACCURACY_M = 150f

        fun fromGcj(lat: Double, lng: Double, accuracyM: Float, timeMs: Long, source: Source): LocationFix =
            LocationFix(lat, lng, accuracyM, timeMs, source)

        /** 系统 LocationManager 给的是 WGS-84，这里显式转成 GCJ。 */
        fun fromWgs(lat: Double, lng: Double, accuracyM: Float, timeMs: Long, source: Source): LocationFix {
            val p = Gcj.toGcj(lat, lng)
            return LocationFix(p[0], p[1], accuracyM, timeMs, source)
        }

        fun fromSystem(loc: Location): LocationFix = fromWgs(
            loc.latitude,
            loc.longitude,
            if (loc.hasAccuracy()) loc.accuracy else -1f,
            if (loc.time > 0L) loc.time else System.currentTimeMillis(),
            Source.SYSTEM
        )
    }
}

/**
 * 在多个候选定位点里挑一个：精度优先，同时惩罚过期的点。
 * 未知精度按 500m 计，避免“没有 accuracy 的缓存点”压过真实高精度点。
 */
object LocationQuality {

    fun score(fix: LocationFix, nowMs: Long): Double {
        val accuracy = if (fix.hasAccuracy) fix.accuracyM.toDouble() else 500.0
        val ageSec = ((nowMs - fix.timeMs).coerceAtLeast(0L)) / 1000.0
        return accuracy + ageSec * 0.5
    }

    fun best(
        fixes: List<LocationFix>,
        nowMs: Long,
        maxAgeMs: Long = 10 * 60_000L,
        maxAccuracyM: Float = 1000f
    ): LocationFix? = fixes
        .filter { fix ->
            val age = nowMs - fix.timeMs
            age in -60_000L..maxAgeMs && (!fix.hasAccuracy || fix.accuracyM <= maxAccuracyM)
        }
        .minByOrNull { score(it, nowMs) }
}
