// SPDX-License-Identifier: Apache-2.0
package app.seamlessupdate.client;

import android.content.SharedPreferences;

import java.util.Objects;

/**
 * An installed update that did not start. After the reboot the phone runs the build from before,
 * because the bootloader went back to the old slot. The first failure is retried like any update,
 * as many are one-off. After the second failure in a row, automatic checks skip that build until
 * another build is installed. A check the user starts still installs it.
 */
final class BootFailure {
    static final int AUTOMATIC_ATTEMPTS = 2;
    // The build an installed update starts after the next reboot, and the build time before it.
    private static final String KEY_PENDING_INCREMENTAL = "boot_pending_incremental";
    private static final String KEY_PENDING_DATE = "boot_pending_date";
    private static final String KEY_SOURCE_DATE = "boot_source_date";
    // The last build that did not start, and how many times in a row.
    private static final String KEY_FAILED_INCREMENTAL = "boot_failed_incremental";
    private static final String KEY_FAILED_DATE = "boot_failed_date";
    private static final String KEY_FAILURES = "boot_failures";

    private BootFailure() {}

    /** An update to this build is installed. Another build ends the failures of the last one. */
    static void installed(final SharedPreferences preferences, final String incremental,
            final long date, final long sourceDate) {
        if (incremental == null) return;
        final SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY_PENDING_INCREMENTAL, incremental)
                .putLong(KEY_PENDING_DATE, date)
                .putLong(KEY_SOURCE_DATE, sourceDate);
        if (!isFailed(preferences, incremental, date)) clearFailures(editor);
        editor.commit();
    }

    /**
     * At boot, with the running build time. Only the first boot after an install counts, and only
     * when the phone runs the build it ran before. True when the installed update did not start
     * for the second time in a row.
     */
    static boolean booted(final SharedPreferences preferences, final long date) {
        final SharedPreferences.Editor editor = preferences.edit();
        // A build at least as new as the failed one runs (it started, or came another way).
        if (date >= preferences.getLong(KEY_FAILED_DATE, Long.MAX_VALUE)) clearFailures(editor);
        final String pending = preferences.getString(KEY_PENDING_INCREMENTAL, null);
        final long pendingDate = preferences.getLong(KEY_PENDING_DATE, 0);
        final boolean failed = pending != null && date < pendingDate
                && date == preferences.getLong(KEY_SOURCE_DATE, 0);
        editor.remove(KEY_PENDING_INCREMENTAL).remove(KEY_PENDING_DATE).remove(KEY_SOURCE_DATE);
        if (!failed) {
            editor.commit();
            return false;
        }
        final int failures = isFailed(preferences, pending, pendingDate)
                ? preferences.getInt(KEY_FAILURES, 0) + 1 : 1;
        editor.putString(KEY_FAILED_INCREMENTAL, pending)
                .putLong(KEY_FAILED_DATE, pendingDate)
                .putInt(KEY_FAILURES, failures)
                .commit();
        return failures >= AUTOMATIC_ATTEMPTS;
    }

    /** Whether automatic checks skip this build. */
    static boolean blocked(final SharedPreferences preferences, final String incremental,
            final long date) {
        return isFailed(preferences, incremental, date)
                && preferences.getInt(KEY_FAILURES, 0) >= AUTOMATIC_ATTEMPTS;
    }

    /** The build automatic checks skip, or null. */
    static String blockedBuild(final SharedPreferences preferences) {
        return preferences.getInt(KEY_FAILURES, 0) >= AUTOMATIC_ATTEMPTS
                ? preferences.getString(KEY_FAILED_INCREMENTAL, null) : null;
    }

    private static boolean isFailed(final SharedPreferences preferences, final String incremental,
            final long date) {
        return Objects.equals(incremental, preferences.getString(KEY_FAILED_INCREMENTAL, null))
                && date == preferences.getLong(KEY_FAILED_DATE, 0);
    }

    private static SharedPreferences.Editor clearFailures(final SharedPreferences.Editor editor) {
        return editor.remove(KEY_FAILED_INCREMENTAL).remove(KEY_FAILED_DATE).remove(KEY_FAILURES);
    }
}
