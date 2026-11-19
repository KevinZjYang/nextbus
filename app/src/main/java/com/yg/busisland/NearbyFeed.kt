package com.yg.busisland

/** 一次「附近」查询的完整结果。 */
data class NearbyData(
    val cityId: String,
    val cityName: String,
    /** 本次自动识别出的城市；没有识别则为 null。 */
    val detectedCity: CityInfo?,
    val label: String,
    val lat: Double,
    val lng: Double,
    val stations: List<NearbyStation>,
    val linesByStation: Map<String, List<StationLine>>,
    /** 部分站点请求失败或超时。 */
    val partial: Boolean,
    val fetchedAt: Long
)

/** 首页/附近页共用的一行：某站点的某条线路。 */
data class NearbyLineRow(
    val station: NearbyStation,
    val line: StationLine,
    val subscription: LineSub?,
    /** 上车站名：订阅站优先，否则就是当前站点。 */
    val boardingName: String = station.name,
    /** 当前站点是否就是订阅记录里的上车站。 */
    val boardingMatchesStation: Boolean = true,
    /** 上车站到当前位置的距离；订阅站不在附近时为 -1。 */
    val boardingDistanceM: Int = station.distance
)

/**
 * 把「站点 → 线路」压平成首页列表：
 *  - 订阅优先落在订阅记录里的上车站，而不是“最近站”；
 *  - 其余同线路同方向只保留最近站点；
 *  - 订阅行置顶，其余按 ETA、距离、标题排序。
 */
object NearbySelector {

    fun build(
        stations: List<NearbyStation>,
        linesByStation: Map<String, List<StationLine>>,
        subscriptions: List<LineSub>,
        maxRows: Int = 60
    ): List<NearbyLineRow> {
        if (stations.isEmpty() || maxRows <= 0) return emptyList()

        val orderedStations = stations.sortedBy {
            if (it.distance >= 0) it.distance else Int.MAX_VALUE
        }
        val subsByKey = subscriptions.groupBy { it.lineId to it.direction }
        val subOrder = HashMap<Pair<String, Int>, Int>()
        subscriptions.forEachIndexed { index, sub ->
            subOrder.putIfAbsent(sub.lineId to sub.direction, index)
        }

        val picked = LinkedHashMap<Pair<String, Int>, NearbyLineRow>()

        // 第一段：订阅记录指到哪一站，就把哪一站作为这一行的上车站。
        // 这样即使订阅站比“最近站”远，距离/ETA 也不会指错地方。
        for ((key, subs) in subsByKey) {
            val sub = subs.firstOrNull() ?: continue
            for (station in orderedStations) {
                val line = linesByStation[station.sId].orEmpty().firstOrNull {
                    it.lineId == key.first && it.direction == key.second &&
                        stationMatches(sub, station, it)
                } ?: continue
                picked[key] = NearbyLineRow(
                    station = station,
                    line = line,
                    subscription = sub,
                    boardingName = sub.stationName.ifBlank { station.name },
                    boardingMatchesStation = true,
                    boardingDistanceM = station.distance
                )
                break
            }
        }

        // 第二段：其余线路仍按最近站收一行；订阅站不在附近时也把订阅挂上，
        // 保证置顶，但明确标记“当前站点不是上车站”。
        for (station in orderedStations) {
            for (line in linesByStation[station.sId].orEmpty()) {
                if (line.lineId.isBlank()) continue
                val key = line.lineId to line.direction
                if (picked.containsKey(key)) continue
                val sub = subsByKey[key]?.firstOrNull()
                picked[key] = NearbyLineRow(
                    station = station,
                    line = line,
                    subscription = sub,
                    boardingName = sub?.stationName?.ifBlank { station.name } ?: station.name,
                    boardingMatchesStation = sub == null,
                    boardingDistanceM = if (sub == null) station.distance else -1
                )
            }
        }

        return picked.values.sortedWith(
            compareBy<NearbyLineRow> { if (it.subscription == null) 1 else 0 }
                .thenBy { row ->
                    row.subscription?.let {
                        subOrder[it.lineId to it.direction] ?: Int.MAX_VALUE
                    } ?: Int.MAX_VALUE
                }
                .thenBy { etaMinutes(it.line.etaText) }
                .thenBy { if (it.station.distance >= 0) it.station.distance else Int.MAX_VALUE }
                .thenBy { it.line.title }
        ).take(maxRows)
    }

    /**
     * 订阅记录是否指向这个站点：
     * 有 stationId 就只认 id（兼容 sId / physicalStId / 线路里的 sId）；
     * 老记录没 id 时按站序，再退到站名。
     */
    private fun stationMatches(sub: LineSub, station: NearbyStation, line: StationLine): Boolean {
        if (sub.stationId.isNotBlank()) {
            return sub.stationId == line.sId ||
                sub.stationId == station.sId ||
                sub.stationId == station.physicalStId
        }
        if (sub.targetOrder > 0 && line.order > 0) return sub.targetOrder == line.order
        if (sub.stationName.isNotBlank()) {
            return sub.stationName == station.name || sub.stationName == line.stationName
        }
        return false
    }

    /** 把「即将到站 / N 分钟 / N 秒 / —」变成可排序分钟数。 */
    fun etaMinutes(text: String): Int {
        val t = text.trim()
        if (t.isEmpty()) return 9999
        if (t.contains("即将") || t.contains("进站") || t.contains("到站")) return 0
        Regex("""(\d+)\s*分钟""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""(\d+)\s*秒""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return 1 }
        return 9999
    }
}
