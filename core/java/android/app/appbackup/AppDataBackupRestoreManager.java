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

package android.app.appbackup;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * Client-side manager for the App Data Backup/Restore system service.
 *
 * <p>Obtain via {@code Context.getSystemService(Context.APP_DATA_BACKUP_SERVICE)}.
 *
 * <p>All methods that start asynchronous operations return an <em>operation token</em>
 * (an opaque string) that can be passed to {@link #cancelOperation} to abort the
 * work in progress.
 *
 * @hide
 */
@SystemService(Context.APP_DATA_BACKUP_SERVICE)
public class AppDataBackupRestoreManager {

    private static final String TAG = "AppDataBackupMgr";

    public static final String SERVICE_NAME = "app_data_backup";

    private final IAppDataBackupService mService;
    private final int mUserId;

    /** @hide */
    public AppDataBackupRestoreManager(Context context, IAppDataBackupService service) {
        mService = service;
        mUserId = context.getUserId();
    }

    private static IAppDataBackupService getService() {
        return IAppDataBackupService.Stub.asInterface(
                ServiceManager.getService(SERVICE_NAME));
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_BACKUP)
    @NonNull
    public List<AppBackupInfo> getInstalledApps() {
        try {
            List<AppBackupInfo> result = mService.getInstalledApps(mUserId);
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            Log.e(TAG, "getInstalledApps failed", e);
            return Collections.emptyList();
        }
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_BACKUP)
    @NonNull
    public List<BackupRecord> getAvailableBackups(@NonNull String backupDir) {
        try {
            List<BackupRecord> result = mService.getAvailableBackups(backupDir, mUserId);
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            Log.e(TAG, "getAvailableBackups failed", e);
            return Collections.emptyList();
        }
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_BACKUP)
    @NonNull
    public String backupPackages(@NonNull List<String> packageNames,
            @NonNull String backupDir,
            boolean excludeCache,
            @Nullable IBackupProgressCallback callback) {
        try {
            return mService.backupPackages(packageNames, backupDir, excludeCache,
                    mUserId, callback);
        } catch (RemoteException e) {
            Log.e(TAG, "backupPackages failed", e);
            return "";
        }
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_RESTORE)
    @NonNull
    public String restorePackages(@NonNull List<String> backupIds,
            @NonNull String backupDir,
            @Nullable IRestoreProgressCallback callback) {
        try {
            return mService.restorePackages(backupIds, backupDir, mUserId, callback);
        } catch (RemoteException e) {
            Log.e(TAG, "restorePackages failed", e);
            return "";
        }
    }

    @RequiresPermission(anyOf = {
            android.Manifest.permission.APP_DATA_BACKUP,
            android.Manifest.permission.APP_DATA_RESTORE
    })
    public void cancelOperation(@NonNull String operationToken) {
        try {
            mService.cancelOperation(operationToken);
        } catch (RemoteException e) {
            Log.e(TAG, "cancelOperation failed", e);
        }
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_BACKUP)
    public boolean deleteBackup(@NonNull String backupId, @NonNull String backupDir) {
        try {
            return mService.deleteBackup(backupId, backupDir);
        } catch (RemoteException e) {
            Log.e(TAG, "deleteBackup failed", e);
            return false;
        }
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_BACKUP)
    @Nullable
    public BackupRecord getBackupRecord(@NonNull String backupId, @NonNull String backupDir) {
        try {
            return mService.getBackupRecord(backupId, backupDir);
        } catch (RemoteException e) {
            Log.e(TAG, "getBackupRecord failed", e);
            return null;
        }
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_BACKUP)
    public boolean isEncryptionAvailable() {
        try {
            return mService.isEncryptionAvailable(mUserId);
        } catch (RemoteException e) {
            Log.e(TAG, "isEncryptionAvailable failed", e);
            return false;
        }
    }
}
