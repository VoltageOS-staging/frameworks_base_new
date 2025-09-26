package com.android.systemui.util;

import android.app.ActivityManager;
import android.hardware.display.AmbientDisplayConfiguration;

public class ScreenAnimationController {

    private static ScreenAnimationController sInstance;

    private AmbientDisplayConfiguration mAmbientDisplayConfiguration = null;

    private boolean mPanelExpandedWhenScreenOff = false;

    private ScreenAnimationController() {}

    public static synchronized ScreenAnimationController INSTANCE() {
        if (sInstance == null) {
            sInstance = new ScreenAnimationController();
        }
        return sInstance;
    }

    public void setPanelExpanded(boolean expanded) {
        mPanelExpandedWhenScreenOff = expanded;
    }
    
    public void init(AmbientDisplayConfiguration ambientConfig) {
        mAmbientDisplayConfiguration = ambientConfig;
    }

    public boolean shouldPlayAnimation() {
        boolean aodEnabled = mAmbientDisplayConfiguration != null && mAmbientDisplayConfiguration.enabled(ActivityManager.getCurrentUser());
        return !mPanelExpandedWhenScreenOff && aodEnabled;
    }
}
