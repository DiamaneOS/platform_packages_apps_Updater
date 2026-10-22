// SPDX-License-Identifier: Apache-2.0
package app.seamlessupdate.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.GeneralSecurityException;

/** Selectors are untrusted hints. Only native OTA verification authorizes installation. */
final class DownloadChecks {
    static final int MAX_METADATA_BYTES = 4096;
    static final long MAX_PACKAGE_BYTES = 8L * 1024 * 1024 * 1024;
    private DownloadChecks() {}

    static String[] metadata(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
        if (bytes.length > MAX_METADATA_BYTES) {
            throw new IOException("Update information is too large");
        }
        String line = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        if (line.endsWith("\n")) line = line.substring(0, line.length() - 1);
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        if (!line.matches("[A-Za-z0-9_.-]{1,128} [0-9]+ [A-Za-z0-9_-]{1,64} [A-Za-z0-9_-]{1,64}")) {
            throw new IOException("Malformed update information");
        }
        String[] metadata = line.split(" ");
        try {
            Long.parseLong(metadata[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed build date in update information", e);
        }
        return metadata;
    }

    static boolean newer(String[] metadata, String device, String channel,
            long sourceDate) throws GeneralSecurityException {
        if (!device.equals(metadata[2]) || !channel.equals(metadata[3])) {
            throw new GeneralSecurityException("Update device or channel does not match");
        }
        // As GrapheneOS: a line no newer than the running build is up to date, as after a
        // switch back from a channel that is ahead.
        return Long.parseLong(metadata[1]) > sourceDate;
    }

    static long length(int code, long received, long length, String range) throws IOException {
        if (received < 0 || received > MAX_PACKAGE_BYTES || length < 1
                || length > MAX_PACKAGE_BYTES - received || code != (received == 0 ? 200 : 206)) {
            throw new IOException("Unexpected update length or response");
        }
        long total = received + length;
        if (received > 0 && !String.format(java.util.Locale.ROOT, "bytes %d-%d/%d", received, total - 1, total).equals(range)) {
            throw new IOException("Unexpected update resume range");
        }
        return total;
    }
}
