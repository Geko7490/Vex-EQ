package com.vexeq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.vexeq.dsp.AudioDspEngine
import com.vexeq.dsp.ParametricBand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private val GraphPurple = Color(0xFFA855F7)
private val GraphMuted = Color(0xFF77717F)
private val GraphGrid = Color(0xFF302C38)

/**
 * Displays and edits the estimated combined response of the current graphic and parametric EQ.
 * Gestures update PEQ settings only; this view never processes or routes audio.
 */
@Composable
fun EqResponseCurve(
    graphicGainsDb: List<Float>,
    parametricBands: List<ParametricBand>,
    selectedBandIndex: Int = -1,
    onSelectBand: (Int) -> Unit = {},
    onBandChange: (Int, ParametricBand) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val points = EqCurveMath.buildCurve(graphicGainsDb, parametricBands)
    val latestBands by androidx.compose.runtime.rememberUpdatedState(parametricBands)
    val latestSelectedBand by androidx.compose.runtime.rememberUpdatedState(selectedBandIndex)
    val latestOnSelectBand by androidx.compose.runtime.rememberUpdatedState(onSelectBand)
    val latestOnBandChange by androidx.compose.runtime.rememberUpdatedState(onBandChange)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF141319),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("EQ RESPONSE", style = MaterialTheme.typography.labelMedium, color = Color.White)
                Text("ESTIMATED · 20 Hz – 20 kHz", style = MaterialTheme.typography.labelSmall, color = GraphMuted)
            }
            Canvas(
                modifier = Modifier.fillMaxWidth().height(164.dp)
                    .background(Color(0xFF101014))
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val index = nearestBand(latestBands, offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                            if (index >= 0) latestOnSelectBand(index)
                        }
                    }
                    .pointerInput(Unit) {
                        var dragIndex = -1
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragIndex = nearestBand(latestBands, offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                                if (dragIndex >= 0) latestOnSelectBand(dragIndex)
                            },
                            onDragEnd = { dragIndex = -1 },
                            onDragCancel = { dragIndex = -1 }
                        ) { change, _ ->
                            if (dragIndex in latestBands.indices) {
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                val height = size.height.toFloat().coerceAtLeast(1f)
                                val updated = latestBands[dragIndex].copy(
                                    frequencyHz = frequencyAt(change.position.x, width),
                                    gainDb = gainAt(change.position.y, height)
                                )
                                latestOnBandChange(dragIndex, updated)
                            }
                            change.consume()
                        }
                    }
            ) {
                val left = 1f
                val right = size.width - 1f
                val top = 4f
                val bottom = size.height - 4f
                val graphHeight = bottom - top
                val zeroY = top + graphHeight * 0.5f

                for (line in 0..4) {
                    val y = top + graphHeight * line / 4f
                    drawLine(
                        color = if (line == 2) GraphMuted.copy(alpha = 0.55f) else GraphGrid,
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = if (line == 2) 1.4f else 1f
                    )
                }
                for (line in 0..4) {
                    val x = left + (right - left) * line / 4f
                    drawLine(GraphGrid, Offset(x, top), Offset(x, bottom), 1f)
                }

                val path = Path()
                points.forEachIndexed { index, point ->
                    val x = left + (right - left) * index / (points.size - 1).toFloat()
                    val y = zeroY - (point.coerceIn(-18f, 18f) / 36f) * graphHeight
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, GraphPurple, style = Stroke(width = 2.6f))
                latestBands.forEachIndexed { index, band ->
                    val x = (ln(band.frequencyHz.coerceIn(20f, 20_000f).toDouble() / 20.0) /
                        ln(20_000.0 / 20.0) * (right - left)).toFloat() + left
                    val y = zeroY - (band.gainDb.coerceIn(-18f, 18f) / 36f) * graphHeight
                    drawCircle(
                        color = if (index == latestSelectedBand) Color.White else GraphPurple,
                        radius = if (index == selectedBandIndex) 6f else 4f,
                        center = Offset(x, y)
                    )
                    if (index == selectedBandIndex) {
                        drawCircle(color = GraphPurple, radius = 9f, center = Offset(x, y), style = Stroke(1.5f))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("20 Hz", "100 Hz", "1 kHz", "10 kHz", "20 kHz").forEach {
                    Text(it, color = GraphMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Lightweight biquad magnitude calculation used only to draw the response preview. */
internal object EqCurveMath {
    private const val SAMPLE_RATE = 48_000.0
    private const val GRAPHIC_Q = 4.318

    fun buildCurve(graphicGainsDb: List<Float>, parametricBands: List<ParametricBand>): List<Float> {
        val graphic = AudioDspEngine.resampleGains(graphicGainsDb, AudioDspEngine.BAND_FREQUENCIES.size)
        return List(160) { index ->
            val frequency = 20.0 * (20_000.0 / 20.0).pow(index / 159.0)
            var response = 0.0
            AudioDspEngine.BAND_FREQUENCIES.forEachIndexed { bandIndex, center ->
                response += bandResponseDb(frequency, center.toDouble(), graphic[bandIndex].toDouble(), GRAPHIC_Q)
            }
            parametricBands.forEach { band ->
                response += bandResponseDb(
                    frequency,
                    band.frequencyHz.coerceIn(20f, 20_000f).toDouble(),
                    band.gainDb.coerceIn(-12f, 12f).toDouble(),
                    band.q.coerceIn(0.3f, 10f).toDouble()
                )
            }
            response.toFloat().coerceIn(-36f, 36f)
        }
    }

    private fun bandResponseDb(frequency: Double, centerHz: Double, gainDb: Double, q: Double): Double {
        if (kotlin.math.abs(gainDb) < 0.0001 || centerHz >= SAMPLE_RATE * 0.48) return 0.0
        val a = 10.0.pow(gainDb / 40.0)
        val omega = 2.0 * PI * centerHz / SAMPLE_RATE
        val alpha = sin(omega) / (2.0 * q)
        val cosOmega = cos(omega)
        val a0 = 1.0 + alpha / a
        val b0 = (1.0 + alpha * a) / a0
        val b1 = (-2.0 * cosOmega) / a0
        val b2 = (1.0 - alpha * a) / a0
        val a1 = (-2.0 * cosOmega) / a0
        val a2 = (1.0 - alpha / a) / a0

        val w = 2.0 * PI * frequency / SAMPLE_RATE
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2.0 * w)
        val s2 = sin(2.0 * w)
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = -b1 * s1 - b2 * s2
        val dr = 1.0 + a1 * c1 + a2 * c2
        val di = -a1 * s1 - a2 * s2
        val numerator = sqrt(nr * nr + ni * ni)
        val denominator = sqrt(dr * dr + di * di).coerceAtLeast(1e-12)
        return 20.0 * log10((numerator / denominator).coerceAtLeast(1e-12))
    }
}

internal fun frequencyAt(x: Float, width: Float): Float {
    val fraction = (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return exp(ln(20f) + (ln(20_000f) - ln(20f)) * fraction).coerceIn(20f, 20_000f)
}

internal fun gainAt(y: Float, height: Float): Float {
    val fraction = (1f - y / height.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return (fraction * 36f - 18f).coerceIn(-12f, 12f)
}

private fun nearestBand(
    bands: List<ParametricBand>,
    x: Float,
    y: Float,
    width: Float,
    height: Float
): Int {
    if (bands.isEmpty()) return -1
    return bands.indices.minByOrNull { index ->
        val band = bands[index]
        val bandX = ln(band.frequencyHz.coerceIn(20f, 20_000f).toDouble() / 20.0) /
            ln(20_000.0 / 20.0) * width
        val bandY = (18f - band.gainDb.coerceIn(-18f, 18f)) / 36f * height
        val dx = (x - bandX).toDouble()
        val dy = (y - bandY).toDouble()
        dx * dx + dy * dy
    } ?: -1
}
