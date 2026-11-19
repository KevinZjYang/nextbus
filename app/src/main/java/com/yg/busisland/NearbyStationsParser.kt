package com.yg.busisland

import org.json.JSONObject

/**
 * 解析 encryptedNearPhysicalStns 的 data.nearStations。
 *
 * 实测：响应顶层 gpstype 恒为 wgs，站点 lat/lng 与 lineRoute 完全一致。
 * 输出 NearbyStation 的 lat/lng 一律是 GCJ-02。
 */
object NearbyStationsParser {

    fun parse(data: JSONObject?): List<NearbyStation> {
        val arr = data?.optJSONArray("nearStations") ?: return emptyList()
        val top = GeoDatum.read(data)
        val out = ArrayList<NearbyStation>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sId = o.optString("sId")
            if (sId.isBlank()) continue
            val lat = o.optDouble("lat", 0.0)
            val lng = o.optDouble("lng", 0.0)
            if (!GeoDatum.valid(lat, lng)) continue
            val p = GeoDatum.convert(lat, lng, GeoDatum.read(o, top))
            out += NearbyStation(
                sId = sId,
                physicalStId = o.optString("physicalStId"),
                name = o.optString("sn"),
                distance = o.optInt("distance", -1),
                lat = p[0],
                lng = p[1]
            )
        }
        return out.distinctBy { it.physicalStId.ifBlank { it.sId } }
    }
}
