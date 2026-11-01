package com.vexeq.visualizer.nextgen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.vexeq.bridge.BridgeSpectrumFrame

/**
 * Lightweight host for the adapted NextGenVisualizer painters.
 * Frames arrive from VEXPlayer; this view never creates an audio capture or DSP effect.
 */
class NextGenSpectrumView(context: Context) : View(context) {
    private val bars = FftBar(
        paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(168, 85, 247)
            style = Paint.Style.FILL
        },
        num = 64,
        side = "ab",
        gapX = 2f,
        ampR = 100f
    )
    private val wave = FftWave(
        paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
        },
        num = 96,
        side = "a",
        ampR = 72f
    )

    private var frame = BridgeSpectrumFrame()
    private var showBars = true
    private var showWave = true

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isOpaque = false
    }

    fun submitFrame(frame: BridgeSpectrumFrame, bars: Boolean, wave: Boolean) {
        this.frame = frame
        showBars = bars
        showWave = wave
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frame.magnitudes.isEmpty()) return
        if (showBars) {
            bars.calc(frame)
            bars.draw(canvas, frame)
        }
        if (showWave) {
            wave.calc(frame)
            wave.draw(canvas, frame)
        }
    }
}
