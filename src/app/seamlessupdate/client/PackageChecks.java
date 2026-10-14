// SPDX-License-Identifier: Apache-2.0
package app.seamlessupdate.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the metadata of a package that RecoverySystem.verifyPackage accepted and refuses packages
 * this device must not install.
 */
final class PackageChecks {
    static final int MAX_TEXT_BYTES = 64 * 1024;

    private PackageChecks() {}

    static final class Metadata {
        long timestamp;
        String incremental;
        String device;
        String serialno;
        String type;
        String sourceIncremental;
        String sourceFingerprint;
        String securityPatch;
        boolean downgrade;
        boolean wipe;
        long payloadOffset = -1;
        long payloadSize = -1;
    }

    static List<String> lines(final InputStream input) throws IOException, GeneralSecurityException {
        final byte[] bytes = input.readNBytes(MAX_TEXT_BYTES + 1);
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new GeneralSecurityException("package text entry is too large");
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (final CharacterCodingException e) {
            throw new GeneralSecurityException("package text entry is not UTF-8");
        }
        final List<String> lines = new ArrayList<>();
        for (final String line : text.split("\r?\n")) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    static Metadata metadata(final InputStream input) throws IOException, GeneralSecurityException {
        final Metadata metadata = new Metadata();
        final Set<String> keys = new HashSet<>();
        for (final String line : lines(input)) {
            final int split = line.indexOf('=');
            if (split < 1) {
                throw new GeneralSecurityException("malformed metadata line");
            }
            final String key = line.substring(0, split);
            final String value = line.substring(split + 1);
            if (!keys.add(key)) {
                throw new GeneralSecurityException("repeated metadata key: " + key);
            }
            switch (key) {
                case "post-timestamp":
                    metadata.timestamp = number(value);
                    break;
                case "post-build-incremental":
                    metadata.incremental = value;
                    break;
                case "post-security-patch-level":
                    metadata.securityPatch = value;
                    break;
                case "pre-device":
                    metadata.device = value;
                    break;
                case "serialno":
                    metadata.serialno = value;
                    break;
                case "ota-type":
                    metadata.type = value;
                    break;
                // The release tools write these only as "yes", so any value counts.
                case "ota-downgrade":
                case "spl-downgrade":
                    metadata.downgrade = true;
                    break;
                case "ota-wipe":
                    metadata.wipe = true;
                    break;
                case "ota-streaming-property-files":
                    payload(metadata, value);
                    break;
                case "pre-build-incremental":
                    metadata.sourceIncremental = value;
                    break;
                case "pre-build":
                    metadata.sourceFingerprint = value;
                    break;
            }
        }
        if (metadata.payloadOffset < 0) {
            throw new GeneralSecurityException("payload offset missing");
        }
        return metadata;
    }

    // The value is padded with spaces and lists name:offset:size for each stored entry.
    private static void payload(final Metadata metadata, final String value)
            throws GeneralSecurityException {
        for (final String file : value.trim().split(",")) {
            final String[] parts = file.split(":");
            if ("payload.bin".equals(parts[0])) {
                if (parts.length != 3) {
                    throw new GeneralSecurityException("malformed payload location");
                }
                metadata.payloadOffset = number(parts[1]);
                metadata.payloadSize = number(parts[2]);
                return;
            }
        }
    }

    private static long number(final String value) throws GeneralSecurityException {
        if (!value.matches("[0-9]{1,18}")) {
            throw new GeneralSecurityException("malformed number in package metadata");
        }
        return Long.parseLong(value);
    }

    /** Refuses a package for another device or source build, or one limited to serial numbers. */
    static void requireDevice(final Metadata metadata, final String device,
            final String incremental, final String fingerprint) throws GeneralSecurityException {
        if (!device.equals(metadata.device)) {
            throw new GeneralSecurityException("device mismatch");
        }
        if (metadata.serialno != null) {
            throw new GeneralSecurityException("serialno constraint not permitted");
        }
        if (!"AB".equals(metadata.type)) {
            throw new GeneralSecurityException("package is not an A/B update");
        }
        if (metadata.sourceIncremental != null && !metadata.sourceIncremental.equals(incremental)) {
            throw new GeneralSecurityException("source incremental mismatch");
        }
        if (metadata.sourceFingerprint != null && !metadata.sourceFingerprint.equals(fingerprint)) {
            throw new GeneralSecurityException("source fingerprint mismatch");
        }
    }

    /** A downloaded package must be the build the update server named. */
    static void requireServerTarget(final Metadata metadata, final long buildDate,
            final String incremental) throws GeneralSecurityException {
        if (metadata.timestamp != buildDate) {
            throw new GeneralSecurityException("timestamp does not match server metadata");
        }
        if (!incremental.equals(metadata.incremental)) {
            throw new GeneralSecurityException("incremental does not match server metadata");
        }
    }
}
