package app.seamlessupdate.client;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Network;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.util.Log;

import java.util.Objects;

public class PeriodicJob extends JobService {
    private static final String TAG = "PeriodicJob";
    private static final int JOB_ID_PERIODIC = 1;
    private static final int JOB_ID_RETRY = 2;
    private static final long INTERVAL_MILLIS = 6 * 60 * 60 * 1000;
    private static final long MIN_LATENCY_MILLIS = 4 * 60 * 1000;
    // Consecutive failed automatic attempts. Each retry waits twice as long as the one before,
    // up to the periodic interval, so an unreachable server is not asked every few minutes.
    private static final String PREFERENCE_FAILED_ATTEMPTS = "failed_attempts";
    private static final String EXTRA_JOB_CHANNEL = "extra_job_channel";

    static void schedule(final Context context) {
        final String channel = SystemProperties.get("sys.update.channel", Settings.getChannel(context));
        final int networkType = Settings.getNetworkType(context);
        final boolean batteryNotLow = Settings.getBatteryNotLow(context);
        final boolean requiresCharging = Settings.getRequiresCharging(context);
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        final JobInfo jobInfo = scheduler.getPendingJob(JOB_ID_PERIODIC);
        if (jobInfo != null &&
                jobInfo.getNetworkType() == networkType &&
                jobInfo.isRequireBatteryNotLow() == batteryNotLow &&
                jobInfo.isRequireCharging() == requiresCharging &&
                jobInfo.isPersisted() &&
                jobInfo.getIntervalMillis() == INTERVAL_MILLIS &&
                Objects.equals(jobInfo.getExtras().getString(EXTRA_JOB_CHANNEL), channel)) {
            Log.d(TAG, "Periodic job already registered");
            return;
        }
        final PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_JOB_CHANNEL, channel);
        final ComponentName serviceName = new ComponentName(context, PeriodicJob.class);
        final int result = scheduler.schedule(new JobInfo.Builder(JOB_ID_PERIODIC, serviceName)
            .setRequiredNetworkType(networkType)
            .setRequiresBatteryNotLow(batteryNotLow)
            .setRequiresCharging(requiresCharging)
            .setPersisted(true)
            .setPeriodic(INTERVAL_MILLIS)
            .setExtras(extras)
            .build());
        if (result == JobScheduler.RESULT_FAILURE) {
            Log.d(TAG, "Periodic job schedule failed");
        }
    }

    static void scheduleRetry(final Context context) {
        final SharedPreferences preferences = Settings.getPreferences(context);
        final int failures = preferences.getInt(PREFERENCE_FAILED_ATTEMPTS, 0);
        preferences.edit().putInt(PREFERENCE_FAILED_ATTEMPTS, failures + 1).apply();
        final long latency = Math.min(MIN_LATENCY_MILLIS << Math.min(failures, 16), INTERVAL_MILLIS);
        Log.d(TAG, "Retry after failed attempt " + (failures + 1) + " in " + latency / 1000 + " s");
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        final ComponentName serviceName = new ComponentName(context, PeriodicJob.class);
        final int result = scheduler.schedule(new JobInfo.Builder(JOB_ID_RETRY, serviceName)
            .setRequiredNetworkType(Settings.getNetworkType(context))
            .setRequiresBatteryNotLow(Settings.getBatteryNotLow(context))
            .setRequiresCharging(Settings.getRequiresCharging(context))
            .setMinimumLatency(latency)
            .build());
        if (result == JobScheduler.RESULT_FAILURE) {
            Log.d(TAG, "Retry job schedule failed");
        }
    }

    static void resetRetryDelay(final Context context) {
        Settings.getPreferences(context).edit().remove(PREFERENCE_FAILED_ATTEMPTS).apply();
    }

    static void cancel(final Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(JOB_ID_PERIODIC);
        scheduler.cancel(JOB_ID_RETRY);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        Log.d(TAG, "onStartJob id: " + params.getJobId());
        final Network network = params.getNetwork();
        if (network == null) {
            Log.e(TAG, "JobParameters have a null Network");
            return false;
        }
        final Intent intent = new Intent(this, Service.class);
        intent.putExtra(Service.INTENT_EXTRA_NETWORK, network);
        startForegroundService(intent);
        return false;
    }

    @Override
    public boolean onStopJob(final JobParameters params) {
        return false;
    }
}
