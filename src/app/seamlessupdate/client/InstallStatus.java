// SPDX-License-Identifier: Apache-2.0
package app.seamlessupdate.client;

import android.os.Handler;
import android.os.Looper;

/** What the service is doing, for the settings screen in the same process. */
final class InstallStatus {
    interface Listener {
        void onInstallStatusChanged();
    }

    private static Handler mainHandler;
    // Main thread only.
    private static Listener listener;

    // Picked packages handed to the service and not finished yet.
    private static int picks;
    private static boolean running;
    // Step of an install from storage, or its result. 0 when there is nothing to show.
    private static int titleResId;
    private static int percent = -1;

    private InstallStatus() {}

    /** Reserves the service for a picked package. False while it works on any update. */
    static synchronized boolean claim(final int firstStepResId) {
        if (busy()) return false;
        picks++;
        show(firstStepResId, -1);
        return true;
    }

    static synchronized boolean busy() {
        return running || picks > 0;
    }

    static synchronized void started() {
        running = true;
    }

    /** Keeps the last step or result on screen. */
    static synchronized void finished(final boolean picked) {
        running = false;
        if (picked && picks > 0) picks--;
        notifyListener();
    }

    /** percent is -1 while the step has no known progress. */
    static synchronized void show(final int resId, final int value) {
        titleResId = resId;
        percent = value;
        notifyListener();
    }

    static synchronized int titleResId() {
        return titleResId;
    }

    static synchronized int percent() {
        return percent;
    }

    static void setListener(final Listener value) {
        listener = value;
    }

    private static void notifyListener() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            if (listener != null) listener.onInstallStatusChanged();
        });
    }
}
