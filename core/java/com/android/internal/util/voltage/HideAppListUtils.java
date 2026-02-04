package com.android.internal.util.voltage;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class HideAppListUtils {
    enum Action {
        ADD,
        REMOVE,
        SET
    }

    private static boolean isBootCompleted() {
        return SystemProperties.getBoolean("sys.boot_completed", false);
    }

    private static final Set<String> HMA_SENSITIVE_PACKAGES = new HashSet<>(Arrays.asList(
        "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk",
        "me.weishu.kernelsu",
        "io.github.a13e300.ksuwebui",
        "com.dergoogler.mmrl",
        "me.bmax.apatch",
        "me.garfieldhan.apatch.next",
        "com.jhc.detach",
        "org.adaway",
        "org.lsposed.manager",
        "org.meowcat.edxposed.manager",
        "de.robv.android.xposed.installer",
        "io.github.lsposed.manager",
        "com.drdisagree.iconify",
        "com.zhenxi.hunter",
        "com.scottyab.rootbeer.sample",
        "com.scottyab.rootcheck",
        "com.joeykrim.rootcheck",
        "com.stericson.busybox",
        "com.kikyps.crackme",
        "com.reveny.nativecheck",
        "icu.nullptr.nativetest",
        "io.github.rabehx.securify",
        "io.github.vvb2060.mahoshojo",
        "io.github.huskydg.memorydetector",
        "org.akanework.checker",
        "icu.nullptr.applistdetector",
        "com.byxiaorun.detector",
        "com.androidfung.drminfo",
        "org.matrix.demo",
        "com.rem01gaming.disclosure",
        "luna.safe.luna",
        "com.detect.mt",
        "io.liankong.riskdetector",
        "com.suisho.rc",
        "com.ahmed.security_tester",
        "id.my.pjm.qbcd_okr_dvii",
        "wu.Zygisk.Detector",
        "com.atominvention.rootchecker",
        "com.studio.duckdetector",
        "com.chuqniudetector",
        "com.lingqing.detector",
        "com.android.nativetest",
        "com.youhu.laifu",
        "chunqiu.safe.detector",
        "chunqiu.safe",
        "wu.Rookie.Detector",
        "com.fkjc.zcro",
        "wu.keyChain.test",
        "at.persie0.root_detection_app",
        "at.austriao.fake_gps_detector_app",
        "io.ngankbakaa.lineage.detector",
        "com.dexprotector.detector.envchecks",
        "krypton.tbsafetychecker",
        "gr.nikolasspyr.integritycheck",
        "com.henrikherzig.playintegritychecker",
        "com.thend.integritychecker",
        "com.flinkapps.safteynet",
        "com.bryancandi.knoxcheck",

        "com.termux",
        "com.termux.api",
        "com.offsec.nethunter",
        "com.happymod.apk",
        "com.chelpus.lackypatch",
        "com.dimonvideo.luckypatcher",
        "ru.zdevs.zarchiver",
        "com.mixplorer",
        "bin.mt.plus",
        "com.speedsoftware.rootexplorer",

        // Custom ROM Specifics
        "org.lineageos.lineageparts",
        "org.lineageos.lineagesettings",
        "org.lineageos.glimpse",
        "org.lineageos.aperture",
        "com.power.hub",
        "org.voltage.updater",
        "com.android.settings.intelligence",
        "me.phh.treble.overlay",
        "me.phh.treble.app"
    ));

    private static final Set<String> SPOOFED_SETTINGS_ZERO = new HashSet<>(Arrays.asList(
        "adb_enabled",
        "development_settings_enabled",
        "adb_wifi_enabled",

    ));

    private static final Set<String> SENSITIVE_PERMISSIONS = new HashSet<>(Arrays.asList(
        "android.permission.ACCESS_SUPERUSER"
    ));

    private static final String[] SENSITIVE_SERVICE_PREFIXES = new String[] {
        "lineage.",
        "org.lineageos.",
        "vendor.lineage.",
        "voltage.",
        "org.voltage.",
        "vendor.voltage.",
    };

    public static boolean shouldSpoofSetting(String settingName) {
        return SPOOFED_SETTINGS_ZERO.contains(settingName);
    }

    public static String getSpoofedSetting(Context context, String settingName) {
        if (!isBootCompleted()) return null;
        if (!shouldSpoofSetting(settingName)) return null;
        return "0";
    }

    public static boolean isSensitivePermission(String permission) {
        return permission != null && SENSITIVE_PERMISSIONS.contains(permission);
    }

    public static boolean shouldHideService(String serviceName) {
        if (serviceName == null) return false;
        for (String prefix : SENSITIVE_SERVICE_PREFIXES) {
            if (serviceName.startsWith(prefix)) return true;
        }
        return false;
    }

    public static boolean isHmaSensitivePackage(String packageName) {
        if (packageName == null) return false;
        if (HMA_SENSITIVE_PACKAGES.contains(packageName)) return true;
        if (packageName.startsWith("org.lineageos.") || 
            packageName.startsWith("com.voltage.") ||
            packageName.startsWith("org.voltage.")) {
            return true;
        }

        if (packageName.toLowerCase().contains("magisk") ||
            packageName.toLowerCase().contains("xposed") ||
            packageName.toLowerCase().contains("lsposed")) {
            return true;
        }
        return false;
    }

    public static boolean shouldHideProcess(Context context, String processName) {
        return shouldHideAppList(context, processName);
    }

    public static boolean shouldHideAppList(Context context, String packageName) {
        return shouldHideAppList(context.getContentResolver(), packageName);
    }

    public static boolean shouldHideAppList(ContentResolver cr, String packageName) {
        if (cr == null || packageName == null || !isBootCompleted()) {
            return false;
        }

        if (isHmaSensitivePackage(packageName)) {
            return true;
        }

        Set<String> apps = getApps(cr);
        if (apps.isEmpty()) {
            return false;
        }

        return apps.contains(packageName);
    }

    public static Set<String> getApps(Context context) {
        if (context == null) {
            return new HashSet<>();
        }

        return getApps(context.getContentResolver());
    }

    public static Set<String> getApps(ContentResolver cr) {
        if (cr == null) {
            return new HashSet<>();
        }

        String apps = "";
        try {
            apps = Settings.Secure.getString(cr, Settings.Secure.HIDE_APPLIST);
        } catch (IllegalStateException e) {
            return new HashSet<>();
        }
        if (apps != null && !apps.isEmpty() && !apps.equals(",")) {
            return new HashSet<>(Arrays.asList(apps.split(",")));
        }

        return new HashSet<>();
    }

    private static void putAppsForUser(
            Context context, String packageName, int userId, Action action) {
        if (context == null || userId < 0) {
            return;
        }

        final Set<String> apps = getApps(context);
        switch (action) {
            case ADD:
                apps.add(packageName);
                break;
            case REMOVE:
                apps.remove(packageName);
                break;
            case SET:
                // Don't change
                break;
        }

        Settings.Secure.putStringForUser(
                context.getContentResolver(),
                Settings.Secure.HIDE_APPLIST,
                String.join(",", apps),
                userId);
    }

    public void addApp(Context mContext, String packageName, int userId) {
        if (mContext == null || packageName == null || userId < 0) {
            return;
        }

        putAppsForUser(mContext, packageName, userId, Action.ADD);
    }

    public void removeApp(Context mContext, String packageName, int userId) {
        if (mContext == null || packageName == null || userId < 0) {
            return;
        }

        putAppsForUser(mContext, packageName, userId, Action.REMOVE);
    }

    public void setApps(Context mContext, int userId) {
        if (mContext == null || userId < 0) {
            return;
        }

        putAppsForUser(mContext, null, userId, Action.SET);
    }
}
