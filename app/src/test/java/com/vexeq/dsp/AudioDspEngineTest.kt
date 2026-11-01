package com.vexeq.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioDspEngineTest {
    @Test fun flatDisabledEnginePassesPcmUnchanged() {
        val input = floatArrayOf(0.1f, -0.2f, 0.3f, -0.4f)
        val output = FloatArray(input.size)
        val engine = AudioDspEngine(48_000, 2, DspSettings(enabled = false))
        assertTrue(engine.process(input, output, 2))
        input.indices.forEach { assertEquals(input[it], output[it], 0.000001f) }
    }

    @Test fun enabledFlatEngineDoesNotAlterSignal() {
        val input = FloatArray(2_000) { if (it % 2 == 0) 0.25f else -0.25f }
        val output = FloatArray(input.size)
        val engine = AudioDspEngine(48_000, 2, DspSettings(enabled = true))
        assertTrue(engine.process(input, output, 1_000))
        input.indices.forEach { assertEquals(input[it], output[it], 0.00001f) }
    }

    @Test fun limiterKeepsBoostedOutputBelowCeiling() {
        val gains = List(31) { 12f }
        val input = FloatArray(4_096) { if (it % 2 == 0) 0.95f else -0.95f }
        val output = FloatArray(input.size)
        val engine = AudioDspEngine(
            48_000, 2,
            DspSettings(enabled = true, graphicGainsDb = gains, autoHeadroom = false, limiterEnabled = true)
        )
        assertTrue(engine.process(input, output, 2_048))
        assertTrue(output.all { it.isFinite() && abs(it) <= 0.980001f })
    }

    @Test fun invalidBufferIsRejected() {
        val engine = AudioDspEngine(48_000, 2)
        assertFalse(engine.process(floatArrayOf(0f), FloatArray(2), 1))
    }

    @Test fun settingsAreClampedAndParametricBandsLimited() {
        val settings = DspSettings(
            graphicGainsDb = listOf(99f),
            parametricBands = List(12) { ParametricBand(99_000f, 30f, 99f) },
            bassBoost = 4f,
            stereoWidth = -2f,
            preampDb = 20f
        ).sanitized()
        assertEquals(31, settings.graphicGainsDb.size)
        assertEquals(12f, settings.graphicGainsDb.first(), 0f)
        assertEquals(12, settings.parametricBands.size)
        assertEquals(20_000f, settings.parametricBands.first().frequencyHz, 0f)
        assertEquals(12f, settings.parametricBands.first().gainDb, 0f)
        assertEquals(10f, settings.parametricBands.first().q, 0f)
        assertEquals(1f, settings.bassBoost, 0f)
        assertEquals(0f, settings.stereoWidth, 0f)
        assertEquals(6f, settings.preampDb, 0f)
    }

    @Test fun engineSupportsInPlaceProcessing() {
        val pcm = floatArrayOf(0.2f, -0.2f, 0.3f, -0.3f)
        val engine = AudioDspEngine(44_100, 2, DspSettings(enabled = true, preampDb = -3f))
        assertTrue(engine.process(pcm, pcm, 2))
        assertTrue(pcm.all { it.isFinite() })
    }

    @Test fun allPerformanceModesRetainTheFullSixteenBandPeqCapacity() {
        val bands = List(16) { ParametricBand(frequencyHz = 100f + it * 100f, gainDb = 2f) }
        DspPerformanceMode.values().forEach { mode ->
            assertEquals(16, DspSettings(parametricBands = bands, performanceMode = mode)
                .sanitized().parametricBands.size)
        }
    }

    @Test fun disabledEngineDoesNotApplyLimiterOrClipInput() {
        val input = floatArrayOf(1.2f, -1.15f, 0.99f, -0.99f)
        val output = FloatArray(input.size)
        val engine = AudioDspEngine(48_000, 2, DspSettings(enabled = false, limiterEnabled = true))
        assertTrue(engine.process(input, output, 2))
        input.indices.forEach { assertEquals(input[it], output[it], 0.000001f) }
    }
}
