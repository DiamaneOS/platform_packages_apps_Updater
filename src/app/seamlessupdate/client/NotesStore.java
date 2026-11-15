// SPDX-License-Identifier: Apache-2.0
package app.seamlessupdate.client;

import static android.os.Build.VERSION.INCREMENTAL;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/**
 * The release notes on the phone: those of the running build and those of the update a check found,
 * each under its build number and build date. Nothing else is kept.
 */
final class NotesStore {
    private static final String TAG = "NotesStore";
    private static final String DIR = "release-notes";
    private static final String KEY_TARGET_BUILD = "notes_target_build";
    private static final String KEY_TARGET_DATE = "notes_target_date";
    // Keys the servers answered with "not found", so a build without notes is not asked for at every check.
    private static final String KEY_MISSING = "notes_missing";
    static final String KEY_LAST_CHECK = "last_check_up_to_date";
    private static final String KEY_STORED = "notes_stored";

    private NotesStore() {}

    static String runningKey() {
        return ReleaseNotes.key(INCREMENTAL, SystemProperties.getLong("ro.build.date.utc", 0));
    }

    private static File dir(final Context context) {
        return new File(context.createDeviceProtectedStorageContext().getFilesDir(), DIR);
    }

    /** The checked notes under key, or null. They are checked again, as the file could be damaged. */
    static String read(final Context context, final String key) {
        if (key == null) return null;
        final File file = new File(dir(context), key + ".txt");
        try {
            if (!file.isFile() || file.length() > ReleaseNotes.MAX_BYTES) return null;
            return ReleaseNotes.text(Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
            Log.w(TAG, "dropping unreadable notes " + key, e);
            file.delete();
            return null;
        }
    }

    static boolean has(final Context context, final String key) {
        return read(context, key) != null;
    }

    /** Writes checked notes under key through a new file and a rename, so a reader never sees half. */
    static void write(final Context context, final String key, final String text) throws IOException {
        final File dir = dir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        final File temp = new File(dir, key + ".tmp");
        try (final FileOutputStream out = new FileOutputStream(temp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!temp.renameTo(new File(dir, key + ".txt"))) {
            temp.delete();
            throw new IOException("cannot store notes " + key);
        }
        // Tells an open settings screen to show the new notes, and drops a "not found" mark of an earlier check.
        final SharedPreferences preferences = Settings.getPreferences(context);
        final Set<String> missing = new HashSet<>(preferences.getStringSet(KEY_MISSING, Set.of()));
        missing.remove(key);
        preferences.edit().putLong(KEY_STORED, System.currentTimeMillis()).putStringSet(KEY_MISSING, missing).apply();
    }

    /** Deletes every stored file and "not found" mark but those of the running build and the target. */
    static void prune(final Context context) {
        final Set<String> keep = new HashSet<>();
        final String running = runningKey();
        if (running != null) keep.add(running);
        final String target = targetKey(context);
        if (target != null) keep.add(target);
        final File[] files = dir(context).listFiles();
        if (files != null) {
            for (final File file : files) {
                final String name = file.getName();
                if (!name.endsWith(".txt") || !keep.contains(name.substring(0, name.length() - 4))) {
                    file.delete();
                }
            }
        }
        final SharedPreferences preferences = Settings.getPreferences(context);
        final Set<String> missing = new HashSet<>(preferences.getStringSet(KEY_MISSING, Set.of()));
        if (missing.retainAll(keep)) preferences.edit().putStringSet(KEY_MISSING, missing).apply();
    }

    static boolean missing(final Context context, final String key) {
        return Settings.getPreferences(context).getStringSet(KEY_MISSING, Set.of()).contains(key);
    }

    static void markMissing(final Context context, final String key) {
        final SharedPreferences preferences = Settings.getPreferences(context);
        final Set<String> missing = new HashSet<>(preferences.getStringSet(KEY_MISSING, Set.of()));
        missing.add(key);
        preferences.edit().putStringSet(KEY_MISSING, missing).apply();
    }

    /** The update the last check found and that this build will install. */
    static void setTarget(final Context context, final String build, final long date) {
        Settings.getPreferences(context).edit().putString(KEY_TARGET_BUILD, build)
                .putLong(KEY_TARGET_DATE, date).apply();
    }

    static void clearTarget(final Context context) {
        Settings.getPreferences(context).edit().remove(KEY_TARGET_BUILD).remove(KEY_TARGET_DATE).apply();
    }

    /** The target's build number while it is newer than the running build, else null. */
    static String targetBuild(final Context context) {
        final SharedPreferences preferences = Settings.getPreferences(context);
        final String build = preferences.getString(KEY_TARGET_BUILD, null);
        final long date = preferences.getLong(KEY_TARGET_DATE, 0);
        if (build == null || date <= SystemProperties.getLong("ro.build.date.utc", 0)) return null;
        return build;
    }

    static String targetKey(final Context context) {
        final String build = targetBuild(context);
        if (build == null) return null;
        return ReleaseNotes.key(build, Settings.getPreferences(context).getLong(KEY_TARGET_DATE, 0));
    }
}
