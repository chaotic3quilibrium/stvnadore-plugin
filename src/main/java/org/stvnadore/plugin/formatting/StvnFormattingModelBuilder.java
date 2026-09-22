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
                .after(StvnTypes.KW_DEFS).spaces(1)
                .after(StvnTypes.KW_TYPE).spaces(1)
                .after(StvnTypes.KW_BODY).spaces(1)
                .after(StvnTypes.KW_PACKAGE).spaces(1)
                .after(StvnTypes.KW_USE).spaces(1)
                .after(StvnTypes.KW_INCLUDE).spaces(1)
                .after(StvnTypes.KW_ENUM).spaces(1)

                // Type definition and constant spacing
                .between(StvnTypes.TYPE_DEF_TARGET, StvnTypes.SCHEMA_TYPE).spaces(1)
                .between(StvnTypes.TYPE_DEF_TARGET, StvnTypes.METADATA_MAP).spaces(1)
                .between(StvnTypes.METADATA_MAP, StvnTypes.SCHEMA_TYPE).spaces(1)
                .between(StvnTypes.VALUE_KEYWORD, StvnTypes.SCHEMA_TYPE).spaces(1)
                .between(StvnTypes.VALUE_KEYWORD, StvnTypes.METADATA_MAP).spaces(1)
                .between(StvnTypes.SCHEMA_TYPE, StvnTypes.VALUE).spaces(1)

                // Metadata map internal spacing: { #unsigned #size 16 }
                .afterInside(StvnTypes.LBRACE, StvnTypes.METADATA_MAP).spaces(1)
                .beforeInside(StvnTypes.RBRACE, StvnTypes.METADATA_MAP).spaces(1)
                .betweenInside(StvnTypes.METADATA_ENTRY, StvnTypes.METADATA_ENTRY, StvnTypes.METADATA_MAP).spaces(1)

                // Enum brackets spacing: :Enum [ #A #B ]
                .afterInside(StvnTypes.LBRACK, StvnTypes.ENUM_DEF).spaces(1)
                .beforeInside(StvnTypes.RBRACK, StvnTypes.ENUM_DEF).spaces(1)
                .betweenInside(StvnTypes.VALUE_KEYWORD, StvnTypes.VALUE_KEYWORD, StvnTypes.ENUM_DEF).spaces(1)

                // Type constructor spacing: :Tuple( ... ), :Option( ... ), :Seq( ... )
                .before(StvnTypes.LPAREN).spaces(0)
                .after(StvnTypes.LPAREN).spaces(0)
                .before(StvnTypes.RPAREN).spaces(0)

                // Braces in document root and defs: line break after '{' and before '}'
                .afterInside(StvnTypes.LBRACE, StvnTypes.STVN_PAYLOAD_DOCUMENT).lineBreakInCode()
                .beforeInside(StvnTypes.RBRACE, StvnTypes.STVN_PAYLOAD_DOCUMENT).lineBreakInCode()
                .afterInside(StvnTypes.LBRACE, StvnTypes.STVN_FLAT_PAYLOAD_DOCUMENT).lineBreakInCode()
                .beforeInside(StvnTypes.RBRACE, StvnTypes.STVN_FLAT_PAYLOAD_DOCUMENT).lineBreakInCode()
                .afterInside(StvnTypes.LBRACE, StvnTypes.STVN_INCL_DOCUMENT).lineBreakInCode()
                .beforeInside(StvnTypes.RBRACE, StvnTypes.STVN_INCL_DOCUMENT).lineBreakInCode()
                .afterInside(StvnTypes.LBRACE, StvnTypes.STVN_INCLF_DOCUMENT).lineBreakInCode()
                .beforeInside(StvnTypes.RBRACE, StvnTypes.STVN_INCLF_DOCUMENT).lineBreakInCode()
                .afterInside(StvnTypes.LBRACE, StvnTypes.DEFS_ENTRY).lineBreakInCode()
                .beforeInside(StvnTypes.RBRACE, StvnTypes.DEFS_ENTRY).lineBreakInCode()
                .afterInside(StvnTypes.LBRACE, StvnTypes.PACKAGE_ENCLOSURE).lineBreakInCode()
                .beforeInside(StvnTypes.RBRACE, StvnTypes.PACKAGE_ENCLOSURE).lineBreakInCode()

                // Line breaks following closing braces
                .after(StvnTypes.RBRACE).lineBreakInCode()

                // Spacing between section entries and comments
                .between(StvnTypes.DEFS_ENTRY, StvnTypes.COMMENT).lineBreakInCode()
                .between(StvnTypes.TYPE_ENTRY, StvnTypes.COMMENT).lineBreakInCode()
                .between(StvnTypes.BODY_ENTRY, StvnTypes.COMMENT).lineBreakInCode()
                .between(StvnTypes.DEFS_INCL_ENTRY, StvnTypes.COMMENT).lineBreakInCode()
                .between(StvnTypes.DEFS_INCLF_ENTRY, StvnTypes.COMMENT).lineBreakInCode()

                // Standalone comments inside document roots preceded by line break
                .beforeInside(StvnTypes.COMMENT, StvnTypes.STVN_PAYLOAD_DOCUMENT).lineBreakInCode()
                .beforeInside(StvnTypes.COMMENT, StvnTypes.STVN_FLAT_PAYLOAD_DOCUMENT).lineBreakInCode()
                .beforeInside(StvnTypes.COMMENT, StvnTypes.STVN_INCL_DOCUMENT).lineBreakInCode()
                .beforeInside(StvnTypes.COMMENT, StvnTypes.STVN_INCLF_DOCUMENT).lineBreakInCode()

                // Entries inside documents separated by line breaks
                .between(StvnTypes.DEFS_ENTRY, StvnTypes.TYPE_ENTRY).lineBreakInCode()
                .between(StvnTypes.TYPE_ENTRY, StvnTypes.BODY_ENTRY).lineBreakInCode()

                // Elements inside defs separated by line breaks
                .between(StvnTypes.DEFS_ELEMENT, StvnTypes.DEFS_ELEMENT).lineBreakInCode()
                .between(StvnTypes.PACKAGE_ELEMENT, StvnTypes.PACKAGE_ELEMENT).lineBreakInCode()

                // Brackets: map entries [ key val ] inside MAP_LITERAL have 1 space inside
                .afterInside(StvnTypes.LBRACK, StvnTypes.MAP_LITERAL).spaces(1)
                .beforeInside(StvnTypes.RBRACK, StvnTypes.MAP_LITERAL).spaces(1)

                // Preservation of comment lines
                .before(StvnTypes.COMMENT).lineBreakOrForceSpace(false, false)
                .after(StvnTypes.COMMENT).lineBreakInCode();
    }

    @Override
    public @Nullable TextRange getRangeAffectingIndent(PsiFile file, int offset, ASTNode elementAtOffset) {
        return null;
    }
}
