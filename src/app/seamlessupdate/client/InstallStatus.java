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

    // Runs the screen asked for (a picked package or a check) and the service has not finished yet.
    private static int picks;
    private static int checks;
    private static boolean running;
    // The run the service works on now was asked for on the screen, not started by the periodic job.
    private static boolean runningClaimed;
    private static long runningSince;
    // Step of the run, or how it ended. 0 when there is nothing to show.
    private static int titleResId;
    private static int percent = -1;
    // The build the run installs, once the check found it. Null before that and for picked packages.
    private static String target;

    private InstallStatus() {}

    /** Reserves the service for a picked package. False while it works on any update. */
    static synchronized boolean claim(final int firstStepResId) {
        if (busy()) return false;
        picks++;
        target = null;
        step(firstStepResId, -1);
        return true;
    }

    /** Reserves the service for a check asked for on the screen. False while it works on any update. */
    static synchronized boolean claimCheck(final int firstStepResId) {
        if (busy()) return false;
        checks++;
        target = null;
        step(firstStepResId, -1);
        return true;
    }

    /** Gives a reservation back when the service could not be started for it. */
    static synchronized void release(final boolean picked) {
        if (picked && picks > 0) picks--;
        if (!picked && checks > 0) checks--;
        if (!busy()) step(0, -1);
        notifyListener();
    }

    static synchronized boolean busy() {
        return running || picks > 0 || checks > 0;
    }

    /**
     * Busy with something the screen should show as such. A run of the periodic job counts only
     * after its first second, so a quick background check does not make the screen flicker.
     */
    static synchronized boolean shownBusy(final long nowMillis) {
        return picks > 0 || checks > 0
                || (running && (runningClaimed || nowMillis - runningSince >= 1000));
    }

    /** claimed: the run comes from claim() or claimCheck(). */
    static synchronized void started(final boolean claimed, final long nowMillis) {
        running = true;
        runningClaimed = claimed;
        runningSince = nowMillis;
        if (!claimed) {
            target = null;
            titleResId = 0;
            percent = -1;
        }
        notifyListener();
    }

    /** Keeps the last step or result on screen. The flags name what the screen claimed for this run. */
    static synchronized void finished(final boolean picked, final boolean claimedCheck) {
        running = false;
        runningClaimed = false;
        if (picked && picks > 0) picks--;
        if (claimedCheck && checks > 0) checks--;
        notifyListener();
    }

    static synchronized void target(final String build) {
        target = build;
        notifyListener();
    }

    /** percent is -1 while the step has no known progress. Late calls after the run are dropped. */
    static synchronized void show(final int resId, final int value) {
        if (!busy()) return;
        step(resId, value);
    }

    private static void step(final int resId, final int value) {
        titleResId = resId;
        percent = value < 0 ? -1 : Math.min(value, 100);
        notifyListener();
    }

    static synchronized int titleResId() {
        return titleResId;
    }

    static synchronized int percent() {
        return percent;
    }

    static synchronized String target() {
        return target;
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
