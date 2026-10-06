package app.seamlessupdate.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.UserManager;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (context.getSystemService(UserManager.class).isSystemUser()) {
            final SharedPreferences preferences = Settings.getPreferences(context);
            preferences.edit().putBoolean(Settings.KEY_WAITING_FOR_REBOOT, false).apply();
            PeriodicJob.schedule(context);
            // DiamaneOS publishes no security preview releases, so there is nothing to opt into.
        } else {
            context.getPackageManager().setApplicationEnabledSetting(context.getPackageName(),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
        }
    }
}
