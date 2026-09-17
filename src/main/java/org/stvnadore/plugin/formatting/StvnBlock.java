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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
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

        // Delimiters and section keywords at block boundaries never indent
        if (childType == StvnTypes.LBRACE || childType == StvnTypes.RBRACE
                || childType == StvnTypes.LBRACK || childType == StvnTypes.RBRACK
                || childType == StvnTypes.LPAREN || childType == StvnTypes.RPAREN
                || childType == StvnTypes.KW_DEFS
                || childType == StvnTypes.KW_PACKAGE
                || childType == StvnTypes.PACKAGE_PATH
                || childType == StvnTypes.KW_TYPE
                || childType == StvnTypes.KW_BODY) {
            return Indent.getNoneIndent();
        }

        // Section entries inside documents indent by 2 spaces (normal indent)
        if (parentType == StvnTypes.STVN_PAYLOAD_DOCUMENT
                || parentType == StvnTypes.STVN_FLAT_PAYLOAD_DOCUMENT
                || parentType == StvnTypes.STVN_INCL_DOCUMENT
                || parentType == StvnTypes.STVN_INCLF_DOCUMENT) {
            return Indent.getNormalIndent();
        }

        // Definitions inside :defs { ... } indent by 2 spaces relative to :defs
        if (parentType == StvnTypes.DEFS_ENTRY
                || parentType == StvnTypes.DEFS_INCL_ENTRY
                || parentType == StvnTypes.DEFS_INCLF_ENTRY) {
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
            if (parentType == StvnTypes.STVN_PAYLOAD_DOCUMENT
                    || parentType == StvnTypes.STVN_FLAT_PAYLOAD_DOCUMENT
                    || parentType == StvnTypes.STVN_INCL_DOCUMENT
                    || parentType == StvnTypes.STVN_INCLF_DOCUMENT
                    || parentType == StvnTypes.LIST_LITERAL
                    || parentType == StvnTypes.MAP_LITERAL
                    || parentType == StvnTypes.TUPLE_LITERAL) {
                return Indent.getNormalIndent();
            }
        }

        return Indent.getNoneIndent();
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
        return spacingBuilder.getSpacing(this, child1, child2);
    }

    @Override
    public @NotNull ChildAttributes getChildAttributes(int newChildIndex) {
        IElementType type = node.getElementType();
        if (type == StvnTypes.STVN_PAYLOAD_DOCUMENT
                || type == StvnTypes.STVN_FLAT_PAYLOAD_DOCUMENT
                || type == StvnTypes.STVN_INCL_DOCUMENT
                || type == StvnTypes.STVN_INCLF_DOCUMENT
                || type == StvnTypes.DEFS_ENTRY
                || type == StvnTypes.DEFS_INCL_ENTRY
                || type == StvnTypes.DEFS_INCLF_ENTRY
                || type == StvnTypes.PACKAGE_ENCLOSURE
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
                || (node.getElementType() == StvnTypes.STVN_PAYLOAD_DOCUMENT && lastChild.getElementType() != StvnTypes.RBRACE);
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
