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

import android.content.Context
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.os.SystemClock
import android.pocket.PocketManager
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.InputEvent
import android.view.MotionEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.shared.system.InputChannelCompat
import com.android.systemui.shared.system.InputMonitorCompat
import com.android.systemui.backtap.dagger.BackTapHandler
import javax.inject.Inject

@SysUISingleton
class BackTapController @Inject constructor(
    private val context: Context,
    private val powerManager: PowerManager,
    private val screenLifecycle: ScreenLifecycle,
    @BackTapHandler private val bgHandler: Handler
) : SensorEventListener, ScreenLifecycle.Observer {

    private val TAG = "BackTapGesture"

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val pocketManager = context.getSystemService(Context.POCKET_SERVICE) as PocketManager?

    private val mainChoreographer = Choreographer.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val actionDispatcher = BackTapActionDispatcher(context)
    private val stateMachine = GestureStateMachine(bgHandler) { executeAction() }
    private val tapEngine = TapDetectionEngine { candidate ->
        stateMachine.onTapCandidate(candidate)
    }

    private var isEnabled = false
    private var currentAction = 0
    private var isListening = false

    @Volatile private var lastTouchTime = 0L
    @Volatile private var wasBlocked = false
    private var inputMonitor: InputMonitorCompat? = null
    private var inputEventReceiver: InputChannelCompat.InputEventReceiver? = null
    
    private val inputEventListener = object : InputChannelCompat.InputEventListener {
        override fun onInputEvent(ev: InputEvent) {
            if (ev is MotionEvent) {
                lastTouchTime = SystemClock.uptimeMillis()
            }
        }
    }

    private val settingsObserver = object : ContentObserver(bgHandler) {
        override fun onChange(selfChange: Boolean) {
            updateSettings()
        }
    }

    fun setup() {
        val resolver = context.contentResolver
        tapEngine.setGyroscopeAvailable(gyroscope != null)

        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.BACK_TAP_ENABLED), false, settingsObserver, UserHandle.USER_ALL)
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.BACK_TAP_ACTION), false, settingsObserver, UserHandle.USER_ALL)
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.BACK_TAP_SENSITIVITY), false, settingsObserver, UserHandle.USER_ALL)
        screenLifecycle.addObserver(this)
        bgHandler.post { updateSettings() }
    }

    private fun updateSettings() {
        val resolver = context.contentResolver
        isEnabled = Settings.System.getInt(resolver, Settings.System.BACK_TAP_ENABLED, 0) == 1
        currentAction = Settings.System.getInt(resolver, Settings.System.BACK_TAP_ACTION, 0)
        
        val sensitivity = Settings.System.getInt(resolver, Settings.System.BACK_TAP_SENSITIVITY, 1)

        tapEngine.updateSensitivity(sensitivity)
        updateSensorState()
    }

    override fun onScreenTurnedOn() {
        bgHandler.post { updateSensorState(true) }
    }
    
    override fun onScreenTurnedOff() {
        bgHandler.post { updateSensorState(false) }
    }

    private fun updateSensorState(isScreenOn: Boolean = powerManager.isInteractive) {
        val shouldListen = isEnabled && isScreenOn && currentAction != 0
        
        if (shouldListen && !isListening) {
            val accel = accelerometer
            if (accel == null) {
                Log.w(TAG, "Accelerometer unavailable, cannot enable back tap")
                return
            }

            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST, bgHandler)
            gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, bgHandler) }
            
            inputMonitor = InputMonitorCompat("BackTap/TouchGate", 0)
            inputEventReceiver = inputMonitor?.getInputReceiver(
                Looper.getMainLooper(),
                mainChoreographer,
                inputEventListener
            )

            isListening = true
        } else if (!shouldListen && isListening) {
            sensorManager.unregisterListener(this)
            tapEngine.reset()

            val receiver = inputEventReceiver
            val monitor = inputMonitor
            inputEventReceiver = null
            inputMonitor = null

            mainHandler.post {
                receiver?.dispose()
                monitor?.dispose()
            }
            
            isListening = false
        }
    }

    private fun executeAction() {
        Log.d(TAG, "Double Tap Triggered! Checking Gates...")

        if (!powerManager.isInteractive) {
            Log.d(TAG, "Blocked: Screen is Off")
            return 
        }
        
        if (pocketManager?.isDeviceInPocket == true) {
            Log.d(TAG, "Blocked: Device is in Pocket")
            return 
        }

        if (SystemClock.uptimeMillis() - lastTouchTime < 300L) {
            Log.d(TAG, "Blocked: Screen is currently being touched")
            return
        }

        Log.d(TAG, "Dispatching Action ID: $currentAction")
        mainHandler.postDelayed({
            actionDispatcher.dispatch(currentAction)
        }, 50)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if (SystemClock.uptimeMillis() - lastTouchTime < 300L) {
                    wasBlocked = true
                    return
                }
                if (wasBlocked) {
                    tapEngine.reset()
                    wasBlocked = false
                }
                tapEngine.processSensorEvent(event)
            }
            Sensor.TYPE_GYROSCOPE -> {
                tapEngine.processGyroEvent(event)
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
