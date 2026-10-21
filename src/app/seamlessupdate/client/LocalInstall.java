// SPDX-License-Identifier: Apache-2.0
package app.seamlessupdate.client;

import static android.os.Build.VERSION.SECURITY_PATCH;
import static app.seamlessupdate.client.Service.PREFERENCE_DOWNLOAD_FILE;
import static app.seamlessupdate.client.Service.UPDATE_PATH;
import static app.seamlessupdate.client.Service.readPackageMetadata;
import static app.seamlessupdate.client.Service.readPayloadProperties;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.zip.ZipFile;

/** Install from storage: the service's path for a package the user picked. */
final class LocalInstall {
    private final Service service;
    private final NotificationHandler notificationHandler;

    LocalInstall(final Service service, final NotificationHandler notificationHandler) {
        this.service = service;
        this.notificationHandler = notificationHandler;
    }

    // A picked package is copied to where downloads go, so verification, parsing and
    // update_engine read the same bytes, no other app can change them and the card can be removed.
    void installFromFile(final Uri uri) throws IOException, GeneralSecurityException {
        notificationHandler.showCopyNotification(0, 0);
        ReleaseKeys.require();
        // The online check must not resume a partial download onto the copy.
        Settings.getPreferences(service).edit().remove(PREFERENCE_DOWNLOAD_FILE).commit();
        try {
            copyToUpdatePath(uri);
            service.verify();
            try (final ZipFile zipFile = new ZipFile(UPDATE_PATH)) {
                final PackageChecks.Metadata metadata = readPackageMetadata(zipFile);
                PackageChecks.requireLocalTarget(metadata,
                        SystemProperties.getLong("ro.build.date.utc", 0), SECURITY_PATCH);
                final String[] headerKeyValuePairs = readPayloadProperties(zipFile);
                PackageChecks.requireLocalPayload(metadata, headerKeyValuePairs, UPDATE_PATH.length());
                service.install(zipFile, metadata, headerKeyValuePairs, false, false);
            }
        } catch (final IOException | GeneralSecurityException | RuntimeException e) {
            UPDATE_PATH.delete();
            throw e;
        }
    }

    private void copyToUpdatePath(final Uri uri) throws IOException {
        Files.deleteIfExists(UPDATE_PATH.toPath());
        final ParcelFileDescriptor file;
        try {
            file = service.getContentResolver().openFileDescriptor(uri, "r");
        } catch (final SecurityException e) {
            throw new IOException("no access to the selected file", e);
        }
        if (file == null) {
            throw new IOException("unable to open the selected file");
        }
        try (final InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(file);
                final FileOutputStream output = new FileOutputStream(UPDATE_PATH)) {
            final long size = file.getStatSize();
            if (size < 1 || size > DownloadChecks.MAX_PACKAGE_BYTES) {
                throw new IOException("unexpected update file size: " + size);
            }
            try {
                service.getSystemService(StorageManager.class).allocateBytes(output.getFD(), size,
                        StorageManager.FLAG_ALLOCATE_AGGRESSIVE);
            } catch (final IOException e) {
                throw new IOException(service.getString(com.android.internal.R.string.low_memory), e);
            }
            notificationHandler.showCopyNotification(0, size);
            long copied = 0;
            long last = System.nanoTime();
            final byte[] buffer = new byte[1024 * 1024];
            for (int bytesRead; (bytesRead = input.read(buffer)) != -1; ) {
                if (bytesRead > size - copied) throw new IOException("the selected file changed while it was copied");
                output.write(buffer, 0, bytesRead);
                copied += bytesRead;
                final long now = System.nanoTime();
                if (now - last > 1000 * 1000 * 1000) {
                    notificationHandler.showCopyNotification(copied, size);
                    last = now;
                }
            }
            if (copied != size) throw new IOException("the selected file changed while it was copied");
        }
    }

    // Service's two early returns for a pick both follow an installed update that waits for the
    // restart.
    static void explainSkippedPick(final Context context, final Uri pickedFile) {
        if (pickedFile != null) {
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context,
                    R.string.notification_reboot_text, Toast.LENGTH_LONG).show());
        }
    }
}
