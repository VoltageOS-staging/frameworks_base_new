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
import android.os.SystemClock

class GestureStateMachine(
    private val handler: Handler,
    private val onGestureTriggered: () -> Unit
) {
    private enum class State { IDLE, WAIT_SECOND_TAP }
    private var state = State.IDLE
    private var firstTapTime = 0L
    private var firstTapPeak = 0f
    private val MAX_TAP_WINDOW_MS = 400L
    private val MIN_TAP_WINDOW_MS = 60L
    private val timeoutRunnable = Runnable { state = State.IDLE }

    fun onSingleTap(peakMagnitude: Float) {
        val now = SystemClock.uptimeMillis()
        when (state) {
            State.IDLE -> {
                state = State.WAIT_SECOND_TAP
                firstTapTime = now
                firstTapPeak = peakMagnitude
                handler.postDelayed(timeoutRunnable, MAX_TAP_WINDOW_MS)
            }
            State.WAIT_SECOND_TAP -> {
                val minPeak = minOf(peakMagnitude, firstTapPeak).coerceAtLeast(0.001f)
                val peakRatio = maxOf(peakMagnitude, firstTapPeak) / minPeak
                
                if ((now - firstTapTime) in MIN_TAP_WINDOW_MS..MAX_TAP_WINDOW_MS && peakRatio <= 8.0f) {
                    handler.removeCallbacks(timeoutRunnable)
                    state = State.IDLE
                    onGestureTriggered()
                } else {
                    firstTapTime = now
                    firstTapPeak = peakMagnitude
                    handler.removeCallbacks(timeoutRunnable)
                    handler.postDelayed(timeoutRunnable, MAX_TAP_WINDOW_MS)
                }
            }
        }
    }
}
