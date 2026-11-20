package org.stvnadore.plugin;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.psi.StringEscapesTokenTypes;
import com.intellij.psi.tree.IElementType;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.StvnTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Platform test suite verifying {@link StvnSimpleStringHighlightingLexer} and {@link StvnSyntaxHighlighter}
 * layered escape highlighting on single-line STVN strings ({@link StvnTypes#LITERAL_STRING_SIMPLE}) while
 * preserving single-token PSI parsing and verbatim fenced string highlighting.
 */
@NullMarked
public final class StvnSimpleStringHighlightingLexerTest extends BasePlatformTestCase {

    private record TokenSlice(IElementType type, String text) {}

    private static List<TokenSlice> tokenize(Lexer lexer, String source) {
        var out = new ArrayList<TokenSlice>();
        lexer.start(source);
        while (lexer.getTokenType() != null) {
            out.add(new TokenSlice(
                lexer.getTokenType(),
                source.substring(lexer.getTokenStart(), lexer.getTokenEnd())
            ));
            lexer.advance();
        }
        return out;
    }

    public void testValidSimpleStringEscapesEmitAlternatingStringAndValidEscapeTokens() {
        var highlighter = new StvnSyntaxHighlighter();
        var lexer = highlighter.getHighlightingLexer();
        String source = "\"line_1\\nline_2\\t\\\"quoted\\\"\\\\done\\/slash\\b\\f\\r\\u0041\"";

        var tokens = tokenize(lexer, source);
        var expected = List.of(
            new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\"line_1"),
            new TokenSlice(StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN, "\\n"),
            new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "line_2"),
            new TokenSlice(StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN, "\\t"),
            new TokenSlice(StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN, "\\\""),
            new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "quoted"),
            new TokenSlice(StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN, "\\\""),
            new TokenSlice(StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN, "\\\\"),
            new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "done"),
            new TokenSlice(StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN, "\\/"),
            new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "slash"),
            new TokenSlice(StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN, "\\b"),
            new TokenSlice(StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN, "\\f"),
            new TokenSlice(StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN, "\\r"),
            new TokenSlice(StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN, "\\u0041"),
            new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\"")
        );
        assertEquals("Expected exact alternating string and valid escape slices across all 9 STVN escapes",
            expected, tokens);

        var validKeys = highlighter.getTokenHighlights(StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN);
        assertEquals(1, validKeys.length);
        assertEquals(StvnSyntaxHighlighterColors.VALID_STRING_ESCAPE, validKeys[0]);

        var strKeys = highlighter.getTokenHighlights(StvnTypes.LITERAL_STRING_SIMPLE);
        assertEquals(1, strKeys.length);
        assertEquals(DefaultLanguageHighlighterColors.STRING, strKeys[0]);
    }

    public void testInvalidSimpleStringEscapesEmitInvalidEscapeTokensWithoutException() {
        var highlighter = new StvnSyntaxHighlighter();
        var lexer = highlighter.getHighlightingLexer();
        String source = "\"\\'\\x41\\0\\a\\u12\\u123G\\uu0041\"";

        var tokens = tokenize(lexer, source);
        var expected = List.of(
            new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\""),
            new TokenSlice(StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN, "\\'"),
            new TokenSlice(StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN, "\\x"),
            new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "41"),
            new TokenSlice(StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN, "\\0"),
            new TokenSlice(StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN, "\\a"),
            new TokenSlice(StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN, "\\u12"),
            new TokenSlice(StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN, "\\u123G"),
            new TokenSlice(StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN, "\\uu004"),
            new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "1\"")
        );
        assertEquals("Expected exact invalid character/unicode escape token classification", expected, tokens);

        // Unclosed trailing backslash directly fed to StvnSimpleStringHighlightingLexer
        var subLexer = new StvnSimpleStringHighlightingLexer();
        var unclosedTokens = tokenize(subLexer, "\"hello\\");
        assertEquals(
            List.of(
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\"hello"),
                new TokenSlice(StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN, "\\")
            ),
            unclosedTokens
        );

        var invalidCharKeys = highlighter.getTokenHighlights(StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN);
        var invalidUniKeys = highlighter.getTokenHighlights(StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN);
        assertEquals(StvnSyntaxHighlighterColors.INVALID_STRING_ESCAPE, invalidCharKeys[0]);
        assertEquals(StvnSyntaxHighlighterColors.INVALID_STRING_ESCAPE, invalidUniKeys[0]);
    }

    public void testParserDefinitionLexerPreservesSingleLiteralStringSimpleToken() {
        var parserLexer = new StvnParserDefinition().createLexer(getProject());
        String source = "\"line_1\\nline_2\\t\\\"quoted\\\"\\u0041\"";

        var tokens = tokenize(parserLexer, source);
        assertEquals("Parser lexer must emit a single unsplit LITERAL_STRING_SIMPLE token", 1, tokens.size());
        assertEquals(StvnTypes.LITERAL_STRING_SIMPLE, tokens.get(0).type());
        assertEquals(source, tokens.get(0).text());
    }

    public void testVerbatimFencedAndMalformedFencedStringsRemainSingleUnsplitTokens() {
        var highlighter = new StvnSyntaxHighlighter();
        var lexer = highlighter.getHighlightingLexer();

        String fenced = "\"\"\"[RAW]\nline_1\\n\\t\\\"verbatim\\\"\\\\done\\u0041\n[RAW]\"\"\"";
        var fencedTokens = tokenize(lexer, fenced);
        assertEquals("Verbatim fenced string must remain a single unsplit token", 1, fencedTokens.size());
        assertEquals(StvnTypes.LITERAL_STRING_FENCED, fencedTokens.get(0).type());
        assertEquals(fenced, fencedTokens.get(0).text());

        String malformedFence = "\"\"\"->[RAW]\nline_1\\n\\t\n[RAW]\"\"\"";
        var malformedTokens = tokenize(lexer, malformedFence);
        assertEquals("Malformed fenced string must remain a single unsplit token", 1, malformedTokens.size());
        assertEquals(StvnTypes.MALFORMED_FENCE, malformedTokens.get(0).type());
        assertEquals(malformedFence, malformedTokens.get(0).text());
    }

    public void testSection531DelimiterBoundedUnicodeAndMultiEscapeSpanParity() {
        var highlighter = new StvnSyntaxHighlighter();
        var lexer = highlighter.getHighlightingLexer();

        // 1. Gauntlet v9 Slot 5: "CORRUPT_ESCAPE_\\z_AND_HEX_\\u12G4"
        assertEquals(
            List.of(
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\"CORRUPT_ESCAPE_"),
                new TokenSlice(StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN, "\\z"),
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "_AND_HEX_"),
                new TokenSlice(StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN, "\\u12G4"),
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\"")
            ),
            tokenizeStrictMonotonic(lexer, "\"CORRUPT_ESCAPE_\\z_AND_HEX_\\u12G4\"")
        );

        // 2. "\\u12G4" -> 6-char INVALID_UNICODE_ESCAPE_TOKEN
        assertEquals(
            List.of(
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\""),
                new TokenSlice(StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN, "\\u12G4"),
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\"")
            ),
            tokenizeStrictMonotonic(lexer, "\"\\u12G4\"")
        );

        // 3. "\\uG123" -> 6-char INVALID_UNICODE_ESCAPE_TOKEN
        assertEquals(
            List.of(
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\""),
                new TokenSlice(StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN, "\\uG123"),
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\"")
            ),
            tokenizeStrictMonotonic(lexer, "\"\\uG123\"")
        );

        // 4. "END_\\u12" -> 4-char INVALID_UNICODE_ESCAPE_TOKEN stopping strictly before closing '"'
        assertEquals(
            List.of(
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\"END_"),
                new TokenSlice(StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN, "\\u12"),
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\"")
            ),
            tokenizeStrictMonotonic(lexer, "\"END_\\u12\"")
        );

        // 5. "\\u12\\z" -> 4-char INVALID_UNICODE_ESCAPE_TOKEN + 2-char INVALID_CHARACTER_ESCAPE_TOKEN
        assertEquals(
            List.of(
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\""),
                new TokenSlice(StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN, "\\u12"),
                new TokenSlice(StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN, "\\z"),
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\"")
            ),
            tokenizeStrictMonotonic(lexer, "\"\\u12\\z\"")
        );

        // 6. "QUOTE_\\u12\"_NEXT_\\q" -> 4-char \\u12, 2-char valid \", _NEXT_, 2-char \\q
        assertEquals(
            List.of(
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\"QUOTE_"),
                new TokenSlice(StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN, "\\u12"),
                new TokenSlice(StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN, "\\\""),
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "_NEXT_"),
                new TokenSlice(StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN, "\\q"),
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\"")
            ),
            tokenizeStrictMonotonic(lexer, "\"QUOTE_\\u12\\\"_NEXT_\\q\"")
        );

        // 7. Backslash + raw horizontal tab (0x09) and \\u + raw horizontal tab (0x09)
        assertEquals(
            List.of(
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\"a"),
                new TokenSlice(StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN, "\\\t"),
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "b\"")
            ),
            tokenizeStrictMonotonic(lexer, "\"a\\\tb\"")
        );
        assertEquals(
            List.of(
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\""),
                new TokenSlice(StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN, "\\u12"),
                new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "\tb\"")
            ),
            tokenizeStrictMonotonic(lexer, "\"\\u12\tb\"")
        );

        // 8. Edge sub-lexer progress vectors (never throws IOOBE or stalls)
        var subLexer = new StvnSimpleStringHighlightingLexer();
        String[] edgeSlices = {
            "\"\\u\"",
            "\"\\u\\z\"",
            "\"\\u\t\"",
            "\"abc\\\\\"",
            "\"abc\\",
            "\"\\u12",
            "\\"
        };
        for (String slice : edgeSlices) {
            var slices = tokenizeStrictMonotonic(subLexer, slice);
            assertFalse("Sub-lexer must emit at least one token for non-empty input: " + slice, slices.isEmpty());
        }
    }

    private static List<TokenSlice> tokenizeStrictMonotonic(Lexer lexer, String source) {
        var out = new ArrayList<TokenSlice>();
        lexer.start(source);
        int expectedStart = 0;
        while (lexer.getTokenType() != null) {
            int start = lexer.getTokenStart();
            int end = lexer.getTokenEnd();
            assertEquals("Token start must equal previous token end", expectedStart, start);
            assertTrue("Lexer advance() must strictly advance tokenEnd > tokenStart on " + source, end > start);
            assertTrue("Token end must not exceed source length", end <= source.length());
            out.add(new TokenSlice(lexer.getTokenType(), source.substring(start, end)));
            expectedStart = end;
            lexer.advance();
        }
        assertEquals("Lexer must consume full source length", source.length(), expectedStart);
        return out;
    }
}
