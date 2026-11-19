package com.yg.busisland

import org.json.JSONObject

/** 地图上的一个坐标点（GCJ-02；解析边界已把接口的 WGS-84 转过来）。 */
data class GeoPoint(val lat: Double, val lng: Double)

/** 线路经过的站点。 */
data class RouteStop(
    val order: Int,
    val sId: String,
    val name: String,
    val lat: Double,
    val lng: Double
)

/**
 * 线路走向：stops 是站点序列，points 是画折线用的点。
 * points 优先取接口 route[]；路线点不足时回退到 stations[]。
 */
data class LineRoute(
    val stops: List<RouteStop>,
    val points: List<GeoPoint>
)

/** 解析 lineRoute 接口返回的 data 对象，不依赖 Android。 */
object LineRouteParser {

    fun parse(data: JSONObject?): LineRoute {
        if (data == null) return LineRoute(emptyList(), emptyList())
        // 顶层 gpstype 缺省 wgs（与原 App LineRouteData 一致）；站点可带 gpsType 覆盖。
        val top = GeoDatum.read(data)
        val stops = parseStops(data.optJSONArray("stations"), top)
        val route = parseRoute(data.optJSONArray("route"), top)
        val fallback = stops.map { GeoPoint(it.lat, it.lng) }
        val points = when {
            route.size >= 2 -> route
            fallback.size >= 2 -> fallback
            else -> route.ifEmpty { fallback }
        }
        return LineRoute(stops, dedupeConsecutive(points))
    }

    private fun parseStops(array: org.json.JSONArray?, top: String): List<RouteStop> {
        if (array == null) return emptyList()
        val rows = ArrayList<RouteStop>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val lat = o.optDouble("lat", 0.0)
            val lng = o.optDouble("lng", 0.0)
            if (!GeoDatum.valid(lat, lng)) continue
            val p = GeoDatum.convert(lat, lng, GeoDatum.read(o, top))
            rows += RouteStop(
                order = o.optInt("order", i + 1),
                sId = o.optString("sId"),
                name = o.optString("sn"),
                lat = p[0],
                lng = p[1]
            )
        }
        return rows.sortedBy { it.order }
    }

    private fun parseRoute(array: org.json.JSONArray?, top: String): List<GeoPoint> {
        if (array == null) return emptyList()
        val rows = ArrayList<GeoPoint>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val lat = o.optDouble("lat", 0.0)
            val lng = o.optDouble("lng", 0.0)
            if (!GeoDatum.valid(lat, lng)) continue
            // route[] 本身是沿着线路的连续点；stopOrder 只标在少数点上，
            // 排序会把没有 stopOrder 的点全排到前面，画成乱线。
            val p = GeoDatum.convert(lat, lng, GeoDatum.read(o, top))
            rows += GeoPoint(p[0], p[1])
        }
        return rows
    }

    private fun dedupeConsecutive(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size < 2) return points
        val out = ArrayList<GeoPoint>(points.size)
        points.forEach { p ->
            val last = out.lastOrNull()
            if (last == null || last.lat != p.lat || last.lng != p.lng) out += p
        }
        return out
    }
}
