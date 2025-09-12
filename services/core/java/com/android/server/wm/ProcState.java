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

import android.util.SparseIntArray;

import java.util.List;

public final class ProcState {
    public final int pid;
    public boolean appSpecificTidsTracked = false;
    public boolean commonTidsTracked = false;
    public boolean commonTidsSaved = false;
    public boolean isShowing = false;
    public boolean isDead = false;
    public int appType = 0;
    public String processName;
    public int failedCount = 0;
    public SparseIntArray commonTids = new SparseIntArray();

    public ProcState(int pid) {
        this.pid = pid;
    }

    public boolean needToTrack() {
        if (isDead || !isShowing) return false;
        return !appSpecificTidsTracked || (!commonTidsTracked && failedCount < 3);
    }

    public boolean needToUntrack() {
        if (isDead) return false;
        return commonTidsTracked;
    }

    public boolean isImportantApp() {
        return appType == 1 || appType == 2;
    }

    public void saveCommonTids(List<Integer> tids, int uxType) {
        for (Integer tid : tids) commonTids.put(tid, uxType);
        commonTidsSaved = true;
    }
}
