package com.vexeq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vexeq.compat.CompatibilityEngine
import com.vexeq.compat.CompatibilityReport
import com.vexeq.dsp.AudioDspEngine
import com.vexeq.dsp.DspSettings
import com.vexeq.dsp.DspPerformanceMode
import com.vexeq.dsp.ParametricBand
import com.vexeq.dsp.PresetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.coroutines.withContext

private val VexPurple = Color(0xFFA855F7)
private val VexBackground = Color(0xFF0F0F13)
private val VexPanel = Color(0xFF1C1B23)
private val VexTextMuted = Color(0xFFB8B4C0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = VexPurple, background = VexBackground, surface = VexPanel,
                onBackground = Color(0xFFF5F3F8), onSurface = Color(0xFFF5F3F8)
            )) {
                Surface(Modifier.fillMaxSize(), color = VexBackground) { VexEqHome() }
            }
        }
    }
}

@Composable
private fun VexEqHome() {
    var selectedTab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text("VEX EQ", color = VexPurple, fontSize = 30.sp, style = MaterialTheme.typography.headlineLarge)
        Text("PRECISION AUDIO CONTROL", color = VexTextMuted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { selectedTab = 0 }, modifier = Modifier.weight(1f)) { Text("Equalizer") }
            Button(onClick = { selectedTab = 1 }, modifier = Modifier.weight(1f)) { Text("Compatibility") }
        }
        Spacer(Modifier.height(10.dp))
        if (selectedTab == 0) EqualizerScreen() else CompatibilityScreen()
    }
}

@Composable
private fun EqualizerScreen() {
    val context = LocalContext.current
    val presetStore = remember(context) { PresetStore(context) }
    var presets by remember { mutableStateOf(presetStore.list()) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var presetMessage by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(DspSettings()) }
    val engine = remember { AudioDspEngine(48_000, 2, settings) }
    LaunchedEffect(settings) { withContext(Dispatchers.Default) { engine.update(settings) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = VexPanel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Master EQ", style = MaterialTheme.typography.titleLarge)
                        Text(if (settings.enabled) "DSP profile: enabled" else "DSP profile: bypass", color = VexTextMuted)
                    }
                    Switch(checked = settings.enabled, onCheckedChange = { settings = settings.copy(enabled = it) })
                }
                Text("Configuration preview only · no audio stream is connected yet. Playback-host integration is the next stage.",
                    color = VexTextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = VexPanel)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Processing Mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (settings.performanceMode) {
                        DspPerformanceMode.LOW -> "Low · 9 graphic filters · up to 2 parametric bands"
                        DspPerformanceMode.MID -> "Mid · 16 graphic filters · up to 4 parametric bands"
                        DspPerformanceMode.HIGH -> "High · 31 graphic filters · up to 8 parametric bands"
                    },
                    color = VexTextMuted, style = MaterialTheme.typography.bodySmall
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DspPerformanceMode.values().forEach { mode ->
                        Button(
                            onClick = { settings = settings.copy(performanceMode = mode) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (settings.performanceMode == mode) VexPurple else VexBackground
                            )
                        ) { Text(mode.name) }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = VexPanel)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Presets", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Button(onClick = { presetName = ""; showSaveDialog = true }) { Text("Save current") }
                }
                if (presets.isEmpty()) {
                    Text("No saved presets yet. Save your current EQ curve to reuse it later.",
                        color = VexTextMuted, style = MaterialTheme.typography.bodySmall)
                } else {
                    presets.forEach { preset ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(preset.name, style = MaterialTheme.typography.titleSmall)
                                Text("${preset.settings.performanceMode.name} · ${preset.settings.parametricBands.size} PEQ bands",
                                    color = VexTextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { settings = preset.settings; presetMessage = "Loaded: ${preset.name}" }) {
                                Text("Load")
                            }
                            TextButton(onClick = {
                                presetStore.delete(preset.name)
                                presets = presetStore.list()
                                presetMessage = "Deleted: ${preset.name}"
                            }) { Text("Delete") }
                        }
                    }
                }
                if (presetMessage.isNotBlank()) {
                    Text(presetMessage, color = VexPurple, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        DspSliderCard("Preamp", "%.1f dB".format(settings.preampDb), settings.preampDb, -12f, 6f) {
            settings = settings.copy(preampDb = it)
        }
        DspSliderCard("Bass Boost", "${(settings.bassBoost * 100).toInt()}%", settings.bassBoost, 0f, 1f) {
            settings = settings.copy(bassBoost = it)
        }
        DspSliderCard("Stereo Width", "${(settings.stereoWidth * 100).toInt()}%", settings.stereoWidth, 0f, 1f) {
            settings = settings.copy(stereoWidth = it)
        }

        Card(colors = CardDefaults.cardColors(containerColor = VexPanel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingSwitch("Automatic Headroom", settings.autoHeadroom) { settings = settings.copy(autoHeadroom = it) }
                SettingSwitch("Output Limiter", settings.limiterEnabled) { settings = settings.copy(limiterEnabled = it) }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = VexPanel)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Parametric EQ", style = MaterialTheme.typography.titleLarge)
                        Text("${settings.parametricBands.size}/8 bands", color = VexTextMuted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            settings = settings.copy(
                                parametricBands = settings.parametricBands + ParametricBand()
                            )
                        },
                        enabled = settings.parametricBands.size < AudioDspEngine.MAX_PARAMETRIC_BANDS
                    ) { Text("Add band") }
                }
                settings.parametricBands.forEachIndexed { index, band ->
                    ParametricBandCard(
                        index = index,
                        band = band,
                        onChange = { changed ->
                            val updated = settings.parametricBands.toMutableList()
                            updated[index] = changed
                            settings = settings.copy(parametricBands = updated)
                        },
                        onRemove = {
                            settings = settings.copy(
                                parametricBands = settings.parametricBands.filterIndexed { i, _ -> i != index }
                            )
                        }
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = VexPanel)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("31-Band Graphic EQ", style = MaterialTheme.typography.titleLarge)
                Text("Adjust each center frequency from -12 dB to +12 dB", color = VexTextMuted,
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                AudioDspEngine.BAND_FREQUENCIES.forEachIndexed { index, frequency ->
                    val value = settings.graphicGainsDb[index]
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(formatFrequency(frequency), modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge)
                            Text("%+.1f dB".format(value), color = VexPurple,
                                style = MaterialTheme.typography.labelMedium)
                        }
                        Slider(value = value, onValueChange = { newValue ->
                            val updated = settings.graphicGainsDb.toMutableList()
                            updated[index] = newValue
                            settings = settings.copy(graphicGainsDb = updated)
                        }, valueRange = -12f..12f)
                    }
                }
                Button(onClick = { settings = settings.copy(graphicGainsDb = List(31) { 0f }) },
                    modifier = Modifier.fillMaxWidth()) { Text("Reset all bands") }
            }
        }

        Text("These controls configure VEX EQ's PCM DSP core. They do not yet modify system audio or VEXPlayer playback; that requires playback-host integration and device compatibility checks.",
            color = VexTextMuted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
    }
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save EQ preset") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it.take(40) },
                    label = { Text("Preset name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (presetStore.save(presetName, settings)) {
                        presets = presetStore.list()
                        presetMessage = "Preset saved"
                        showSaveDialog = false
                    } else {
                        presetMessage = "Enter a name and try again"
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            }
        )
    }
}


@Composable
private fun ParametricBandCard(
    index: Int,
    band: ParametricBand,
    onChange: (ParametricBand) -> Unit,
    onRemove: () -> Unit
) {
    val minLog = ln(20f)
    val maxLog = ln(20_000f)
    val normalizedFrequency = ((ln(band.frequencyHz.coerceIn(20f, 20_000f)) - minLog) /
        (maxLog - minLog)).coerceIn(0f, 1f)
    Card(colors = CardDefaults.cardColors(containerColor = VexBackground)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Band ${index + 1}", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onRemove) { Text("Remove") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Frequency", modifier = Modifier.weight(1f), color = VexTextMuted)
                Text("${band.frequencyHz.toInt()} Hz", color = VexPurple)
            }
            Slider(value = normalizedFrequency, onValueChange = {
                val hz = exp(minLog + (maxLog - minLog) * it)
                onChange(band.copy(frequencyHz = hz))
            }, valueRange = 0f..1f)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Gain", modifier = Modifier.weight(1f), color = VexTextMuted)
                Text("%+.1f dB".format(band.gainDb), color = VexPurple)
            }
            Slider(value = band.gainDb, onValueChange = { onChange(band.copy(gainDb = it)) },
                valueRange = -12f..12f)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Q", modifier = Modifier.weight(1f), color = VexTextMuted)
                Text("%.2f".format(band.q), color = VexPurple)
            }
            Slider(value = band.q, onValueChange = { onChange(band.copy(q = it)) },
                valueRange = 0.3f..10f)
        }
    }
}

@Composable
private fun DspSliderCard(title: String, valueLabel: String, value: Float, minimum: Float,
    maximum: Float, onValueChange: (Float) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = VexPanel)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(valueLabel, color = VexPurple)
            }
            Slider(value = value, onValueChange = onValueChange, valueRange = minimum..maximum)
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CompatibilityScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<CompatibilityReport?>(null) }
    var scanning by remember { mutableStateOf(true) }
    fun refresh() {
        scope.launch {
            scanning = true
            report = withContext(Dispatchers.IO) { CompatibilityEngine(context).scan() }
            scanning = false
        }
    }
    LaunchedEffect(Unit) { refresh() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (scanning) {
            Card(colors = CardDefaults.cardColors(containerColor = VexPanel)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = VexPurple)
                    Text("Scanning Android audio capabilities…")
                }
            }
        }
        report?.let { data ->
            StatusCard("Global audio processing", data.globalStatus.label, data.globalExplanation)
            StatusCard("VEXPlayer connection", data.playerStatus, data.playerExplanation)
            StatusCard("Audio effects", data.effectSummary,
                data.effectDetails.joinToString("\n").ifBlank { "No effect descriptors reported." })
            StatusCard("Audio output", data.outputSummary,
                data.outputDetails.joinToString("\n").ifBlank { "No output route reported." })
            StatusCard("Android", "API " + data.apiLevel, data.androidRelease)
            StatusCard("Active third-party effects", "Not publicly inspectable",
                "Android does not provide a general public API to enumerate every other app's active audio effects. VEX EQ will not guess this state.")
        }
        Button(onClick = ::refresh, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
            Text("Rescan compatibility")
        }
    }
}

@Composable
private fun StatusCard(title: String, status: String, details: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = VexPanel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = VexTextMuted, style = MaterialTheme.typography.labelMedium)
            Text(status, color = VexPurple, style = MaterialTheme.typography.titleMedium)
            Text(details, color = Color(0xFFD0CDD6), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatFrequency(hz: Int): String = when {
    hz >= 1_000 -> if (hz % 1_000 == 0) "${hz / 1_000} kHz" else "${hz / 1_000f} kHz"
    else -> "${hz} Hz"
}
