package org.stvnadore.plugin.psi;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless unit tests verifying {@link StvnStringLiteralCodec} (backing {@link StvnPsiLiteralEvaluator})
 * 3-case fenced line-ending normalization, paired UTF-16 surrogate USV decoding, raw forbidden
 * character rejection, {@code escapeSimpleStringBody} delegation, and fenced representability gating.
 */
@NullMarked
public final class StvnPsiLiteralEvaluatorHygieneUnitTest {

    @Test
    public void testThreeCaseFencedOpeningAndBodyNormalization() {
        String lfFence = "\"\"\"[RAW]\n  line1\n  line2\n[RAW]\"\"\"";
        String crlfFence = "\"\"\"[RAW]\r\n  line1\r\n  line2\r\n[RAW]\"\"\"";
        String crFence = "\"\"\"[RAW]\r  line1\r  line2\r[RAW]\"\"\"";

        assertEquals("line1\nline2\n", StvnStringLiteralCodec.decodeStringLiteral(lfFence, false).orElseThrow());
        assertEquals("line1\nline2\n", StvnStringLiteralCodec.decodeStringLiteral(crlfFence, false).orElseThrow());
        assertEquals("line1\nline2\n", StvnStringLiteralCodec.decodeStringLiteral(crFence, false).orElseThrow());
        assertEquals("  line1\n  line2\n", StvnStringLiteralCodec.decodeStringLiteral(crFence, true).orElseThrow());
    }

    @Test
    public void testPairedSurrogateDecodeAndUnpairedOrRawForbiddenRejection() {
        assertEquals(
            "\uD83D\uDE80\n\uD83D\uDC68\u200D\uD83D\uDCBB",
            StvnStringLiteralCodec.decodeStringLiteral(
                "\"\\uD83D\\uDE80\\n\\uD83D\\uDC68\\u200D\\uD83D\\uDCBB\"", false
            ).orElseThrow()
        );

        // Unpaired high or low surrogate escapes must return empty
        assertTrue(StvnStringLiteralCodec.decodeStringLiteral("\"\\uD800\"", false).isEmpty());
        assertTrue(StvnStringLiteralCodec.decodeStringLiteral("\"\\uDC00\"", false).isEmpty());
        assertTrue(StvnStringLiteralCodec.decodeStringLiteral("\"\\uD83D\\u0041\"", false).isEmpty());
        assertTrue(StvnStringLiteralCodec.decodeStringLiteral("\"\\uD83D\"", false).isEmpty());

        // Raw forbidden characters (DEL, C1, Trojan Source bidi, ZWSP, BOM) must return empty
        assertTrue(StvnStringLiteralCodec.decodeStringLiteral("\"\u007F\"", false).isEmpty());
        assertTrue(StvnStringLiteralCodec.decodeStringLiteral("\"\u0085\"", false).isEmpty());
        assertTrue(StvnStringLiteralCodec.decodeStringLiteral("\"\u202E\"", false).isEmpty());
        assertTrue(StvnStringLiteralCodec.decodeStringLiteral("\"\u200B\"", false).isEmpty());
        assertTrue(StvnStringLiteralCodec.decodeStringLiteral("\"\uFEFF\"", false).isEmpty());
        assertTrue(StvnStringLiteralCodec.decodeStringLiteral("\"\"\"[RAW]\n\u202E\n[RAW]\"\"\"", false).isEmpty());
    }

    @Test
    public void testFormatCanonicalQuotedStringAndShouldEmitFencedPretty() {
        assertEquals(
            "\"line\\r\\n\\u202e\\u007f\\ufeff\uD83D\uDE80\"",
            StvnStringLiteralCodec.formatCanonicalQuotedString("line\r\n\u202E\u007F\uFEFF\uD83D\uDE80")
        );

        assertTrue(StvnStringLiteralCodec.shouldEmitFencedPretty("hello\n\uD83D\uDE80", false));
        assertFalse(StvnStringLiteralCodec.shouldEmitFencedPretty("hello\r\nworld", false));
        assertFalse(StvnStringLiteralCodec.shouldEmitFencedPretty("hello\n\u202Eworld", true));
        assertFalse(StvnStringLiteralCodec.shouldEmitFencedPretty("hello\n\u200Bworld", true));
        assertFalse(StvnStringLiteralCodec.shouldEmitFencedPretty("  indented\n  text", false));
        assertTrue(StvnStringLiteralCodec.shouldEmitFencedPretty("  indented\n  text", true));
    }
}
