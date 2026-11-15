package app.seamlessupdate.client;

import android.net.Network;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.os.UserManager;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;
import android.app.job.JobInfo;
import android.app.AlertDialog;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import androidx.preference.Preference;
import androidx.preference.ListPreference;
import androidx.preference.TwoStatePreference;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.preference.PreferenceFragment;

import java.text.NumberFormat;

import static java.util.Objects.requireNonNull;

public class Settings extends CollapsingToolbarBaseActivity {
    private static final String KEY_CHANNEL = "channel";
    static final String KEY_USE_SECURITY_PREVIEW_CHANNEL = "use_security_preview_channel";
    private static final String KEY_NETWORK_TYPE = "network_type";
    private static final String KEY_BATTERY_NOT_LOW = "battery_not_low";
    private static final String KEY_REQUIRES_CHARGING = "requires_charging";
    private static final String KEY_IDLE_REBOOT = "idle_reboot";
    private static final String KEY_CHECK_FOR_UPDATES = "check_for_updates";
    private static final String KEY_INSTALL_FROM_FILE = "install_from_file";
    private static final String KEY_UPDATE_STATUS = "update_status";
    private static final String KEY_WHATS_NEW = "whats_new";
    private static final String KEY_RELEASE_NOTES = "release_notes";
    static final String KEY_WAITING_FOR_REBOOT = "waiting_for_reboot";

    static SharedPreferences getPreferences(final Context context) {
        final Context deviceContext = context.createDeviceProtectedStorageContext();
        return PreferenceManager.getDefaultSharedPreferences(deviceContext);
    }

    static String getChannel(final Context context) {
        // DiamaneOS publishes no security preview releases: no -security-preview channels.
        return getPreferences(context).getString(KEY_CHANNEL,
                context.getString(R.string.channel_default));
    }

    static boolean shouldUseSecurityPreviewChannel(final Context context) {
        int val = getPreferences(context).getInt(KEY_USE_SECURITY_PREVIEW_CHANNEL, -1);
        switch (val) {
            case 0:
                return false;
            case 1:
                return true;
            default:
                return false;
        }
    }

    static int getNetworkType(final Context context) {
        return getPreferences(context).getInt(KEY_NETWORK_TYPE,
                Integer.parseInt(context.getString(R.string.network_type_default)));
    }

    static boolean getBatteryNotLow(final Context context) {
        return getPreferences(context).getBoolean(KEY_BATTERY_NOT_LOW,
                Boolean.parseBoolean(context.getString(R.string.battery_not_low_default)));
    }

    static boolean getRequiresCharging(final Context context) {
        return getPreferences(context).getBoolean(KEY_REQUIRES_CHARGING,
                Boolean.parseBoolean(context.getString(R.string.requires_charging_default)));
    }

    static boolean getIdleReboot(final Context context) {
        return getPreferences(context).getBoolean(KEY_IDLE_REBOOT,
                Boolean.parseBoolean(context.getString(R.string.idle_reboot_default)));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UserManager userManager = (UserManager) getSystemService(USER_SERVICE);
        if (!userManager.isSystemUser()) {
            throw new SecurityException("system user only");
        }

        setContentView(R.layout.settings_activity);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        // Closes the activity when the up action is clicked
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragment
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        private static String TAG = "SettingsFragment";

        // The read grant goes with the service start, so the copy can outlive this screen.
        private final ActivityResultLauncher<String[]> pickPackage = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) {
                        return;
                    }
                    if (getPreferences(requireContext()).getBoolean(KEY_WAITING_FOR_REBOOT, false)
                            || !InstallStatus.claim(com.android.internal.R.string.reboot_to_update_package)) {
                        toast(R.string.install_from_file_busy);
                        return;
                    }
                    try {
                        final Context context = requireContext();
                        context.startForegroundService(new Intent(context, Service.class)
                                .setData(uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    } catch (RuntimeException e) {
                        // A reservation the service never sees would grey out the screen for good.
                        Log.e(TAG, "cannot start the service for a picked package", e);
                        InstallStatus.release(true);
                    }
                });

        private final Handler handler = new Handler(Looper.getMainLooper());
        // The step last announced to accessibility services, so each step is read out once.
        private int announcedResId;
        // The notes rows read files, so they are worked out again only when a preference changed,
        // not at every progress step.
        private int notesGeneration;
        private int notesShownGeneration = -1;
        private boolean hasTargetNotes;
        private boolean hasNotes;

        // Starts a check the screen reserved, or gives the reservation back.
        private void startCheck(final Context context, final Network network) {
            if (getPreferences(context).getBoolean(KEY_WAITING_FOR_REBOOT, false)
                    || !InstallStatus.claimCheck(R.string.notification_check_title)) {
                refresh();
                return;
            }
            try {
                final var intent = new Intent(context, Service.class);
                intent.putExtra(Service.INTENT_EXTRA_IS_USER_INITIATED, true);
                intent.putExtra(Service.INTENT_EXTRA_CLAIMED_CHECK, true);
                intent.putExtra(Service.INTENT_EXTRA_NETWORK, network);
                context.startForegroundService(intent);
            } catch (RuntimeException e) {
                Log.e(TAG, "cannot start the service for a check", e);
                InstallStatus.release(false);
            }
        }

        private void showAlertDialogForMismatchedNetwork(final Context context, final Network network, final int messageId) {
            new AlertDialog.Builder(context)
                .setTitle(android.R.string.dialog_alert_title)
                .setMessage(messageId)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> startCheck(context, network))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {})
                .show();
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            // The status line changes with every percent, and every change wakes
            // the list's scrollbar. The list is short, so it has none, and no change animation.
            getListView().setVerticalScrollBarEnabled(false);
            final RecyclerView.ItemAnimator animator = getListView().getItemAnimator();
            if (animator instanceof SimpleItemAnimator) {
                ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
            }
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            getPreferenceManager().setStorageDeviceProtected();
            setPreferencesFromResource(R.xml.settings, rootKey);

            requirePreference(KEY_WHATS_NEW).setOnPreferenceClickListener(pref -> {
                openNotes(NotesStore.targetKey(requireContext()), NotesStore.targetBuild(requireContext()), true);
                return true;
            });
            requirePreference(KEY_RELEASE_NOTES).setOnPreferenceClickListener(pref -> {
                openNotes(NotesStore.runningKey(), android.os.Build.VERSION.INCREMENTAL, false);
                return true;
            });

            requirePreference("download_servers").setOnPreferenceClickListener(pref -> {
                try {
                    startActivity(de.diamaneos.downloads.DownloadPolicyClient.settingsIntent(requireContext()));
                } catch (android.content.ActivityNotFoundException | SecurityException e) {
                    pref.setEnabled(false);
                }
                return true;
            });

            requirePreference(KEY_CHECK_FOR_UPDATES).setOnPreferenceClickListener(pref -> {
                final Context context = requireContext();
                // The row is greyed out then, but a tap can race the change.
                if (!getPreferences(context).getBoolean(KEY_WAITING_FOR_REBOOT, false) && !InstallStatus.busy()) {
                    final ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
                    final Network network = connectivityManager.getActiveNetwork();
                    if (network == null) {
                        Log.w(TAG, "checkForUpdates.onClickListener – network will be unavailable");
                    } else {
                        final int networkType = Settings.getNetworkType(context);
                        final NetworkCapabilities networkCapabilities = connectivityManager.getNetworkCapabilities(network);
                        if (networkCapabilities != null && networkType == JobInfo.NETWORK_TYPE_UNMETERED 
                            && !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                                showAlertDialogForMismatchedNetwork(context, network, R.string.alert_metered_network);
                                return true;
                        }
                        if (networkCapabilities != null && networkType == JobInfo.NETWORK_TYPE_NOT_ROAMING 
                            && !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) {
                                showAlertDialogForMismatchedNetwork(context, network, R.string.alert_roaming_network);
                                return true;
                        }
                    }
                    startCheck(context, network);
                }
                return true;
            });

            requirePreference(KEY_INSTALL_FROM_FILE).setOnPreferenceClickListener(pref -> {
                if (getPreferences(requireContext()).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                    toast(R.string.notification_reboot_text);
                } else if (InstallStatus.busy()) {
                    toast(R.string.install_from_file_busy);
                } else if (!ReleaseKeys.trusted()) {
                    toast(R.string.install_from_file_summary_untrusted_build);
                } else {
                    pickPackage.launch(new String[] {"application/zip"});
                }
                return true;
            });

            requirePreference(KEY_NETWORK_TYPE).setOnPreferenceChangeListener((pref, newValue) -> {
                final int value = Integer.parseInt((String) newValue);
                getPreferences(requireContext()).edit().putInt(KEY_NETWORK_TYPE, value).apply();
                if (!getPreferences(requireContext()).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                    PeriodicJob.schedule(requireContext());
                }
                return true;
            });

            updateAndReturnSecurityPreviewPreference().setOnPreferenceChangeListener((pref, newValue) -> {
                // This preference is intentionally marked as persistent=false in XML to avoid
                // automatic clobbering of the default value. Handle persistence manually.
                Context context = requireContext();
                SharedPreferences prefs = getPreferences(context);
                boolean res = prefs.edit()
                        .putInt(KEY_USE_SECURITY_PREVIEW_CHANNEL, ((boolean) newValue) ? 1 : 0)
                        .commit();
                if (res) {
                    if (!prefs.getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                        PeriodicJob.schedule(requireContext());
                    }
                    NotificationHandler.cancelSetSecurityPreviewNotification(requireContext());
                }
                return res;
            });
        }

        private TwoStatePreference updateAndReturnSecurityPreviewPreference() {
            final TwoStatePreference useSecurityPreviewChannel =
                    requirePreference(KEY_USE_SECURITY_PREVIEW_CHANNEL);
            final boolean newChecked = shouldUseSecurityPreviewChannel(requireContext());
            useSecurityPreviewChannel.setChecked(newChecked);
            return useSecurityPreviewChannel;
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (key == null) return;
            switch (key) {
                case KEY_CHANNEL:
                    // An update found on the old channel is not offered any more.
                    NotesStore.clearTarget(requireContext());
                    NotesStore.prune(requireContext());
                    if (!getPreferences(requireContext()).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                        PeriodicJob.schedule(requireContext());
                    }
                    break;
                case KEY_BATTERY_NOT_LOW:
                case KEY_REQUIRES_CHARGING:
                    if (!getPreferences(requireContext()).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                        PeriodicJob.schedule(requireContext());
                    }
                    break;
                case KEY_IDLE_REBOOT:
                    if (!getIdleReboot(requireContext())) {
                        IdleReboot.cancel(requireContext());
                    }
                    break;
            }
            // The service writes the waiting state, the update it found, stored notes and the last check here.
            notesGeneration++;
            if (isResumed()) refresh();
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
            final ListPreference networkType = (ListPreference) findPreference(KEY_NETWORK_TYPE);
            networkType.setValue(Integer.toString(getNetworkType(requireContext())));
            // User can open updater settings and then open security preview settings from the
            // notification.
            updateAndReturnSecurityPreviewPreference();
            InstallStatus.setListener(this::refresh);
            notesGeneration++;
            refresh();
        }

        @Override
        public void onPause() {
            super.onPause();
            getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
            InstallStatus.setListener(null);
            handler.removeCallbacksAndMessages(null);
        }

        private void toast(final int resId) {
            Toast.makeText(requireContext(), resId, Toast.LENGTH_LONG).show();
        }

        private void openNotes(final String key, final String build, final boolean target) {
            if (key == null || build == null) return;
            startActivity(new Intent(requireContext(), ReleaseNotesActivity.class)
                    .putExtra(ReleaseNotesActivity.EXTRA_KEY, key)
                    .putExtra(ReleaseNotesActivity.EXTRA_BUILD, build)
                    .putExtra(ReleaseNotesActivity.EXTRA_TARGET, target));
        }

        /**
         * Everything on the screen that depends on the service: the status line, which rows can be
         * used and why not, and the notes rows. Runs on every status and preference change.
         */
        private void refresh() {
            if (!isAdded()) return;
            final Context context = requireContext();
            final SharedPreferences preferences = getPreferences(context);
            final boolean trusted = ReleaseKeys.trusted();
            final boolean waiting = preferences.getBoolean(KEY_WAITING_FOR_REBOOT, false);
            final long now = System.currentTimeMillis();
            final boolean busy = InstallStatus.shownBusy(now);
            if (InstallStatus.busy() && !busy) {
                // A run of the periodic job shows once it takes longer than a second.
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(this::refresh, 1000);
            }
            final String target = NotesStore.targetBuild(context);
            final int stepResId = InstallStatus.titleResId();

            // The status line.
            final Preference status = requirePreference(KEY_UPDATE_STATUS);
            final String runTarget = InstallStatus.target() != null ? InstallStatus.target() : target;
            CharSequence title = null;
            CharSequence summary = null;
            if (waiting) {
                title = getString(R.string.notification_reboot_title);
                summary = getString(R.string.notification_reboot_text);
            } else if (busy && stepResId != 0) {
                title = runTarget != null ? getString(R.string.status_target_title, runTarget)
                        : getString(R.string.status_title);
                summary = step(stepResId, InstallStatus.percent());
            } else if (!busy && stepResId != 0) {
                // How the last run in this process ended.
                title = getString(stepResId);
                if (target != null && stepResId != R.string.notification_updated_title) {
                    summary = getString(R.string.status_available_title, target);
                }
            } else if (!busy && target != null) {
                title = getString(R.string.status_available_title, target);
            } else if (!busy) {
                final long last = preferences.getLong(NotesStore.KEY_LAST_CHECK, 0);
                if (last > 0) {
                    title = getString(R.string.notification_updated_title);
                    summary = getString(R.string.status_last_checked, DateUtils.formatDateTime(context, last,
                            DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME));
                }
            }
            status.setTitle(title);
            status.setSummary(summary);
            status.setVisible(title != null);
            if (busy && stepResId != 0 && stepResId != announcedResId) {
                announcedResId = stepResId;
                getListView().announceForAccessibility(getString(stepResId));
            }

            // Check for updates: a build with public test keys still checks.
            final Preference check = requirePreference(KEY_CHECK_FOR_UPDATES);
            check.setEnabled(!waiting && !busy);
            final String failedBuild = BootFailure.blockedBuild(preferences);
            if (!trusted) {
                check.setSummary(R.string.check_for_updates_summary_untrusted_build);
            } else if (waiting) {
                check.setSummary(R.string.update_waiting_summary);
            } else if (busy) {
                check.setSummary(R.string.update_busy_summary);
            } else if (failedBuild != null) {
                check.setSummary(getString(R.string.check_for_updates_summary_boot_failed, failedBuild));
            } else {
                check.setSummary(R.string.check_for_updates_summary);
            }

            // Install from storage: a build with public test keys installs nothing.
            final Preference install = requirePreference(KEY_INSTALL_FROM_FILE);
            install.setEnabled(trusted && !waiting && !busy);
            if (!trusted) {
                install.setSummary(R.string.install_from_file_summary_untrusted_build);
            } else if (waiting) {
                install.setSummary(R.string.update_waiting_summary);
            } else if (busy) {
                install.setSummary(R.string.update_busy_summary);
            } else {
                install.setSummary(R.string.install_from_file_summary);
            }

            // Notes of the update and of the running build, when the phone has them.
            if (notesShownGeneration != notesGeneration) {
                notesShownGeneration = notesGeneration;
                hasTargetNotes = target != null && NotesStore.has(context, NotesStore.targetKey(context));
                hasNotes = NotesStore.has(context, NotesStore.runningKey());
            }
            final Preference whatsNew = requirePreference(KEY_WHATS_NEW);
            whatsNew.setVisible(hasTargetNotes);
            if (hasTargetNotes) whatsNew.setTitle(getString(R.string.whats_new_title, target));
            final Preference notes = requirePreference(KEY_RELEASE_NOTES);
            notes.setVisible(hasNotes);
            if (hasNotes) {
                notes.setSummary(getString(R.string.release_notes_summary, android.os.Build.VERSION.INCREMENTAL));
            }
        }

        private CharSequence step(final int resId, final int percent) {
            if (percent < 0) return getString(resId);
            return getString(R.string.install_from_file_progress, getString(resId),
                    NumberFormat.getPercentInstance().format(percent / 100.0));
        }

        private <T extends Preference> T requirePreference(String key) {
            return requireNonNull(findPreference(key));
        }
    }
}
