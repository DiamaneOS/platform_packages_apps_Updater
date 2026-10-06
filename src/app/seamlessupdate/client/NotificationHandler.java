package app.seamlessupdate.client;

import android.annotation.NonNull;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.text.Html;
import android.text.Spanned;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;

public class NotificationHandler {
    private static enum Phase {
        CHECK, DOWNLOAD, VERIFY, INSTALL
    }

    private static final int NOTIFICATION_ID_PROGRESS = 1;
    private static final int NOTIFICATION_ID_REBOOT = 2;
    private static final int NOTIFICATION_ID_FAILURE = 3;
    private static final int NOTIFICATION_ID_UPDATED = 4;
    private static final int MAX_NOTIFICATION_ID_FOR_SERVICE = NOTIFICATION_ID_UPDATED;
    private static final int NOTIFICATION_ID_SET_SECURITY_PREVIEW = 1000;
    private static final String NOTIFICATION_CHANNEL_ID_PROGRESS = "progress";
    private static final String NOTIFICATION_CHANNEL_ID_REBOOT = "updates2";
    private static final String NOTIFICATION_CHANNEL_ID_FAILURE = "failure";
    private static final String NOTIFICATION_CHANNEL_ID_UPDATED = "updated";
    private static final String NOTIFICATION_CHANNEL_ID_SET_SECURITY_PREVIEW = "set_security_preview";
    private static final int PENDING_REBOOT_ID = 1;
    private static final int PENDING_SETTINGS_ID = 2;
    private static final int PENDING_SECURITY_PREVIEW_SETTINGS_ID = 3;

    private final Service service;
    private final NotificationManager notificationManager;

    private Phase phase;

    NotificationHandler(Service service) {
        this.service = service;
        notificationManager = service.getSystemService(NotificationManager.class);
    }

    static void createNotificationChannels(Context context) {
        var notificationManager = context.getSystemService(NotificationManager.class);
        final List<NotificationChannel> channels = new ArrayList<>();

        // channels are unblockable by default with fixed notification permission
        final NotificationChannel progress = new NotificationChannel(NOTIFICATION_CHANNEL_ID_PROGRESS,
                context.getString(R.string.notification_channel_progress), IMPORTANCE_LOW);
        progress.setBlockable(true);
        channels.add(progress);

        final NotificationChannel reboot = new NotificationChannel(NOTIFICATION_CHANNEL_ID_REBOOT,
                context.getString(R.string.notification_channel_reboot), IMPORTANCE_HIGH);
        reboot.enableLights(true);
        reboot.enableVibration(true);
        channels.add(reboot);

        final NotificationChannel failure = new NotificationChannel(NOTIFICATION_CHANNEL_ID_FAILURE,
                context.getString(R.string.notification_channel_failure), IMPORTANCE_LOW);
        failure.setBlockable(true);
        channels.add(failure);

        final NotificationChannel updated = new NotificationChannel(NOTIFICATION_CHANNEL_ID_UPDATED,
                context.getString(R.string.notification_channel_updated), IMPORTANCE_MIN);
        updated.setShowBadge(false);
        updated.setBlockable(true);
        channels.add(updated);

        final NotificationChannel setSecurityPreview = new NotificationChannel(NOTIFICATION_CHANNEL_ID_SET_SECURITY_PREVIEW,
                context.getString(R.string.notification_channel_set_security_preview), IMPORTANCE_HIGH);
        setSecurityPreview.setShowBadge(false);
        channels.add(setSecurityPreview);

        notificationManager.createNotificationChannels(channels);
    }

    private Notification buildProgressNotification(int resId, long progress, long max) {
        Notification.Builder builder = new Notification.Builder(service, NOTIFICATION_CHANNEL_ID_PROGRESS)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(resId))
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.system_update_fill0_wght400_grad0_opsz48);
        if (max <= 0) {
            builder.setProgress(0, 0, true);
        } else {
            double fraction = (double) progress / (double) max;
            int maxScaled = 100;
            int progressScaled = (int) (fraction * (double) maxScaled);
            builder.setProgress(maxScaled, progressScaled, false);
        }
        return builder.build();
    }

    void start() {
        phase = Phase.CHECK;

        // Avoid cancelling persistent security settings preview notification
        for (int id = 1; id <= MAX_NOTIFICATION_ID_FOR_SERVICE; id++) {
            notificationManager.cancel(id);
        }

        service.startForeground(NOTIFICATION_ID_PROGRESS, new Notification.Builder(this.service, NOTIFICATION_CHANNEL_ID_PROGRESS)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(R.string.notification_check_title))
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.system_update_fill0_wght400_grad0_opsz48).build());
    }

    void showUpdatedNotification(final String channel) {
        final String channelText;
        if ("stable".equals(channel)) {
            channelText = service.getString(R.string.channel_stable);
        } else if ("beta".equals(channel)) {
            channelText = service.getString(R.string.channel_beta);
        } else if ("alpha".equals(channel)) {
            channelText = service.getString(R.string.channel_alpha);
        } else {
            channelText = channel;
        }

        notificationManager.notify(NOTIFICATION_ID_UPDATED, new Notification.Builder(service, NOTIFICATION_CHANNEL_ID_UPDATED)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(R.string.notification_updated_title))
                .setContentText(service.getString(R.string.notification_updated_text, channelText))
                .setShowWhen(true)
                .setSmallIcon(R.drawable.security_update_good_fill0_wght400_grad0_opsz48)
                .build());
    }

    void showDownloadNotification(long progress, long max) {
        phase = Phase.DOWNLOAD;
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
                buildProgressNotification(R.string.notification_download_title, progress, max));
    }

    void showVerifyNotification(int progress) {
        phase = Phase.VERIFY;
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
                buildProgressNotification(R.string.notification_verify_title, progress, 100));
    }

    void showInstallNotification(int progress) {
        phase = Phase.INSTALL;
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
                buildProgressNotification(R.string.notification_install_title, progress, 100));
    }

    void showValidateNotification(int progress) {
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
                buildProgressNotification(R.string.notification_validate_title, progress, 100));
    }

    void showFinalizeNotification(int progress) {
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
                buildProgressNotification(R.string.notification_finalize_title, progress, 100));
    }

    void cancelProgressNotification() {
        service.stopForeground(true);
    }

    void showRebootNotification() {
        final PendingIntent reboot = PendingIntent.getBroadcast(service, PENDING_REBOOT_ID,
                        new Intent(service, RebootReceiver.class), PendingIntent.FLAG_IMMUTABLE);

        Notification.Action rebootAction = new Notification.Action.Builder(
                Icon.createWithResource(service.getApplication(), R.drawable.restart_alt_fill0_wght400_grad0_opsz48),
                service.getString(R.string.notification_reboot_action),
                reboot).build();

        notificationManager.notify(NOTIFICATION_ID_REBOOT, new Notification.Builder(service, NOTIFICATION_CHANNEL_ID_REBOOT)
                .addAction(rebootAction)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(R.string.notification_reboot_title))
                .setContentText(service.getString(R.string.notification_reboot_text))
                .setOngoing(true)
                .setShowWhen(true)
                .setTimeoutAfter(-1)
                .setSmallIcon(R.drawable.system_update_fill0_wght400_grad0_opsz48)
                .build());
    }

    void showFailureNotification(String exceptionMessage) {
        final int titleResId;
        final int contentResId;

        switch (phase) {
            case CHECK:
                titleResId = R.string.notification_failed_check_title;
                contentResId = R.string.notification_failed_check_text;
                break;

            case DOWNLOAD:
                titleResId = R.string.notification_failed_download_title;
                contentResId = R.string.notification_failed_download_text;
                break;

            case VERIFY:
                titleResId = R.string.notification_failed_verify_title;
                contentResId = R.string.notification_failed_verify_text;
                break;

            default:
                titleResId = R.string.notification_failed_install_title;
                contentResId = R.string.notification_failed_install_text;
        }

        String text = service.getString(contentResId) + "<br><br><tt>" + exceptionMessage + "</tt>";
        Spanned styledText = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY);

        notificationManager.notify(NOTIFICATION_ID_FAILURE, new Notification.Builder(service, NOTIFICATION_CHANNEL_ID_FAILURE)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(titleResId))
                .setContentText(styledText)
                .setStyle(new Notification.BigTextStyle()
                    .bigText(styledText))
                .setShowWhen(true)
                .setSmallIcon(R.drawable.security_update_warning_fill0_wght400_grad0_opsz48)
                .build());
    }

    void showUntrustedBuildNotification() {
        final String text = service.getString(R.string.notification_untrusted_build_text);
        notificationManager.notify(NOTIFICATION_ID_FAILURE, new Notification.Builder(service, NOTIFICATION_CHANNEL_ID_FAILURE)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(R.string.notification_untrusted_build_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle()
                    .bigText(text))
                .setShowWhen(true)
                .setSmallIcon(R.drawable.security_update_warning_fill0_wght400_grad0_opsz48)
                .build());
    }

    private PendingIntent getPendingSettingsIntent() {
        return PendingIntent.getActivity(service, PENDING_SETTINGS_ID, new Intent(service,
                Settings.class), PendingIntent.FLAG_IMMUTABLE);
    }

    static void showSetSecurityPreviewNotification(@NonNull Context context) {
        final var notificationManager = context.getSystemService(NotificationManager.class);
        final int titleResId = R.string.notification_set_security_preview_title;
        final int contentResId = R.string.notification_set_security_preview_text;
        notificationManager.notify(NOTIFICATION_ID_SET_SECURITY_PREVIEW, new Notification.Builder(
                context, NOTIFICATION_CHANNEL_ID_SET_SECURITY_PREVIEW)
                .setContentIntent(getPendingSecurityPreviewSettingsIntent(context))
                .setContentTitle(context.getString(titleResId))
                .setContentText(context.getString(contentResId))
                .setOngoing(true)
                .setShowWhen(true)
                .setTimeoutAfter(-1)
                .setSmallIcon(R.drawable.security_update_warning_fill0_wght400_grad0_opsz48)
                .build());
    }

    static void cancelSetSecurityPreviewNotification(@NonNull Context context) {
        final var notificationManager = context.getSystemService(NotificationManager.class);
        notificationManager.cancel(NOTIFICATION_ID_SET_SECURITY_PREVIEW);
    }

    private static PendingIntent getPendingSecurityPreviewSettingsIntent(Context context) {
        return PendingIntent.getActivity(context, PENDING_SECURITY_PREVIEW_SETTINGS_ID,
                new Intent(context, SecurityPreviewSettings.class), PendingIntent.FLAG_IMMUTABLE);
    }
}
