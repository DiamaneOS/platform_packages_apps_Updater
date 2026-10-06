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

    // SHA-256 of the DER certificates in build/make/target/product/security: the public test
    // keys every build carries until it is signed with release keys.
    private static final Set<String> PUBLIC_TEST_CERTIFICATES = Set.of(
            "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc", // testkey
            "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8", // platform
            "28bbfe4a7b97e74681dc55c2fbb6ccb8d6c74963733f6af6ae74d8c3a6e879fd", // shared
            "465983f7791f2abeb43ea2cbdc7f21a8260b72bc08a55c839fc1a43bc741a81e", // media
            "e1dbadce60dc080d15b58a014b0dcf9400e24de23fa00b287a5a982bfebda2ee", // networkstack
            "abf21f9e2af1d881cc673fddcefa6ed9c269a437bd64b279cf45844cfd589126", // sdk_sandbox
            "a6ccc500ff0e7421200eb66a7fe174ef1b00e52ca91727070cbedf061ff76c35", // bluetooth
            "fae9122a8721d6e2a196d2224dffcf773c9127e2bb956cbddb40b009192ffdfd", // nfc
            "cb8b7b48f132174850cb584d34593f2f1679e94f53b99fc2404003b498365ebf", // gmscompat_lib
            "ce7b2b47ae2b7552c8f92cc29124279883041fb623a5f194a82c9bf15d492aa0"  // cts_uicc_2021
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
                        final String digest = HexFormat.of().formatHex(sha256.digest(certificate.getEncoded()));
                        if (PUBLIC_TEST_CERTIFICATES.contains(digest)) {
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
