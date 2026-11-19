package com.yg.busisland

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyStationsParserTest {

    private fun parse(json: String) = NearbyStationsParser.parse(JSONObject(json))

    private fun gcj(lat: Double, lng: Double) = Gcj.toGcj(lat, lng)

    @Test
    fun `converts wgs stations to gcj`() {
        val stations = parse(
            """
            {"gpstype": "wgs", "nearStations": [
              {"sId": "a", "physicalStId": "pa", "sn": "首钢园", "distance": 120,
               "lat": 39.905506, "lng": 116.151776}
            ]}
            """.trimIndent()
        )
        assertEquals(1, stations.size)
        val expected = gcj(39.905506, 116.151776)
        assertEquals(expected[0], stations[0].lat, 1e-9)
        assertEquals(expected[1], stations[0].lng, 1e-9)
        assertEquals("首钢园", stations[0].name)
        assertEquals(120, stations[0].distance)
    }

    @Test
    fun `keeps gcj stations untouched`() {
        val stations = parse(
            """
            {"gpstype": "gcj", "nearStations": [
              {"sId": "a", "physicalStId": "pa", "sn": "A", "distance": 1,
               "lat": 39.905506, "lng": 116.151776}
            ]}
            """.trimIndent()
        )
        assertEquals(39.905506, stations[0].lat, 0.0)
    }

    @Test
    fun `skips blank sId and dedupes by physicalStId`() {
        val stations = parse(
            """
            {"nearStations": [
              {"sId": "", "physicalStId": "p0", "sn": "no-id", "distance": 1, "lat": 39.9, "lng": 116.2},
              {"sId": "a", "physicalStId": "p1", "sn": "A", "distance": 20, "lat": 39.9, "lng": 116.2},
              {"sId": "a2", "physicalStId": "p1", "sn": "A2", "distance": 30, "lat": 39.91, "lng": 116.21}
            ]}
            """.trimIndent()
        )
        assertEquals(listOf("a"), stations.map { it.sId })
    }

    @Test
    fun `empty data is safe`() {
        assertTrue(parse("{}").isEmpty())
        assertTrue(parse("""{"nearStations": []}""").isEmpty())
    }
}
