package org.stvnadore.plugin;

import com.intellij.lexer.FlexAdapter;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.parser._StvnLexer;
import org.stvnadore.psi.StvnTypes;

/**
 * Handles basic lexical highlighting of standard language syntax constructs.
 */
@NullMarked
public final class StvnSyntaxHighlighter extends SyntaxHighlighterBase {

    private static final TextAttributesKey[] COMMENT_KEYS = new TextAttributesKey[]{DefaultLanguageHighlighterColors.LINE_COMMENT};
    private static final TextAttributesKey[] STRING_KEYS = new TextAttributesKey[]{DefaultLanguageHighlighterColors.STRING};
    private static final TextAttributesKey[] NUMBER_KEYS = new TextAttributesKey[]{DefaultLanguageHighlighterColors.NUMBER};
    private static final TextAttributesKey[] BRACKET_KEYS = new TextAttributesKey[]{DefaultLanguageHighlighterColors.BRACKETS};
    private static final TextAttributesKey[] BRACE_KEYS = new TextAttributesKey[]{DefaultLanguageHighlighterColors.BRACES};
    private static final TextAttributesKey[] PARENTHESE_KEYS = new TextAttributesKey[]{DefaultLanguageHighlighterColors.PARENTHESES};
    private static final TextAttributesKey[] COLON_KEYS = new TextAttributesKey[]{DefaultLanguageHighlighterColors.OPERATION_SIGN};
    private static final TextAttributesKey[] PRIMITIVE_TYPE_KEYS = new TextAttributesKey[]{StvnSyntaxHighlighterColors.STVN_PRIMITIVE_TYPE};
    private static final TextAttributesKey[] VALUE_KEYWORD_KEYS = new TextAttributesKey[]{StvnSyntaxHighlighterColors.STVN_VALUE_KEYWORD};
    private static final TextAttributesKey[] METADATA_KEYS = new TextAttributesKey[]{StvnSyntaxHighlighterColors.STVN_METADATA_TARGET};
    private static final TextAttributesKey[] NOMINAL_TYPE_KEYS = new TextAttributesKey[]{StvnSyntaxHighlighterColors.STVN_NOMINAL_TYPE};
    private static final TextAttributesKey[] EMPTY_KEYS = new TextAttributesKey[0];

    /** Constructs an StvnSyntaxHighlighter instance. */
    public StvnSyntaxHighlighter() {}

    @Override
    public Lexer getHighlightingLexer() {
        return new FlexAdapter(new _StvnLexer(null));
    }

    @Override
    public TextAttributesKey[] getTokenHighlights(IElementType tokenType) {
        if (tokenType.equals(StvnTypes.COMMENT)) {
            return COMMENT_KEYS;
        } else if (tokenType.equals(StvnTypes.LITERAL_STRING_SIMPLE) ||
                   tokenType.equals(StvnTypes.LITERAL_STRING_BLOCK) ||
                   tokenType.equals(StvnTypes.LITERAL_STRING_FENCED)) {
            return STRING_KEYS;
        } else if (tokenType.equals(StvnTypes.LITERAL_INTEGER) ||
                   tokenType.equals(StvnTypes.LITERAL_FLOAT)) {
            return NUMBER_KEYS;
        } else if (tokenType.equals(StvnTypes.LBRACK) ||
                   tokenType.equals(StvnTypes.RBRACK)) {
            return BRACKET_KEYS;
        } else if (tokenType.equals(StvnTypes.LBRACE) ||
                   tokenType.equals(StvnTypes.RBRACE)) {
            return BRACE_KEYS;
        } else if (tokenType.equals(StvnTypes.LPAREN) ||
                   tokenType.equals(StvnTypes.RPAREN)) {
            return PARENTHESE_KEYS;
        } else if (tokenType.equals(StvnTypes.COLON)) {
            return COLON_KEYS;
        } else if (isPrimitiveTokenType(tokenType)) {
            return PRIMITIVE_TYPE_KEYS;
        } else if (isValueTokenType(tokenType)) {
            return VALUE_KEYWORD_KEYS;
        } else if (isMetadataTokenType(tokenType)) {
            return METADATA_KEYS;
        } else if (tokenType.equals(StvnTypes.TYPE_KEYWORD_BASE)) {
            return NOMINAL_TYPE_KEYS;
        }
        return EMPTY_KEYS;
    }

    private static boolean isPrimitiveTokenType(IElementType type) {
        return type.equals(StvnTypes.ATOM_BOOLEAN) ||
               type.equals(StvnTypes.ATOM_DATE_TIME_OFFSET) ||
               type.equals(StvnTypes.ATOM_DATE_TIME_ZONED) ||
               type.equals(StvnTypes.ATOM_DATE_TIME_AUDITED) ||
               type.equals(StvnTypes.ATOM_FLOAT) ||
               type.equals(StvnTypes.ATOM_FLOAT_EXACT) ||
               type.equals(StvnTypes.ATOM_INT) ||
               type.equals(StvnTypes.ATOM_STRING) ||
               type.equals(StvnTypes.ATOM_STRING_FIXED) ||
               type.equals(StvnTypes.ATOM_STRING_NON_EMPTY) ||
               type.equals(StvnTypes.ATOM_TIME_EPOCH_MS) ||
               type.equals(StvnTypes.ATOM_TIME_EPOCH_NS) ||
               type.equals(StvnTypes.ATOM_TIME_EPOCH_S) ||
               type.equals(StvnTypes.ATOM_UINT) ||
               type.equals(StvnTypes.COLL_MAP) ||
               type.equals(StvnTypes.COLL_MAP_INV) ||
               type.equals(StvnTypes.COLL_MAP_INV_NON_EMPTY) ||
               type.equals(StvnTypes.COLL_MAP_NON_EMPTY) ||
               type.equals(StvnTypes.COLL_SEQ) ||
               type.equals(StvnTypes.COLL_SEQ_NON_EMPTY) ||
               type.equals(StvnTypes.COLL_SET) ||
               type.equals(StvnTypes.COLL_SET_NON_EMPTY) ||
               type.equals(StvnTypes.KW_TUPLE) ||
               type.equals(StvnTypes.KW_MAP_ENTRY) ||
               type.equals(StvnTypes.KW_ENUM) ||
               type.equals(StvnTypes.KW_OPTION) ||
               type.equals(StvnTypes.KW_EITHER) ||
               type.equals(StvnTypes.KW_UNION) ||
               type.equals(StvnTypes.KW_DEFS) ||
               type.equals(StvnTypes.KW_TYPE) ||
               type.equals(StvnTypes.KW_BODY) ||
               type.equals(StvnTypes.KW_INCLUDE);
    }

    private static boolean isValueTokenType(IElementType type) {
        return type.equals(StvnTypes.KW_TRUE) ||
               type.equals(StvnTypes.KW_FALSE) ||
               type.equals(StvnTypes.KW_TRUE_SHORT) ||
               type.equals(StvnTypes.KW_FALSE_SHORT) ||
               type.equals(StvnTypes.KW_SOME) ||
               type.equals(StvnTypes.KW_SOME_SHORT) ||
               type.equals(StvnTypes.KW_NONE) ||
               type.equals(StvnTypes.KW_NONE_SHORT) ||
               type.equals(StvnTypes.KW_LEFT) ||
               type.equals(StvnTypes.KW_LEFT_SHORT) ||
               type.equals(StvnTypes.KW_RIGHT) ||
               type.equals(StvnTypes.KW_RIGHT_SHORT) ||
               type.equals(StvnTypes.UNION_TAG_PREFIX) ||
               type.equals(StvnTypes.VALUE_KEYWORD_BASE);
    }

    private static boolean isMetadataTokenType(IElementType type) {
        return type.equals(StvnTypes.KW_EQUATABLE) ||
               type.equals(StvnTypes.KW_COMPARABLE) ||
               type.equals(StvnTypes.KW_PRESERVE_INDENT) ||
               type.equals(StvnTypes.KW_MIN_INCL) ||
               type.equals(StvnTypes.KW_MIN_EXCL) ||
               type.equals(StvnTypes.KW_MAX_INCL) ||
               type.equals(StvnTypes.KW_MAX_EXCL) ||
               type.equals(StvnTypes.KW_REGEX);
    }
}