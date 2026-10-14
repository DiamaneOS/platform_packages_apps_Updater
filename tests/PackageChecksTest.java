package app.seamlessupdate.client;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

public final class PackageChecksTest {
    private static final String STREAMING = "ota-streaming-property-files=payload.bin:1000:5000,payload_properties.txt:6100:154,metadata:69:303     \n";
    private static final String FULL = "ota-property-files=metadata:69:303,payload.bin:1000:5000   \n"
            + "ota-required-cache=0\n" + STREAMING + "ota-type=AB\n"
            + "post-build=brand/FP6/FP6:17/BP4A.261005.001/2026101400:user/release-keys\n"
            + "post-build-incremental=2026101400\npost-sdk-level=37\n"
            + "post-security-patch-level=2026-10-05\npost-timestamp=1760400000\npre-device=FP6\n";
    private static final String INCREMENTAL = FULL
            + "pre-build=brand/FP6/FP6:17/BP4A.260905.001/2026091000:user/release-keys\npre-build-incremental=2026091000\n";

    private static void check(boolean v) { if (!v) throw new AssertionError(); }
    private interface Action { void run() throws Exception; }
    private static void refuses(Action action) throws Exception {
        try { action.run(); } catch (GeneralSecurityException e) { return; }
        throw new AssertionError("Expected refusal");
    }
    private static PackageChecks.Metadata metadata(String text) throws Exception {
        return PackageChecks.metadata(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "parse":
                PackageChecks.Metadata m = metadata(INCREMENTAL);
                check(m.timestamp == 1760400000 && m.incremental.equals("2026101400") && m.device.equals("FP6"));
                check(m.type.equals("AB") && m.securityPatch.equals("2026-10-05") && m.sourceIncremental.equals("2026091000"));
                check(m.payloadOffset == 1000 && m.payloadSize == 5000 && m.serialno == null && !m.downgrade && !m.wipe);
                check(metadata(FULL.replace("\n", "\r\n")).payloadOffset == 1000);
                check(PackageChecks.lines(new ByteArrayInputStream("FILE_HASH=a\r\nFILE_SIZE=1\n\n".getBytes(StandardCharsets.UTF_8))).size() == 2);
                break;
            case "malformed":
                for (String text : new String[]{FULL + "garbage\n", FULL + "=x\n", FULL + "pre-device=FP6\n",
                        FULL.replace("post-timestamp=1760400000", "post-timestamp=17604e5"),
                        FULL.replace("post-timestamp=1760400000", "post-timestamp=99999999999999999999"),
                        FULL.replace(STREAMING, ""), FULL.replace("payload.bin:1000:5000,payload_properties", "payload.bin:1000,payload_properties"),
                        FULL.replace("payload.bin:1000:5000,payload_properties", "payload.bin:-1:5000,payload_properties")}) {
                    refuses(() -> metadata(text));
                }
                refuses(() -> PackageChecks.metadata(new ByteArrayInputStream(new byte[PackageChecks.MAX_TEXT_BYTES + 1])));
                refuses(() -> PackageChecks.lines(new ByteArrayInputStream(new byte[]{'a', '=', (byte) 0xff})));
                break;
            case "device":
                PackageChecks.requireDevice(metadata(FULL), "FP6", "any", "any");
                PackageChecks.requireDevice(metadata(INCREMENTAL), "FP6", "2026091000", "brand/FP6/FP6:17/BP4A.260905.001/2026091000:user/release-keys");
                refuses(() -> PackageChecks.requireDevice(metadata(FULL), "FP5", "any", "any"));
                refuses(() -> PackageChecks.requireDevice(metadata(FULL.replace("pre-device=FP6", "pre-device=FP5|FP6")), "FP6", "any", "any"));
                refuses(() -> PackageChecks.requireDevice(metadata(FULL + "serialno=1234\n"), "FP6", "any", "any"));
                refuses(() -> PackageChecks.requireDevice(metadata(FULL.replace("ota-type=AB", "ota-type=BLOCK")), "FP6", "any", "any"));
                refuses(() -> PackageChecks.requireDevice(metadata(INCREMENTAL), "FP6", "2026080100", "brand/FP6/FP6:17/BP4A.260905.001/2026091000:user/release-keys"));
                refuses(() -> PackageChecks.requireDevice(metadata(INCREMENTAL), "FP6", "2026091000", "brand/FP6/FP6:17/BP4A.260905.001/2026091000:userdebug/test-keys"));
                break;
            case "server_target":
                PackageChecks.requireServerTarget(metadata(FULL), 1760400000, "2026101400");
                refuses(() -> PackageChecks.requireServerTarget(metadata(FULL), 1760400001, "2026101400"));
                refuses(() -> PackageChecks.requireServerTarget(metadata(FULL), 1760400000, "2026101401"));
                break;
            default: throw new IllegalArgumentException();
        }
    }
}
