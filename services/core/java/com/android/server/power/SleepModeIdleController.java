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

package com.android.server.power;

import android.app.ActivityManager;
import android.app.role.RoleManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.UserHandle;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

final class SleepModeIdleController {

    private static final String TAG = "SleepModeIdleController";

    private static final int TARGET_BUCKET = UsageStatsManager.STANDBY_BUCKET_RESTRICTED;

    private final Context mContext;

    SleepModeIdleController(Context context) {
        mContext = context;
    }

    void runSleepPass() {
        BackgroundThread.getHandler().post(this::runSleepPassInternal);
    }

    void restoreFromSleep() {
        BackgroundThread.getHandler().post(this::restoreFromSleepInternal);
    }

    private void runSleepPassInternal() {
        final int userId = ActivityManager.getCurrentUser();
        if (!shouldApplyIdleMode(userId)) {
            restoreFromSleepInternal();
            return;
        }
        final String existingSnapshot = getSnapshot(userId);
        if (!TextUtils.isEmpty(existingSnapshot)) {
            Slog.v(TAG, "Sleep pass already applied for user " + userId);
            return;
        }

        final Context userContext = createUserContext(userId);
        if (userContext == null) {
            return;
        }

        final UsageStatsManager usageStatsManager =
                userContext.getSystemService(UsageStatsManager.class);
        final ActivityManager activityManager = userContext.getSystemService(ActivityManager.class);
        final PackageManager packageManager = userContext.getPackageManager();
        if (usageStatsManager == null || activityManager == null || packageManager == null) {
            Slog.w(TAG, "Sleep pass aborted: missing required services");
            return;
        }

        final Set<String> protectedPackages = getProtectedPackages(userContext, packageManager,
                userId);
        final Set<String> foregroundPackages = getForegroundPackages(
                activityManager.getRunningAppProcesses());
        final List<ApplicationInfo> installedApps = packageManager.getInstalledApplicationsAsUser(
                PackageManager.ApplicationInfoFlags.of(0), userId);
        final JSONObject snapshot = new JSONObject();
        int restrictedCount = 0;

        for (ApplicationInfo applicationInfo : installedApps) {
            if (!shouldRestrict(applicationInfo, protectedPackages, foregroundPackages)) {
                continue;
            }

            final String packageName = applicationInfo.packageName;
            try {
                final int currentBucket = usageStatsManager.getAppStandbyBucket(packageName);
                if (currentBucket >= TARGET_BUCKET) {
                    continue;
                }
                usageStatsManager.setAppStandbyBucket(packageName, TARGET_BUCKET);
                snapshot.put(packageName, currentBucket);
                restrictedCount++;
            } catch (Exception e) {
                Slog.w(TAG, "Failed to restrict " + packageName, e);
            }
        }

        putSnapshot(userId, snapshot.length() == 0 ? "" : snapshot.toString());
        Slog.i(TAG, "Sleep idle pass complete for user " + userId
                + ", restricted=" + restrictedCount);
    }

    private void restoreFromSleepInternal() {
        final int userId = ActivityManager.getCurrentUser();
        final String snapshotJson = getSnapshot(userId);
        if (TextUtils.isEmpty(snapshotJson)) {
            return;
        }

        final Context userContext = createUserContext(userId);
        if (userContext == null) {
            return;
        }

        final UsageStatsManager usageStatsManager =
                userContext.getSystemService(UsageStatsManager.class);
        if (usageStatsManager == null) {
            Slog.w(TAG, "Restore aborted: UsageStatsManager unavailable");
            return;
        }

        int restoredCount = 0;
        try {
            final JSONObject snapshot = new JSONObject(snapshotJson);
            final Iterator<String> packages = snapshot.keys();
            while (packages.hasNext()) {
                final String packageName = packages.next();
                final int originalBucket = snapshot.optInt(packageName,
                        UsageStatsManager.STANDBY_BUCKET_ACTIVE);
                try {
                    usageStatsManager.setAppStandbyBucket(packageName, originalBucket);
                    restoredCount++;
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to restore " + packageName, e);
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to parse Sleep Mode idle snapshot", e);
        } finally {
            putSnapshot(userId, "");
        }

        Slog.i(TAG, "Sleep idle restore complete for user " + userId
                + ", restored=" + restoredCount);
    }

    private Context createUserContext(int userId) {
        try {
            return mContext.createContextAsUser(UserHandle.of(userId), 0);
        } catch (Exception e) {
            Slog.w(TAG, "Unable to create user context for user " + userId, e);
            return null;
        }
    }

    private boolean shouldRestrict(ApplicationInfo applicationInfo, Set<String> protectedPackages,
            Set<String> foregroundPackages) {
        if (applicationInfo == null || !applicationInfo.enabled) {
            return false;
        }

        final String packageName = applicationInfo.packageName;
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }

        if (applicationInfo.uid < Process.FIRST_APPLICATION_UID) {
            return false;
        }

        if (applicationInfo.isSystemApp() || applicationInfo.isUpdatedSystemApp()) {
            return false;
        }

        if (protectedPackages.contains(packageName)) {
            return false;
        }

        return !foregroundPackages.contains(packageName);
    }

    private Set<String> getProtectedPackages(Context userContext, PackageManager packageManager,
            int userId) {
        final ArraySet<String> packages = new ArraySet<>();
        packages.add("android");
        packages.add("com.android.systemui");

        final RoleManager roleManager = userContext.getSystemService(RoleManager.class);
        if (roleManager != null) {
            addRoleHolders(packages, roleManager, RoleManager.ROLE_DIALER);
            addRoleHolders(packages, roleManager, RoleManager.ROLE_SMS);
        }

        addResolvedActivity(packages, packageManager,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), userId);
        addResolvedActivity(packages, packageManager, new Intent(AlarmClock.ACTION_SHOW_ALARMS),
                userId);

        return packages;
    }

    private void addRoleHolders(Set<String> packages, RoleManager roleManager, String roleName) {
        try {
            packages.addAll(roleManager.getRoleHolders(roleName));
        } catch (Exception e) {
            Slog.v(TAG, "Unable to resolve holders for role " + roleName, e);
        }
    }

    private void addResolvedActivity(Set<String> packages, PackageManager packageManager,
            Intent intent, int userId) {
        try {
            final ResolveInfo resolveInfo = packageManager.resolveActivityAsUser(intent,
                    PackageManager.MATCH_DEFAULT_ONLY, userId);
            if (resolveInfo != null && resolveInfo.activityInfo != null) {
                packages.add(resolveInfo.activityInfo.packageName);
            }
        } catch (Exception e) {
            Slog.v(TAG, "Unable to resolve protected activity for " + intent.getAction(), e);
        }
    }

    private Set<String> getForegroundPackages(
            List<ActivityManager.RunningAppProcessInfo> runningAppProcesses) {
        final ArraySet<String> packages = new ArraySet<>();
        if (runningAppProcesses == null) {
            return packages;
        }

        for (ActivityManager.RunningAppProcessInfo processInfo : runningAppProcesses) {
            if (processInfo == null || processInfo.pkgList == null) {
                continue;
            }
            if (processInfo.importance
                    > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
                continue;
            }
            for (String packageName : processInfo.pkgList) {
                packages.add(packageName);
            }
        }
        return packages;
    }

    private String getSnapshot(int userId) {
        return Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_IDLE_SNAPSHOT, userId);
    }

    private boolean shouldApplyIdleMode(int userId) {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_ENABLED, 0, userId) == 1
                && Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_IDLE_TOGGLE, 0, userId) == 1;
    }

    private void putSnapshot(int userId, String snapshot) {
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_IDLE_SNAPSHOT, snapshot, userId);
    }
}
