package com.vexeq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.vexeq.compat.CompatibilityEngine
import com.vexeq.compat.CompatibilityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val VexPurple = Color(0xFFA855F7)
private val VexBackground = Color(0xFF0F0F13)
private val VexPanel = Color(0xFF1C1B23)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = VexPurple, background = VexBackground, surface = VexPanel,
                onBackground = Color(0xFFF5F3F8), onSurface = Color(0xFFF5F3F8)
            )) {
                Surface(Modifier.fillMaxSize(), color = VexBackground) { CompatibilityHome() }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CompatibilityHome() {
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

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("VEX EQ", color = VexPurple, fontSize = 30.sp, style = MaterialTheme.typography.headlineLarge)
        Text("SYSTEM AUDIO • COMPATIBILITY", color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
        Text("Device capability check", style = MaterialTheme.typography.titleLarge)

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
            StatusCard("Audio effects", data.effectSummary, data.effectDetails.joinToString("\n").ifBlank { "No effect descriptors reported." })
            StatusCard("Audio output", data.outputSummary, data.outputDetails.joinToString("\n").ifBlank { "No output route reported." })
            StatusCard("Android", "API " + data.apiLevel, data.androidRelease)
            StatusCard("Active third-party effects", "Not publicly inspectable",
                "Android does not provide a general public API to enumerate every other app's active audio effects. VEX EQ will not guess this state.")
        }
        Button(onClick = ::refresh, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
            Text("Rescan compatibility")
        }
        Spacer(Modifier.height(16.dp))
        Text("Compatibility scan only. This build does not activate a global effect or alter audio from other apps.",
            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    }
}

@androidx.compose.runtime.Composable
private fun StatusCard(title: String, status: String, details: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = VexPanel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
            Text(status, color = VexPurple, style = MaterialTheme.typography.titleMedium)
            Text(details, color = Color(0xFFD0CDD6), style = MaterialTheme.typography.bodySmall)
        }
    }
}
