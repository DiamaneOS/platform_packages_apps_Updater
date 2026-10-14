package app.seamlessupdate.client;

import static android.os.Build.DEVICE;
import static android.os.Build.FINGERPRINT;
import static android.os.Build.VERSION.INCREMENTAL;
import static android.os.Build.VERSION.SECURITY_PATCH;

import android.app.IntentService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Network;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RecoverySystem;
import android.os.ServiceSpecificException;
import android.os.SystemProperties;
import android.os.UpdateEngine;
import android.os.UpdateEngine.ErrorCodeConstants;
import android.os.UpdateEngine.UpdateStatusConstants;
import android.os.UpdateEngineCallback;
import android.os.storage.StorageManager;
import android.util.Log;

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.concurrent.CountDownLatch;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.net.ssl.HttpsURLConnection;

import libcore.io.IoUtils;
import de.diamaneos.downloads.DownloadPolicy;
import de.diamaneos.downloads.DownloadPolicyClient;
import de.diamaneos.downloads.DownloadTransport;

import org.grapheneos.tls.ModernTLSSocketFactory;

public class Service extends IntentService {
    private static final String TAG = "Service";
    static final String INTENT_EXTRA_NETWORK = "network";
    static final String INTENT_EXTRA_IS_USER_INITIATED = "is_user_initiated";
    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 30000;
    private static final File CARE_MAP_PATH = new File("/data/ota_package/care_map.pb");
    private static final File UPDATE_PATH = new File("/data/ota_package/update.zip");
    private static final String PREFERENCE_DOWNLOAD_FILE = "download_file";
    private static final String PREFERENCE_FAILED_INCREMENTAL = "failed_incremental";
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;
    private static final int UPDATE_ENGINE_DOWNLOAD_STATE_INITIALIZATION_ERROR = 20;

    private final ModernTLSSocketFactory tlsSocketFactory = new ModernTLSSocketFactory();

    private NotificationHandler notificationHandler;
    private boolean mUpdating = false;
    private DownloadPolicy downloadPolicy = DownloadPolicy.DEFAULT;

    public Service() {
        super(TAG);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationHandler = new NotificationHandler(this);
    }

    private HttpsURLConnection fetchData(final Network network, final String path) throws IOException {
        return fetchData(network, path, 0);
    }

    private HttpsURLConnection fetchData(final Network network, final String path,
            final long offset) throws IOException {
        return DownloadTransport.open(downloadPolicy, false, path,
                url -> (HttpsURLConnection) network.openConnection(url), connection -> {
                    connection.setSSLSocketFactory(tlsSocketFactory);
                    connection.setConnectTimeout(CONNECT_TIMEOUT);
                    connection.setReadTimeout(READ_TIMEOUT);
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
                });
    }

    private void applyUpdate(final boolean streaming, final long payloadOffset,
            final String[] headerKeyValuePairs, final boolean incremental) throws IOException {
        if (streaming) throw new IOException("Remote streaming is unsupported by the bounded transport");
        notificationHandler.showInstallNotification(0);

        final CountDownLatch monitor = new CountDownLatch(1);
        final UpdateEngine engine = new UpdateEngine();
        final var callback = new UpdateEngineCallback() {
            Integer errorCode = null;

            @Override
            public void onStatusUpdate(int status, float percent) {
                Log.d(TAG, "onStatusUpdate: " + status + ", " + percent * 100 + "%");
                if (status == UpdateStatusConstants.DOWNLOADING) {
                    notificationHandler.showInstallNotification(Math.round(percent * 100));
                } else if (status == UpdateStatusConstants.VERIFYING) {
                    notificationHandler.showValidateNotification(Math.round(percent * 100));
                } else if (status == UpdateStatusConstants.FINALIZING) {
                    notificationHandler.showFinalizeNotification(Math.round(percent * 100));
                }
            }

            @Override
            public void onPayloadApplicationComplete(final int errorCode) {
                Log.d(TAG, "onPayloadApplicationComplete: " + errorCode);
                this.errorCode = errorCode;
                if (errorCode == ErrorCodeConstants.SUCCESS) {
                    UPDATE_PATH.delete();
                    annoyUser();
                } else {
                    UPDATE_PATH.delete();
                    if (incremental && errorCode == UPDATE_ENGINE_DOWNLOAD_STATE_INITIALIZATION_ERROR) {
                        final SharedPreferences preferences = Settings.getPreferences(Service.this);
                        final String downloadFile = preferences.getString(PREFERENCE_DOWNLOAD_FILE, null);
                        preferences.edit().putString(PREFERENCE_FAILED_INCREMENTAL, downloadFile).commit();
                    }
                }
                monitor.countDown();
            }
        };
        engine.bind(callback);
        UPDATE_PATH.setReadable(true, false);
        engine.applyPayload("file://" + UPDATE_PATH, payloadOffset, 0, headerKeyValuePairs);
        try {
            monitor.await();
        } catch (InterruptedException e) {}

        if (!engine.unbind()) {
            Log.e(TAG, "unable to unbind update_engine");
        }

        if (callback.errorCode != null && callback.errorCode != ErrorCodeConstants.SUCCESS) {
            throw new IOException("update_engine error code: " + callback.errorCode);
        }
    }

    private static ZipEntry getEntry(final ZipFile zipFile, final String name) throws GeneralSecurityException {
        final ZipEntry entry = zipFile.getEntry(name);
        if (entry == null) {
            throw new GeneralSecurityException("missing zip entry: " + name);
        }
        return entry;
    }

    private void verify() throws IOException, GeneralSecurityException {
        try {
            ReleaseKeys.require();
            notificationHandler.showVerifyNotification(0);
            RecoverySystem.verifyPackage(UPDATE_PATH, (int progress) -> {
                Log.d(TAG, "verifyPackage: " + progress + "%");
                notificationHandler.showVerifyNotification(progress);
            }, null);
        } catch (final GeneralSecurityException e) {
            UPDATE_PATH.delete();
            throw e;
        }
    }

    private static PackageChecks.Metadata readPackageMetadata(final ZipFile zipFile)
            throws IOException, GeneralSecurityException {
        final PackageChecks.Metadata metadata;
        try (final InputStream input = zipFile.getInputStream(
                getEntry(zipFile, "META-INF/com/android/metadata"))) {
            metadata = PackageChecks.metadata(input);
        }
        PackageChecks.requireDevice(metadata, DEVICE, INCREMENTAL, FINGERPRINT);
        return metadata;
    }

    private static String[] readPayloadProperties(final ZipFile zipFile)
            throws IOException, GeneralSecurityException {
        try (final InputStream input = zipFile.getInputStream(
                getEntry(zipFile, "payload_properties.txt"))) {
            return PackageChecks.lines(input).toArray(new String[0]);
        }
    }

    private void install(final ZipFile zipFile, final PackageChecks.Metadata metadata,
            final String[] headerKeyValuePairs, final boolean streaming,
            final boolean incremental) throws IOException {
        Files.deleteIfExists(CARE_MAP_PATH.toPath());
        final ZipEntry careMapEntry = zipFile.getEntry("care_map.pb");
        if (careMapEntry == null) {
            Log.w(TAG, "care_map.pb missing");
        } else {
            Files.copy(zipFile.getInputStream(careMapEntry), CARE_MAP_PATH.toPath());
            CARE_MAP_PATH.setReadable(true, false);
        }
        applyUpdate(streaming, metadata.payloadOffset, headerKeyValuePairs, incremental);
    }

    private void onDownloadFinished(final boolean streaming, final long targetBuildDate,
            final String targetIncremental) throws IOException, GeneralSecurityException {
        verify();
        try (final ZipFile zipFile = new ZipFile(UPDATE_PATH)) {
            final PackageChecks.Metadata metadata = readPackageMetadata(zipFile);
            PackageChecks.requireServerTarget(metadata, targetBuildDate, targetIncremental);
            install(zipFile, metadata, readPayloadProperties(zipFile), streaming,
                    metadata.sourceIncremental != null);
        } catch (final GeneralSecurityException e) {
            UPDATE_PATH.delete();
            throw e;
        }
    }

    // A picked package is copied to where downloads go, so verification, parsing and
    // update_engine read the same bytes, no other app can change them and the card can be removed.
    private void installFromFile(final Uri uri) throws IOException, GeneralSecurityException {
        notificationHandler.showCopyNotification(0, 0);
        ReleaseKeys.require();
        // The online check must not resume a partial download onto the copy.
        Settings.getPreferences(this).edit().remove(PREFERENCE_DOWNLOAD_FILE).commit();
        try {
            copyToUpdatePath(uri);
            verify();
            try (final ZipFile zipFile = new ZipFile(UPDATE_PATH)) {
                final PackageChecks.Metadata metadata = readPackageMetadata(zipFile);
                PackageChecks.requireLocalTarget(metadata,
                        SystemProperties.getLong("ro.build.date.utc", 0), SECURITY_PATCH);
                final String[] headerKeyValuePairs = readPayloadProperties(zipFile);
                PackageChecks.requireLocalPayload(metadata, headerKeyValuePairs, UPDATE_PATH.length());
                install(zipFile, metadata, headerKeyValuePairs, false, false);
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
            file = getContentResolver().openFileDescriptor(uri, "r");
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
                getSystemService(StorageManager.class).allocateBytes(output.getFD(), size,
                        StorageManager.FLAG_ALLOCATE_AGGRESSIVE);
            } catch (final IOException e) {
                throw new IOException(getString(com.android.internal.R.string.low_memory), e);
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

    // A server that answers with anything but update information (a web page, say) fails the
    // check like an unreachable one, instead of crashing the service.
    private static String[] readMetadata(final HttpsURLConnection connection) throws IOException {
        try (final InputStream input = connection.getInputStream()) {
            return DownloadChecks.metadata(input);
        }
    }

    private void annoyUser() {
        PeriodicJob.cancel(this);
        PeriodicJob.resetRetryDelay(this);
        final SharedPreferences preferences = Settings.getPreferences(this);
        preferences.edit().putBoolean(Settings.KEY_WAITING_FOR_REBOOT, true).apply();
        if (Settings.getIdleReboot(this)) {
            IdleReboot.schedule(this);
        }
        notificationHandler.showRebootNotification();
    }

    @Override
    protected void onHandleIntent(final Intent intent) {
        Log.d(TAG, "onHandleIntent");

        final Network network = intent.getParcelableExtra(INTENT_EXTRA_NETWORK, Network.class);
        // Settings puts a package the user picked in the intent data, with a read grant.
        final Uri pickedFile = intent.getData();
        final var serviceIsUserInitiated = pickedFile != null
                || intent.getBooleanExtra(INTENT_EXTRA_IS_USER_INITIATED, false);
        if (serviceIsUserInitiated) Log.d(TAG, "onHandleIntent() – service is user-initiated");

        final PowerManager pm = getSystemService(PowerManager.class);
        final WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Updater:" + TAG);
        HttpsURLConnection connection = null;
        InputStream input = null;
        try {
            wakeLock.acquire();

            if (mUpdating) {
                Log.d(TAG, "updating already, returning early");
                return;
            }
            final SharedPreferences preferences = Settings.getPreferences(this);
            if (preferences.getBoolean(Settings.KEY_WAITING_FOR_REBOOT, false)) {
                Log.d(TAG, "updated already, waiting for reboot");
                return;
            }
            mUpdating = true;
            notificationHandler.start(pickedFile != null);

            if (pickedFile != null) {
                Log.d(TAG, "installing a picked package");
                installFromFile(pickedFile);
                return;
            }

            if (network == null) {
                throw new IOException("Network is unavailable");
            }

            downloadPolicy = DownloadPolicyClient.read(this);
            final String channel = SystemProperties.get("sys.update.channel", Settings.getChannel(this));

            Log.d(TAG, "fetching metadata for " + DEVICE + "-" + channel);
            connection = fetchData(network, DEVICE + "-" + channel);
            final int metadataResponse = connection.getResponseCode();
            if (metadataResponse != HTTP_OK) {
                throw new IOException("update server answered HTTP " + metadataResponse + " for " + connection.getURL());
            }
            final String[] metadata = readMetadata(connection);

            final String targetIncremental = metadata[0];
            final long targetBuildDate = Long.parseLong(metadata[1]);
            final long sourceBuildDate = SystemProperties.getLong("ro.build.date.utc", 0);
            if (!DownloadChecks.newer(metadata, DEVICE, channel, INCREMENTAL, sourceBuildDate)) {
                PeriodicJob.resetRetryDelay(this);
                notificationHandler.showUpdatedNotification(channel);
                Log.d(TAG, "targetBuildDate: " + targetBuildDate + " not higher than sourceBuildDate: " + sourceBuildDate);
                mUpdating = false;
                return;
            }

            // Never download an update this build could not tell from a forged one.
            ReleaseKeys.require();

            notificationHandler.showDownloadNotification(0, 100);

            String downloadFile = preferences.getString(PREFERENCE_DOWNLOAD_FILE, null);
            long downloaded;
            long contentLength;

            // Keep every network read in the allowlisted transport. update_engine uses verified local bytes.
            final boolean streaming = false;

            final String streamingPrefix = streaming ? "-streaming" : "";
            final String incrementalUpdate = DEVICE + streamingPrefix + "-incremental-" + INCREMENTAL + "-" + targetIncremental + ".zip";
            final String fullUpdate = DEVICE + streamingPrefix + "-ota_update-" + targetIncremental + ".zip";

            if (incrementalUpdate.equals(downloadFile) || fullUpdate.equals(downloadFile)) {
                downloaded = UPDATE_PATH.length();
            } else {
                downloaded = 0;
            }

            if (downloaded > 0) {
                Log.d(TAG, "resume fetch of " + downloadFile + " from " + downloaded + " bytes");
                connection = fetchData(network, downloadFile, downloaded);
                final int responseCode = connection.getResponseCode();
                if (responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
                    Log.d(TAG, "download completed previously");
                    onDownloadFinished(streaming, targetBuildDate, targetIncremental);
                    return;
                }
                if (responseCode == HTTP_NOT_FOUND && incrementalUpdate.equals(downloadFile)) {
                    final InputStream error = connection.getErrorStream();
                    if (error != null) {
                        error.close();
                    }

                    downloaded = 0;
                    UPDATE_PATH.delete();

                    Log.d(TAG, "previous incremental not found, fetch full update " + fullUpdate);
                    downloadFile = fullUpdate;
                    connection = fetchData(network, downloadFile);
                }
                contentLength = DownloadChecks.length(connection.getResponseCode(), downloaded,
                        connection.getContentLengthLong(), connection.getHeaderField("Content-Range"));
                input = connection.getInputStream();
            } else {
                Files.deleteIfExists(UPDATE_PATH.toPath());

                final String failedIncremental = preferences.getString(PREFERENCE_FAILED_INCREMENTAL, null);
                if (incrementalUpdate.equals(failedIncremental)) {
                    Log.d(TAG, "incremental update initialization failed, fetch full update " + fullUpdate);
                    downloadFile = fullUpdate;
                    connection = fetchData(network, downloadFile);
                } else {
                    Log.d(TAG, "fetch incremental " + incrementalUpdate);
                    downloadFile = incrementalUpdate;
                    connection = fetchData(network, downloadFile);
                    if (connection.getResponseCode() == HTTP_NOT_FOUND) {
                        final InputStream error = connection.getErrorStream();
                        if (error != null) {
                            error.close();
                        }

                        Log.d(TAG, "incremental not found, fetch full update " + fullUpdate);
                        downloadFile = fullUpdate;
                        connection = fetchData(network, downloadFile);
                    }
                }
                contentLength = DownloadChecks.length(connection.getResponseCode(), 0,
                        connection.getContentLengthLong(), null);
                input = connection.getInputStream();
            }

            notificationHandler.showDownloadNotification(downloaded, contentLength);

            final long requiredBytes = contentLength - downloaded;
            try {
                StorageManager sm = getSystemService(StorageManager.class);
                // TODO: allocating bytes for file descriptor is more reliable, but it breaks the current
                //  download resume code
                sm.allocateBytes(sm.getUuidForPath(UPDATE_PATH), requiredBytes, StorageManager.FLAG_ALLOCATE_AGGRESSIVE);
            } catch (IOException e) {
                // storage space is likely to become available later
                Log.d(TAG, "unable to allocate " + requiredBytes + " bytes, proceeding anyway", e);
            }

            try (final OutputStream output = new FileOutputStream(UPDATE_PATH, downloaded != 0)) {
                preferences.edit().putString(PREFERENCE_DOWNLOAD_FILE, downloadFile).commit();

                int bytesRead;
                long last = System.nanoTime();
                final byte[] buffer = new byte[16384];
                while ((bytesRead = input.read(buffer)) != -1) {
                    if (bytesRead > contentLength - downloaded) throw new IOException("Update exceeds expected length");
                    output.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;
                    final long now = System.nanoTime();
                    if (now - last > 1000 * 1000 * 1000) {
                        Log.d(TAG, "downloaded " + downloaded + " from " + contentLength + " bytes");
                        notificationHandler.showDownloadNotification(downloaded, contentLength);
                        last = now;
                    }
                }
            }

            if (downloaded != contentLength) throw new IOException("Update download is incomplete");
            Log.d(TAG, "download completed");
            onDownloadFinished(streaming, targetBuildDate, targetIncremental);
        } catch (ReleaseKeys.UntrustedBuildException e) {
            Log.w(TAG, "not downloading or installing updates: " + e.getMessage());
            notificationHandler.showUntrustedBuildNotification();
            mUpdating = false;
            // A property of this build: a retry before the next periodic check changes nothing.
            PeriodicJob.resetRetryDelay(this);
        } catch (GeneralSecurityException | IOException | ServiceSpecificException e) {
            Log.e(TAG, "failed to download and install update", e);
            notificationHandler.showFailureNotification(e.getMessage());
            mUpdating = false;
            if (serviceIsUserInitiated) {
                // Either the user will try again immediately or the already scheduled periodic
                // job will pick it up under constraints (which will retry on failure)
                Log.w(TAG, "onHandleIntent() – service failed but failure is ignored because it was user-initiated");
            } else {
                PeriodicJob.scheduleRetry(this);
                Log.w(TAG, "onHandleIntent() – service failed but has been scheduled for retry");
            }
        } finally {
            IoUtils.closeQuietly(input);

            if (connection != null) {
                connection.disconnect();
            }
            notificationHandler.cancelProgressNotification();
            Log.d(TAG, "release wake lock");
            wakeLock.release();
        }
    }
}
