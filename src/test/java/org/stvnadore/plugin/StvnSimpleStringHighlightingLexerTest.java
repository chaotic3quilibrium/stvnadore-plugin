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
            new TokenSlice(StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN, "\\u123"),
            new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "G"),
            new TokenSlice(StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN, "\\u"),
            new TokenSlice(StvnTypes.LITERAL_STRING_SIMPLE, "u0041\"")
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
}
