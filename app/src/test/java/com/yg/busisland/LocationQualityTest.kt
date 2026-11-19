package com.yg.busisland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityTest {

    private val now = 1_800_000_000_000L

    private fun fix(
        accuracy: Float,
        ageMs: Long = 0,
        source: LocationFix.Source = LocationFix.Source.SYSTEM
    ) = LocationFix(39.9, 116.4, accuracy, now - ageMs, source)

    @Test
    fun `prefers better accuracy when ages are close`() {
        val coarse = fix(300f)
        val fine = fix(30f, ageMs = 5_000)
        val best = LocationQuality.best(listOf(coarse, fine), now)
        assertEquals(30f, best!!.accuracyM, 0f)
    }

    @Test
    fun `rejects stale and wildly inaccurate fixes`() {
        assertNull(LocationQuality.best(listOf(fix(30f, ageMs = 11 * 60_000L)), now))
        assertNull(LocationQuality.best(listOf(fix(5_000f)), now))
    }

    @Test
    fun `finishes unknown accuracy`() {
        val unknown = fix(0f, ageMs = 60_000)
        val known = fix(500f)
        assertEquals(500f, LocationQuality.best(listOf(unknown, known), now)!!.accuracyM, 0f)
        assertFalse(unknown.hasAccuracy)
    }

    @Test
    fun `coarse threshold`() {
        assertTrue(fix(151f).isCoarse)
        assertFalse(fix(150f).isCoarse)
        assertTrue(fix(0f).isCoarse)
    }

    @Test
    fun `converts system wgs to gcj`() {
        val gcj = LocationFix.fromWgs(39.905506, 116.151776, 32f, now, LocationFix.Source.SYSTEM)
        val expected = Gcj.toGcj(39.905506, 116.151776)
        assertEquals(expected[0], gcj.lat, 1e-9)
        assertEquals(expected[1], gcj.lng, 1e-9)
        assertEquals(LocationFix.Source.SYSTEM, gcj.source)
    }

    @Test
    fun `amap gcj coordinates are kept`() {
        val gcj = LocationFix.fromGcj(39.9087, 116.3975, 24f, now, LocationFix.Source.AMAP)
        assertEquals(39.9087, gcj.lat, 0.0)
        assertEquals(LocationFix.Source.AMAP, gcj.source)
    }
}
