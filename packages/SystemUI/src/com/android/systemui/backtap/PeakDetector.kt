/*
 * Copyright 2026 (C) VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.android.systemui.backtap

class PeakDetector(
    var minNoiseTolerate: Float = 0.03f,
    var windowSize: Int = 64
) {
    var peakId: Int = -1
    var amplitude: Float = 0f
    var amplitudeReference: Float = 0f
    var gotNewHighValue: Boolean = false
    var numberPeak: Int = 0

    private val noiseTolerate: Float
        get() = maxOf(minNoiseTolerate, amplitude / 5f)

    fun update(z: Float) {
        peakId--
        if (peakId < 0) reset()

        val delta = amplitudeReference - z
        if (delta < 0f) {
            amplitudeReference = z
            gotNewHighValue = true
            if (z >= noiseTolerate) {
                peakId = windowSize - 1
                amplitude = z
            }
        } else if (delta > noiseTolerate) {
            amplitudeReference = z
            if (gotNewHighValue) numberPeak++
            gotNewHighValue = false
        }
    }

    fun reset() {
        amplitude = 0f
        amplitudeReference = 0f
        numberPeak = 0
        peakId = 0
    }
}
