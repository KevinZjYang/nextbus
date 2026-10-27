package com.yg.busisland

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

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

/** 附近站点（车来了 nearPhysicalStns） */
data class NearbyStation(
    val sId: String,
    val physicalStId: String,
    val name: String,
    val distance: Int,
    val lat: Double,
    val lng: Double
)

/** 某个站点经过的线路（车来了 phyStnDetail） */
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

/** 一条订阅线路（含目标站与所属场景） */
data class LineSub(
    val cityId: String,
    val lineId: String,
    val lineName: String,
    val lineNo: String,
    val direction: Int,
    val targetOrder: Int,
    val stationName: String,
    val stationId: String,
    val place: String, // 地点 id
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
            place = o.optString("place", "home"),
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

/** 通勤时段（支持跨天，end < start 时视为跨零点） */
data class Schedule(
    val morningStart: Int = 7 * 60,
    val morningEnd: Int = 9 * 60 + 30,
    val eveningStart: Int = 17 * 60,
    val eveningEnd: Int = 19 * 60 + 30,
    val weekdays: Int = 0b0011111 // 周一~周五，bit0=周一
) {
    fun activeAt(cal: Calendar): Boolean {
        val dow = cal.get(Calendar.DAY_OF_WEEK) // 1=周日
        val bit = when (dow) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6
        }
        if (weekdays and (1 shl bit) == 0) return false
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return inRange(m, morningStart, morningEnd) || inRange(m, eveningStart, eveningEnd)
    }

    private fun inRange(v: Int, start: Int, end: Int): Boolean =
        if (start <= end) v in start..end else (v >= start || v <= end)

    fun toJson(): JSONObject = JSONObject()
        .put("ms", morningStart).put("me", morningEnd)
        .put("es", eveningStart).put("ee", eveningEnd)
        .put("wd", weekdays)

    companion object {
        fun fromJson(o: JSONObject?): Schedule {
            if (o == null) return Schedule()
            return Schedule(
                o.optInt("ms", 7 * 60),
                o.optInt("me", 9 * 60 + 30),
                o.optInt("es", 17 * 60),
                o.optInt("ee", 19 * 60 + 30),
                o.optInt("wd", 0b0011111)
            )
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

data class RealTimeResult(
    val lineDesc: String,
    val preArrival: String,
    val buses: List<BusItem>,
    val ok: Boolean,
    val message: String = ""
)
