package org.stvnadore.plugin;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import org.jspecify.annotations.NullMarked;

/**
 * Declares the custom text attributes keys used for STVN syntax highlighting.
 */
@NullMarked
public final class StvnSyntaxHighlighterColors {

    /** Text attributes key for primitive type keywords. */
    public static final TextAttributesKey STVN_PRIMITIVE_TYPE = TextAttributesKey.createTextAttributesKey(
            "STVN_PRIMITIVE_TYPE",
            DefaultLanguageHighlighterColors.KEYWORD
    );

    /** Text attributes key for nominal type identifiers. */
    public static final TextAttributesKey STVN_NOMINAL_TYPE = TextAttributesKey.createTextAttributesKey(
            "STVN_NOMINAL_TYPE",
            DefaultLanguageHighlighterColors.CLASS_NAME
    );

    /** Text attributes key for value keywords. */
    public static final TextAttributesKey STVN_VALUE_KEYWORD = TextAttributesKey.createTextAttributesKey(
            "STVN_VALUE_KEYWORD",
            DefaultLanguageHighlighterColors.CONSTANT
    );

    /** Text attributes key for metadata target annotations. */
    public static final TextAttributesKey STVN_METADATA_TARGET = TextAttributesKey.createTextAttributesKey(
            "STVN_METADATA_TARGET",
            DefaultLanguageHighlighterColors.METADATA
    );

    /** Text attributes key for valid escape sequences inside single-line STVN strings. */
    public static final TextAttributesKey VALID_STRING_ESCAPE = TextAttributesKey.createTextAttributesKey(
            "STVN_VALID_STRING_ESCAPE",
            DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE
    );

    /** Text attributes key for invalid escape sequences inside single-line STVN strings. */
    public static final TextAttributesKey INVALID_STRING_ESCAPE = TextAttributesKey.createTextAttributesKey(
            "STVN_INVALID_STRING_ESCAPE",
            DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE
    );

    private StvnSyntaxHighlighterColors() {
        // Prevent instantiation
    }
}