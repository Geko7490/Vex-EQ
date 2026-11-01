package com.vexeq.visualizer.nextgen

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import com.vexeq.bridge.BridgeSpectrumFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Mobile-friendly VEX renderer inspired by jeffshee/NextGenVisualizer (Apache-2.0).
 * It consumes only immutable VEXPlayer bridge frames; it never opens an audio capture
 * session and never creates or modifies a playback DSP chain.
 */
class NextGenSpectrumView(context: Context) : View(context) {
    private val purple = 0xFFA855F7.toInt()
    private val cyan = 0xFF38C8D8.toInt()
    private val pink = 0xFFFF5EC4.toInt()
    private val white = 0xFFF5F1FA.toInt()
    private val muted = 0xFF5C5368.toInt()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val thinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB86B.toInt()
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private var frame = BridgeSpectrumFrame()
    private var style = NextGenVisualizerStyle.FFT_BARS
    private var beatPulse = false
    private var rotate = false
    private var glitch = false
    private var shake = false
    private var peakHold = true
    private var gradient: Shader? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        contentDescription = "VEX EQ live audio visualizer"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(purple, cyan, pink), null, Shader.TileMode.CLAMP)
        fillPaint.shader = gradient
        linePaint.shader = gradient
    }

    fun submitFrame(
        frame: BridgeSpectrumFrame,
        style: NextGenVisualizerStyle,
        beatPulse: Boolean,
        rotate: Boolean,
        glitch: Boolean,
        shake: Boolean,
        peakHold: Boolean
    ) {
        this.frame = frame
        this.style = style
        this.beatPulse = beatPulse
        this.rotate = rotate
        this.glitch = glitch
        this.shake = shake
        this.peakHold = peakHold
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val values = frame.magnitudes
        if (values.isEmpty() || width <= 0 || height <= 0) return

        val cx = width / 2f
        val cy = height / 2f
        val beat = if (beatPulse) frame.beatPulse.coerceIn(0f, 1f) else 0f
        val scale = 1f + beat * 0.075f
        val shakeOffset = if (shake) (beat * 4.5f) else 0f

        canvas.save()
        canvas.translate(cx + shakeOffset, cy)
        canvas.scale(scale, scale)
        if (rotate && style in CIRCULAR_STYLES) {
            val degrees = ((frame.timestampNanos / 1_000_000L) % 6000L) / 6000f * 360f
            canvas.rotate(degrees)
        }

        when (style) {
            NextGenVisualizerStyle.FFT_BARS -> drawBars(canvas, values, frame.peaks)
            NextGenVisualizerStyle.FFT_LINE -> drawLineSpectrum(canvas, values)
            NextGenVisualizerStyle.FFT_WAVE -> drawWaveform(canvas, frame.waveform, white, 0f)
            NextGenVisualizerStyle.FFT_WAVE_RGB -> drawRgbWaveform(canvas, frame)
            NextGenVisualizerStyle.FFT_CIRCLE -> drawCircularBars(canvas, values, false)
            NextGenVisualizerStyle.FFT_CIRCLE_WAVE -> drawCircularWave(canvas, frame.waveform, white, 0f)
            NextGenVisualizerStyle.FFT_CIRCLE_WAVE_RGB -> drawRgbCircle(canvas, frame)
        }
        canvas.restore()

        if (glitch && beat > 0.35f) drawGlitchAccent(canvas, beat)
    }

    private fun drawBars(canvas: Canvas, values: FloatArray, peaks: FloatArray) {
        val count = values.size
        val usableWidth = width.toFloat()
        val gap = (usableWidth / count * 0.22f).coerceAtMost(5f)
        val barWidth = (usableWidth - gap * (count - 1)) / count
        val maxHeight = height * 0.43f
        val center = 0f
        for (i in values.indices) {
            val magnitude = values[i].coerceIn(0f, 1f)
            val x = -width / 2f + i * (barWidth + gap)
            val h = (magnitude * maxHeight).coerceAtLeast(1f)
            canvas.drawRoundRect(x, center - h, x + barWidth, center + h, barWidth / 2f, barWidth / 2f, fillPaint)
            if (peakHold && i < peaks.size) {
                val py = center - peaks[i].coerceIn(0f, 1f) * maxHeight
                canvas.drawLine(x, py, x + barWidth, py, peakPaint)
            }
        }
        canvas.drawLine(-width / 2f, 0f, width / 2f, 0f, thinPaint.apply { color = muted })
    }

    private fun drawLineSpectrum(canvas: Canvas, values: FloatArray) {
        if (values.size < 2) return
        path.reset()
        val left = -width / 2f
        val step = width.toFloat() / (values.size - 1)
        val amplitude = height * 0.43f
        path.moveTo(left, amplitude - values[0].coerceIn(0f, 1f) * amplitude * 2f)
        for (i in 1 until values.size) {
            val x = left + i * step
            val y = amplitude - values[i].coerceIn(0f, 1f) * amplitude * 2f
            val prevX = left + (i - 1) * step
            val prevY = amplitude - values[i - 1].coerceIn(0f, 1f) * amplitude * 2f
            val mid = (prevX + x) / 2f
            path.quadTo(mid, prevY, x, y)
        }
        canvas.drawPath(path, linePaint)
        if (peakHold && frame.peaks.isNotEmpty()) {
            for (i in frame.peaks.indices) {
                val x = left + i * width.toFloat() / (frame.peaks.size - 1).coerceAtLeast(1)
                val y = amplitude - frame.peaks[i].coerceIn(0f, 1f) * amplitude * 2f
                canvas.drawCircle(x, y, 2.2f, peakPaint)
            }
        }
    }

    private fun drawWaveform(canvas: Canvas, samples: FloatArray, color: Int, offset: Float) {
        if (samples.size < 2) return
        path.reset()
        val halfWidth = width / 2f
        val amplitude = height * 0.40f
        samples.forEachIndexed { index, sample ->
            val x = -halfWidth + index * width.toFloat() / (samples.size - 1)
            val y = -sample.coerceIn(-1f, 1f) * amplitude + offset
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        linePaint.color = color
        linePaint.shader = null
        canvas.drawPath(path, linePaint)
        linePaint.shader = gradient
    }

    private fun drawRgbWaveform(canvas: Canvas, frame: BridgeSpectrumFrame) {
        val samples = frame.waveform
        drawWaveform(canvas, samples, pink, -3.5f)
        drawWaveform(canvas, samples, cyan, 0f)
        drawWaveform(canvas, samples, purple, 3.5f)
    }

    private fun drawCircularBars(canvas: Canvas, values: FloatArray, rgb: Boolean) {
        val radius = min(width, height) * 0.22f
        val maxLength = min(width, height) * 0.19f
        val count = values.size
        for (i in values.indices) {
            val angle = (2.0 * PI * i / count - PI / 2.0).toFloat()
            val magnitude = values[i].coerceIn(0f, 1f)
            val start = radius
            val end = radius + magnitude * maxLength
            val x1 = cos(angle) * start
            val y1 = sin(angle) * start
            val x2 = cos(angle) * end
            val y2 = sin(angle) * end
            linePaint.color = if (rgb) colorForIndex(i, count) else purple
            linePaint.strokeWidth = (min(width, height) * 0.009f).coerceIn(2f, 6f)
            canvas.drawLine(x1, y1, x2, y2, linePaint)
        }
        linePaint.shader = gradient
        linePaint.strokeWidth = 1.5f
        linePaint.color = muted
        canvas.drawCircle(0f, 0f, radius - 2f, linePaint)
    }

    private fun drawCircularWave(canvas: Canvas, samples: FloatArray, color: Int, radiusOffset: Float) {
        if (samples.size < 3) return
        val base = min(width, height) * 0.27f + radiusOffset
        val amplitude = min(width, height) * 0.12f
        path.reset()
        samples.forEachIndexed { index, sample ->
            val angle = (2.0 * PI * index / samples.size - PI / 2.0)
            val radius = base + sample.coerceIn(-1f, 1f) * amplitude
            val x = cos(angle).toFloat() * radius
            val y = sin(angle).toFloat() * radius
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        linePaint.color = color
        linePaint.shader = null
        linePaint.strokeWidth = 2.8f
        canvas.drawPath(path, linePaint)
        linePaint.shader = gradient
    }

    private fun drawRgbCircle(canvas: Canvas, frame: BridgeSpectrumFrame) {
        val left = frame.leftMagnitudes
        val right = frame.rightMagnitudes
        if (left.size >= 2 && right.size >= 2) {
            drawCircularBars(canvas, left, true)
            drawCircularWave(canvas, frame.waveform, cyan, min(width, height) * 0.035f)
            drawCircularWave(canvas, right.map { -it }.toFloatArray(), pink, -min(width, height) * 0.035f)
        } else {
            drawCircularBars(canvas, frame.magnitudes, true)
            drawCircularWave(canvas, frame.waveform, cyan, 0f)
            drawCircularWave(canvas, frame.waveform.map { -it }.toFloatArray(), pink, 0f)
        }
    }

    private fun drawGlitchAccent(canvas: Canvas, intensity: Float) {
        val bandHeight = (height * 0.025f).coerceAtLeast(3f)
        val y = (frame.timestampNanos / 1_000_000L % (height.toLong().coerceAtLeast(1))).toFloat()
        thinPaint.color = cyan
        thinPaint.strokeWidth = bandHeight
        canvas.drawLine(0f, y, width * (0.18f + intensity * 0.55f), y, thinPaint)
        thinPaint.color = pink
        canvas.drawLine(width * 0.62f, (y + bandHeight * 2f) % height, width.toFloat(), (y + bandHeight * 2f) % height.toFloat(), thinPaint)
    }

    private fun colorForIndex(index: Int, count: Int): Int {
        val phase = index.toFloat() / count.coerceAtLeast(1)
        return when {
            phase < 0.34f -> purple
            phase < 0.67f -> cyan
            else -> pink
        }
    }

    companion object {
        private val CIRCULAR_STYLES = setOf(
            NextGenVisualizerStyle.FFT_CIRCLE,
            NextGenVisualizerStyle.FFT_CIRCLE_WAVE,
            NextGenVisualizerStyle.FFT_CIRCLE_WAVE_RGB
        )
    }
}
