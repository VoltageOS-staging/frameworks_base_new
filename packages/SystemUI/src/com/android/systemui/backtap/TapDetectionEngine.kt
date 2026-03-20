/*
 * Copyright 2026 (C) VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.backtap

import android.hardware.SensorEvent
import kotlin.math.abs

class TapDetectionEngine(
    private val onTapDetected: (Float) -> Unit
) {
    private val resampler = Resampler(intervalNs = 2_400_000L)
    
    private val HPF_PARA = 0.05f
    private var hpfLastIn = 0f
    private var hpfLastOut = 0f

    private var slopePrevZ = 0f

    val positivePeakDetector = PeakDetector(windowSize = 64)
    val negativePeakDetector = PeakDetector(windowSize = 64)
    
    var gyroEnergySq = 0f
    private var noiseFloor = 0f
    var sensitivityMultiplier = 4.5f

    private val COOLDOWN_NS = 80_000_000L // 80ms, allows GestureStateMachine to enforce the 60ms min window
    private var lastTapNs = 0L

    fun updateSensitivity(level: Int) { 
        sensitivityMultiplier = when (level) {
            0 -> 5.0f
            2 -> 1.5f
            else -> 3.0f
        }
    }

    fun processSensorEvent(event: SensorEvent) {
        resampler.update(
            event.values[0], event.values[1], event.values[2], event.timestamp
        ) { s -> processResampledSample(s) }
     }

    private fun processResampledSample(s: Resampler.Sample) {
        val dtSec = 2_400_000f / 1_000_000_000f

        val dz = (s.z - slopePrevZ) / dtSec
        slopePrevZ = s.z

        val hpfOut = HPF_PARA * (hpfLastOut + dz - hpfLastIn)
        hpfLastIn = dz
        hpfLastOut = hpfOut

        if (abs(hpfOut) < noiseFloor * 2f || noiseFloor == 0f) {
            noiseFloor = 0.95f * noiseFloor + 0.05f * abs(hpfOut)
        }
        val threshold = (noiseFloor * sensitivityMultiplier).coerceIn(0.005f, 0.5f)
        positivePeakDetector.minNoiseTolerate = threshold
        negativePeakDetector.minNoiseTolerate = threshold * 0.6f

        positivePeakDetector.update(hpfOut)
        negativePeakDetector.update(-hpfOut)

        recognizeTap(s.tNs)
    }

    private fun recognizeTap(tNs: Long) {
        val posId = positivePeakDetector.peakId
        val negId = negativePeakDetector.peakId - posId

        if (posId != 4) return
        if (negId <= 0 || negId > 5) return 

        if (gyroEnergySq > 8.0f) return

        if (tNs - lastTapNs < COOLDOWN_NS) return
        lastTapNs = tNs

        onTapDetected(positivePeakDetector.amplitude)
    }

    fun reset() {
        resampler.reset()
        positivePeakDetector.reset()
        negativePeakDetector.reset()
        hpfLastIn = 0f; hpfLastOut = 0f; slopePrevZ = 0f
        noiseFloor = 0f; lastTapNs = 0L
        gyroEnergySq = 0f
    }
}
