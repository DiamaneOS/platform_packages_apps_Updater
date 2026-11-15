// SPDX-License-Identifier: Apache-2.0
package app.seamlessupdate.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Release notes as the servers publish them: plain text next to the update files, written from the
 * website's notes when a release is published and never changed afterwards. They are not signed, so
 * they are only ever shown as text: a file that is not plain text of a sane size is refused whole.
 */
final class ReleaseNotes {
    static final int MAX_BYTES = 16 * 1024;
    static final int MAX_LINES = 300;

    /** One line of the notes. level 0 is a paragraph, 1 a bullet, 2 a bullet under a bullet. */
    static final class Line {
        final int level;
        final String text;

        Line(final int level, final String text) {
            this.level = level;
            this.text = text;
        }
    }

    private ReleaseNotes() {}

    /** The notes file of a build on the servers, or null for a build that cannot have one. */
    static String fileName(final String device, final String build) {
        if (device == null || build == null || !device.matches("[A-Za-z0-9_]{1,32}")
                || !build.matches("[0-9]{1,18}")) {
            return null;
        }
        return device + "-release-notes-" + build + ".txt";
    }

    /** The cache name of the notes of a build: its number and its build date, as checked by the service. */
    static String key(final String build, final long date) {
        if (build == null || !build.matches("[0-9]{1,18}") || date <= 0) return null;
        return build + "-" + date;
    }

    /** The text of a notes file. Throws for anything but bounded, strict UTF-8 plain text. */
    static String text(final byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("Release notes are empty or too large");
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Release notes are not UTF-8", e);
        }
        int lines = 1;
        for (int i = 0; i < text.length(); ) {
            final int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (c == '\n') {
                lines++;
                continue;
            }
            switch (Character.getType(c)) {
                // Control characters, direction and zero-width marks, private use, unassigned code
                // points and other line breaks: none belongs in notes, and some could make the
                // text read differently from how it is stored.
                case Character.CONTROL:
                case Character.FORMAT:
                case Character.PRIVATE_USE:
                case Character.SURROGATE:
                case Character.UNASSIGNED:
                case Character.LINE_SEPARATOR:
                case Character.PARAGRAPH_SEPARATOR:
                    throw new IOException("Release notes hold a character that is not plain text");
                default:
            }
        }
        if (text.endsWith("\n")) lines--;
        if (lines > MAX_LINES) throw new IOException("Release notes have too many lines");
        if (text.isBlank()) throw new IOException("Release notes are empty");
        return text;
    }

    /**
     * The lines of checked notes for the screen: "- " starts a bullet, "  - " a bullet under it,
     * anything else is a paragraph. Empty lines are dropped.
     */
    static List<Line> lines(final String text) {
        final List<Line> result = new ArrayList<>();
        for (final String raw : text.split("\n")) {
            final String line = raw.stripTrailing();
            if (line.isBlank()) continue;
            if (line.startsWith("  - ")) {
                result.add(new Line(2, line.substring(4).strip()));
            } else if (line.startsWith("- ")) {
                result.add(new Line(1, line.substring(2).strip()));
            } else {
                result.add(new Line(0, line.strip()));
            }
        }
        return Collections.unmodifiableList(result);
    }
}
