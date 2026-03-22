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

import android.os.Handler
import kotlin.math.abs

class GestureStateMachine(
    private val handler: Handler,
    private val onGestureTriggered: () -> Unit
) {
    private enum class State { IDLE, WAIT_SECOND_TAP }
    private var state = State.IDLE
    private var firstTap: TapCandidate? = null

    var sensitivityMultiplier = 2.2f

    private val MAX_TAP_WINDOW_MS = 320L
    private val MIN_TAP_WINDOW_MS = 85L
    private val IDEAL_TAP_INTERVAL_MS = 170L
    private val RHYTHM_TOLERANCE_MS = 140L
    private val MAX_PEAK_RATIO = 5.0f
    private val MIN_SINGLE_TAP_SCORE = 2.15f

    private val timeoutRunnable = Runnable { reset() }

    private fun minCombinedScore(): Float {
        return when {
            sensitivityMultiplier <= 1.5f -> 3.80f  // high sensitivity
            sensitivityMultiplier >= 3.0f -> 4.40f  // low sensitivity
            else -> 4.20f                            // medium
        }
    }

    fun onTapCandidate(candidate: TapCandidate) {
        when (state) {
            State.IDLE -> armFirstTap(candidate)
            State.WAIT_SECOND_TAP -> {
                val first = firstTap ?: run {
                    armFirstTap(candidate)
                    return
                }

                val interval = candidate.detectedUptimeMs - first.detectedUptimeMs
                val minPeak = minOf(candidate.amplitude, first.amplitude).coerceAtLeast(0.001f)
                val peakRatio = maxOf(candidate.amplitude, first.amplitude) / minPeak
                val rhythmPenalty =
                    (abs(interval - IDEAL_TAP_INTERVAL_MS).toFloat() / RHYTHM_TOLERANCE_MS)
                        .coerceAtMost(1.25f)
                val combinedScore = first.score + candidate.score - rhythmPenalty

                if (interval in MIN_TAP_WINDOW_MS..MAX_TAP_WINDOW_MS &&
                    peakRatio <= MAX_PEAK_RATIO &&
                    combinedScore >= minCombinedScore()) {
                    handler.removeCallbacks(timeoutRunnable)
                    reset()
                    onGestureTriggered()
                } else {
                    armFirstTap(candidate)
                }
            }
        }
    }
    private fun armFirstTap(candidate: TapCandidate) {
        firstTap = candidate
        state = State.WAIT_SECOND_TAP
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, MAX_TAP_WINDOW_MS)
    }

    private fun reset() {
        state = State.IDLE
        firstTap = null
    }
}
