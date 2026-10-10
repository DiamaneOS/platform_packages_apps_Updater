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
                check(metadata("2026010100 1770000000 FP6 stable\n")[0].equals("2026010100"));
                check(metadata("test.1-2 1770000000 FP6 beta\r\n")[3].equals("beta"));
                for (String text : new String[]{"", "<html>fixture</html>", "x 1 FP6", "x 1 FP6 stable extra", "x 1 FP6 stable\nx 2 FP6 beta", "https://evil.invalid/ 1 FP6 stable", "x 9999999999999999999 FP6 stable"}) {
                    refuses(() -> metadata(text));
                }
                break;
            case "metadata_bounds":
                refuses(() -> DownloadChecks.metadata(new ByteArrayInputStream(new byte[4097])));
                refuses(() -> DownloadChecks.metadata(new ByteArrayInputStream(new byte[]{(byte) 0xff})));
                break;
            case "identity_and_downgrade":
                check(DownloadChecks.newer(metadata("next 11 FP6 stable"), "FP6", "stable", "current", 10));
                check(!DownloadChecks.newer(metadata("current 10 FP6 stable"), "FP6", "stable", "current", 10));
                for (String text : new String[]{"old 9 FP6 stable", "wrong 10 FP6 stable", "current 10 other stable", "current 10 FP6 beta"}) {
                    refuses(() -> DownloadChecks.newer(metadata(text), "FP6", "stable", "current", 10));
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
