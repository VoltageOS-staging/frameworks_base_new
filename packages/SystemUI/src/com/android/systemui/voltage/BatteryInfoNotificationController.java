/*
 * Copyright (C) 2026 VoltageOS
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

package com.android.systemui.voltage;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.BatterySummaryStats;
import android.os.BatteryStatsManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.settingslib.Utils;
import com.android.settingslib.fuelgauge.BatteryInfoFormatter;
import com.android.systemui.CoreStartable;
import com.android.systemui.res.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.policy.BatteryController;

import javax.inject.Inject;

@SysUISingleton
public class BatteryInfoNotificationController implements CoreStartable {

    private static final String CHANNEL_ID = "battery_info_stats";
    private static final int NOTIF_ID = 0x560000BA;
    private static final long POLL_INTERVAL_MS = 6_000;
    private static final long STATS_THROTTLE_MS = 30_000;
    private static final long MAX_PLAUSIBLE_MA = 30_000L;
    private static final long CURRENT_ROUND_MA = 25L;

    private final Context mContext;
    private final Handler mMainHandler;
    private final Handler mBgHandler;
    private final NotificationManager mNotifManager;
    private final BatteryManager mBatteryManager;
    private final BatteryStatsManager mBatteryStatsManager;
    private final PowerManager mPowerManager;
    private final BatteryController mBatteryController;
    private final ContentObserver mSettingsObserver;

    private final int mCurrentSign;
    private final int mCurrentDivisor;

    private volatile int mVoltageMv;
    private volatile int mTemperature;
    private volatile long mCurrentMa;
    private volatile int mLevel = -1;
    private volatile boolean mEnabled;
    private volatile boolean mScreenOn;
    private volatile BatterySummaryStats mCachedStats;
    private volatile String mCachedEstimate = "";
    private volatile String mLastNotifText = "";

    private boolean mReceiverRegistered;
    private long mLastStatsFetch;

    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mEnabled || !mScreenOn) return;
            mCurrentMa = readCurrentMa();
            refreshNotification();
            mBgHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case Intent.ACTION_BATTERY_CHANGED:
                    mVoltageMv   = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, mVoltageMv);
                    mTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, mTemperature);
                    mLevel       = readLevel(intent);
                    mBatteryController.getEstimatedTimeRemainingString(est -> {
                        mCachedEstimate = est != null ? est : "";
                        refreshNotification();
                    });
                    final long now = SystemClock.uptimeMillis();
                    if (now - mLastStatsFetch > STATS_THROTTLE_MS) {
                        mLastStatsFetch = now;
                        fetchStatsAsync();
                    }
                    break;
                case Intent.ACTION_SCREEN_ON:
                    mScreenOn = true;
                    startPolling();
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    mScreenOn = false;
                    stopPolling();
                    refreshNotification();
                    break;
            }
        }
    };

    @Inject
    public BatteryInfoNotificationController(
            Context context,
            @Main Handler mainHandler,
            @Background Handler bgHandler,
            BatteryController batteryController,
            PowerManager powerManager) {
        mContext = context;
        mMainHandler = mainHandler;
        mBgHandler = bgHandler;
        mBatteryController = batteryController;
        mPowerManager = powerManager;
        mNotifManager = context.getSystemService(NotificationManager.class);
        mBatteryManager = context.getSystemService(BatteryManager.class);
        mBatteryStatsManager = context.getSystemService(BatteryStatsManager.class);
        mCurrentSign    = context.getResources().getInteger(R.integer.config_batteryCurrentNowSign);
        mCurrentDivisor = context.getResources().getInteger(R.integer.config_currentInfoDivider);
        mSettingsObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                final boolean enabled = isEnabledInSettings();
                if (enabled == mEnabled) return;
                mEnabled = enabled;
                if (mEnabled) enable(); else disable();
            }
        };
    }

    @Override
    public void start() {
        createNotificationChannel();
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.BATTERY_INFO_NOTIFICATION),
                false, mSettingsObserver, UserHandle.USER_ALL);
        mEnabled = isEnabledInSettings();
        if (mEnabled) enable();
    }

    private void enable() {
        if (mReceiverRegistered) return;
        mScreenOn = mPowerManager.isInteractive();
        final Intent sticky = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) {
            mVoltageMv   = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            mTemperature = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            mLevel       = readLevel(sticky);
        }
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        mReceiverRegistered = true;
        mLastStatsFetch = 0;
        startPolling();
        fetchStatsAsync();
    }

    private void disable() {
        stopPolling();
        if (mReceiverRegistered) {
            mContext.unregisterReceiver(mReceiver);
            mReceiverRegistered = false;
        }
        mNotifManager.cancel(NOTIF_ID);
        mLastNotifText = "";
    }

    private void startPolling() {
        mBgHandler.removeCallbacks(mPollRunnable);
        if (mEnabled && mScreenOn) mBgHandler.post(mPollRunnable);
    }

    private void stopPolling() {
        mBgHandler.removeCallbacks(mPollRunnable);
    }

    private void fetchStatsAsync() {
        mBgHandler.post(() -> {
            BatterySummaryStats stats = null;
            try {
                stats = mBatteryStatsManager.getBatterySummaryStats();
            } catch (Exception ignored) {}
            if (stats != null) mCachedStats = stats;
            refreshNotification();
        });
    }

    private void createNotificationChannel() {
        final NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                mContext.getString(R.string.battery_info_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableLights(false);
        ch.enableVibration(false);
        mNotifManager.createNotificationChannel(ch);
    }

    private void refreshNotification() {
        final String nowLine = buildNowLine();
        final String text = buildNotifText(nowLine);
        if (text.equals(mLastNotifText)) return;
        mLastNotifText = text;
        final int level = mLevel;
        final Notification notif = new Notification.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_battery_info)
                .setContentTitle(mContext.getString(R.string.battery_info_notif_title))
                .setContentText(nowLine)
                .setSubText(level >= 0 ? BatteryInfoFormatter.formatPercent(level) : null)
                .setColor(Utils.getColorAttrDefaultColor(mContext, android.R.attr.colorAccent))
                .setColorized(false)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
        mNotifManager.notify(NOTIF_ID, notif);
    }

    private String buildNowLine() {
        String nowLine = mContext.getString(R.string.battery_info_notif_now,
                BatteryInfoFormatter.formatCurrent(mCurrentMa),
                BatteryInfoFormatter.formatPower(mCurrentMa, mVoltageMv),
                BatteryInfoFormatter.formatTemp(mTemperature));
        final String estimate = mCachedEstimate;
        if (!estimate.isEmpty()) {
            nowLine += " \u00b7 " + estimate;
        }
        return nowLine;
    }

    private String buildNotifText(String nowLine) {
        final StringBuilder sb = new StringBuilder();
        sb.append(nowLine).append('\n');

        final BatterySummaryStats s = mCachedStats;
        if (s != null) {
            sb.append(mContext.getString(R.string.battery_info_notif_rates,
                    BatteryInfoFormatter.formatDischargeRate(
                            s.screenOnDischargePercent, s.screenOnTimeMs),
                    BatteryInfoFormatter.formatDischargeRate(
                            s.screenOffDischargePercent, s.screenOffTimeMs)));
            sb.append('\n');
            sb.append(mContext.getString(R.string.battery_info_notif_screen_on,
                    BatteryInfoFormatter.formatDuration(s.screenOnTimeMs),
                    BatteryInfoFormatter.formatPercent(s.screenOnDischargePercent),
                    BatteryInfoFormatter.formatMah(s.screenOnDischargeMah)));
            sb.append('\n');
            sb.append(mContext.getString(R.string.battery_info_notif_screen_off,
                    BatteryInfoFormatter.formatDuration(s.screenOffTimeMs),
                    BatteryInfoFormatter.formatPercent(s.screenOffDischargePercent),
                    BatteryInfoFormatter.formatMah(s.screenOffDischargeMah)));
            sb.append('\n');
            sb.append(mContext.getString(R.string.battery_info_notif_deep_sleep,
                    BatteryInfoFormatter.formatDuration(s.deepSleepTimeMs),
                    screenOffFraction(s.deepSleepTimeMs, s.screenOffTimeMs)));
            sb.append('\n');
            sb.append(mContext.getString(R.string.battery_info_notif_awake,
                    BatteryInfoFormatter.formatDuration(s.screenOffAwakeTimeMs),
                    screenOffFraction(s.screenOffAwakeTimeMs, s.screenOffTimeMs)));
        }
        return sb.toString().trim();
    }

    private long readCurrentMa() {
        if (mBatteryManager == null) return mCurrentMa;
        final long raw = mBatteryManager.getLongProperty(
                BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (raw == Long.MIN_VALUE) return mCurrentMa;
        final long divisor = mCurrentDivisor != 0 ? mCurrentDivisor : 1;
        final long mA = (raw * mCurrentSign) / divisor;
        if (Math.abs(mA) > MAX_PLAUSIBLE_MA) return mCurrentMa;
        return Math.round((double) mA / CURRENT_ROUND_MA) * CURRENT_ROUND_MA;
    }

    private int readLevel(Intent intent) {
        if (intent == null) return mLevel;
        final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return mLevel;
        return Math.round(level * 100f / scale);
    }

    private static String screenOffFraction(long partMs, long totalMs) {
        if (totalMs <= 0) return "0%";
        return Math.round(100.0 * partMs / totalMs) + "%";
    }

    private boolean isEnabledInSettings() {
        return Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.BATTERY_INFO_NOTIFICATION,
                0, UserHandle.USER_CURRENT) == 1;
    }
}
