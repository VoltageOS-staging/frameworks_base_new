/*
 * Copyright (C) 2025 The AxionAOSP Project
 *           (C) 2026 VoltageOS
 *           (C) 2026 crDroid Android Project
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
package com.android.systemui.pulse

import android.content.Context
import android.media.session.PlaybackState
import com.android.systemui.LauncherProxyService
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@SysUISingleton
class PulseViewController @Inject constructor(
    private val context: Context,
    private val launcherProxyService: LauncherProxyService
) : PulseAudioDataProcessor.DataListener,
    MediaSessionManager.MediaDataListener,
    ScrimUtils.ScrimEventListener {

    private val mainScope = MainScope()
    private var listenersRegistered = false

    private var isMediaPlaying = false
    private var bouncerShowingOrKeyguardDismissing = false
    private var keyguardShowing = false
    private var isDozing = false
    private var isScreenOff = false

    private val settingsRepository: PulseSettingsRepository =
        PulseSettingsRepository(context)

    private val view: PulseView =
        PulseView(context)

    private val audioProcessor: PulseAudioDataProcessor =
        PulseAudioDataProcessor(context).apply {
            setDataListener(this@PulseViewController)
        }

    private val bassHaptics: PulseBassHaptics =
        PulseBassHaptics(context)

    val pulseEnabled: Boolean
        get() = settingsRepository.isPulseEnabled()

    val ambientEnabled: Boolean
        get() = settingsRepository.isPulseAmbientEnabled()

    val navbarEnabled: Boolean
        get() = settingsRepository.isPulseNavbarEnabled()

    private val isCollapsed: Boolean
        get() = ScrimUtils.get().isPanelFullyCollapsed()

    private val hapticsMode: Int
        get() = settingsRepository.getPulseHapticsMode()

    var pulseRunning: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            updatePulse(value)
        }

    init {
        INSTANCE = this

        view.initialize(settingsRepository)
        settingsRepository.setOnSettingsChangedListener { onSettingsChanged() }
        settingsRepository.startObserving()
        onSettingsChanged()
    }

    fun getPulseView(): PulseView = view

    private fun updateState() {
        if (!pulseEnabled) {
            pulseRunning = false
            return
        }
        val runOnLockscreen = !bouncerShowingOrKeyguardDismissing
                && isCollapsed
                && !isScreenOff
                && ((keyguardShowing && !isDozing) || (isDozing && ambientEnabled))
        
        val runOnNavbar = navbarEnabled && !keyguardShowing && !isDozing

        pulseRunning = isMediaPlaying && (runOnLockscreen || runOnNavbar)
    }

    private var navbarView: PulseView? = null

    /** Last known media album-art colour forwarded to the Launcher pulse renderer. */
    private var lastMediaColor = 0

    fun attachNavbarView(v: PulseView) {
        navbarView = v
        v.initialize(settingsRepository)
        v.setNavbarMode(true)
        updateState()
        mainScope.launch {
            v.setVisibility(pulseRunning && navbarEnabled && !keyguardShowing && !isDozing)
        }
    }

    fun detachNavbarView() {
        navbarView = null
    }

    /**
     * No-op: the old WindowManager overlay approach has been replaced by sending FFT data
     * through [LauncherProxyService.sendPulseData] so that Launcher can render the visualizer
     * directly inside TaskbarDragLayer, behind the pill/3-button row.
     */
    fun attachTaskbarPulse(@Suppress("UNUSED_PARAMETER") windowContext: Context) {
        // Data propagation is driven by onDataUpdate(); nothing to attach here.
    }

    /** @see attachTaskbarPulse */
    fun detachTaskbarPulse(@Suppress("UNUSED_PARAMETER") windowContext: Context) {
        // Send a stop signal so Launcher hides the visualizer immediately.
        launcherProxyService.sendPulseData(null, false, 0)
    }

    private fun onSettingsChanged() {
        val enabled = pulseEnabled
        if (enabled && !listenersRegistered) {
            ScrimUtils.get().addListener(this)
            MediaSessionManager.get().addListener(this)
            listenersRegistered = true
        } else if (!enabled && listenersRegistered) {
            ScrimUtils.get().removeListener(this)
            MediaSessionManager.get().removeListener(this)
            listenersRegistered = false
            pulseRunning = false
            mainScope.launch {
                view.setVisibility(false)
                navbarView?.setVisibility(false)
                audioProcessor.stopCapture()
            }
        }
        updateState()
        // Force update
        updatePulse(pulseRunning)
    }

    private fun updatePulse(show: Boolean) {
        mainScope.launch {
            val runOnLockscreen = !bouncerShowingOrKeyguardDismissing
                    && isCollapsed
                    && ((keyguardShowing && !isDozing) || (isDozing && ambientEnabled))

            val runOnNavbar = navbarEnabled && !keyguardShowing && !isDozing

            view.setVisibility(show && runOnLockscreen)
            navbarView?.setVisibility(show && runOnNavbar)

            // Notify Launcher to show or hide the in-taskbar pulse view.
            if (!show || !runOnNavbar) {
                launcherProxyService.sendPulseData(null, false, lastMediaColor)
            }

            if (pulseEnabled && (show || hapticsMode > 1)) {
                audioProcessor.startCapture()
            } else {
                audioProcessor.stopCapture()
                bassHaptics.reset()
            }
        }
    }

    override fun onDataUpdate(data: PulseData) {
        if (hapticsMode > 0) {
            bassHaptics.process(data.fftBytes)
        }
        if (pulseRunning) {
            mainScope.launch {
                view.updateVisualizerData(data)
                navbarView?.updateVisualizerData(data)
            }
            // Forward raw FFT data to Launcher so it can render the visualizer behind the
            // taskbar pill.  Only send when navbar pulse is actually active.
            if (navbarEnabled && !keyguardShowing && !isDozing && data.isDataValid) {
                launcherProxyService.sendPulseData(data.fftBytes, true, lastMediaColor)
            }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        isMediaPlaying = state == PlaybackState.STATE_PLAYING
        updateState()
    }

    override fun onMediaColorsChanged(color: Int) {
        lastMediaColor = color
        if (pulseEnabled) {
            view.onMediaColorsChanged(color)
            navbarView?.onMediaColorsChanged(color)
        }
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        keyguardShowing = showing
        updateState()
    }

    override fun onDozingChanged(dozing: Boolean) {
        isDozing = dozing
        updateState()
    }

    override fun onExpandedFractionChanged(expandedFraction: Float) {
        updateState()
    }

    override fun onBarStateChanged(state: Int) {
        updateState()
    }

    override fun onQsVisibilityChanged(visible: Boolean) {
        updateState()
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = fadingAway
        updateState()
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = goingAway
        updateState()
    }

    override fun onPrimaryBouncerShowingChanged(showing: Boolean) {
        bouncerShowingOrKeyguardDismissing = showing
        updateState()
    }

    override fun onScreenTurnedOff() {
        isScreenOff = true
        updateState()
    }

    override fun onStartedWakingUp() {
        isScreenOff = false
        updateState()
    }

    override fun onUserChanged() {
        settingsRepository.invalidateCache()
        bassHaptics.reset()
        updateState()
    }

    fun destroy() {
        pulseRunning = false
        settingsRepository.stopObserving()
        if (listenersRegistered) {
            ScrimUtils.get().removeListener(this)
            MediaSessionManager.get().removeListener(this)
            listenersRegistered = false
        }
        audioProcessor.cleanup()
        bassHaptics.reset()
        mainScope.cancel()
    }

    companion object {
        private const val TAG = "PulseViewController"

        @Volatile
        private var INSTANCE: PulseViewController? = null

        @JvmStatic
        fun get(context: Context): PulseViewController {
            return INSTANCE ?: throw IllegalStateException(
                "PulseViewController not initialized"
            )
        }
    }
}
