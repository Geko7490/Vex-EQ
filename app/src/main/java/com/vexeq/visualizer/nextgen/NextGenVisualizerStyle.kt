package com.vexeq.visualizer.nextgen

/** Rendering modes inspired by jeffshee/NextGenVisualizer's FFT and waveform painters. */
enum class NextGenVisualizerStyle(val label: String) {
    FFT_BARS("Bars"),
    FFT_LINE("Line"),
    FFT_WAVE("Wave"),
    FFT_WAVE_RGB("RGB Wave"),
    FFT_CIRCLE("Circle"),
    FFT_CIRCLE_WAVE("Circle Wave"),
    FFT_CIRCLE_WAVE_RGB("RGB Circle")
}
