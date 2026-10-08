package app.seamlessupdate.client;

import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Whether this build can tell a genuine update from a forged one.
 *
 * RecoverySystem.verifyPackage and update_engine accept a package signed with any certificate
 * in otacerts.zip. A build signed with Android's public test keys trusts a public test
 * certificate there, so anyone could sign an update it accepts. Such a build checks for updates
 * but never downloads or installs one.
 */
final class ReleaseKeys {
    private static final String TAG = "ReleaseKeys";
    private static final String OTA_CERTIFICATES = "/system/etc/security/otacerts.zip";

    // SHA-256 of the public keys (DER SubjectPublicKeyInfo) of the certificates in
    // build/make/target/product/security: the public test keys every build carries until it is
    // signed with release keys. The key, not the certificate: verification compares keys, so a
    // new certificate for a public test key is still that key.
    private static final Set<String> PUBLIC_TEST_KEYS = Set.of(
            "ef57b690165cb561b5026922c00d2d6574e8b184fa7d161e076f06e06e6d35db", // testkey
            "3d3df7dc9bf26e02d4cd76256d41d45e41a4dedebe7feb95c40e3697681be8a7", // platform
            "2b59625f19b7d0d143a69fb7a02d42b151480ddfe60b0572070ac24afca212a0", // shared
            "091377d6fd00e4e217b750571d45cbe1a32c7fa74075138fc529fdf162b5416f", // media
            "7bfcc5541e9f18e3738b1d5fa0b8524d44bac99424cfdcc6594d9386d4daaf15", // networkstack
            "d2e67bf7c0aaa67f3a4f7dc35dd2d45aa111a91e06a2cc517eaf9c249de31454", // sdk_sandbox
            "de5b1ddc59331bbdeee26d6467dd4f9fc09719098ba7dcb6a973045781a0e36c", // bluetooth
            "9318ca97d2c148ca94639603b68d0928068158113d23a1cfca30acc4df2cac37", // nfc
            "838012d323ab15f0c93349a8e954b74be367350fc8f2cf151290efddaa37a22a", // gmscompat_lib
            "4d850245247af73aaf72fe598c7c7ef73f618391d771763299d5f5139ca43f2c"  // cts_uicc_2021
    );

    private static volatile Boolean trusted;

    private ReleaseKeys() {}

    static final class UntrustedBuildException extends GeneralSecurityException {
        private static final long serialVersionUID = 1L;

        UntrustedBuildException(final String message) {
            super(message);
        }
    }

    /** Throws unless this build is signed with release keys that no one else holds. */
    static void require() throws UntrustedBuildException {
        final String problem = problem();
        if (problem != null) {
            throw new UntrustedBuildException(problem);
        }
    }

    static boolean trusted() {
        Boolean result = trusted;
        if (result == null) {
            result = problem() == null;
            trusted = result;
        }
        return result;
    }

    private static String problem() {
        if (!"release-keys".equals(Build.TAGS)) {
            return "build tags are " + Build.TAGS + ", not release-keys";
        }
        // Fail closed: an unreadable or empty certificate list counts as untrusted.
        try (final ZipFile zip = new ZipFile(OTA_CERTIFICATES)) {
            final CertificateFactory factory = CertificateFactory.getInstance("X.509");
            final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            int count = 0;
            for (final Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                final ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                try (final InputStream input = zip.getInputStream(entry)) {
                    for (final Certificate certificate : factory.generateCertificates(input)) {
                        count++;
                        final String digest = HexFormat.of().formatHex(
                                sha256.digest(certificate.getPublicKey().getEncoded()));
                        if (PUBLIC_TEST_KEYS.contains(digest)) {
                            return OTA_CERTIFICATES + " trusts a public test key (" + entry.getName() + ")";
                        }
                    }
                }
            }
            if (count == 0) {
                return OTA_CERTIFICATES + " holds no certificate";
            }
            return null;
        } catch (IOException | GeneralSecurityException e) {
            Log.e(TAG, "unable to read " + OTA_CERTIFICATES, e);
            return "unable to read " + OTA_CERTIFICATES;
        }
    }
}
