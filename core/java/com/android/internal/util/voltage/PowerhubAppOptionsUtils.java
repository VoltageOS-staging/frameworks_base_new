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

package com.android.internal.util.voltage;

import android.content.ContentResolver;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PowerhubAppOptionsUtils {
    public static final String SETTING_APP_OPTIONS_CONFIG = "powerhub_app_options_config";

    public static final String KEY_DATA_ISOLATION = "data_isolation_pkgs";
    public static final String KEY_SPOOF_SETTINGS_MAP = "spoof_settings_map";

    public static final String KEY_ADB_WIFI_ENABLED = "adb_wifi_enabled";
    public static final String KEY_PACKAGE_VERIFIER = "package_verifier_user_consent";
    public static final String KEY_VERIFY_APPS_USB = "verify_apps_over_usb";
    public static final String KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled";
    public static final String KEY_ENABLED_ACCESSIBILITY_SERVICES =
            "enabled_accessibility_services";
    public static final String KEY_ACCESSIBILITY_DISPLAY_INVERSION_ENABLED =
            "accessibility_display_inversion_enabled";

    private static final Map<String, String> SPOOFED_SETTINGS = new HashMap<>();

    static {
        SPOOFED_SETTINGS.put(KEY_ADB_WIFI_ENABLED, "0");
        SPOOFED_SETTINGS.put(KEY_PACKAGE_VERIFIER, "0");
        SPOOFED_SETTINGS.put(KEY_VERIFY_APPS_USB, "0");
        SPOOFED_SETTINGS.put(KEY_ACCESSIBILITY_ENABLED, "0");
        SPOOFED_SETTINGS.put(KEY_ENABLED_ACCESSIBILITY_SERVICES, "");
        SPOOFED_SETTINGS.put(KEY_ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, "0");
    }

    private static final Object sLock = new Object();
    private static String sCachedRawConfig = "";
    private static Config sCachedConfig = new Config();

    private PowerhubAppOptionsUtils() {}

    public static String getRawConfig(ContentResolver resolver) {
        if (resolver == null) {
            return "";
        }
        try {
            String config = Settings.Secure.getStringForUser(
                    resolver, SETTING_APP_OPTIONS_CONFIG, UserHandle.USER_SYSTEM);
            return config != null ? config : "";
        } catch (IllegalStateException | SecurityException e) {
            return "";
        }
    }

    public static boolean isDataIsolationEnabled(ContentResolver resolver, String packageName) {
        return isDataIsolationEnabled(getRawConfig(resolver), packageName);
    }

    public static boolean isDataIsolationEnabled(String rawConfig, String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        return getConfig(rawConfig).mDataIsolationPackages.contains(packageName);
    }

    public static boolean isSpoofSettingEnabled(ContentResolver resolver, String packageName,
            String settingName) {
        return isSpoofSettingEnabled(getRawConfig(resolver), packageName, settingName);
    }

    public static boolean isSpoofSettingEnabled(String rawConfig, String packageName,
            String settingName) {
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(settingName)) {
            return false;
        }
        Set<String> spoofSettings = getConfig(rawConfig).mSpoofSettingsMap.get(packageName);
        return spoofSettings != null && spoofSettings.contains(settingName);
    }

    public static String getSpoofedSetting(ContentResolver resolver, String packageName,
            String settingName) {
        return getSpoofedSetting(getRawConfig(resolver), packageName, settingName);
    }

    public static String getSpoofedSetting(String rawConfig, String packageName,
            String settingName) {
        String spoofedValue = getSpoofedValue(settingName);
        if (spoofedValue == null || shouldSkipSpoofing(packageName)) {
            return null;
        }
        return isSpoofSettingEnabled(rawConfig, packageName, settingName) ? spoofedValue : null;
    }

    public static String getSpoofedValue(String settingName) {
        if (TextUtils.isEmpty(settingName)) {
            return null;
        }
        return SPOOFED_SETTINGS.get(settingName);
    }

    public static boolean shouldSkipSpoofing(String packageName) {
        return TextUtils.isEmpty(packageName)
                || "android".equals(packageName)
                || packageName.startsWith("com.android.")
                || packageName.startsWith("com.google.android.");
    }

    private static Config getConfig(String rawConfig) {
        String normalizedConfig = rawConfig != null ? rawConfig : "";
        synchronized (sLock) {
            if (Objects.equals(normalizedConfig, sCachedRawConfig)) {
                return sCachedConfig;
            }

            Config config = parseConfig(normalizedConfig);
            sCachedRawConfig = normalizedConfig;
            sCachedConfig = config;
            return config;
        }
    }

    private static Config parseConfig(String rawConfig) {
        Config config = new Config();
        if (TextUtils.isEmpty(rawConfig)) {
            return config;
        }

        try {
            JSONObject root = new JSONObject(rawConfig);
            loadPackageSet(root, KEY_DATA_ISOLATION, config.mDataIsolationPackages);
            loadSpoofSettingsMap(root, config.mSpoofSettingsMap);
        } catch (Exception ignored) {
        }
        return config;
    }

    private static void loadPackageSet(JSONObject root, String key, Set<String> target) {
        JSONArray packages = root.optJSONArray(key);
        if (packages == null) {
            return;
        }
        for (int i = 0; i < packages.length(); i++) {
            String packageName = packages.optString(i);
            if (!TextUtils.isEmpty(packageName)) {
                target.add(packageName);
            }
        }
    }

    private static void loadSpoofSettingsMap(JSONObject root,
            Map<String, Set<String>> spoofSettingsMap) {
        JSONObject spoofSettings = root.optJSONObject(KEY_SPOOF_SETTINGS_MAP);
        if (spoofSettings == null) {
            return;
        }

        Iterator<String> packages = spoofSettings.keys();
        while (packages.hasNext()) {
            String packageName = packages.next();
            if (TextUtils.isEmpty(packageName)) {
                continue;
            }

            JSONArray settings = spoofSettings.optJSONArray(packageName);
            if (settings == null || settings.length() == 0) {
                continue;
            }

            Set<String> enabledSettings = new HashSet<>();
            for (int i = 0; i < settings.length(); i++) {
                String settingName = settings.optString(i);
                if (!TextUtils.isEmpty(settingName)
                        && SPOOFED_SETTINGS.containsKey(settingName)) {
                    enabledSettings.add(settingName);
                }
            }

            if (!enabledSettings.isEmpty()) {
                spoofSettingsMap.put(packageName, enabledSettings);
            }
        }
    }

    private static final class Config {
        private final Set<String> mDataIsolationPackages = new HashSet<>();
        private final Map<String, Set<String>> mSpoofSettingsMap = new HashMap<>();
    }
}
