package com.vexeq.ui

import com.vexeq.dsp.ParametricBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
