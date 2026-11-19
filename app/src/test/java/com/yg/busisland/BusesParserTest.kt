package com.yg.busisland

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusesParserTest {

    private fun parse(json: String) = BusesParser.parse(JSONObject(json))

    private fun gcj(lat: Double, lng: Double) = Gcj.toGcj(lat, lng)

    private val busBody =
        """
        {"busId": "b1", "order": 8, "lat": 39.906184323074356, "lng": 116.27604382980763,
         "speed": 0.6, "nextStationName": "西单", "distanceToWaitStn": 8092}
        """.trimIndent()

    @Test
    fun `converts wgs buses to gcj`() {
        val buses = parse("""{"gpstype": "wgs", "buses": [$busBody]}""")
        assertEquals(1, buses.size)
        val expected = gcj(39.906184323074356, 116.27604382980763)
        assertEquals(expected[0], buses[0].lat, 1e-9)
        assertEquals(expected[1], buses[0].lng, 1e-9)
        assertEquals(8, buses[0].order)
        assertEquals("西单", buses[0].nextStation)
        assertEquals(8092, buses[0].distance)
    }

    @Test
    fun `defaults to wgs when gpstype is absent`() {
        val buses = parse("""{"buses": [$busBody]}""")
        assertEquals(1, buses.size)
        assertEquals(gcj(39.906184323074356, 116.27604382980763)[0], buses[0].lat, 1e-9)
    }

    @Test
    fun `keeps gcj buses untouched`() {
        val buses = parse("""{"gpstype": "gcj", "buses": [$busBody]}""")
        assertEquals(39.906184323074356, buses[0].lat, 0.0)
        assertEquals(116.27604382980763, buses[0].lng, 0.0)
    }

    @Test
    fun `per bus gpsType overrides top level`() {
        val buses = parse(
            """
            {"gpstype": "wgs", "buses": [
              {"busId": "b1", "lat": 39.906184, "lng": 116.276044, "gpsType": "gcj"},
              {"busId": "b2", "lat": 39.906184, "lng": 116.276044}
            ]}
            """.trimIndent()
        )
        assertEquals(2, buses.size)
        assertEquals(39.906184, buses[0].lat, 0.0)
        assertEquals(gcj(39.906184, 116.276044)[0], buses[1].lat, 1e-9)
    }

    @Test
    fun `filters invalid buses`() {
        val buses = parse(
            """
            {"gpstype": "wgs", "buses": [
              {"busId": "", "lat": 39.9, "lng": 116.2},
              {"busId": "zero", "lat": 0.0, "lng": 0.0},
              {"busId": "bad", "lat": 91.0, "lng": 116.2},
              {"busId": "ok", "lat": 39.9, "lng": 116.2}
            ]}
            """.trimIndent()
        )
        assertEquals(listOf("ok"), buses.map { it.busId })
    }

    @Test
    fun `empty buses is safe`() {
        assertTrue(parse("{}").isEmpty())
        assertTrue(parse("""{"buses": []}""").isEmpty())
    }
}
