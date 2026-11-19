package app.seamlessupdate.client;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class DownloadChecksTest {
    private static void check(boolean v) { if (!v) throw new AssertionError(); }
    private interface Action { void run() throws Exception; }
    private static void refuses(Action action) throws Exception {
        try { action.run(); } catch (IOException | java.security.GeneralSecurityException e) { return; }
        throw new AssertionError("Expected refusal");
    }
    private static String[] metadata(String text) throws IOException {
        return DownloadChecks.metadata(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "metadata":
                check(metadata("2026010100 1770000000 sample stable\n")[0].equals("2026010100"));
                check(metadata("test.1-2 1770000000 sample beta\r\n")[3].equals("beta"));
                for (String text : new String[]{"", "<html>fixture</html>", "x 1 sample", "x 1 sample stable extra", "x 1 sample stable\nx 2 sample beta", "https://evil.invalid/ 1 sample stable", "x 9999999999999999999 sample stable"}) {
                    refuses(() -> metadata(text));
                }
                break;
            case "metadata_bounds":
                refuses(() -> DownloadChecks.metadata(new ByteArrayInputStream(
                        new byte[DownloadChecks.MAX_METADATA_BYTES + 1])));
                refuses(() -> DownloadChecks.metadata(new ByteArrayInputStream(new byte[]{(byte) 0xff})));
                break;
            case "metadata_timestamp":
                check(metadata("build 9223372036854775807 sample stable")[1]
                        .equals(Long.toString(Long.MAX_VALUE)));
                check(metadata("build 00000000000000000001 sample stable")[1]
                        .equals("00000000000000000001"));
                for (String timestamp : new String[]{"9223372036854775808", "-1", "+1",
                        "\u0661\u0662\u0663", "\uff11\uff12\uff13", "1\u0662"}) {
                    refuses(() -> metadata("build " + timestamp + " sample stable"));
                }
                String prefix = "build ";
                String suffix = "1 sample stable";
                String boundary = prefix + "0".repeat(DownloadChecks.MAX_METADATA_BYTES
                        - prefix.length() - suffix.length()) + suffix;
                check(boundary.length() == DownloadChecks.MAX_METADATA_BYTES);
                check(metadata(boundary)[1].endsWith("1"));
                refuses(() -> metadata(boundary + "\n"));
                break;
            case "identity_and_downgrade":
                check(DownloadChecks.newer(metadata("next 11 sample stable"), "sample", "stable", 10));
                check(!DownloadChecks.newer(metadata("current 10 sample stable"), "sample", "stable", 10));
                // Older or same build time, also under another name: up to date, not a failure.
                check(!DownloadChecks.newer(metadata("old 9 sample stable"), "sample", "stable", 10));
                check(!DownloadChecks.newer(metadata("other 10 sample stable"), "sample", "stable", 10));
                for (String text : new String[]{"current 10 other stable", "current 10 sample beta", "next 11 other stable", "next 11 sample beta"}) {
                    refuses(() -> DownloadChecks.newer(metadata(text), "sample", "stable", 10));
                }
                break;
            case "range":
                check(DownloadChecks.length(200, 0, 64, null) == 64);
                check(DownloadChecks.length(206, 64, 64, "bytes 64-127/128") == 128);
                for (String value : new String[]{null, "bytes 0-127/128", "bytes 64-128/128", "bytes 64-127/*", "bytes 64-127/129"}) {
                    refuses(() -> DownloadChecks.length(206, 64, 64, value));
                }
                refuses(() -> DownloadChecks.length(200, 64, 64, "bytes 64-127/128"));
                break;
            case "length_bounds":
                for (long size : new long[]{-1, 0, DownloadChecks.MAX_PACKAGE_BYTES + 1, Long.MAX_VALUE}) {
                    refuses(() -> DownloadChecks.length(200, 0, size, null));
                }
                refuses(() -> DownloadChecks.length(206, Long.MAX_VALUE, 1, null));
                check(DownloadChecks.length(200, 0, DownloadChecks.MAX_PACKAGE_BYTES, null) == DownloadChecks.MAX_PACKAGE_BYTES);
                break;
            default: throw new IllegalArgumentException();
        }
    }
}
