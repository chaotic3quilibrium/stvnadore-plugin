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
            boolean sameLineMapContainer = isSameLineMapEntryContainer(node);
            if (childType == StvnTypes.LBRACE) {
                return Indent.getNoneIndent();
            }
            if (childType == StvnTypes.RBRACE) {
                return sameLineMapContainer ? Indent.getNormalIndent() : Indent.getNoneIndent();
            }
            if (isSecondValueOfMapEntry(child) && isMapEntryValueOnNewLine(child)) {
                return Indent.getContinuationIndent();
            }
            return sameLineMapContainer ? Indent.getSpaceIndent(4) : Indent.getNormalIndent();
        }

        // List literal block boundaries: [ and ] never indent; contents indent normally (+2)
        if (parentType == StvnTypes.LIST_LITERAL) {
            boolean sameLineMapContainer = isSameLineMapEntryContainer(node);
            if (childType == StvnTypes.LBRACK) {
                return Indent.getNoneIndent();
            }
            if (childType == StvnTypes.RBRACK) {
                return sameLineMapContainer ? Indent.getNormalIndent() : Indent.getNoneIndent();
            }
            return sameLineMapContainer ? Indent.getSpaceIndent(4) : Indent.getNormalIndent();
        }

        // Tuple literal block boundaries: ( and ) never indent; contents indent normally (+2)
        if (parentType == StvnTypes.TUPLE_LITERAL) {
            boolean sameLineMapContainer = isSameLineMapEntryContainer(node);
            if (childType == StvnTypes.LPAREN) {
                return Indent.getNoneIndent();
            }
            if (childType == StvnTypes.RPAREN) {
                return sameLineMapContainer ? Indent.getNormalIndent() : Indent.getNoneIndent();
            }
            return sameLineMapContainer ? Indent.getSpaceIndent(4) : Indent.getNormalIndent();
        }

        // Schema-level composite type constructors: :Tuple(...), :Seq(...), :Set(...), :Map(...), :Option(...), :Either(...), :Union(...)
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
            return Indent.getNormalIndent();
        }

        // Enum variant brackets [:Enum[#A #B]] and subset filter variant lists [#filterIncl [#A #B]]
        if (parentType == StvnTypes.ENUM_DEF || parentType == StvnTypes.VARIANT_LIST) {
            if (childType == StvnTypes.LBRACK || childType == StvnTypes.RBRACK) {
                return Indent.getNoneIndent();
            }
            return Indent.getNormalIndent();
        }

        // Metadata facet map { #offset ... }: braces never indent; multiline facet entries indent by +2 spaces
        if (parentType == StvnTypes.METADATA_MAP) {
            if (childType == StvnTypes.LBRACE || childType == StvnTypes.RBRACE) {
                return Indent.getNoneIndent();
            }
            return Indent.getNormalIndent();
        }

        // Use and Include options block boundaries ({ #strip })
        if (parentType == StvnTypes.USE_OPTIONS_BLOCK || parentType == StvnTypes.INCLUDE_OPTIONS_BLOCK) {
            if (childType == StvnTypes.LBRACE || childType == StvnTypes.RBRACE) {
                return Indent.getNoneIndent();
            }
            return Indent.getNormalIndent();
        }

        // Use and Include alias block boundaries: context-aware indentation for same-line vs new-line '{'
        if (parentType == StvnTypes.USE_ALIAS_BLOCK || parentType == StvnTypes.INCLUDE_ALIAS_BLOCK) {
            boolean anchoredToDirective = isAliasBlockAnchoredToDirectiveHeader(node);
            if (childType == StvnTypes.LBRACE) {
                return Indent.getNoneIndent();
            }
            if (childType == StvnTypes.RBRACE) {
                return anchoredToDirective ? Indent.getNormalIndent() : Indent.getNoneIndent();
            }
            return anchoredToDirective ? Indent.getSpaceIndent(4) : Indent.getNormalIndent();
        }

        if (parentType == StvnTypes.USE_MAP_ALIAS || parentType == StvnTypes.INCLUDE_MAP_ALIAS) {
            return Indent.getNoneIndent();
        }

        if (childType == StvnTypes.USE_MAP_ALIAS || childType == StvnTypes.INCLUDE_MAP_ALIAS) {
            return Indent.getNormalIndent();
        }

        if (parentType == StvnTypes.USE_STMT || parentType == StvnTypes.INCLUDE_STMT || parentType == StvnTypes.INCLUDE_ELEMENT) {
            if (childType == StvnTypes.KW_USE || childType == StvnTypes.KW_INCLUDE
                    || childType == StvnTypes.LBRACK || childType == StvnTypes.RBRACK) {
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

        // Section entries inside documents indent by 2 spaces (normal indent);
        // comments outside the root '{ ... }' envelope remain at column 0.
        if (parentType instanceof IFileElementType) {
            if (childType == StvnTypes.COMMENT && isOutsideRootBraces(child)) {
                return Indent.getNoneIndent();
            }
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
                    || parentType == StvnTypes.TUPLE_LITERAL
                    || parentType == StvnTypes.PRODUCT_TYPE
                    || parentType == StvnTypes.COLLECTION_TYPE
                    || parentType == StvnTypes.SUM_TYPE
                    || parentType == StvnTypes.ENUM_DEF
                    || parentType == StvnTypes.VARIANT_LIST
                    || parentType == StvnTypes.METADATA_MAP) {
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

    private static boolean isMapEntryValueOnNewLine(ASTNode secondValueNode) {
        ASTNode prev = secondValueNode.getTreePrev();
        while (prev != null && (prev.getElementType() == TokenType.WHITE_SPACE || prev.getElementType() == StvnTypes.COMMENT)) {
            if (prev.getText().indexOf('\n') >= 0) {
                return true;
            }
            prev = prev.getTreePrev();
        }
        return false;
    }

    private boolean isSameLineMapEntryContainer(ASTNode containerNode) {
        ASTNode collectionVal = containerNode.getTreeParent();
        if (collectionVal == null || collectionVal.getElementType() != StvnTypes.COLLECTION_VALUE) {
            return false;
        }
        ASTNode valueNode = collectionVal.getTreeParent();
        if (valueNode == null || valueNode.getElementType() != StvnTypes.VALUE) {
            return false;
        }
        ASTNode parent = valueNode.getTreeParent();
        if (parent == null || parent.getElementType() != StvnTypes.MAP_LITERAL) {
            return false;
        }
        return isSecondValueOfMapEntry(valueNode) && !isMapEntryValueOnNewLine(valueNode);
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
                return createTrailingCommentSpacing(b1.getNode(), b2.getNode(), keepBlankLines);
            }
            if (t1 == StvnTypes.COMMENT && (t2 == StvnTypes.DEFS_ELEMENT || t2 == StvnTypes.PACKAGE_ELEMENT || t2 == StvnTypes.META_ELEMENT || t2 == StvnTypes.LBRACK)) {
                return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
            }
            IElementType parentType = node.getElementType();
            if ((t1 == StvnTypes.LPAREN || t1 == StvnTypes.LBRACK || t1 == StvnTypes.LBRACE)
                    && t2 == StvnTypes.COMMENT) {
                return createTrailingCommentSpacing(b1.getNode(), b2.getNode(), keepBlankLines);
            }
            if (t2 == StvnTypes.COMMENT) {
                return createTrailingCommentSpacing(b1.getNode(), b2.getNode(), keepBlankLines);
            }
            if (parentType == StvnTypes.METADATA_MAP && isMultiLineMetadataMap(node)) {
                if ((t1 == StvnTypes.LBRACE && t2 == StvnTypes.METADATA_ENTRY)
                        || (t1 == StvnTypes.METADATA_ENTRY && t2 == StvnTypes.METADATA_ENTRY)
                        || (t1 == StvnTypes.METADATA_ENTRY && t2 == StvnTypes.RBRACE)) {
                    return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
                }
            }
            if ((parentType == StvnTypes.PRODUCT_TYPE
                    || (parentType == StvnTypes.SUM_TYPE && node.findChildByType(StvnTypes.KW_UNION) != null))
                    && requiresMultiLineSchemaGroup(node)) {
                if ((t1 == StvnTypes.LPAREN && t2 == StvnTypes.SCHEMA_TYPE)
                        || (t1 == StvnTypes.SCHEMA_TYPE && t2 == StvnTypes.SCHEMA_TYPE)
                        || (t1 == StvnTypes.SCHEMA_TYPE && t2 == StvnTypes.RPAREN)) {
                    return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
                }
            }
            if ((parentType == StvnTypes.COLLECTION_TYPE
                    || (parentType == StvnTypes.SUM_TYPE
                        && (node.findChildByType(StvnTypes.KW_OPTION) != null
                            || node.findChildByType(StvnTypes.KW_EITHER) != null)))
                    && isAuthoredMultiLineParameterizedConstructor(node)) {
                if ((t1 == StvnTypes.LPAREN && t2 == StvnTypes.SCHEMA_TYPE)
                        || (t1 == StvnTypes.SCHEMA_TYPE && t2 == StvnTypes.SCHEMA_TYPE)
                        || (t1 == StvnTypes.SCHEMA_TYPE && t2 == StvnTypes.RPAREN)) {
                    return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
                }
            }
            if (parentType == StvnTypes.LIST_LITERAL && hasNestedValueContainer(node)) {
                if ((t1 == StvnTypes.LBRACK && t2 == StvnTypes.VALUE)
                        || (t1 == StvnTypes.VALUE && t2 == StvnTypes.VALUE)
                        || (t1 == StvnTypes.VALUE && t2 == StvnTypes.RBRACK)) {
                    return Spacing.createSpacing(0, 0, 1, true, keepBlankLines);
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

    private static boolean requiresMultiLineSchemaGroup(ASTNode groupNode) {
        for (ASTNode child = groupNode.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            if (child.getElementType() == StvnTypes.SCHEMA_TYPE
                    && isInlineFacetedOrMultiLineSchemaNode(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInlineFacetedOrMultiLineSchemaNode(ASTNode schemaTypeNode) {
        ASTNode metaMap = schemaTypeNode.findChildByType(StvnTypes.METADATA_MAP);
        if (metaMap != null && metaMap.findChildByType(StvnTypes.METADATA_ENTRY) != null) {
            return true;
        }
        return containsMultiLineSchemaConstruct(schemaTypeNode);
    }

    private static boolean isMultiLineMetadataMap(ASTNode metadataMapNode) {
        return metadataMapNode.textContains('\n')
                || metadataMapNode.findChildByType(StvnTypes.COMMENT) != null;
    }

    private static boolean containsMultiLineSchemaConstruct(ASTNode schemaTypeNode) {
        ASTNode metaMap = schemaTypeNode.findChildByType(StvnTypes.METADATA_MAP);
        if (metaMap != null && isMultiLineMetadataMap(metaMap)) {
            return true;
        }
        ASTNode constructor = schemaTypeNode.findChildByType(StvnTypes.SCHEMA_CONSTRUCTOR);
        if (constructor == null) {
            return false;
        }
        ASTNode productType = constructor.findChildByType(StvnTypes.PRODUCT_TYPE);
        if (productType != null) {
            return productType.textContains('\n') || requiresMultiLineSchemaGroup(productType);
        }
        ASTNode sumType = constructor.findChildByType(StvnTypes.SUM_TYPE);
        if (sumType != null) {
            if (sumType.findChildByType(StvnTypes.KW_UNION) != null) {
                return sumType.textContains('\n') || requiresMultiLineSchemaGroup(sumType);
            }
            if (isAuthoredMultiLineParameterizedConstructor(sumType)) {
                return true;
            }
            for (ASTNode child = sumType.getFirstChildNode(); child != null; child = child.getTreeNext()) {
                if (child.getElementType() == StvnTypes.SCHEMA_TYPE
                        && containsMultiLineSchemaConstruct(child)) {
                    return true;
                }
            }
            return false;
        }
        ASTNode collectionType = constructor.findChildByType(StvnTypes.COLLECTION_TYPE);
        if (collectionType != null) {
            if (isAuthoredMultiLineParameterizedConstructor(collectionType)) {
                return true;
            }
            for (ASTNode child = collectionType.getFirstChildNode(); child != null; child = child.getTreeNext()) {
                if (child.getElementType() == StvnTypes.SCHEMA_TYPE
                        && containsMultiLineSchemaConstruct(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Determines whether a parameterized collection or sum constructor ({@code :Map}, {@code :Seq},
     * {@code :Set}, {@code :Option}, or {@code :Either}) was authored in multi-line form by checking
     * whether any boundary between {@code (}, child {@code SCHEMA_TYPE} nodes, and {@code )} spans
     * a newline or contains a comment token.
     */
    private static boolean isAuthoredMultiLineParameterizedConstructor(ASTNode constructorNode) {
        ASTNode lparen = constructorNode.findChildByType(StvnTypes.LPAREN);
        ASTNode rparen = constructorNode.findChildByType(StvnTypes.RPAREN);
        if (lparen == null || rparen == null) {
            return false;
        }
        List<ASTNode> schemaChildren = new ArrayList<>();
        for (ASTNode child = constructorNode.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            if (child.getElementType() == StvnTypes.SCHEMA_TYPE) {
                schemaChildren.add(child);
            }
        }
        if (schemaChildren.isEmpty()) {
            return false;
        }
        if (hasNewlineOrCommentBetween(lparen, schemaChildren.get(0))) {
            return true;
        }
        for (int i = 0; i < schemaChildren.size() - 1; i++) {
            if (hasNewlineOrCommentBetween(schemaChildren.get(i), schemaChildren.get(i + 1))) {
                return true;
            }
        }
        return hasNewlineOrCommentBetween(schemaChildren.get(schemaChildren.size() - 1), rparen);
    }

    private static boolean hasNewlineOrCommentBetween(ASTNode left, ASTNode right) {
        for (ASTNode cur = left.getTreeNext(); cur != null && cur != right; cur = cur.getTreeNext()) {
            if (cur.getElementType() == StvnTypes.COMMENT || cur.textContains('\n')) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNestedValueContainer(ASTNode containerNode) {
        for (ASTNode child = containerNode.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            if (child.getElementType() == StvnTypes.VALUE
                    && child.findChildByType(StvnTypes.COLLECTION_VALUE) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAliasBlockAnchoredToDirectiveHeader(ASTNode aliasBlockNode) {
        ASTNode prev = aliasBlockNode.getTreePrev();
        while (prev != null && (prev.getElementType() == TokenType.WHITE_SPACE || prev.getElementType() == StvnTypes.COMMENT)) {
            if (prev.getText().indexOf('\n') >= 0) {
                return false;
            }
            prev = prev.getTreePrev();
        }
        if (aliasBlockNode.getElementType() == StvnTypes.USE_ALIAS_BLOCK) {
            return true;
        }
        ASTNode parent = aliasBlockNode.getTreeParent();
        if (parent != null && parent.getElementType() == StvnTypes.INCLUDE_ELEMENT) {
            ASTNode elemPrev = parent.getTreePrev();
            while (elemPrev != null && (elemPrev.getElementType() == TokenType.WHITE_SPACE || elemPrev.getElementType() == StvnTypes.COMMENT)) {
                if (elemPrev.getText().indexOf('\n') >= 0) {
                    return false;
                }
                elemPrev = elemPrev.getTreePrev();
            }
        }
        return true;
    }

    @Override
    public @NotNull ChildAttributes getChildAttributes(int newChildIndex) {
        IElementType type = node.getElementType();
        if (type == StvnTypes.USE_ALIAS_BLOCK || type == StvnTypes.INCLUDE_ALIAS_BLOCK) {
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
                || type == StvnTypes.USE_STMT
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
                || (node.getElementType() == StvnTypes.INCLUDE_ALIAS_BLOCK && lastChild.getElementType() != StvnTypes.RBRACE)
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
                || type == StvnTypes.COMMENT
                || node.getFirstChildNode() == null;
    }

    /**
     * Computes horizontal spacing before a comment node in compliance with STVN_LANGUAGE_SPEC.md § 3.3.1.
     * Preserves multi-space horizontal padding ({@code authoredSpaces >= 2}) and compensates {@code minSpaces}
     * when interior multi-space runs earlier on the same line collapse to 1 space during PSI formatting.
     */
    private static Spacing createTrailingCommentSpacing(ASTNode prevNode, ASTNode commentNode, int keepBlankLines) {
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
                return Spacing.createSpacing(1, Integer.MAX_VALUE, 0, true, keepBlankLines);
            }
        }
        int authoredSpaces = commentStart - prevEnd;
        if (authoredSpaces <= 1) {
            return Spacing.createSpacing(1, 1, 0, true, keepBlankLines);
        }
        int lineStart = prevEnd;
        while (lineStart > 0 && chars.charAt(lineStart - 1) != '\n' && chars.charAt(lineStart - 1) != '\r') {
            lineStart--;
        }
        int targetCol = commentStart - lineStart;
        int collapsedInteriorSpaces = countCollapsibleInteriorSpacesOnLine(chars, lineStart, prevEnd);
        int estimatedCurrentLineCol = Math.max(0, (prevEnd - lineStart) - collapsedInteriorSpaces);
        int minSpaces = Math.max(authoredSpaces, targetCol - estimatedCurrentLineCol);
        return Spacing.createSpacing(minSpaces, Integer.MAX_VALUE, 0, true, keepBlankLines);
    }

    private static int countCollapsibleInteriorSpacesOnLine(CharSequence chars, int lineStart, int codeEnd) {
        int i = lineStart;
        while (i < codeEnd && (chars.charAt(i) == ' ' || chars.charAt(i) == '\t')) {
            i++;
        }
        int collapsible = 0;
        boolean inString = false;
        int spaceRun = 0;
        for (; i < codeEnd; i++) {
            char c = chars.charAt(i);
            if (c == '"' && (i == lineStart || chars.charAt(i - 1) != '\\')) {
                inString = !inString;
                spaceRun = 0;
            } else if (!inString && c == ' ') {
                spaceRun++;
                if (spaceRun >= 2) {
                    collapsible++;
                }
            } else {
                spaceRun = 0;
            }
        }
        return collapsible;
    }
}
