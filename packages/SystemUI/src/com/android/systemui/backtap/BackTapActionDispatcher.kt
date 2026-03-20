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

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.NotificationManager
import android.app.SearchManager
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.net.TetheringManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.UserHandle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.WindowManagerGlobal
import android.view.accessibility.AccessibilityManager
import com.android.internal.util.ScreenshotHelper
import com.android.internal.util.voltage.VoltageUtils

class BackTapActionDispatcher(private val context: Context) {
    private val TAG = "BackTapActionDispatcher"

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    fun dispatch(actionId: Int) {
        if (actionId == 0) return
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))

        when (actionId) {
            1 -> VoltageUtils.launchVoiceSearch(context)
            2 -> VoltageUtils.launchCamera(context)
            3 -> VoltageUtils.toggleCameraFlash()
            4 -> VoltageUtils.toggleVolumePanel(context)
            5 -> VoltageUtils.switchScreenOff(context)
            6 -> VoltageUtils.takeScreenshot()
            7 -> triggerPartialScreenshot()
            8 -> VoltageUtils.toggleNotifications()
            9 -> VoltageUtils.toggleQsPanel()
            10 -> VoltageUtils.clearAllNotifications()
            11 -> VoltageUtils.toggleRingerModes(context)
            12 -> VoltageUtils.killForegroundApp()
            13 -> VoltageUtils.switchToLastApp(context)
            14 -> VoltageUtils.showPowerMenu()
            15 -> VoltageUtils.sendKeycode(context, KeyEvent.KEYCODE_APP_SWITCH)
            16 -> toggleNirvanaMode()
            17 -> launchCustomApp()
            18 -> VoltageUtils.sendKeycode(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            19 -> VoltageUtils.sendKeycode(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            20 -> VoltageUtils.sendKeycode(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            21 -> toggleDnd()
            22 -> toggleOneHandedMode()
            23 -> toggleRotationLock()
            24 -> toggleDarkMode()
            25 -> toggleBatterySaver()
            26 -> toggleHotspot()
            27 -> toggleScreenPinning()
            28 -> triggerAccessibilityShortcut()
            29 -> launchAssistant()
            30 -> launchContextualSearch()
        }
    }

    private fun launchCustomApp() {
        val resolver = context.contentResolver
        val packageName = Settings.System.getStringForUser(resolver, Settings.System.BACK_TAP_APP_ACTION, UserHandle.USER_CURRENT)
        val activity = Settings.System.getStringForUser(resolver, Settings.System.BACK_TAP_APP_ACTIVITY_ACTION, UserHandle.USER_CURRENT)
        if (TextUtils.isEmpty(packageName)) return

        try {
            val intent = if (!TextUtils.isEmpty(activity) && activity != "NONE") {
                Intent(Intent.ACTION_MAIN).apply { setClassName(packageName, activity) }
            } else {
                context.packageManager.getLaunchIntentForPackage(packageName)
            }
            intent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivityAsUser(it, UserHandle.CURRENT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch custom app: $packageName", e)
        }
    }

    private fun triggerPartialScreenshot() {
        try {
            val screenshotHelper = ScreenshotHelper(context)
            screenshotHelper.takeScreenshot(
                WindowManager.TAKE_SCREENSHOT_SELECTED_REGION,
                WindowManager.ScreenshotSource.SCREENSHOT_VENDOR_GESTURE,
                Handler(Looper.getMainLooper()),
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger partial screenshot", e)
        }
    }

    private fun toggleNirvanaMode() {
        val resolver = context.contentResolver
        val currentState = Settings.Secure.getInt(resolver, "nirvana_mode_manual_active", 0)
        Settings.Secure.putInt(resolver, "nirvana_mode_manual_active", if (currentState == 1) 0 else 1)
        val intent = Intent("com.power.hub.action.UPDATE_NIRVANA_SCHEDULE")
        intent.setPackage("com.android.settings")
        context.sendBroadcastAsUser(intent, UserHandle.ALL)
    }
    private fun toggleDnd() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val current = nm.currentInterruptionFilter
        if (current == NotificationManager.INTERRUPTION_FILTER_ALL) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } else {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }

    private fun toggleOneHandedMode() {
        val resolver = context.contentResolver
        val enabled = Settings.Secure.getIntForUser(resolver, Settings.Secure.ONE_HANDED_MODE_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        if (!enabled) {
            Settings.Secure.putIntForUser(resolver, Settings.Secure.ONE_HANDED_MODE_ENABLED, 1, UserHandle.USER_CURRENT)
        }
        val active = Settings.Secure.getIntForUser(resolver, "one_handed_mode_activated", 0, UserHandle.USER_CURRENT) == 1
        Settings.Secure.putIntForUser(resolver, "one_handed_mode_activated", if (active) 0 else 1, UserHandle.USER_CURRENT)
    }

    private fun toggleRotationLock() {
        val resolver = context.contentResolver
        val current = Settings.System.getIntForUser(resolver, Settings.System.ACCELEROMETER_ROTATION, 0, UserHandle.USER_CURRENT)
        Settings.System.putIntForUser(resolver, Settings.System.ACCELEROMETER_ROTATION, if (current == 1) 0 else 1, UserHandle.USER_CURRENT)
    }

    private fun toggleDarkMode() {
        val uiModeManager = context.getSystemService(UiModeManager::class.java) ?: return
        val isNightMode = uiModeManager.nightMode == UiModeManager.MODE_NIGHT_YES
        uiModeManager.nightMode = if (isNightMode) UiModeManager.MODE_NIGHT_NO else UiModeManager.MODE_NIGHT_YES
    }

    private fun toggleBatterySaver() {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return
        powerManager.setPowerSaveModeEnabled(!powerManager.isPowerSaveMode)
    }

    private fun toggleHotspot() {
        val wifiManager = context.getSystemService(WifiManager::class.java) ?: return
        val tetheringManager = context.getSystemService(TetheringManager::class.java) ?: return
        
        if (wifiManager.isWifiApEnabled) {
            tetheringManager.stopTethering(TetheringManager.TETHERING_WIFI)
        } else {
            tetheringManager.startTethering(TetheringManager.TETHERING_WIFI, context.mainExecutor, object : TetheringManager.StartTetheringCallback {
                override fun onTetheringFailed(error: Int) {
                    Log.e(TAG, "Failed to start hotspot: $error")
                }
            })
        }
    }

    private fun toggleScreenPinning() {
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
                ActivityTaskManager.getService().stopSystemLockTaskMode()
            } else {
                val tasks = am.getRunningTasks(1)
                if (tasks.isNotEmpty()) {
                    ActivityTaskManager.getService().startSystemLockTaskMode(tasks[0].id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle screen pinning", e)
        }
    }

    private fun triggerAccessibilityShortcut() {
        try {
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return
            am.performAccessibilityShortcut()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger accessibility shortcut", e)
        }
    }

    private fun launchAssistant() {
        try {
            val intent = Intent(Intent.ACTION_ASSIST).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivityAsUser(intent, UserHandle.CURRENT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Assistant", e)
        }
    }

    private fun launchContextualSearch() {
        val searchManager = context.getSystemService(SearchManager::class.java) ?: return
        searchManager.launchAssist(null)
    }
}
