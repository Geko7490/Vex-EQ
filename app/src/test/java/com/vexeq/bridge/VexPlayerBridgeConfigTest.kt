package com.vexeq.bridge

import com.vexeq.dsp.DspPerformanceMode
import com.vexeq.dsp.DspSettings
import com.vexeq.dsp.ParametricBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VexPlayerBridgeConfigTest {
    @Test fun convertsVexEqUnitsToVexPlayerUnits() {
        val config = VexPlayerBridgeConfig.fromDspSettings(
            DspSettings(
                enabled = true,
                graphicGainsDb = List(31) { if (it == 0) 3.5f else 0f },
                parametricBands = listOf(ParametricBand(1_250f, -2.5f, 1.4f)),
                bassBoost = 0.65f,
                stereoWidth = 0.4f,
                preampDb = -3.25f,
                autoHeadroom = false,
                limiterEnabled = true,
                performanceMode = DspPerformanceMode.HIGH
            )
        )
        assertTrue(config.enabled)
        assertEquals(350, config.graphicBandLevelsMb.first())
        assertEquals(650, config.bassStrength)
        assertEquals(400, config.stereoWidth)
        assertEquals(-325, config.preampMb)
        assertEquals(1_250, config.parametricBands.single().frequencyHz)
        assertEquals(-250, config.parametricBands.single().gainMb)
        assertEquals(1.4f, config.parametricBands.single().q, 0f)
        assertTrue(config.isValid())
    }

    @Test fun rejectsUnsupportedProtocolAndInvalidPayloadRanges() {
        val valid = VexPlayerBridgeConfig.fromDspSettings(DspSettings())
        assertFalse(valid.copy(protocolVersion = 99).isValid())
        assertFalse(valid.copy(bassStrength = 1_001).isValid())
        assertFalse(valid.copy(graphicBandLevelsMb = listOf(0)).isValid())
        assertFalse(valid.copy(parametricBands = List(9) { BridgeParametricBand(1_000, 0, 1f) }).isValid())
        assertFalse(valid.copy(parametricBands = listOf(BridgeParametricBand(1_000, 0, Float.NaN))).isValid())
        assertFalse(valid.copy(parametricBands = listOf(BridgeParametricBand(1_000, 0, Float.POSITIVE_INFINITY))).isValid())
        assertFalse(valid.copy(parametricBands = listOf(BridgeParametricBand(19, 0, 1f))).isValid())
        assertFalse(valid.copy(parametricBands = listOf(BridgeParametricBand(1_000, 1_201, 1f))).isValid())
    }

    @Test fun roundTripsHostSnapshotWithoutDroppingPeqBands() {
        val original = DspSettings(
            enabled = true,
            graphicGainsDb = List(31) { index -> (index - 15) / 2f },
            parametricBands = List(8) { index ->
                ParametricBand((100 + index * 500).toFloat(), (index - 4) / 2f, 0.8f + index / 10f)
            },
            bassBoost = 0.72f,
            stereoWidth = 0.35f,
            preampDb = -2.5f,
            autoHeadroom = false,
            limiterEnabled = true,
            performanceMode = DspPerformanceMode.LOW
        )
        val payload = VexPlayerBridgeConfig.fromDspSettings(original)
        assertEquals(8, payload.parametricBands.size)

        val restored = payload.toDspSettings(DspPerformanceMode.LOW)!!
        assertEquals(DspPerformanceMode.LOW, restored.performanceMode)
        assertEquals(8, restored.parametricBands.size)
        assertEquals(original.graphicGainsDb, restored.graphicGainsDb)
        assertEquals(original.bassBoost, restored.bassBoost, 0.001f)
        assertEquals(original.stereoWidth, restored.stereoWidth, 0.001f)
        assertEquals(original.preampDb, restored.preampDb, 0.001f)
    }

    @Test fun sanitizesSettingsBeforeBuildingBridgePayload() {
        val config = VexPlayerBridgeConfig.fromDspSettings(
            DspSettings(
                graphicGainsDb = listOf(99f),
                bassBoost = 3f,
                preampDb = 20f,
                parametricBands = List(12) { ParametricBand(1_000f, 0f, 1f) }
            )
        )
        assertEquals(31, config.graphicBandLevelsMb.size)
        assertEquals(1_200, config.graphicBandLevelsMb.first())
        assertEquals(1_000, config.bassStrength)
        assertEquals(600, config.preampMb)
        assertEquals(8, config.parametricBands.size)
        assertTrue(config.isValid())
    }
}
