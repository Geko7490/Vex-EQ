package com.vexeq

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vexeq.bridge.VexPlayerBridgeClient
import com.vexeq.bridge.VexPlayerBridgeState
import com.vexeq.bridge.VexPlayerBridgeStatus
import com.vexeq.dsp.DspSettings
import com.vexeq.dsp.PresetStore
import com.vexeq.ui.EqResponseCurve

private val StudioPanel = Color(0xFF1B1A22)
private val StudioPurple = Color(0xFFA855F7)
private val StudioMuted = Color(0xFFB8B4C0)

@Composable
fun HomeDashboardScreen(
    bridgeClient: VexPlayerBridgeClient,
    bridgeState: VexPlayerBridgeState,
    openEqualizer: () -> Unit,
    openAnalyzer: () -> Unit
) {
    val connected = bridgeState.status == VexPlayerBridgeStatus.CONNECTED
    val config = bridgeState.config
    Column(Modifier.fillMaxSize().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = StudioPanel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PLAYBACK LINK", color = StudioPurple, style = MaterialTheme.typography.labelLarge)
                Text(
                    when (bridgeState.status) {
                        VexPlayerBridgeStatus.CONNECTED -> "VEXPlayer Connected"
                        VexPlayerBridgeStatus.READY -> "VEXPlayer Available"
                        VexPlayerBridgeStatus.CONNECTING -> "Connecting…"
                        VexPlayerBridgeStatus.ERROR -> "Connection Error"
                        VexPlayerBridgeStatus.DISCONNECTED -> "VEXPlayer Disconnected"
                    }, style = MaterialTheme.typography.titleLarge
                )
                Text(bridgeState.message ?: "Checking for VEXPlayer", color = StudioMuted,
                    style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        when (bridgeState.status) {
                            VexPlayerBridgeStatus.READY -> bridgeClient.acquireControl()
                            VexPlayerBridgeStatus.CONNECTED -> bridgeClient.releaseControl()
                            VexPlayerBridgeStatus.DISCONNECTED, VexPlayerBridgeStatus.ERROR -> bridgeClient.bind()
                            VexPlayerBridgeStatus.CONNECTING -> Unit
                        }
                    },
                    enabled = bridgeState.status != VexPlayerBridgeStatus.CONNECTING,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (connected) "Release VEXPlayer Control" else if (bridgeState.status == VexPlayerBridgeStatus.READY) "Enable Advanced Mode" else "Connect VEXPlayer")
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = StudioPanel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Master EQ", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = config?.enabled ?: false,
                        onCheckedChange = { enabled ->
                            config?.let { bridgeClient.apply(it.copy(enabled = enabled)) }
                        },
                        enabled = connected && config != null
                    )
                }
                Text(if (connected) "Single DSP chain · VEXPlayer owns playback processing" else "Connect VEXPlayer to change live playback settings",
                    color = StudioMuted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = openEqualizer, modifier = Modifier.weight(1f)) { Text("Open EQ Studio") }
                    OutlinedButton(onClick = openAnalyzer, modifier = Modifier.weight(1f)) { Text("Analyzer") }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = StudioPanel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("QUICK CONTROLS", color = StudioPurple, style = MaterialTheme.typography.labelLarge)
                val bass = (config?.bassStrength ?: 0) / 1000f
                Text("Bass Boost · ${(bass * 100).toInt()}%", color = StudioMuted)
                Slider(value = bass, onValueChange = { value ->
                    config?.let { bridgeClient.apply(it.copy(bassStrength = (value * 1000).toInt())) }
                }, enabled = connected, valueRange = 0f..1f)
                val width = (config?.stereoWidth ?: 0) / 1000f
                Text("Stereo Width · ${(width * 100).toInt()}%", color = StudioMuted)
                Slider(value = width, onValueChange = { value ->
                    config?.let { bridgeClient.apply(it.copy(stereoWidth = (value * 1000).toInt())) }
                }, enabled = connected, valueRange = 0f..1f)
        config?.toDspSettings()?.let { snapshot ->
            Card(colors = CardDefaults.cardColors(containerColor = StudioPanel)) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("LIVE EQ RESPONSE", color = StudioPurple, style = MaterialTheme.typography.labelLarge)
                    EqResponseCurve(
                        graphicGainsDb = snapshot.graphicGainsDb,
                        parametricBands = snapshot.parametricBands
                    )
                }
            }
        }
            }
        }
    }
}

@Composable
fun StudioModulesScreen(bridgeClient: VexPlayerBridgeClient, bridgeState: VexPlayerBridgeState) {
    val config = bridgeState.config
    val connected = bridgeState.status == VexPlayerBridgeStatus.CONNECTED
    fun update(transform: (com.vexeq.bridge.VexPlayerBridgeConfig) -> com.vexeq.bridge.VexPlayerBridgeConfig) {
        config?.let { bridgeClient.apply(transform(it)) }
    }
    Column(Modifier.fillMaxSize().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = StudioPanel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("STEREO & PHASE", color = StudioPurple, style = MaterialTheme.typography.labelLarge)
                Text("Stereo Width", style = MaterialTheme.typography.titleMedium)
                val width = (config?.stereoWidth ?: 0) / 1000f
                Text("${(width * 100).toInt()}%", color = StudioMuted)
                Slider(value = width, onValueChange = { value ->
                    update { it.copy(stereoWidth = (value * 1000).toInt()) }
                }, enabled = connected, valueRange = 0f..1f)
                Text("Width is applied by VEXPlayer's stereo mid/side stage. L/R balance and phase correlation remain unavailable until the host provides channel-separated analysis data.",
                    color = StudioMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = StudioPanel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DYNAMICS STUDIO", color = StudioPurple, style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Compressor", style = MaterialTheme.typography.titleMedium)
                        Text("Linked-channel RMS/peak protection stage", color = StudioMuted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = config?.compressorEnabled ?: false,
                        onCheckedChange = { enabled -> update { it.copy(compressorEnabled = enabled) } },
                        enabled = connected && config != null)
                }
                val threshold = config?.compressorThresholdDb ?: -18f
                Text("Threshold · %.1f dB".format(threshold), color = StudioMuted)
                Slider(value = threshold, onValueChange = { value ->
                    update { it.copy(compressorThresholdDb = value) }
                }, enabled = connected && config != null, valueRange = -36f..0f)
                val ratio = config?.compressorRatio ?: 4f
                Text("Ratio · %.1f:1".format(ratio), color = StudioMuted)
                Slider(value = ratio, onValueChange = { value ->
                    update { it.copy(compressorRatio = value) }
                }, enabled = connected && config != null, valueRange = 1f..20f)
                val attack = config?.compressorAttackMs ?: 10f
                Text("Attack · %.0f ms".format(attack), color = StudioMuted)
                Slider(value = attack, onValueChange = { value ->
                    update { it.copy(compressorAttackMs = value) }
                }, enabled = connected && config != null, valueRange = 1f..100f)
                val release = config?.compressorReleaseMs ?: 120f
                Text("Release · %.0f ms".format(release), color = StudioMuted)
                Slider(value = release, onValueChange = { value ->
                    update { it.copy(compressorReleaseMs = value) }
                }, enabled = connected && config != null, valueRange = 10f..1_000f)
                androidx.compose.material3.HorizontalDivider(color = Color(0xFF292831))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Soft Clipper", style = MaterialTheme.typography.titleMedium)
                        Text("Smooth saturation before the final safety limiter", color = StudioMuted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = config?.softClipperEnabled ?: false,
                        onCheckedChange = { enabled -> update { it.copy(softClipperEnabled = enabled) } },
                        enabled = connected && config != null)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Output Limiter", style = MaterialTheme.typography.titleMedium)
                        Text("Final linked-channel peak ceiling", color = StudioMuted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = config?.limiterEnabled ?: true,
                        onCheckedChange = { enabled -> update { it.copy(limiterEnabled = enabled) } },
                        enabled = connected && config != null)
                }
                if (!connected) {
                    Text("Connect VEXPlayer and enable Advanced Mode to change live DSP settings.",
                        color = StudioMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = StudioPanel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SIGNAL OWNERSHIP", color = StudioPurple, style = MaterialTheme.typography.labelLarge)
                Text("VEX EQ sends versioned control snapshots. VEXPlayer remains the sole playback DSP host; the analyzer is a read-only observation path.",
                    color = StudioMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun PresetLibraryScreen(bridgeClient: VexPlayerBridgeClient, bridgeState: VexPlayerBridgeState) {
    val context = LocalContext.current
    val store = remember(context) { PresetStore(context) }
    var presets by remember { mutableStateOf(store.list()) }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PRESET LIBRARY", color = StudioPurple, style = MaterialTheme.typography.labelLarge)
        if (presets.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = StudioPanel)) {
                Text("No presets saved yet. Open Equalizer and save your current sound profile.",
                    Modifier.padding(16.dp), color = StudioMuted)
            }
        }
        presets.forEach { preset ->
            Card(colors = CardDefaults.cardColors(containerColor = StudioPanel)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.name, style = MaterialTheme.typography.titleMedium)
                        Text("${preset.settings.parametricBands.size}/16 PEQ · ${preset.settings.performanceMode.name}",
                            color = StudioMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            if (bridgeState.status == VexPlayerBridgeStatus.CONNECTED) {
                                bridgeClient.apply(com.vexeq.bridge.VexPlayerBridgeConfig.fromDspSettings(preset.settings))
                                message = "Applied ${preset.name} to VEXPlayer"
                            } else {
                                message = "Connect VEXPlayer before applying a live preset"
                            }
                        },
                        enabled = bridgeState.status == VexPlayerBridgeStatus.CONNECTED
                    ) { Text("Apply") }
                }
            }
        }
        if (message.isNotBlank()) Text(message, color = StudioMuted, style = MaterialTheme.typography.bodySmall)
    }
}
