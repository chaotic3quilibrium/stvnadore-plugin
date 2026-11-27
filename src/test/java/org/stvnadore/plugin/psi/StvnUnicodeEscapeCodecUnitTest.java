package org.stvnadore.plugin.psi;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure headless JUnit 5 unit tests for {@link StvnUnicodeEscapeCodec} and
 * {@code StvnBundle.properties} {@code \u005CuXXXX} escape loading.
 */
@NullMarked
class StvnUnicodeEscapeCodecUnitTest {

    @Test
    @DisplayName("BMP non-ASCII characters escape to uppercase \\uXXXX while preserving existing escapes verbatim")
    void testBmpEscapePreservesExistingEscapesAndEscapedBackslashes() {
        assertEquals(
            Optional.of("\"Caf\\u00E9 \\u2014 [0, \\u221E)\\n\\\"quoted\\\"\\t\\\\u00E9\""),
            StvnUnicodeEscapeCodec.escapeNonAsciiInSimpleStringLiteral(
                "\"Café — [0, ∞)\\n\\\"quoted\\\"\\t\\\\u00E9\""
            )
        );
        assertFalse(StvnUnicodeEscapeCodec.hasEscapableRawNonAscii("\"pure_ascii\\n\\u00E9\""));
        assertTrue(StvnUnicodeEscapeCodec.hasEscapableRawNonAscii("\"Café\""));
    }

    @Test
    @DisplayName("Emoji surrogate pairs, ZWJ clusters, and variation selectors form a strict 1:1 involution")
    void testEmojiSurrogatePairAndZwjInvolution() {
        String emojisA = "\"\\uD83D\\uDE80 by \\uD83D\\uDC68\\u200D\\uD83D\\uDCBB and \\u2764\\uFE0F\\nline2\"";
        String emojisC = "\"🚀 by 👨‍💻 and ❤️\\nline2\"";

        Optional<String> unescaped = StvnUnicodeEscapeCodec.unescapeUnicodeInSimpleStringLiteral(emojisA);
        assertEquals(Optional.of(emojisC), unescaped);

        Optional<String> reEscaped = StvnUnicodeEscapeCodec.escapeNonAsciiInSimpleStringLiteral(emojisC);
        assertEquals(Optional.of(emojisA), reEscaped);

        // Strict 1:1 involution in both directions
        assertEquals(
            emojisA,
            StvnUnicodeEscapeCodec.escapeNonAsciiInSimpleStringLiteral(unescaped.orElseThrow()).orElseThrow()
        );
        assertEquals(
            emojisC,
            StvnUnicodeEscapeCodec.unescapeUnicodeInSimpleStringLiteral(reEscaped.orElseThrow()).orElseThrow()
        );

        // Verify both forms compile with 0 diagnostics in StvnCompiler
        String docTemplate = "{\n  :type :String\n  :body %s\n}\n";
        assertFalse(StvnCompiler.compileToResult(docTemplate.formatted(emojisA), "a.stvn").hasErrors());
        assertFalse(StvnCompiler.compileToResult(docTemplate.formatted(emojisC), "c.stvn").hasErrors());
    }

    @Test
    @DisplayName("Lexical safety guard never unescapes lone surrogates, controls, line breaks, quotes, backslashes, or forbidden raw scalars")
    void testLexicalSafetyGuardsKeepUnsafeEscapesUntouched() {
        String unsafeOnly =
            "\"\\uDFFF\\uD800\\uD83Dplain\\u0000\\u0009\\u000A\\u000D\\u001F\\u0022\\u005C\\u007F\\u0085\\u009F\\u2028\\u2029\\u200B\\u202E\\uFEFF\"";
        assertFalse(StvnUnicodeEscapeCodec.hasSafeUnescapableUnicode(unsafeOnly));
        assertEquals(
            Optional.of(unsafeOnly),
            StvnUnicodeEscapeCodec.unescapeUnicodeInSimpleStringLiteral(unsafeOnly)
        );

        // Mixed safe + unsafe escapes: only safe scalars materialize to UTF-8
        String mixed = "\"\\u000D\\uD83D\\uDE80\\u0022\\u005C\\u202E\\u00E9\"";
        String expectedMixed = "\"\\u000D🚀\\u0022\\u005C\\u202Eé\"";
        assertTrue(StvnUnicodeEscapeCodec.hasSafeUnescapableUnicode(mixed));
        assertEquals(
            Optional.of(expectedMixed),
            StvnUnicodeEscapeCodec.unescapeUnicodeInSimpleStringLiteral(mixed)
        );
        String mixedDoc = "{\n  :type :String\n  :body " + expectedMixed + "\n}\n";
        assertFalse(StvnCompiler.compileToResult(mixedDoc, "mixed.stvn").hasErrors());

        // Escaped literal backslash before uXXXX ("\\u00E9") must never be misread as a Unicode escape
        String literalBackslash = "\"\\\\u00E9\"";
        assertFalse(StvnUnicodeEscapeCodec.hasSafeUnescapableUnicode(literalBackslash));
        assertEquals(
            Optional.of(literalBackslash),
            StvnUnicodeEscapeCodec.unescapeUnicodeInSimpleStringLiteral(literalBackslash)
        );
    }

    @Test
    @DisplayName("Malformed strings, fenced strings, and raw unpaired surrogates fail closed")
    void testMalformedAndFencedInputsFailClosed() {
        assertEquals(Optional.empty(), StvnUnicodeEscapeCodec.escapeNonAsciiInSimpleStringLiteral("\"\"\"[RAW]\n🚀[RAW]\"\"\""));
        assertEquals(Optional.empty(), StvnUnicodeEscapeCodec.unescapeUnicodeInSimpleStringLiteral("\"\"\"[RAW]\n\\u00E9[RAW]\"\"\""));
        assertEquals(Optional.empty(), StvnUnicodeEscapeCodec.escapeNonAsciiInSimpleStringLiteral("\"unterminated"));
        assertEquals(Optional.empty(), StvnUnicodeEscapeCodec.unescapeUnicodeInSimpleStringLiteral("\"bad\\u12G4\""));
        assertEquals(Optional.empty(), StvnUnicodeEscapeCodec.escapeNonAsciiInSimpleStringLiteral("\"\uD83D\""));
    }

    @Test
    @DisplayName("StvnBundle.properties loads \\\\uXXXX keys cleanly with zero CR (0x0D) bytes")
    void testStvnBundlePropertiesLoadsLiteralBackslashUXxxxAndPureLf() throws IOException {
        try (InputStream rawIn = getClass().getResourceAsStream("/messages/StvnBundle.properties")) {
            assertNotNull(rawIn, "StvnBundle.properties must exist on classpath");
            byte[] bytes = rawIn.readAllBytes();
            for (byte b : bytes) {
                assertFalse(b == (byte) '\r', "CR (0x0D) byte is prohibited in StvnBundle.properties");
            }
        }
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/messages/StvnBundle.properties")) {
            assertNotNull(in);
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertEquals("Escape non-ASCII characters as \\uXXXX", props.getProperty("stvn.intention.escape.non.ascii.text"));
        assertEquals("Unescape \\uXXXX sequences to UTF-8", props.getProperty("stvn.intention.unescape.unicode.text"));
        assertEquals(
            "Escape non-ASCII characters as \\uXXXX when converting to simple strings",
            props.getProperty("stvn.settings.escape.non.ascii.simple.strings")
        );
    }
}
