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

import android.os.FileUtils;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.util.Slog;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class ProcUtils {
    private static final String TAG = "ProcUtils";

    private ProcUtils() {
    }

    public static int findTidForTask(int pid, String name) {
        File taskDir = new File("/proc/" + pid + "/task");
        if (!taskDir.exists() || !taskDir.isDirectory()) {
            return 0;
        }

        File[] taskFolders = taskDir.listFiles();
        if (taskFolders == null || taskFolders.length == 0) return 0;

        Arrays.sort(taskFolders, Comparator.comparingInt(f -> getProcessId(f.toPath())));

        for (File taskFolder : taskFolders) {
            File commFile = new File(taskFolder, "comm");
            if (!commFile.exists()) continue;

            try {
                String content = FileUtils.readTextFile(commFile, 1024, null).trim();
                logger("findTidForTask: commFile: " + commFile + " content: " + content);

                if (content.equals(name)) {
                    int tid = getProcessId(taskFolder.toPath());
                    logger("findTidForTask: Thread found: " + content + " -> " + tid);
                    return tid;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return 0;
    }

    public static int getProcessId(Path path) {
        try {
            return Integer.parseInt(path.getFileName().toString());
        } catch (NumberFormatException e) {
            logger("Failed to parse as process ID: " + e);
            return -1;
        }
    }

    public static String getThreadName(Path taskPath) {
        return readNullSeparatedFile(taskPath.resolve("comm").toString());
    }

    public static String readNullSeparatedFile(String path) {
        String contents = readSingleLineProcFile(path);
        if (contents == null) return null;
        int endIndex = contents.indexOf("\u0000\u0000");
        if (endIndex != -1) {
            contents = contents.substring(0, endIndex);
        }
        return contents.replace("\u0000", " ");
    }

    public static String readSingleLineProcFile(String path) {
        return readTerminatedProcFile(path, (byte) 10);
    }

    public static String readTerminatedProcFile(String path, byte terminator) {
        int savedPolicy = StrictMode.allowThreadDiskReadsMask();
        try {
            return readTerminatedProcFileInternal(path, terminator);
        } finally {
            StrictMode.setThreadPolicyMask(savedPolicy);
        }
    }

    private static String readTerminatedProcFileInternal(String path, byte terminator) {
        try (FileInputStream is = new FileInputStream(path)) {
            ByteArrayOutputStream byteStream = null;
            byte[] buffer = new byte[1024];
            boolean foundTerminator;

            do {
                int len = is.read(buffer);
                if (len <= 0) break;

                int terminatingIndex = -1;
                for (int i = 0; i < len; i++) {
                    if (buffer[i] == terminator) {
                        terminatingIndex = i;
                        break;
                    }
                }

                foundTerminator = terminatingIndex != -1;

                if (foundTerminator && byteStream == null) {
                    return new String(buffer, 0, terminatingIndex);
                }

                if (byteStream == null) {
                    byteStream = new ByteArrayOutputStream(1024);
                }
                byteStream.write(buffer, 0, foundTerminator ? terminatingIndex : len);
            } while (!foundTerminator);

            if (byteStream == null) return "";
            return byteStream.toString();
        } catch (Exception e) {
            return null;
        }
    }
    
    private static void logger(String msg) {
        if (SystemProperties.getBoolean("persist.sys.ax_scheduler_debug", false)) Slog.d(TAG, msg);
    }
}
