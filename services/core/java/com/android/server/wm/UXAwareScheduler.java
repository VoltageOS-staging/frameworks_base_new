/*
 * Copyright (C) 2025 AxionOS
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
package com.android.server.wm;

import static android.os.Process.THREAD_GROUP_BACKGROUND;

import android.os.*;
import android.os.Process;
import com.android.server.AnimationThread;
import com.android.server.DisplayThread;
import com.android.server.UiThread;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.server.am.ActivityManagerService;
import com.android.server.am.BoostAdjuster;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class UXAwareScheduler implements IWindowEventListener {
    private static final String TAG = "UXAwareScheduler";
    private static final Path DEFAULT_PROC_PATH = Paths.get("/proc");

    private static final int UX_TYPE_OTHER = 1;
    private static final int UX_TYPE_KSWAPD = 2;
    private static final int UX_TYPE_SYSTEM = 3;
    private static final int UX_TYPE_TOPAPP = 4;
    private static final int UX_TYPE_INPUT = 5;
    private static final int UX_TYPE_ANIMATOR = 6;

    private static final int MSG_PROCESS_START = 1;
    private static final int MSG_PROCESS_REMOVED  = 2;
    private static final int MSG_TRACK_SYSTEM = 3;
    private static final int MSG_UNTRACK_APP = 4;
    private static final int MSG_TRACK_COMMON = 5;
    
    private static final int APP_TYPE_LAUNCHER = 1;
    private static final int APP_TYPE_SYSTEMUI = 2;
    
    private final SparseArray<ProcState> mProcStates = new SparseArray<>();

    private final BoostAdjuster mBoosterAdjuster;
    private volatile int mFocusedPid = -1;
    private volatile String mFocusedProcessName = null;
    private boolean mSystemReady = false;

    private final Handler mBgHandler = new BgHandler(AxBgThread.get().getLooper());

    public UXAwareScheduler(BoostAdjuster booster) {
        mBoosterAdjuster = booster;
    }

    public void systemReady() {
        mSystemReady = true;
        WindowEventDispatcher.get().registerListener(this);
        trackSystemThreadsAsync();
    }

    @Override
    public void onAppFocusChanged(ActivityRecord r, Task task) {
        if (r == null) return;
        mBgHandler.post(() -> {
            int pid = r.getPid();
            String procName = r.getProcessName();
            if (mFocusedPid != pid) {
                untrackThreads(mFocusedPid, mFocusedProcessName);
                mFocusedPid = pid;
                mFocusedProcessName = procName;
            }
            handleProcessStart(pid, procName, "focused");
        });
    }

    public void handleProcessStart(int pid, String processName, String reason) {
        mBgHandler.sendMessageDelayed(
            mBgHandler.obtainMessage(MSG_PROCESS_START, new Object[]{pid, processName, reason}), 500
        );
    }

    public void untrackThreads(int pid, String processName) {
        if (pid < 0 || processName == null) return;
        mBgHandler.obtainMessage(MSG_PROCESS_REMOVED, new Object[]{pid, processName}).sendToTarget();
    }

    private void handleProcessStartInner(int pid, String processName, String reason) {
        int type = getAppType(processName);
        logger("Process start: pid=" + pid + " process=" + processName + " type=" + type + " reason=" + reason);
        trackUXThreadsForApp(pid, processName, type);
    }

    private void handleProcessRemoved(int pid, String processName) {
        if (pid <= 0) return;
        ProcState procState = getProcState(pid);
        if (procState == null) return;
        procState.isDead = true;
        logger("Process removed: pid=" + pid + " process=" + processName);
        untrackUXThreadsForAppInner(pid, processName);
        mBgHandler.removeMessages(MSG_TRACK_COMMON, procState);
        removeProcState(pid);
    }

    public void trackSystemThreadsAsync() {
        mBgHandler.sendEmptyMessage(MSG_TRACK_SYSTEM);
    }

    private void handleTrackSystemThreadsInner() {
        try {
            Map<String, Integer> processMap = getProcessMap(-1);

            trackUXThread(AnimationThread.get().getThreadId(), AnimationThread.get().getName(), UX_TYPE_ANIMATOR);
            trackUXThread(SurfaceAnimationThread.get().getThreadId(), SurfaceAnimationThread.get().getName(), UX_TYPE_ANIMATOR);
            trackUXThread(DisplayThread.get().getThreadId(), DisplayThread.get().getName(), UX_TYPE_ANIMATOR);
            trackUXThread(UiThread.get().getThreadId(), UiThread.get().getName(), UX_TYPE_ANIMATOR);

            int serverPid = ActivityManagerService.MY_PID;
            trackUXThreadFromProcess(serverPid, UX_TYPE_INPUT, "InputReader", "InputDispatcher");

            trackUXThreadFromProcessMap(processMap, "surfaceflinger", UX_TYPE_ANIMATOR);

            Integer sfPid = processMap.get("surfaceflinger");
            trackUXThreadFromProcess(sfPid, UX_TYPE_ANIMATOR, "RegionSampling");

            for (String kProc : Arrays.asList("kswapd0", "kcompactd0")) {
                Integer tid = processMap.get(kProc);
                if (tid != null && tid > 0) {
                    trackUXThread(tid, kProc, UX_TYPE_KSWAPD);
                    Process.setThreadGroupAndCpuset(tid, THREAD_GROUP_BACKGROUND);
                }
            }

            trackUXThreadFromProcessMapByPrefix(processMap, "f2fs_ckpt-", UX_TYPE_SYSTEM);
            trackUXThreadFromProcessMapByPrefix(processMap, "ext4-rsv-", UX_TYPE_SYSTEM);

            mBgHandler.post(() -> {
                for (String sys : Arrays.asList("kgsl-events", "kgsl_worker")) {
                    trackUXThreadFromProcessMap(processMap, sys, UX_TYPE_ANIMATOR);
                }
                for (String sys : Arrays.asList("vndservicemanag", "hwservicemanage", "servicemanager")) {
                    trackUXThreadFromProcessMap(processMap, sys, UX_TYPE_SYSTEM);
                }
            });

            trackUXThreadFromProcess(1, UX_TYPE_SYSTEM, "init");
        } catch (Exception e) {
            logger("Error tracking system threads: " + e);
        }
    }

    public void trackUXThreadsForApp(int pid, String processName, int appType) {
        if (!mSystemReady) return;
        ProcState procState = getOrCreateProcState(pid, true);

        if (procState != null) {
            procState.isShowing = true;
        }

        if (procState == null || !procState.needToTrack()) {
            return;
        }

        procState.appType = appType;
        procState.processName = processName;

        logger("trackUXThreadsForApp " + processName);

        if (appType == APP_TYPE_LAUNCHER && !procState.appSpecificTidsTracked) {
            trackUXThread("droid.launcher3", new String[] { "UiThreadHelper" });
            procState.appSpecificTidsTracked = true;
        } else if (appType == APP_TYPE_SYSTEMUI && !procState.appSpecificTidsTracked) {
            trackUXThread(
                "ndroid.systemui",
                new String[] { "ll.splashscreen", "ll.splashworker", "wmshell.main", "wmshell.anim" }
            );
            procState.appSpecificTidsTracked = true;
        } else {
            procState.appSpecificTidsTracked = true;
        }

        if (procState.needToTrack()) {
            if (procState.commonTidsSaved) {
                for (int i = 0; i < procState.commonTids.size(); i++) {
                    trackUXThread(procState.commonTids.keyAt(i), "common", procState.commonTids.valueAt(i));
                }
            } else {
                scheduleTrackCommon(procState, 500);
            }
        }
    }

    private void handleTrackCommonThreads(ProcState procState) {
        if (!procState.needToTrack()) return;

        List<Integer> foundTids = new ArrayList<>();
        boolean success = trackUXThreadFromProcess(procState.pid, UX_TYPE_ANIMATOR, foundTids,
                "hwuiTask0", "hwuiTask1", "mali-event-hand", "mali-mem-purge",
                "mali-cpu-comman", "ged-swd", "RenderThread");

        if (success) {
            procState.saveCommonTids(foundTids, UX_TYPE_ANIMATOR);
            procState.commonTidsTracked = true;
            for (int tid : foundTids) {
                int effectiveType = (procState.appType == APP_TYPE_LAUNCHER || procState.appType == APP_TYPE_SYSTEMUI)
                        ? UX_TYPE_TOPAPP : UX_TYPE_ANIMATOR;
                applyUxBoostInternal(tid, effectiveType, true);
            }
        } else {
            procState.failedCount++;
            if (procState.needToTrack()) scheduleTrackCommon(procState, 3000);
        }
    }

    private void scheduleTrackCommon(ProcState procState, long delayMs) {
        mBgHandler.removeMessages(MSG_TRACK_COMMON, procState);
        mBgHandler.sendMessageDelayed(mBgHandler.obtainMessage(MSG_TRACK_COMMON, procState), delayMs);
    }

    public void untrackUXThreadsForAppInner(int pid, String packageName) {
        ProcState procState = getProcState(pid);
        if (procState != null) procState.isShowing = false;
        if (procState == null || !procState.needToUntrack() || procState.isImportantApp()) return;

        logger("Untracking app threads for " + packageName + " (PID: " + pid + ")");
        for (int i = 0; i < procState.commonTids.size(); i++) {
            untrackUXThread(procState.commonTids.keyAt(i), "common", procState.commonTids.valueAt(i));
        }
        procState.commonTidsTracked = false;
        removeProcState(pid);
    }

    private void trackUXThread(String processName, String[] threadNames) {
        Map<String, Integer> processMap = getProcessMap(-1);
        Integer pid = processMap.get(processName);
        if (pid == null) {
            return;
        }
        for (String threadName : threadNames) {
            int tid = ProcUtils.findTidForTask(pid, threadName);
            if (tid != 0) {
                trackUXThread(tid, threadName, UX_TYPE_ANIMATOR);
            }
        }
    }

    public void trackUXThread(int tid, String processName, int uxType) {
        trackUXThread(-1, tid, processName, uxType);
    }

    public void trackUXThread(final int pid, final int tid, final String processName, final int uxType) {
        if (uxType <= 0 || tid <= 0) return;
        mBgHandler.post(() -> {
            logger("Tracking UX thread: tid=" + tid + " name=" + processName + " type=" + uxType);
            applyUxBoostInternal(tid, uxType, true);
        });
    }

    private boolean trackUXThreadFromProcess(int pid, int uxType, List<Integer> outList, String... threadNames) {
        Set<String> names = new HashSet<>(Arrays.asList(threadNames));
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get("/proc/" + pid + "/task"), "[0-9]*")) {
            for (Path path : ds) {
                int tid = ProcUtils.getProcessId(path);
                String tname = ProcUtils.getThreadName(path);
                if (tid > 0 && tname != null && names.contains(tname)) {
                    names.remove(tname);
                    trackUXThread(tid, tname, uxType);
                    if (outList != null) {
                        outList.add(tid);
                    }
                }
            }
        } catch (Exception e) {
            logger("Failed to iterate /proc/" + pid + "/task" + e);
        }
        return names.isEmpty();
    }

    private boolean trackUXThreadFromProcess(int pid, int uxType, String... threadNames) {
        return trackUXThreadFromProcess(pid, uxType, null, threadNames);
    }

    private void trackUXThreadFromProcessMap(Map<String, Integer> processMap, String name, int uxType) {
        Integer tid = processMap.get(name);
        if (tid != null && tid > 0) trackUXThread(tid, name, uxType);
    }

    private void trackUXThreadFromProcessMapByPrefix(Map<String, Integer> processMap, String prefix, int uxType) {
        for (Map.Entry<String, Integer> e : processMap.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue() > 0) {
                trackUXThread(e.getValue(), e.getKey(), uxType);
            }
        }
    }

    public void untrackUXThread(final int tid, final String processName, final int uxType) {
        if (uxType <= 0 || tid <= 0) return;
        logger("Untracking UX thread: tid=" + tid + " name=" + processName + " type=" + uxType);
        mBgHandler.post(() -> {
            applyUxBoostInternal(tid, uxType, false);
        });
    }

    private void applyUxBoostInternal(final int tid, final int uxTypeId, final boolean enable) {
        if (tid <= 0) return;
        final UxType type = UxType.fromId(uxTypeId);
        if (type == null) return;
        try {
            if (enable) {
                if (type.fifoBoost) {
                    Process.setThreadScheduler(tid,
                            Process.SCHED_FIFO | Process.SCHED_RESET_ON_FORK,
                            type.fifoPrio);
                    logger("FIFO prio=" + type.fifoPrio + " for tid=" + tid + " (" + type + ")");
                }
                if (!"0".equals(type.boostValue)) {
                    mBoosterAdjuster.boostUxThread(tid, type.boostValue, true);
                }
            } else {
                mBoosterAdjuster.boostUxThread(tid, "0", false);
                Process.setThreadScheduler(tid, 0, 0);
                Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DEFAULT);
                logger("Restored scheduler for tid=" + tid + " (" + type + ")");
            }
        } catch (Exception e) {
            logger("applyUxBoostInternal failed tid=" + tid + ": " + e);
        }
    }

    public final Map<String, Integer> getProcessMap(int pid) {
        Map<String, Integer> map = new HashMap<>();
        try {
            Path listingPath = pid > 0 ? Paths.get("/proc/" + pid + "/task") : DEFAULT_PROC_PATH;
            if (!Files.exists(listingPath)) return map;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(listingPath, "[0-9]*")) {
                for (Path path : ds) {
                    int processId = ProcUtils.getProcessId(path);
                    String name = ProcUtils.getThreadName(path);
                    if (processId != -1 && name != null) {
                        map.put(name, processId);
                    }
                }
            }
        } catch (Exception e) {
            logger("getProcessMap failed: " + e);
        }
        return map;
    }

    private ProcState getProcState(int pid) {
        return getOrCreateProcState(pid, false);
    }

    private ProcState getOrCreateProcState(int pid, boolean create) {
        if (pid <= 0) return null;
        synchronized (mProcStates) {
            ProcState state = mProcStates.get(pid);
            if (state == null && create) {
                state = new ProcState(pid);
                mProcStates.put(pid, state);
            }
            return state;
        }
    }

    private void removeProcState(int pid) {
        if (pid <= 0) return;
        synchronized (mProcStates) {
            mProcStates.delete(pid);
        }
    }

    private int getAppType(String processName) {
        if (processName == null) return UX_TYPE_OTHER;
        if (processName.contains("launcher")) return APP_TYPE_LAUNCHER;
        if (processName.contains("systemui")) return APP_TYPE_SYSTEMUI;
        return UX_TYPE_OTHER;
    }

    private static void logger(String msg) {
        if (SystemProperties.getBoolean("persist.sys.ax_scheduler_debug", false)) Slog.d(TAG, msg);
    }
    
    private class BgHandler extends Handler {
        public BgHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PROCESS_START: {
                    Object[] args = (Object[]) msg.obj;
                    handleProcessStartInner((int) args[0], (String) args[1], (String) args[2]);
                    break;
                }
                case MSG_PROCESS_REMOVED: {
                    Object[] args = (Object[]) msg.obj;
                    handleProcessRemoved((int) args[0], (String) args[1]);
                    break;
                }
                case MSG_TRACK_SYSTEM:
                    handleTrackSystemThreadsInner();
                    break;
                case MSG_UNTRACK_APP: {
                    Object[] args = (Object[]) msg.obj;
                    untrackUXThreadsForAppInner((int) args[0], (String) args[1]);
                    break;
                }
                case MSG_TRACK_COMMON:
                    ProcState ps = (ProcState) msg.obj;
                    handleTrackCommonThreads(ps);
                    break;
            }
        }
    }
}
