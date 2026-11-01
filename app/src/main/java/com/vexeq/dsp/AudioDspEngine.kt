package com.vexeq.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class DspPerformanceMode(val bandStride: Int, val maxParametricBands: Int) {
    LOW(4, 2), MID(2, 4), HIGH(1, 8)
}

data class ParametricBand(
    val frequencyHz: Float = 1_000f,
    val gainDb: Float = 0f,
    val q: Float = 1f
)

data class DspSettings(
    val enabled: Boolean = false,
    val graphicGainsDb: List<Float> = List(AudioDspEngine.BAND_FREQUENCIES.size) { 0f },
    val parametricBands: List<ParametricBand> = emptyList(),
    val bassBoost: Float = 0f,
    val stereoWidth: Float = 0f,
    val preampDb: Float = 0f,
    val autoHeadroom: Boolean = true,
    val limiterEnabled: Boolean = true,
    val performanceMode: DspPerformanceMode = DspPerformanceMode.HIGH
) {
    fun sanitized(): DspSettings = copy(
        graphicGainsDb = AudioDspEngine.resampleGains(graphicGainsDb, AudioDspEngine.BAND_FREQUENCIES.size)
            .map { it.coerceIn(-12f, 12f) },
        parametricBands = parametricBands.take(performanceMode.maxParametricBands).map {
            it.copy(
                frequencyHz = it.frequencyHz.coerceIn(20f, 20_000f),
                gainDb = it.gainDb.coerceIn(-12f, 12f),
                q = it.q.coerceIn(0.3f, 10f)
            )
        },
        bassBoost = bassBoost.coerceIn(0f, 1f),
        stereoWidth = stereoWidth.coerceIn(0f, 1f),
        preampDb = preampDb.coerceIn(-12f, 6f)
    )
}

/**
 * Float-PCM DSP engine. Feed interleaved normalized PCM (-1..1) from a playback host.
 * One audio thread owns process()/reset(); configure()/update() build a fresh processing
 * state and publish it atomically, so the audio thread never waits on a settings lock.
 * No system-wide or microphone-loopback capture is performed by this class.
 */
class AudioDspEngine(
    sampleRate: Int,
    channelCount: Int,
    settings: DspSettings = DspSettings()
) {
    @Volatile
    private var activeState = ProcessingState(sampleRate, channelCount, settings.sanitized())

    /** Reconfigure after a host output-format change. Safe to call from a control thread. */
    fun configure(sampleRate: Int, channelCount: Int) {
        val previous = activeState
        activeState = ProcessingState(sampleRate, channelCount, previous.settings)
    }

    /** Build new filter state off the audio thread, then atomically publish it. */
    fun update(settings: DspSettings) {
        val previous = activeState
        activeState = ProcessingState(previous.sampleRate, previous.channelCount, settings.sanitized())
    }

    /** Process interleaved frames. input and output may reference the same array. */
    fun process(input: FloatArray, output: FloatArray, frameCount: Int): Boolean =
        activeState.process(input, output, frameCount)

    /** Call on the same thread that owns process(). */
    fun reset() = activeState.reset()

    private class ProcessingState(
        val sampleRate: Int,
        val channelCount: Int,
        val settings: DspSettings
    ) {
        private val rate = sampleRate.coerceAtLeast(8_000)
        private val channels = channelCount.coerceIn(1, 8)
        private val graphicFilters: Array<Biquad>
        private val parametricFilters: Array<Biquad>
        private val bassFilter: Biquad
        private val bassEnvelope = FloatArray(channels)
        private val sampleScratch = FloatArray(channels)
        private var limiterGain = 1f
        private val limiterRelease = (1f - exp(-1f / (rate * 0.08f))).coerceIn(0.000001f, 1f)
        private val outputGain: Float

        init {
            val selectedBandIndices = AudioDspEngine.BAND_FREQUENCIES.indices.filter { index ->
                index % settings.performanceMode.bandStride == 0 ||
                    index == AudioDspEngine.BAND_FREQUENCIES.lastIndex
            }
            val selectedGains = AudioDspEngine.resampleGains(
                settings.graphicGainsDb, selectedBandIndices.size
            )
            graphicFilters = selectedBandIndices.mapIndexedNotNull { slot, bandIndex ->
                val gain = selectedGains[slot]
                if (abs(gain) < 0.0001f) null
                else Biquad(
                    peaking(
                        AudioDspEngine.BAND_FREQUENCIES[bandIndex].toFloat(),
                        gain,
                        AudioDspEngine.GRAPHIC_Q
                    ),
                    channels
                )
            }.toTypedArray()
            parametricFilters = settings.parametricBands.mapNotNull { band ->
                if (abs(band.gainDb) < 0.0001f) null
                else Biquad(
                    peaking(band.frequencyHz.coerceAtMost(rate * 0.46f), band.gainDb, band.q),
                    channels
                )
            }.toTypedArray()
            bassFilter = Biquad(lowPass(min(120f, rate * 0.1f)), channels)

            val eqPeak = estimatePeakResponseDb()
            val bassAllowance = 5f * settings.bassBoost
            val widthAllowance = if (channels >= 2) 20f * log10(1f + settings.stereoWidth * 0.6f) else 0f
            val requestedPeak = eqPeak + bassAllowance + widthAllowance + settings.preampDb
            val reduction = if (settings.autoHeadroom && requestedPeak > 0f) requestedPeak + 1f else 0f
            outputGain = dbToLinear(settings.preampDb - reduction)
        }

        fun process(input: FloatArray, output: FloatArray, frameCount: Int): Boolean {
            if (frameCount < 0 || input.size < frameCount * channels ||
                output.size < frameCount * channels
            ) return false

            val active = settings.enabled
            val width = if (active && channels >= 2) 1f + settings.stereoWidth * 0.6f else 1f
            val bassAmount = if (active) settings.bassBoost else 0f
            val attack = exp(-1f / (rate * 0.008f))
            val bassRelease = exp(-1f / (rate * 0.100f))
            var frame = 0

            while (frame < frameCount) {
                var ch = 0
                while (ch < channels) {
                    var sample = input[frame * channels + ch]
                    if (!sample.isFinite()) sample = 0f

                    if (active) {
                        var i = 0
                        while (i < graphicFilters.size) {
                            sample = graphicFilters[i].process(sample, ch)
                            i++
                        }
                        i = 0
                        while (i < parametricFilters.size) {
                            sample = parametricFilters[i].process(sample, ch)
                            i++
                        }
                        if (bassAmount > 0f) {
                            val low = bassFilter.process(sample, ch)
                            val boostGain = AudioDspEngine.dbToLinear(5f * bassAmount)
                            val magnitude = abs(low * boostGain)
                            val coefficient = if (magnitude > bassEnvelope[ch]) attack else bassRelease
                            bassEnvelope[ch] = coefficient * bassEnvelope[ch] + (1f - coefficient) * magnitude
                            val envelope = bassEnvelope[ch]
                            val compressed = if (envelope > AudioDspEngine.BASS_THRESHOLD) {
                                AudioDspEngine.BASS_THRESHOLD + (envelope - AudioDspEngine.BASS_THRESHOLD) / AudioDspEngine.BASS_RATIO
                            } else envelope
                            val reduction = if (envelope > 1e-7f) compressed / envelope else 1f
                            sample += low * (boostGain - 1f) * reduction
                        }
                        sample *= outputGain
                    }
                    sampleScratch[ch] = if (sample.isFinite()) sample else 0f
                    ch++
                }

                if (active && channels >= 2 && width != 1f) {
                    val mid = (sampleScratch[0] + sampleScratch[1]) * 0.5f
                    val side = (sampleScratch[0] - sampleScratch[1]) * 0.5f * width
                    sampleScratch[0] = mid + side
                    sampleScratch[1] = mid - side
                }

                var peak = 0f
                ch = 0
                while (ch < channels) {
                    peak = max(peak, abs(sampleScratch[ch]))
                    ch++
                }

                if (active && settings.limiterEnabled) {
                    val target = if (peak > AudioDspEngine.LIMITER_CEILING) AudioDspEngine.LIMITER_CEILING / peak else 1f
                    limiterGain = if (target < limiterGain) target
                        else limiterGain + (1f - limiterGain) * limiterRelease
                } else {
                    limiterGain = 1f
                }

                ch = 0
                while (ch < channels) {
                    output[frame * channels + ch] =
                        (sampleScratch[ch] * limiterGain).coerceIn(-AudioDspEngine.LIMITER_CEILING, AudioDspEngine.LIMITER_CEILING)
                    ch++
                }
                frame++
            }
            return true
        }

        fun reset() {
            graphicFilters.forEach(Biquad::reset)
            parametricFilters.forEach(Biquad::reset)
            bassFilter.reset()
            bassEnvelope.fill(0f)
            limiterGain = 1f
        }

        private fun estimatePeakResponseDb(): Float {
            if (graphicFilters.isEmpty() && parametricFilters.isEmpty()) return 0f
            var maximumDb = 0f
            val maxHz = min(20_000f, rate * 0.46f)
            var point = 0
            while (point <= 384) {
                val frequency = 20.0 * (maxHz / 20.0).toDouble().pow(point / 384.0)
                val omega = 2.0 * PI * frequency / rate
                val c1 = cos(omega); val s1 = sin(omega)
                val c2 = cos(2.0 * omega); val s2 = sin(2.0 * omega)
                var db = 0.0
                var i = 0
                while (i < graphicFilters.size) {
                    db += graphicFilters[i].magnitudeDb(c1, s1, c2, s2)
                    i++
                }
                i = 0
                while (i < parametricFilters.size) {
                    db += parametricFilters[i].magnitudeDb(c1, s1, c2, s2)
                    i++
                }
                maximumDb = max(maximumDb, db.toFloat())
                point++
            }
            return maximumDb
        }

        private fun peaking(frequency: Float, gainDb: Float, q: Float): Coefficients {
            if (frequency <= 0f || frequency >= rate * 0.48f || abs(gainDb) < 0.0001f) return AudioDspEngine.IDENTITY
            val a = 10.0.pow(gainDb / 40.0).toFloat()
            val w = (2.0 * PI * frequency / rate).toFloat()
            val cosine = cos(w)
            val alpha = sin(w) / (2f * q.coerceIn(0.3f, 10f))
            val a0 = 1f + alpha / a
            return Coefficients(
                (1f + alpha * a) / a0, (-2f * cosine) / a0, (1f - alpha * a) / a0,
                (-2f * cosine) / a0, (1f - alpha / a) / a0
            )
        }

        private fun lowPass(frequency: Float): Coefficients {
            val w = (2.0 * PI * frequency / rate).toFloat()
            val cosine = cos(w)
            val alpha = sin(w) / (2f * 0.70710677f)
            val a0 = 1f + alpha
            return Coefficients(
                ((1f - cosine) * 0.5f) / a0, (1f - cosine) / a0,
                ((1f - cosine) * 0.5f) / a0, (-2f * cosine) / a0, (1f - alpha) / a0
            )
        }
    }

    private data class Coefficients(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)

    private class Biquad(private val c: Coefficients, channelCount: Int) {
        private val x1 = FloatArray(channelCount)
        private val x2 = FloatArray(channelCount)
        private val y1 = FloatArray(channelCount)
        private val y2 = FloatArray(channelCount)

        fun process(input: Float, channel: Int): Float {
            val output = c.b0 * input + c.b1 * x1[channel] + c.b2 * x2[channel] -
                c.a1 * y1[channel] - c.a2 * y2[channel]
            x2[channel] = x1[channel]; x1[channel] = input
            y2[channel] = y1[channel]
            y1[channel] = if (output.isFinite()) output else 0f
            return y1[channel]
        }

        fun magnitudeDb(c1: Double, s1: Double, c2: Double, s2: Double): Double {
            val nr = c.b0 + c.b1 * c1 + c.b2 * c2
            val ni = -c.b1 * s1 - c.b2 * s2
            val dr = 1.0 + c.a1 * c1 + c.a2 * c2
            val di = -c.a1 * s1 - c.a2 * s2
            val numerator = sqrt(nr * nr + ni * ni)
            val denominator = sqrt(dr * dr + di * di).coerceAtLeast(1e-12)
            return 20.0 * log10((numerator / denominator).coerceAtLeast(1e-12))
        }

        fun reset() {
            x1.fill(0f); x2.fill(0f); y1.fill(0f); y2.fill(0f)
        }
    }

    companion object {
        val BAND_FREQUENCIES = listOf(
            20, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500,
            630, 800, 1_000, 1_250, 1_600, 2_000, 2_500, 3_150, 4_000, 5_000,
            6_300, 8_000, 10_000, 12_500, 16_000, 20_000
        )
        const val MAX_PARAMETRIC_BANDS = 8
        private const val GRAPHIC_Q = 4.318f
        private const val BASS_THRESHOLD = 0.48f
        private const val BASS_RATIO = 2.5f
        private const val LIMITER_CEILING = 0.98f
        private val IDENTITY = Coefficients(1f, 0f, 0f, 0f, 0f)

        fun resampleGains(values: List<Float>, count: Int): List<Float> {
            if (count <= 0) return emptyList()
            if (values.isEmpty()) return List(count) { 0f }
            if (values.size == 1) return List(count) { values[0] }
            if (count == 1) return listOf(values.first())
            return List(count) { index ->
                val position = index.toFloat() / (count - 1) * (values.size - 1)
                val lower = position.toInt().coerceIn(values.indices)
                val upper = (lower + 1).coerceAtMost(values.lastIndex)
                val fraction = position - lower
                values[lower] * (1f - fraction) + values[upper] * fraction
            }
        }

        private fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()
    }
}
