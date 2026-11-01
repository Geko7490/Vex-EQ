package com.vexeq.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vexeq.bridge.BridgeSpectrumFrame

private val RtaPurple = Color(0xFFA855F7)
private val RtaPeak = Color(0xFFE8D7FF)
private val RtaGrid = Color(0xFF302C38)

/** Read-only RTA renderer for the real analysis frames received from VEXPlayer. */
@Composable
fun RtaSpectrumView(
    frame: BridgeSpectrumFrame,
    peakHoldEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.fillMaxWidth().height(220.dp)) {
        val values = frame.magnitudes
        if (values.isEmpty()) return@Canvas

        for (line in 0..4) {
            val y = size.height * line / 4f
            drawLine(RtaGrid, Offset(0f, y), Offset(size.width, y), 1f)
        }

        val count = values.size
        val slot = size.width / count
        val barWidth = (slot * 0.68f).coerceAtLeast(1f)
        values.forEachIndexed { index, raw ->
            val magnitude = raw.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            val barHeight = magnitude * size.height
            val left = index * slot + (slot - barWidth) * 0.5f
            drawRect(
                color = RtaPurple,
                topLeft = Offset(left, size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
            )

            if (peakHoldEnabled && index < frame.peaks.size) {
                val peak = frame.peaks[index].takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
                val peakY = size.height * (1f - peak)
                drawLine(
                    color = RtaPeak,
                    start = Offset(left, peakY),
                    end = Offset(left + barWidth, peakY),
                    strokeWidth = 2f
                )
            }
        }
    }
}
