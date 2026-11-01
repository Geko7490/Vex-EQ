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

/** One peaking-EQ band. Gain is expressed in decibels. */
data class ParametricBand(
    val frequencyHz: Float = 1_000f,
    val gainDb: Float = 0f,
    val q: Float = 1f
)

/** Immutable user-facing DSP configuration. */
data class DspSettings(
    val enabled: Boolean = false,
    val graphicGainsDb: List<Float> = List(BAND_FREQUENCIES.size) { 0f },
    val parametricBands: List<ParametricBand> = emptyList(),
    val bassBoost: Float = 0f,
    val stereoWidth: Float = 0f,
    val preampDb: Float = 0f,
    val autoHeadroom: Boolean = true,
    val limiterEnabled: Boolean = true
) {
    fun sanitized(): DspSettings = copy(
        graphicGainsDb = resampleGains(graphicGainsDb, BAND_FREQUENCIES.size)
            .map { it.coerceIn(-12f, 12f) },
        parametricBands = parametricBands.take(MAX_PARAMETRIC_BANDS).map {
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
 * Allocation-free-after-configuration PCM float DSP engine.
 *
 * Input/output are interleaved normalized Float PCM (-1..1). The engine does not
 * acquire audio from other applications; a host playback pipeline must feed PCM
 * frames into process(). Configure/update are control-thread operations.
 */
class AudioDspEngine(
    sampleRate: Int,
    channelCount: Int,
    settings: DspSettings = DspSettings()
) {
    private var rate = sampleRate.coerceAtLeast(8_000)
    private var channels = channelCount.coerceIn(1, 8)
    private var config = settings.sanitized()
    private var graphicFilters = emptyArray<Biquad>()
    private var parametricFilters = emptyArray<Biquad>()
    private var bassFilter: Biquad? = null
    private var bassEnvelope = FloatArray(channels)
    private var limiterGain = 1f
    private var limiterRelease = releaseCoefficient(rate)
    private var outputGain = 1f
    private var sampleScratch = FloatArray(channels)

    init { rebuild() }

    /** Reconfigure after an output format change. Call from the audio host's control thread. */
    @Synchronized
    fun configure(sampleRate: Int, channelCount: Int) {
        rate = sampleRate.coerceAtLeast(8_000)
        channels = channelCount.coerceIn(1, 8)
        bassEnvelope = FloatArray(channels)
        sampleScratch = FloatArray(channels)
        limiterRelease = releaseCoefficient(rate)
        rebuild()
    }

    /** Apply settings atomically between audio blocks. */
    @Synchronized
    fun update(settings: DspSettings) {
        config = settings.sanitized()
        rebuild()
    }

    /**
     * Process [frameCount] interleaved PCM frames. input and output may be the same array.
     * Returns false for invalid buffer dimensions; valid samples are always finite and bounded.
     */
    @Synchronized
    fun process(input: FloatArray, output: FloatArray, frameCount: Int): Boolean {
        if (frameCount < 0 || input.size < frameCount * channels ||
            output.size < frameCount * channels
        ) return false

        val active = config.enabled
        val width = if (active && channels >= 2) 1f + config.stereoWidth * 0.6f else 1f
        val bassAmount = if (active) config.bassBoost else 0f
        val attack = exp(-1f / (rate * 0.008f))
        val bassRelease = exp(-1f / (rate * 0.100f))
        val samples = sampleScratch

        var frame = 0
        while (frame < frameCount) {
            var peak = 0f
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
                        val lowComponent = bassFilter?.process(sample, ch) ?: 0f
                        val boostGain = dbToLinear(5f * bassAmount)
                        val boosted = lowComponent * boostGain
                        val magnitude = abs(boosted)
                        val coefficient = if (magnitude > bassEnvelope[ch]) attack else bassRelease
                        bassEnvelope[ch] = coefficient * bassEnvelope[ch] + (1f - coefficient) * magnitude
                        val envelope = bassEnvelope[ch]
                        val compressed = if (envelope > BASS_THRESHOLD) {
                            BASS_THRESHOLD + (envelope - BASS_THRESHOLD) / BASS_RATIO
                        } else envelope
                        val reduction = if (envelope > 1e-7f) compressed / envelope else 1f
                        sample += lowComponent * (boostGain - 1f) * reduction
                    }
                    sample *= outputGain
                }
                if (!sample.isFinite()) sample = 0f
                samples[ch] = sample
                ch++
            }

            if (active && channels >= 2 && width != 1f) {
                val mid = (samples[0] + samples[1]) * 0.5f
                val side = (samples[0] - samples[1]) * 0.5f * width
                samples[0] = mid + side
                samples[1] = mid - side
            }

            peak = 0f
            var peakChannel = 0
            while (peakChannel < channels) {
                peak = max(peak, abs(samples[peakChannel]))
                peakChannel++
            }

            if (active && config.limiterEnabled) {
                val target = if (peak > LIMITER_CEILING) LIMITER_CEILING / peak else 1f
                limiterGain = if (target < limiterGain) target
                    else limiterGain + (1f - limiterGain) * limiterRelease
            } else {
                limiterGain = 1f
            }

            ch = 0
            while (ch < channels) {
                output[frame * channels + ch] =
                    (samples[ch] * limiterGain).coerceIn(-1f, LIMITER_CEILING)
                ch++
            }
            frame++
        }
        return true
    }

    @Synchronized
    fun reset() {
        graphicFilters.forEach(Biquad::reset)
        parametricFilters.forEach(Biquad::reset)
        bassFilter?.reset()
        bassEnvelope.fill(0f)
        limiterGain = 1f
    }

    private fun rebuild() {
        graphicFilters = BAND_FREQUENCIES.mapIndexedNotNull { index, frequency ->
            val gain = config.graphicGainsDb[index]
            if (abs(gain) < 0.0001f) null else Biquad(peaking(frequency.toFloat(), gain, GRAPHIC_Q), channels)
        }.toTypedArray()
        parametricFilters = config.parametricBands.mapNotNull { band ->
            if (abs(band.gainDb) < 0.0001f) null
            else Biquad(peaking(band.frequencyHz.coerceAtMost(rate * 0.46f), band.gainDb, band.q), channels)
        }.toTypedArray()
        bassFilter = Biquad(lowPass(min(120f, rate * 0.1f)), channels)
        bassEnvelope = FloatArray(channels)
        limiterGain = 1f
        limiterRelease = releaseCoefficient(rate)

        val eqPeak = estimatePeakResponseDb()
        val bassAllowance = 5f * config.bassBoost
        val widthAllowance = if (channels >= 2) 20f * log10(1f + config.stereoWidth * 0.6f) else 0f
        val requestedPeak = eqPeak + bassAllowance + widthAllowance + config.preampDb
        val reduction = if (config.autoHeadroom && requestedPeak > 0f) requestedPeak + 1f else 0f
        outputGain = dbToLinear(config.preampDb - reduction)
    }

    /** Sample the combined filter response logarithmically to avoid stacking all band gains blindly. */
    private fun estimatePeakResponseDb(): Float {
        if (graphicFilters.isEmpty() && parametricFilters.isEmpty()) return 0f
        var maximumDb = 0f
        val maxHz = min(20_000f, rate * 0.46f)
        var point = 0
        while (point <= 384) {
            val frequency = 20.0 * (maxHz / 20.0).toDouble().pow(point / 384.0)
            val omega = 2.0 * PI * frequency / rate
            val c1 = cos(omega)
            val s1 = sin(omega)
            val c2 = cos(2.0 * omega)
            val s2 = sin(2.0 * omega)
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
        if (frequency <= 0f || frequency >= rate * 0.48f || abs(gainDb) < 0.0001f) return IDENTITY
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

    private data class Coefficients(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)

    private class Biquad(private val c: Coefficients, channelCount: Int) {
        private val x1 = FloatArray(channelCount)
        private val x2 = FloatArray(channelCount)
        private val y1 = FloatArray(channelCount)
        private val y2 = FloatArray(channelCount)

        fun process(input: Float, channel: Int): Float {
            val output = c.b0 * input + c.b1 * x1[channel] + c.b2 * x2[channel] -
                c.a1 * y1[channel] - c.a2 * y2[channel]
            x2[channel] = x1[channel]
            x1[channel] = input
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
        private fun releaseCoefficient(rate: Int): Float =
            (1f - exp(-1f / (rate * 0.08f))).coerceIn(0.000001f, 1f)

        private val IDENTITY = Coefficients(1f, 0f, 0f, 0f, 0f)
    }
}
