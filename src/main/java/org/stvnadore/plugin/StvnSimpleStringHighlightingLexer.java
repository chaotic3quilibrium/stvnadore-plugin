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
 * Enforces exact STVN 2.0.0 escape parity under Specification § 5.1.5 and
 * {@link org.stvnadore.core.StvnStringCharacterPolicy}: valid escapes are strictly {@code \"},
 * {@code \\}, {@code \/}, {@code \b}, {@code \f}, {@code \n}, {@code \r}, {@code \t}, four-digit
 * non-surrogate hexadecimal {@code \u005cuXXXX}, and paired UTF-16 surrogate escapes
 * {@code \u005cuD800}–{@code \u005cuDBFF\u005cuDC00}–{@code \u005cuDFFF}. Unpaired surrogate escapes
 * highlight as {@link StringEscapesTokenTypes#INVALID_UNICODE_ESCAPE_TOKEN}. Rule STR-04 fenced strings
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
                boolean allHex = true;
                int unit1 = 0;
                while (i < limit) {
                    char ch = buffer.charAt(i);
                    if (ch == '\\' || ch == '"' || ch == '\r' || ch == '\n' || ch == '\t') {
                        break;
                    }
                    int d = Character.digit(ch, 16);
                    if (d < 0) {
                        allHex = false;
                    } else {
                        unit1 = (unit1 << 4) | d;
                    }
                    i++;
                }
                boolean validFourHex = allHex && (i - (tokenStart + 2)) == 4;
                if (!validFourHex) {
                    tokenEnd = i;
                    tokenType = StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN;
                } else if (Character.isHighSurrogate((char) unit1)) {
                    if (hasPairedLowSurrogate(tokenStart + 6)) {
                        tokenEnd = tokenStart + 12;
                        tokenType = StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN;
                    } else {
                        tokenEnd = tokenStart + 6;
                        tokenType = StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN;
                    }
                } else if (Character.isLowSurrogate((char) unit1)) {
                    tokenEnd = tokenStart + 6;
                    tokenType = StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN;
                } else {
                    tokenEnd = tokenStart + 6;
                    tokenType = StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN;
                }
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

    private boolean hasPairedLowSurrogate(int secondEscapeStart) {
        if (secondEscapeStart + 6 > endOffset
                || buffer.charAt(secondEscapeStart) != '\\'
                || buffer.charAt(secondEscapeStart + 1) != 'u') {
            return false;
        }
        int unit2 = 0;
        for (int k = 0; k < 4; k++) {
            int d = Character.digit(buffer.charAt(secondEscapeStart + 2 + k), 16);
            if (d < 0) {
                return false;
            }
            unit2 = (unit2 << 4) | d;
        }
        return Character.isLowSurrogate((char) unit2);
    }
}
