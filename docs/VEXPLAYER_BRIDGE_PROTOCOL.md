# VEXPlayer Advanced EQ Bridge — Protocol v2

## Status and ownership

VEXPlayer is the only owner of its Media3 playback PCM/DSP chain. VEX EQ is a remote control surface and optional read-only analyzer. Never attach a second processor to VEXPlayer playback.

- VEXPlayer application ID: `com.vexmusic.player`
- Playback host: `PlaybackService` / Media3 `ExoPlayer`
- DSP host: `SoftwareEqualizerAudioProcessor.shared`
- VEXPlayer remains fully functional when VEX EQ is absent or disconnected.

## Transport and authentication

The bridge uses an explicit, signature-protected bound Android Service and Android `Messenger`. Do not replace it with implicit broadcasts, an unrestricted exported receiver, or an unauthenticated provider.

The host service must require `com.vexmusic.player.permission.CONTROL_EQ` with `protectionLevel="signature"`. VEX EQ declares that permission and binds explicitly to the VEXPlayer package. The host must additionally validate the calling UID/package relationship.

**Release prerequisite:** both APKs must use the same release signing certificate. Different debug signing keys will not satisfy the signature permission. Do not weaken the permission to make debug builds connect.

## Message lifecycle

The client uses protocol-info, current-state, acquire/release control, apply-config, and subscribe/unsubscribe spectrum messages. The client accepts protocol version 2 only.

1. Bind explicitly and request protocol information.
2. Fetch the current host snapshot.
3. Acquire EQ control only after the user enables Advanced Mode.
4. Send complete, validated settings snapshots while control is acquired.
5. Subscribe to spectrum only while Spectrum Studio is visible. Spectrum subscription is independent of EQ control ownership.
6. Release spectrum and EQ control independently.
7. On disconnect, keep standalone configuration available; VEXPlayer playback and its last saved DSP state continue unaffected.

## Protocol v2 settings contract

All numeric values must be finite and within the ranges below. The host remains authoritative and must validate incoming data before applying it.

| Field | Unit / constraints |
|---|---|
| `protocolVersion` | Exactly 2 |
| `enabled` | Boolean |
| `graphicBandLevelsMb` | Exactly 31 integer millibels, -1200..1200 |
| `bassStrength` | Integer 0..1000 |
| `stereoWidth` | Integer 0..1000 |
| `preampMb` | Integer -1200..600 |
| `autoHeadroom` | Boolean |
| `limiterEnabled` | Boolean |
| `compressorEnabled` | Boolean |
| `compressorThresholdDb` | Finite float, -36..0 dB |
| `compressorRatio` | Finite float, 1..20 |
| `compressorAttackMs` | Finite float, 1..100 ms |
| `compressorReleaseMs` | Finite float, 10..1000 ms |
| `softClipperEnabled` | Boolean |
| `parametricBands` | 0..16 entries |

Each parametric band contains frequency (20..22000 Hz), gain (-1200..1200 millibels), finite Q (0.3..10), filter type (`BELL`, `LOW_SHELF`, `HIGH_SHELF`, `NOTCH`, `HIGH_PASS`, `LOW_PASS`) and enabled/bypass state.

Reject unknown protocol versions, malformed arrays, invalid filter names, out-of-range values, and NaN/infinity. Never silently accept malformed PEQ entries.

## State and revision handling

- VEXPlayer's controller and persisted settings are canonical while controlling VEXPlayer playback.
- VEX EQ sends a complete settings snapshot, not a partial patch.
- Apply requests include the latest known host revision. The host rejects stale revisions rather than applying last-arrival-wins updates.
- A successful result reports acceptance and the resulting host revision. A rejected result reports an error and the current revision.
- The client must reflect host-originated state and acknowledgements; it must not assume a requested value was applied merely because it was sent.
- Reconnecting clients fetch host state before reconciling local presets. Never blindly overwrite the host with an old local snapshot.
- Disconnecting VEX EQ must not disable EQ, reset settings, or stop playback.

## Read-only spectrum side channel

The bridge exposes bounded spectrum frames independently of the PCM/DSP path:

| Message | Code | Purpose |
|---|---:|---|
| `MSG_SUBSCRIBE_SPECTRUM` | 6 | Begin spectrum frames |
| `MSG_UNSUBSCRIBE_SPECTRUM` | 7 | Stop spectrum frames |
| `MSG_SPECTRUM_READY` | 106 | Subscription accepted |
| `MSG_SPECTRUM_FRAME` | 107 | One analysis frame |
| `MSG_SPECTRUM_ERROR` | 108 | Capture/subscription error |

A frame contains 64 normalized logarithmic magnitudes, 64 peak values, up to 160 waveform samples, bass/mid/treble energy, beat pulse, RMS and a monotonic timestamp. The host targets no more than 15 frames per second. The client bounds array sizes and sanitizes non-finite values before rendering.

The host reuses its existing visualizer capture with consumer leases. VEX EQ must not create a second `android.media.audiofx.Visualizer`, feed analysis frames into the PCM chain, or fall back to microphone/system-loopback capture. If capture is unavailable, show the host-provided error state.

**Stereo/phase limitation:** the current spectrum contract is mono-analysis data. It does not contain separate left/right samples, channel correlation, or phase measurements. VEX EQ must not display fabricated stereo meters or phase correlation. Those require a future host protocol extension backed by channel-separated analysis from VEXPlayer.

## Optional stereo-analysis extension (backward compatible v2 keys)

The VEX EQ client accepts these optional spectrum Bundle fields without requiring a protocol-version bump:

| Key | Type | Meaning |
|---|---|---|
| `spectrumLeftMagnitudes` | float[64] | Normalized logarithmic left-channel FFT magnitudes |
| `spectrumRightMagnitudes` | float[64] | Normalized logarithmic right-channel FFT magnitudes |
| `spectrumPhaseCorrelation` | finite float [-1, 1] | Wideband L/R normalized cross-correlation |

If the host omits either channel array, the UI reports stereo analysis as unavailable. It must never duplicate the mono spectrum and label it as L/R. Phase correlation is shown only when the host supplies a finite value. Interpret +1 as in-phase, 0 as uncorrelated, and -1 as inverted; it is a wideband correlation indicator, not a frequency-dependent phase response.

The VEXPlayer host must calculate these values from the actual left and right playback channels before emitting the optional keys. Until that host-side implementation and device validation exist, these panels remain explicitly unavailable. No microphone, loopback, or second DSP processor is permitted.

## Failure and release validation

Missing VEXPlayer, denied signature permission, incompatible protocol, binder death, malformed data, stale revisions, or rejected settings must produce a clear error state. VEXPlayer playback must continue using its own saved DSP state.

Before release, validate:
- Same-signature APKs can bind; different-signature APKs are denied.
- Unknown package/UID callers are rejected.
- Invalid protocol, band counts, filter names, ranges, NaN and infinity are rejected without mutating host state.
- All 16 PEQ bands and dynamics settings round-trip correctly.
- A successful apply updates the host controller and PCM processor.
- Host-originated edits are reflected by the client with the new revision.
- VEX EQ process death/uninstall does not interrupt playback.
- Reconnect fetches host state before local reconciliation.
- No second processor is attached to VEXPlayer playback.
