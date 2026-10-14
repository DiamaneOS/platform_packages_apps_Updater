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
    private static final String[] PROPERTIES = {"FILE_HASH=aGFzaA==", "FILE_SIZE=5000", "METADATA_HASH=bWV0YQ==", "METADATA_SIZE=400"};
    private static final long INSTALLED = 1757500000;

    private static void check(boolean v) { if (!v) throw new AssertionError(); }
    private interface Action { void run() throws Exception; }
    private static void refuses(Action action) throws Exception {
        try { action.run(); } catch (GeneralSecurityException e) { return; }
        throw new AssertionError("Expected refusal");
    }
    private static PackageChecks.Metadata metadata(String text) throws Exception {
        return PackageChecks.metadata(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static String[] with(String... extra) {
        String[] all = java.util.Arrays.copyOf(PROPERTIES, PROPERTIES.length + extra.length);
        System.arraycopy(extra, 0, all, PROPERTIES.length, extra.length);
        return all;
    }
    private static String[] replacing(String key, String line) {
        return java.util.Arrays.stream(PROPERTIES).map(p -> p.startsWith(key + "=") ? line : p).toArray(String[]::new);
    }
    private static void local(String text, String[] properties, long length) throws Exception {
        PackageChecks.Metadata m = metadata(text);
        PackageChecks.requireDevice(m, "FP6", "2026091000", "brand/FP6/FP6:17/BP4A.260905.001/2026091000:user/release-keys");
        PackageChecks.requireLocalTarget(m, INSTALLED, "2026-09-05");
        PackageChecks.requireLocalPayload(m, properties, length);
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
            case "local_target":
                local(INCREMENTAL, PROPERTIES, 6000);
                PackageChecks.requireLocalTarget(metadata(FULL), INSTALLED, "2026-10-05");
                for (long installed : new long[]{0, -1, 1760400000, 1760400001}) {
                    refuses(() -> PackageChecks.requireLocalTarget(metadata(FULL), installed, "2026-09-05"));
                }
                refuses(() -> PackageChecks.requireLocalTarget(metadata(FULL), INSTALLED, "2026-11-05"));
                for (String text : new String[]{FULL + "ota-downgrade=yes\n", FULL + "spl-downgrade=yes\n", FULL + "ota-wipe=yes\n",
                        FULL.replace("post-security-patch-level=2026-10-05\n", ""),
                        FULL.replace("post-security-patch-level=2026-10-05", "post-security-patch-level=October")}) {
                    refuses(() -> PackageChecks.requireLocalTarget(metadata(text), INSTALLED, "2026-09-05"));
                }
                break;
            case "local_payload":
                PackageChecks.requireLocalPayload(metadata(FULL), with("POWERWASH=0", "SWITCH_SLOT_ON_REBOOT=1"), 6000);
                refuses(() -> PackageChecks.requireLocalPayload(metadata(FULL), PROPERTIES, 5999));
                refuses(() -> PackageChecks.requireLocalPayload(metadata(FULL.replace(":1000:5000,payload_properties", ":1000:0,payload_properties")), PROPERTIES, 6000));
                for (String[] properties : new String[][]{with("POWERWASH=1"), with("SPL_DOWNGRADE=1"), with("POWERWASH=yes"),
                        with("FILE_SIZE=5000"), with("garbage"), with("lower_case=1"), with("=1"),
                        replacing("FILE_SIZE", "FILE_SIZE=4999"), replacing("FILE_HASH", "FILE_HASH="),
                        replacing("METADATA_HASH", "OTHER=1"), replacing("METADATA_SIZE", "METADATA_SIZE=5000"),
                        replacing("METADATA_SIZE", "METADATA_SIZE=0"), replacing("METADATA_SIZE", "METADATA_SIZE=x")}) {
                    refuses(() -> PackageChecks.requireLocalPayload(metadata(FULL), properties, 6000));
                }
                break;
            default: throw new IllegalArgumentException();
        }
    }
}
