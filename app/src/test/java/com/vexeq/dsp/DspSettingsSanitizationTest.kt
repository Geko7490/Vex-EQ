package com.vexeq.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspSettingsSanitizationTest {
    @Test
    fun replacesNonFiniteGraphicAndGlobalControlsWithSafeDefaults() {
        val safe = DspSettings(
            graphicGainsDb = listOf(Float.NaN, Float.POSITIVE_INFINITY),
            bassBoost = Float.NaN,
            stereoWidth = Float.NEGATIVE_INFINITY,
            preampDb = Float.NaN
        ).sanitized()

        assertEquals(31, safe.graphicGainsDb.size)
        assertTrue(safe.graphicGainsDb.all { it == 0f && it.isFinite() })
        assertEquals(0f, safe.bassBoost, 0f)
        assertEquals(0f, safe.stereoWidth, 0f)
        assertEquals(0f, safe.preampDb, 0f)
    }

    @Test
    fun replacesNonFiniteParametricValuesWithSafeDefaults() {
        val safe = DspSettings(
            parametricBands = listOf(
                ParametricBand(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN)
            )
        ).sanitized()

        val band = safe.parametricBands.single()
        assertEquals(1_000f, band.frequencyHz, 0f)
        assertEquals(0f, band.gainDb, 0f)
        assertEquals(1f, band.q, 0f)
        assertTrue(band.frequencyHz.isFinite())
        assertTrue(band.gainDb.isFinite())
        assertTrue(band.q.isFinite())
    }
}
