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
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.printer.NestedContainerWrapPolicy;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.psi.StvnTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AST Block node for the STVN native PSI formatter.
 * <p>
 * Enforces the canonical 2-space indentation hierarchy, Zero-Tab Invariant, Law 2 section-scoped
 * nested container wrap policies, Law 3 target-column horizontal anchoring, and Law 4 bidirectional
 * peer-aware trailing {@code //} comment alignment via {@link StvnPsiFormatContext}.
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
    private final @Nullable StvnPsiFormatContext formatContext;
    private @Nullable List<Block> subBlocks;

    /**
     * Constructs a new {@code StvnBlock} without an explicit precomputed format context.
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
        this(node, wrap, alignment, spacingBuilder, settings, indent, null);
    }

    /**
     * Constructs a new {@code StvnBlock} bound to an analyzed {@link StvnPsiFormatContext}.
     *
     * @param node the underlying AST node
     * @param wrap the wrap specification for this block
     * @param alignment the alignment rule for this block
     * @param spacingBuilder the configured spacing builder
     * @param settings the code style settings
     * @param indent the indentation rule for this block
     * @param formatContext precomputed PSI format context, or {@code null}
     */
    public StvnBlock(
            ASTNode node,
            @Nullable Wrap wrap,
            @Nullable Alignment alignment,
            SpacingBuilder spacingBuilder,
            CodeStyleSettings settings,
            Indent indent,
            @Nullable StvnPsiFormatContext formatContext
    ) {
        this.node = node;
        this.wrap = wrap;
        this.alignment = alignment;
        this.spacingBuilder = spacingBuilder;
        this.settings = settings;
        this.indent = indent;
        this.formatContext = formatContext;
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
                        childIndent,
                        formatContext
                ));
            }
            child = child.getTreeNext();
        }
        return Collections.unmodifiableList(blocks);
    }

    private Indent computeChildIndent(ASTNode child) {
        IElementType parentType = node.getElementType();
        IElementType childType = child.getElementType();

        if (childType == StvnTypes.KW_DEFS
                || childType == StvnTypes.KW_PACKAGE
                || childType == StvnTypes.PACKAGE_PATH
                || childType == StvnTypes.KW_TYPE
                || childType == StvnTypes.KW_BODY
                || childType == StvnTypes.KW_USE
                || childType == StvnTypes.KW_INCLUDE) {
            return Indent.getNoneIndent();
        }

        if (parentType == StvnTypes.MAP_LITERAL) {
            if (!isContainerMultiLine(node)) {
                return Indent.getNoneIndent();
            }
            boolean sameLineMapContainer = isSameLineMapEntryContainer(node);
            if (childType == StvnTypes.LBRACE) {
                return Indent.getNoneIndent();
            }
            if (childType == StvnTypes.RBRACE) {
                return sameLineMapContainer ? Indent.getNormalIndent() : Indent.getNoneIndent();
            }
            int entrySpaces = sameLineMapContainer ? 4 : 2;
            if (isSecondValueOfMapEntry(child) && isMapEntryValueOnNewLine(child)) {
                return Indent.getSpaceIndent(entrySpaces + 2);
            }
            return Indent.getSpaceIndent(entrySpaces);
        }

        if (parentType == StvnTypes.LIST_LITERAL) {
            if (!isContainerMultiLine(node)) {
                return Indent.getNoneIndent();
            }
            boolean sameLineMapContainer = isSameLineMapEntryContainer(node);
            if (childType == StvnTypes.LBRACK) {
                return Indent.getNoneIndent();
            }
            if (childType == StvnTypes.RBRACK) {
                return sameLineMapContainer ? Indent.getNormalIndent() : Indent.getNoneIndent();
            }
            return sameLineMapContainer ? Indent.getSpaceIndent(4) : Indent.getNormalIndent();
        }

        if (parentType == StvnTypes.TUPLE_LITERAL) {
            if (!isContainerMultiLine(node)) {
                return Indent.getNoneIndent();
            }
            boolean sameLineMapContainer = isSameLineMapEntryContainer(node);
            if (childType == StvnTypes.LPAREN) {
                return Indent.getNoneIndent();
            }
            if (childType == StvnTypes.RPAREN) {
                return sameLineMapContainer ? Indent.getNormalIndent() : Indent.getNoneIndent();
            }
            return sameLineMapContainer ? Indent.getSpaceIndent(4) : Indent.getNormalIndent();
        }

        if (parentType == StvnTypes.PRODUCT_TYPE
                || parentType == StvnTypes.COLLECTION_TYPE
                || parentType == StvnTypes.SUM_TYPE) {
            if (childType == StvnTypes.KW_TUPLE
                    || childType == StvnTypes.COLL_SEQ
                    || childType == StvnTypes.COLL_SET
                    || childType == StvnTypes.COLL_MAP
                    || childType == StvnTypes.KW_OPTION
                    || childType == StvnTypes.KW_EITHER
                    || childType == StvnTypes.KW_UNION
                    || childType == StvnTypes.KW_ENUM
                    || childType == StvnTypes.ENUM_DEF
                    || childType == StvnTypes.LPAREN
                    || childType == StvnTypes.RPAREN) {
                return Indent.getNoneIndent();
            }
            return isContainerMultiLine(node) ? Indent.getNormalIndent() : Indent.getNoneIndent();
        }

        if (parentType == StvnTypes.ENUM_DEF || parentType == StvnTypes.VARIANT_LIST) {
            if (childType == StvnTypes.LBRACK || childType == StvnTypes.RBRACK || parentType == StvnTypes.VARIANT_LIST) {
                return Indent.getNoneIndent();
            }
            return isContainerMultiLine(node) ? Indent.getNormalIndent() : Indent.getNoneIndent();
        }

        if (parentType == StvnTypes.METADATA_MAP) {
            if (childType == StvnTypes.LBRACE || childType == StvnTypes.RBRACE || !isContainerMultiLine(node)) {
                return Indent.getNoneIndent();
            }
            return Indent.getNormalIndent();
        }

        if (parentType == StvnTypes.USE_OPTIONS_BLOCK) {
            return Indent.getNoneIndent();
        }

        if (parentType == StvnTypes.USE_ALIAS_BLOCK) {
            if (!isContainerMultiLine(node)) {
                return Indent.getNoneIndent();
            }
            boolean anchoredToDirective = isAliasBlockAnchoredToDirectiveHeader(node);
            if (childType == StvnTypes.LBRACE) {
                return Indent.getNoneIndent();
            }
            if (childType == StvnTypes.RBRACE) {
                return anchoredToDirective ? Indent.getNormalIndent() : Indent.getNoneIndent();
            }
            return anchoredToDirective ? Indent.getSpaceIndent(4) : Indent.getNormalIndent();
        }

        if (parentType == StvnTypes.USE_MAP_ALIAS
                || parentType == StvnTypes.USE_ELEMENT
                || parentType == StvnTypes.INCLUDE_ELEMENT) {
            return Indent.getNoneIndent();
        }

        if (parentType == StvnTypes.USE_STMT || parentType == StvnTypes.INCLUDE_STMT) {
            if (childType == StvnTypes.KW_USE || childType == StvnTypes.KW_INCLUDE
                    || childType == StvnTypes.LBRACK || childType == StvnTypes.RBRACK
                    || !isContainerMultiLine(node)) {
                return Indent.getNoneIndent();
            }
            return Indent.getNormalIndent();
        }

        if (childType == StvnTypes.LBRACE || childType == StvnTypes.RBRACE
                || childType == StvnTypes.LBRACK || childType == StvnTypes.RBRACK
                || childType == StvnTypes.LPAREN || childType == StvnTypes.RPAREN) {
            return Indent.getNoneIndent();
        }

        if (parentType instanceof IFileElementType) {
            if (childType == StvnTypes.COMMENT && isOutsideRootBraces(child)) {
                return Indent.getNoneIndent();
            }
            return Indent.getNormalIndent();
        }

        if (parentType == StvnTypes.META_BLOCK) {
            if (childType == StvnTypes.META_ELEMENT || childType == StvnTypes.COMMENT) {
                return Indent.getNormalIndent();
            }
            return Indent.getNoneIndent();
        }

        if (parentType == StvnTypes.DEFS_ENTRY) {
            if (childType == StvnTypes.DEFS_ELEMENT || childType == StvnTypes.COMMENT) {
                return Indent.getNormalIndent();
            }
            return Indent.getNoneIndent();
        }

        if (parentType == StvnTypes.PACKAGE_ENCLOSURE || parentType == StvnTypes.NESTED_PACKAGE_ILLEGAL) {
            if (childType == StvnTypes.PACKAGE_ELEMENT || childType == StvnTypes.COMMENT) {
                return Indent.getNormalIndent();
            }
            return Indent.getNoneIndent();
        }

        return Indent.getNoneIndent();
    }

    private boolean isContainerMultiLine(ASTNode containerNode) {
        if (formatContext != null) {
            return formatContext.isMultiLine(containerNode);
        }
        return containerNode.textContains('\n') || StvnPsiFormatContext.containsComment(containerNode);
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

    private static boolean isMapEntryValueOnNewLine(ASTNode secondValueNode) {
        ASTNode prev = secondValueNode.getTreePrev();
        while (prev != null && (prev.getElementType() == TokenType.WHITE_SPACE || prev.getElementType() == StvnTypes.COMMENT)) {
            if (prev.getElementType() == StvnTypes.COMMENT || prev.getText().indexOf('\n') >= 0) {
                return true;
            }
            prev = prev.getTreePrev();
        }
        return false;
    }

    private boolean isSameLineMapEntryContainer(ASTNode containerNode) {
        ASTNode cur = containerNode.getTreeParent();
        if (cur == null || cur.getElementType() != StvnTypes.COLLECTION_VALUE) {
            return false;
        }
        ASTNode valueNode = cur.getTreeParent();
        while (valueNode != null && valueNode.getElementType() == StvnTypes.VALUE) {
            ASTNode parent = valueNode.getTreeParent();
            if (parent == null) {
                return false;
            }
            IElementType pt = parent.getElementType();
            if (pt == StvnTypes.MAP_LITERAL) {
                return isSecondValueOfMapEntry(valueNode) && !isMapEntryValueOnNewLine(valueNode);
            }
            if (pt == StvnTypes.EXPLICIT_OPTION_VALUE
                    || pt == StvnTypes.EXPLICIT_EITHER_VALUE
                    || pt == StvnTypes.EXPLICIT_UNION_VALUE) {
                if (hasNewlineBeforeChild(parent, valueNode)) {
                    return false;
                }
                valueNode = parent.getTreeParent();
            } else {
                return false;
            }
        }
        return false;
    }

    private static boolean hasNewlineBeforeChild(ASTNode parent, ASTNode targetChild) {
        for (ASTNode c = parent.getFirstChildNode(); c != null && c != targetChild; c = c.getTreeNext()) {
            if (c.textContains('\n')) {
                return true;
            }
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
            if (formatContext != null && formatContext.isCompleteDocument()) {
                Spacing exact = formatContext.getSpacingBefore(b2.getNode());
                if (exact != null) {
                    return exact;
                }
            }

            IElementType t1 = b1.getNode().getElementType();
            IElementType t2 = b2.getNode().getElementType();
            int keepBlankLines = settings.getCommonSettings(StvnLanguage.INSTANCE).KEEP_BLANK_LINES_IN_CODE;
            if (t1 == StvnTypes.COMMENT && t2 == StvnTypes.COMMENT) {
                return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
            }
            if (t1 == StvnTypes.COMMENT) {
                return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
            }
            if (t2 == StvnTypes.COMMENT) {
                return createFallbackTrailingCommentSpacing(b1.getNode(), b2.getNode(), keepBlankLines);
            }
            IElementType parentType = node.getElementType();
            if (parentType == StvnTypes.METADATA_MAP && isContainerMultiLine(node)) {
                if ((t1 == StvnTypes.LBRACE && t2 == StvnTypes.METADATA_ENTRY)
                        || (t1 == StvnTypes.METADATA_ENTRY && t2 == StvnTypes.METADATA_ENTRY)
                        || (t1 == StvnTypes.METADATA_ENTRY && t2 == StvnTypes.RBRACE)) {
                    return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
                }
            }
            if ((parentType == StvnTypes.PRODUCT_TYPE
                    || parentType == StvnTypes.COLLECTION_TYPE
                    || parentType == StvnTypes.SUM_TYPE)
                    && isContainerMultiLine(node)) {
                if ((t1 == StvnTypes.LPAREN && t2 == StvnTypes.SCHEMA_TYPE)
                        || (t1 == StvnTypes.SCHEMA_TYPE && t2 == StvnTypes.SCHEMA_TYPE)
                        || (t1 == StvnTypes.SCHEMA_TYPE && t2 == StvnTypes.RPAREN)) {
                    return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
                }
            }
            if (parentType == StvnTypes.LIST_LITERAL && isContainerMultiLine(node)) {
                if ((t1 == StvnTypes.LBRACK && t2 == StvnTypes.VALUE)
                        || (t1 == StvnTypes.VALUE && t2 == StvnTypes.VALUE)
                        || (t1 == StvnTypes.VALUE && t2 == StvnTypes.RBRACK)) {
                    return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
                }
            }
            if (parentType == StvnTypes.TUPLE_LITERAL && isContainerMultiLine(node)) {
                if ((t1 == StvnTypes.LPAREN && t2 == StvnTypes.VALUE)
                        || (t1 == StvnTypes.VALUE && t2 == StvnTypes.VALUE)
                        || (t1 == StvnTypes.VALUE && t2 == StvnTypes.RPAREN)) {
                    return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
                }
            }
            if (parentType == StvnTypes.MAP_LITERAL) {
                if (isContainerMultiLine(node)) {
                    if ((t1 == StvnTypes.LBRACE && t2 == StvnTypes.LBRACK)
                            || (t1 == StvnTypes.RBRACK && t2 == StvnTypes.LBRACK)
                            || (t1 == StvnTypes.RBRACK && t2 == StvnTypes.RBRACE)) {
                        return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
                    }
                } else {
                    if ((t1 == StvnTypes.LBRACE && t2 == StvnTypes.LBRACK)
                            || (t1 == StvnTypes.RBRACK && t2 == StvnTypes.RBRACE)) {
                        return Spacing.createSpacing(0, 0, 0, false, 0);
                    }
                    if (t1 == StvnTypes.RBRACK && t2 == StvnTypes.LBRACK) {
                        return Spacing.createSpacing(1, 1, 0, false, 0);
                    }
                }
            }
            if (formatContext != null
                    && formatContext.options().schemaNestedWrapPolicy() == NestedContainerWrapPolicy.PRESERVE_AUTHORED
                    && !isContainerMultiLine(node)
                    && (parentType == StvnTypes.PRODUCT_TYPE
                        || parentType == StvnTypes.COLLECTION_TYPE
                        || parentType == StvnTypes.SUM_TYPE)) {
                if (t1 == StvnTypes.LPAREN || t2 == StvnTypes.RPAREN) {
                    return Spacing.createSpacing(0, 0, 0, false, 0);
                }
            }
        }
        return spacingBuilder.getSpacing(this, child1, child2);
    }

    private static boolean isOutsideRootBraces(ASTNode child) {
        boolean seenOpenBrace = false;
        for (ASTNode prev = child.getTreePrev(); prev != null; prev = prev.getTreePrev()) {
            if (prev.getElementType() == StvnTypes.RBRACE) {
                return true;
            }
            if (prev.getElementType() == StvnTypes.LBRACE) {
                seenOpenBrace = true;
                break;
            }
        }
        return !seenOpenBrace;
    }

    private static boolean isAliasBlockAnchoredToDirectiveHeader(ASTNode aliasBlockNode) {
        ASTNode prev = aliasBlockNode.getTreePrev();
        while (prev != null && (prev.getElementType() == TokenType.WHITE_SPACE || prev.getElementType() == StvnTypes.COMMENT)) {
            if (prev.getText().indexOf('\n') >= 0) {
                return false;
            }
            prev = prev.getTreePrev();
        }
        ASTNode parent = aliasBlockNode.getTreeParent();
        if (parent != null && parent.getElementType() == StvnTypes.USE_ELEMENT) {
            ASTNode elemPrev = parent.getTreePrev();
            while (elemPrev != null && elemPrev.getElementType() != StvnTypes.LBRACK) {
                if (elemPrev.getText().indexOf('\n') >= 0) {
                    return false;
                }
                elemPrev = elemPrev.getTreePrev();
            }
            ASTNode useStmt = parent.getTreeParent();
            if (useStmt != null && useStmt.getElementType() == StvnTypes.USE_STMT) {
                int useElemCount = 0;
                for (ASTNode c = useStmt.getFirstChildNode(); c != null; c = c.getTreeNext()) {
                    if (c.getElementType() == StvnTypes.USE_ELEMENT) {
                        useElemCount++;
                    }
                }
                if (useElemCount > 1) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public @NotNull ChildAttributes getChildAttributes(int newChildIndex) {
        IElementType type = node.getElementType();
        if (type == StvnTypes.USE_ALIAS_BLOCK) {
            Indent childIndent = isAliasBlockAnchoredToDirectiveHeader(node)
                ? Indent.getSpaceIndent(4)
                : Indent.getNormalIndent();
            return new ChildAttributes(childIndent, null);
        }
        if (type == StvnTypes.LIST_LITERAL
                || type == StvnTypes.MAP_LITERAL
                || type == StvnTypes.TUPLE_LITERAL) {
            Indent childIndent = isSameLineMapEntryContainer(node)
                ? Indent.getSpaceIndent(4)
                : Indent.getNormalIndent();
            return new ChildAttributes(childIndent, null);
        }
        if (type instanceof IFileElementType
                || type == StvnTypes.DEFS_ENTRY
                || type == StvnTypes.META_BLOCK
                || type == StvnTypes.PACKAGE_ENCLOSURE
                || type == StvnTypes.NESTED_PACKAGE_ILLEGAL
                || type == StvnTypes.USE_STMT
                || type == StvnTypes.USE_ELEMENT
                || type == StvnTypes.INCLUDE_STMT
                || type == StvnTypes.INCLUDE_ELEMENT
                || type == StvnTypes.PRODUCT_TYPE
                || type == StvnTypes.COLLECTION_TYPE
                || type == StvnTypes.SUM_TYPE
                || type == StvnTypes.ENUM_DEF
                || type == StvnTypes.VARIANT_LIST
                || type == StvnTypes.METADATA_MAP) {
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
                || (node.getElementType() == StvnTypes.METADATA_MAP && lastChild.getElementType() != StvnTypes.RBRACE)
                || (node.getElementType() == StvnTypes.USE_ALIAS_BLOCK && lastChild.getElementType() != StvnTypes.RBRACE)
                || (node.getElementType() == StvnTypes.USE_STMT && lastChild.getElementType() != StvnTypes.RBRACK)
                || (node.getElementType() == StvnTypes.INCLUDE_STMT && lastChild.getElementType() != StvnTypes.RBRACK)
                || (node.getElementType() == StvnTypes.ENUM_DEF && lastChild.getElementType() != StvnTypes.RBRACK)
                || (node.getElementType() == StvnTypes.VARIANT_LIST && lastChild.getElementType() != StvnTypes.RBRACK)
                || (node.getElementType() == StvnTypes.LIST_LITERAL && lastChild.getElementType() != StvnTypes.RBRACK)
                || (node.getElementType() == StvnTypes.PRODUCT_TYPE && lastChild.getElementType() != StvnTypes.RPAREN)
                || (node.getElementType() == StvnTypes.COLLECTION_TYPE && lastChild.getElementType() != StvnTypes.RPAREN)
                || (node.getElementType() == StvnTypes.SUM_TYPE && lastChild.getElementType() != StvnTypes.RPAREN && lastChild.getElementType() != StvnTypes.ENUM_DEF)
                || (node.getElementType() == StvnTypes.TUPLE_LITERAL && lastChild.getElementType() != StvnTypes.RPAREN)
                || (node.getElementType() instanceof IFileElementType && lastChild.getElementType() != StvnTypes.RBRACE);
    }

    @Override
    public boolean isLeaf() {
        IElementType type = node.getElementType();
        return type == StvnTypes.LITERAL_STRING_FENCED
                || type == StvnTypes.LITERAL_STRING_SIMPLE
                || type == StvnTypes.MALFORMED_FENCE
                || type == StvnTypes.COMMENT
                || node.getFirstChildNode() == null;
    }

    private static Spacing createFallbackTrailingCommentSpacing(
            ASTNode prevNode,
            ASTNode commentNode,
            int keepBlankLines
    ) {
        PsiElement psi = commentNode.getPsi();
        PsiFile file = psi != null ? psi.getContainingFile() : null;
        CharSequence chars = file != null ? file.getViewProvider().getContents() : null;
        int prevEnd = prevNode.getTextRange().getEndOffset();
        int commentStart = commentNode.getTextRange().getStartOffset();
        if (chars == null || prevEnd < 0 || commentStart < prevEnd || commentStart > chars.length()) {
            return Spacing.createSpacing(1, Integer.MAX_VALUE, 0, true, keepBlankLines);
        }
        for (int i = prevEnd; i < commentStart; i++) {
            char c = chars.charAt(i);
            if (c == '\n' || c == '\r') {
                return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
            }
            if (c == '\t') {
                return Spacing.createSpacing(1, 1, 0, false, 0);
            }
        }
        int authoredSpaces = commentStart - prevEnd;
        if (authoredSpaces <= 1) {
            return Spacing.createSpacing(1, 1, 0, false, 0);
        }
        int lineStart = commentStart;
        while (lineStart > 0 && chars.charAt(lineStart - 1) != '\n' && chars.charAt(lineStart - 1) != '\r') {
            lineStart--;
        }
        int origCommentCol = commentStart - lineStart;
        int currentCodeEndCol = prevEnd - lineStart;
        int spaces = Math.max(1, origCommentCol - currentCodeEndCol);
        return Spacing.createSpacing(spaces, spaces, 0, false, 0);
    }
}
