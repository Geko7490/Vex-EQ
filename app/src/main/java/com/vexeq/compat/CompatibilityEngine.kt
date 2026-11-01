package com.vexeq.compat

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build

enum class CompatibilityStatus(val label: String) {
    SUPPORTED("Supported"),
    PARTIALLY_SUPPORTED("Partially Supported"),
    UNSUPPORTED("Unsupported"),
    PERMISSION_REQUIRED("Permission Required"),
    UNKNOWN("Unknown")
}

data class CompatibilityReport(
    val apiLevel: Int,
    val androidRelease: String,
    val globalStatus: CompatibilityStatus,
    val globalExplanation: String,
    val playerStatus: String,
    val playerExplanation: String,
    val effectSummary: String,
    val effectDetails: List<String>,
    val outputSummary: String,
    val outputDetails: List<String>
)

object CompatibilityPolicy {
    // A registered effect descriptor does not prove global-mix insertion works.
    fun globalStatus(equalizerDescriptorFound: Boolean): CompatibilityStatus =
        if (equalizerDescriptorFound) CompatibilityStatus.PARTIALLY_SUPPORTED
        else CompatibilityStatus.UNSUPPORTED
}

class CompatibilityEngine(private val context: Context) {
    fun scan(): CompatibilityReport {
        val descriptors = runCatching { AudioEffect.queryEffects()?.toList().orEmpty() }
            .getOrDefault(emptyList())
        val equalizer = descriptors.filter { it.type == AudioEffect.EFFECT_TYPE_EQUALIZER }
        val bass = descriptors.filter { it.type == AudioEffect.EFFECT_TYPE_BASS_BOOST }
        val virtualizer = descriptors.filter { it.type == AudioEffect.EFFECT_TYPE_VIRTUALIZER }

        val outputDevices = runCatching {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())

        val playerInstalled = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(VEX_PLAYER_PACKAGE, 0)
        }.isSuccess

        val effectDetails = buildList {
            add("Equalizer: " + if (equalizer.isNotEmpty()) "available (" + equalizer.size + ")" else "not reported")
            add("Bass Boost: " + if (bass.isNotEmpty()) "available (" + bass.size + ")" else "not reported")
            add("Virtualizer: " + if (virtualizer.isNotEmpty()) "available (" + virtualizer.size + ")" else "not reported")
            descriptors.forEach { add(it.name + " · " + it.implementor) }
        }
        val outputDetails = outputDevices.map { device ->
            device.type.toFriendlyName() + " · " + (device.channelCounts.maxOrNull() ?: 0) + " channel(s)"
        }
        val outputSummary = if (outputDetails.isEmpty()) "No output device detected"
            else outputDetails.distinct().joinToString(", ")

        val status = CompatibilityPolicy.globalStatus(equalizer.isNotEmpty())
        val explanation = if (equalizer.isNotEmpty()) {
            "An Android Equalizer implementation is registered. Inserting an effect into the global output mix with session ID 0 is deprecated and OEM-dependent. This is not proof of system-wide operation."
        } else {
            "Android did not report an Equalizer effect descriptor. VEX EQ cannot claim global Equalizer support on this device."
        }
        return CompatibilityReport(
            apiLevel = Build.VERSION.SDK_INT,
            androidRelease = "Android " + Build.VERSION.RELEASE,
            globalStatus = status,
            globalExplanation = explanation,
            playerStatus = if (playerInstalled) "Installed · integration pending" else "Not detected",
            playerExplanation = if (playerInstalled)
                "VEXPlayer is installed. A verified VEX EQ control protocol has not yet been negotiated; this scan does not change player settings."
            else "VEX EQ remains independent. Install VEXPlayer to prepare for future direct integration.",
            effectSummary = equalizer.size.toString() + " EQ · " + bass.size + " Bass Boost · " + virtualizer.size + " Virtualizer",
            effectDetails = effectDetails,
            outputSummary = outputSummary,
            outputDetails = outputDetails
        )
    }

    private fun Int.toFriendlyName(): String = when (this) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog line"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_UNKNOWN -> "Unknown output"
        else -> if (Build.VERSION.SDK_INT >= 31 && this == AudioDeviceInfo.TYPE_BLE_HEADSET)
            "Bluetooth LE headset" else "Other output (" + this + ")"
    }

    companion object { const val VEX_PLAYER_PACKAGE = "com.vexmusic.player" }
}
