package com.vexeq.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.vexeq.dsp.DspSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BridgeSpectrumFrame(
    val magnitudes: FloatArray = FloatArray(0),
    val peaks: FloatArray = FloatArray(0),
    val waveform: FloatArray = FloatArray(0),
    /** Optional channel-separated FFT data; null/empty means host has not implemented it. */
    val leftMagnitudes: FloatArray = FloatArray(0),
    val rightMagnitudes: FloatArray = FloatArray(0),
    /** -1 = fully out of phase, 0 = uncorrelated, +1 = in phase. */
    val phaseCorrelation: Float? = null,
    /** Optional Mid/Side index: 0 = mono, 1 = side-only. */
    val stereoWidth: Float? = null,
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,
    val beatPulse: Float = 0f,
    val rms: Float = 0f,
    val timestampNanos: Long = 0L
)

enum class VexPlayerBridgeStatus { DISCONNECTED, CONNECTING, READY, CONNECTED, ERROR }

data class VexPlayerBridgeState(
    val status: VexPlayerBridgeStatus = VexPlayerBridgeStatus.DISCONNECTED,
    val hostRevision: Long = 0L,
    val config: VexPlayerBridgeConfig? = null,
    val message: String? = null
)

/**
 * Messenger client for VEXPlayer's signature-protected EQ service.
 * VEX EQ sends settings only; VEXPlayer remains the sole PCM DSP host.
 */
class VexPlayerBridgeClient(context: Context) {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(VexPlayerBridgeState())
    val state: StateFlow<VexPlayerBridgeState> = mutableState.asStateFlow()

    private var serviceMessenger: Messenger? = null
    private var isBound = false
    private var spectrumSubscribed = false
    private val mutableSpectrumFrame = MutableStateFlow(BridgeSpectrumFrame())
    val spectrumFrame: StateFlow<BridgeSpectrumFrame> = mutableSpectrumFrame.asStateFlow()
    private val mutableSpectrumError = MutableStateFlow<String?>(null)
    val spectrumError: StateFlow<String?> = mutableSpectrumError.asStateFlow()

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            handleResponse(message)
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                fail("VEXPlayer returned an empty bridge connection.")
                return
            }
            serviceMessenger = Messenger(binder)
            mutableState.value = mutableState.value.copy(
                status = VexPlayerBridgeStatus.CONNECTING,
                message = "Checking VEXPlayer bridge protocol…"
            )
            send(MSG_GET_PROTOCOL_INFO)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            spectrumSubscribed = false
            mutableSpectrumFrame.value = BridgeSpectrumFrame()
            mutableSpectrumError.value = null
            serviceMessenger = null
            mutableState.value = mutableState.value.copy(
                status = VexPlayerBridgeStatus.DISCONNECTED,
                message = "VEXPlayer disconnected. Standalone EQ remains available."
            )
        }

        override fun onBindingDied(name: ComponentName?) {
            spectrumSubscribed = false
            mutableSpectrumFrame.value = BridgeSpectrumFrame()
            mutableSpectrumError.value = null
            serviceMessenger = null
            mutableState.value = mutableState.value.copy(
                status = VexPlayerBridgeStatus.ERROR,
                message = "VEXPlayer bridge binding died. Reconnect to try again."
            )
        }

        override fun onNullBinding(name: ComponentName?) {
            spectrumSubscribed = false
            mutableSpectrumFrame.value = BridgeSpectrumFrame()
            mutableSpectrumError.value = null
            serviceMessenger = null
            mutableState.value = mutableState.value.copy(
                status = VexPlayerBridgeStatus.ERROR,
                message = "VEXPlayer did not expose the EQ bridge."
            )
        }
    }

    fun bind() {
        if (isBound || serviceMessenger != null) return
        mutableState.value = VexPlayerBridgeState(
            status = VexPlayerBridgeStatus.CONNECTING,
            message = "Connecting to VEXPlayer…"
        )
        val intent = Intent().setComponent(
            ComponentName(
                VEXPLAYER_PACKAGE,
                "$VEXPLAYER_PACKAGE.bridge.VexEqBridgeService"
            )
        )
        try {
            isBound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!isBound) fail("VEXPlayer is not installed or its bridge is unavailable.")
        } catch (error: SecurityException) {
            fail("VEXPlayer bridge permission denied. Both apps must use the same signing certificate.")
        } catch (error: RuntimeException) {
            fail(error.message ?: "Could not connect to VEXPlayer.")
        }
    }

    fun subscribeSpectrum() {
        if (spectrumSubscribed) return
        if (mutableState.value.status !in setOf(VexPlayerBridgeStatus.READY, VexPlayerBridgeStatus.CONNECTED)) return
        spectrumSubscribed = true
        send(MSG_SUBSCRIBE_SPECTRUM)
    }

    fun unsubscribeSpectrum() {
        if (!spectrumSubscribed) return
        send(MSG_UNSUBSCRIBE_SPECTRUM)
        spectrumSubscribed = false
        mutableSpectrumFrame.value = BridgeSpectrumFrame()
        mutableSpectrumError.value = null
    }

    fun acquireControl() {
        if (mutableState.value.status != VexPlayerBridgeStatus.READY) return
        send(MSG_ACQUIRE_CONTROL)
    }

    fun releaseControl() {
        if (mutableState.value.status != VexPlayerBridgeStatus.CONNECTED) return
        send(MSG_RELEASE_CONTROL)
        mutableState.value = mutableState.value.copy(
            status = VexPlayerBridgeStatus.READY,
            message = "VEXPlayer EQ control released."
        )
    }

    fun apply(config: VexPlayerBridgeConfig) {
        if (mutableState.value.status != VexPlayerBridgeStatus.CONNECTED) return
        if (!config.isValid()) {
            mutableState.value = mutableState.value.copy(message = "Invalid EQ configuration.")
            return
        }
        val data = Bundle().apply {
            putInt(KEY_PROTOCOL_VERSION, config.protocolVersion)
            putLong(KEY_CLIENT_REVISION, mutableState.value.hostRevision)
            putBoolean(KEY_ENABLED, config.enabled)
            putIntegerArrayList(KEY_GRAPHIC_LEVELS, ArrayList(config.graphicBandLevelsMb))
            putInt(KEY_BASS, config.bassStrength)
            putInt(KEY_STEREO_WIDTH, config.stereoWidth)
            putInt(KEY_PREAMP, config.preampMb)
            putBoolean(KEY_AUTO_HEADROOM, config.autoHeadroom)
            putBoolean(KEY_LIMITER, config.limiterEnabled)
            putBoolean(KEY_COMPRESSOR_ENABLED, config.compressorEnabled)
            putFloat(KEY_COMPRESSOR_THRESHOLD, config.compressorThresholdDb)
            putFloat(KEY_COMPRESSOR_RATIO, config.compressorRatio)
            putFloat(KEY_COMPRESSOR_ATTACK, config.compressorAttackMs)
            putFloat(KEY_COMPRESSOR_RELEASE, config.compressorReleaseMs)
            putBoolean(KEY_SOFT_CLIPPER_ENABLED, config.softClipperEnabled)
            putParcelableArrayList(KEY_PARAMETRIC_BANDS, ArrayList(config.parametricBands.map { band ->
                Bundle().apply {
                    putInt(KEY_FREQUENCY, band.frequencyHz)
                    putInt(KEY_GAIN, band.gainMb)
                    putFloat(KEY_Q, band.q)
                    putString(KEY_FILTER_TYPE, band.filterType)
                    putBoolean(KEY_BAND_ENABLED, band.enabled)
                }
            }))
        }
        send(MSG_APPLY_CONFIG, data)
    }

    private fun send(what: Int, data: Bundle = Bundle()) {
        val target = serviceMessenger ?: return
        try {
            target.send(Message.obtain(null, what).apply {
                this.data = data
                replyTo = incoming
            })
        } catch (error: RemoteException) {
            fail("VEXPlayer bridge connection was lost.")
        }
    }

    private fun handleResponse(message: Message) {
        when (message.what) {
            MSG_PROTOCOL_INFO -> {
                val version = message.data.getInt(KEY_PROTOCOL_VERSION, -1)
                if (version != PROTOCOL_VERSION) {
                    fail("Unsupported VEXPlayer bridge protocol: $version")
                } else {
                    send(MSG_GET_CURRENT_STATE)
                }
            }
            MSG_CURRENT_STATE -> acceptHostState(message.data, VexPlayerBridgeStatus.READY)
            MSG_CONTROL_ACQUIRED -> acceptHostState(message.data, VexPlayerBridgeStatus.CONNECTED)
            MSG_HOST_STATE -> acceptHostState(message.data, VexPlayerBridgeStatus.CONNECTED)
            MSG_SPECTRUM_FRAME -> acceptSpectrumFrame(message.data)
            MSG_SPECTRUM_ERROR -> mutableSpectrumError.value = message.data.getString(KEY_ERROR)
                ?: "VEXPlayer spectrum capture is unavailable."
            MSG_SPECTRUM_READY -> Unit
            MSG_RESULT -> {
                val data = message.data
                val revision = data.getLong(KEY_HOST_REVISION, mutableState.value.hostRevision)
                if (!data.getBoolean(KEY_ACCEPTED, false)) {
                    mutableState.value = mutableState.value.copy(
                        hostRevision = revision,
                        message = data.getString(KEY_ERROR) ?: "VEXPlayer rejected the EQ update."
                    )
                } else {
                    mutableState.value = mutableState.value.copy(
                        hostRevision = revision,
                        message = null
                    )
                }
            }
        }
    }

    private fun acceptSpectrumFrame(data: Bundle) {
        mutableSpectrumError.value = null
        val magnitudes = data.getFloatArray(KEY_SPECTRUM_MAGNITUDES)?.take(SPECTRUM_BANDS)
            ?.map { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }?.toFloatArray() ?: FloatArray(0)
        val peaks = data.getFloatArray(KEY_SPECTRUM_PEAKS)?.take(SPECTRUM_BANDS)
            ?.map { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }?.toFloatArray() ?: FloatArray(0)
        val waveform = data.getFloatArray(KEY_SPECTRUM_WAVEFORM)?.take(SPECTRUM_WAVE_POINTS)
            ?.map { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }?.toFloatArray() ?: FloatArray(0)
        val leftMagnitudes = data.getFloatArray(KEY_SPECTRUM_LEFT_MAGNITUDES)
            ?.take(SPECTRUM_BANDS)?.map { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
            ?.toFloatArray() ?: FloatArray(0)
        val rightMagnitudes = data.getFloatArray(KEY_SPECTRUM_RIGHT_MAGNITUDES)
            ?.take(SPECTRUM_BANDS)?.map { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
            ?.toFloatArray() ?: FloatArray(0)
        val phaseCorrelation = if (data.containsKey(KEY_SPECTRUM_PHASE_CORRELATION)) {
            data.getFloat(KEY_SPECTRUM_PHASE_CORRELATION).takeIf { it.isFinite() }?.coerceIn(-1f, 1f)
        } else null
        val stereoWidth = if (data.containsKey(KEY_SPECTRUM_STEREO_WIDTH)) {
            data.getFloat(KEY_SPECTRUM_STEREO_WIDTH).takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        } else null
        mutableSpectrumFrame.value = BridgeSpectrumFrame(
            magnitudes = magnitudes,
            peaks = peaks,
            waveform = waveform,
            leftMagnitudes = leftMagnitudes,
            rightMagnitudes = rightMagnitudes,
            phaseCorrelation = phaseCorrelation,
            stereoWidth = stereoWidth,
            bass = data.getFloat(KEY_SPECTRUM_BASS).coerceIn(0f, 1f),
            mid = data.getFloat(KEY_SPECTRUM_MID).coerceIn(0f, 1f),
            treble = data.getFloat(KEY_SPECTRUM_TREBLE).coerceIn(0f, 1f),
            beatPulse = data.getFloat(KEY_SPECTRUM_BEAT).coerceIn(0f, 1f),
            rms = data.getFloat(KEY_SPECTRUM_RMS).coerceIn(0f, 1f),
            timestampNanos = data.getLong(KEY_SPECTRUM_TIMESTAMP)
        )
    }

    private fun acceptHostState(data: Bundle, status: VexPlayerBridgeStatus) {
        val config = parseConfig(data)
        if (config == null) {
            fail("VEXPlayer returned an invalid EQ state.")
            return
        }
        mutableState.value = VexPlayerBridgeState(
            status = status,
            hostRevision = data.getLong(KEY_HOST_REVISION, 0L),
            config = config,
            message = if (status == VexPlayerBridgeStatus.CONNECTED) {
                "VEX EQ controls VEXPlayer's active DSP."
            } else {
                "VEXPlayer found. Connect to enable Advanced EQ."
            }
        )
    }

    private fun parseConfig(data: Bundle): VexPlayerBridgeConfig? = runCatching {
        val bands = data.getParcelableArrayList<Bundle>(KEY_PARAMETRIC_BANDS).orEmpty().map {
            BridgeParametricBand(
                frequencyHz = it.getInt(KEY_FREQUENCY),
                gainMb = it.getInt(KEY_GAIN),
                q = it.getFloat(KEY_Q),
                filterType = it.getString(KEY_FILTER_TYPE) ?: "BELL",
                enabled = it.getBoolean(KEY_BAND_ENABLED, true)
            )
        }
        VexPlayerBridgeConfig(
            protocolVersion = data.getInt(KEY_PROTOCOL_VERSION, -1),
            enabled = data.getBoolean(KEY_ENABLED),
            graphicBandLevelsMb = data.getIntegerArrayList(KEY_GRAPHIC_LEVELS)?.toList().orEmpty(),
            bassStrength = data.getInt(KEY_BASS),
            stereoWidth = data.getInt(KEY_STEREO_WIDTH),
            preampMb = data.getInt(KEY_PREAMP),
            autoHeadroom = data.getBoolean(KEY_AUTO_HEADROOM),
            limiterEnabled = data.getBoolean(KEY_LIMITER),
            compressorEnabled = data.getBoolean(KEY_COMPRESSOR_ENABLED),
            compressorThresholdDb = data.getFloat(KEY_COMPRESSOR_THRESHOLD, -18f),
            compressorRatio = data.getFloat(KEY_COMPRESSOR_RATIO, 4f),
            compressorAttackMs = data.getFloat(KEY_COMPRESSOR_ATTACK, 10f),
            compressorReleaseMs = data.getFloat(KEY_COMPRESSOR_RELEASE, 120f),
            softClipperEnabled = data.getBoolean(KEY_SOFT_CLIPPER_ENABLED),
            parametricBands = bands
        ).takeIf { it.isValid() }
    }.getOrNull()

    private fun fail(reason: String) {
        mutableState.value = mutableState.value.copy(
            status = VexPlayerBridgeStatus.ERROR,
            message = reason
        )
    }

    fun close() {
        unsubscribeSpectrum()
        if (mutableState.value.status == VexPlayerBridgeStatus.CONNECTED) {
            send(MSG_RELEASE_CONTROL)
        }
        if (isBound) {
            runCatching { appContext.unbindService(connection) }
        }
        isBound = false
        serviceMessenger = null
        mutableState.value = VexPlayerBridgeState()
    }

    companion object {
        private const val VEXPLAYER_PACKAGE = "com.vexmusic.player"
        private const val PROTOCOL_VERSION = 2

        private const val MSG_GET_PROTOCOL_INFO = 1
        private const val MSG_GET_CURRENT_STATE = 2
        private const val MSG_ACQUIRE_CONTROL = 3
        private const val MSG_RELEASE_CONTROL = 4
        private const val MSG_APPLY_CONFIG = 5
        private const val MSG_SUBSCRIBE_SPECTRUM = 6
        private const val MSG_UNSUBSCRIBE_SPECTRUM = 7
        private const val MSG_PROTOCOL_INFO = 101
        private const val MSG_CURRENT_STATE = 102
        private const val MSG_CONTROL_ACQUIRED = 103
        private const val MSG_HOST_STATE = 104
        private const val MSG_RESULT = 105
        private const val MSG_SPECTRUM_READY = 106
        private const val MSG_SPECTRUM_FRAME = 107
        private const val MSG_SPECTRUM_ERROR = 108

        private const val KEY_PROTOCOL_VERSION = "protocolVersion"
        private const val KEY_HOST_REVISION = "hostRevision"
        private const val KEY_CLIENT_REVISION = "clientRevision"
        private const val KEY_ACCEPTED = "accepted"
        private const val KEY_ERROR = "error"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_GRAPHIC_LEVELS = "graphicBandLevelsMb"
        private const val KEY_BASS = "bassStrength"
        private const val KEY_STEREO_WIDTH = "stereoWidth"
        private const val KEY_PREAMP = "preampMb"
        private const val KEY_AUTO_HEADROOM = "autoHeadroom"
        private const val KEY_LIMITER = "limiterEnabled"
        private const val KEY_COMPRESSOR_ENABLED = "compressorEnabled"
        private const val KEY_COMPRESSOR_THRESHOLD = "compressorThresholdDb"
        private const val KEY_COMPRESSOR_RATIO = "compressorRatio"
        private const val KEY_COMPRESSOR_ATTACK = "compressorAttackMs"
        private const val KEY_COMPRESSOR_RELEASE = "compressorReleaseMs"
        private const val KEY_SOFT_CLIPPER_ENABLED = "softClipperEnabled"
        private const val KEY_PARAMETRIC_BANDS = "parametricBands"
        private const val KEY_FREQUENCY = "frequencyHz"
        private const val KEY_GAIN = "gainMb"
        private const val KEY_Q = "q"
        private const val KEY_FILTER_TYPE = "filterType"
        private const val KEY_BAND_ENABLED = "bandEnabled"
        private const val KEY_SPECTRUM_MAGNITUDES = "spectrumMagnitudes"
        private const val KEY_SPECTRUM_PEAKS = "spectrumPeaks"
        private const val KEY_SPECTRUM_WAVEFORM = "spectrumWaveform"
        // Optional v2 extension: older VEXPlayer hosts simply omit these keys.
        private const val KEY_SPECTRUM_LEFT_MAGNITUDES = "spectrumLeftMagnitudes"
        private const val KEY_SPECTRUM_RIGHT_MAGNITUDES = "spectrumRightMagnitudes"
        private const val KEY_SPECTRUM_PHASE_CORRELATION = "spectrumPhaseCorrelation"
        private const val KEY_SPECTRUM_STEREO_WIDTH = "spectrumStereoWidth"
        private const val KEY_SPECTRUM_BASS = "spectrumBass"
        private const val KEY_SPECTRUM_MID = "spectrumMid"
        private const val KEY_SPECTRUM_TREBLE = "spectrumTreble"
        private const val KEY_SPECTRUM_BEAT = "spectrumBeat"
        private const val KEY_SPECTRUM_RMS = "spectrumRms"
        private const val KEY_SPECTRUM_TIMESTAMP = "spectrumTimestamp"
        private const val SPECTRUM_BANDS = 64
        private const val SPECTRUM_WAVE_POINTS = 160
    }
}
