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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.view.WindowManager
import android.view.Gravity
import android.graphics.PixelFormat

@SysUISingleton
class PulseViewController @Inject constructor(
    private val context: Context
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
    private var floatingPulseView: PulseView? = null
    private var taskbarWm: WindowManager? = null
    
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

    fun attachTaskbarPulse(windowContext: Context) {
        if (floatingPulseView != null) return
        val navPanelContext = windowContext.createWindowContext(
            windowContext.display,
            WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
            null
        )
        val wm = navPanelContext.getSystemService(WindowManager::class.java)
        taskbarWm = wm
        floatingPulseView = PulseView(navPanelContext)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            navPanelContext.resources.getDimensionPixelSize(com.android.internal.R.dimen.navigation_bar_height),
            WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.title = "PulseTaskbarOverlay"
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        
        wm?.addView(floatingPulseView, params)
        
        floatingPulseView?.initialize(settingsRepository)
        floatingPulseView?.setNavbarMode(true)
        updateState()
        mainScope.launch {
            floatingPulseView?.setVisibility(pulseRunning && navbarEnabled && !keyguardShowing && !isDozing)
        }
    }

    fun detachTaskbarPulse(windowContext: Context) {
        floatingPulseView?.let {
            taskbarWm?.removeView(it)
        }
        floatingPulseView = null
        taskbarWm = null
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
                floatingPulseView?.setVisibility(false)
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
            floatingPulseView?.setVisibility(show && runOnNavbar)

            view.setVisibility(show)
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
                floatingPulseView?.updateVisualizerData(data)
            }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        isMediaPlaying = state == PlaybackState.STATE_PLAYING
        updateState()
    }

    override fun onMediaColorsChanged(color: Int) {
        if (pulseEnabled) {
            view.onMediaColorsChanged(color)
            navbarView?.onMediaColorsChanged(color)
            floatingPulseView?.onMediaColorsChanged(color)
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
