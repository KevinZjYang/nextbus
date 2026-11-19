package com.yg.busisland

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.ceil

/**
 * 实时到站解析。
 *
 * encRefreshLineDetail 的到站时间不在 buses[] 顶层，而在每辆车
 * travels[] 里目标站那一条：arrivalTime（毫秒）/ travelTime（秒）/
 * recommTip（到站时刻）。这里统一取目标站、换算成分钟，并按 ETA 排序。
 */
object RealtimeParser {

    fun parse(module: JSONObject, targetOrder: Int): RealTimeResult {
        val line = module.optJSONObject("line")
        val lineDesc = line?.optString("desc").orEmpty()
            .ifBlank { line?.optString("shortDesc").orEmpty() }
        val modulePre = module.optString("preArrivalTime").orEmpty()
        val nextOp = module.optString("nextOperationTimeDesc").orEmpty()
            .ifBlank { line?.optString("nextOperationTimeDesc").orEmpty() }
        val target = module.optInt("targetOrder", targetOrder).takeIf { it > 0 }
            ?: targetOrder

        val now = System.currentTimeMillis()
        val buses = mutableListOf<BusItem>()
        val arr = module.optJSONArray("buses") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val order = b.optInt("order", 0)
            // 目标站之前的车才有意义；目标站未知时保留全部。
            if (target > 0 && order > target) continue

            var arrivalMs = 0L
            var travelSec = 0L
            var clock = ""
            val travels = b.optJSONArray("travels")
            if (travels != null && travels.length() > 0) {
                val items = (0 until travels.length()).mapNotNull { travels.optJSONObject(it) }
                val t = items.firstOrNull { it.optInt("order", 0) == target }
                    ?: items.firstOrNull { it.optLong("arrivalTime", 0L) > 0L }
                if (t != null) {
                    arrivalMs = t.optLong("arrivalTime", 0L).takeIf { it > 0 }
                        ?: t.optLong("optArrivalTime", 0L)
                    travelSec = t.optLong("travelTime", 0L)
                    clock = t.optString("recommTip").orEmpty()
                }
            }
            if (arrivalMs <= 0L) arrivalMs = b.optLong("arriveTime", 0L)
            if (travelSec <= 0L) travelSec = b.optLong("travelTime", 0L)
            if (clock.isBlank()) clock = b.optString("recommTip").orEmpty()

            var minutes = minutesOf(arrivalMs, travelSec, now)
            if (minutes < 0 && clock.isNotBlank()) {
                minutes = minutesFromClock(clock, now)
            }
            val distance = b.optInt("distanceToWaitStn", -1)
            val etaText = when {
                minutes > 0 -> if (minutes == 1) "1 分钟" else "$minutes 分钟"
                minutes == 0 -> "即将到站"
                arrivalMs in 1..now -> "即将到站"
                else -> "—"
            }
            buses.add(
                BusItem(
                    busId = b.optString("busId"),
                    nextStation = b.optString("nextStationName"),
                    distance = distance,
                    etaText = etaText,
                    etaMinutes = minutes,
                    clock = clock,
                    order = order,
                    lat = b.optDouble("lat", 0.0),
                    lng = b.optDouble("lng", 0.0)
                )
            )
        }
        buses.sortWith(
            compareBy<BusItem>({ if (it.etaMinutes > 0) it.etaMinutes else 9_999 }, { it.busId })
        )
        return RealTimeResult(
            lineDesc = lineDesc,
            preArrival = modulePre,
            buses = buses,
            ok = true,
            targetOrder = target,
            nextOperationDesc = nextOp
        )
    }

    private fun minutesOf(arrivalMs: Long, travelSec: Long, now: Long): Int = when {
        arrivalMs > now -> ceil((arrivalMs - now) / 60_000.0).toInt()
        travelSec > 0 -> ceil(travelSec / 60.0).toInt()
        else -> -1
    }

    /** "02:26" → 距现在几分钟；已过站返回 0。 */
    private fun minutesFromClock(clock: String, now: Long): Int {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(clock.trim()) ?: return -1
        val h = m.groupValues[1].toIntOrNull() ?: return -1
        val mi = m.groupValues[2].toIntOrNull() ?: return -1
        if (h !in 0..23 || mi !in 0..59) return -1
        val nowCal = Calendar.getInstance().apply { timeInMillis = now }
        val target = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, mi)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var diff = ((target.timeInMillis - nowCal.timeInMillis) / 60_000L).toInt()
        if (diff < -120) diff += 24 * 60
        return if (diff <= 0) 0 else diff
    }
}
