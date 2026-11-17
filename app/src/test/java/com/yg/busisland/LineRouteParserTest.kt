package com.yg.busisland

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineRouteParserTest {

    private fun parse(json: String) = LineRouteParser.parse(JSONObject(json))

    @Test
    fun `sorts stops but preserves route array order`() {
        val route = parse(
            """
            {
              "stations": [
                {"order": 2, "sId": "b", "sn": "B", "lat": 30.2, "lng": 120.2},
                {"order": 1, "sId": "a", "sn": "A", "lat": 30.1, "lng": 120.1}
              ],
              "route": [
                {"stopOrder": 3, "lat": 30.3, "lng": 120.3},
                {"stopOrder": 1, "lat": 30.1, "lng": 120.1},
                {"stopOrder": 2, "lat": 30.2, "lng": 120.2}
              ]
            }
            """.trimIndent()
        )
        assertEquals(listOf("a", "b"), route.stops.map { it.sId })
        assertEquals(listOf(30.3, 30.1, 30.2), route.points.map { it.lat })
    }

    @Test
    fun `falls back to stations when route is missing`() {
        val route = parse(
            """
            {
              "stations": [
                {"order": 1, "sId": "a", "sn": "A", "lat": 30.1, "lng": 120.1},
                {"order": 2, "sId": "b", "sn": "B", "lat": 30.2, "lng": 120.2}
              ]
            }
            """.trimIndent()
        )
        assertEquals(listOf(30.1, 30.2), route.points.map { it.lat })
    }

    @Test
    fun `filters invalid points and consecutive duplicates`() {
        val route = parse(
            """
            {
              "stations": [],
              "route": [
                {"stopOrder": 1, "lat": 30.1, "lng": 120.1},
                {"stopOrder": 2, "lat": 30.1, "lng": 120.1},
                {"stopOrder": 3, "lat": 0.0, "lng": 0.0},
                {"stopOrder": 4, "lat": 91.0, "lng": 120.0},
                {"stopOrder": 5, "lat": 30.2, "lng": 120.2}
              ]
            }
            """.trimIndent()
        )
        assertEquals(2, route.points.size)
        assertEquals(30.1, route.points[0].lat, 0.0)
        assertEquals(30.2, route.points[1].lat, 0.0)
    }

    @Test
    fun `single route point falls back to stations`() {
        val route = parse(
            """
            {
              "stations": [
                {"order": 1, "sId": "a", "sn": "A", "lat": 30.1, "lng": 120.1},
                {"order": 2, "sId": "b", "sn": "B", "lat": 30.2, "lng": 120.2}
              ],
              "route": [{"stopOrder": 1, "lat": 30.15, "lng": 120.15}]
            }
            """.trimIndent()
        )
        assertEquals(listOf(30.1, 30.2), route.points.map { it.lat })
    }

    @Test
    fun `empty payload is safe`() {
        val route = parse("{}")
        assertTrue(route.stops.isEmpty())
        assertTrue(route.points.isEmpty())
    }
}
