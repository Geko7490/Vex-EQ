package com.vexeq.ui

import com.vexeq.dsp.ParametricBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class EqCurveMathTest {
    @Test
    fun flatSettingsProduceFlatResponse() {
        val response = EqCurveMath.buildCurve(List(31) { 0f }, emptyList())
        assertEquals(160, response.size)
        response.forEach { assertEquals(0f, it, 0.001f) }
    }

    @Test
    fun positiveParametricGainRaisesResponseNearItsCenter() {
        val flat = EqCurveMath.buildCurve(List(31) { 0f }, emptyList())
        val boosted = EqCurveMath.buildCurve(
            List(31) { 0f },
            listOf(ParametricBand(frequencyHz = 1_000f, gainDb = 6f, q = 1f))
        )
        val centerIndex = (ln(1_000.0 / 20.0) / ln(20_000.0 / 20.0) * 159).toInt()
        assertTrue(boosted[centerIndex] > flat[centerIndex] + 4f)
    }
    @Test
    fun graphFrequencyMappingUsesLogarithmicRange() {
        assertEquals(20f, frequencyAt(0f, 1000f), 0.01f)
        assertEquals(20_000f, frequencyAt(1000f, 1000f), 1f)
        assertEquals(632.46f, frequencyAt(500f, 1000f), 1f)
    }

    @Test
    fun graphGainMappingClampsToSupportedPeqRange() {
        assertEquals(12f, gainAt(0f, 100f), 0.001f)
        assertEquals(0f, gainAt(50f, 100f), 0.001f)
        assertEquals(-12f, gainAt(100f, 100f), 0.001f)
    }

}
