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
    val subscription: LineSub?
)

/**
 * 把「站点 → 线路」压平成首页列表：
 *  - 同一线路同方向只保留最近站点；
 *  - 订阅按 lineId + direction 匹配，不依赖地点；
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
        for (station in orderedStations) {
            for (line in linesByStation[station.sId].orEmpty()) {
                if (line.lineId.isBlank()) continue
                val key = line.lineId to line.direction
                if (picked.containsKey(key)) continue
                picked[key] = NearbyLineRow(
                    station = station,
                    line = line,
                    subscription = subsByKey[key]?.firstOrNull()
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
