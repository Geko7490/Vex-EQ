package com.vexeq

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vexeq.bridge.VexPlayerBridgeClient
import com.vexeq.bridge.VexPlayerBridgeState
import com.vexeq.bridge.VexPlayerBridgeStatus
import com.vexeq.visualizer.nextgen.NextGenSpectrumView
import com.vexeq.visualizer.nextgen.NextGenVisualizerStyle

private val AnalyzerPanel = Color(0xFF1C1B23)
private val AnalyzerMuted = Color(0xFFB8B4C0)
private val AnalyzerPurple = Color(0xFFA855F7)

@Composable
fun SpectrumStudioScreen(
    bridgeClient: VexPlayerBridgeClient,
    bridgeState: VexPlayerBridgeState
) {
    val frame by bridgeClient.spectrumFrame.collectAsState()
    val spectrumError by bridgeClient.spectrumError.collectAsState()
    var selectedStyle by remember { mutableStateOf(NextGenVisualizerStyle.FFT_BARS) }
    var showRta by remember { mutableStateOf(true) }
    var showPeakHold by remember { mutableStateOf(true) }
    var showBeatPulse by remember { mutableStateOf(false) }
    var showRotate by remember { mutableStateOf(false) }
    var showGlitch by remember { mutableStateOf(false) }
    var showShake by remember { mutableStateOf(false) }

    LaunchedEffect(bridgeState.status) {
        if (bridgeState.status == VexPlayerBridgeStatus.READY ||
            bridgeState.status == VexPlayerBridgeStatus.CONNECTED
        ) {
            bridgeClient.subscribeSpectrum()
        } else {
            bridgeClient.unsubscribeSpectrum()
        }
    }
    DisposableEffect(bridgeClient) {
        onDispose { bridgeClient.unsubscribeSpectrum() }
    }

    Column(
        Modifier.fillMaxSize().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = AnalyzerPanel)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SPECTRUM STUDIO", color = AnalyzerPurple, style = MaterialTheme.typography.labelLarge)
                Text(
                    when (bridgeState.status) {
                        VexPlayerBridgeStatus.CONNECTED -> "Live VEXPlayer analysis · EQ control connected"
                        VexPlayerBridgeStatus.READY -> "Live VEXPlayer analysis · read-only"
                        VexPlayerBridgeStatus.CONNECTING -> "Connecting to VEXPlayer…"
                        VexPlayerBridgeStatus.ERROR -> "VEXPlayer connection unavailable"
                        VexPlayerBridgeStatus.DISCONNECTED -> "Connect VEXPlayer to receive live spectrum data"
                    },
                    color = AnalyzerMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                spectrumError?.let {
                    Text(it, color = Color(0xFFFFB86B), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("VISUALIZER STYLE", color = AnalyzerMuted, style = MaterialTheme.typography.labelSmall)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NextGenVisualizerStyle.values().forEach { style ->
                    FilterChip(
                        selected = selectedStyle == style,
                        onClick = { selectedStyle = style },
                        label = { Text(style.label) }
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = showBeatPulse, onClick = { showBeatPulse = !showBeatPulse }, label = { Text("Beat") })
                FilterChip(selected = showRotate, onClick = { showRotate = !showRotate }, label = { Text("Rotate") })
                FilterChip(selected = showGlitch, onClick = { showGlitch = !showGlitch }, label = { Text("Glitch") })
                FilterChip(selected = showShake, onClick = { showShake = !showShake }, label = { Text("Shake") })
                FilterChip(selected = showRta, onClick = { showRta = !showRta }, label = { Text("RTA") })
                FilterChip(selected = showPeakHold, onClick = { showPeakHold = !showPeakHold }, label = { Text("Peak Hold") })
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101014))) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                if (frame.timestampNanos > 0L) {
                    AndroidView(
                        factory = { context -> NextGenSpectrumView(context) },
                        modifier = Modifier.fillMaxWidth().height(280.dp),
                        update = {
                            it.submitFrame(
                                frame = frame,
                                style = selectedStyle,
                                beatPulse = showBeatPulse,
                                rotate = showRotate,
                                glitch = showGlitch,
                                shake = showShake,
                                peakHold = showPeakHold
                            )
                        }
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().height(280.dp).padding(16.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            when {
                                bridgeState.status !in setOf(VexPlayerBridgeStatus.READY, VexPlayerBridgeStatus.CONNECTED) ->
                                    "Waiting for VEXPlayer connection"
                                spectrumError != null -> spectrumError!!
                                else -> "Play a track in VEXPlayer to start the analyzer"
                            },
                            color = AnalyzerMuted
                        )
                    }
                }
            }
        }

        if (showRta && frame.magnitudes.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101014))) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("REAL-TIME ANALYZER", color = AnalyzerPurple, style = MaterialTheme.typography.labelLarge)
                    Canvas(Modifier.fillMaxWidth().height(116.dp)) {
                        val values = frame.magnitudes
                        val peaks = frame.peaks
                        val count = values.size.coerceAtLeast(1)
                        val baseline = size.height - 2f
                        for (line in 1..3) {
                            val y = size.height * line / 4f
                            drawLine(Color(0xFF302C38), Offset(0f, y), Offset(size.width, y), 1f)
                        }
                        val path = Path()
                        values.forEachIndexed { index, value ->
                            val x = size.width * index / (count - 1).coerceAtLeast(1)
                            val y = baseline - value.coerceIn(0f, 1f) * (size.height - 6f)
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            if (showPeakHold && index < peaks.size) {
                                val peakY = baseline - peaks[index].coerceIn(0f, 1f) * (size.height - 6f)
                                drawLine(Color(0xFFFFB86B), Offset(x, peakY), Offset(x, (peakY + 3f).coerceAtMost(baseline)), 1.5f)
                            }
                        }
                        drawPath(path, AnalyzerPurple, style = Stroke(width = 2f))
                    }
                    Text("Live logarithmic FFT energy · peak decay supplied by VEXPlayer",
                        color = AnalyzerMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        StereoPhaseCard(frame)

        Card(colors = CardDefaults.cardColors(containerColor = AnalyzerPanel)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("FREQUENCY ENERGY", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    EnergyValue("BASS", frame.bass)
                    EnergyValue("MID", frame.mid)
                    EnergyValue("TREBLE", frame.treble)
                    EnergyValue("BEAT", frame.beatPulse)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "FFT Bars and FFT Wave are adapted from jeffshee/NextGenVisualizer. Analysis frames are streamed from VEXPlayer; this renderer does not create a second audio processor.",
                    color = AnalyzerMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun EnergyValue(label: String, value: Float) {
    Column {
        Text(label, color = AnalyzerMuted, style = MaterialTheme.typography.labelSmall)
        Text("${(value.coerceIn(0f, 1f) * 100).toInt()}%", color = AnalyzerPurple,
            style = MaterialTheme.typography.titleMedium)
    }
}


@Composable
private fun StereoPhaseCard(frame: com.vexeq.bridge.BridgeSpectrumFrame) {
    val hasStereo = frame.leftMagnitudes.size == 64 && frame.rightMagnitudes.size == 64
    Card(colors = CardDefaults.cardColors(containerColor = AnalyzerPanel)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("STEREO & PHASE STUDIO", color = AnalyzerPurple, style = MaterialTheme.typography.labelLarge)
            if (hasStereo) {
                Canvas(Modifier.fillMaxWidth().height(104.dp)) {
                    val leftPath = Path()
                    val rightPath = Path()
                    frame.leftMagnitudes.forEachIndexed { index, value ->
                        val x = size.width * index / 63f
                        val y = size.height * (1f - value.coerceIn(0f, 1f)) * 0.48f
                        if (index == 0) leftPath.moveTo(x, y) else leftPath.lineTo(x, y)
                    }
                    frame.rightMagnitudes.forEachIndexed { index, value ->
                        val x = size.width * index / 63f
                        val y = size.height * (0.52f + value.coerceIn(0f, 1f) * 0.48f)
                        if (index == 0) rightPath.moveTo(x, y) else rightPath.lineTo(x, y)
                    }
                    drawLine(Color(0xFF302C38), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1f)
                    drawPath(leftPath, Color(0xFFA855F7), style = Stroke(width = 2f))
                    drawPath(rightPath, Color(0xFF38C8D8), style = Stroke(width = 2f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("LEFT", color = AnalyzerPurple, style = MaterialTheme.typography.labelSmall)
                    Text("RIGHT", color = Color(0xFF38C8D8), style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Text(
                    "Waiting for channel-separated FFT data from VEXPlayer. Mono spectrum is not duplicated or presented as stereo.",
                    color = AnalyzerMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            val width = frame.stereoWidth
            if (width != null) {
                Text(
                    "STEREO WIDTH INDEX  ${(width * 100f).toInt()}%",
                    color = AnalyzerMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                    drawLine(Color(0xFF383340), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 5f)
                    drawLine(Color(0xFF38C8D8), Offset(0f, size.height / 2f), Offset(size.width * width, size.height / 2f), 5f)
                }
                Text(
                    when {
                        width < 0.08f -> "Near-mono: little side-channel energy"
                        width < 0.35f -> "Narrow to moderate stereo side energy"
                        else -> "Strong side-channel energy"
                    },
                    color = AnalyzerMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Mid/Side energy share · 0% = mono, 100% = side-only. This is an energy index, not a psychoacoustic width guarantee.",
                    color = AnalyzerMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "Stereo width unavailable · waiting for real post-DSP L/R PCM analysis.",
                    color = AnalyzerMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            val correlation = frame.phaseCorrelation
            if (correlation != null) {
                Text(
                    "PHASE CORRELATION  ${if (correlation > 0.05f) "+" else ""}${"%.2f".format(correlation)}",
                    color = AnalyzerMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                    val center = size.width / 2f
                    drawLine(Color(0xFF48424F), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 4f)
                    drawLine(
                        when {
                            correlation < -0.25f -> Color(0xFFFF6B6B)
                            correlation > 0.25f -> Color(0xFF6DD6A5)
                            else -> Color(0xFFFFB86B)
                        },
                        Offset(center, size.height / 2f),
                        Offset(center + correlation * center, size.height / 2f),
                        6f
                    )
                    drawLine(Color.White, Offset(center, 1f), Offset(center, size.height - 1f), 1f)
                }
                Text(
                    when {
                        correlation < -0.25f -> "Potential phase cancellation · check mono compatibility"
                        correlation > 0.25f -> "Channels are positively correlated"
                        else -> "Low correlation · inspect stereo content"
                    },
                    color = AnalyzerMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "Phase correlation unavailable · requires host-side L/R channel analysis.",
                    color = AnalyzerMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
