package org.stvnadore.plugin.formatting;

import com.intellij.formatting.ASTBlock;
import com.intellij.formatting.Alignment;
import com.intellij.formatting.Block;
import com.intellij.formatting.ChildAttributes;
import com.intellij.formatting.Indent;
import com.intellij.formatting.Spacing;
import com.intellij.formatting.SpacingBuilder;
import com.intellij.formatting.Wrap;
import com.intellij.formatting.WrapType;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.TokenType;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.psi.StvnTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AST Block node for the STVN native formatter.
 * <p>
 * This block enforces the canonical 2-space indentation hierarchy across nested
 * schemas, definitions, and compound payloads while preserving comments and fenced strings.
 * </p>
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnBlock implements ASTBlock {

    private final ASTNode node;
    private final @Nullable Wrap wrap;
    private final @Nullable Alignment alignment;
    private final SpacingBuilder spacingBuilder;
    private final CodeStyleSettings settings;
    private final Indent indent;
    private @Nullable List<Block> subBlocks;

    /**
     * Constructs a new {@code StvnBlock}.
     *
     * @param node the underlying AST node
     * @param wrap the wrap specification for this block
     * @param alignment the alignment rule for this block
     * @param spacingBuilder the configured spacing builder
     * @param settings the code style settings
     * @param indent the indentation rule for this block
     */
    public StvnBlock(
            ASTNode node,
            @Nullable Wrap wrap,
            @Nullable Alignment alignment,
            SpacingBuilder spacingBuilder,
            CodeStyleSettings settings,
            Indent indent
    ) {
        this.node = node;
        this.wrap = wrap;
        this.alignment = alignment;
        this.spacingBuilder = spacingBuilder;
        this.settings = settings;
        this.indent = indent;
    }

    @Override
    public ASTNode getNode() {
        return node;
    }

    @Override
    public @NotNull TextRange getTextRange() {
        return node.getTextRange();
    }

    @Override
    public @NotNull List<Block> getSubBlocks() {
        if (subBlocks == null) {
            subBlocks = buildSubBlocks();
        }
        return subBlocks;
    }

    private List<Block> buildSubBlocks() {
        if (isLeaf()) {
            return Collections.emptyList();
        }

        List<Block> blocks = new ArrayList<>();
        ASTNode child = node.getFirstChildNode();
        while (child != null) {
            if (child.getElementType() != TokenType.WHITE_SPACE) {
                Indent childIndent = computeChildIndent(child);
                blocks.add(new StvnBlock(
                        child,
                        Wrap.createWrap(WrapType.NONE, false),
                        null,
                        spacingBuilder,
                        settings,
                        childIndent
                ));
            }
            child = child.getTreeNext();
        }
        return Collections.unmodifiableList(blocks);
    }

    private Indent computeChildIndent(ASTNode child) {
        IElementType parentType = node.getElementType();
        IElementType childType = child.getElementType();

        // Section keywords and top-level delimiters at block boundaries never indent
        if (childType == StvnTypes.KW_DEFS
                || childType == StvnTypes.KW_PACKAGE
                || childType == StvnTypes.PACKAGE_PATH
                || childType == StvnTypes.KW_TYPE
                || childType == StvnTypes.KW_BODY
                || childType == StvnTypes.KW_USE
                || childType == StvnTypes.KW_INCLUDE) {
            return Indent.getNoneIndent();
        }

        // Map literal block boundaries: { and } never indent; all entries between them indent normally (+2)
        if (parentType == StvnTypes.MAP_LITERAL) {
            if (childType == StvnTypes.LBRACE || childType == StvnTypes.RBRACE) {
                return Indent.getNoneIndent();
            }
            if (isSecondValueOfMapEntry(child)) {
                return Indent.getContinuationIndent();
            }
            return Indent.getNormalIndent();
        }

        // List literal block boundaries: [ and ] never indent; contents indent normally (+2)
        if (parentType == StvnTypes.LIST_LITERAL) {
            if (childType == StvnTypes.LBRACK || childType == StvnTypes.RBRACK) {
                return Indent.getNoneIndent();
            }
            return Indent.getNormalIndent();
        }

        // Tuple literal block boundaries: ( and ) never indent; contents indent normally (+2)
        if (parentType == StvnTypes.TUPLE_LITERAL) {
            if (childType == StvnTypes.LPAREN || childType == StvnTypes.RPAREN) {
                return Indent.getNoneIndent();
            }
            return Indent.getNormalIndent();
        }

        // Use and Include alias block boundaries: { and } never indent; entries indent normally (+2)
        if (parentType == StvnTypes.USE_ALIAS_BLOCK || parentType == StvnTypes.INCLUDE_ALIAS_BLOCK) {
            if (childType == StvnTypes.LBRACE || childType == StvnTypes.RBRACE) {
                return Indent.getNoneIndent();
            }
            return Indent.getNormalIndent();
        }

        if (parentType == StvnTypes.USE_MAP_ALIAS || parentType == StvnTypes.INCLUDE_MAP_ALIAS) {
            return Indent.getNoneIndent();
        }

        if (childType == StvnTypes.USE_MAP_ALIAS || childType == StvnTypes.INCLUDE_MAP_ALIAS) {
            return Indent.getNormalIndent();
        }

        if (parentType == StvnTypes.USE_STMT || parentType == StvnTypes.INCLUDE_STMT || parentType == StvnTypes.INCLUDE_ELEMENT) {
            if (childType == StvnTypes.KW_USE || childType == StvnTypes.KW_INCLUDE
                    || childType == StvnTypes.LBRACK || childType == StvnTypes.RBRACK
                    || childType == StvnTypes.USE_ALIAS_BLOCK || childType == StvnTypes.INCLUDE_ALIAS_BLOCK) {
                return Indent.getNoneIndent();
            }
            return Indent.getNormalIndent();
        }

        // Delimiters at block boundaries never indent
        if (childType == StvnTypes.LBRACE || childType == StvnTypes.RBRACE
                || childType == StvnTypes.LBRACK || childType == StvnTypes.RBRACK
                || childType == StvnTypes.LPAREN || childType == StvnTypes.RPAREN) {
            return Indent.getNoneIndent();
        }

        // Section entries inside documents indent by 2 spaces (normal indent)
        if (parentType instanceof IFileElementType) {
            return Indent.getNormalIndent();
        }

        // Elements inside :meta { ... } indent by 2 spaces relative to :meta (4 spaces relative to root)
        if (parentType == StvnTypes.META_BLOCK) {
            if (childType == StvnTypes.META_ELEMENT || childType == StvnTypes.COMMENT) {
                return Indent.getNormalIndent();
            }
            return Indent.getNoneIndent();
        }

        // Definitions inside :defs { ... } indent by 2 spaces relative to :defs
        if (parentType == StvnTypes.DEFS_ENTRY) {
            if (childType == StvnTypes.DEFS_ELEMENT || childType == StvnTypes.COMMENT) {
                return Indent.getNormalIndent();
            }
            return Indent.getNoneIndent();
        }

        // Elements inside :package ... { ... } indent by 2 spaces relative to :package
        if (parentType == StvnTypes.PACKAGE_ENCLOSURE) {
            if (childType == StvnTypes.PACKAGE_ELEMENT || childType == StvnTypes.COMMENT) {
                return Indent.getNormalIndent();
            }
            return Indent.getNoneIndent();
        }

        // Compound collection value contents indent by 2 spaces
        if (parentType == StvnTypes.LIST_LITERAL
                || parentType == StvnTypes.MAP_LITERAL
                || parentType == StvnTypes.TUPLE_LITERAL) {
            return Indent.getNormalIndent();
        }

        // Comments inherit normal indent if enclosed within a document or collection
        if (childType == StvnTypes.COMMENT) {
            if (parentType instanceof IFileElementType
                    || parentType == StvnTypes.LIST_LITERAL
                    || parentType == StvnTypes.MAP_LITERAL
                    || parentType == StvnTypes.TUPLE_LITERAL) {
                return Indent.getNormalIndent();
            }
        }

        return Indent.getNoneIndent();
    }

    private boolean isSecondValueOfMapEntry(ASTNode child) {
        if (child.getElementType() != StvnTypes.VALUE) {
            return false;
        }
        ASTNode prev = child.getTreePrev();
        while (prev != null && (prev.getElementType() == TokenType.WHITE_SPACE || prev.getElementType() == StvnTypes.COMMENT)) {
            prev = prev.getTreePrev();
        }
        if (prev != null && prev.getElementType() == StvnTypes.VALUE) {
            ASTNode beforeFirst = prev.getTreePrev();
            while (beforeFirst != null && (beforeFirst.getElementType() == TokenType.WHITE_SPACE || beforeFirst.getElementType() == StvnTypes.COMMENT)) {
                beforeFirst = beforeFirst.getTreePrev();
            }
            return beforeFirst != null && beforeFirst.getElementType() == StvnTypes.LBRACK;
        }
        return false;
    }

    @Override
    public @Nullable Wrap getWrap() {
        return wrap;
    }

    @Override
    public @Nullable Indent getIndent() {
        return indent;
    }

    @Override
    public @Nullable Alignment getAlignment() {
        return alignment;
    }

    @Override
    public @Nullable Spacing getSpacing(@Nullable Block child1, @NotNull Block child2) {
        if (child1 instanceof ASTBlock b1 && child2 instanceof ASTBlock b2) {
            IElementType t1 = b1.getNode().getElementType();
            IElementType t2 = b2.getNode().getElementType();
            int keepBlankLines = settings.getCommonSettings(StvnLanguage.INSTANCE).KEEP_BLANK_LINES_IN_CODE;
            if (t1 == StvnTypes.COMMENT && t2 == StvnTypes.COMMENT) {
                return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
            }
            if ((t1 == StvnTypes.DEFS_ELEMENT || t1 == StvnTypes.PACKAGE_ELEMENT || t1 == StvnTypes.META_ELEMENT || t1 == StvnTypes.RBRACK) && t2 == StvnTypes.COMMENT) {
                return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
            }
            if (t1 == StvnTypes.COMMENT && (t2 == StvnTypes.DEFS_ELEMENT || t2 == StvnTypes.PACKAGE_ELEMENT || t2 == StvnTypes.META_ELEMENT || t2 == StvnTypes.LBRACK)) {
                return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
            }
        }
        return spacingBuilder.getSpacing(this, child1, child2);
    }

    @Override
    public @NotNull ChildAttributes getChildAttributes(int newChildIndex) {
        IElementType type = node.getElementType();
        if (type instanceof IFileElementType
                || type == StvnTypes.DEFS_ENTRY
                || type == StvnTypes.META_BLOCK
                || type == StvnTypes.PACKAGE_ENCLOSURE
                || type == StvnTypes.USE_STMT
                || type == StvnTypes.USE_ALIAS_BLOCK
                || type == StvnTypes.INCLUDE_STMT
                || type == StvnTypes.INCLUDE_ELEMENT
                || type == StvnTypes.INCLUDE_ALIAS_BLOCK
                || type == StvnTypes.LIST_LITERAL
                || type == StvnTypes.MAP_LITERAL
                || type == StvnTypes.TUPLE_LITERAL) {
            return new ChildAttributes(Indent.getNormalIndent(), null);
        }
        return new ChildAttributes(Indent.getNoneIndent(), null);
    }

    @Override
    public boolean isIncomplete() {
        ASTNode lastChild = node.getLastChildNode();
        if (lastChild == null) {
            return false;
        }
        return lastChild.getElementType() == TokenType.ERROR_ELEMENT
                || (node.getElementType() == StvnTypes.DEFS_ENTRY && lastChild.getElementType() != StvnTypes.RBRACE)
                || (node.getElementType() == StvnTypes.MAP_LITERAL && lastChild.getElementType() != StvnTypes.RBRACE)
                || (node.getElementType() == StvnTypes.USE_ALIAS_BLOCK && lastChild.getElementType() != StvnTypes.RBRACE)
                || (node.getElementType() == StvnTypes.INCLUDE_ALIAS_BLOCK && lastChild.getElementType() != StvnTypes.RBRACE)
                || (node.getElementType() == StvnTypes.USE_STMT && lastChild.getElementType() != StvnTypes.RBRACK)
                || (node.getElementType() == StvnTypes.INCLUDE_STMT && lastChild.getElementType() != StvnTypes.RBRACK)
                || (node.getElementType() == StvnTypes.LIST_LITERAL && lastChild.getElementType() != StvnTypes.RBRACK)
                || (node.getElementType() == StvnTypes.TUPLE_LITERAL && lastChild.getElementType() != StvnTypes.RPAREN)
                || (node.getElementType() instanceof IFileElementType && lastChild.getElementType() != StvnTypes.RBRACE);
    }

    @Override
    public boolean isLeaf() {
        IElementType type = node.getElementType();
        return type == StvnTypes.LITERAL_STRING_FENCED
                || type == StvnTypes.LITERAL_STRING_BLOCK
                || type == StvnTypes.LITERAL_STRING_SIMPLE
                || type == StvnTypes.COMMENT
                || node.getFirstChildNode() == null;
    }
}
