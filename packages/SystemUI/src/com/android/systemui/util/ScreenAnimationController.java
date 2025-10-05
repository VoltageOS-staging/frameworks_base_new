package com.android.systemui.util;

import android.app.ActivityManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.DisplayManager;

public class ScreenAnimationController {

    private static ScreenAnimationController sInstance;

    private AmbientDisplayConfiguration mAmbientDisplayConfiguration = null;
    private DisplayManager mDisplayManager = null;

    private boolean mPanelExpandedWhenScreenOff = false;
    private boolean mLandscapeWhenScreenOff = false;
    private boolean mIsPressSleepButton = false;

    private ScreenAnimationController() {}

    public static synchronized ScreenAnimationController INSTANCE() {
        if (sInstance == null) {
            sInstance = new ScreenAnimationController();
        }
        return sInstance;
    }

    public void updateCsfStates(boolean expanded, boolean landscape, boolean powerButton) {
        mPanelExpandedWhenScreenOff = expanded;
        mLandscapeWhenScreenOff = landscape;
        mIsPressSleepButton = powerButton;
    }

    public void init(AmbientDisplayConfiguration ambientConfig, DisplayManager displayManager) {
        mAmbientDisplayConfiguration = ambientConfig;
        mDisplayManager = displayManager;
    }
    
    /**
     * Sets whether the notification panel was expanded when the screen started turning off.
     * This is a temporary state capture and is reset when the screen wakes up.
     */
    public void setPanelExpanded(boolean expanded) {
        mPanelExpandedWhenScreenOff = expanded;
    }

    public boolean isLandscapeScreenOff() {
        return mLandscapeWhenScreenOff;
    }

    public boolean isPanelExpandedWhenScreenOff() {
        return mPanelExpandedWhenScreenOff;
    }

    public boolean shouldPlayAnimation() {
        boolean aodEnabled = mAmbientDisplayConfiguration != null
                && mAmbientDisplayConfiguration.enabled(ActivityManager.getCurrentUser());
        return !mPanelExpandedWhenScreenOff && !mLandscapeWhenScreenOff && aodEnabled
                && !mIsPressSleepButton;
    }
}
