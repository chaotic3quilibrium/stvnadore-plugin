package org.stvnadore.plugin.documentation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless unit tests for {@link StvnStringEscapeHoverSupport} covering shorthand escapes,
 * BMP Unicode escapes, paired UTF-16 surrogates, multi-codepoint UAX #29 ZWJ / variation-selector
 * emoji clusters, non-printable indicators, and HTML escaping.
 */
@NullMarked
public final class StvnStringEscapeHoverSupportUnitTest {

    @Test
    public void testShorthandAndBmpEscapesDecodeMetadataAndNonPrintableIndicators() {
        String raw = "\"A\\n\\r\\u000D\\u0041\\u2705\\u202E\\\"\\\\\\/\"";

        // Index 0 is opening quote '"', index 1 is unescaped 'A' -> neither activates escape hover
        assertTrue(StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(raw, 0).isEmpty());
        assertTrue(StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(raw, 1).isEmpty());
        assertTrue(StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(raw, raw.length() - 1).isEmpty());

        // \n at [2, 4)
        var lfModel = StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(raw, 2).orElseThrow();
        assertEquals("\\n", lfModel.sourceEscape());
        assertEquals("U+000A", lfModel.scalarId());
        assertEquals("LINE FEED", lfModel.characterName());
        assertEquals("0A", lfModel.utf8HexBytes());
        assertFalse(lfModel.printableScalarGlyph());
        assertNull(lfModel.scrutinyNote());
        String lfHtml = StvnStringEscapeHoverSupport.renderEscapeHoverHtml(lfModel);
        assertTrue(lfHtml.contains(StvnStringEscapeHoverSupport.NON_PRINTABLE_INDICATOR));

        // \r at [4, 6)
        var crShortModel = StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(raw, 5).orElseThrow();
        assertEquals("\\r", crShortModel.sourceEscape());
        assertEquals("U+000D", crShortModel.scalarId());
        assertEquals("CARRIAGE RETURN", crShortModel.characterName());
        assertEquals("0D", crShortModel.utf8HexBytes());
        assertFalse(crShortModel.printableScalarGlyph());
        assertNotNull(crShortModel.scrutinyNote());
        assertTrue(crShortModel.scrutinyNote().contains("\\u000D"));

        // Explicit U+000D escape at [6, 12)
        var crExplicitModel = StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(raw, 9).orElseThrow();
        assertEquals("\\u000D", crExplicitModel.sourceEscape());
        assertEquals("U+000D", crExplicitModel.scalarId());
        assertFalse(crExplicitModel.printableScalarGlyph());
        assertNotNull(crExplicitModel.scrutinyNote());
        assertTrue(crExplicitModel.scrutinyNote().contains("0 shorthand warnings"));

        // \u0041 ('A') at [12, 18) -> ASCII printable rendered inside <code>A</code>
        var letterModel = StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(raw, 14).orElseThrow();
        assertEquals("U+0041", letterModel.scalarId());
        assertEquals("LATIN CAPITAL LETTER A", letterModel.characterName());
        assertEquals("41", letterModel.utf8HexBytes());
        assertTrue(letterModel.printableScalarGlyph());
        String letterHtml = StvnStringEscapeHoverSupport.renderEscapeHoverHtml(letterModel);
        assertTrue(letterHtml.contains("<code>A</code>"));

        // \u2705 ('✅') at [18, 24) -> BMP emoji rendered without <code> wrapper for color fallback font
        var checkEmojiModel = StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(raw, 20).orElseThrow();
        assertEquals("U+2705", checkEmojiModel.scalarId());
        assertEquals("E2 9C 85", checkEmojiModel.utf8HexBytes());
        assertTrue(checkEmojiModel.printableScalarGlyph());
        String checkEmojiHtml = StvnStringEscapeHoverSupport.renderEscapeHoverHtml(checkEmojiModel);
        assertTrue(checkEmojiHtml.contains("<span style=\"font-size: 1.25em;\">\u2705</span>"));

        // \u202E (RLO) at [24, 30) -> non-printable + hazardous scrutiny note
        var bidiModel = StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(raw, 27).orElseThrow();
        assertEquals("U+202E", bidiModel.scalarId());
        assertEquals("RIGHT-TO-LEFT OVERRIDE", bidiModel.characterName());
        assertEquals("E2 80 AE", bidiModel.utf8HexBytes());
        assertFalse(bidiModel.printableScalarGlyph());
        assertNotNull(bidiModel.scrutinyNote());
        assertTrue(bidiModel.scrutinyNote().contains("Hazardous"));
    }

    @Test
    public void testSurrogatePairResolvesIdenticallyFromHighAndLowHalf() {
        String raw = "\"\\uD83D\\uDE80\"";
        var fromHigh = StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(raw, 2).orElseThrow();
        var fromLow = StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(raw, 9).orElseThrow();

        assertEquals(fromHigh, fromLow);
        assertEquals("\\uD83D\\uDE80", fromHigh.sourceEscape());
        assertEquals(0x1F680, fromHigh.codePoint());
        assertEquals("U+1F680", fromHigh.scalarId());
        assertEquals("ROCKET", fromHigh.characterName());
        assertEquals("F0 9F 9A 80", fromHigh.utf8HexBytes());
        assertTrue(fromHigh.printableScalarGlyph());
        assertNull(fromHigh.composedClusterPreview());

        String html = StvnStringEscapeHoverSupport.renderEscapeHoverHtml(fromHigh);
        assertTrue(html.contains("<span style=\"font-size: 1.25em;\">\uD83D\uDE80</span>"));
        assertTrue(html.contains("F0 9F 9A 80"));
    }

    @Test
    public void testZwjAndVariationSelectorEmojiClustersRenderComposedSequencePreview() {
        String allEscapedZwj = "\"\\uD83D\\uDC68\\u200D\\uD83D\\uDCBB\"";
        // Hovering \uD83D\uDC68 (MAN)
        var manHover = StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(allEscapedZwj, 3).orElseThrow();
        assertEquals("U+1F468", manHover.scalarId());
        assertEquals("\uD83D\uDC68\u200D\uD83D\uDCBB", manHover.composedClusterPreview());
        assertEquals("U+1F468 + U+200D + U+1F4BB", manHover.composedScalarChain());

        // Hovering \u200D (ZWJ) inside mixed raw+escaped "👨\u200D💻"
        String mixedZwj = "\"\uD83D\uDC68\\u200D\uD83D\uDCBB\"";
        int zwjStart = mixedZwj.indexOf("\\u200D");
        var zwjHover = StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(mixedZwj, zwjStart + 2).orElseThrow();
        assertEquals("\\u200D", zwjHover.sourceEscape());
        assertEquals("U+200D", zwjHover.scalarId());
        assertEquals("ZERO WIDTH JOINER", zwjHover.characterName());
        assertFalse(zwjHover.printableScalarGlyph());
        assertEquals("\uD83D\uDC68\u200D\uD83D\uDCBB", zwjHover.composedClusterPreview());
        assertEquals("U+1F468 + U+200D + U+1F4BB", zwjHover.composedScalarChain());

        String zwjHtml = StvnStringEscapeHoverSupport.renderEscapeHoverHtml(zwjHover);
        assertTrue(zwjHtml.contains(StvnStringEscapeHoverSupport.NON_PRINTABLE_INDICATOR));
        assertTrue(zwjHtml.contains("Composed Sequence Preview:"));
        assertTrue(zwjHtml.contains("\uD83D\uDC68\u200D\uD83D\uDCBB"));

        // Variation selector heart "\u2764\uFE0F"
        String heartVs16 = "\"\\u2764\\uFE0F\"";
        var vsHover = StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(heartVs16, 8).orElseThrow();
        assertEquals("U+FE0F", vsHover.scalarId());
        assertFalse(vsHover.printableScalarGlyph());
        assertEquals("\u2764\uFE0F", vsHover.composedClusterPreview());
        assertEquals("U+2764 + U+FE0F", vsHover.composedScalarChain());
    }

    @Test
    public void testHtmlSensitiveCharactersAndMalformedOrUnpairedEscapes() {
        String htmlChars = "\"\\u003C\\u003E\\u0026\\\"\"";
        var ltHover = StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(htmlChars, 2).orElseThrow();
        String ltHtml = StvnStringEscapeHoverSupport.renderEscapeHoverHtml(ltHover);
        assertTrue(ltHtml.contains("<code>&lt;</code>"));
        assertFalse(ltHtml.contains("<code><</code>"));

        // Adjacent \uD800\uDC00 is a valid supplementary pair (U+10000)
        var pair10000 = StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset("\"\\uD800\\uDC00\"", 2).orElseThrow();
        assertEquals("U+10000", pair10000.scalarId());

        // Malformed escapes and lone unpaired surrogates separated by '_' must return empty
        String invalid = "\"\\z_\\u12G4_\\uD800_\\uDC00\"";
        for (int off = 0; off < invalid.length(); off++) {
            assertTrue(StvnStringEscapeHoverSupport.resolveEscapeAtRelativeOffset(invalid, off).isEmpty(),
                "Invalid or unpaired escape at offset " + off + " must return empty");
        }
    }
}
