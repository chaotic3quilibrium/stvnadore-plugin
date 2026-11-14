package org.stvnadore.plugin;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.StringEscapesTokenTypes;
import com.intellij.psi.tree.IElementType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.psi.StvnTypes;

/**
 * Highlighting-only sub-lexer for single-line STVN strings ({@link StvnTypes#LITERAL_STRING_SIMPLE}).
 * <p>
 * Enforces exact STVN 2.0.0 escape parity with {@code StvnLexer.g4} and {@code StvnPayloadLowerer}:
 * valid escapes are strictly {@code \"}, {@code \\}, {@code \/}, {@code \b}, {@code \f}, {@code \n},
 * {@code \r}, {@code \t}, and four-digit hexadecimal {@code \u005cuXXXX}. Rule STR-04 fenced strings
 * ({@link StvnTypes#LITERAL_STRING_FENCED} and {@link StvnTypes#MALFORMED_FENCE}) are never routed here.
 */
@NullMarked
public final class StvnSimpleStringHighlightingLexer extends LexerBase {

    private CharSequence buffer = "";
    private int endOffset;
    private int tokenStart;
    private int tokenEnd;
    private @Nullable IElementType tokenType;

    /** Constructs a new {@code StvnSimpleStringHighlightingLexer}. */
    public StvnSimpleStringHighlightingLexer() {}

    @Override
    public void start(CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.endOffset = endOffset;
        this.tokenStart = startOffset;
        this.tokenEnd = startOffset;
        advance();
    }

    @Override
    public int getState() {
        return 0;
    }

    @Override
    public @Nullable IElementType getTokenType() {
        return tokenType;
    }

    @Override
    public int getTokenStart() {
        return tokenStart;
    }

    @Override
    public int getTokenEnd() {
        return tokenEnd;
    }

    @Override
    public void advance() {
        tokenStart = tokenEnd;
        if (tokenStart >= endOffset) {
            tokenType = null;
            return;
        }

        if (buffer.charAt(tokenStart) != '\\') {
            int i = tokenStart + 1;
            while (i < endOffset && buffer.charAt(i) != '\\') {
                i++;
            }
            tokenEnd = i;
            tokenType = StvnTypes.LITERAL_STRING_SIMPLE;
            return;
        }

        // Backslash escape start
        if (tokenStart + 1 >= endOffset) {
            tokenEnd = endOffset;
            tokenType = StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN;
            return;
        }

        char esc = buffer.charAt(tokenStart + 1);
        switch (esc) {
            case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {
                tokenEnd = tokenStart + 2;
                tokenType = StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN;
            }
            case 'u' -> {
                int i = tokenStart + 2;
                int limit = Math.min(tokenStart + 6, endOffset);
                boolean allHex = (limit - (tokenStart + 2)) == 4;
                while (i < limit) {
                    char ch = buffer.charAt(i);
                    if (!isHexDigit(ch)) {
                        allHex = false;
                        break;
                    }
                    i++;
                }
                tokenEnd = i;
                tokenType = allHex
                    ? StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN
                    : StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN;
            }
            default -> {
                tokenEnd = tokenStart + 2;
                tokenType = StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN;
            }
        }
    }

    @Override
    public CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return endOffset;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9')
            || (c >= 'a' && c <= 'f')
            || (c >= 'A' && c <= 'F');
    }
}
