# VEXPlayer Advanced EQ Bridge — Protocol v1

## Status

This document describes the implemented Messenger contract on the feature branches. VEXPlayer remains the sole owner of playback DSP; VEX EQ is a remote control surface plus an optional read-only spectrum renderer.

VEXPlayer remains the sole owner of its Media3 PCM DSP chain. VEX EQ is an optional remote control surface. Never attach a second processor to VEXPlayer playback.

## Verified host facts

- VEXPlayer application ID: `com.vexmusic.player`
- Playback host: `PlaybackService` / Media3 `ExoPlayer`
- DSP host: `SoftwareEqualizerAudioProcessor.shared`
- Existing controls: 31 graphic bands, up to 8 parametric bands, bass enhancement, stereo width, preamp, auto headroom and limiter.
- VEXPlayer already works independently of VEX EQ.

## Transport and authentication

Use the explicit, signature-protected bound Android Service in VEXPlayer. Do not use implicit broadcasts, an unrestricted exported receiver, or an unauthenticated provider.

VEXPlayer should declare a custom signature permission:

```xml
<permission
    android:name="com.vexmusic.player.permission.CONTROL_EQ"
    android:protectionLevel="signature" />

<service
    android:name=".bridge.VexEqBridgeService"
    android:exported="true"
    android:permission="com.vexmusic.player.permission.CONTROL_EQ" />
```

VEX EQ declares `uses-permission` for that permission and binds using an explicit `ComponentName("com.vexmusic.player", "...VexEqBridgeService")`.

**Signing prerequisite:** Android signature permissions are granted only when both APKs are signed by the same signing certificate. Before shipping this bridge, confirm the actual release signing certificate used by both apps. Debug builds signed with different debug keys will not be able to use this production permission. Do not weaken the permission to `normal` or `dangerous` to make debug testing pass.

On the host side, also validate the calling UID/package relationship and reject unknown callers. Treat permission checks and caller validation as mandatory defense-in-depth.

## API shape

The current transport is Android `Messenger` over an explicit bound Service. Commands are protocol-info, current-state, acquire/release control, apply-config, subscribe/unsubscribe spectrum. Keep handlers fast. Validate and sanitize complete settings snapshots before mutating the live controller. Never perform disk I/O or audio-thread work in a Binder call.

## Data contract

Protocol v1 uses the fields represented by `VexPlayerBridgeConfig`:

| Field | Unit / constraints |
|---|---|
| `protocolVersion` | Must equal 1 |
| `enabled` | Boolean |
| `graphicBandLevelsMb` | Exactly 31 integer millibels; -1200..1200 |
| `bassStrength` | Integer 0..1000 |
| `stereoWidth` | Integer 0..1000 |
| `preampMb` | Integer -1200..600 |
| `autoHeadroom` | Boolean |
| `limiterEnabled` | Boolean |
| `parametricBands` | 0..8 entries; frequency 20..22000 Hz, gain -1200..1200 millibels, finite Q 0.3..10 |

Reject unknown protocol versions and malformed payloads. Do not silently truncate invalid PEQ entries or accept NaN/infinite values. Host-side validation remains authoritative even if the client validates first.

## State ownership and synchronization

- VEXPlayer's `EqualizerController` and persisted VEXPlayer settings are the canonical state while controlling VEXPlayer playback.
- VEX EQ sends a complete snapshot, not a partial patch, so stale values cannot accidentally survive a mode switch.
- The host returns an acknowledgement containing `accepted`, `hostRevision`, and the sanitized applied snapshot.
- Host-originated edits (including changes made in VEXPlayer's normal EQ screen) are sent to registered clients with the new `hostRevision`.
- VEX EQ must update its UI from the acknowledged/host snapshot rather than assuming its requested values were applied.
- Use monotonically increasing host revisions. Every apply request includes `clientRevision`, which must equal the latest host revision. Reject stale client revisions; never resolve conflicts by last-arrival-wins without revision checks.
- Disconnecting VEX EQ must not disable EQ, reset settings, or stop playback. VEXPlayer continues using its own last saved state.
- If the client reconnects, it first fetches host state and then reconciles. It must not blindly overwrite host state with an old local preset.


## Spectrum side channel

The same signature-protected service exposes a read-only analysis stream:

| Message | Code | Purpose |
|---|---:|---|
| `MSG_SUBSCRIBE_SPECTRUM` | 6 | Begin bounded spectrum frames |
| `MSG_UNSUBSCRIBE_SPECTRUM` | 7 | Stop the stream |
| `MSG_SPECTRUM_READY` | 106 | Subscription accepted |
| `MSG_SPECTRUM_FRAME` | 107 | One analysis frame |

Each frame contains 64 normalized logarithmic magnitudes, 64 peak values, up to 160 waveform samples, bass/mid/treble energy, beat pulse, RMS and a monotonic timestamp. VEXPlayer sends frames at up to 15 FPS. VEX EQ sanitizes array lengths and finite numeric values before rendering.

The host reuses its existing `VisualizerController` through owner leases. The Spectrum screen and VEX EQ can both observe the stream without one consumer stopping capture for the other. VEX EQ does not create an `android.media.audiofx.Visualizer` instance and does not send frames through the PCM/DSP chain. The spectrum stream is analysis-only and must never be interpreted as a second audio processor.

The host's Android audio-visualization permission may need to be granted in VEXPlayer before frames are available. If capture is unavailable, show an explicit permission/capture state; do not fall back to microphone recording or system loopback.

## Advanced-mode lifecycle

1. VEX EQ checks whether `com.vexmusic.player` is installed and binds explicitly.
2. Confirm protocol v1, then fetch host state.
3. Acquire EQ control only when the user enables Advanced Mode.
4. Send complete, validated snapshots when the user changes settings.
5. Subscribe to spectrum frames only while Spectrum Studio is visible; this does not require EQ control ownership.
6. Reflect host acknowledgements and host-originated updates.
7. Release spectrum subscription and EQ control independently. On binder death/disconnect, show disconnected state and keep standalone mode available.

## Failure behavior

- Missing app, denied signature permission, incompatible protocol, binder death, malformed data, or rejected revision must produce a clear disconnected/error state.
- Never fall back to microphone capture or audio loopback.
- Never claim system-wide audio control from this bridge. It controls VEXPlayer's own playback pipeline only.
- VEX EQ standalone/global compatibility features remain separate from this integration.

## Required tests before release

- Same-signature install: allowed client binds and reads protocol info.
- Different-signature install: binding is denied.
- Unknown package/UID: rejected.
- Invalid version, band count, out-of-range gain, NaN Q: rejected without changing host state.
- Successful apply: controller state and PCM processor settings match the acknowledged snapshot.
- VEXPlayer UI edit while VEX EQ is connected: client receives the new host revision.
- VEX EQ process death/uninstall: playback and host EQ continue normally.
- Reconnect after offline local edits: host state is fetched before any reconciliation.
- Verify no second audio processor is attached to VEXPlayer.

## Implementation order

1. Confirm the shared release signing-certificate strategy for both apps.
2. Review the VEXPlayer host service and VEX EQ Messenger client feature branches.
3. Complete signing configuration and same-certificate install tests.
4. Add instrumented IPC, revision-conflict and process-death tests.
5. Run on-device audio tests with VEXPlayer playing, including disconnect/reconnect.
