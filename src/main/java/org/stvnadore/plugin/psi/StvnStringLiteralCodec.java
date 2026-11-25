package org.stvnadore.plugin.psi;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnStringCharacterPolicy;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pure-JDK string literal decoder, dedenter, and canonical formatter free of IntelliJ PSI class
 * linkage so headless {@code unitTest} suites and live PSI evaluators share identical semantics.
 */
@NullMarked
final class StvnStringLiteralCodec {

    private static final Pattern VALID_FENCE_TAG_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,256}$");

    private StvnStringLiteralCodec() {
    }

    static Optional<String> decodeStringLiteral(@Nullable String rawLiteral, boolean preserveIndent) {
        if (rawLiteral == null
                || rawLiteral.length() < 2
                || StvnStringCharacterPolicy.containsUnpairedSurrogate(rawLiteral)) {
            return Optional.empty();
        }
        if (rawLiteral.startsWith("\"\"\"[")) {
            if (containsForbiddenRawCodePoint(rawLiteral)) {
                return Optional.empty();
            }
            String normalized = StvnStringCharacterPolicy.normalizeLineEndingsToLf(rawLiteral);
            int openCloseBracket = normalized.indexOf(']', 4);
            if (openCloseBracket <= 4) {
                return Optional.empty();
            }
            String tag = normalized.substring(4, openCloseBracket);
            if (!VALID_FENCE_TAG_PATTERN.matcher(tag).matches()) {
                return Optional.empty();
            }
            String closeDelim = "[" + tag + "]\"\"\"";
            if (!normalized.endsWith(closeDelim)) {
                return Optional.empty();
            }
            int firstNewline = normalized.indexOf('\n', openCloseBracket + 1);
            if (firstNewline < 0) {
                return Optional.empty();
            }
            for (int i = openCloseBracket + 1; i < firstNewline; i++) {
                char ch = normalized.charAt(i);
                if (ch != ' ' && ch != '\t') {
                    return Optional.empty();
                }
            }
            int payloadStart = firstNewline + 1;
            int payloadEnd = normalized.length() - closeDelim.length();
            if (payloadStart > payloadEnd) {
                return Optional.empty();
            }
            String body = normalized.substring(payloadStart, payloadEnd);
            return Optional.of(preserveIndent ? body : trimCommonIndent(body));
        }
        if (rawLiteral.startsWith("\"") && rawLiteral.endsWith("\"") && !rawLiteral.startsWith("\"\"\"")) {
            String inner = rawLiteral.substring(1, rawLiteral.length() - 1);
            StringBuilder sb = new StringBuilder(inner.length());
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '\\') {
                    if (i + 1 >= inner.length()) {
                        return Optional.empty();
                    }
                    char next = inner.charAt(++i);
                    switch (next) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 >= inner.length()) {
                                return Optional.empty();
                            }
                            int codeUnit = parseFourHexUnits(inner, i + 1);
                            if (codeUnit < 0) {
                                return Optional.empty();
                            }
                            if (Character.isHighSurrogate((char) codeUnit)) {
                                if (i + 10 >= inner.length()
                                        || inner.charAt(i + 5) != '\\'
                                        || inner.charAt(i + 6) != 'u') {
                                    return Optional.empty();
                                }
                                int lowUnit = parseFourHexUnits(inner, i + 7);
                                if (lowUnit < 0 || !Character.isLowSurrogate((char) lowUnit)) {
                                    return Optional.empty();
                                }
                                sb.append((char) codeUnit).append((char) lowUnit);
                                i += 10;
                            } else if (Character.isLowSurrogate((char) codeUnit)) {
                                return Optional.empty();
                            } else {
                                sb.append((char) codeUnit);
                                i += 4;
                            }
                        }
                        default -> {
                            return Optional.empty();
                        }
                    }
                } else {
                    int cp = inner.codePointAt(i);
                    if (cp < 0x20 || cp == '"' || StvnStringCharacterPolicy.isForbiddenRawSourceCodePoint(cp)) {
                        return Optional.empty();
                    }
                    sb.appendCodePoint(cp);
                    if (Character.charCount(cp) == 2) {
                        i++;
                    }
                }
            }
            return Optional.of(sb.toString());
        }
        return Optional.empty();
    }

    static String trimCommonIndent(String text) {
        if (text.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (isAsciiSpacesOnly(line)) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            if (indent < minIndent) {
                minIndent = indent;
            }
        }
        if (minIndent == Integer.MAX_VALUE) {
            minIndent = 0;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            if (!isAsciiSpacesOnly(line)) {
                out.append(minIndent <= line.length() ? line.substring(minIndent) : "");
            }
        }
        return out.toString();
    }

    static boolean shouldEmitFencedPretty(String decoded, boolean preserveIndent) {
        return containsSimpleEscapeTrigger(decoded)
            && StvnStringCharacterPolicy.canRepresentVerbatimInFencedString(decoded, preserveIndent);
    }

    static String formatCanonicalQuotedString(String decoded) {
        return "\"" + StvnStringCharacterPolicy.escapeSimpleStringBody(decoded) + "\"";
    }

    private static boolean containsSimpleEscapeTrigger(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"' || c == '\n' || c == '\t') {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiSpacesOnly(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != ' ') {
                return false;
            }
        }
        return true;
    }

    private static int parseFourHexUnits(String text, int start) {
        int codeUnit = 0;
        for (int k = 0; k < 4; k++) {
            int digit = Character.digit(text.charAt(start + k), 16);
            if (digit < 0) {
                return -1;
            }
            codeUnit = (codeUnit << 4) | digit;
        }
        return codeUnit;
    }

    private static boolean containsForbiddenRawCodePoint(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (StvnStringCharacterPolicy.isForbiddenRawSourceCodePoint(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }
}
