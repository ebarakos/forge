package forge.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * A digest of the board a dump file holds, written into the file's own header.
 *
 * <p>Replaying a dumped position already checks that the restored game matches the file it
 * came from. That catches a board Forge could not rebuild, but it cannot catch a file that
 * is no longer the board that was written: a dump cut short by a full disk, a killed
 * process or a partial copy is still a valid, restorable, <em>different</em> position, and
 * it agrees with itself. Every number measured on it is about a board nobody chose.
 *
 * <p>So the writer records the digest of the payload — every line that is neither blank nor
 * a comment — as one more comment line, and the reader recomputes it. Comments are left out
 * of the digest for two reasons: the header can then gain a field without invalidating
 * dumps written before it, and the checksum line is not part of what it covers. Blank lines
 * are left out because the state parser never sees them either. Trailing whitespace is
 * stripped so a file that has been through a text editor or a Windows checkout still
 * matches.
 *
 * <p>A file carrying no checksum line is accepted. Boards written by hand — for tests, for
 * one-off investigations — are legitimate inputs, and so are dumps written before this
 * existed. Only a checksum that is present and wrong is a refusal.
 */
public final class StateDumpChecksum {

    /** Comment prefix the digest is written under. */
    public static final String HEADER_PREFIX = "# sha256=";

    private StateDumpChecksum() {
    }

    /**
     * The digest of a dump's payload, lower-case hex. Safe to pass either the whole file or
     * just the board: comment and blank lines are skipped, so both give the same answer.
     */
    public static String of(final String stateText) {
        final StringBuilder payload = new StringBuilder();
        if (stateText != null) {
            for (final String raw : stateText.split("\n")) {
                final String line = stripTrailingWhitespace(raw);
                if (line.isEmpty() || line.trim().charAt(0) == '#') {
                    continue;
                }
                payload.append(line).append('\n');
            }
        }
        return sha256(payload.toString());
    }

    /** The digest this file claims, or null when it carries none. */
    public static String declaredIn(final String stateText) {
        if (stateText == null) {
            return null;
        }
        for (final String raw : stateText.split("\n")) {
            final String line = raw.trim();
            if (line.startsWith(HEADER_PREFIX)) {
                return line.substring(HEADER_PREFIX.length()).trim().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * Why this dump cannot be taken as the board that was written, or null when it can.
     * A file with no checksum line has no problem: it is accepted on trust.
     */
    public static String problem(final String stateText) {
        final String declared = declaredIn(stateText);
        if (declared == null || declared.isEmpty()) {
            return null;
        }
        final String actual = of(stateText);
        if (declared.equals(actual)) {
            return null;
        }
        return "the board does not match the checksum written into the file (recorded "
                + declared + ", recomputed " + actual
                + "), so the dump was truncated or edited after it was written";
    }

    /** The header line to write above a board, digest included. */
    public static String headerLineFor(final String board) {
        return HEADER_PREFIX + of(board);
    }

    private static String stripTrailingWhitespace(final String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    private static String sha256(final String text) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime is required to ship SHA-256, so this cannot be handled
            // meaningfully — and silently returning no checksum would be the failure the
            // class exists to prevent.
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
        final byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        final StringBuilder hex = new StringBuilder(hash.length * 2);
        for (final byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
