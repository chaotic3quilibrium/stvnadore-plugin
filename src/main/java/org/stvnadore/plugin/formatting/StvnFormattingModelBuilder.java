package org.stvnadore.plugin.formatting;

import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.FormattingModel;
import com.intellij.formatting.FormattingModelBuilder;
import com.intellij.formatting.FormattingModelProvider;
import com.intellij.formatting.Indent;
import com.intellij.formatting.SpacingBuilder;
import com.intellij.formatting.Wrap;
import com.intellij.formatting.WrapType;
import com.intellij.formatting.Alignment;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.StvnParserDefinition;
import org.stvnadore.psi.StvnTypes;

/**
 * Native formatting model builder for Strongly Typed Value Notation (STVN) documents.
 * <p>
 * This builder constructs spacing rules for STVN structural delimiters, section headers,
 * and punctuation, enforcing the canonical 2-space indentation hierarchy and Zero-Tab Invariant.
 * </p>
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnFormattingModelBuilder implements FormattingModelBuilder {

    /**
     * Default constructor for the STVN formatting model builder.
     */
    public StvnFormattingModelBuilder() {
        super();
    }

    @Override
    public @NotNull FormattingModel createModel(@NotNull FormattingContext formattingContext) {
        CodeStyleSettings settings = formattingContext.getCodeStyleSettings();
        PsiFile psiFile = formattingContext.getContainingFile();
        ASTNode rootNode = psiFile.getNode();

        SpacingBuilder spacingBuilder = createSpacingBuilder(settings);
        StvnBlock rootBlock = new StvnBlock(
                rootNode,
                Wrap.createWrap(WrapType.NONE, false),
                Alignment.createAlignment(),
                spacingBuilder,
                settings,
                Indent.getNoneIndent()
        );

        return FormattingModelProvider.createFormattingModelForPsiFile(psiFile, rootBlock, settings);
    }

    /**
     * Creates and configures the canonical STVN spacing builder.
     *
     * @param settings the project code style settings
     * @return the configured spacing builder
     */
    public static SpacingBuilder createSpacingBuilder(CodeStyleSettings settings) {
        return new SpacingBuilder(settings, StvnLanguage.INSTANCE)
                // Section headers: single space before opening brace/bracket/value
                .after(StvnTypes.KW_META).spaces(1)
                .after(StvnTypes.KW_DEFS).spaces(1)
                .after(StvnTypes.KW_TYPE).spaces(1)
                .after(StvnTypes.KW_BODY).spaces(1)
                .after(StvnTypes.KW_PACKAGE).spaces(1)
                .between(StvnTypes.PACKAGE_PATH, StvnTypes.LBRACE).spaces(1)
                .after(StvnTypes.KW_USE).spaces(1)
                .after(StvnTypes.KW_INCLUDE).spaces(1)
                .after(StvnTypes.KW_META_NAME).spaces(1)
                .after(StvnTypes.KW_META_DOMAIN).spaces(1)
                .after(StvnTypes.KW_META_KIND).spaces(1)

                // Type definition and constant spacing
                .between(StvnTypes.TYPE_DEF_TARGET, StvnTypes.SCHEMA_TYPE).spaces(1)
                .between(StvnTypes.TYPE_DEF_TARGET, StvnTypes.METADATA_MAP).spaces(1)
                .between(StvnTypes.METADATA_MAP, StvnTypes.SCHEMA_TYPE).spaces(1)
                .between(StvnTypes.METADATA_MAP, StvnTypes.SCHEMA_CONSTRUCTOR).spaces(1)
                .between(StvnTypes.METADATA_MAP, StvnTypes.TYPE_KEYWORD).spaces(1)
                .between(StvnTypes.VALUE_KEYWORD, StvnTypes.SCHEMA_TYPE).spaces(1)
                .between(StvnTypes.VALUE_KEYWORD, StvnTypes.METADATA_MAP).spaces(1)
                .between(StvnTypes.SCHEMA_TYPE, StvnTypes.VALUE).spaces(1)

                // Metadata map internal spacing: { #unsigned #size 16 } or empty {}
                .betweenInside(StvnTypes.LBRACE, StvnTypes.RBRACE, StvnTypes.METADATA_MAP).spaces(0)
                .afterInside(StvnTypes.LBRACE, StvnTypes.METADATA_MAP).spacing(1, 1, 0, true, 1)
                .beforeInside(StvnTypes.RBRACE, StvnTypes.METADATA_MAP).spacing(1, 1, 0, true, 1)
                .betweenInside(StvnTypes.METADATA_ENTRY, StvnTypes.METADATA_ENTRY, StvnTypes.METADATA_MAP).spacing(1, 1, 0, true, 1)
                .before(StvnTypes.METADATA_VALUE).spaces(1)
                .after(StvnTypes.KW_PRESERVE_INDENT).spaces(1)
                .after(StvnTypes.KW_FILTER_INCL).spaces(1)
                .after(StvnTypes.KW_FILTER_EXCL).spaces(1)

                // Enum and subset filter variant list brackets (STVN 2.0.0 § 3.3): :Enum[#A #B], #filterIncl [#A #B]
                .between(StvnTypes.KW_ENUM, StvnTypes.ENUM_DEF).spaces(0)
                .afterInside(StvnTypes.LBRACK, StvnTypes.ENUM_DEF).spacing(0, 0, 0, true, 1)
                .beforeInside(StvnTypes.RBRACK, StvnTypes.ENUM_DEF).spacing(0, 0, 0, true, 1)
                .betweenInside(StvnTypes.VALUE_KEYWORD, StvnTypes.VALUE_KEYWORD, StvnTypes.ENUM_DEF).spacing(1, 1, 0, true, 1)
                .betweenInside(StvnTypes.LBRACK, StvnTypes.RBRACK, StvnTypes.VARIANT_LIST).spaces(0)
                .afterInside(StvnTypes.LBRACK, StvnTypes.VARIANT_LIST).spacing(0, 0, 0, true, 1)
                .beforeInside(StvnTypes.RBRACK, StvnTypes.VARIANT_LIST).spacing(0, 0, 0, true, 1)
                .betweenInside(StvnTypes.VALUE_KEYWORD, StvnTypes.VALUE_KEYWORD, StvnTypes.VARIANT_LIST).spacing(1, 1, 0, true, 1)

                // Type constructor spacing (STVN 2.0.0 § 3.3): :Tuple(:Int :String), :Option(:String), :Seq(:DateTimes)
                .beforeInside(StvnTypes.LPAREN, StvnTypes.PRODUCT_TYPE).spaces(0)
                .beforeInside(StvnTypes.LPAREN, StvnTypes.COLLECTION_TYPE).spaces(0)
                .beforeInside(StvnTypes.LPAREN, StvnTypes.SUM_TYPE).spaces(0)
                .afterInside(StvnTypes.LPAREN, StvnTypes.PRODUCT_TYPE).spacing(0, 0, 0, true, 1)
                .beforeInside(StvnTypes.RPAREN, StvnTypes.PRODUCT_TYPE).spacing(0, 0, 0, true, 1)
                .betweenInside(StvnTypes.SCHEMA_TYPE, StvnTypes.SCHEMA_TYPE, StvnTypes.PRODUCT_TYPE).spacing(1, 1, 0, true, 1)
                .afterInside(StvnTypes.LPAREN, StvnTypes.COLLECTION_TYPE).spacing(0, 0, 0, true, 1)
                .beforeInside(StvnTypes.RPAREN, StvnTypes.COLLECTION_TYPE).spacing(0, 0, 0, true, 1)
                .betweenInside(StvnTypes.SCHEMA_TYPE, StvnTypes.SCHEMA_TYPE, StvnTypes.COLLECTION_TYPE).spacing(1, 1, 0, true, 1)
                .afterInside(StvnTypes.LPAREN, StvnTypes.SUM_TYPE).spacing(0, 0, 0, true, 1)
                .beforeInside(StvnTypes.RPAREN, StvnTypes.SUM_TYPE).spacing(0, 0, 0, true, 1)
                .betweenInside(StvnTypes.SCHEMA_TYPE, StvnTypes.SCHEMA_TYPE, StvnTypes.SUM_TYPE).spacing(1, 1, 0, true, 1)

                // Explicit sum variant tag spacing: #Some val, #Left val, #Right val, #1 val
                .between(StvnTypes.SOME_LITERAL, StvnTypes.VALUE).spaces(1)
                .between(StvnTypes.SOME_SHORT_LITERAL, StvnTypes.VALUE).spaces(1)
                .between(StvnTypes.LEFT_LITERAL, StvnTypes.VALUE).spaces(1)
                .between(StvnTypes.LEFT_SHORT_LITERAL, StvnTypes.VALUE).spaces(1)
                .between(StvnTypes.RIGHT_LITERAL, StvnTypes.VALUE).spaces(1)
                .between(StvnTypes.RIGHT_SHORT_LITERAL, StvnTypes.VALUE).spaces(1)
                .between(StvnTypes.UNION_TAG_PREFIX, StvnTypes.VALUE).spaces(1)

                // Payload tuple and list literal spacing (STVN 2.0.0 § 3.3): ("host" 8080), [1 2 3], []
                .afterInside(StvnTypes.LPAREN, StvnTypes.TUPLE_LITERAL).spacing(0, 0, 0, true, 1)
                .beforeInside(StvnTypes.RPAREN, StvnTypes.TUPLE_LITERAL).spacing(0, 0, 0, true, 1)
                .betweenInside(StvnTypes.VALUE, StvnTypes.VALUE, StvnTypes.TUPLE_LITERAL).spacing(1, 1, 0, true, 1)
                .betweenInside(StvnTypes.LBRACK, StvnTypes.RBRACK, StvnTypes.LIST_LITERAL).spaces(0)
                .afterInside(StvnTypes.LBRACK, StvnTypes.LIST_LITERAL).spacing(0, 0, 0, true, 1)
                .beforeInside(StvnTypes.RBRACK, StvnTypes.LIST_LITERAL).spacing(0, 0, 0, true, 1)
                .betweenInside(StvnTypes.VALUE, StvnTypes.VALUE, StvnTypes.LIST_LITERAL).spacing(1, 1, 0, true, 1)

                // Map entries (STVN 2.0.0 § 3.3): ["key" val] inside MAP_LITERAL have 0 bracket padding, 1 key-value space
                .betweenInside(StvnTypes.LBRACE, StvnTypes.RBRACE, StvnTypes.MAP_LITERAL).spaces(0)
                .afterInside(StvnTypes.LBRACK, StvnTypes.MAP_LITERAL).spacing(0, 0, 0, true, 1)
                .beforeInside(StvnTypes.RBRACK, StvnTypes.MAP_LITERAL).spacing(0, 0, 0, true, 1)
                .betweenInside(StvnTypes.VALUE, StvnTypes.VALUE, StvnTypes.MAP_LITERAL).spacing(1, 1, 0, true, 1)

                // Map entries separation: keep line breaks in multiline maps; 0 brace padding on single-line maps
                .betweenInside(StvnTypes.RBRACK, StvnTypes.LBRACK, StvnTypes.MAP_LITERAL).spacing(1, 1, 0, true, 1)
                .afterInside(StvnTypes.LBRACE, StvnTypes.MAP_LITERAL).spacing(0, 0, 0, true, 1)
                .beforeInside(StvnTypes.RBRACE, StvnTypes.MAP_LITERAL).spacing(0, 0, 0, true, 1)

                // Braces in document root and defs: line break after '{' and before '}'
                .afterInside(StvnTypes.LBRACE, StvnParserDefinition.FILE).lineBreakInCode()
                .beforeInside(StvnTypes.RBRACE, StvnParserDefinition.FILE).lineBreakInCode()
                .afterInside(StvnTypes.LBRACE, StvnTypes.META_BLOCK).lineBreakInCode()
                .beforeInside(StvnTypes.RBRACE, StvnTypes.META_BLOCK).lineBreakInCode()
                .afterInside(StvnTypes.LBRACE, StvnTypes.DEFS_ENTRY).lineBreakInCode()
                .beforeInside(StvnTypes.RBRACE, StvnTypes.DEFS_ENTRY).lineBreakInCode()
                .afterInside(StvnTypes.LBRACE, StvnTypes.PACKAGE_ENCLOSURE).lineBreakInCode()
                .beforeInside(StvnTypes.RBRACE, StvnTypes.PACKAGE_ENCLOSURE).lineBreakInCode()

                // Use and Include options block spacing: { #strip }
                .afterInside(StvnTypes.LBRACE, StvnTypes.USE_OPTIONS_BLOCK).spacing(1, 1, 0, true, 1)
                .beforeInside(StvnTypes.RBRACE, StvnTypes.USE_OPTIONS_BLOCK).spacing(1, 1, 0, true, 1)
                .afterInside(StvnTypes.LBRACE, StvnTypes.INCLUDE_OPTIONS_BLOCK).spacing(1, 1, 0, true, 1)
                .beforeInside(StvnTypes.RBRACE, StvnTypes.INCLUDE_OPTIONS_BLOCK).spacing(1, 1, 0, true, 1)
                .afterInside(StvnTypes.LBRACK, StvnTypes.USE_STMT).spacing(1, 1, 0, true, 1)
                .beforeInside(StvnTypes.RBRACK, StvnTypes.USE_STMT).spacing(1, 1, 0, true, 1)
                .afterInside(StvnTypes.LBRACK, StvnTypes.INCLUDE_STMT).spacing(1, 1, 0, true, 1)
                .beforeInside(StvnTypes.RBRACK, StvnTypes.INCLUDE_STMT).spacing(1, 1, 0, true, 1)

                // Line breaks following closing braces
                .after(StvnTypes.RBRACE).lineBreakInCode()

                // Spacing between section entries and comments
                .between(StvnTypes.DEFS_ENTRY, StvnTypes.COMMENT).lineBreakInCode()
                .between(StvnTypes.TYPE_ENTRY, StvnTypes.COMMENT).spacing(1, Integer.MAX_VALUE, 0, true, 1)
                .between(StvnTypes.BODY_ENTRY, StvnTypes.COMMENT).spacing(1, Integer.MAX_VALUE, 0, true, 1)
                .between(StvnTypes.DEFS_ELEMENT, StvnTypes.COMMENT).spacing(1, Integer.MAX_VALUE, 0, true, 1)
                .between(StvnTypes.COMMENT, StvnTypes.DEFS_ELEMENT).lineBreakInCode()
                .between(StvnTypes.PACKAGE_ELEMENT, StvnTypes.COMMENT).spacing(1, Integer.MAX_VALUE, 0, true, 1)
                .between(StvnTypes.COMMENT, StvnTypes.PACKAGE_ELEMENT).lineBreakInCode()
                .between(StvnTypes.META_ELEMENT, StvnTypes.COMMENT).spacing(1, Integer.MAX_VALUE, 0, true, 1)
                .between(StvnTypes.COMMENT, StvnTypes.META_ELEMENT).lineBreakInCode()
                .between(StvnTypes.COMMENT, StvnTypes.DEFS_ENTRY).lineBreakInCode()
                .between(StvnTypes.COMMENT, StvnTypes.TYPE_ENTRY).lineBreakInCode()
                .between(StvnTypes.COMMENT, StvnTypes.BODY_ENTRY).lineBreakInCode()
                .between(StvnTypes.COMMENT, StvnTypes.META_ENTRY).lineBreakInCode()
                .between(StvnTypes.COMMENT, StvnTypes.COMMENT).lineBreakInCode()

                // Standalone comments inside document roots preceded by line break
                .beforeInside(StvnTypes.COMMENT, StvnParserDefinition.FILE).lineBreakInCode()

                // Entries inside documents separated by line breaks
                .between(StvnTypes.META_ELEMENT, StvnTypes.META_ELEMENT).lineBreakInCode()
                .between(StvnTypes.META_ENTRY, StvnTypes.DEFS_ENTRY).lineBreakInCode()
                .between(StvnTypes.META_ENTRY, StvnTypes.TYPE_ENTRY).lineBreakInCode()
                .between(StvnTypes.META_ENTRY, StvnTypes.BODY_ENTRY).lineBreakInCode()
                .between(StvnTypes.DEFS_ENTRY, StvnTypes.TYPE_ENTRY).lineBreakInCode()
                .between(StvnTypes.DEFS_ENTRY, StvnTypes.BODY_ENTRY).lineBreakInCode()
                .between(StvnTypes.TYPE_ENTRY, StvnTypes.BODY_ENTRY).lineBreakInCode()

                // Elements inside defs separated by line breaks
                .between(StvnTypes.DEFS_ELEMENT, StvnTypes.DEFS_ELEMENT).lineBreakInCode()
                .between(StvnTypes.PACKAGE_ELEMENT, StvnTypes.PACKAGE_ELEMENT).lineBreakInCode()

                // Use and Include alias block spacing: multiline line breaks, single-line 1 space
                .afterInside(StvnTypes.LBRACE, StvnTypes.USE_ALIAS_BLOCK).spacing(1, 1, 0, true, 1)
                .beforeInside(StvnTypes.RBRACE, StvnTypes.USE_ALIAS_BLOCK).spacing(1, 1, 0, true, 1)
                .betweenInside(StvnTypes.USE_MAP_ALIAS, StvnTypes.USE_MAP_ALIAS, StvnTypes.USE_ALIAS_BLOCK).spacing(1, 1, 0, true, 1)
                .afterInside(StvnTypes.LBRACE, StvnTypes.INCLUDE_ALIAS_BLOCK).spacing(1, 1, 0, true, 1)
                .beforeInside(StvnTypes.RBRACE, StvnTypes.INCLUDE_ALIAS_BLOCK).spacing(1, 1, 0, true, 1)
                .betweenInside(StvnTypes.INCLUDE_MAP_ALIAS, StvnTypes.INCLUDE_MAP_ALIAS, StvnTypes.INCLUDE_ALIAS_BLOCK).spacing(1, 1, 0, true, 1)

                // :use and :include directive and alias pair horizontal spacing
                .between(StvnTypes.KW_USE, StvnTypes.LBRACK).spaces(1)
                .between(StvnTypes.USE_TARGET, StvnTypes.USE_OPTIONS_BLOCK).spaces(1)
                .between(StvnTypes.USE_TARGET, StvnTypes.USE_ALIAS_BLOCK).spaces(1)
                .between(StvnTypes.USE_OPTIONS_BLOCK, StvnTypes.USE_ALIAS_BLOCK).spaces(1)
                .betweenInside(StvnTypes.TYPE_KEYWORD, StvnTypes.TYPE_KEYWORD, StvnTypes.USE_MAP_ALIAS).spaces(1)
                .betweenInside(StvnTypes.VALUE_KEYWORD, StvnTypes.VALUE_KEYWORD, StvnTypes.USE_MAP_ALIAS).spaces(1)
                .between(StvnTypes.KW_INCLUDE, StvnTypes.LBRACK).spaces(1)
                .between(StvnTypes.STRING_LITERAL, StvnTypes.INCLUDE_OPTIONS_BLOCK).spaces(1)
                .between(StvnTypes.STRING_LITERAL, StvnTypes.INCLUDE_ALIAS_BLOCK).spaces(1)
                .between(StvnTypes.INCLUDE_OPTIONS_BLOCK, StvnTypes.INCLUDE_ALIAS_BLOCK).spaces(1)
                .betweenInside(StvnTypes.TYPE_KEYWORD, StvnTypes.TYPE_KEYWORD, StvnTypes.INCLUDE_MAP_ALIAS).spaces(1)

                // Comments inside MAP_LITERAL
                .betweenInside(StvnTypes.RBRACK, StvnTypes.COMMENT, StvnTypes.MAP_LITERAL).spacing(1, Integer.MAX_VALUE, 0, true, 1)
                .betweenInside(StvnTypes.COMMENT, StvnTypes.LBRACK, StvnTypes.MAP_LITERAL).lineBreakInCode()

                // Preservation of comment lines
                .after(StvnTypes.COMMENT).lineBreakInCode()
                .before(StvnTypes.COMMENT).spacing(1, Integer.MAX_VALUE, 0, true, 1);
    }

    @Override
    public @Nullable TextRange getRangeAffectingIndent(PsiFile file, int offset, ASTNode elementAtOffset) {
        return null;
    }
}
