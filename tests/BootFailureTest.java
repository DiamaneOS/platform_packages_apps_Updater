package app.seamlessupdate.client;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;

public final class BootFailureTest {
    private static void check(boolean v) { if (!v) throw new AssertionError(); }

    // Device-protected preferences as a map. Edits apply on commit, as on Android.
    static final class Preferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        public String getString(String key, String def) { return (String) values.getOrDefault(key, def); }
        public long getLong(String key, long def) { return (Long) values.getOrDefault(key, def); }
        public int getInt(String key, int def) { return (Integer) values.getOrDefault(key, def); }
        public Editor edit() {
            final Map<String, Object> changes = new HashMap<>();
            return new Editor() {
                public Editor putString(String k, String v) { changes.put(k, v); return this; }
                public Editor putLong(String k, long v) { changes.put(k, v); return this; }
                public Editor putInt(String k, int v) { changes.put(k, v); return this; }
                public Editor remove(String k) { changes.put(k, null); return this; }
                public boolean commit() {
                    changes.forEach((k, v) -> { if (v == null) values.remove(k); else values.put(k, v); });
                    return true;
                }
            };
        }
    }

    // S5 is the running build, S6 the update, S7 a newer one.
    private static final String S5 = "2026102197", S6 = "2026102198", S7 = "2026102199";
    private static final long D5 = 500, D6 = 600, D7 = 700;

    // Install from S5, then boot the given build.
    private static void install(Preferences p, String target, long date) { BootFailure.installed(p, target, date, D5); }
    private static boolean fail(Preferences p) { install(p, S6, D6); return BootFailure.booted(p, D5); }

    // S6 boots and the boot hook runs, but the slot is never marked successful. The userdata
    // checkpoint undoes that boot's writes when the bootloader goes back to S5.
    private static boolean failAfterHook(Preferences p) {
        install(p, S6, D6);
        final Preferences boot = new Preferences();
        boot.values.putAll(p.values);
        check(!BootFailure.booted(boot, D6) && boot.values.isEmpty());
        return BootFailure.booted(p, D5);
    }

    public static void main(String[] args) {
        switch (args[0]) {
            case "started": {
                Preferences p = new Preferences();
                check(!BootFailure.booted(p, D5));
                install(p, S6, D6);
                check(!BootFailure.booted(p, D6));
                check(p.values.isEmpty());
                break;
            }
            case "retry_once_then_block": {
                Preferences p = new Preferences();
                check(!fail(p));
                check(!BootFailure.blocked(p, S6, D6) && BootFailure.blockedBuild(p) == null);
                check(fail(p));
                check(BootFailure.blocked(p, S6, D6) && S6.equals(BootFailure.blockedBuild(p)));
                // Another build, or the same name with another build time, is never blocked.
                check(!BootFailure.blocked(p, S7, D7) && !BootFailure.blocked(p, S6, D7));
                break;
            }
            case "only_first_boot_counts": {
                Preferences p = new Preferences();
                check(!fail(p));
                // Later boots without a new install change nothing.
                for (int i = 0; i < 5; i++) check(!BootFailure.booted(p, D5));
                check(!BootFailure.blocked(p, S6, D6));
                break;
            }
            case "started_ends_failures": {
                Preferences p = new Preferences();
                fail(p);
                install(p, S6, D6);
                check(!BootFailure.booted(p, D6));
                // A later failure of the same build starts again at one.
                check(!fail(p) && !BootFailure.blocked(p, S6, D6));
                break;
            }
            case "newer_build_resets": {
                Preferences p = new Preferences();
                fail(p); fail(p);
                check(BootFailure.blocked(p, S6, D6));
                install(p, S7, D7);
                check(!BootFailure.blocked(p, S6, D6) && BootFailure.blockedBuild(p) == null);
                // The newer build fails once: retried, not blocked.
                check(!BootFailure.booted(p, D5) && !BootFailure.blocked(p, S7, D7));
                break;
            }
            case "manual_install_after_block": {
                Preferences p = new Preferences();
                fail(p); fail(p);
                // The user installs it again: still blocked until it starts.
                install(p, S6, D6);
                check(BootFailure.blocked(p, S6, D6));
                check(!BootFailure.booted(p, D6) && !BootFailure.blocked(p, S6, D6));
                check(p.values.isEmpty());
                break;
            }
            case "other_install_runs": {
                // A build at least as new installed another way (sideload) is no failure.
                Preferences p = new Preferences();
                fail(p);
                install(p, S6, D6);
                check(!BootFailure.booted(p, D7) && !BootFailure.blocked(p, S6, D6));
                check(p.values.isEmpty());
                // After a block, too, with nothing pending.
                fail(p); fail(p);
                check(!BootFailure.booted(p, D6) && BootFailure.blockedBuild(p) == null);
                check(p.values.isEmpty());
                break;
            }
            case "other_old_build_runs": {
                // An older build than the one the update came from (flashed): no failure counted.
                Preferences p = new Preferences();
                fail(p);
                install(p, S6, D6);
                check(!BootFailure.booted(p, D5 - 1) && !BootFailure.blocked(p, S6, D6));
                break;
            }
            case "same_name_older_date": {
                // The old build carries the target's name: its build time still tells the fallback.
                Preferences p = new Preferences();
                BootFailure.installed(p, S6, D6, D5);
                check(!BootFailure.booted(p, D5));
                BootFailure.installed(p, S6, D6, D5);
                check(BootFailure.booted(p, D5));
                break;
            }
            case "no_incremental": {
                Preferences p = new Preferences();
                BootFailure.installed(p, null, D6, D5);
                check(!BootFailure.booted(p, D5) && p.values.isEmpty());
                break;
            }
            case "marked_clears": {
                // The marked boot keeps its writes: nothing left, later boots count nothing.
                Preferences p = new Preferences();
                install(p, S6, D6);
                check(!BootFailure.booted(p, D6) && p.values.isEmpty());
                check(!BootFailure.booted(p, D6) && !BootFailure.blocked(p, S6, D6));
                break;
            }
            case "fallback_after_hook_counts": {
                Preferences p = new Preferences();
                check(!failAfterHook(p));
                check(p.values.get("boot_failures").equals(1) && BootFailure.blockedBuild(p) == null);
                break;
            }
            case "second_fallback_after_hook_blocks": {
                Preferences p = new Preferences();
                check(!failAfterHook(p));
                check(failAfterHook(p));
                check(BootFailure.blocked(p, S6, D6) && S6.equals(BootFailure.blockedBuild(p)));
                break;
            }
            case "block_after_hook_ends": {
                // Another build installed ends the block.
                Preferences p = new Preferences();
                failAfterHook(p); failAfterHook(p);
                install(p, S7, D7);
                check(!BootFailure.blocked(p, S6, D6) && BootFailure.blockedBuild(p) == null);
                // A newer build running ends it too.
                p = new Preferences();
                failAfterHook(p); failAfterHook(p);
                check(!BootFailure.booted(p, D7) && BootFailure.blockedBuild(p) == null);
                check(p.values.isEmpty());
                break;
            }
            default: throw new AssertionError(args[0]);
        }
    }
}
