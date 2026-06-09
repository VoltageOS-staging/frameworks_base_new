/*
 * Copyright (C) 2026 Voltage OS
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

package com.android.server.appbackup;

import android.app.appbackup.BackupRecord;
import android.app.appbackup.BackupResult;
import android.app.appbackup.AppBackupInfo;
import android.app.appbackup.IBackupProgressCallback;
import android.app.appbackup.IRestoreProgressCallback;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @hide
 */
class AppDataBackupShellCommand extends ShellCommand {

    private static final long TIMEOUT_MS = 5 * 60 * 1000;

    private final AppDataBackupService.BinderService mService;

    AppDataBackupShellCommand(AppDataBackupService.BinderService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(null);
        }
        switch (cmd) {
            case "list":    return cmdList();
            case "backup":  return cmdBackup();
            case "restore": return cmdRestore();
            case "delete":  return cmdDelete();
            case "apps":    return cmdApps();
            default:        return handleDefaultCommands(cmd);
        }
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("App Data Backup commands:");
        pw.println("  list   --dir <path> [--user <userId>]");
        pw.println("             List available backups in <path>");
        pw.println("  apps   [--user <userId>]");
        pw.println("             List user-installed apps eligible for backup");
        pw.println("  backup --package <pkg>[,<pkg>...] --dir <path>");
        pw.println("         [--exclude-cache] [--user <userId>]");
        pw.println("             Back up one or more packages to <path>");
        pw.println("  restore --id <id>[,<id>...] --dir <path> [--user <userId>]");
        pw.println("             Restore backup(s) from <path>");
        pw.println("  delete  --id <id> --dir <path>");
        pw.println("             Permanently delete a backup");
    }

    private int cmdList() {
        String dir = null;
        int userId = 0;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--dir":    dir = getNextArgRequired(); break;
                case "--user":   userId = Integer.parseInt(getNextArgRequired()); break;
            }
        }
        if (dir == null) { getErrPrintWriter().println("--dir required"); return 1; }

        final List<BackupRecord> records = mService.getAvailableBackups(dir, userId);
        final PrintWriter pw = getOutPrintWriter();
        if (records.isEmpty()) {
            pw.println("No backups found in " + dir);
            return 0;
        }
        final SimpleDateFormat sdf =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        for (BackupRecord r : records) {
            pw.printf("%-50s  %-30s  %s  %,d bytes%n",
                    r.getId(),
                    r.getLabel() + " (" + r.getVersionName() + ")",
                    sdf.format(new Date(r.getTimestampMs())),
                    r.getTotalSize());
        }
        return 0;
    }

    private int cmdApps() {
        int userId = 0;
        String opt;
        while ((opt = getNextOption()) != null) {
            if ("--user".equals(opt)) userId = Integer.parseInt(getNextArgRequired());
        }
        final List<AppBackupInfo> apps = mService.getInstalledApps(userId);
        final PrintWriter pw = getOutPrintWriter();
        for (AppBackupInfo info : apps) {
            pw.printf("%-50s  %s%n", info.getPackageName(),
                    info.getLabel() + "  v" + info.getVersionName());
        }
        pw.println(apps.size() + " apps");
        return 0;
    }

    private int cmdBackup() {
        String packages = null, dir = null;
        boolean excludeCache = false;
        int userId = 0;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--package":      packages = getNextArgRequired(); break;
                case "--dir":          dir = getNextArgRequired(); break;
                case "--exclude-cache": excludeCache = true; break;
                case "--user":         userId = Integer.parseInt(getNextArgRequired()); break;
            }
        }
        if (packages == null || dir == null) {
            getErrPrintWriter().println("--package and --dir required"); return 1;
        }
        final List<String> pkgList = java.util.Arrays.asList(packages.split(","));
        final String backupDir = dir;
        final PrintWriter pw = getOutPrintWriter();
        final CountDownLatch latch = new CountDownLatch(1);
        final int[] exitCode = {0};

        mService.backupPackages(pkgList, dir, excludeCache, userId,
                new IBackupProgressCallback.Stub() {
                    @Override public void onBackupStarted(String t, int total) {
                        pw.println("Backing up " + total + " package(s) to " + backupDir + "...");
                        pw.flush();
                    }
                    @Override public void onPackageBackupStarted(String t, String pkg,
                            int idx, int total) {
                        pw.println("[" + idx + "/" + total + "] " + pkg);
                        pw.flush();
                    }
                    @Override public void onPackageBackupFinished(String t, String pkg,
                            BackupResult r) {
                        pw.println("  -> " + (r.isSuccess() ? "OK" : "FAILED: " + r.getMessage()));
                        pw.flush();
                    }
                    @Override public void onBackupFinished(String t, BackupResult r) {
                        pw.println(r.isSuccess() ? "Done." : "Finished with errors: " + r.getMessage());
                        pw.flush();
                        if (!r.isSuccess()) exitCode[0] = 1;
                        latch.countDown();
                    }
                    @Override public void onBackupCancelled(String t) {
                        pw.println("Cancelled.");
                        pw.flush();
                        exitCode[0] = 1;
                        latch.countDown();
                    }
                });

        try {
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return exitCode[0];
    }

    private int cmdRestore() {
        String ids = null, dir = null;
        int userId = 0;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--id":   ids = getNextArgRequired(); break;
                case "--dir":  dir = getNextArgRequired(); break;
                case "--user": userId = Integer.parseInt(getNextArgRequired()); break;
            }
        }
        if (ids == null || dir == null) {
            getErrPrintWriter().println("--id and --dir required"); return 1;
        }
        final List<String> idList = java.util.Arrays.asList(ids.split(","));
        final PrintWriter pw = getOutPrintWriter();
        final CountDownLatch latch = new CountDownLatch(1);
        final int[] exitCode = {0};

        mService.restorePackages(idList, dir, userId,
                new IRestoreProgressCallback.Stub() {
                    @Override public void onRestoreStarted(String t, int total) {
                        pw.println("Restoring " + total + " package(s)...");
                        pw.flush();
                    }
                    @Override public void onPackageRestoreStarted(String t, String pkg,
                            int idx, int total) {
                        pw.println("[" + idx + "/" + total + "] " + pkg + " - installing APK...");
                        pw.flush();
                    }
                    @Override public void onPackageDataRestoring(String t, String pkg) {
                        pw.println("  -> restoring data...");
                        pw.flush();
                    }
                    @Override public void onPackageRestoreFinished(String t, String pkg,
                            BackupResult r) {
                        pw.println("  -> " + (r.isSuccess() ? "OK" : "FAILED: " + r.getMessage()));
                        pw.flush();
                    }
                    @Override public void onRestoreFinished(String t, BackupResult r) {
                        pw.println(r.isSuccess() ? "Done." : "Finished with errors: " + r.getMessage());
                        pw.flush();
                        if (!r.isSuccess()) exitCode[0] = 1;
                        latch.countDown();
                    }
                    @Override public void onRestoreCancelled(String t) {
                        pw.println("Cancelled.");
                        pw.flush();
                        exitCode[0] = 1;
                        latch.countDown();
                    }
                });

        try {
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return exitCode[0];
    }

    private int cmdDelete() {
        String id = null, dir = null;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--id":  id = getNextArgRequired(); break;
                case "--dir": dir = getNextArgRequired(); break;
            }
        }
        if (id == null || dir == null) {
            getErrPrintWriter().println("--id and --dir required"); return 1;
        }
        final boolean deleted = mService.deleteBackup(id, dir);
        getOutPrintWriter().println(deleted ? "Deleted." : "Backup not found.");
        return deleted ? 0 : 1;
    }
}
