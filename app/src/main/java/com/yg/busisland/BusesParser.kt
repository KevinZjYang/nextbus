package com.yg.busisland

import org.json.JSONObject

/**
 * 解析 encryptedLineDetail 解密后的 data.buses。
 *
 * 实测：该响应的 data 没有 gpstype，buses[] 与 lineRoute 同为 WGS-84；
 * 按原 App `BusesData` 的默认值 wgs 处理。每辆车若带 gpsType 则以它为准。
 * 输出 BusPosition 的 lat/lng 一律是 GCJ-02，可直接画到高德地图。
 */
object BusesParser {

    fun parse(data: JSONObject?): List<BusPosition> {
        val arr = data?.optJSONArray("buses") ?: return emptyList()
        val top = GeoDatum.read(data)
        val out = ArrayList<BusPosition>(arr.length())
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val busId = b.optString("busId")
            if (busId.isBlank()) continue
            val lat = b.optDouble("lat", 0.0)
            val lng = b.optDouble("lng", 0.0)
            if (!GeoDatum.valid(lat, lng)) continue
            val p = GeoDatum.convert(lat, lng, GeoDatum.read(b, top))
            out += BusPosition(
                busId = busId,
                order = b.optInt("order", 0),
                lat = p[0],
                lng = p[1],
                speed = b.optDouble("speed", 0.0),
                nextStation = b.optString("nextStationName"),
                distance = b.optInt("distanceToWaitStn", -1)
            )
        }
        return out
    }
}
