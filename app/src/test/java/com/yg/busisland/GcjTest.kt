package com.yg.busisland

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GcjTest {

    @Test
    fun `converts Urumqi WGS point to GCJ`() {
        val p = Gcj.toGcj(43.858646, 87.500296)
        assertEquals(43.860005, p[0], 0.0002)
        assertEquals(87.503384, p[1], 0.0002)
    }

    @Test
    fun `leaves coordinates outside China unchanged`() {
        val p = Gcj.toGcj(48.8566, 2.3522)
        assertArrayEquals(doubleArrayOf(48.8566, 2.3522), p, 0.0)
    }
}
