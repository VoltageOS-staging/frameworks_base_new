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


import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import com.android.server.ServiceThread;

public class AxBgThread extends ServiceThread {
    public static Handler sHandler;
    public static HandlerExecutor sHandlerExecutor;
    public static AxBgThread sInstance;

    public AxBgThread() {
        super("bg.ax", 0, true);
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            AxBgThread bgthread = new AxBgThread();
            sInstance = bgthread;
            bgthread.start();
            Looper looper = sInstance.getLooper();
            looper.setTraceTag(524288L);
            looper.setSlowLogThresholdMs(5000L, 10000L);
            sHandler = new Handler(sInstance.getLooper());
            sHandlerExecutor = new HandlerExecutor(sHandler);
        }
    }

    public static AxBgThread get() {
        AxBgThread bgthread;
        synchronized (AxBgThread.class) {
            ensureThreadLocked();
            bgthread = sInstance;
        }
        return bgthread;
    }
}
