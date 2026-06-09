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

package com.android.appdatabackup;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.appbackup.AppBackupInfo;
import android.app.appbackup.AppDataBackupRestoreManager;
import android.app.appbackup.BackupRecord;
import android.app.appbackup.BackupResult;
import android.app.appbackup.IBackupProgressCallback;
import android.app.appbackup.IRestoreProgressCallback;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main activity for the App Data Backup & Restore UI.
 *
 * <p>Layout:
 * <ul>
 *   <li><b>Apps</b> tab - lists user apps with checkboxes; "Back up selected" FAB</li>
 *   <li><b>Backups</b> tab - lists existing backups; per-item restore / delete</li>
 * </ul>
 */
public class AppDataBackupActivity extends Activity {

    private static final String TAG = "AppDataBackupUI";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG)
            || Log.isLoggable(TAG, Log.VERBOSE);
    private static final int MENU_SELECT_ALL = Menu.FIRST;
    private static final int MENU_DESELECT_ALL = Menu.FIRST + 1;

    private AppDataBackupRestoreManager mManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    // Store backups where users can inspect the APKs and tar archives directly.
    private File mBackupDir;
    private String mCurrentOperationToken;

    private TabLayout mTabLayout;
    private ViewPager2 mViewPager;
    private FloatingActionButton mFab;
    private ProgressBar mProgressBar;
    private TextView mProgressText;
    private Switch mExcludeCacheSwitch;

    private final List<AppBackupInfo> mApps = new ArrayList<>();
    private final Set<String> mSelectedPackages = new HashSet<>();
    private final List<BackupRecord> mBackups = new ArrayList<>();

    private AppListAdapter mAppAdapter;
    private BackupListAdapter mBackupAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_backup);

        mManager = (AppDataBackupRestoreManager)
                getSystemService(APP_DATA_BACKUP_SERVICE);

        mBackupDir = new File("/data/media/" + UserHandle.myUserId() + "/AppDataBackup");

        mTabLayout = findViewById(R.id.tab_layout);
        mViewPager = findViewById(R.id.view_pager);
        mFab = findViewById(R.id.fab_backup);
        mProgressBar = findViewById(R.id.progress_bar);
        mProgressText = findViewById(R.id.progress_text);
        mExcludeCacheSwitch = findViewById(R.id.switch_exclude_cache);

        setupViewPager();
        setupFab();
        loadAppsAsync();
        loadBackupsAsync();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_SELECT_ALL, 0, "Select all");
        menu.add(Menu.NONE, MENU_DESELECT_ALL, 1, "Deselect all");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_SELECT_ALL) {
            for (AppBackupInfo info : mApps) mSelectedPackages.add(info.getPackageName());
            mAppAdapter.notifyDataSetChanged();
            return true;
        } else if (item.getItemId() == MENU_DESELECT_ALL) {
            mSelectedPackages.clear();
            mAppAdapter.notifyDataSetChanged();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupViewPager() {
        final RecyclerView appsRv = new RecyclerView(this);
        appsRv.setLayoutManager(new LinearLayoutManager(this));
        appsRv.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        mAppAdapter = new AppListAdapter();
        appsRv.setAdapter(mAppAdapter);

        final RecyclerView backupsRv = new RecyclerView(this);
        backupsRv.setLayoutManager(new LinearLayoutManager(this));
        backupsRv.addItemDecoration(
                new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        mBackupAdapter = new BackupListAdapter();
        backupsRv.setAdapter(mBackupAdapter);

        mViewPager.setAdapter(new TabPagerAdapter(appsRv, backupsRv));

        new TabLayoutMediator(mTabLayout, mViewPager,
                (tab, position) -> tab.setText(position == 0 ? "Apps" : "Backups"))
                .attach();

        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                mFab.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void setupFab() {
        mFab.setOnClickListener(v -> {
            if (mSelectedPackages.isEmpty()) {
                Toast.makeText(this, "Select at least one app", Toast.LENGTH_SHORT).show();
                return;
            }
            startBackup();
        });
    }

    private void loadAppsAsync() {
        mExecutor.submit(() -> {
            final List<AppBackupInfo> apps = mManager.getInstalledApps();
            if (DEBUG) {
                Log.d(TAG, "Loaded " + apps.size() + " apps for backup UI");
            }
            mMainHandler.post(() -> {
                mApps.clear();
                mApps.addAll(apps);
                mAppAdapter.notifyDataSetChanged();
            });
        });
    }

    private void loadBackupsAsync() {
        mExecutor.submit(() -> {
            mBackupDir.mkdirs();
            final List<BackupRecord> backups =
                    mManager.getAvailableBackups(mBackupDir.getAbsolutePath());
            if (DEBUG) {
                Log.d(TAG, "Loaded " + backups.size() + " backups from " + mBackupDir);
            }
            mMainHandler.post(() -> {
                mBackups.clear();
                mBackups.addAll(backups);
                mBackupAdapter.notifyDataSetChanged();
            });
        });
    }

    private void startBackup() {
        final boolean excludeCache = mExcludeCacheSwitch != null
                && mExcludeCacheSwitch.isChecked();
        showProgress("Preparing backup...");

        mCurrentOperationToken = mManager.backupPackages(
                new ArrayList<>(mSelectedPackages),
                mBackupDir.getAbsolutePath(),
                excludeCache,
                new IBackupProgressCallback.Stub() {
                    @Override
                    public void onBackupStarted(String token, int total) {
                        updateProgress("Backing up 0 / " + total + " apps...");
                    }

                    @Override
                    public void onPackageBackupStarted(String token, String pkg,
                            int idx, int total) {
                        updateProgress("Backing up " + pkg + " (" + idx + "/" + total + ")");
                    }

                    @Override
                    public void onPackageBackupFinished(String token, String pkg,
                            BackupResult result) {
                        if (!result.isSuccess()) {
                            Log.w(TAG, "Backup failed for " + pkg + ": " + result.getMessage());
                        }
                    }

                    @Override
                    public void onBackupFinished(String token, BackupResult result) {
                        mMainHandler.post(() -> {
                            hideProgress();
                            Toast.makeText(AppDataBackupActivity.this,
                                    result.isSuccess() ? "Backup complete" : result.getMessage(),
                                    Toast.LENGTH_LONG).show();
                            loadBackupsAsync();
                        });
                    }

                    @Override
                    public void onBackupCancelled(String token) {
                        mMainHandler.post(() -> {
                            hideProgress();
                            Toast.makeText(AppDataBackupActivity.this,
                                    "Backup cancelled", Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    private void cancelCurrentOperation() {
        if (mCurrentOperationToken != null) {
            mManager.cancelOperation(mCurrentOperationToken);
        }
    }

    private void startRestore(BackupRecord record) {
        new AlertDialog.Builder(this)
                .setTitle("Restore " + record.getLabel() + "?")
                .setMessage("This will reinstall the APK and overwrite all app data.\n"
                        + "The app will be force-stopped during restore.")
                .setPositiveButton("Restore", (d, w) -> doRestore(record))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doRestore(BackupRecord record) {
        showProgress("Restoring " + record.getLabel() + "...");

        final List<String> ids = java.util.Collections.singletonList(record.getId());
        mCurrentOperationToken = mManager.restorePackages(
                ids,
                record.getBackupDir(),
                new IRestoreProgressCallback.Stub() {
                    @Override
                    public void onRestoreStarted(String token, int total) {}

                    @Override
                    public void onPackageRestoreStarted(String token, String pkg,
                            int idx, int total) {
                        updateProgress("Installing APK for " + pkg + "...");
                    }

                    @Override
                    public void onPackageDataRestoring(String token, String pkg) {
                        updateProgress("Restoring data for " + pkg + "...");
                    }

                    @Override
                    public void onPackageRestoreFinished(String token, String pkg,
                            BackupResult result) {}

                    @Override
                    public void onRestoreFinished(String token, BackupResult result) {
                        mMainHandler.post(() -> {
                            hideProgress();
                            Toast.makeText(AppDataBackupActivity.this,
                                    result.isSuccess()
                                            ? "Restore complete - relaunch the app"
                                            : "Restore failed: " + result.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onRestoreCancelled(String token) {
                        mMainHandler.post(() -> {
                            hideProgress();
                            Toast.makeText(AppDataBackupActivity.this,
                                    "Restore cancelled", Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    private void showProgress(String message) {
        mMainHandler.post(() -> {
            mProgressBar.setVisibility(View.VISIBLE);
            mProgressText.setVisibility(View.VISIBLE);
            mProgressText.setText(message);
            mFab.setEnabled(false);
        });
    }

    private void updateProgress(String message) {
        mMainHandler.post(() -> mProgressText.setText(message));
    }

    private void hideProgress() {
        mProgressBar.setVisibility(View.GONE);
        mProgressText.setVisibility(View.GONE);
        mFab.setEnabled(true);
    }

    private final class AppListAdapter
            extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

        private static final SimpleDateFormat SDF =
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final AppBackupInfo info = mApps.get(position);
            holder.label.setText(info.getLabel());
            holder.pkg.setText(info.getPackageName() + "  v" + info.getVersionName());
            holder.dataSize.setText(formatBytes(info.getDataSize()));
            holder.checkbox.setChecked(mSelectedPackages.contains(info.getPackageName()));
            holder.checkbox.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) mSelectedPackages.add(info.getPackageName());
                else mSelectedPackages.remove(info.getPackageName());
            });
            holder.itemView.setOnClickListener(v ->
                    holder.checkbox.setChecked(!holder.checkbox.isChecked()));
        }

        @Override
        public int getItemCount() { return mApps.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView label, pkg, dataSize;
            CheckBox checkbox;
            ViewHolder(View v) {
                super(v);
                label = v.findViewById(R.id.tv_label);
                pkg = v.findViewById(R.id.tv_package);
                dataSize = v.findViewById(R.id.tv_data_size);
                checkbox = v.findViewById(R.id.checkbox);
            }
        }
    }

    private final class BackupListAdapter
            extends RecyclerView.Adapter<BackupListAdapter.ViewHolder> {

        private static final SimpleDateFormat SDF =
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_backup, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final BackupRecord record = mBackups.get(position);
            holder.label.setText(record.getLabel());
            holder.pkg.setText(record.getPackageName());
            holder.meta.setText("v" + record.getVersionName()
                    + "  •  " + SDF.format(new Date(record.getTimestampMs()))
                    + "  •  " + formatBytes(record.getTotalSize()));
            holder.btnRestore.setOnClickListener(v -> startRestore(record));
            holder.btnDelete.setOnClickListener(v -> confirmDelete(record));
        }

        @Override
        public int getItemCount() { return mBackups.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView label, pkg, meta;
            Button btnRestore, btnDelete;
            ViewHolder(View v) {
                super(v);
                label = v.findViewById(R.id.tv_label);
                pkg = v.findViewById(R.id.tv_package);
                meta = v.findViewById(R.id.tv_meta);
                btnRestore = v.findViewById(R.id.btn_restore);
                btnDelete = v.findViewById(R.id.btn_delete);
            }
        }

        private void confirmDelete(BackupRecord record) {
            new AlertDialog.Builder(AppDataBackupActivity.this)
                    .setTitle("Delete backup?")
                    .setMessage("Delete backup of " + record.getLabel()
                            + " from " + new SimpleDateFormat("yyyy-MM-dd HH:mm",
                            Locale.getDefault()).format(new Date(record.getTimestampMs()))
                            + "?\nThis cannot be undone.")
                    .setPositiveButton("Delete", (d, w) -> {
                        mExecutor.submit(() -> {
                            mManager.deleteBackup(record.getId(), record.getBackupDir());
                            mMainHandler.post(() -> loadBackupsAsync());
                        });
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private static final class TabPagerAdapter extends RecyclerView.Adapter<TabPagerAdapter.VH> {

        private final RecyclerView[] mPages;

        TabPagerAdapter(RecyclerView... pages) { mPages = pages; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final RecyclerView page = mPages[viewType];
            page.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            return new VH(page);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {}

        @Override
        public int getItemCount() { return mPages.length; }

        @Override
        public int getItemViewType(int position) { return position; }

        static class VH extends RecyclerView.ViewHolder {
            VH(RecyclerView rv) { super(rv); }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024)
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
