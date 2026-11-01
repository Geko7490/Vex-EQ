# VEX EQ

Standalone Android system-audio enhancement companion with a dedicated VEXPlayer integration.

## Project status

The repository is being initialized. The first implementation milestone is the Android application foundation and a truthful device compatibility report. No global/system-wide processing capability is assumed until it is verified on a device.

## Product direction

- Native Android application (Kotlin, Jetpack Compose).
- Compatibility engine that reports platform audio-effect availability without claiming unsupported global processing.
- Independent DSP engine for VEXPlayer integration and future supported audio sessions.
- VEXPlayer remains fully functional when VEX EQ is absent or disconnected.
- One EQ control surface: once the integration is verified and enabled, VEXPlayer's duplicate EQ controls can be retired while its proven PCM processing pipeline remains available as the processing backend.
- Low / Mid / High performance profiles, bilingual Turkish / English UI, profile management and diagnostics.

## Android constraints

Android's AudioEffect APIs are primarily attached to an application's audio session. Attaching insert effects to the global output mix with session ID 0 is deprecated and device-dependent. VEX EQ will report compatibility honestly and will not capture/reprocess microphone audio as a substitute for system audio processing.

## Development

The Android project and CI workflow are being added on a feature branch. See the repository's Actions tab for build results once the workflow is present.
