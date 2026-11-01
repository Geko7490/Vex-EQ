package com.vexeq.visualizer.nextgen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.vexeq.bridge.BridgeSpectrumFrame
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction

/**
 * VEX adapter of NextGenVisualizer's FftWave painter.
 * Original project: https://github.com/jeffshee/NextGenVisualizer (Apache-2.0).
 * Modified to consume VEXPlayer bridge frames instead of VisualizerHelper.
 */
class FftWave(
    override var paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    },
    var num: Int = 96,
    var interpolator: String = "sp",
    var side: String = "a",
    var mirror: Boolean = false,
    var power: Boolean = false,
    var ampR: Float = 72f
) : Painter() {
    private val path = Path()
    private var points = Array(0) { GravityModel() }
    private var skipFrame = true
    private var spline: PolynomialSplineFunction? = null

    override fun calc(frame: BridgeSpectrumFrame) {
        if (frame.magnitudes.size < 2) {
            skipFrame = true
            return
        }
        var fft = frame.magnitudes.map { it.coerceIn(0f, 1f).toDouble() * ampR }.toDoubleArray()
        if (isQuiet(fft)) {
            skipFrame = true
            return
        }
        skipFrame = false
        if (power) fft = getPowerFft(fft)
        if (mirror) fft = getMirrorFft(fft)
        if (points.size != fft.size) points = Array(fft.size) { GravityModel() }
        points.forEachIndexed { index, model -> model.update(fft[index].toFloat()) }
        spline = interpolateFft(points, num, interpolator)
    }

    override fun draw(canvas: Canvas, frame: BridgeSpectrumFrame) {
        val curve = spline ?: return
        if (skipFrame) return
        val width = canvas.width.toFloat()
        val sliceWidth = width / num
        path.moveTo(0f, -curve.value(0.0).toFloat())
        for (i in 1..num) path.lineTo(sliceWidth * i, -curve.value(i.toDouble()).toFloat())
        drawHelper(canvas, side, 0f, .5f) { canvas.drawPath(path, paint) }
        path.reset()
    }
}
