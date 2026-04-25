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

package com.android.systemui.axplatform;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.service.quicksettings.Tile;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.axion.platform.IAxPlatformCallback;
import com.android.axion.platform.IAxPlatformService;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

public class AxPlatformService extends Service {

    private static final String ACTION_BIND = "com.android.systemui.action.AX_PLATFORM";

    private static final String FEATURE_WIFI = "wifi";
    private static final String FEATURE_MOBILE_DATA = "mobile_data";
    private static final String FEATURE_BLUETOOTH = "bluetooth";
    private static final String FEATURE_HOTSPOT = "hotspot";
    private static final String FEATURE_FLASHLIGHT = "flashlight";
    private static final String FEATURE_LOCATION = "location";
    private static final String FEATURE_BATTERY_SAVER = "battery_saver";
    private static final String FEATURE_ZEN = "zen";
    private static final String FEATURE_AOD = "aod";
    private static final String FEATURE_AIRPLANE_MODE = "airplane_mode";
    private static final String FEATURE_NFC = "nfc";
    private static final String FEATURE_DARK_MODE = "dark_mode";
    private static final String FEATURE_NIGHT_LIGHT = "night_light";
    private static final String FEATURE_COLOR_INVERSION = "color_inversion";
    private static final String FEATURE_COLOR_CORRECTION = "color_correction";
    private static final String FEATURE_REDUCE_BRIGHTNESS = "reduce_brightness";
    private static final String FEATURE_ONE_HANDED_MODE = "one_handed_mode";
    private static final String FEATURE_AUTO_SYNC = "auto_sync";
    private static final String FEATURE_CAMERA_PRIVACY = "camera_privacy";
    private static final String FEATURE_MIC_PRIVACY = "mic_privacy";
    private static final String FEATURE_WORK_PROFILE = "work_profile";
    private static final String FEATURE_USB_TETHER = "usb_tether";
    private static final String FEATURE_READING_MODE = "reading_mode";
    private static final String FEATURE_POWER_SHARE = "power_share";
    private static final String FEATURE_CAFFEINE = "caffeine";
    private static final String FEATURE_VPN = "vpn";
    private static final String FEATURE_CAST = "cast";
    private static final String FEATURE_SMART_PIXELS = "smart_pixels";
    private static final String FEATURE_SCREEN_RECORD = "screen_record";
    private static final String FEATURE_SCREENSHOT = "screenshot";
    private static final String FEATURE_HEADS_UP = "heads_up";
    private static final String FEATURE_ROTATION = "rotation";

    private static final long MAIN_THREAD_TIMEOUT_MS = 2000L;

    private static final LinkedHashMap<String, List<String>> FEATURE_TILE_SPECS =
            new LinkedHashMap<>();

    static {
        mapFeature(FEATURE_WIFI, "wifilegacy", "wifi", "internet");
        mapFeature(FEATURE_MOBILE_DATA, "celllegacy", "cell", "mobiledata", "mobile_data");
        mapFeature(FEATURE_BLUETOOTH, "bt", "bluetooth");
        mapFeature(FEATURE_FLASHLIGHT, "flashlight");
        mapFeature(FEATURE_ZEN, "dnd", "modes_dnd", "zen");
        mapFeature(FEATURE_ROTATION, "rotation");
        mapFeature(FEATURE_DARK_MODE, "dark", "dark_mode");
        mapFeature(FEATURE_AIRPLANE_MODE, "airplane", "airplane_mode");
        mapFeature(FEATURE_HOTSPOT, "hotspot");
        mapFeature(FEATURE_LOCATION, "location");
        mapFeature(FEATURE_BATTERY_SAVER, "battery", "saver", "battery_saver");
        mapFeature(FEATURE_CAST, "cast");
        mapFeature(FEATURE_CAMERA_PRIVACY, "cameratoggle", "camera", "camera_privacy");
        mapFeature(FEATURE_MIC_PRIVACY, "mictoggle", "mic", "mic_privacy");
        mapFeature(FEATURE_NFC, "nfc");
        mapFeature(FEATURE_WORK_PROFILE, "work", "work_profile");
        mapFeature(FEATURE_NIGHT_LIGHT, "night", "night_light");
        mapFeature(FEATURE_ONE_HANDED_MODE, "onehanded", "one_handed_mode");
        mapFeature(FEATURE_COLOR_INVERSION, "inversion", "color_inversion");
        mapFeature(FEATURE_COLOR_CORRECTION, "color_correction");
        mapFeature(FEATURE_REDUCE_BRIGHTNESS, "reduce_brightness");
        mapFeature(FEATURE_AOD, "aod", "ambient_display");
        mapFeature(FEATURE_AUTO_SYNC, "sync", "auto_sync");
        mapFeature(FEATURE_USB_TETHER, "usb_tether");
        mapFeature(FEATURE_CAFFEINE, "caffeine");
        mapFeature(FEATURE_VPN, "vpn");
        mapFeature(FEATURE_SCREENSHOT, "screenshot");
        mapFeature(FEATURE_SCREEN_RECORD, "screenrecord", "screen_record");
        mapFeature(FEATURE_POWER_SHARE, "powershare", "power_share", "reverse");
        mapFeature(FEATURE_SMART_PIXELS, "smartpixels", "smart_pixels");
        mapFeature(FEATURE_READING_MODE, "reading_mode");
        mapFeature(FEATURE_HEADS_UP, "heads_up");
    }

    private static void mapFeature(String feature, String... tileSpecs) {
        ArrayList<String> specs = new ArrayList<>(tileSpecs.length);
        for (String spec : tileSpecs) {
            specs.add(spec);
        }
        FEATURE_TILE_SPECS.put(feature, specs);
    }

    private final QSHost mQsHost;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Object mListeningToken = new Object();
    private final RemoteCallbackList<IAxPlatformCallback> mCallbacks = new RemoteCallbackList<>();
    private final Map<String, QSTile> mObservedTiles = new LinkedHashMap<>();
    private final Map<String, QSTile.Callback> mObservedCallbacks = new LinkedHashMap<>();
    private final Map<String, Boolean> mTileAvailabilityCache = new LinkedHashMap<>();
    private final Map<String, String> mResolvedFeatureSpecs = new LinkedHashMap<>();

    private final QSHost.Callback mQsHostCallback = this::onTilesChanged;

    private final IAxPlatformService.Stub mBinder = new IAxPlatformService.Stub() {
        @Override
        public void toggle(String feature) {
            runOnMain(() -> clickTileForFeature(feature));
        }

        @Override
        public void setEnabled(String feature, boolean enabled) {
            runOnMain(() -> {
                Bundle state = getStateInternal(feature);
                if (!state.isEmpty() && state.getBoolean("active", false) != enabled) {
                    clickTileForFeature(feature);
                }
            });
        }

        @Override
        public void setValue(String feature, int value) {
            // Not used by QS-backed features yet.
        }

        @Override
        public void performAction(String feature, String param) {
            // The current desktop integration only needs tile state and toggle support.
        }

        @Override
        public Bundle getState(String feature) {
            return runOnMainBlocking(() -> getStateInternal(feature), Bundle.EMPTY);
        }

        @Override
        public Bundle getAllStates() {
            return runOnMainBlocking(() -> {
                Bundle allStates = new Bundle();
                for (String feature : getSupportedFeaturesInternal()) {
                    Bundle state = getStateInternal(feature);
                    if (!state.isEmpty()) {
                        allStates.putBundle(feature, state);
                    }
                }
                return allStates;
            }, Bundle.EMPTY);
        }

        @Override
        public String[] getSupportedFeatures() {
            return runOnMainBlocking(
                    () -> getSupportedFeaturesInternal().toArray(new String[0]),
                    new String[0]
            );
        }

        @Override
        public void registerCallback(IAxPlatformCallback callback) {
            if (callback == null) {
                return;
            }
            mCallbacks.register(callback);
            runOnMain(() -> dispatchAllStatesTo(callback));
        }

        @Override
        public void unregisterCallback(IAxPlatformCallback callback) {
            if (callback == null) {
                return;
            }
            mCallbacks.unregister(callback);
        }
    };

    @Inject
    public AxPlatformService(QSHost qsHost) {
        mQsHost = qsHost;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        runOnMain(() -> {
            mQsHost.addCallback(mQsHostCallback);
            attachToCurrentTiles();
            dispatchAllStates();
        });
    }

    @Override
    public void onDestroy() {
        runOnMain(() -> {
            detachObservedTiles();
            mQsHost.removeCallback(mQsHostCallback);
        });
        mCallbacks.kill();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        if (intent == null || !ACTION_BIND.equals(intent.getAction())) {
            return null;
        }
        return mBinder;
    }

    private void onTilesChanged() {
        runOnMain(() -> {
            clearTileCaches();
            attachToCurrentTiles();
            dispatchAllStates();
        });
    }

    private void attachToCurrentTiles() {
        Map<String, QSTile> nextTiles = new LinkedHashMap<>();
        Collection<QSTile> currentTiles = mQsHost.getTiles();
        for (QSTile tile : currentTiles) {
            if (tile == null || TextUtils.isEmpty(tile.getTileSpec())) {
                continue;
            }
            nextTiles.put(tile.getTileSpec(), tile);
        }

        for (String spec : new ArrayList<>(mObservedTiles.keySet())) {
            if (nextTiles.containsKey(spec) && mObservedTiles.get(spec) == nextTiles.get(spec)) {
                continue;
            }
            stopObservingTile(mObservedTiles.remove(spec), mObservedCallbacks.remove(spec));
        }

        for (Map.Entry<String, QSTile> entry : nextTiles.entrySet()) {
            String spec = entry.getKey();
            QSTile tile = entry.getValue();
            if (mObservedTiles.containsKey(spec)) {
                continue;
            }
            QSTile.Callback callback = state -> dispatchState(spec, tile, state);
            tile.addCallback(callback);
            tile.setListening(mListeningToken, true);
            tile.refreshState();
            mObservedTiles.put(spec, tile);
            mObservedCallbacks.put(spec, callback);
        }
    }

    private void detachObservedTiles() {
        for (String spec : new ArrayList<>(mObservedTiles.keySet())) {
            stopObservingTile(mObservedTiles.remove(spec), mObservedCallbacks.remove(spec));
        }
    }

    private void stopObservingTile(@Nullable QSTile tile, @Nullable QSTile.Callback callback) {
        if (tile == null) {
            return;
        }
        if (callback != null) {
            tile.removeCallback(callback);
        }
        tile.setListening(mListeningToken, false);
    }

    private void clickTileForFeature(String feature) {
        ResolvedTile resolvedTile = resolveTile(feature, false);
        if (resolvedTile == null) {
            return;
        }
        try {
            resolvedTile.tile.click(null);
        } finally {
            resolvedTile.recycle();
        }
    }

    @NonNull
    private List<String> getSupportedFeaturesInternal() {
        ArrayList<String> supported = new ArrayList<>();
        for (String feature : FEATURE_TILE_SPECS.keySet()) {
            ResolvedTile resolvedTile = resolveTile(feature, false);
            if (resolvedTile != null) {
                supported.add(feature);
                resolvedTile.recycle();
            }
        }
        return supported;
    }

    @NonNull
    private Bundle getStateInternal(String feature) {
        ResolvedTile resolvedTile = resolveTile(feature, true);
        if (resolvedTile == null) {
            return Bundle.EMPTY;
        }
        try {
            return stateToBundle(
                    feature,
                    resolvedTile.actualSpec,
                    resolvedTile.tile,
                    resolvedTile.tile.getState().copy()
            );
        } finally {
            resolvedTile.recycle();
        }
    }

    @Nullable
    private ResolvedTile resolveTile(String feature, boolean refresh) {
        String actualSpec = resolveTileSpec(feature);
        if (actualSpec == null) {
            return null;
        }

        QSTile observedTile = mObservedTiles.get(actualSpec);
        if (observedTile != null) {
            if (refresh) {
                observedTile.refreshState();
            }
            return new ResolvedTile(actualSpec, observedTile, false);
        }

        QSTile tile = mQsHost.createTile(actualSpec);
        if (tile == null) {
            return null;
        }
        tile.setListening(mListeningToken, true);
        if (refresh) {
            tile.refreshState();
        }
        return new ResolvedTile(actualSpec, tile, true);
    }

    @Nullable
    private String resolveTileSpec(String feature) {
        if (TextUtils.isEmpty(feature)) {
            return null;
        }

        String cachedSpec = mResolvedFeatureSpecs.get(feature);
        if (!TextUtils.isEmpty(cachedSpec) && canUseTile(cachedSpec)) {
            return cachedSpec;
        }
        mResolvedFeatureSpecs.remove(feature);

        List<String> candidates = FEATURE_TILE_SPECS.get(feature);
        if (candidates == null) {
            ArrayList<String> direct = new ArrayList<>(1);
            direct.add(feature);
            candidates = direct;
        }

        Set<String> configuredSpecs = getConfiguredSpecs();
        for (String candidate : candidates) {
            if (configuredSpecs.contains(candidate) && canUseTile(candidate)) {
                mResolvedFeatureSpecs.put(feature, candidate);
                return candidate;
            }
        }

        for (String candidate : candidates) {
            if (canUseTile(candidate)) {
                mResolvedFeatureSpecs.put(feature, candidate);
                return candidate;
            }
        }

        return null;
    }

    @NonNull
    private Set<String> getConfiguredSpecs() {
        LinkedHashSet<String> specs = new LinkedHashSet<>(mQsHost.getSpecs());
        String stock = getString(R.string.quick_settings_tiles_stock);
        for (String spec : stock.split(",")) {
            if (!spec.isBlank()) {
                specs.add(spec.trim());
            }
        }
        return specs;
    }

    private boolean canUseTile(String tileSpec) {
        QSTile observedTile = mObservedTiles.get(tileSpec);
        if (observedTile != null) {
            return observedTile.isAvailable();
        }

        Boolean cachedAvailability = mTileAvailabilityCache.get(tileSpec);
        if (cachedAvailability != null) {
            return cachedAvailability;
        }

        QSTile tile = mQsHost.createTile(tileSpec);
        if (tile == null) {
            mTileAvailabilityCache.put(tileSpec, false);
            return false;
        }
        try {
            boolean available = tile.isAvailable();
            mTileAvailabilityCache.put(tileSpec, available);
            return available;
        } finally {
            tile.destroy();
        }
    }

    private void clearTileCaches() {
        mTileAvailabilityCache.clear();
        mResolvedFeatureSpecs.clear();
    }

    private void dispatchAllStates() {
        for (String feature : getSupportedFeaturesInternal()) {
            Bundle state = getStateInternal(feature);
            if (!state.isEmpty()) {
                dispatchState(feature, state);
            }
        }
    }

    private void dispatchAllStatesTo(IAxPlatformCallback callback) {
        for (String feature : getSupportedFeaturesInternal()) {
            Bundle state = getStateInternal(feature);
            if (state.isEmpty()) {
                continue;
            }
            try {
                callback.onStateChanged(feature, state);
            } catch (RemoteException ignored) {
                return;
            }
        }
    }

    private void dispatchState(String actualSpec, QSTile tile, QSTile.State state) {
        String feature = featureForSpec(actualSpec);
        if (feature == null) {
            return;
        }
        dispatchState(feature, stateToBundle(feature, actualSpec, tile, state.copy()));
    }

    private void dispatchState(String feature, Bundle state) {
        int count = mCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onStateChanged(feature, state);
                } catch (RemoteException ignored) {
                }
            }
        } finally {
            mCallbacks.finishBroadcast();
        }
    }

    @NonNull
    private Bundle stateToBundle(
            String feature,
            String actualSpec,
            QSTile tile,
            QSTile.State state
    ) {
        Bundle bundle = new Bundle();
        CharSequence defaultLabel = tile.getTileLabel();
        String defaultLabelString = defaultLabel != null ? defaultLabel.toString() : feature;
        String stateLabel = state.label != null ? state.label.toString() : defaultLabelString;
        String secondaryLabel = state.secondaryLabel != null ? state.secondaryLabel.toString() : "";
        boolean isActive = state.state == Tile.STATE_ACTIVE;
        boolean available = tile.isAvailable() && state.state != Tile.STATE_UNAVAILABLE;
        boolean enabled = state instanceof BooleanState
                ? ((BooleanState) state).value
                : isActive;

        bundle.putString("feature", feature);
        bundle.putString("spec", actualSpec);
        bundle.putString("tileSpec", actualSpec);
        bundle.putInt("tileState", state.state);
        bundle.putBoolean("available", available);
        bundle.putBoolean("active", enabled);
        bundle.putBoolean("enabled", enabled);
        bundle.putBoolean("isEnabled", enabled);
        bundle.putBoolean("transient", state.isTransient);
        bundle.putBoolean("isTransient", state.isTransient);

        String label = defaultLabelString;

        if (FEATURE_WIFI.equals(feature)) {
            boolean connected = enabled
                    && !TextUtils.isEmpty(stateLabel)
                    && !TextUtils.equals(stateLabel, defaultLabelString);
            bundle.putBoolean("connected", connected);
            bundle.putBoolean("wifiEnabled", enabled);
            bundle.putBoolean("wifiConnected", connected);
            bundle.putString("ssid", connected ? stateLabel : "");
            if (connected && TextUtils.isEmpty(secondaryLabel)) {
                secondaryLabel = stateLabel;
            }
        } else if (FEATURE_MOBILE_DATA.equals(feature)) {
            bundle.putBoolean("mobileDataEnabled", enabled);
            bundle.putBoolean("dataEnabled", enabled);
            bundle.putString("type", secondaryLabel);
        } else if (FEATURE_BLUETOOTH.equals(feature)) {
            boolean connected = enabled
                    && !TextUtils.isEmpty(stateLabel)
                    && !TextUtils.equals(stateLabel, defaultLabelString);
            bundle.putBoolean("connected", connected);
            if (connected && TextUtils.isEmpty(secondaryLabel)) {
                secondaryLabel = stateLabel;
            }
        }

        bundle.putString("label", label);
        bundle.putString("title", label);
        bundle.putString("secondaryLabel", secondaryLabel);
        bundle.putString("subtitle", secondaryLabel);
        return bundle;
    }

    @Nullable
    private static String featureForSpec(String spec) {
        for (Map.Entry<String, List<String>> entry : FEATURE_TILE_SPECS.entrySet()) {
            if (entry.getValue().contains(spec)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mMainHandler.post(runnable);
        }
    }

    @NonNull
    private <T> T runOnMainBlocking(MainThreadCallable<T> callable, T fallback) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return callable.call();
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>(fallback);
        mMainHandler.post(() -> {
            try {
                result.set(callable.call());
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private interface MainThreadCallable<T> {
        T call();
    }

    private final class ResolvedTile {
        final String actualSpec;
        final QSTile tile;
        final boolean destroyWhenFinished;

        ResolvedTile(String actualSpec, QSTile tile, boolean destroyWhenFinished) {
            this.actualSpec = actualSpec;
            this.tile = tile;
            this.destroyWhenFinished = destroyWhenFinished;
        }

        void recycle() {
            if (!destroyWhenFinished) {
                return;
            }
            tile.setListening(mListeningToken, false);
            tile.destroy();
        }
    }
}
