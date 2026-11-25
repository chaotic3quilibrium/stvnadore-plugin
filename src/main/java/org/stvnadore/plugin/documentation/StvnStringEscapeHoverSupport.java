package org.stvnadore.plugin.documentation;

import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnStringCharacterPolicy;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;

import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Decodes valid backslash escape sequences inside {@code "..."} simple string tokens, computes
 * enclosing Unicode Standard Annex #29 extended grapheme clusters (such as ZWJ and variation-selector
 * emoji sequences), and renders {@link DocumentationMarkup} HTML hover cards.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnStringEscapeHoverSupport {

    /**
     * Explicit indicator rendered in place of blank whitespace for control, format, bidirectional,
     * joiner, variation-selector, and whitespace-only Unicode scalars.
     */
    public static final String NON_PRINTABLE_INDICATOR = "[Non-Printable / Invisible Character]";

    /**
     * Immutable hover model for a resolved valid escape sequence inside {@code "..."}.
     *
     * @param sourceEscape           exact authored escape slice (for example, {@code "\\n"} or {@code "\\uD83D\\uDE80"})
     * @param codePoint              decoded Unicode Scalar Value
     * @param scalarId               uppercase scalar ID (for example, {@code "U+1F680"})
     * @param characterName          uppercase Unicode / ISO-6429 character name (for example, {@code "ROCKET"})
     * @param utf8HexBytes           space-delimited uppercase UTF-8 byte sequence (for example, {@code "F0 9F 9A 80"})
     * @param printableScalarGlyph   {@code true} when {@code codePoint} renders a visible graphical glyph
     * @param composedClusterPreview composed multi-codepoint UAX #29 cluster when part of a graphical sequence, else {@code null}
     * @param composedScalarChain    plus-delimited scalar list for {@code composedClusterPreview}, else {@code null}
     * @param scrutinyNote           concise STE scrutiny or fenced-string note when hazardous or {@code CR}, else {@code null}
     * @param relStart               0-based relative start offset inside the literal token
     * @param relEnd                 0-based relative end offset (exclusive) inside the literal token
     */
    public record EscapeHoverModel(
        String sourceEscape,
        int codePoint,
        String scalarId,
        String characterName,
        String utf8HexBytes,
        boolean printableScalarGlyph,
        @Nullable String composedClusterPreview,
        @Nullable String composedScalarChain,
        @Nullable String scrutinyNote,
        int relStart,
        int relEnd
    ) {}

    private record DecodedUnit(
        int relStart,
        int relEnd,
        int codePoint,
        boolean escaped,
        boolean valid
    ) {}

    private StvnStringEscapeHoverSupport() {
    }

    /**
     * Resolves a synthetic {@link StvnStringEscapeHoverElement} strictly when {@code targetOffset}
     * falls inside a valid backslash escape sequence within a {@code LITERAL_STRING_SIMPLE} leaf.
     *
     * @param file           containing PSI file
     * @param contextElement leaf or wrapper PSI element at the hover/caret offset
     * @param targetOffset   0-based document offset of the hover/caret request
     * @return synthetic {@link StvnStringEscapeHoverElement}, or {@code null} to preserve standard hover cards
     */
    public static @Nullable StvnStringEscapeHoverElement resolveEscapeHoverElement(
            PsiFile file,
            @Nullable PsiElement contextElement,
            int targetOffset
    ) {
        PsiElement leaf = extractSimpleStringLeaf(file, contextElement, targetOffset);
        if (leaf == null) {
            return null;
        }
        TextRange leafRange = leaf.getTextRange();
        int relOffset = targetOffset - leafRange.getStartOffset();
        Optional<EscapeHoverModel> resolved = resolveEscapeAtRelativeOffset(leaf.getText(), relOffset);
        if (resolved.isEmpty()) {
            return null;
        }
        EscapeHoverModel model = resolved.get();
        TextRange absEscRange = new TextRange(
            leafRange.getStartOffset() + model.relStart(),
            leafRange.getStartOffset() + model.relEnd()
        );
        return new StvnStringEscapeHoverElement(leaf, absEscRange, model);
    }

    private static @Nullable PsiElement extractSimpleStringLeaf(
            PsiFile file,
            @Nullable PsiElement contextElement,
            int targetOffset
    ) {
        if (contextElement != null && contextElement.getNode() != null) {
            if (contextElement.getNode().getElementType() == StvnTypes.LITERAL_STRING_SIMPLE) {
                return contextElement;
            }
            if (contextElement instanceof StringLiteral sl) {
                var child = sl.getNode().findChildByType(StvnTypes.LITERAL_STRING_SIMPLE);
                if (child != null) {
                    return child.getPsi();
                }
            }
        }
        if (targetOffset >= 0 && targetOffset < file.getTextLength()) {
            PsiElement atOffset = file.findElementAt(targetOffset);
            if (atOffset != null && atOffset.getNode() != null
                    && atOffset.getNode().getElementType() == StvnTypes.LITERAL_STRING_SIMPLE) {
                return atOffset;
            }
        }
        return null;
    }

    /**
     * Pure string-level resolver extracting {@link EscapeHoverModel} when {@code relOffset} falls
     * strictly inside a valid escape sequence of {@code rawLiteralText} ({@code "..."}).
     *
     * @param rawLiteralText raw {@code LITERAL_STRING_SIMPLE} token text
     * @param relOffset      0-based offset relative to {@code rawLiteralText}
     * @return resolved {@link EscapeHoverModel} when inside a valid escape, else {@link Optional#empty()}
     */
    public static Optional<EscapeHoverModel> resolveEscapeAtRelativeOffset(String rawLiteralText, int relOffset) {
        int len = rawLiteralText.length();
        if (len < 2 || rawLiteralText.charAt(0) != '"') {
            return Optional.empty();
        }
        int contentEnd = hasUnescapedClosingQuote(rawLiteralText) ? len - 1 : len;
        if (relOffset < 1 || relOffset >= contentEnd) {
            return Optional.empty();
        }

        List<DecodedUnit> units = tokenizeSimpleStringInterior(rawLiteralText, contentEnd);
        int targetIdx = -1;
        for (int i = 0; i < units.size(); i++) {
            DecodedUnit u = units.get(i);
            if (relOffset >= u.relStart() && relOffset < u.relEnd()) {
                targetIdx = i;
                break;
            }
        }
        if (targetIdx < 0) {
            return Optional.empty();
        }
        DecodedUnit target = units.get(targetIdx);
        if (!target.escaped() || !target.valid()) {
            return Optional.empty();
        }

        // Extract maximal contiguous valid decoded run surrounding targetIdx for UAX #29 cluster analysis
        int runStart = targetIdx;
        while (runStart > 0 && units.get(runStart - 1).valid()) {
            runStart--;
        }
        int runEnd = targetIdx + 1;
        while (runEnd < units.size() && units.get(runEnd).valid()) {
            runEnd++;
        }

        StringBuilder runBuilder = new StringBuilder();
        int targetUtf16OffsetInRun = 0;
        for (int i = runStart; i < runEnd; i++) {
            if (i == targetIdx) {
                targetUtf16OffsetInRun = runBuilder.length();
            }
            runBuilder.appendCodePoint(units.get(i).codePoint());
        }
        String validRunText = runBuilder.toString();

        String composedPreview = null;
        String composedChain = null;
        String cluster = extractEnclosingGraphemeCluster(validRunText, targetUtf16OffsetInRun);
        if (cluster != null && cluster.codePointCount(0, cluster.length()) >= 2 && containsGraphicalCodePoint(cluster)) {
            composedPreview = cluster;
            composedChain = cluster.codePoints()
                .mapToObj(StvnStringCharacterPolicy::formatUnicodeScalarId)
                .collect(Collectors.joining(" + "));
        }

        int cp = target.codePoint();
        String sourceEscape = rawLiteralText.substring(target.relStart(), target.relEnd());
        String scalarId = StvnStringCharacterPolicy.formatUnicodeScalarId(cp);
        String charName = StvnStringCharacterPolicy.resolveUnicodeCharacterName(cp);
        String utf8Hex = formatUtf8HexBytes(cp);
        boolean printableScalar = isGraphicalPreviewCodePoint(cp);
        String scrutinyNote = buildScrutinyOrFencedNote(sourceEscape, cp);

        return Optional.of(new EscapeHoverModel(
            sourceEscape,
            cp,
            scalarId,
            charName,
            utf8Hex,
            printableScalar,
            composedPreview,
            composedChain,
            scrutinyNote,
            target.relStart(),
            target.relEnd()
        ));
    }

    /**
     * Renders clean {@link DocumentationMarkup}-compatible HTML for a resolved escape sequence.
     *
     * @param element synthetic escape hover element
     * @return rendered HTML documentation string
     */
    public static String renderEscapeHoverHtml(StvnStringEscapeHoverElement element) {
        return renderEscapeHoverHtml(element.model());
    }

    /**
     * Renders clean {@link DocumentationMarkup}-compatible HTML for an {@link EscapeHoverModel}.
     *
     * @param model escape hover metadata model
     * @return rendered HTML documentation string
     */
    public static String renderEscapeHoverHtml(EscapeHoverModel model) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(DocumentationMarkup.DEFINITION_START);
        sb.append("<b>STVN String Escape Sequence</b> <code>")
          .append(escapeHtml(model.sourceEscape()))
          .append("</code>");
        sb.append(DocumentationMarkup.DEFINITION_END);

        sb.append(DocumentationMarkup.CONTENT_START);
        sb.append("<b>Rendered Preview:</b> ");
        if (model.printableScalarGlyph()) {
            int cp = model.codePoint();
            String glyphHtml = escapeHtml(Character.toString(cp));
            if (Character.isSupplementaryCodePoint(cp) || (cp > 0x007F && Character.isEmoji(cp))) {
                sb.append("<span style=\"font-size: 1.25em;\">")
                  .append(glyphHtml)
                  .append("</span>");
            } else {
                sb.append("<span style=\"font-size: 1.25em;\"><code>")
                  .append(glyphHtml)
                  .append("</code></span>");
            }
        } else {
            sb.append("<code>").append(escapeHtml(NON_PRINTABLE_INDICATOR)).append("</code>");
        }
        sb.append("<br/>");

        if (model.composedClusterPreview() != null && model.composedScalarChain() != null) {
            sb.append("<b>Composed Sequence Preview:</b> <span style=\"font-size: 1.25em;\">")
              .append(escapeHtml(model.composedClusterPreview()))
              .append("</span> (<code>")
              .append(escapeHtml(model.composedScalarChain()))
              .append("</code>)<br/>");
        }
        sb.append(DocumentationMarkup.CONTENT_END);

        sb.append(DocumentationMarkup.SECTIONS_START);
        appendSectionRow(sb, "Source Escape:", "<code>" + escapeHtml(model.sourceEscape()) + "</code>");
        appendSectionRow(sb, "Unicode Scalar:", "<code>" + escapeHtml(model.scalarId()) + "</code>");
        appendSectionRow(sb, "Character Name:", "<code>" + escapeHtml(model.characterName()) + "</code>");
        appendSectionRow(sb, "UTF-8 Bytes:", "<code>" + escapeHtml(model.utf8HexBytes()) + "</code>");
        if (model.scrutinyNote() != null) {
            appendSectionRow(sb, "Hygiene Note:", escapeHtml(model.scrutinyNote()));
        }
        sb.append(DocumentationMarkup.SECTIONS_END);
        return sb.toString();
    }

    private static void appendSectionRow(StringBuilder sb, String header, String valueHtml) {
        sb.append(DocumentationMarkup.SECTION_HEADER_START)
          .append("<nobr>").append(header).append("</nobr>")
          .append(DocumentationMarkup.SECTION_SEPARATOR)
          .append(valueHtml)
          .append(DocumentationMarkup.SECTION_END);
    }

    private static @Nullable String buildScrutinyOrFencedNote(String sourceEscape, int cp) {
        if (StvnStringCharacterPolicy.isHazardousEscapedCodePoint(cp)) {
            return "Hazardous control, bidirectional, or invisible format scalar. "
                + "Forbidden raw in STVN source and cannot be represented verbatim in fenced strings.";
        }
        if (cp == '\r') {
            if ("\\r".equals(sourceEscape)) {
                return "Shorthand '\\r' (U+000D CARRIAGE RETURN) cannot be represented verbatim in fenced strings "
                    + "(which normalize all line breaks to LF U+000A). Use explicit '\\u000D' when intentional.";
            }
            return "Explicit CARRIAGE RETURN escape (0 shorthand warnings). "
                + "Cannot be represented verbatim in fenced strings, which normalize line breaks to LF (U+000A).";
        }
        return null;
    }

    static boolean isGraphicalPreviewCodePoint(int cp) {
        if (!StvnStringCharacterPolicy.isValidUnicodeScalarValue(cp)) {
            return false;
        }
        if (cp <= 0x0020 || (cp >= 0x007F && cp <= 0x009F)) {
            return false;
        }
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            return false;
        }
        if (StvnStringCharacterPolicy.isHazardousEscapedCodePoint(cp)
                || StvnStringCharacterPolicy.isPermittedEmojiJoiner(cp)) {
            return false;
        }
        if ((cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF)) {
            return false;
        }
        int category = Character.getType(cp);
        return category != Character.CONTROL
            && category != Character.FORMAT
            && category != Character.LINE_SEPARATOR
            && category != Character.PARAGRAPH_SEPARATOR
            && category != Character.SPACE_SEPARATOR
            && category != Character.SURROGATE
            && category != Character.PRIVATE_USE
            && category != Character.UNASSIGNED;
    }

    private static boolean containsGraphicalCodePoint(String text) {
        return text.codePoints().anyMatch(StvnStringEscapeHoverSupport::isGraphicalPreviewCodePoint);
    }

    private static @Nullable String extractEnclosingGraphemeCluster(String text, int targetUtf16Offset) {
        if (text.isEmpty() || targetUtf16Offset < 0 || targetUtf16Offset >= text.length()) {
            return null;
        }
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        int start = iterator.isBoundary(targetUtf16Offset)
            ? targetUtf16Offset
            : iterator.preceding(targetUtf16Offset);
        int end = iterator.following(targetUtf16Offset);
        if (start == BreakIterator.DONE || end == BreakIterator.DONE || end <= start) {
            return null;
        }
        return text.substring(start, end);
    }

    private static List<DecodedUnit> tokenizeSimpleStringInterior(String text, int contentEnd) {
        List<DecodedUnit> units = new ArrayList<>();
        for (int i = 1; i < contentEnd; ) {
            char c = text.charAt(i);
            if (c != '\\') {
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 < contentEnd && Character.isLowSurrogate(text.charAt(i + 1))) {
                        int cp = Character.toCodePoint(c, text.charAt(i + 1));
                        boolean ok = !StvnStringCharacterPolicy.isForbiddenRawSourceCodePoint(cp);
                        units.add(new DecodedUnit(i, i + 2, cp, false, ok));
                        i += 2;
                    } else {
                        units.add(new DecodedUnit(i, i + 1, c, false, false));
                        i++;
                    }
                } else if (Character.isLowSurrogate(c)) {
                    units.add(new DecodedUnit(i, i + 1, c, false, false));
                    i++;
                } else {
                    boolean ok = c != '\t' && c != '\r' && c != '\n'
                        && !StvnStringCharacterPolicy.isForbiddenRawSourceCodePoint(c);
                    units.add(new DecodedUnit(i, i + 1, c, false, ok));
                    i++;
                }
                continue;
            }

            if (i + 1 >= contentEnd) {
                units.add(new DecodedUnit(i, contentEnd, '\\', true, false));
                break;
            }
            char esc = text.charAt(i + 1);
            switch (esc) {
                case '"' -> {
                    units.add(new DecodedUnit(i, i + 2, '"', true, true));
                    i += 2;
                }
                case '\\' -> {
                    units.add(new DecodedUnit(i, i + 2, '\\', true, true));
                    i += 2;
                }
                case '/' -> {
                    units.add(new DecodedUnit(i, i + 2, '/', true, true));
                    i += 2;
                }
                case 'b' -> {
                    units.add(new DecodedUnit(i, i + 2, '\b', true, true));
                    i += 2;
                }
                case 'f' -> {
                    units.add(new DecodedUnit(i, i + 2, '\f', true, true));
                    i += 2;
                }
                case 'n' -> {
                    units.add(new DecodedUnit(i, i + 2, '\n', true, true));
                    i += 2;
                }
                case 'r' -> {
                    units.add(new DecodedUnit(i, i + 2, '\r', true, true));
                    i += 2;
                }
                case 't' -> {
                    units.add(new DecodedUnit(i, i + 2, '\t', true, true));
                    i += 2;
                }
                case 'u' -> {
                    int unit1 = parseFourHex(text, i + 2, contentEnd);
                    if (unit1 < 0) {
                        int badEnd = advanceMalformedUnicode(text, i, contentEnd);
                        units.add(new DecodedUnit(i, badEnd, 0, true, false));
                        i = badEnd;
                        continue;
                    }
                    if (Character.isHighSurrogate((char) unit1)) {
                        if (i + 12 <= contentEnd
                                && text.charAt(i + 6) == '\\'
                                && text.charAt(i + 7) == 'u') {
                            int unit2 = parseFourHex(text, i + 8, contentEnd);
                            if (unit2 >= 0 && Character.isLowSurrogate((char) unit2)) {
                                int cp = Character.toCodePoint((char) unit1, (char) unit2);
                                units.add(new DecodedUnit(i, i + 12, cp, true, true));
                                i += 12;
                                continue;
                            }
                        }
                        units.add(new DecodedUnit(i, i + 6, unit1, true, false));
                        i += 6;
                    } else if (Character.isLowSurrogate((char) unit1)) {
                        units.add(new DecodedUnit(i, i + 6, unit1, true, false));
                        i += 6;
                    } else {
                        units.add(new DecodedUnit(i, i + 6, unit1, true, true));
                        i += 6;
                    }
                }
                default -> {
                    units.add(new DecodedUnit(i, i + 2, esc, true, false));
                    i += 2;
                }
            }
        }
        return units;
    }

    static String formatUtf8HexBytes(int codePoint) {
        byte[] utf8 = Character.toString(codePoint).getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(utf8.length * 3);
        for (int i = 0; i < utf8.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.ROOT, "%02X", utf8[i] & 0xFF));
        }
        return sb.toString();
    }

    private static boolean hasUnescapedClosingQuote(String text) {
        int len = text.length();
        if (len < 2 || text.charAt(len - 1) != '"') {
            return false;
        }
        int backslashes = 0;
        for (int k = len - 2; k >= 1 && text.charAt(k) == '\\'; k--) {
            backslashes++;
        }
        return (backslashes & 1) == 0;
    }

    private static int parseFourHex(String text, int hexStart, int limit) {
        if (hexStart + 4 > limit) {
            return -1;
        }
        int val = 0;
        for (int k = 0; k < 4; k++) {
            int digit = Character.digit(text.charAt(hexStart + k), 16);
            if (digit < 0) {
                return -1;
            }
            val = (val << 4) | digit;
        }
        return val;
    }

    private static int advanceMalformedUnicode(String text, int backslashPos, int limit) {
        int cursor = backslashPos + 2;
        int scanned = 0;
        while (cursor < limit && scanned < 4 && Character.digit(text.charAt(cursor), 16) >= 0) {
            cursor++;
            scanned++;
        }
        return cursor;
    }

    private static String escapeHtml(String input) {
        StringBuilder sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
