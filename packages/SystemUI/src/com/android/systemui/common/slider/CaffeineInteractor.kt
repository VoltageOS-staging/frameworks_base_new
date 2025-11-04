/*
 * Copyright (C) 2025 VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.common.slider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.CountDownTimer
import android.os.PowerManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlin.math.roundToInt

class CaffeineInteractor(
    private val context: Context,
    private val powerManager: PowerManager
) : LevelSliderInteractor {

    companion object {
        private val DURATIONS_MINUTES = listOf(0, 5, 15, 30, 60, 120, -1)
        private const val INFINITE_INDEX = 6
    }

    private val wakeLock = powerManager.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
        "CaffeineInteractor"
    )

    private val _stateFlow = MutableStateFlow(getCurrentLevel())
    private val _remainingSeconds = MutableStateFlow(0)
    private val _labelFlow = MutableStateFlow("Caffeine")
    private var countdownTimer: CountDownTimer? = null
    private var currentDurationIndex = 0

    override val level: Flow<Float> = callbackFlow {
        trySend(_stateFlow.value)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    stopCaffeine()
                    trySend(0f)
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        context.registerReceiver(receiver, filter)

        val job = _stateFlow.collect { trySend(it) }

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }.distinctUntilChanged()

    override fun getCurrentLevel(): Float {
        return if (wakeLock.isHeld) {
            currentDurationIndex / (DURATIONS_MINUTES.size - 1).toFloat()
        } else {
            0f
        }
    }

    override fun setLevel(level: Float) {
        val index = (level * (DURATIONS_MINUTES.size - 1)).roundToInt()
            .coerceIn(0, DURATIONS_MINUTES.size - 1)
        
        currentDurationIndex = index
        val minutes = DURATIONS_MINUTES[index]

        if (minutes == 0) {
            stopCaffeine()
        } else {
            startCaffeine(minutes)
        }

        _stateFlow.value = level
    }

    private fun startCaffeine(minutes: Int) {
        stopCountdown()

        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }

        if (minutes == -1) {
            _remainingSeconds.value = -1
            _labelFlow.value = "Caffeine • ∞"
            return
        }

        val durationMillis = minutes * 60 * 1000L
        _remainingSeconds.value = minutes * 60
        
        countdownTimer = object : CountDownTimer(durationMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                _remainingSeconds.value = seconds
                _labelFlow.value = formatCountdown(seconds)
            }

            override fun onFinish() {
                stopCaffeine()
            }
        }.start()
        
        _labelFlow.value = formatCountdown(minutes * 60)
    }

    private fun stopCaffeine() {
        stopCountdown()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        currentDurationIndex = 0
        _stateFlow.value = 0f
        _labelFlow.value = "Caffeine"
    }

    private fun stopCountdown() {
        countdownTimer?.cancel()
        countdownTimer = null
        _remainingSeconds.value = 0
    }

    private fun formatCountdown(seconds: Int): String {
        if (seconds == -1) return "Caffeine • ∞"
        val hours = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return if (hours > 0) {
            String.format("Caffeine • %d:%02d:%02d", hours, mins, secs)
        } else {
            String.format("Caffeine • %02d:%02d", mins, secs)
        }
    }

    @Composable
    override fun getIcon(level: Float): ImageVector {
        return if (level > 0f) Icons.Filled.LocalCafe else Icons.Filled.Coffee
    }

    @Composable
    override fun getLabel(level: Float): String {
        return _labelFlow.collectAsState().value
    }

    fun cleanup() {
        stopCaffeine()
    }
}
