package app.seamlessupdate.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ReleaseNotesTest {
    private static void check(boolean v) { if (!v) throw new AssertionError(); }

    private static boolean refused(byte[] bytes) {
        try {
            ReleaseNotes.text(bytes);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean refused(String text) {
        return refused(text.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "names":
                check("FP6-release-notes-2026111400.txt".equals(ReleaseNotes.fileName("FP6", "2026111400")));
                // Only plain build numbers and device names make a file name, never a path.
                for (String build : new String[] {null, "", "../x", "2026111400.txt", "20261114a", "1234567890123456789", " 2026111400"}) {
                    check(ReleaseNotes.fileName("FP6", build) == null);
                }
                for (String device : new String[] {null, "", "FP6/..", "FP 6", "FP6.x"}) {
                    check(ReleaseNotes.fileName(device, "2026111400") == null);
                }
                // The cache name holds the build date too, so a rebuild under the same number is another entry.
                check("2026111400-1794652071".equals(ReleaseNotes.key("2026111400", 1794652071L)));
                check(!ReleaseNotes.key("2026111400", 1794652071L).equals(ReleaseNotes.key("2026111400", 1794652072L)));
                check(ReleaseNotes.key("2026111400", 0) == null && ReleaseNotes.key("x", 5) == null && ReleaseNotes.key(null, 5) == null);
                break;
            case "text":
                final String notes = "Changes since the 2026110600 release:\n\n- Fairphone 6: faster starts.\n  - Ümlauts, “quotes”, 3 % and CVE-2026-1 stay.\n";
                check(notes.equals(ReleaseNotes.text(notes.getBytes(StandardCharsets.UTF_8))));
                // Not UTF-8, empty, blank, too large or too many lines: refused whole.
                check(refused(new byte[] {(byte) 0xc3, 0x28}));
                check(refused(new byte[0]) && refused((byte[]) null) && refused("\n \n"));
                check(refused("a".repeat(ReleaseNotes.MAX_BYTES + 1)) && !refused("a".repeat(ReleaseNotes.MAX_BYTES)));
                check(refused("a\n".repeat(ReleaseNotes.MAX_LINES + 1)) && !refused("a\n".repeat(ReleaseNotes.MAX_LINES)));
                // Characters that make text read differently from how it is stored.
                for (String bad : new String[] {"a\rb", "a\tb", "a\u0000b", "a\u001bb", "a‮b", "a⁦b", "a​b",
                        "a‍b", "a﻿b", "a b", "a b", "ab", "a͸b", "a­b", "a؜b"}) {
                    check(refused(bad));
                }
                break;
            case "lines":
                final List<ReleaseNotes.Line> lines = ReleaseNotes.lines("Intro:\n\n- One\n  - Two\n- Three \n   \nEnd");
                check(lines.size() == 5);
                check(lines.get(0).level == 0 && "Intro:".equals(lines.get(0).text));
                check(lines.get(1).level == 1 && "One".equals(lines.get(1).text));
                check(lines.get(2).level == 2 && "Two".equals(lines.get(2).text));
                check(lines.get(3).level == 1 && "Three".equals(lines.get(3).text));
                check(lines.get(4).level == 0 && "End".equals(lines.get(4).text));
                break;
            default:
                throw new IllegalArgumentException(args[0]);
        }
    }
}
