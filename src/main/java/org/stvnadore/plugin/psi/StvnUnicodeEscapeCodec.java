package org.stvnadore.plugin.psi;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnStringCharacterPolicy;

import java.util.Locale;
import java.util.Optional;

/**
 * Pure-Java codec for escaping raw non-ASCII characters ({@code > 0x7F}) as uppercase
 * {@code \u005CuXXXX} sequences and unescaping lexically safe {@code \u005CuXXXX} sequences back
 * to UTF-8 inside STVN single-line simple string literals ({@code "..."}).
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnUnicodeEscapeCodec {

    private StvnUnicodeEscapeCodec() {}

    /**
     * Encodes all raw UTF-16 code units {@code > 0x7F} inside a lexically valid double-quoted
     * simple string literal ({@code "..."}) as uppercase 4-hex {@code \u005CuXXXX} escapes while
     * preserving all existing escape sequences verbatim.
     *
     * @param rawQuotedLiteral authored {@code "..."} token text
     * @return transformed {@code "..."} literal when well-formed, or {@link Optional#empty()} if malformed or containing raw unpaired surrogates
     */
    public static Optional<String> escapeNonAsciiInSimpleStringLiteral(String rawQuotedLiteral) {
        if (!isWellFormedSimpleStringToken(rawQuotedLiteral)) {
            return Optional.empty();
        }
        int contentEnd = rawQuotedLiteral.length() - 1;
        StringBuilder sb = new StringBuilder(rawQuotedLiteral.length() + 16);
        sb.append('"');
        for (int i = 1; i < contentEnd; ) {
            char c = rawQuotedLiteral.charAt(i);
            if (c == '\\') {
                int escLen = measureValidEscapeLength(rawQuotedLiteral, i, contentEnd);
                if (escLen < 0) {
                    return Optional.empty();
                }
                sb.append(rawQuotedLiteral, i, i + escLen);
                i += escLen;
                continue;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= contentEnd || !Character.isLowSurrogate(rawQuotedLiteral.charAt(i + 1))) {
                    return Optional.empty();
                }
                char low = rawQuotedLiteral.charAt(i + 1);
                appendUppercaseUnicodeEscape(sb, c);
                appendUppercaseUnicodeEscape(sb, low);
                i += 2;
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                return Optional.empty();
            }
            if (c > 0x7F) {
                appendUppercaseUnicodeEscape(sb, c);
            } else {
                sb.append(c);
            }
            i++;
        }
        sb.append('"');
        return Optional.of(sb.toString());
    }

    /**
     * Determines whether {@code rawQuotedLiteral} is a well-formed simple string literal whose
     * raw non-ASCII characters change under {@link #escapeNonAsciiInSimpleStringLiteral(String)}.
     *
     * @param rawQuotedLiteral authored {@code "..."} token text
     * @return {@code true} when at least one raw {@code > 0x7F} character can be escaped
     */
    public static boolean hasEscapableRawNonAscii(String rawQuotedLiteral) {
        Optional<String> escaped = escapeNonAsciiInSimpleStringLiteral(rawQuotedLiteral);
        return escaped.isPresent() && !escaped.get().equals(rawQuotedLiteral);
    }

    /**
     * Decodes lexically safe {@code \u005CuXXXX} escapes (including paired UTF-16 surrogate
     * escapes {@code \u005CuD83D\u005CuDE80} and {@code \u005Cu200D} ZWJ / {@code \u005CuFE0F}
     * variation selectors) to raw UTF-8 characters inside {@code "..."} while keeping lone
     * surrogates, ASCII/C0/DEL/C1 controls, line separators, {@code "}, {@code \}, and forbidden
     * raw scalars untouched.
     *
     * @param rawQuotedLiteral authored {@code "..."} token text
     * @return transformed {@code "..."} literal when well-formed, or {@link Optional#empty()} if malformed
     */
    public static Optional<String> unescapeUnicodeInSimpleStringLiteral(String rawQuotedLiteral) {
        if (!isWellFormedSimpleStringToken(rawQuotedLiteral)) {
            return Optional.empty();
        }
        int contentEnd = rawQuotedLiteral.length() - 1;
        StringBuilder sb = new StringBuilder(rawQuotedLiteral.length());
        sb.append('"');
        for (int i = 1; i < contentEnd; ) {
            char c = rawQuotedLiteral.charAt(i);
            if (c != '\\') {
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= contentEnd || !Character.isLowSurrogate(rawQuotedLiteral.charAt(i + 1))) {
                        return Optional.empty();
                    }
                    sb.append(c).append(rawQuotedLiteral.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (Character.isLowSurrogate(c)) {
                    return Optional.empty();
                }
                sb.append(c);
                i++;
                continue;
            }
            int escLen = measureValidEscapeLength(rawQuotedLiteral, i, contentEnd);
            if (escLen < 0) {
                return Optional.empty();
            }
            if (rawQuotedLiteral.charAt(i + 1) != 'u') {
                sb.append(rawQuotedLiteral, i, i + escLen);
                i += escLen;
                continue;
            }
            int unit1 = parseFourHexDigits(rawQuotedLiteral, i + 2);
            if (Character.isHighSurrogate((char) unit1)) {
                if (i + 12 <= contentEnd
                        && rawQuotedLiteral.charAt(i + 6) == '\\'
                        && rawQuotedLiteral.charAt(i + 7) == 'u') {
                    int unit2 = parseFourHexDigits(rawQuotedLiteral, i + 8);
                    if (unit2 >= 0 && Character.isLowSurrogate((char) unit2)) {
                        int supplementary = Character.toCodePoint((char) unit1, (char) unit2);
                        if (isSafeRawSimpleStringCodePoint(supplementary)) {
                            sb.append((char) unit1).append((char) unit2);
                        } else {
                            sb.append(rawQuotedLiteral, i, i + 12);
                        }
                        i += 12;
                        continue;
                    }
                }
                sb.append(rawQuotedLiteral, i, i + 6);
                i += 6;
            } else if (Character.isLowSurrogate((char) unit1)) {
                sb.append(rawQuotedLiteral, i, i + 6);
                i += 6;
            } else {
                if (isSafeRawSimpleStringCodePoint(unit1)) {
                    sb.append((char) unit1);
                } else {
                    sb.append(rawQuotedLiteral, i, i + 6);
                }
                i += 6;
            }
        }
        sb.append('"');
        return Optional.of(sb.toString());
    }

    /**
     * Determines whether {@code rawQuotedLiteral} contains at least one safe {@code \u005CuXXXX}
     * sequence that materializes to UTF-8 under {@link #unescapeUnicodeInSimpleStringLiteral(String)}.
     *
     * @param rawQuotedLiteral authored {@code "..."} token text
     * @return {@code true} when at least one {@code \u005CuXXXX} sequence can be safely unescaped
     */
    public static boolean hasSafeUnescapableUnicode(String rawQuotedLiteral) {
        Optional<String> unescaped = unescapeUnicodeInSimpleStringLiteral(rawQuotedLiteral);
        return unescaped.isPresent() && !unescaped.get().equals(rawQuotedLiteral);
    }

    /**
     * Tests whether Unicode Scalar Value {@code codePoint} can be materialized as a raw character
     * inside a single-line STVN simple string literal with zero lexical or character hygiene violations.
     *
     * @param codePoint Unicode code point
     * @return {@code true} if safe to emit verbatim inside {@code "..."}
     */
    public static boolean isSafeRawSimpleStringCodePoint(int codePoint) {
        if (!Character.isValidCodePoint(codePoint)) {
            return false;
        }
        if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
            return false;
        }
        if (codePoint <= 0x001F || (codePoint >= 0x007F && codePoint <= 0x009F)) {
            return false;
        }
        if (codePoint == '"' || codePoint == '\\') {
            return false;
        }
        return !StvnStringCharacterPolicy.isForbiddenRawSourceCodePoint(codePoint);
    }

    private static boolean isWellFormedSimpleStringToken(String text) {
        int len = text.length();
        if (len < 2 || text.charAt(0) != '"' || text.charAt(len - 1) != '"' || text.startsWith("\"\"\"")) {
            return false;
        }
        int backslashes = 0;
        for (int k = len - 2; k >= 1 && text.charAt(k) == '\\'; k--) {
            backslashes++;
        }
        if ((backslashes & 1) != 0) {
            return false;
        }
        for (int i = 1; i < len - 1; i++) {
            char c = text.charAt(i);
            if (c < 0x20) {
                return false;
            }
        }
        return true;
    }

    private static int measureValidEscapeLength(String text, int backslashIdx, int contentEnd) {
        if (backslashIdx + 1 >= contentEnd) {
            return -1;
        }
        char esc = text.charAt(backslashIdx + 1);
        return switch (esc) {
            case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> 2;
            case 'u' -> (backslashIdx + 6 <= contentEnd && parseFourHexDigits(text, backslashIdx + 2) >= 0) ? 6 : -1;
            default -> -1;
        };
    }

    private static int parseFourHexDigits(String text, int hexStart) {
        if (hexStart + 4 > text.length()) {
            return -1;
        }
        int val = 0;
        for (int k = 0; k < 4; k++) {
            int d = Character.digit(text.charAt(hexStart + k), 16);
            if (d < 0) {
                return -1;
            }
            val = (val << 4) | d;
        }
        return val;
    }

    private static void appendUppercaseUnicodeEscape(StringBuilder sb, char unit) {
        sb.append(String.format(Locale.ROOT, "\\u%04X", (int) unit));
    }
}
