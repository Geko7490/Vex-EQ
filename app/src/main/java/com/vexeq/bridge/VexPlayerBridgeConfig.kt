package com.vexeq.bridge

import com.vexeq.dsp.DspSettings
import com.vexeq.dsp.DspPerformanceMode
import com.vexeq.dsp.ParametricBand
import kotlin.math.roundToInt

/**
 * Versioned, transport-neutral contract for the optional VEXPlayer advanced mode.
 *
 * VEXPlayer owns the actual Media3 PCM processor. This DTO only transfers controls;
 * VEX EQ must never attach a second processor to VEXPlayer's playback path.
 */
data class VexPlayerBridgeConfig(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val enabled: Boolean,
    val graphicBandLevelsMb: List<Int>,
    val bassStrength: Int,
    val stereoWidth: Int,
    val preampMb: Int,
    val autoHeadroom: Boolean,
    val limiterEnabled: Boolean,
    val parametricBands: List<BridgeParametricBand>
) {
    fun isValid(): Boolean =
        protocolVersion == CURRENT_PROTOCOL_VERSION &&
            graphicBandLevelsMb.size == 31 &&
            graphicBandLevelsMb.all { it in -1_200..1_200 } &&
            bassStrength in 0..1_000 &&
            stereoWidth in 0..1_000 &&
            preampMb in -1_200..600 &&
            parametricBands.size <= 8 &&
            parametricBands.all {
                it.frequencyHz in 20..22_000 && it.gainMb in -1_200..1_200 &&
                    it.q.isFinite() && it.q in 0.3f..10f
            }

    fun toDspSettings(performanceMode: DspPerformanceMode = DspPerformanceMode.HIGH): DspSettings? {
        if (!isValid()) return null
        return DspSettings(
            enabled = enabled,
            graphicGainsDb = graphicBandLevelsMb.map { it / 100f },
            parametricBands = parametricBands.map {
                com.vexeq.dsp.ParametricBand(it.frequencyHz.toFloat(), it.gainMb / 100f, it.q)
            },
            bassBoost = bassStrength / 1_000f,
            stereoWidth = stereoWidth / 1_000f,
            preampDb = preampMb / 100f,
            autoHeadroom = autoHeadroom,
            limiterEnabled = limiterEnabled,
            performanceMode = performanceMode
        )
    }

    companion object {
        const val CURRENT_PROTOCOL_VERSION = 1

        fun fromDspSettings(settings: DspSettings): VexPlayerBridgeConfig {
            // VEXPlayer's host DSP supports all eight PEQ bands regardless of VEX EQ's
            // standalone Low / Mid / High workload profile.
            val safe = settings.copy(performanceMode = DspPerformanceMode.HIGH).sanitized()
            return VexPlayerBridgeConfig(
                enabled = safe.enabled,
                graphicBandLevelsMb = safe.graphicGainsDb.map { (it * 100f).roundToInt() },
                bassStrength = (safe.bassBoost * 1_000f).roundToInt(),
                stereoWidth = (safe.stereoWidth * 1_000f).roundToInt(),
                preampMb = (safe.preampDb * 100f).roundToInt(),
                autoHeadroom = safe.autoHeadroom,
                limiterEnabled = safe.limiterEnabled,
                parametricBands = safe.parametricBands.map {
                    BridgeParametricBand(
                        frequencyHz = it.frequencyHz.roundToInt().coerceIn(20, 22_000),
                        gainMb = (it.gainDb * 100f).roundToInt(),
                        q = it.q
                    )
                }
            )
        }
    }
}

data class BridgeParametricBand(
    val frequencyHz: Int,
    val gainMb: Int,
    val q: Float
)
