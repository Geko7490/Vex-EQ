# VEX EQ

Standalone Android audio equalizer and DSP companion with an optional advanced VEXPlayer integration.

## Project status

The native Android foundation, compatibility dashboard, reusable PCM DSP core, interactive 31-band graphic EQ and up-to-8-band parametric EQ controls, offline preset management, last-used settings restore, Low / Mid / High workload profiles, and automated build/test workflow are implemented.

The DSP core is implemented and unit-tested as a reusable PCM processing component. It is **not yet connected to Android's global output or VEXPlayer playback**, so the current UI clearly labels playback-host integration as pending.

## Product direction

- Native Android application (Kotlin, Jetpack Compose).
- 31-band graphic EQ with per-band gain control (-12 dB to +12 dB).
- Up to 8 parametric peaking bands, Bass Boost, Stereo Width, Preamp, automatic headroom, and output limiter.
- Low / Mid / High workload profiles that reduce the active graphic-filter count and parametric-band limit while preserving the full 31-band control curve.
- Offline preset save/load/delete/import/export and automatic last-used settings restore.
- Compatibility engine that reports platform audio-effect availability without claiming unsupported global processing.
- Turkish / English UI planned.
- VEXPlayer remains fully functional when VEX EQ is absent or disconnected.

## Android constraints

Android's AudioEffect APIs are primarily attached to an application's audio session. Attaching insert effects to the global output mix with session ID 0 is deprecated and device-dependent. A registered effect descriptor is not proof that system-wide processing works. VEX EQ reports compatibility honestly and does not capture/reprocess microphone audio as a substitute for system audio processing.

## DSP core contract

`AudioDspEngine` accepts interleaved normalized Float PCM buffers and processes them through its configured filter chain. Configure it with the host sample rate and channel count, then call `process()` on the audio pipeline's processing thread. Settings are published as immutable processing states so the audio processing thread does not wait for control-thread settings locks.

The current app creates the DSP engine for UI configuration only. A supported Android audio-session host and the VEXPlayer bridge are separate integration milestones; no system-wide or external-app audio effect is claimed yet.

## VEXPlayer advanced-mode integration

The VEXPlayer repository has been inspected. Its Android application ID is `com.vexmusic.player`, and it already owns a Media3 `SoftwareEqualizerAudioProcessor` inside `PlaybackService`. Its controller exposes the same 31 graphic bands, up to 8 parametric bands, Bass Boost, stereo width, preamp, automatic headroom, and limiter controls.

**Integration ownership rule:** VEXPlayer remains the playback host and owns the only PCM DSP stage for its own playback. VEX EQ's advanced mode will send a versioned settings payload to VEXPlayer; it will not run `AudioDspEngine` on top of VEXPlayer's processor. The VEXPlayer app's ordinary EQ screen remains available and must stay synchronized through the same controller/settings state.

The transport-neutral contract is implemented in `VexPlayerBridgeConfig`:
- Protocol version is explicit and unknown versions are rejected.
- EQ gain values convert from dB to VEXPlayer's integer millibel units (dB × 100).
- Bass and stereo width convert from 0..1 to 0..1000.
- Parametric band values convert to VEXPlayer's frequency/gain/Q representation.
- Payload validation checks band counts and supported ranges before a future transport can apply it.

The transport and VEXPlayer-side receiver are **not yet implemented**. The security, synchronization, and lifecycle requirements are documented in [VEXPlayer Bridge Protocol v1](docs/VEXPLAYER_BRIDGE_PROTOCOL.md). In particular, the proposed signature-protected Binder service requires both apps to use the same release signing certificate. Confirm that signing strategy before implementing cross-app writes.

## VEXPlayer Advanced EQ integration

VEX EQ can connect to VEXPlayer's existing Media3 PCM DSP through the authenticated Messenger/Binder bridge. When Advanced Mode is active, VEXPlayer remains the only DSP host and its native EQ controls are locked; playback is not processed twice.

**Signing requirement:** Android's signature permission is granted only when both apps are signed with the same certificate. Configure the same `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` GitHub Actions secrets in both repositories. If these secrets are absent, CI still builds an unsigned release artifact, but cross-app Advanced Mode cannot bind successfully. Use same-certificate release builds for device integration tests.

## Development

Work is performed on feature branches and proposed through pull requests. GitHub Actions runs Android lint, unit tests, debug/release APK assembly, and release AAB assembly. See the repository's Actions tab for the latest CI result.
