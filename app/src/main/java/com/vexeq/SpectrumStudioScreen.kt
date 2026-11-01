package com.vexeq

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

private val AnalyzerPanel = Color(0xFF1C1B23)
private val AnalyzerMuted = Color(0xFFB8B4C0)
private val AnalyzerPurple = Color(0xFFA855F7)

@Composable
fun SpectrumStudioScreen(
    bridgeClient: VexPlayerBridgeClient,
    bridgeState: VexPlayerBridgeState
) {
    val frame by bridgeClient.spectrumFrame.collectAsState()
    var showBars by remember { mutableStateOf(true) }
    var showWave by remember { mutableStateOf(true) }

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
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = showBars, onClick = { showBars = !showBars }, label = { Text("FFT Bars") })
            FilterChip(selected = showWave, onClick = { showWave = !showWave }, label = { Text("FFT Wave") })
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101014))) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                if (frame.timestampNanos > 0L && (showBars || showWave)) {
                    AndroidView(
                        factory = { context -> NextGenSpectrumView(context) },
                        modifier = Modifier.fillMaxWidth().height(280.dp),
                        update = { it.submitFrame(frame, showBars, showWave) }
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
                                !showBars && !showWave -> "Enable at least one visual layer"
                                else -> "Play a track in VEXPlayer to start the analyzer"
                            },
                            color = AnalyzerMuted
                        )
                    }
                }
            }
        }

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
        Text("\${(value.coerceIn(0f, 1f) * 100).toInt()}%", color = AnalyzerPurple,
            style = MaterialTheme.typography.titleMedium)
    }
}
