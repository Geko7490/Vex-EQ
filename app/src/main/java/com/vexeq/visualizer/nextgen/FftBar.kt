package com.vexeq.visualizer.nextgen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.vexeq.bridge.BridgeSpectrumFrame
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction

/**
 * VEX adapter of NextGenVisualizer's FftBar painter.
 * Original project: https://github.com/jeffshee/NextGenVisualizer (Apache-2.0).
 * Modified to consume VEXPlayer bridge frames instead of VisualizerHelper.
 */
class FftBar(
    override var paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        strokeWidth = 2f
    },
    var num: Int = 64,
    var interpolator: String = "li",
    var side: String = "ab",
    var mirror: Boolean = false,
    var power: Boolean = false,
    var gapX: Float = 2f,
    var ampR: Float = 100f
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
        val barWidth = (canvas.width - (num + 1) * gapX) / num
        drawHelper(canvas, side, 0f, .5f, {
            for (i in 0 until num) {
                val value = curve.value(i.toDouble()).toFloat()
                path.moveTo(barWidth * i + gapX * (i + 1), -value)
                path.lineTo(barWidth * (i + 1) + gapX * (i + 1), -value)
                path.lineTo(barWidth * (i + 1) + gapX * (i + 1), 0f)
                path.lineTo(barWidth * i + gapX * (i + 1), 0f)
                path.close()
            }
            canvas.drawPath(path, paint)
        }, {
            for (i in 0 until num) {
                val value = curve.value(i.toDouble()).toFloat()
                path.moveTo(barWidth * i + gapX * (i + 1), -value)
                path.lineTo(barWidth * (i + 1) + gapX * (i + 1), -value)
                path.lineTo(barWidth * (i + 1) + gapX * (i + 1), value)
                path.lineTo(barWidth * i + gapX * (i + 1), value)
                path.close()
            }
            canvas.drawPath(path, paint)
        })
        path.reset()
    }
}
