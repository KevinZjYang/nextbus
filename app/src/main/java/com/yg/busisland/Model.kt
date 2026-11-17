package com.yg.busisland

import org.json.JSONArray
import org.json.JSONObject

data class Place(
    val id: String = "",
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val cityId: String = ""
) {
    val valid: Boolean get() = lat != 0.0 || lng != 0.0

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("lat", lat)
        .put("lng", lng)
        .put("cityId", cityId)

    companion object {
        fun fromJson(o: JSONObject?): Place? {
            if (o == null) return null
            val p = Place(
                o.optString("id", ""),
                o.optString("name", ""),
                o.optDouble("lat", 0.0),
                o.optDouble("lng", 0.0),
                o.optString("cityId", "")
            )
            return if (p.valid || p.name.isNotEmpty()) p else null
        }
    }
}

/** 附近站点（nearPhysicalStns） */
data class NearbyStation(
    val sId: String,
    val physicalStId: String,
    val name: String,
    val distance: Int,
    val lat: Double,
    val lng: Double
)

/** 某个站点经过的线路（phyStnDetail） */
data class StationLine(
    val sId: String,
    val stationName: String,
    val lineId: String,
    val lineNo: String,
    val lineName: String,
    val direction: Int,
    val startSn: String,
    val endSn: String,
    val firstTime: String,
    val lastTime: String,
    val stationsNum: Int,
    val order: Int = 0,
    val etaText: String = ""
) {
    val title: String get() = when {
        lineNo.isBlank() -> lineName
        lineNo.endsWith("路") || lineNo.endsWith("线") -> lineNo
        else -> "$lineNo 路"
    }
    val towards: String get() = if (endSn.isNotBlank()) "往 $endSn" else ""
    val routeText: String get() = when {
        startSn.isBlank() && endSn.isBlank() -> ""
        startSn.isBlank() -> endSn
        endSn.isBlank() -> startSn
        else -> "$startSn ↔ $endSn"
    }
    val timeRange: String get() = when {
        firstTime.isBlank() && lastTime.isBlank() -> ""
        else -> "首 $firstTime 末 $lastTime"
    }
}

/** 一条订阅线路（含目标站；地点字段仅为兼容旧数据保留） */
data class LineSub(
    val cityId: String,
    val lineId: String,
    val lineName: String,
    val lineNo: String,
    val direction: Int,
    val targetOrder: Int,
    val stationName: String,
    val stationId: String,
    val place: String = "", // 旧版地点 id，可为空
    val startSn: String = "",
    val endSn: String = ""
) {
    /** 起终点：北郊客运站 ↔ 黄山街 */
    val routeText: String get() = when {
        startSn.isBlank() && endSn.isBlank() -> ""
        startSn.isBlank() -> endSn
        endSn.isBlank() -> startSn
        else -> "$startSn ↔ $endSn"
    }

    val title: String get() = when {
        lineNo.isBlank() -> lineName
        lineNo.endsWith("路") || lineNo.endsWith("线") -> lineNo
        else -> "$lineNo 路"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("cityId", cityId)
        .put("lineId", lineId)
        .put("lineName", lineName)
        .put("lineNo", lineNo)
        .put("direction", direction)
        .put("targetOrder", targetOrder)
        .put("stationName", stationName)
        .put("stationId", stationId)
        .put("place", place)
        .put("startSn", startSn)
        .put("endSn", endSn)

    companion object {
        fun fromJson(o: JSONObject): LineSub = LineSub(
            cityId = o.optString("cityId"),
            lineId = o.optString("lineId"),
            lineName = o.optString("lineName"),
            lineNo = o.optString("lineNo"),
            direction = o.optInt("direction", 0),
            targetOrder = o.optInt("targetOrder", 0),
            stationName = o.optString("stationName"),
            stationId = o.optString("stationId"),
            place = o.optString("place", ""),
            startSn = o.optString("startSn"),
            endSn = o.optString("endSn")
        )

        fun listFromJson(s: String?): MutableList<LineSub> {
            val out = mutableListOf<LineSub>()
            if (s.isNullOrBlank()) return out
            runCatching {
                val arr = JSONArray(s)
                for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
            }
            return out
        }

        fun listToJson(list: List<LineSub>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}

/** 首末班文案，形如「首班 07:10 · 末班 21:30」 */
fun RealTimeResult.firstLastText(): String {
    val first = runCatching { Regex("""(首班[^,，;；]*)""").find(lineDesc)?.value }.getOrNull().orEmpty()
    return first
}

data class CityInfo(
    val id: String,
    val name: String,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val pinyin: String = ""
)

data class BusItem(    val busId: String,
    val nextStation: String,
    val distance: Int,
    val etaText: String
)

/** 线路上正在运行的车辆（encryptedLineDetail 的 buses[]，坐标为 GCJ-02）。 */
data class BusPosition(
    val busId: String,
    val order: Int,
    val lat: Double,
    val lng: Double,
    val speed: Double,
    val nextStation: String,
    val distance: Int
)

data class RealTimeResult(
    val lineDesc: String,
    val preArrival: String,
    val buses: List<BusItem>,
    val ok: Boolean,
    val message: String = ""
)
