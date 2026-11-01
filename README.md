# VEX EQ

Standalone Android audio equalizer and DSP companion with an optional advanced VEXPlayer integration.

## Project status

The native Android foundation, compatibility dashboard, PCM DSP core, interactive 31-band graphic EQ and 8-band parametric EQ controls, offline local preset save/load/delete/import/export, automatic last-used settings restore,, plus Low / Mid / High workload profiles, and automated build/test workflow are in place. The DSP core is implemented and unit-tested as a reusable PCM processing component. It is **not yet connected to Android's global output or VEXPlayer playback**, so the current UI clearly labels playback-host integration as pending.

## Product direction

- Native Android application (Kotlin, Jetpack Compose).
- 31-band graphic EQ with per-band gain control (-12 dB to +12 dB).
- Reusable float-PCM DSP core with up to 8 parametric peaking bands, Bass Boost, Stereo Width, Preamp, automatic headroom, output limiter, and Low / Mid / High workload profiles that reduce the active graphic-filter count and parametric-band limit. DSP settings are published as fresh processing states so the audio processing thread does not wait for settings locks.
- Compatibility engine that reports platform audio-effect availability without claiming unsupported global processing.
- VEXPlayer remains fully functional when VEX EQ is absent or disconnected.
- VEXPlayer's built-in EQ and DSP remain in the app. VEX EQ adds an optional advanced VEXPlayer mode with synchronized controls and profiles; the integration must prevent duplicate processing rather than remove the player's native EQ.
- Planned: persistent user presets, output-device profiles, per-app support where Android permits, Low / Mid / High performance modes, bilingual Turkish / English UI, and diagnostics.

## Android constraints

Android's AudioEffect APIs are primarily attached to an application's audio session. Attaching insert effects to the global output mix with session ID 0 is deprecated and device-dependent. A registered effect descriptor is not proof that system-wide processing works. VEX EQ reports compatibility honestly and does not capture/reprocess microphone audio as a substitute for system audio processing.

## DSP core contract

`AudioDspEngine` accepts interleaved normalized Float PCM buffers and processes them in-place or into a separate output buffer. Configure it with the host sample rate and channel count, then call `process()` on the audio pipeline's processing thread. Update settings from the host's control thread between audio blocks. Once configured, processing reuses its channel scratch buffers and filter state.

The current app creates the DSP engine for UI configuration only. A supported Android audio-session host and the VEXPlayer bridge are separate integration milestones; no system-wide or external-app audio effect is claimed yet.

## Development

Work is performed on feature branches and proposed through pull requests. GitHub Actions runs Android lint, unit tests, debug/release APK assembly, and release AAB assembly. See the repository's Actions tab for the latest CI result.

## VEXPlayer advanced-mode integration

The VEXPlayer repository has been inspected. Its Android application ID is `com.vexmusic.player`, and it already owns a Media3 `SoftwareEqualizerAudioProcessor` inside `PlaybackService`. Its controller exposes the same 31 graphic bands, up to 8 parametric bands, Bass Boost, stereo width, preamp, automatic headroom, and limiter controls.

**Integration ownership rule:** VEXPlayer remains the playback host and owns the only PCM DSP stage for its own playback. VEX EQ's advanced mode will send a versioned settings payload to VEXPlayer; it will not run `AudioDspEngine` on top of VEXPlayer's processor. The VEXPlayer app's ordinary EQ screen remains available and is synchronized through the same controller/settings state.

The first transport-neutral contract is implemented in `VexPlayerBridgeConfig`:
- Protocol version is explicit and unknown versions are rejected.
- EQ gain values convert from dB to VEXPlayer's integer millibel units (dB × 100).
- Bass and stereo width convert from 0..1 to 0..1000.
- Parametric band values convert to VEXPlayer's frequency/gain/Q representation.
- Payload validation checks band counts and supported ranges before a future transport can apply it.

The transport and VEXPlayer-side receiver are **not yet implemented**. Before enabling cross-app writes, both apps need a secure authenticated IPC contract, request/acknowledgement flow, conflict resolution when the user edits EQ in either app, and a shared release-signing strategy or another verified caller-authentication mechanism. Until then, compatibility scanning only detects the installed package and does not modify it.
