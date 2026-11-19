package com.yg.busisland

import org.json.JSONObject
import kotlin.math.abs

/**
 * 坐标系约定：接口响应自带 gpstype / gpsType（缺省 wgs），
 * 在解析边界统一转成 GCJ-02；模型、缓存、地图只认 GCJ。
 *
 * 高德底图与高德定位是 GCJ-02；系统 LocationManager 是 WGS-84；
 * 常去地点 / POI / 手输坐标按 GCJ 处理。
 */
internal object GeoDatum {
    const val WGS = "wgs"
    const val GCJ = "gcj"

    /** 读取坐标标注；兼容 gpsType（站点/巴士）和 gpstype（整个响应）。 */
    fun read(o: JSONObject?, fallback: String = WGS): String {
        val raw = o?.optString("gpsType").orEmpty()
            .ifBlank { o?.optString("gpstype").orEmpty() }
            .ifBlank { fallback }
        return if (raw.equals(GCJ, ignoreCase = true)) GCJ else WGS
    }

    /** 把原始坐标转成 GCJ；GCJ 原样返回。 */
    fun convert(lat: Double, lng: Double, datum: String): DoubleArray =
        if (datum == GCJ) doubleArrayOf(lat, lng) else Gcj.toGcj(lat, lng)

    fun valid(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() &&
            abs(lat) <= 90.0 && abs(lng) <= 180.0 &&
            !(lat == 0.0 && lng == 0.0)
}
