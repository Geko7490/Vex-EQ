# VEXPlayer Advanced EQ Bridge — Protocol v1

## Status

This document defines the integration contract only. The current VEX-EQ repository contains a transport-neutral DTO; it does **not** yet contain a working Android IPC client, and VEXPlayer does **not** yet expose the receiver described here.

VEXPlayer remains the sole owner of its Media3 PCM DSP chain. VEX EQ is an optional remote control surface. Never attach a second processor to VEXPlayer playback.

## Verified host facts

- VEXPlayer application ID: `com.vexmusic.player`
- Playback host: `PlaybackService` / Media3 `ExoPlayer`
- DSP host: `SoftwareEqualizerAudioProcessor.shared`
- Existing controls: 31 graphic bands, up to 8 parametric bands, bass enhancement, stereo width, preamp, auto headroom and limiter.
- VEXPlayer already works independently of VEX EQ.

## Transport and authentication

Use an explicit bound Android Service in VEXPlayer. Do not use implicit broadcasts, an unrestricted exported receiver, or an unauthenticated provider.

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

Expose a small Binder/AIDL interface with these operations:

- `getProtocolInfo() -> BridgeInfo`
- `getCurrentState() -> BridgeState`
- `applyConfig(config, clientRevision) -> ApplyResult`
- `registerCallback(callback)`
- `unregisterCallback(callback)`

Keep Binder methods fast. Validate and sanitize the complete payload before mutating the live controller. Never perform disk I/O or audio-thread work in a Binder call.

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

## Advanced-mode lifecycle

1. VEX EQ checks whether `com.vexmusic.player` is installed and whether the bridge permission is granted.
2. Bind explicitly and call `getProtocolInfo()`.
3. Confirm a supported protocol version, then fetch host state.
4. Enter advanced mode only after the host confirms the connection.
5. Send complete, validated snapshots when the user changes settings.
6. Reflect host acknowledgements and host-originated updates.
7. On binder death/disconnect, show disconnected state and keep VEX EQ's standalone mode available.

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
