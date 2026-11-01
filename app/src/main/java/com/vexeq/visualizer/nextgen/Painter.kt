package com.vexeq.visualizer.nextgen

import android.graphics.Canvas
import android.graphics.Paint
import com.vexeq.bridge.BridgeSpectrumFrame
import org.apache.commons.math3.analysis.interpolation.AkimaSplineInterpolator
import org.apache.commons.math3.analysis.interpolation.LinearInterpolator
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction
import kotlin.math.cos
import kotlin.math.sin

/**
 * Adapted from jeffshee/NextGenVisualizer (Apache-2.0).
 * VEX EQ changes: painter input is an immutable frame supplied by VEXPlayer's bridge,
 * rather than opening an Android Visualizer session inside the renderer.
 */
abstract class Painter {
    private val linear = LinearInterpolator()
    private val spline = AkimaSplineInterpolator()

    abstract var paint: Paint
    abstract fun calc(frame: BridgeSpectrumFrame)
    abstract fun draw(canvas: Canvas, frame: BridgeSpectrumFrame)

    fun interpolateFft(models: Array<GravityModel>, sliceNum: Int, interpolator: String): PolynomialSplineFunction {
        val count = models.size.coerceAtLeast(2)
        val x = DoubleArray(count) { it.toDouble() * sliceNum / (count - 1) }
        val y = DoubleArray(count) { models[it].height.toDouble() }
        return when (interpolator.lowercase()) {
            "sp", "spline" -> runCatching { spline.interpolate(x, y) }.getOrElse { linear.interpolate(x, y) }
            else -> linear.interpolate(x, y)
        }
    }

    fun isQuiet(fft: DoubleArray): Boolean = fft.all { it <= 5.0 }

    fun getMirrorFft(fft: DoubleArray, mode: Int = 1): DoubleArray = when (mode) {
        1 -> fft.reversedArray() + fft
        2 -> fft + fft.reversedArray()
        3 -> fft.sliceArray(0..fft.lastIndex / 2).reversedArray() + fft.sliceArray(0..fft.lastIndex / 2)
        4 -> fft.sliceArray(0..fft.lastIndex / 2) + fft.sliceArray(0..fft.lastIndex / 2).reversedArray()
        else -> fft
    }

    fun getPowerFft(fft: DoubleArray, param: Double = 100.0): DoubleArray =
        fft.map { it * it / param }.toDoubleArray()

    fun drawHelper(canvas: Canvas, side: String, xR: Float, yR: Float, draw: () -> Unit) {
        canvas.save()
        when (side) {
            "b" -> canvas.scale(1f, -1f, canvas.width / 2f, canvas.height / 2f)
            "ab" -> Unit
        }
        canvas.translate(canvas.width * xR, canvas.height * yR)
        draw()
        if (side == "ab") {
            canvas.scale(1f, -1f)
            draw()
        }
        canvas.restore()
    }

    fun drawHelper(
        canvas: Canvas,
        side: String,
        xR: Float,
        yR: Float,
        drawA: () -> Unit,
        drawBoth: () -> Unit
    ) {
        canvas.save()
        canvas.translate(canvas.width * xR, canvas.height * yR)
        when (side) {
            "a" -> drawA()
            "b" -> {
                canvas.scale(1f, -1f, canvas.width / 2f, canvas.height / 2f)
                drawA()
            }
            "ab" -> drawBoth()
        }
        canvas.restore()
    }

    class GravityModel(
        var height: Float = 0f,
        var dy: Float = 0f,
        var ay: Float = 2f
    ) {
        fun update(target: Float) {
            if (target > height) {
                height = target
                dy = 0f
            }
            height -= dy
            dy += ay
            if (height < 0f) {
                height = 0f
                dy = 0f
            }
        }
    }
}
