package com.yg.busisland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbySelectorTest {

    private fun station(id: String, distance: Int, name: String = "站$id") =
        NearbyStation(
            sId = id,
            physicalStId = "p-$id",
            name = name,
            distance = distance,
            lat = 0.0,
            lng = 0.0
        )

    private fun line(
        lineId: String,
        direction: Int,
        eta: String = "",
        lineNo: String = lineId,
        order: Int = 1
    ) = StationLine(
        sId = "s-$lineId-$direction",
        stationName = "站",
        lineId = lineId,
        lineNo = lineNo,
        lineName = lineNo,
        direction = direction,
        startSn = "A",
        endSn = "B",
        firstTime = "06:00",
        lastTime = "22:00",
        stationsNum = 10,
        order = order,
        etaText = eta
    )

    private fun sub(
        lineId: String,
        direction: Int,
        station: String = "站",
        order: Int = 1,
        stationId: String = "s-$lineId-$direction"
    ) = LineSub(
        cityId = "027",
        lineId = lineId,
        lineName = lineId,
        lineNo = lineId,
        direction = direction,
        targetOrder = order,
        stationName = station,
        stationId = stationId,
        place = ""
    )

    @Test
    fun dedupesSameLineDirectionKeepingNearestStation() {
        val near = station("near", 50)
        val far = station("far", 300)
        val rows = NearbySelector.build(
            stations = listOf(far, near),
            linesByStation = mapOf(
                near.sId to listOf(line("1", 0)),
                far.sId to listOf(line("1", 0), line("2", 0))
            ),
            subscriptions = emptyList()
        )

        assertEquals(2, rows.size)
        val first = rows.first { it.line.lineId == "1" }
        assertEquals(near.sId, first.station.sId)
    }

    @Test
    fun keepsTwoDirectionsOfSameLine() {
        val st = station("s", 100)
        val rows = NearbySelector.build(
            stations = listOf(st),
            linesByStation = mapOf(
                st.sId to listOf(line("1", 0), line("1", 1))
            ),
            subscriptions = emptyList()
        )
        assertEquals(2, rows.size)
        assertEquals(setOf(0, 1), rows.map { it.line.direction }.toSet())
    }

    @Test
    fun pinsSubscriptionsInSubscriptionOrder() {
        val st = station("s", 100)
        val rows = NearbySelector.build(
            stations = listOf(st),
            linesByStation = mapOf(
                st.sId to listOf(
                    line("1", 0, eta = "1 分钟"),
                    line("2", 0, eta = "9 分钟"),
                    line("3", 0, eta = "2 分钟")
                )
            ),
            subscriptions = listOf(sub("2", 0), sub("3", 0))
        )
        assertEquals(listOf("2", "3", "1"), rows.map { it.line.lineId })
        assertTrue(rows[0].subscription != null)
        assertTrue(rows[1].subscription != null)
        assertTrue(rows[2].subscription == null)
    }

    @Test
    fun sortsUnsubscribedByEta() {
        val st = station("s", 100)
        val rows = NearbySelector.build(
            stations = listOf(st),
            linesByStation = mapOf(
                st.sId to listOf(
                    line("slow", 0, eta = "8 分钟"),
                    line("fast", 0, eta = "1 分钟"),
                    line("unknown", 0, eta = "")
                )
            ),
            subscriptions = emptyList()
        )
        assertEquals(listOf("fast", "slow", "unknown"), rows.map { it.line.lineId })
    }

    @Test
    fun etaMinutesHandlesCommonText() {
        assertEquals(0, NearbySelector.etaMinutes("即将到站"))
        assertEquals(0, NearbySelector.etaMinutes("列车进站"))
        assertEquals(3, NearbySelector.etaMinutes("3 分钟"))
        assertEquals(1, NearbySelector.etaMinutes("30 秒"))
        assertEquals(9999, NearbySelector.etaMinutes("—"))
        assertEquals(9999, NearbySelector.etaMinutes(""))
    }

    @Test
    fun subscribedBoardingStationWinsOverNearestStation() {
        val near = station("near", 50)
        val far = station("far", 300, name = "远站")
        val rows = NearbySelector.build(
            stations = listOf(near, far),
            linesByStation = mapOf(
                near.sId to listOf(line("1", 0, order = 2)),
                far.sId to listOf(line("1", 0, order = 9))
            ),
            subscriptions = listOf(
                sub("1", 0, station = "远站", order = 9, stationId = "far")
            )
        )

        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals("far", row.station.sId)
        assertEquals("远站", row.boardingName)
        assertEquals(300, row.boardingDistanceM)
        assertTrue(row.boardingMatchesStation)
    }

    @Test
    fun subscribedStationAwayFallsBackToNearestButKeepsSubscription() {
        val near = station("near", 50)
        val rows = NearbySelector.build(
            stations = listOf(near),
            linesByStation = mapOf(near.sId to listOf(line("1", 0))),
            subscriptions = listOf(
                sub("1", 0, station = "远站", order = 9, stationId = "elsewhere")
            )
        )

        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals("near", row.station.sId)
        assertEquals("远站", row.boardingName)
        assertFalse(row.boardingMatchesStation)
        assertEquals(-1, row.boardingDistanceM)
        assertTrue(row.subscription != null)
    }

    @Test
    fun matchesLegacySubscriptionByOrder() {
        val st = station("s", 80)
        val rows = NearbySelector.build(
            stations = listOf(st),
            linesByStation = mapOf(st.sId to listOf(line("1", 0, order = 4))),
            subscriptions = listOf(
                sub("1", 0, station = "旧站名", order = 4, stationId = "")
            )
        )
        assertEquals(1, rows.size)
        assertTrue(rows[0].boardingMatchesStation)
        assertEquals(80, rows[0].boardingDistanceM)
    }

    @Test
    fun matchesLegacySubscriptionByStationName() {
        val st = station("s", 80, name = "天安门东")
        val rows = NearbySelector.build(
            stations = listOf(st),
            linesByStation = mapOf(st.sId to listOf(line("1", 0, order = 0))),
            subscriptions = listOf(
                sub("1", 0, station = "天安门东", order = 0, stationId = "")
            )
        )
        assertEquals(1, rows.size)
        assertTrue(rows[0].boardingMatchesStation)
    }

    @Test
    fun handlesEmptyInput() {
        assertTrue(
            NearbySelector.build(emptyList(), emptyMap(), emptyList()).isEmpty()
        )
        assertTrue(
            NearbySelector.build(
                listOf(station("s", 10)),
                emptyMap(),
                emptyList()
            ).isEmpty()
        )
    }
}
