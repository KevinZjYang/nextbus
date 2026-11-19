package com.yg.busisland

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RealtimeParserTest {

    private fun travel(order: Int, arrivalMs: Long, travelSec: Long, tip: String = "") =
        JSONObject()
            .put("order", order)
            .put("arrivalTime", arrivalMs)
            .put("travelTime", travelSec)
            .put("recommTip", tip)

    private fun bus(id: String, order: Int, travels: JSONArray) =
        JSONObject()
            .put("busId", id)
            .put("order", order)
            .put("nextStationName", "下一站")
            .put("distanceToWaitStn", 1200)
            .put("travels", travels)

    private fun module(buses: List<JSONObject>, targetOrder: Int = 5) = JSONObject()
        .put("targetOrder", targetOrder)
        .put("buses", JSONArray(buses))
        .put("line", JSONObject().put("desc", ""))

    @Test
    fun usesArrivalTimeForTargetOrder() {
        val now = System.currentTimeMillis()
        val data = module(
            listOf(bus("a", 2, JSONArray().put(travel(5, now + 5 * 60_000, 0)))),
            targetOrder = 5
        )
        val result = RealtimeParser.parse(data, 5)
        assertEquals(1, result.buses.size)
        assertEquals(5, result.buses[0].etaMinutes)
        assertEquals("5 分钟", result.buses[0].etaText)
        assertEquals("a", result.buses[0].busId)
        assertEquals(1200, result.buses[0].distance)
    }

    @Test
    fun filtersBusesAlreadyPastTarget() {
        val now = System.currentTimeMillis()
        val data = module(
            listOf(
                bus("passed", 6, JSONArray().put(travel(5, now + 60_000, 0))),
                bus("ahead", 3, JSONArray().put(travel(5, now + 2 * 60_000, 0)))
            ),
            targetOrder = 5
        )
        val result = RealtimeParser.parse(data, 5)
        assertEquals(listOf("ahead"), result.buses.map { it.busId })
    }

    @Test
    fun fallsBackToTravelTimeWhenArrivalTimeMissing() {
        val data = module(
            listOf(bus("a", 1, JSONArray().put(travel(5, 0, 200)))),
            targetOrder = 5
        )
        val result = RealtimeParser.parse(data, 5)
        // 200 秒按 15km/h 附近折算成 4 分钟。
        assertEquals(4, result.buses[0].etaMinutes)
    }

    @Test
    fun fallsBackToClockWhenNoArrivalOrTravel() {
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, 3) }
        val tip = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val data = module(
            listOf(bus("a", 1, JSONArray().put(travel(5, 0, 0, tip)))),
            targetOrder = 5
        )
        val result = RealtimeParser.parse(data, 5)
        assertTrue(result.buses[0].etaMinutes in 2..3)
        assertEquals(tip, result.buses[0].clock)
    }

    @Test
    fun sortsByEtaThenBusId() {
        val now = System.currentTimeMillis()
        val data = module(
            listOf(
                bus("c", 1, JSONArray().put(travel(5, now + 9 * 60_000, 0))),
                bus("b", 2, JSONArray().put(travel(5, now + 1 * 60_000, 0))),
                bus("a", 3, JSONArray().put(travel(5, now + 4 * 60_000, 0)))
            ),
            targetOrder = 5
        )
        val result = RealtimeParser.parse(data, 5)
        assertEquals(listOf("b", "a", "c"), result.buses.map { it.busId })
    }

    @Test
    fun readsLineDescAndNextOperationDesc() {
        val data = module(emptyList())
            .put("nextOperationTimeDesc", "末班 04:50")
        data.getJSONObject("line").put("desc", "不在运营时间")
        val result = RealtimeParser.parse(data, 5)
        assertEquals("不在运营时间", result.lineDesc)
        assertEquals("末班 04:50", result.nextOperationDesc)
        assertTrue(result.buses.isEmpty())
        assertTrue(result.ok)
    }

    @Test
    fun targetOrderComesFromModuleWhenPresent() {
        val now = System.currentTimeMillis()
        val data = module(
            listOf(bus("a", 1, JSONArray().put(travel(7, now + 60_000, 0)))),
            targetOrder = 7
        )
        val result = RealtimeParser.parse(data, 1)
        assertEquals(7, result.targetOrder)
        assertEquals(1, result.buses.size)
    }
}
