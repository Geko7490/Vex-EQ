package com.vexeq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import com.vexeq.ui.EqResponseCurve
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.vexeq.compat.CompatibilityEngine
import com.vexeq.compat.CompatibilityReport
import com.vexeq.bridge.VexPlayerBridgeClient
import com.vexeq.bridge.VexPlayerBridgeConfig
import com.vexeq.bridge.VexPlayerBridgeState
import com.vexeq.bridge.VexPlayerBridgeStatus
import com.vexeq.dsp.AudioDspEngine
import com.vexeq.dsp.DspSettings
import com.vexeq.dsp.DspPerformanceMode
import com.vexeq.dsp.ParametricBand
import com.vexeq.dsp.PresetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
            ), shapes = Shapes(
                small = RoundedCornerShape(3.dp),
                medium = RoundedCornerShape(4.dp),
                large = RoundedCornerShape(6.dp)
            )) {
                Surface(Modifier.fillMaxSize(), color = VexBackground) { VexEqHome() }
            }
        }
    }
}

@Composable
private fun VexEqHome() {
    val context = LocalContext.current
    val bridgeClient = remember(context) { VexPlayerBridgeClient(context) }
    val bridgeState by bridgeClient.state.collectAsState()
    LaunchedEffect(Unit) { bridgeClient.bind() }
    DisposableEffect(bridgeClient) { onDispose { bridgeClient.close() } }
    var selectedTab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("VEX EQ", color = VexPurple, fontSize = 27.sp,
                    style = MaterialTheme.typography.headlineLarge)
                Text("PRECISION AUDIO CONTROL", color = VexTextMuted,
                    style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { selectedTab = 5 }) { Text("⚙") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp)) {
            when (selectedTab) {
                0 -> HomeDashboardScreen(
                    bridgeClient, bridgeState,
                    openEqualizer = { selectedTab = 1 },
                    openAnalyzer = { selectedTab = 2 }
                )
                1 -> EqualizerScreen(bridgeClient, bridgeState)
                2 -> SpectrumStudioScreen(bridgeClient, bridgeState)
                3 -> StudioModulesScreen(bridgeClient, bridgeState)
                4 -> PresetLibraryScreen(bridgeClient, bridgeState)
                else -> CompatibilityScreen()
            }
        }
        NavigationBar(containerColor = VexPanel) {
            listOf("Home", "Equalizer", "Analyzer", "Studio", "Presets").forEachIndexed { index, label ->
                NavigationBarItem(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    icon = { Text(label.take(1), color = if (selectedTab == index) VexPurple else VexTextMuted) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

@Composable
private fun EqualizerScreen(bridgeClient: VexPlayerBridgeClient, bridgeState: VexPlayerBridgeState) {
    val context = LocalContext.current
    val presetStore = remember(context) { PresetStore(context) }
    var presets by remember { mutableStateOf(presetStore.list()) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var presetMessage by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(presetStore.exportJson().toByteArray(Charsets.UTF_8))
                } ?: error("Cannot open destination")
            }
            presetMessage = if (result.isSuccess) "Presets exported" else "Export failed"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                    it.readBoundedText(1_048_576)
                } ?: error("Cannot open preset file")
                presetStore.importJson(json)
            }
            if (result.isSuccess) {
                presets = presetStore.list()
                presetMessage = "Imported ${result.getOrThrow()} preset(s)"
            } else {
                presetMessage = result.exceptionOrNull()?.message ?: "Import failed"
            }
        }
    }
    var settings by remember { mutableStateOf(presetStore.loadLastSettings()) }
    var undoStack by remember { mutableStateOf<List<DspSettings>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<DspSettings>>(emptyList()) }
    var aSettings by remember { mutableStateOf<DspSettings?>(null) }
    var bSettings by remember { mutableStateOf<DspSettings?>(null) }
    var showingA by remember { mutableStateOf(false) }
    fun commitSettings(next: DspSettings) {
        if (next != settings) {
            undoStack = (undoStack + settings).takeLast(100)
            redoStack = emptyList()
            settings = next
        }
    }
    fun undoSettings() {
        val previous = undoStack.lastOrNull() ?: return
        redoStack = (redoStack + settings).takeLast(100)
        undoStack = undoStack.dropLast(1)
        settings = previous
    }
    fun redoSettings() {
        val next = redoStack.lastOrNull() ?: return
        undoStack = (undoStack + settings).takeLast(100)
        redoStack = redoStack.dropLast(1)
        settings = next
    }
    var advancedStudio by remember { mutableStateOf(false) }
    var selectedParametricBand by remember { mutableIntStateOf(-1) }
    val engine = remember { AudioDspEngine(48_000, 2, settings) }
    LaunchedEffect(bridgeState.config, bridgeState.status) {
        if (bridgeState.status == VexPlayerBridgeStatus.CONNECTED) {
            bridgeState.config?.toDspSettings(settings.performanceMode)?.let { settings = it }
        }
    }
    LaunchedEffect(settings, bridgeState.status, bridgeState.config) {
        delay(250)
        if (bridgeState.status == VexPlayerBridgeStatus.CONNECTED) {
            val hostSettings = bridgeState.config?.toDspSettings(settings.performanceMode)
            if (hostSettings != settings) {
                bridgeClient.apply(VexPlayerBridgeConfig.fromDspSettings(settings))
            }
        } else {
            withContext(Dispatchers.Default) { engine.update(settings) }
        }
        withContext(Dispatchers.IO) { presetStore.saveLastSettings(settings) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !advancedStudio, onClick = { advancedStudio = false }, label = { Text("Standard") })
            FilterChip(selected = advancedStudio, onClick = { advancedStudio = true }, label = { Text("Advanced Studio") })
        }
        if (!advancedStudio) {
        Card(colors = CardDefaults.cardColors(containerColor = VexPanel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Master EQ", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (bridgeState.status == VexPlayerBridgeStatus.CONNECTED) "VEXPlayer DSP · remote control active"
                            else if (settings.enabled) "DSP profile: enabled" else "DSP profile: bypass",
                            color = VexTextMuted
                        )
                    }
                    Switch(checked = settings.enabled, onCheckedChange = { settings = settings.copy(enabled = it) })
                }
                Text(
                    if (bridgeState.status == VexPlayerBridgeStatus.CONNECTED)
                        "VEXPlayer is processing playback through its existing DSP. VEX EQ is the active control surface."
                    else "Standalone configuration preview · connect VEXPlayer to control its playback DSP.",
                    color = VexTextMuted, style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = VexPanel)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("VEXPlayer Integration", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    when (bridgeState.status) {
                        VexPlayerBridgeStatus.CONNECTED -> Text("ACTIVE", color = VexPurple, style = MaterialTheme.typography.labelMedium)
                        VexPlayerBridgeStatus.READY -> Text("AVAILABLE", color = VexTextMuted, style = MaterialTheme.typography.labelMedium)
                        VexPlayerBridgeStatus.CONNECTING -> Text("CONNECTING", color = VexTextMuted, style = MaterialTheme.typography.labelMedium)
                        VexPlayerBridgeStatus.ERROR -> Text("UNAVAILABLE", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                        VexPlayerBridgeStatus.DISCONNECTED -> Text("OFFLINE", color = VexTextMuted, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    bridgeState.message ?: "Searching for VEXPlayer…",
                    color = VexTextMuted, style = MaterialTheme.typography.bodySmall
                )
                if (bridgeState.status == VexPlayerBridgeStatus.CONNECTED) {
                    Text("VEXPlayer's own EQ controls are locked until this connection is released. Playback continues through one DSP chain.",
                        color = VexTextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        when (bridgeState.status) {
                            VexPlayerBridgeStatus.READY -> bridgeClient.acquireControl()
                            VexPlayerBridgeStatus.CONNECTED -> bridgeClient.releaseControl()
                            VexPlayerBridgeStatus.ERROR -> { bridgeClient.close(); bridgeClient.bind() }
                            VexPlayerBridgeStatus.DISCONNECTED -> bridgeClient.bind()
                            VexPlayerBridgeStatus.CONNECTING -> Unit
                        }
                    },
                    enabled = bridgeState.status != VexPlayerBridgeStatus.CONNECTING,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when (bridgeState.status) {
                            VexPlayerBridgeStatus.READY -> "Enable Advanced Mode"
                            VexPlayerBridgeStatus.CONNECTED -> "Disconnect VEXPlayer"
                            VexPlayerBridgeStatus.CONNECTING -> "Connecting…"
                            VexPlayerBridgeStatus.ERROR -> "Retry Connection"
                            VexPlayerBridgeStatus.DISCONNECTED -> "Find VEXPlayer"
                        }
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = VexPanel)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Processing Mode", style = MaterialTheme.typography.titleMedium)
                if (bridgeState.status == VexPlayerBridgeStatus.CONNECTED) {
                    Text("VEXPlayer uses its own full-resolution DSP profile; Low / Mid / High applies to standalone VEX EQ processing.", color = VexTextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    when (settings.performanceMode) {
                        DspPerformanceMode.LOW -> "Low · 9 graphic filters · up to 16 parametric bands"
                        DspPerformanceMode.MID -> "Mid · 16 graphic filters · up to 16 parametric bands"
                        DspPerformanceMode.HIGH -> "High · 31 graphic filters · up to 16 parametric bands"
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("vex-eq-presets.json") },
                        modifier = Modifier.weight(1f)) { Text("Export") }
                    OutlinedButton(onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
                    }, modifier = Modifier.weight(1f)) { Text("Import") }
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

        }

        if (advancedStudio) {
            Text("PARAMETRIC EQ WORKSPACE", color = VexPurple, style = MaterialTheme.typography.labelMedium)
            Text("Touch or drag a node, then enter exact values below. Both controls edit the same live PEQ settings.", color = VexTextMuted, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { undoSettings() }, enabled = undoStack.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Undo") }
            OutlinedButton(onClick = { redoSettings() }, enabled = redoStack.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Redo") }
            OutlinedButton(onClick = { aSettings = settings; showingA = false }, modifier = Modifier.weight(1f)) { Text("Store A") }
            OutlinedButton(onClick = { bSettings = settings; showingA = false }, modifier = Modifier.weight(1f)) { Text("Store B") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val target = if (showingA) bSettings else aSettings
                if (target != null) commitSettings(target)
                showingA = !showingA
            }, enabled = aSettings != null && bSettings != null, modifier = Modifier.weight(1f)) {
                Text(if (showingA) "Switch to B" else "Switch to A")
            }
            OutlinedButton(onClick = { commitSettings(settings.copy(parametricBands = emptyList())) },
                modifier = Modifier.weight(1f)) { Text("Clear PEQ") }
        }
        Text(
            when {
                aSettings == null || bSettings == null -> "A/B: Store both snapshots to compare settings."
                showingA -> "A/B preview: A snapshot is active"
                else -> "A/B preview: B/current snapshot is active"
            }, color = VexTextMuted, style = MaterialTheme.typography.labelSmall
        )
        EqResponseCurve(
            graphicGainsDb = settings.graphicGainsDb,
            parametricBands = settings.parametricBands,
            selectedBandIndex = selectedParametricBand,
            onSelectBand = { selectedParametricBand = it },
            onBandChange = { index, changed ->
                val updated = settings.parametricBands.toMutableList()
                if (index in updated.indices) {
                    updated[index] = changed
                    commitSettings(settings.copy(parametricBands = updated))
                }
            }
        )

        Card(colors = CardDefaults.cardColors(containerColor = VexPanel)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Parametric EQ", style = MaterialTheme.typography.titleLarge)
                        Text("${settings.parametricBands.size}/16 bands", color = VexTextMuted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            commitSettings(settings.copy(
                                parametricBands = settings.parametricBands + ParametricBand()
                            ))
                            selectedParametricBand = settings.parametricBands.lastIndex
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
                            commitSettings(settings.copy(parametricBands = updated))
                        },
                        onRemove = {
                            commitSettings(settings.copy(
                                parametricBands = settings.parametricBands.filterIndexed { i, _ -> i != index }
                            ))
                        },
                        onDuplicate = {
                            if (settings.parametricBands.size < AudioDspEngine.MAX_PARAMETRIC_BANDS) {
                                val duplicate = band.copy(frequencyHz = (band.frequencyHz * 1.15f).coerceAtMost(20_000f))
                                val updated = settings.parametricBands.toMutableList().apply { add(index + 1, duplicate) }
                                commitSettings(settings.copy(parametricBands = updated))
                                selectedParametricBand = index + 1
                            }
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
                            commitSettings(settings.copy(graphicGainsDb = updated))
                        }, valueRange = -12f..12f)
                    }
                }
                Button(onClick = { commitSettings(settings.copy(graphicGainsDb = List(31) { 0f })) },
                    modifier = Modifier.fillMaxWidth()) { Text("Reset all bands") }
            }
        }

        Text(
            if (bridgeState.status == VexPlayerBridgeStatus.CONNECTED)
                "Live control active: changes are sent to VEXPlayer, which remains the sole playback DSP owner."
            else
                "Standalone mode edits a local DSP profile only. Connect VEXPlayer to control its playback EQ; VEX EQ does not process system-wide audio.",
            color = VexTextMuted, style = MaterialTheme.typography.bodySmall
        )
        }
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
    onRemove: () -> Unit,
    onDuplicate: () -> Unit = {}
) {
    val minLog = ln(20f)
    val maxLog = ln(20_000f)
    val normalizedFrequency = ((ln(band.frequencyHz.coerceIn(20f, 20_000f)) - minLog) /
        (maxLog - minLog)).coerceIn(0f, 1f)
    Card(colors = CardDefaults.cardColors(containerColor = VexBackground)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Band ${index + 1} · Bell", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onDuplicate) { Text("Copy") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
            Text("MANUAL VALUES", color = VexTextMuted, style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = band.frequencyHz.toInt().toString(),
                    onValueChange = { raw -> raw.toFloatOrNull()?.let {
                        onChange(band.copy(frequencyHz = it.coerceIn(20f, 20_000f)))
                    } },
                    modifier = Modifier.weight(1.2f),
                    label = { Text("Hz") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = "%.1f".format(java.util.Locale.US, band.gainDb),
                    onValueChange = { raw -> raw.toFloatOrNull()?.let {
                        onChange(band.copy(gainDb = it.coerceIn(-12f, 12f)))
                    } },
                    modifier = Modifier.weight(1f),
                    label = { Text("Gain dB") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = "%.2f".format(java.util.Locale.US, band.q),
                    onValueChange = { raw -> raw.toFloatOrNull()?.let {
                        onChange(band.copy(q = it.coerceIn(0.3f, 10f)))
                    } },
                    modifier = Modifier.weight(0.9f),
                    label = { Text("Q") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
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
                Text("Q Factor", modifier = Modifier.weight(1f), color = VexTextMuted)
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


private fun java.io.Reader.readBoundedText(maxChars: Int): String {
    val result = StringBuilder()
    val buffer = CharArray(8_192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(result.length + count <= maxChars) { "Preset file is too large" }
        result.append(buffer, 0, count)
    }
    return result.toString()
}
