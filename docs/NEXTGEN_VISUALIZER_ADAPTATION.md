# NextGenVisualizer adaptation

VEX EQ adapts the FFT bar and FFT wave painter approach from:

- Project: https://github.com/jeffshee/NextGenVisualizer
- Author/repository: jeffshee
- License: Apache License 2.0
- Upstream license copy: `licenses/NextGenVisualizer-APACHE-2.0.txt`

The adapted VEX files are under `app/src/main/java/com/vexeq/visualizer/nextgen/`. They retain the upstream painter concepts (gravity-smoothed FFT bars, interpolated FFT wave, mirrored drawing) while replacing the original `VisualizerHelper` capture dependency with immutable `BridgeSpectrumFrame` input from VEXPlayer.

This is intentional: VEXPlayer owns playback and its existing visualizer capture; VEX EQ only renders received frames. No second audio capture effect or PCM processor is created by the renderer.

Changes from upstream:
- Updated package and host view for VEX EQ.
- Replaced the capture helper dependency with VEXPlayer bridge frame data.
- Removed the fixed 44.1 kHz FFT-bin mapping from the rendering layer; the host supplies already grouped logarithmic magnitudes.
- Added selectable FFT Bars, FFT Line, FFT Wave, RGB Wave, FFT Circle, FFT Circle Wave, and RGB Circle render modes.
- Added optional Beat Pulse, Rotate, Glitch, Shake, and Peak Hold visual modifiers.
- Added horizontally scrollable style/effect selectors for narrow phone screens.
- Circular stereo rendering uses actual left/right bridge magnitudes when available; otherwise it labels no fake stereo source and falls back to the mono frame.
- Added Apache 2.0 attribution and retained the license text.
