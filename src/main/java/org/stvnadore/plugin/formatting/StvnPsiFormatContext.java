package org.stvnadore.plugin.formatting;

import com.intellij.formatting.Spacing;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.printer.NestedContainerWrapPolicy;
import org.stvnadore.core.printer.StvnSourceFormatterOptions;
import org.stvnadore.psi.StvnTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Linear {@code O(N)} PSI layout and column-anchoring pass for {@link StvnFormattingModelBuilder}
 * and {@link StvnBlock}, enforcing 1:1 parity with {@code StvnSourceFormatter} across Law 2
 * (section-scoped container wrap policies), Law 3 (target-column horizontal anchoring), and
 * Law 4 (bidirectional peer-aware trailing {@code //} comment alignment).
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnPsiFormatContext {

    private static final Spacing ZERO_SPACES = Spacing.createSpacing(0, 0, 0, false, 0);
    private static final Spacing ONE_SPACE = Spacing.createSpacing(1, 1, 0, false, 0);
    private static final Spacing LINE_BREAK_ONE = Spacing.createSpacing(0, 0, 1, false, 0);
    private static final Spacing LINE_BREAK_BLANK = Spacing.createSpacing(0, 0, 2, false, 0);

    private final StvnSourceFormatterOptions options;
    private final Set<ASTNode> multiLineContainers;
    private final Map<ASTNode, Spacing> spacingBeforeNode;
    private final boolean completeDocument;

    private StvnPsiFormatContext(
            StvnSourceFormatterOptions options,
            Set<ASTNode> multiLineContainers,
            Map<ASTNode, Spacing> spacingBeforeNode,
            boolean completeDocument
    ) {
        this.options = options;
        this.multiLineContainers = multiLineContainers;
        this.spacingBeforeNode = spacingBeforeNode;
        this.completeDocument = completeDocument;
    }

    /**
     * Analyzes {@code psiFile} under {@code options} and precomputes container wrap states and
     * exact inter-sibling {@link Spacing} decisions.
     *
     * @param psiFile target STVN PSI file
     * @param options section-scoped nested container wrap policies
     * @return immutable {@link StvnPsiFormatContext}
     */
    public static StvnPsiFormatContext analyze(PsiFile psiFile, StvnSourceFormatterOptions options) {
        ASTNode root = psiFile.getNode();
        CharSequence chars = psiFile.getViewProvider().getContents();
        LineIndex lineIndex = LineIndex.build(chars);
        Set<ASTNode> multiLine = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Map<ASTNode, Spacing> spacingMap = new IdentityHashMap<>();

        if (root == null) {
            return new StvnPsiFormatContext(options, multiLine, spacingMap, false);
        }

        computeMultiLineContainersRec(root, lineIndex, options, multiLine);

        boolean cleanAst = !PsiTreeUtil.hasErrorElements(psiFile);
        ASTNode rootLBrace = root.findChildByType(StvnTypes.LBRACE);
        ASTNode rootRBrace = findLastDirectChildOfType(root, StvnTypes.RBRACE);
        if (!cleanAst || rootLBrace == null || rootRBrace == null || rootLBrace == rootRBrace) {
            return new StvnPsiFormatContext(options, multiLine, spacingMap, false);
        }

        Simulator sim = new Simulator(chars, lineIndex, options, multiLine, spacingMap);
        boolean ok = sim.simulateDocument(root, rootLBrace, rootRBrace);
        if (!ok) {
            spacingMap.clear();
        }
        return new StvnPsiFormatContext(options, multiLine, spacingMap, ok);
    }

    /**
     * Returns the active {@link StvnSourceFormatterOptions}.
     *
     * @return non-null formatter options
     */
    public StvnSourceFormatterOptions options() {
        return options;
    }

    /**
     * Returns {@code true} when {@code containerNode} formats across multiple lines under Law 2.
     *
     * @param containerNode container AST node
     * @return {@code true} if multi-line; {@code false} if single-line
     */
    public boolean isMultiLine(ASTNode containerNode) {
        return multiLineContainers.contains(containerNode);
    }

    /**
     * Returns {@code true} when {@code psiFile} parsed cleanly and full-file spacing was simulated.
     *
     * @return {@code true} if complete precomputed spacing is active
     */
    public boolean isCompleteDocument() {
        return completeDocument;
    }

    /**
     * Looks up the exact precomputed {@link Spacing} before {@code child2} when {@link #isCompleteDocument()} holds.
     *
     * @param child2 right-hand sibling AST node in {@code StvnBlock.getSpacing}
     * @return exact {@link Spacing}, or {@code null} when fallback rules apply
     */
    public @Nullable Spacing getSpacingBefore(ASTNode child2) {
        if (!completeDocument) {
            return null;
        }
        return spacingBeforeNode.get(child2);
    }

    private static void computeMultiLineContainersRec(
            ASTNode node,
            LineIndex lineIndex,
            StvnSourceFormatterOptions opts,
            Set<ASTNode> out
    ) {
        for (ASTNode child = node.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            if (child.getElementType() != TokenType.WHITE_SPACE) {
                computeMultiLineContainersRec(child, lineIndex, opts, out);
            }
        }
        IElementType type = node.getElementType();
        if (type == StvnTypes.META_BLOCK || type == StvnTypes.DEFS_ENTRY || type == StvnTypes.PACKAGE_ENCLOSURE
                || type == StvnTypes.NESTED_PACKAGE_ILLEGAL) {
            out.add(node);
            return;
        }
        if (type == StvnTypes.METADATA_MAP) {
            ASTNode open = node.findChildByType(StvnTypes.LBRACE);
            ASTNode close = findLastDirectChildOfType(node, StvnTypes.RBRACE);
            if (open != null && close != null
                    && (lineIndex.lineOf(open.getStartOffset()) < lineIndex.lineOf(close.getStartOffset())
                        || containsComment(node))) {
                out.add(node);
            }
            return;
        }
        if (type == StvnTypes.ENUM_DEF) {
            ASTNode open = node.findChildByType(StvnTypes.LBRACK);
            ASTNode close = findLastDirectChildOfType(node, StvnTypes.RBRACK);
            if (open != null && close != null
                    && (lineIndex.lineOf(open.getStartOffset()) < lineIndex.lineOf(close.getStartOffset())
                        || containsComment(node))) {
                out.add(node);
            }
            return;
        }
        if (type == StvnTypes.PRODUCT_TYPE || type == StvnTypes.COLLECTION_TYPE || type == StvnTypes.SUM_TYPE) {
            if (type == StvnTypes.SUM_TYPE && node.findChildByType(StvnTypes.KW_ENUM) != null) {
                ASTNode enumDef = node.findChildByType(StvnTypes.ENUM_DEF);
                if (enumDef != null && out.contains(enumDef)) {
                    out.add(node);
                }
                return;
            }
            ASTNode open = node.findChildByType(StvnTypes.LPAREN);
            ASTNode close = findLastDirectChildOfType(node, StvnTypes.RPAREN);
            if (open == null || close == null) {
                return;
            }
            List<ASTNode> args = collectDirectChildrenOfType(node, StvnTypes.SCHEMA_TYPE);
            boolean expandable = type == StvnTypes.PRODUCT_TYPE
                || (type == StvnTypes.COLLECTION_TYPE && node.findChildByType(StvnTypes.COLL_MAP) != null)
                || (type == StvnTypes.SUM_TYPE
                    && (node.findChildByType(StvnTypes.KW_EITHER) != null
                        || node.findChildByType(StvnTypes.KW_UNION) != null));
            boolean authoredMulti = isAuthoredMultiLineSchemaParen(open, close, args, lineIndex);
            boolean policyExpand = expandable
                && opts.schemaNestedWrapPolicy() == NestedContainerWrapPolicy.EXPAND_NESTED
                && hasNestedSchemaConstructorOrFacetedArg(args, out);
            if (authoredMulti || policyExpand) {
                out.add(node);
            }
            return;
        }
        if (type == StvnTypes.LIST_LITERAL || type == StvnTypes.TUPLE_LITERAL) {
            IElementType openType = type == StvnTypes.LIST_LITERAL ? StvnTypes.LBRACK : StvnTypes.LPAREN;
            IElementType closeType = type == StvnTypes.LIST_LITERAL ? StvnTypes.RBRACK : StvnTypes.RPAREN;
            ASTNode open = node.findChildByType(openType);
            ASTNode close = findLastDirectChildOfType(node, closeType);
            if (open == null || close == null) {
                return;
            }
            List<ASTNode> values = collectDirectChildrenOfType(node, StvnTypes.VALUE);
            if (values.isEmpty() && !containsComment(node)) {
                return;
            }
            boolean multi = containsComment(node)
                || lineIndex.lineOf(open.getStartOffset()) < lineIndex.lineOf(close.getStartOffset())
                || containsFencedString(node)
                || (opts.payloadNestedWrapPolicy() == NestedContainerWrapPolicy.EXPAND_NESTED
                    && hasStructuredOrFencedPayloadChild(values));
            if (multi) {
                out.add(node);
            }
            return;
        }
        if (type == StvnTypes.MAP_LITERAL) {
            ASTNode open = node.findChildByType(StvnTypes.LBRACE);
            ASTNode close = findLastDirectChildOfType(node, StvnTypes.RBRACE);
            if (open == null || close == null) {
                return;
            }
            boolean hasEntries = node.findChildByType(StvnTypes.LBRACK) != null;
            if (!hasEntries && !containsComment(node)) {
                return;
            }
            boolean multi = containsComment(node)
                || lineIndex.lineOf(open.getStartOffset()) < lineIndex.lineOf(close.getStartOffset())
                || containsFencedString(node)
                || opts.payloadNestedWrapPolicy() == NestedContainerWrapPolicy.EXPAND_NESTED;
            if (multi) {
                out.add(node);
            }
            return;
        }
        if (type == StvnTypes.USE_ALIAS_BLOCK) {
            ASTNode open = node.findChildByType(StvnTypes.LBRACE);
            ASTNode close = findLastDirectChildOfType(node, StvnTypes.RBRACE);
            if (open != null && close != null
                    && (lineIndex.lineOf(open.getStartOffset()) < lineIndex.lineOf(close.getStartOffset())
                        || containsComment(node))) {
                out.add(node);
            }
            return;
        }
        if (type == StvnTypes.USE_STMT || type == StvnTypes.INCLUDE_STMT) {
            ASTNode open = node.findChildByType(StvnTypes.LBRACK);
            ASTNode close = findLastDirectChildOfType(node, StvnTypes.RBRACK);
            if (open != null && close != null
                    && (lineIndex.lineOf(open.getStartOffset()) < lineIndex.lineOf(close.getStartOffset())
                        || containsComment(node))) {
                out.add(node);
            }
        }
    }

    private static boolean isAuthoredMultiLineSchemaParen(
            ASTNode openParen,
            ASTNode closeParen,
            List<ASTNode> args,
            LineIndex lineIndex
    ) {
        if (args.isEmpty()) {
            return lineIndex.lineOf(openParen.getStartOffset()) < lineIndex.lineOf(closeParen.getStartOffset())
                || hasCommentStrictlyBetweenOffsets(openParen.getTreeParent(), openParen.getTextRange().getEndOffset(), closeParen.getStartOffset());
        }
        ASTNode first = args.get(0);
        if (lineIndex.lineOf(openParen.getStartOffset()) < lineIndex.lineOf(first.getStartOffset())
                || hasCommentStrictlyBetweenOffsets(openParen.getTreeParent(), openParen.getTextRange().getEndOffset(), first.getStartOffset())) {
            return true;
        }
        for (int i = 1; i < args.size(); i++) {
            ASTNode prev = args.get(i - 1);
            ASTNode curr = args.get(i);
            int prevStopLine = lineIndex.lineOf(Math.max(0, prev.getTextRange().getEndOffset() - 1));
            if (prevStopLine < lineIndex.lineOf(curr.getStartOffset())
                    || hasCommentStrictlyBetweenOffsets(openParen.getTreeParent(), prev.getTextRange().getEndOffset(), curr.getStartOffset())) {
                return true;
            }
        }
        ASTNode last = args.get(args.size() - 1);
        int lastStopLine = lineIndex.lineOf(Math.max(0, last.getTextRange().getEndOffset() - 1));
        return lastStopLine < lineIndex.lineOf(closeParen.getStartOffset())
            || hasCommentStrictlyBetweenOffsets(openParen.getTreeParent(), last.getTextRange().getEndOffset(), closeParen.getStartOffset());
    }

    private static boolean hasNestedSchemaConstructorOrFacetedArg(List<ASTNode> args, Set<ASTNode> multiLineSet) {
        for (ASTNode arg : args) {
            ASTNode ctor = arg.findChildByType(StvnTypes.SCHEMA_CONSTRUCTOR);
            if (ctor != null && ctor.findChildByType(StvnTypes.ATOMIC_TYPE) == null) {
                return true;
            }
            ASTNode meta = arg.findChildByType(StvnTypes.METADATA_MAP);
            if (meta != null && (meta.findChildByType(StvnTypes.METADATA_ENTRY) != null || multiLineSet.contains(meta))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStructuredOrFencedPayloadChild(List<ASTNode> values) {
        for (ASTNode val : values) {
            if (isStructuredOrFencedValue(val)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStructuredOrFencedValue(@Nullable ASTNode val) {
        if (val == null) {
            return false;
        }
        if (val.findChildByType(StvnTypes.COLLECTION_VALUE) != null) {
            return true;
        }
        ASTNode str = val.findChildByType(StvnTypes.STRING_LITERAL);
        if (str != null && (str.findChildByType(StvnTypes.LITERAL_STRING_FENCED) != null
                || str.findChildByType(StvnTypes.MALFORMED_FENCE) != null)) {
            return true;
        }
        for (IElementType wt : List.of(
                StvnTypes.EXPLICIT_OPTION_VALUE,
                StvnTypes.EXPLICIT_EITHER_VALUE,
                StvnTypes.EXPLICIT_UNION_VALUE)) {
            ASTNode wrapped = val.findChildByType(wt);
            if (wrapped != null) {
                return isStructuredOrFencedValue(wrapped.findChildByType(StvnTypes.VALUE));
            }
        }
        return false;
    }

    static boolean containsComment(ASTNode root) {
        for (ASTNode c = root.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            if (c.getElementType() == StvnTypes.COMMENT || containsComment(c)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsFencedString(ASTNode root) {
        IElementType t = root.getElementType();
        if (t == StvnTypes.LITERAL_STRING_FENCED || t == StvnTypes.MALFORMED_FENCE) {
            return true;
        }
        for (ASTNode c = root.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            if (containsFencedString(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCommentStrictlyBetweenOffsets(@Nullable ASTNode root, int startOffset, int endOffset) {
        if (root == null || endOffset <= startOffset) {
            return false;
        }
        for (ASTNode c = root.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            TextRange r = c.getTextRange();
            if (r.getEndOffset() <= startOffset || r.getStartOffset() >= endOffset) {
                continue;
            }
            if (c.getElementType() == StvnTypes.COMMENT || hasCommentStrictlyBetweenOffsets(c, startOffset, endOffset)) {
                return true;
            }
        }
        return false;
    }

    static @Nullable ASTNode findLastDirectChildOfType(ASTNode parent, IElementType type) {
        ASTNode found = null;
        for (ASTNode c = parent.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            if (c.getElementType() == type) {
                found = c;
            }
        }
        return found;
    }

    static List<ASTNode> collectDirectChildrenOfType(ASTNode parent, IElementType type) {
        List<ASTNode> list = new ArrayList<>();
        for (ASTNode c = parent.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            if (c.getElementType() == type) {
                list.add(c);
            }
        }
        return list;
    }

    private static final class LineIndex {
        private final int[] lineStarts;
        private final int length;

        private LineIndex(int[] lineStarts, int length) {
            this.lineStarts = lineStarts;
            this.length = length;
        }

        static LineIndex build(CharSequence chars) {
            int len = chars.length();
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < len; i++) {
                if (chars.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
            int[] arr = new int[starts.size()];
            for (int i = 0; i < starts.size(); i++) {
                arr[i] = starts.get(i);
            }
            return new LineIndex(arr, len);
        }

        int lineOf(int offset) {
            int clamped = Math.max(0, Math.min(offset, length));
            int lo = 0;
            int hi = lineStarts.length - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (lineStarts[mid] <= clamped) {
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return Math.max(0, hi);
        }

        int colOf(int offset) {
            int clamped = Math.max(0, Math.min(offset, length));
            int line = lineOf(clamped);
            return clamped - lineStarts[line];
        }
    }

    private record MapEntrySlice(
        ASTNode lbrack,
        ASTNode keyVal,
        ASTNode valVal,
        ASTNode rbrack
    ) {}

    private static final class Simulator {
        private final CharSequence chars;
        private final LineIndex lineIndex;
        private final StvnSourceFormatterOptions options;
        private final Set<ASTNode> multiLine;
        private final Map<ASTNode, Spacing> spacingMap;
        private final List<ASTNode> allCodeAndComments = new ArrayList<>();
        private final List<ASTNode> comments = new ArrayList<>();
        private final Set<ASTNode> lineStartCodeLeaves = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<Integer, Integer> trailingCommentColByLine = new HashMap<>();

        private int nextCommentIdx = 0;
        private int lastEmittedInputLine = -1;
        private int simCol = 0;
        private boolean currentOutputLineAnchored = true;
        private boolean inSingleLineMetadataMap = false;
        private @Nullable ASTNode lastEmittedUnit = null;

        Simulator(
                CharSequence chars,
                LineIndex lineIndex,
                StvnSourceFormatterOptions options,
                Set<ASTNode> multiLine,
                Map<ASTNode, Spacing> spacingMap
        ) {
            this.chars = chars;
            this.lineIndex = lineIndex;
            this.options = options;
            this.multiLine = multiLine;
            this.spacingMap = spacingMap;
        }

        boolean simulateDocument(ASTNode root, ASTNode openBrace, ASTNode closeBrace) {
            collectOrderedUnits(root);
            int lastCodeLine = -1;
            for (ASTNode unit : allCodeAndComments) {
                if (unit.getElementType() == StvnTypes.COMMENT) {
                    comments.add(unit);
                    int cLine = lineIndex.lineOf(unit.getStartOffset());
                    if (lastCodeLine == cLine && !isTabPrecededOnSameLine(unit.getStartOffset())) {
                        trailingCommentColByLine.put(cLine, lineIndex.colOf(unit.getStartOffset()));
                    }
                } else {
                    int startLine = lineIndex.lineOf(unit.getStartOffset());
                    if (startLine != lastCodeLine) {
                        lineStartCodeLeaves.add(firstLeaf(unit));
                    }
                    lastCodeLine = lineIndex.lineOf(Math.max(0, unit.getTextRange().getEndOffset() - 1));
                }
            }

            // 1. Leading comments before root '{'
            boolean hadLeading = false;
            while (nextCommentIdx < comments.size()
                    && comments.get(nextCommentIdx).getStartOffset() < openBrace.getStartOffset()) {
                ASTNode c = comments.get(nextCommentIdx++);
                int cLine = lineIndex.lineOf(c.getStartOffset());
                if (hadLeading) {
                    emitNewLine(0, cLine - lastEmittedInputLine >= 2, c);
                } else {
                    simCol = 0;
                }
                advanceOverAtomic(c);
                lastEmittedInputLine = cLine;
                hadLeading = true;
            }

            // 2. Root '{'
            if (hadLeading) {
                emitNewLine(0, false, openBrace);
            } else {
                simCol = 0;
            }
            markLineAnchor(openBrace);
            advanceOverAtomic(openBrace);
            lastEmittedInputLine = lineIndex.lineOf(openBrace.getStartOffset());

            List<ASTNode> sections = new ArrayList<>(4);
            for (ASTNode child = openBrace.getTreeNext(); child != null && child != closeBrace; child = child.getTreeNext()) {
                IElementType ct = child.getElementType();
                if (ct == StvnTypes.META_ENTRY || ct == StvnTypes.DEFS_ENTRY
                        || ct == StvnTypes.TYPE_ENTRY || ct == StvnTypes.BODY_ENTRY) {
                    sections.add(child);
                }
            }

            int rootOpenLimit = sections.isEmpty() ? closeBrace.getStartOffset() : sections.get(0).getStartOffset();
            appendTrailingCommentIfOnLine(lastEmittedInputLine, rootOpenLimit, null);

            boolean emittedInRoot = false;
            for (int i = 0; i < sections.size(); i++) {
                ASTNode sec = sections.get(i);
                int secLimit = (i + 1 < sections.size()) ? sections.get(i + 1).getStartOffset() : closeBrace.getStartOffset();
                emittedInRoot = flushStandaloneCommentsBefore(sec.getStartOffset(), 1, emittedInRoot);
                startNewBlockChildLine(1, sec, emittedInRoot);
                emittedInRoot = true;
                if (!simulateRootSection(sec, 1, secLimit)) {
                    return false;
                }
            }

            flushStandaloneCommentsBefore(closeBrace.getStartOffset(), 1, emittedInRoot);
            emitNewLine(0, false, closeBrace);
            markLineAnchor(closeBrace);
            advanceOverAtomic(closeBrace);
            lastEmittedInputLine = lineIndex.lineOf(closeBrace.getStartOffset());
            appendTrailingCommentIfOnLine(lastEmittedInputLine, Integer.MAX_VALUE, null);

            while (nextCommentIdx < comments.size()) {
                ASTNode tc = comments.get(nextCommentIdx++);
                emitNewLine(0, false, tc);
                advanceOverAtomic(tc);
                lastEmittedInputLine = lineIndex.lineOf(tc.getStartOffset());
            }
            return true;
        }

        private boolean simulateRootSection(ASTNode sec, int indent, int nextLimitOffset) {
            IElementType t = sec.getElementType();
            if (t == StvnTypes.META_ENTRY) {
                return simulateMetaEntry(sec, indent, nextLimitOffset);
            }
            if (t == StvnTypes.DEFS_ENTRY) {
                return simulateDefsEntry(sec, indent, nextLimitOffset);
            }
            if (t == StvnTypes.TYPE_ENTRY) {
                return simulateTypeEntry(sec, indent, nextLimitOffset);
            }
            if (t == StvnTypes.BODY_ENTRY) {
                return simulateBodyEntry(sec, indent, nextLimitOffset);
            }
            return false;
        }

        private boolean simulateMetaEntry(ASTNode metaEntry, int indent, int nextLimitOffset) {
            ASTNode kwMeta = metaEntry.findChildByType(StvnTypes.KW_META);
            ASTNode block = metaEntry.findChildByType(StvnTypes.META_BLOCK);
            if (kwMeta == null || block == null) {
                return false;
            }
            ASTNode open = block.findChildByType(StvnTypes.LBRACE);
            ASTNode close = findLastDirectChildOfType(block, StvnTypes.RBRACE);
            if (open == null || close == null) {
                return false;
            }
            advanceOverAtomic(kwMeta);
            emitSpaceOrCommentBreak(kwMeta, open, indent, false);
            advanceOverAtomic(open);
            lastEmittedInputLine = lineIndex.lineOf(open.getStartOffset());

            List<ASTNode> elems = collectDirectChildrenOfType(block, StvnTypes.META_ELEMENT);
            simulateCommentedFacetBlock(open, close, elems, indent + 1, elem -> simulateMetaElement(elem, indent + 1));

            emitNewLine(indent, false, close);
            markLineAnchor(close);
            advanceOverAtomic(close);
            lastEmittedInputLine = lineIndex.lineOf(close.getStartOffset());
            appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
            return true;
        }

        private boolean simulateMetaElement(ASTNode metaElem, int indent) {
            ASTNode facetChild = firstCodeChild(metaElem);
            if (facetChild == null) {
                return false;
            }
            ASTNode keyNode = firstCodeChild(facetChild);
            if (keyNode == null) {
                keyNode = facetChild;
            }
            advanceOverCompoundAtomic(keyNode);
            ASTNode argNode = nextCodeSibling(keyNode);
            if (argNode != null) {
                emitSpaceOrCommentBreak(keyNode, argNode, indent, true);
                if (argNode.getElementType() == StvnTypes.VALUE) {
                    if (!simulateValue(argNode, indent)) {
                        return false;
                    }
                } else if (argNode.getElementType() == StvnTypes.SCHEMA_TYPE) {
                    if (!simulateSchemaType(argNode, indent)) {
                        return false;
                    }
                } else {
                    advanceOverCompoundAtomic(argNode);
                }
            }
            lastEmittedInputLine = lineIndex.lineOf(Math.max(0, metaElem.getTextRange().getEndOffset() - 1));
            return true;
        }

        private boolean simulateDefsEntry(ASTNode defsEntry, int indent, int nextLimitOffset) {
            ASTNode kwDefs = defsEntry.findChildByType(StvnTypes.KW_DEFS);
            ASTNode open = defsEntry.findChildByType(StvnTypes.LBRACE);
            ASTNode close = findLastDirectChildOfType(defsEntry, StvnTypes.RBRACE);
            if (kwDefs == null || open == null || close == null) {
                return false;
            }
            advanceOverAtomic(kwDefs);
            emitSpaceOrCommentBreak(kwDefs, open, indent, false);
            advanceOverAtomic(open);
            lastEmittedInputLine = lineIndex.lineOf(open.getStartOffset());

            List<ASTNode> elements = collectDirectChildrenOfType(defsEntry, StvnTypes.DEFS_ELEMENT);
            int openLimit = elements.isEmpty() ? close.getStartOffset() : elements.get(0).getStartOffset();
            appendTrailingCommentIfOnLine(lastEmittedInputLine, openLimit, null);

            boolean emitted = false;
            for (int i = 0; i < elements.size(); i++) {
                ASTNode el = elements.get(i);
                int nextLimit = (i + 1 < elements.size()) ? elements.get(i + 1).getStartOffset() : close.getStartOffset();
                emitted = flushStandaloneCommentsBefore(el.getStartOffset(), indent + 1, emitted);
                startNewBlockChildLine(indent + 1, el, emitted);
                emitted = true;
                if (!simulateDefsOrPackageElement(el, indent + 1, nextLimit)) {
                    return false;
                }
            }

            flushStandaloneCommentsBefore(close.getStartOffset(), indent + 1, emitted);
            emitNewLine(indent, false, close);
            markLineAnchor(close);
            advanceOverAtomic(close);
            lastEmittedInputLine = lineIndex.lineOf(close.getStartOffset());
            appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
            return true;
        }

        private boolean simulateDefsOrPackageElement(ASTNode wrapper, int indent, int nextLimitOffset) {
            ASTNode child = firstCodeChild(wrapper);
            if (child == null) {
                return false;
            }
            IElementType t = child.getElementType();
            if (t == StvnTypes.PACKAGE_ENCLOSURE || t == StvnTypes.NESTED_PACKAGE_ILLEGAL) {
                return simulatePackageEnclosure(child, indent, nextLimitOffset);
            }
            if (t == StvnTypes.USE_STMT) {
                return simulateUseStmt(child, indent, nextLimitOffset);
            }
            if (t == StvnTypes.INCLUDE_STMT) {
                return simulateIncludeStmt(child, indent, nextLimitOffset);
            }
            if (t == StvnTypes.TYPE_DEFINITION) {
                return simulateTypeDefinition(child, indent, nextLimitOffset);
            }
            if (t == StvnTypes.CONSTANT_DEFINITION) {
                return simulateConstantDefinition(child, indent, nextLimitOffset);
            }
            return false;
        }

        private boolean simulatePackageEnclosure(ASTNode pkg, int indent, int nextLimitOffset) {
            ASTNode kwPkg = pkg.findChildByType(StvnTypes.KW_PACKAGE);
            ASTNode pkgPath = pkg.findChildByType(StvnTypes.PACKAGE_PATH);
            ASTNode open = pkg.findChildByType(StvnTypes.LBRACE);
            ASTNode close = findLastDirectChildOfType(pkg, StvnTypes.RBRACE);
            if (kwPkg == null || pkgPath == null || open == null || close == null) {
                return false;
            }
            advanceOverAtomic(kwPkg);
            emitSpaceOrCommentBreak(kwPkg, pkgPath, indent, false);
            advanceOverCompoundAtomic(pkgPath);
            emitSpaceOrCommentBreak(pkgPath, open, indent, false);
            advanceOverAtomic(open);
            lastEmittedInputLine = lineIndex.lineOf(open.getStartOffset());

            List<ASTNode> elements = collectDirectChildrenOfType(pkg, StvnTypes.PACKAGE_ELEMENT);
            int openLimit = elements.isEmpty() ? close.getStartOffset() : elements.get(0).getStartOffset();
            appendTrailingCommentIfOnLine(lastEmittedInputLine, openLimit, null);

            boolean emitted = false;
            for (int i = 0; i < elements.size(); i++) {
                ASTNode el = elements.get(i);
                int childLimit = (i + 1 < elements.size()) ? elements.get(i + 1).getStartOffset() : close.getStartOffset();
                emitted = flushStandaloneCommentsBefore(el.getStartOffset(), indent + 1, emitted);
                startNewBlockChildLine(indent + 1, el, emitted);
                emitted = true;
                if (!simulateDefsOrPackageElement(el, indent + 1, childLimit)) {
                    return false;
                }
            }

            flushStandaloneCommentsBefore(close.getStartOffset(), indent + 1, emitted);
            emitNewLine(indent, false, close);
            markLineAnchor(close);
            advanceOverAtomic(close);
            lastEmittedInputLine = lineIndex.lineOf(close.getStartOffset());
            appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
            return true;
        }

        private boolean simulateUseStmt(ASTNode useStmt, int baseIndent, int nextLimitOffset) {
            ASTNode kwUse = useStmt.findChildByType(StvnTypes.KW_USE);
            ASTNode lbrack = useStmt.findChildByType(StvnTypes.LBRACK);
            ASTNode rbrack = findLastDirectChildOfType(useStmt, StvnTypes.RBRACK);
            List<ASTNode> elements = collectDirectChildrenOfType(useStmt, StvnTypes.USE_ELEMENT);
            if (kwUse == null || lbrack == null || rbrack == null || elements.isEmpty()) {
                return false;
            }
            boolean hasInteriorComments = hasUnconsumedCommentBefore(rbrack.getStartOffset());
            int lbrackLine = lineIndex.lineOf(lbrack.getStartOffset());
            int rbrackLine = lineIndex.lineOf(rbrack.getStartOffset());

            advanceOverAtomic(kwUse);
            emitHorizontalSpaces(1, lbrack);
            advanceOverAtomic(lbrack);
            lastEmittedInputLine = lbrackLine;

            if (lbrackLine == rbrackLine && !hasInteriorComments) {
                for (ASTNode elem : elements) {
                    emitHorizontalSpaces(1, elem);
                    if (!simulateUseElementInline(elem)) {
                        return false;
                    }
                }
                emitHorizontalSpaces(1, rbrack);
                advanceOverAtomic(rbrack);
                lastEmittedInputLine = rbrackLine;
                appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
                return true;
            }

            ASTNode firstElem = elements.get(0);
            int firstStartLine = lineIndex.lineOf(firstElem.getStartOffset());
            if (lbrackLine == firstStartLine && elements.size() == 1 && !hasUnconsumedCommentBefore(firstElem.getStartOffset())) {
                emitHorizontalSpaces(1, firstElem);
                ASTNode target = firstElem.findChildByType(StvnTypes.USE_TARGET);
                if (target == null) {
                    return false;
                }
                advanceOverCompoundAtomic(target);
                ASTNode prevEnd = target;
                ASTNode optBlock = firstElem.findChildByType(StvnTypes.USE_OPTIONS_BLOCK);
                if (optBlock != null) {
                    emitAlignableSpace(prevEnd, optBlock);
                    if (!simulateUseOptionsBlockInline(optBlock)) {
                        return false;
                    }
                    prevEnd = optBlock;
                }
                ASTNode aliasBlock = firstElem.findChildByType(StvnTypes.USE_ALIAS_BLOCK);
                if (aliasBlock != null) {
                    ASTNode aliasOpen = aliasBlock.findChildByType(StvnTypes.LBRACE);
                    ASTNode aliasClose = findLastDirectChildOfType(aliasBlock, StvnTypes.RBRACE);
                    if (aliasOpen == null || aliasClose == null) {
                        return false;
                    }
                    if (optBlock != null) {
                        emitHorizontalSpaces(1, aliasBlock);
                    } else {
                        emitAlignableSpace(prevEnd, aliasBlock);
                    }
                    advanceOverAtomic(aliasOpen);
                    lastEmittedInputLine = lineIndex.lineOf(aliasOpen.getStartOffset());

                    List<ASTNode> aliases = collectDirectChildrenOfType(aliasBlock, StvnTypes.USE_MAP_ALIAS);
                    int aliasOpenLimit = aliases.isEmpty() ? aliasClose.getStartOffset() : aliases.get(0).getStartOffset();
                    appendTrailingCommentIfOnLine(lastEmittedInputLine, aliasOpenLimit, null);

                    boolean emittedAlias = false;
                    for (int i = 0; i < aliases.size(); i++) {
                        ASTNode alias = aliases.get(i);
                        int aliasLimit = (i + 1 < aliases.size()) ? aliases.get(i + 1).getStartOffset() : aliasClose.getStartOffset();
                        emittedAlias = flushStandaloneCommentsBefore(alias.getStartOffset(), baseIndent + 2, emittedAlias);
                        startNewBlockChildLine(baseIndent + 2, alias, emittedAlias);
                        emittedAlias = true;
                        if (!simulateUseMapAliasInline(alias)) {
                            return false;
                        }
                        lastEmittedInputLine = lineIndex.lineOf(Math.max(0, alias.getTextRange().getEndOffset() - 1));
                        appendTrailingCommentIfOnLine(lastEmittedInputLine, aliasLimit, null);
                    }

                    flushStandaloneCommentsBefore(aliasClose.getStartOffset(), baseIndent + 2, emittedAlias);
                    emitNewLine(baseIndent + 1, false, aliasClose);
                    markLineAnchor(aliasClose);
                    advanceOverAtomic(aliasClose);
                    lastEmittedInputLine = lineIndex.lineOf(aliasClose.getStartOffset());

                    boolean sameLineClose = lineIndex.lineOf(aliasClose.getStartOffset()) == rbrackLine
                        && !hasUnconsumedCommentBefore(rbrack.getStartOffset());
                    if (sameLineClose) {
                        emitHorizontalSpaces(1, rbrack);
                        advanceOverAtomic(rbrack);
                        lastEmittedInputLine = rbrackLine;
                        appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
                    } else {
                        appendTrailingCommentIfOnLine(lastEmittedInputLine, rbrack.getStartOffset(), null);
                        flushStandaloneCommentsBefore(rbrack.getStartOffset(), baseIndent + 1, true);
                        emitNewLine(baseIndent, false, rbrack);
                        markLineAnchor(rbrack);
                        advanceOverAtomic(rbrack);
                        lastEmittedInputLine = rbrackLine;
                        appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
                    }
                } else {
                    lastEmittedInputLine = lineIndex.lineOf(Math.max(0, firstElem.getTextRange().getEndOffset() - 1));
                    appendTrailingCommentIfOnLine(lastEmittedInputLine, rbrack.getStartOffset(), null);
                    flushStandaloneCommentsBefore(rbrack.getStartOffset(), baseIndent + 1, false);
                    emitNewLine(baseIndent, false, rbrack);
                    markLineAnchor(rbrack);
                    advanceOverAtomic(rbrack);
                    lastEmittedInputLine = rbrackLine;
                    appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
                }
                return true;
            }

            appendTrailingCommentIfOnLine(lbrackLine, firstElem.getStartOffset(), null);
            boolean emittedElem = false;
            for (int eIdx = 0; eIdx < elements.size(); eIdx++) {
                ASTNode elem = elements.get(eIdx);
                int elemLimit = (eIdx + 1 < elements.size()) ? elements.get(eIdx + 1).getStartOffset() : rbrack.getStartOffset();
                emittedElem = flushStandaloneCommentsBefore(elem.getStartOffset(), baseIndent + 1, emittedElem);
                startNewBlockChildLine(baseIndent + 1, elem, emittedElem);
                emittedElem = true;

                ASTNode target = elem.findChildByType(StvnTypes.USE_TARGET);
                if (target == null) {
                    return false;
                }
                advanceOverCompoundAtomic(target);
                ASTNode prevEnd = target;
                ASTNode optBlock = elem.findChildByType(StvnTypes.USE_OPTIONS_BLOCK);
                if (optBlock != null) {
                    emitAlignableSpace(prevEnd, optBlock);
                    if (!simulateUseOptionsBlockInline(optBlock)) {
                        return false;
                    }
                    prevEnd = optBlock;
                }
                ASTNode aliasBlock = elem.findChildByType(StvnTypes.USE_ALIAS_BLOCK);
                if (aliasBlock != null) {
                    ASTNode aliasOpen = aliasBlock.findChildByType(StvnTypes.LBRACE);
                    ASTNode aliasClose = findLastDirectChildOfType(aliasBlock, StvnTypes.RBRACE);
                    if (aliasOpen == null || aliasClose == null) {
                        return false;
                    }
                    if (optBlock != null) {
                        emitHorizontalSpaces(1, aliasBlock);
                    } else {
                        emitAlignableSpace(prevEnd, aliasBlock);
                    }
                    if (!multiLine.contains(aliasBlock)) {
                        if (!simulateUseAliasBlockInline(aliasBlock)) {
                            return false;
                        }
                        lastEmittedInputLine = lineIndex.lineOf(aliasClose.getStartOffset());
                        appendTrailingCommentIfOnLine(lastEmittedInputLine, elemLimit, null);
                    } else {
                        advanceOverAtomic(aliasOpen);
                        lastEmittedInputLine = lineIndex.lineOf(aliasOpen.getStartOffset());
                        List<ASTNode> aliases = collectDirectChildrenOfType(aliasBlock, StvnTypes.USE_MAP_ALIAS);
                        int aliasOpenLimit = aliases.isEmpty() ? aliasClose.getStartOffset() : aliases.get(0).getStartOffset();
                        appendTrailingCommentIfOnLine(lastEmittedInputLine, aliasOpenLimit, null);

                        boolean emittedAlias = false;
                        for (int i = 0; i < aliases.size(); i++) {
                            ASTNode alias = aliases.get(i);
                            int aliasLimit = (i + 1 < aliases.size()) ? aliases.get(i + 1).getStartOffset() : aliasClose.getStartOffset();
                            emittedAlias = flushStandaloneCommentsBefore(alias.getStartOffset(), baseIndent + 2, emittedAlias);
                            startNewBlockChildLine(baseIndent + 2, alias, emittedAlias);
                            emittedAlias = true;
                            if (!simulateUseMapAliasInline(alias)) {
                                return false;
                            }
                            lastEmittedInputLine = lineIndex.lineOf(Math.max(0, alias.getTextRange().getEndOffset() - 1));
                            appendTrailingCommentIfOnLine(lastEmittedInputLine, aliasLimit, null);
                        }

                        flushStandaloneCommentsBefore(aliasClose.getStartOffset(), baseIndent + 2, emittedAlias);
                        emitNewLine(baseIndent + 1, false, aliasClose);
                        markLineAnchor(aliasClose);
                        advanceOverAtomic(aliasClose);
                        lastEmittedInputLine = lineIndex.lineOf(aliasClose.getStartOffset());
                        appendTrailingCommentIfOnLine(lastEmittedInputLine, elemLimit, null);
                    }
                } else {
                    lastEmittedInputLine = lineIndex.lineOf(Math.max(0, elem.getTextRange().getEndOffset() - 1));
                    appendTrailingCommentIfOnLine(lastEmittedInputLine, elemLimit, null);
                }
            }

            flushStandaloneCommentsBefore(rbrack.getStartOffset(), baseIndent + 1, emittedElem);
            emitNewLine(baseIndent, false, rbrack);
            markLineAnchor(rbrack);
            advanceOverAtomic(rbrack);
            lastEmittedInputLine = rbrackLine;
            appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
            return true;
        }

        private boolean simulateUseElementInline(ASTNode elem) {
            ASTNode target = elem.findChildByType(StvnTypes.USE_TARGET);
            if (target == null) {
                return false;
            }
            advanceOverCompoundAtomic(target);
            ASTNode prevEnd = target;
            ASTNode optBlock = elem.findChildByType(StvnTypes.USE_OPTIONS_BLOCK);
            if (optBlock != null) {
                emitAlignableSpace(prevEnd, optBlock);
                if (!simulateUseOptionsBlockInline(optBlock)) {
                    return false;
                }
                prevEnd = optBlock;
            }
            ASTNode aliasBlock = elem.findChildByType(StvnTypes.USE_ALIAS_BLOCK);
            if (aliasBlock != null) {
                if (optBlock != null) {
                    emitHorizontalSpaces(1, aliasBlock);
                } else {
                    emitAlignableSpace(prevEnd, aliasBlock);
                }
                return simulateUseAliasBlockInline(aliasBlock);
            }
            return true;
        }

        private boolean simulateUseOptionsBlockInline(ASTNode optBlock) {
            ASTNode open = optBlock.findChildByType(StvnTypes.LBRACE);
            ASTNode close = findLastDirectChildOfType(optBlock, StvnTypes.RBRACE);
            if (open == null || close == null) {
                return false;
            }
            List<ASTNode> opts = collectDirectChildrenOfType(optBlock, StvnTypes.USE_OPTION);
            advanceOverAtomic(open);
            if (opts.isEmpty()) {
                emitHorizontalSpaces(0, close);
                advanceOverAtomic(close);
                return true;
            }
            for (ASTNode opt : opts) {
                emitHorizontalSpaces(1, opt);
                ASTNode kw = opt.findChildByType(StvnTypes.KW_STRIP);
                if (kw == null) {
                    return false;
                }
                advanceOverAtomic(kw);
                ASTNode illegalArg = nextCodeSibling(kw);
                if (illegalArg != null) {
                    emitHorizontalSpaces(1, illegalArg);
                    advanceOverCompoundAtomic(illegalArg);
                }
            }
            emitHorizontalSpaces(1, close);
            advanceOverAtomic(close);
            return true;
        }

        private boolean simulateUseAliasBlockInline(ASTNode aliasBlock) {
            ASTNode open = aliasBlock.findChildByType(StvnTypes.LBRACE);
            ASTNode close = findLastDirectChildOfType(aliasBlock, StvnTypes.RBRACE);
            if (open == null || close == null) {
                return false;
            }
            List<ASTNode> aliases = collectDirectChildrenOfType(aliasBlock, StvnTypes.USE_MAP_ALIAS);
            advanceOverAtomic(open);
            if (aliases.isEmpty()) {
                emitHorizontalSpaces(0, close);
                advanceOverAtomic(close);
                return true;
            }
            for (ASTNode alias : aliases) {
                emitHorizontalSpaces(1, alias);
                if (!simulateUseMapAliasInline(alias)) {
                    return false;
                }
            }
            emitHorizontalSpaces(1, close);
            advanceOverAtomic(close);
            return true;
        }

        private boolean simulateUseMapAliasInline(ASTNode alias) {
            ASTNode first = firstCodeChild(alias);
            if (first == null) {
                return false;
            }
            ASTNode second = nextCodeSibling(first);
            if (second == null) {
                return false;
            }
            advanceOverCompoundAtomic(first);
            emitAlignableSpace(first, second);
            advanceOverCompoundAtomic(second);
            return true;
        }

        private boolean simulateIncludeStmt(ASTNode incStmt, int baseIndent, int nextLimitOffset) {
            ASTNode kwInc = incStmt.findChildByType(StvnTypes.KW_INCLUDE);
            ASTNode lbrack = incStmt.findChildByType(StvnTypes.LBRACK);
            ASTNode rbrack = findLastDirectChildOfType(incStmt, StvnTypes.RBRACK);
            List<ASTNode> elements = collectDirectChildrenOfType(incStmt, StvnTypes.INCLUDE_ELEMENT);
            if (kwInc == null || lbrack == null || rbrack == null || elements.isEmpty()) {
                return false;
            }
            boolean hasInteriorComments = hasUnconsumedCommentBefore(rbrack.getStartOffset());
            int lbrackLine = lineIndex.lineOf(lbrack.getStartOffset());
            int rbrackLine = lineIndex.lineOf(rbrack.getStartOffset());

            advanceOverAtomic(kwInc);
            emitHorizontalSpaces(1, lbrack);
            advanceOverAtomic(lbrack);
            lastEmittedInputLine = lbrackLine;

            if (lbrackLine == rbrackLine && !hasInteriorComments) {
                for (ASTNode elem : elements) {
                    emitHorizontalSpaces(1, elem);
                    advanceOverCompoundAtomic(elem);
                }
                emitHorizontalSpaces(1, rbrack);
                advanceOverAtomic(rbrack);
                lastEmittedInputLine = rbrackLine;
                appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
                return true;
            }

            ASTNode firstElem = elements.get(0);
            if (lbrackLine == lineIndex.lineOf(firstElem.getStartOffset())
                    && elements.size() == 1
                    && !hasUnconsumedCommentBefore(firstElem.getStartOffset())) {
                emitHorizontalSpaces(1, firstElem);
                advanceOverCompoundAtomic(firstElem);
                lastEmittedInputLine = lineIndex.lineOf(Math.max(0, firstElem.getTextRange().getEndOffset() - 1));
                appendTrailingCommentIfOnLine(lastEmittedInputLine, rbrack.getStartOffset(), null);
                flushStandaloneCommentsBefore(rbrack.getStartOffset(), baseIndent + 1, false);
                emitNewLine(baseIndent, false, rbrack);
                markLineAnchor(rbrack);
                advanceOverAtomic(rbrack);
                lastEmittedInputLine = rbrackLine;
                appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
                return true;
            }

            appendTrailingCommentIfOnLine(lbrackLine, firstElem.getStartOffset(), null);
            boolean emitted = false;
            for (int i = 0; i < elements.size(); i++) {
                ASTNode elem = elements.get(i);
                int elemLimit = (i + 1 < elements.size()) ? elements.get(i + 1).getStartOffset() : rbrack.getStartOffset();
                emitted = flushStandaloneCommentsBefore(elem.getStartOffset(), baseIndent + 1, emitted);
                startNewBlockChildLine(baseIndent + 1, elem, emitted);
                emitted = true;
                advanceOverCompoundAtomic(elem);
                lastEmittedInputLine = lineIndex.lineOf(Math.max(0, elem.getTextRange().getEndOffset() - 1));
                appendTrailingCommentIfOnLine(lastEmittedInputLine, elemLimit, null);
            }

            flushStandaloneCommentsBefore(rbrack.getStartOffset(), baseIndent + 1, emitted);
            emitNewLine(baseIndent, false, rbrack);
            markLineAnchor(rbrack);
            advanceOverAtomic(rbrack);
            lastEmittedInputLine = rbrackLine;
            appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
            return true;
        }

        private boolean simulateTypeDefinition(ASTNode typeDef, int indent, int nextLimitOffset) {
            ASTNode target = typeDef.findChildByType(StvnTypes.TYPE_DEF_TARGET);
            ASTNode schema = typeDef.findChildByType(StvnTypes.SCHEMA_TYPE);
            if (target == null || schema == null) {
                return false;
            }
            advanceOverCompoundAtomic(target);
            emitSpaceOrCommentBreak(target, schema, indent + 1, true);
            if (!simulateSchemaType(schema, indent)) {
                return false;
            }
            lastEmittedInputLine = lineIndex.lineOf(Math.max(0, typeDef.getTextRange().getEndOffset() - 1));
            appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
            return true;
        }

        private boolean simulateConstantDefinition(ASTNode constDef, int indent, int nextLimitOffset) {
            ASTNode kw = constDef.findChildByType(StvnTypes.VALUE_KEYWORD);
            ASTNode schema = constDef.findChildByType(StvnTypes.SCHEMA_TYPE);
            ASTNode val = constDef.findChildByType(StvnTypes.VALUE);
            if (kw == null || schema == null || val == null) {
                return false;
            }
            advanceOverCompoundAtomic(kw);
            emitSpaceOrCommentBreak(kw, schema, indent + 1, true);
            if (!simulateSchemaType(schema, indent)) {
                return false;
            }
            emitSpaceOrCommentBreak(schema, val, indent + 1, true);
            if (!simulateValue(val, indent)) {
                return false;
            }
            lastEmittedInputLine = lineIndex.lineOf(Math.max(0, constDef.getTextRange().getEndOffset() - 1));
            appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
            return true;
        }

        private boolean simulateTypeEntry(ASTNode typeEntry, int indent, int nextLimitOffset) {
            ASTNode kw = typeEntry.findChildByType(StvnTypes.KW_TYPE);
            if (kw == null) {
                return false;
            }
            advanceOverAtomic(kw);
            ASTNode schema = typeEntry.findChildByType(StvnTypes.SCHEMA_TYPE);
            ASTNode bareMeta = typeEntry.findChildByType(StvnTypes.METADATA_MAP);
            if (schema != null) {
                emitSpaceOrCommentBreak(kw, schema, indent, true);
                if (!simulateSchemaType(schema, indent)) {
                    return false;
                }
            } else if (bareMeta != null) {
                emitSpaceOrCommentBreak(kw, bareMeta, indent, true);
                if (!simulateMetadataMap(bareMeta, indent)) {
                    return false;
                }
            }
            lastEmittedInputLine = lineIndex.lineOf(Math.max(0, typeEntry.getTextRange().getEndOffset() - 1));
            appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
            return true;
        }

        private boolean simulateBodyEntry(ASTNode bodyEntry, int indent, int nextLimitOffset) {
            ASTNode kw = bodyEntry.findChildByType(StvnTypes.KW_BODY);
            if (kw == null) {
                return false;
            }
            advanceOverAtomic(kw);
            ASTNode val = bodyEntry.findChildByType(StvnTypes.VALUE);
            if (val != null) {
                emitSpaceOrCommentBreak(kw, val, indent, true);
                if (!simulateValue(val, indent)) {
                    return false;
                }
            }
            lastEmittedInputLine = lineIndex.lineOf(Math.max(0, bodyEntry.getTextRange().getEndOffset() - 1));
            appendTrailingCommentIfOnLine(lastEmittedInputLine, nextLimitOffset, null);
            return true;
        }

        private boolean simulateSchemaType(ASTNode schemaType, int indent) {
            ASTNode metaMap = schemaType.findChildByType(StvnTypes.METADATA_MAP);
            ASTNode typeKw = schemaType.findChildByType(StvnTypes.TYPE_KEYWORD);
            ASTNode ctor = schemaType.findChildByType(StvnTypes.SCHEMA_CONSTRUCTOR);
            if (metaMap != null) {
                if (!simulateMetadataMap(metaMap, indent)) {
                    return false;
                }
                ASTNode target = typeKw != null ? typeKw : ctor;
                if (target == null) {
                    return false;
                }
                emitSpaceOrCommentBreak(metaMap, target, indent, true);
            }
            if (typeKw != null) {
                advanceOverCompoundAtomic(typeKw);
                lastEmittedInputLine = lineIndex.lineOf(Math.max(0, typeKw.getTextRange().getEndOffset() - 1));
                return true;
            }
            if (ctor != null) {
                return simulateSchemaConstructor(ctor, indent);
            }
            return false;
        }

        private boolean simulateMetadataMap(ASTNode metaMap, int indent) {
            ASTNode open = metaMap.findChildByType(StvnTypes.LBRACE);
            ASTNode close = findLastDirectChildOfType(metaMap, StvnTypes.RBRACE);
            if (open == null || close == null) {
                return false;
            }
            List<ASTNode> entries = collectDirectChildrenOfType(metaMap, StvnTypes.METADATA_ENTRY);
            boolean hasComments = hasUnconsumedCommentBefore(close.getStartOffset());
            if (entries.isEmpty() && !hasComments) {
                advanceOverAtomic(open);
                emitHorizontalSpaces(0, close);
                advanceOverAtomic(close);
                lastEmittedInputLine = lineIndex.lineOf(close.getStartOffset());
                return true;
            }
            if (!multiLine.contains(metaMap)) {
                advanceOverAtomic(open);
                boolean prevInlineMeta = inSingleLineMetadataMap;
                inSingleLineMetadataMap = true;
                try {
                    for (ASTNode entry : entries) {
                        emitHorizontalSpaces(1, entry);
                        if (!simulateMetadataEntry(entry, indent, false)) {
                            return false;
                        }
                    }
                } finally {
                    inSingleLineMetadataMap = prevInlineMeta;
                }
                emitHorizontalSpaces(1, close);
                advanceOverAtomic(close);
                lastEmittedInputLine = lineIndex.lineOf(close.getStartOffset());
                return true;
            }

            advanceOverAtomic(open);
            lastEmittedInputLine = lineIndex.lineOf(open.getStartOffset());
            simulateCommentedFacetBlock(open, close, entries, indent + 1, entry -> simulateMetadataEntry(entry, indent + 1, true));
            emitNewLine(indent, false, close);
            markLineAnchor(close);
            advanceOverAtomic(close);
            lastEmittedInputLine = lineIndex.lineOf(close.getStartOffset());
            return true;
        }

        private boolean simulateMetadataEntry(ASTNode entry, int indent, boolean alignable) {
            ASTNode first = firstCodeChild(entry);
            if (first == null) {
                return false;
            }
            IElementType ft = first.getElementType();
            if (ft == StvnTypes.METADATA_BARE_FLAG || ft == StvnTypes.METADATA_DIRECTIVE) {
                advanceOverCompoundAtomic(first);
                lastEmittedInputLine = lineIndex.lineOf(Math.max(0, entry.getTextRange().getEndOffset() - 1));
                return true;
            }
            ASTNode keyNode = (ft == StvnTypes.METADATA_BOOL
                    || ft == StvnTypes.METADATA_TRAIT
                    || ft == StvnTypes.METADATA_FILTER
                    || ft == StvnTypes.METADATA_UNKNOWN_ENTRY)
                ? firstCodeChild(first)
                : first;
            if (keyNode == null) {
                return false;
            }
            advanceOverCompoundAtomic(keyNode);
            ASTNode argNode = nextCodeSibling(keyNode);
            if (argNode == null && keyNode.getTreeParent() != entry) {
                argNode = nextCodeSibling(keyNode.getTreeParent());
            }
            if (argNode != null) {
                if (alignable) {
                    emitAlignableSpace(keyNode, argNode);
                } else {
                    emitHorizontalSpaces(1, argNode);
                }
                if (argNode.getElementType() == StvnTypes.VARIANT_LIST) {
                    if (!simulateVariantListInline(argNode)) {
                        return false;
                    }
                } else if (argNode.getElementType() == StvnTypes.VALUE) {
                    if (!simulateValue(argNode, indent)) {
                        return false;
                    }
                } else if (argNode.getElementType() == StvnTypes.SCHEMA_TYPE) {
                    if (!simulateSchemaType(argNode, indent)) {
                        return false;
                    }
                } else {
                    advanceOverCompoundAtomic(argNode);
                }
            }
            lastEmittedInputLine = lineIndex.lineOf(Math.max(0, entry.getTextRange().getEndOffset() - 1));
            return true;
        }

        private boolean simulateVariantListInline(ASTNode variantList) {
            ASTNode open = variantList.findChildByType(StvnTypes.LBRACK);
            ASTNode close = findLastDirectChildOfType(variantList, StvnTypes.RBRACK);
            if (open == null || close == null) {
                return false;
            }
            advanceOverAtomic(open);
            boolean first = true;
            for (ASTNode c = open.getTreeNext(); c != null && c != close; c = c.getTreeNext()) {
                if (c.getElementType() == TokenType.WHITE_SPACE || c.getElementType() == StvnTypes.COMMENT) {
                    continue;
                }
                emitHorizontalSpaces(first ? 0 : 1, c);
                advanceOverCompoundAtomic(c);
                first = false;
            }
            emitHorizontalSpaces(0, close);
            advanceOverAtomic(close);
            return true;
        }

        private boolean simulateSchemaConstructor(ASTNode ctor, int indent) {
            ASTNode child = firstCodeChild(ctor);
            if (child == null) {
                return false;
            }
            IElementType t = child.getElementType();
            if (t == StvnTypes.ATOMIC_TYPE) {
                advanceOverCompoundAtomic(child);
                lastEmittedInputLine = lineIndex.lineOf(Math.max(0, child.getTextRange().getEndOffset() - 1));
                return true;
            }
            if (t == StvnTypes.SUM_TYPE && child.findChildByType(StvnTypes.KW_ENUM) != null) {
                return simulateEnumConstructor(child, indent);
            }
            ASTNode head = firstCodeChild(child);
            ASTNode open = child.findChildByType(StvnTypes.LPAREN);
            ASTNode close = findLastDirectChildOfType(child, StvnTypes.RPAREN);
            if (head == null || open == null || close == null) {
                return false;
            }
            List<ASTNode> args = collectDirectChildrenOfType(child, StvnTypes.SCHEMA_TYPE);
            advanceOverAtomic(head);
            emitHorizontalSpaces(0, open);
            advanceOverAtomic(open);
            lastEmittedInputLine = lineIndex.lineOf(open.getStartOffset());

            if (!multiLine.contains(child)) {
                for (int i = 0; i < args.size(); i++) {
                    ASTNode arg = args.get(i);
                    if (i == 0) {
                        emitHorizontalSpaces(0, arg);
                    } else {
                        emitAlignableSpace(args.get(i - 1), arg);
                    }
                    if (!simulateSchemaType(arg, indent)) {
                        return false;
                    }
                }
                emitHorizontalSpaces(0, close);
                advanceOverAtomic(close);
                lastEmittedInputLine = lineIndex.lineOf(close.getStartOffset());
                return true;
            }

            int openLimit = args.isEmpty() ? close.getStartOffset() : args.get(0).getStartOffset();
            appendTrailingCommentIfOnLine(lastEmittedInputLine, openLimit, null);
            boolean emitted = false;
            for (int i = 0; i < args.size(); i++) {
                ASTNode arg = args.get(i);
                int limit = (i + 1 < args.size()) ? args.get(i + 1).getStartOffset() : close.getStartOffset();
                emitted = flushStandaloneCommentsBefore(arg.getStartOffset(), indent + 1, emitted);
                startNewBlockChildLine(indent + 1, arg, emitted);
                emitted = true;
                if (!simulateSchemaType(arg, indent + 1)) {
                    return false;
                }
                lastEmittedInputLine = lineIndex.lineOf(Math.max(0, arg.getTextRange().getEndOffset() - 1));
                appendTrailingCommentIfOnLine(lastEmittedInputLine, limit, null);
            }

            flushStandaloneCommentsBefore(close.getStartOffset(), indent + 1, emitted);
            emitNewLine(indent, false, close);
            markLineAnchor(close);
            advanceOverAtomic(close);
            lastEmittedInputLine = lineIndex.lineOf(close.getStartOffset());
            return true;
        }

        private boolean simulateEnumConstructor(ASTNode sumType, int indent) {
            ASTNode kwEnum = sumType.findChildByType(StvnTypes.KW_ENUM);
            ASTNode enumDef = sumType.findChildByType(StvnTypes.ENUM_DEF);
            if (kwEnum == null || enumDef == null) {
                return false;
            }
            ASTNode lbrack = enumDef.findChildByType(StvnTypes.LBRACK);
            ASTNode rbrack = findLastDirectChildOfType(enumDef, StvnTypes.RBRACK);
            if (lbrack == null || rbrack == null) {
                return false;
            }
            List<ASTNode> variants = collectDirectChildrenOfType(enumDef, StvnTypes.VALUE_KEYWORD);
            advanceOverAtomic(kwEnum);
            emitHorizontalSpaces(0, enumDef);
            advanceOverAtomic(lbrack);
            lastEmittedInputLine = lineIndex.lineOf(lbrack.getStartOffset());

            if (!multiLine.contains(enumDef)) {
                for (int i = 0; i < variants.size(); i++) {
                    emitHorizontalSpaces(i == 0 ? 0 : 1, variants.get(i));
                    advanceOverCompoundAtomic(variants.get(i));
                }
                emitHorizontalSpaces(0, rbrack);
                advanceOverAtomic(rbrack);
                lastEmittedInputLine = lineIndex.lineOf(rbrack.getStartOffset());
                return true;
            }

            int openLimit = variants.isEmpty() ? rbrack.getStartOffset() : variants.get(0).getStartOffset();
            appendTrailingCommentIfOnLine(lastEmittedInputLine, openLimit, null);
            boolean emitted = false;
            for (int i = 0; i < variants.size(); i++) {
                ASTNode v = variants.get(i);
                int limit = (i + 1 < variants.size()) ? variants.get(i + 1).getStartOffset() : rbrack.getStartOffset();
                emitted = flushStandaloneCommentsBefore(v.getStartOffset(), indent + 1, emitted);
                startNewBlockChildLine(indent + 1, v, emitted);
                emitted = true;
                advanceOverCompoundAtomic(v);
                lastEmittedInputLine = lineIndex.lineOf(Math.max(0, v.getTextRange().getEndOffset() - 1));
                appendTrailingCommentIfOnLine(lastEmittedInputLine, limit, null);
            }
            flushStandaloneCommentsBefore(rbrack.getStartOffset(), indent + 1, emitted);
            emitNewLine(indent, false, rbrack);
            markLineAnchor(rbrack);
            advanceOverAtomic(rbrack);
            lastEmittedInputLine = lineIndex.lineOf(rbrack.getStartOffset());
            return true;
        }

        private boolean simulateValue(ASTNode val, int indent) {
            ASTNode child = firstCodeChild(val);
            if (child == null) {
                return false;
            }
            IElementType t = child.getElementType();
            if (t == StvnTypes.BOOLEAN_VALUE
                    || t == StvnTypes.INTEGER_LITERAL
                    || t == StvnTypes.FLOAT_LITERAL
                    || t == StvnTypes.STRING_LITERAL
                    || t == StvnTypes.VALUE_KEYWORD) {
                advanceOverCompoundAtomic(child);
                lastEmittedInputLine = lineIndex.lineOf(Math.max(0, child.getTextRange().getEndOffset() - 1));
                return true;
            }
            if (t == StvnTypes.EXPLICIT_OPTION_VALUE
                    || t == StvnTypes.EXPLICIT_EITHER_VALUE
                    || t == StvnTypes.EXPLICIT_UNION_VALUE) {
                ASTNode tag = firstCodeChild(child);
                if (tag == null) {
                    return false;
                }
                advanceOverCompoundAtomic(tag);
                ASTNode innerVal = child.findChildByType(StvnTypes.VALUE);
                if (innerVal != null) {
                    emitSpaceOrCommentBreak(tag, innerVal, indent, true);
                    return simulateValue(innerVal, indent);
                }
                lastEmittedInputLine = lineIndex.lineOf(Math.max(0, child.getTextRange().getEndOffset() - 1));
                return true;
            }
            if (t == StvnTypes.COLLECTION_VALUE) {
                ASTNode coll = firstCodeChild(child);
                if (coll == null) {
                    return false;
                }
                IElementType ct = coll.getElementType();
                if (ct == StvnTypes.LIST_LITERAL) {
                    return simulateLinearValueContainer(coll, StvnTypes.LBRACK, StvnTypes.RBRACK, indent);
                }
                if (ct == StvnTypes.TUPLE_LITERAL) {
                    return simulateLinearValueContainer(coll, StvnTypes.LPAREN, StvnTypes.RPAREN, indent);
                }
                if (ct == StvnTypes.MAP_LITERAL) {
                    return simulateMapLiteral(coll, indent);
                }
            }
            return false;
        }

        private boolean simulateLinearValueContainer(
                ASTNode container,
                IElementType openType,
                IElementType closeType,
                int indent
        ) {
            ASTNode open = container.findChildByType(openType);
            ASTNode close = findLastDirectChildOfType(container, closeType);
            if (open == null || close == null) {
                return false;
            }
            List<ASTNode> elems = collectDirectChildrenOfType(container, StvnTypes.VALUE);
            boolean hasComments = hasUnconsumedCommentBefore(close.getStartOffset());
            advanceOverAtomic(open);
            lastEmittedInputLine = lineIndex.lineOf(open.getStartOffset());

            if (elems.isEmpty() && !hasComments) {
                emitHorizontalSpaces(0, close);
                advanceOverAtomic(close);
                lastEmittedInputLine = lineIndex.lineOf(close.getStartOffset());
                return true;
            }

            if (!multiLine.contains(container)) {
                for (int i = 0; i < elems.size(); i++) {
                    ASTNode el = elems.get(i);
                    if (i == 0) {
                        emitHorizontalSpaces(0, el);
                    } else {
                        emitAlignableSpace(elems.get(i - 1), el);
                    }
                    if (!simulateValue(el, indent)) {
                        return false;
                    }
                }
                emitHorizontalSpaces(0, close);
                advanceOverAtomic(close);
                lastEmittedInputLine = lineIndex.lineOf(close.getStartOffset());
                return true;
            }

            int openLimit = elems.isEmpty() ? close.getStartOffset() : elems.get(0).getStartOffset();
            appendTrailingCommentIfOnLine(lastEmittedInputLine, openLimit, null);
            boolean emitted = false;
            for (int i = 0; i < elems.size(); i++) {
                ASTNode el = elems.get(i);
                int elLimit = (i + 1 < elems.size()) ? elems.get(i + 1).getStartOffset() : close.getStartOffset();
                emitted = flushStandaloneCommentsBefore(el.getStartOffset(), indent + 1, emitted);
                startNewBlockChildLine(indent + 1, el, emitted);
                emitted = true;
                if (!simulateValue(el, indent + 1)) {
                    return false;
                }
                lastEmittedInputLine = lineIndex.lineOf(Math.max(0, el.getTextRange().getEndOffset() - 1));
                appendTrailingCommentIfOnLine(lastEmittedInputLine, elLimit, null);
            }

            flushStandaloneCommentsBefore(close.getStartOffset(), indent + 1, emitted);
            emitNewLine(indent, false, close);
            markLineAnchor(close);
            advanceOverAtomic(close);
            lastEmittedInputLine = lineIndex.lineOf(close.getStartOffset());
            return true;
        }

        private boolean simulateMapLiteral(ASTNode mapLit, int indent) {
            ASTNode open = mapLit.findChildByType(StvnTypes.LBRACE);
            ASTNode close = findLastDirectChildOfType(mapLit, StvnTypes.RBRACE);
            if (open == null || close == null) {
                return false;
            }
            List<MapEntrySlice> entries = collectMapEntrySlices(mapLit, open, close);
            if (entries == null) {
                return false;
            }
            boolean hasComments = hasUnconsumedCommentBefore(close.getStartOffset());
            advanceOverAtomic(open);
            lastEmittedInputLine = lineIndex.lineOf(open.getStartOffset());

            if (entries.isEmpty() && !hasComments) {
                emitHorizontalSpaces(0, close);
                advanceOverAtomic(close);
                lastEmittedInputLine = lineIndex.lineOf(close.getStartOffset());
                return true;
            }

            if (!multiLine.contains(mapLit)) {
                for (int i = 0; i < entries.size(); i++) {
                    MapEntrySlice entry = entries.get(i);
                    if (i == 0) {
                        emitHorizontalSpaces(0, entry.lbrack());
                    } else {
                        emitAlignableSpace(entries.get(i - 1).rbrack(), entry.lbrack());
                    }
                    advanceOverAtomic(entry.lbrack());
                    emitHorizontalSpaces(0, entry.keyVal());
                    if (!simulateValue(entry.keyVal(), indent)) {
                        return false;
                    }
                    emitAlignableSpace(entry.keyVal(), entry.valVal());
                    if (!simulateValue(entry.valVal(), indent)) {
                        return false;
                    }
                    emitHorizontalSpaces(0, entry.rbrack());
                    advanceOverAtomic(entry.rbrack());
                }
                emitHorizontalSpaces(0, close);
                advanceOverAtomic(close);
                lastEmittedInputLine = lineIndex.lineOf(close.getStartOffset());
                return true;
            }

            int openLimit = entries.isEmpty() ? close.getStartOffset() : entries.get(0).lbrack().getStartOffset();
            appendTrailingCommentIfOnLine(lastEmittedInputLine, openLimit, null);

            boolean emitted = false;
            for (int i = 0; i < entries.size(); i++) {
                MapEntrySlice entry = entries.get(i);
                int entryLimit = (i + 1 < entries.size())
                    ? entries.get(i + 1).lbrack().getStartOffset()
                    : close.getStartOffset();
                emitted = flushStandaloneCommentsBefore(entry.lbrack().getStartOffset(), indent + 1, emitted);
                startNewBlockChildLine(indent + 1, entry.lbrack(), emitted);
                emitted = true;

                advanceOverAtomic(entry.lbrack());
                emitHorizontalSpaces(0, entry.keyVal());
                if (!simulateValue(entry.keyVal(), indent + 1)) {
                    return false;
                }
                int keyStopLine = lineIndex.lineOf(Math.max(0, entry.keyVal().getTextRange().getEndOffset() - 1));
                int valStartLine = lineIndex.lineOf(entry.valVal().getStartOffset());
                boolean valueOnNewLine = valStartLine > keyStopLine
                    || hasUnconsumedCommentBefore(entry.valVal().getStartOffset());
                if (valueOnNewLine) {
                    lastEmittedInputLine = keyStopLine;
                    appendTrailingCommentIfOnLine(keyStopLine, entry.valVal().getStartOffset(), null);
                    flushStandaloneCommentsBefore(entry.valVal().getStartOffset(), indent + 2, true);
                    emitNewLine(indent + 2, false, entry.valVal());
                    markLineAnchor(entry.valVal());
                    if (!simulateValue(entry.valVal(), indent + 2)) {
                        return false;
                    }
                    lastEmittedInputLine = lineIndex.lineOf(Math.max(0, entry.valVal().getTextRange().getEndOffset() - 1));
                    appendTrailingCommentIfOnLine(lastEmittedInputLine, entry.rbrack().getStartOffset(), null);
                    flushStandaloneCommentsBefore(entry.rbrack().getStartOffset(), indent + 2, true);
                    emitNewLine(indent + 1, false, entry.rbrack());
                    markLineAnchor(entry.rbrack());
                    advanceOverAtomic(entry.rbrack());
                } else {
                    emitAlignableSpace(entry.keyVal(), entry.valVal());
                    if (!simulateValue(entry.valVal(), indent + 1)) {
                        return false;
                    }
                    emitHorizontalSpaces(0, entry.rbrack());
                    advanceOverAtomic(entry.rbrack());
                }
                lastEmittedInputLine = lineIndex.lineOf(entry.rbrack().getStartOffset());
                appendTrailingCommentIfOnLine(lastEmittedInputLine, entryLimit, null);
            }

            flushStandaloneCommentsBefore(close.getStartOffset(), indent + 1, emitted);
            emitNewLine(indent, false, close);
            markLineAnchor(close);
            advanceOverAtomic(close);
            lastEmittedInputLine = lineIndex.lineOf(close.getStartOffset());
            return true;
        }

        private @Nullable List<MapEntrySlice> collectMapEntrySlices(ASTNode mapLit, ASTNode open, ASTNode close) {
            List<MapEntrySlice> slices = new ArrayList<>();
            ASTNode cur = open.getTreeNext();
            while (cur != null && cur != close) {
                IElementType t = cur.getElementType();
                if (t == TokenType.WHITE_SPACE || t == StvnTypes.COMMENT) {
                    cur = cur.getTreeNext();
                    continue;
                }
                if (t != StvnTypes.LBRACK) {
                    return null;
                }
                ASTNode lbrack = cur;
                ASTNode key = nextCodeSibling(lbrack);
                if (key == null || key.getElementType() != StvnTypes.VALUE) {
                    return null;
                }
                ASTNode val = nextCodeSibling(key);
                if (val == null || val.getElementType() != StvnTypes.VALUE) {
                    return null;
                }
                ASTNode rbrack = nextCodeSibling(val);
                if (rbrack == null || rbrack.getElementType() != StvnTypes.RBRACK) {
                    return null;
                }
                slices.add(new MapEntrySlice(lbrack, key, val, rbrack));
                cur = rbrack.getTreeNext();
            }
            return slices;
        }

        @FunctionalInterface
        private interface EntrySimulator {
            boolean run(ASTNode entry);
        }

        private void simulateCommentedFacetBlock(
                ASTNode openBrace,
                ASTNode closeBrace,
                List<ASTNode> entries,
                int entryIndent,
                EntrySimulator entrySimulator
        ) {
            int openLimit = entries.isEmpty() ? closeBrace.getStartOffset() : entries.get(0).getStartOffset();
            appendTrailingCommentIfOnLine(lastEmittedInputLine, openLimit, null);

            Map<Integer, Integer> peerCols = new HashMap<>();
            List<ASTNode> entryTrailings = new ArrayList<>(entries.size());
            int scanIdx = nextCommentIdx;
            for (int i = 0; i < entries.size(); i++) {
                ASTNode entry = entries.get(i);
                int entryStopOffset = entry.getTextRange().getEndOffset();
                int entryStopLine = lineIndex.lineOf(Math.max(0, entryStopOffset - 1));
                int nextLimit = (i + 1 < entries.size()) ? entries.get(i + 1).getStartOffset() : closeBrace.getStartOffset();
                while (scanIdx < comments.size() && comments.get(scanIdx).getStartOffset() < entryStopOffset) {
                    scanIdx++;
                }
                ASTNode tc = null;
                if (scanIdx < comments.size()) {
                    ASTNode cand = comments.get(scanIdx);
                    if (cand.getStartOffset() < nextLimit && lineIndex.lineOf(cand.getStartOffset()) == entryStopLine) {
                        tc = cand;
                        peerCols.merge(lineIndex.colOf(cand.getStartOffset()), 1, Integer::sum);
                        scanIdx++;
                    }
                }
                entryTrailings.add(tc);
            }

            int prevAnchorStopLine = lineIndex.lineOf(openBrace.getStartOffset());
            for (int i = 0; i < entries.size(); i++) {
                ASTNode entry = entries.get(i);
                int entryStartLine = lineIndex.lineOf(entry.getStartOffset());
                int entryStopLine = lineIndex.lineOf(Math.max(0, entry.getTextRange().getEndOffset() - 1));
                List<ASTNode> leading = new ArrayList<>();
                while (nextCommentIdx < comments.size()
                        && comments.get(nextCommentIdx).getStartOffset() < entry.getStartOffset()) {
                    leading.add(comments.get(nextCommentIdx++));
                }
                int firstSliceLine = leading.isEmpty() ? entryStartLine : lineIndex.lineOf(leading.get(0).getStartOffset());
                boolean hadBlankBeforeSlice = i > 0 && (firstSliceLine - prevAnchorStopLine >= 2);

                for (int j = 0; j < leading.size(); j++) {
                    ASTNode c = leading.get(j);
                    boolean blank = (j == 0 && hadBlankBeforeSlice)
                        || (j > 0 && lineIndex.lineOf(c.getStartOffset()) - lineIndex.lineOf(leading.get(j - 1).getStartOffset()) >= 2);
                    emitNewLine(entryIndent, blank, c);
                    advanceOverAtomic(c);
                }
                boolean blankBeforeEntry = leading.isEmpty()
                    ? hadBlankBeforeSlice
                    : (entryStartLine - lineIndex.lineOf(leading.get(leading.size() - 1).getStartOffset()) >= 2);
                emitNewLine(entryIndent, blankBeforeEntry, entry);
                markLineAnchor(entry);
                entrySimulator.run(entry);
                while (nextCommentIdx < comments.size()
                        && comments.get(nextCommentIdx).getStartOffset() < entry.getTextRange().getEndOffset()) {
                    nextCommentIdx++;
                }

                ASTNode tc = entryTrailings.get(i);
                if (tc != null && nextCommentIdx < comments.size() && comments.get(nextCommentIdx) == tc) {
                    nextCommentIdx++;
                    int spaces = computeTrailingCommentSpaces(tc, peerCols);
                    emitHorizontalSpaces(spaces, tc);
                    advanceOverAtomic(tc);
                }
                prevAnchorStopLine = entryStopLine;
                lastEmittedInputLine = entryStopLine;
            }

            List<ASTNode> footer = new ArrayList<>();
            while (nextCommentIdx < comments.size()
                    && comments.get(nextCommentIdx).getStartOffset() < closeBrace.getStartOffset()) {
                footer.add(comments.get(nextCommentIdx++));
            }
            if (!footer.isEmpty()) {
                boolean footerBlank = !entries.isEmpty()
                    && (lineIndex.lineOf(footer.get(0).getStartOffset()) - prevAnchorStopLine >= 2);
                for (int j = 0; j < footer.size(); j++) {
                    ASTNode c = footer.get(j);
                    boolean blank = (j == 0 && footerBlank)
                        || (j > 0 && lineIndex.lineOf(c.getStartOffset()) - lineIndex.lineOf(footer.get(j - 1).getStartOffset()) >= 2);
                    emitNewLine(entryIndent, blank, c);
                    advanceOverAtomic(c);
                    lastEmittedInputLine = lineIndex.lineOf(c.getStartOffset());
                }
            }
        }

        private void emitSpaceOrCommentBreak(
                ASTNode leftNode,
                ASTNode rightNode,
                int breakIndent,
                boolean alignable
        ) {
            if (hasUnconsumedCommentBefore(rightNode.getStartOffset())) {
                int leftLine = lineIndex.lineOf(Math.max(0, leftNode.getTextRange().getEndOffset() - 1));
                appendTrailingCommentIfOnLine(leftLine, rightNode.getStartOffset(), null);
                flushStandaloneCommentsBefore(rightNode.getStartOffset(), breakIndent, true);
                emitNewLine(breakIndent, false, rightNode);
                markLineAnchor(rightNode);
                return;
            }
            if (alignable) {
                emitAlignableSpace(leftNode, rightNode);
            } else {
                emitHorizontalSpaces(1, rightNode);
            }
        }

        private void emitAlignableSpace(ASTNode leftNode, ASTNode rightNode) {
            int leftStopOff = leftNode.getTextRange().getEndOffset();
            int rightStartOff = rightNode.getStartOffset();
            int leftLine = lineIndex.lineOf(Math.max(0, leftStopOff - 1));
            int rightLine = lineIndex.lineOf(rightStartOff);
            if (!inSingleLineMetadataMap
                    && currentOutputLineAnchored
                    && leftLine == rightLine
                    && !hasUnconsumedCommentBefore(rightStartOff)
                    && !containsTabBetween(leftStopOff, rightStartOff)) {
                int authoredSpaces = rightStartOff - leftStopOff;
                if (authoredSpaces >= 2) {
                    int origTargetCol = lineIndex.colOf(rightStartOff);
                    int spaces = Math.max(1, origTargetCol - simCol);
                    emitHorizontalSpaces(spaces, rightNode);
                    return;
                }
            }
            emitHorizontalSpaces(1, rightNode);
        }

        private boolean flushStandaloneCommentsBefore(int limitOffset, int indent, boolean hasEmittedItemInBlock) {
            boolean emitted = hasEmittedItemInBlock;
            while (nextCommentIdx < comments.size() && comments.get(nextCommentIdx).getStartOffset() < limitOffset) {
                ASTNode c = comments.get(nextCommentIdx++);
                int cLine = lineIndex.lineOf(c.getStartOffset());
                boolean blank = emitted && lastEmittedInputLine >= 0 && (cLine - lastEmittedInputLine >= 2);
                emitNewLine(indent, blank, c);
                advanceOverAtomic(c);
                lastEmittedInputLine = cLine;
                emitted = true;
            }
            return emitted;
        }

        private void startNewBlockChildLine(int indent, ASTNode childStartNode, boolean hasEmittedItemInBlock) {
            int childLine = lineIndex.lineOf(childStartNode.getStartOffset());
            boolean blank = hasEmittedItemInBlock && lastEmittedInputLine >= 0 && (childLine - lastEmittedInputLine >= 2);
            emitNewLine(indent, blank, childStartNode);
            markLineAnchor(childStartNode);
        }

        private void appendTrailingCommentIfOnLine(
                int line,
                int maxStartOffsetExclusive,
                @Nullable Map<Integer, Integer> peerCols
        ) {
            if (nextCommentIdx < comments.size()) {
                ASTNode cand = comments.get(nextCommentIdx);
                if (lineIndex.lineOf(cand.getStartOffset()) == line && cand.getStartOffset() < maxStartOffsetExclusive) {
                    nextCommentIdx++;
                    int spaces = computeTrailingCommentSpaces(cand, peerCols);
                    emitHorizontalSpaces(spaces, cand);
                    advanceOverAtomic(cand);
                    lastEmittedInputLine = lineIndex.lineOf(cand.getStartOffset());
                }
            }
        }

        private int computeTrailingCommentSpaces(ASTNode commentNode, @Nullable Map<Integer, Integer> peerCols) {
            if (lastEmittedUnit == null || !currentOutputLineAnchored) {
                return 1;
            }
            int prevEnd = lastEmittedUnit.getTextRange().getEndOffset();
            int commentStart = commentNode.getStartOffset();
            int commentLine = lineIndex.lineOf(commentStart);
            int prevLine = lineIndex.lineOf(Math.max(0, prevEnd - 1));
            if (prevLine != commentLine || containsTabBetween(prevEnd, commentStart)) {
                return 1;
            }
            int authoredSpaces = Math.max(0, commentStart - prevEnd);
            int origCol = lineIndex.colOf(commentStart);
            boolean anchored = authoredSpaces >= 2
                || Objects.equals(trailingCommentColByLine.get(commentLine - 1), origCol)
                || Objects.equals(trailingCommentColByLine.get(commentLine + 1), origCol)
                || (peerCols != null && peerCols.getOrDefault(origCol, 0) >= 2);
            if (!anchored) {
                return 1;
            }
            return Math.max(1, origCol - simCol);
        }

        private void emitHorizontalSpaces(int spaces, ASTNode nextNode) {
            if (lastEmittedUnit != null && lastEmittedUnit.getElementType() == StvnTypes.COMMENT) {
                emitNewLine(0, false, nextNode);
                return;
            }
            ASTNode targetChild2 = resolveSpacingTarget(firstLeaf(nextNode));
            if (targetChild2 != null) {
                Spacing sp = spaces == 0
                    ? ZERO_SPACES
                    : (spaces == 1 ? ONE_SPACE : Spacing.createSpacing(spaces, spaces, 0, false, 0));
                spacingMap.put(targetChild2, sp);
            }
            simCol += spaces;
        }

        private void emitNewLine(int indentLevel, boolean blankLine, ASTNode nextNode) {
            ASTNode targetChild2 = resolveSpacingTarget(firstLeaf(nextNode));
            if (targetChild2 != null) {
                spacingMap.put(targetChild2, blankLine ? LINE_BREAK_BLANK : LINE_BREAK_ONE);
            }
            simCol = Math.max(0, indentLevel) * 2;
        }

        private void markLineAnchor(ASTNode node) {
            currentOutputLineAnchored = lineStartCodeLeaves.contains(firstLeaf(node));
        }

        private void advanceOverAtomic(ASTNode atomicLeafOrToken) {
            String text = atomicLeafOrToken.getText();
            advanceSimColByText(text);
            lastEmittedUnit = atomicLeafOrToken;
        }

        private void advanceOverCompoundAtomic(ASTNode compoundNode) {
            List<ASTNode> leaves = new ArrayList<>();
            collectCodeLeaves(compoundNode, leaves);
            for (int i = 0; i < leaves.size(); i++) {
                ASTNode leaf = leaves.get(i);
                if (i > 0) {
                    emitHorizontalSpaces(0, leaf);
                }
                advanceOverAtomic(leaf);
            }
        }

        private void advanceSimColByText(String text) {
            String normalized = text.indexOf('\r') >= 0 ? text.replace("\r\n", "\n").replace('\r', '\n') : text;
            int lastNl = normalized.lastIndexOf('\n');
            if (lastNl < 0) {
                simCol += normalized.length();
            } else {
                simCol = normalized.length() - (lastNl + 1);
            }
        }

        private void collectCodeLeaves(ASTNode node, List<ASTNode> out) {
            IElementType t = node.getElementType();
            if (t == TokenType.WHITE_SPACE || t == StvnTypes.COMMENT) {
                return;
            }
            if (isFormattedLeaf(node)) {
                out.add(node);
                return;
            }
            for (ASTNode c = node.getFirstChildNode(); c != null; c = c.getTreeNext()) {
                collectCodeLeaves(c, out);
            }
        }

        private void collectOrderedUnits(ASTNode node) {
            IElementType t = node.getElementType();
            if (t == TokenType.WHITE_SPACE) {
                return;
            }
            if (t == StvnTypes.COMMENT || isFormattedLeaf(node)) {
                allCodeAndComments.add(node);
                return;
            }
            for (ASTNode c = node.getFirstChildNode(); c != null; c = c.getTreeNext()) {
                collectOrderedUnits(c);
            }
        }

        private boolean hasUnconsumedCommentBefore(int limitOffset) {
            return nextCommentIdx < comments.size() && comments.get(nextCommentIdx).getStartOffset() < limitOffset;
        }

        private boolean containsTabBetween(int start, int end) {
            int lo = Math.max(0, start);
            int hi = Math.min(chars.length(), end);
            for (int i = lo; i < hi; i++) {
                if (chars.charAt(i) == '\t') {
                    return true;
                }
            }
            return false;
        }

        private boolean isTabPrecededOnSameLine(int offset) {
            for (int i = offset - 1; i >= 0; i--) {
                char c = chars.charAt(i);
                if (c == '\n' || c == '\r') {
                    return false;
                }
                if (c == '\t') {
                    return true;
                }
                if (c != ' ') {
                    return false;
                }
            }
            return false;
        }

        private static @Nullable ASTNode resolveSpacingTarget(ASTNode nextLeaf) {
            ASTNode cur = nextLeaf;
            while (cur != null) {
                ASTNode prev = cur.getTreePrev();
                while (prev != null && prev.getElementType() == TokenType.WHITE_SPACE) {
                    prev = prev.getTreePrev();
                }
                if (prev != null) {
                    return cur;
                }
                cur = cur.getTreeParent();
            }
            return null;
        }

        private static ASTNode firstLeaf(ASTNode node) {
            ASTNode cur = node;
            while (!isFormattedLeaf(cur)) {
                ASTNode first = null;
                for (ASTNode c = cur.getFirstChildNode(); c != null; c = c.getTreeNext()) {
                    if (c.getElementType() != TokenType.WHITE_SPACE) {
                        first = c;
                        break;
                    }
                }
                if (first == null) {
                    return cur;
                }
                cur = first;
            }
            return cur;
        }

        private static boolean isFormattedLeaf(ASTNode node) {
            IElementType t = node.getElementType();
            return t == StvnTypes.LITERAL_STRING_FENCED
                || t == StvnTypes.LITERAL_STRING_SIMPLE
                || t == StvnTypes.MALFORMED_FENCE
                || t == StvnTypes.COMMENT
                || node.getFirstChildNode() == null;
        }

        private static @Nullable ASTNode firstCodeChild(ASTNode parent) {
            for (ASTNode c = parent.getFirstChildNode(); c != null; c = c.getTreeNext()) {
                if (c.getElementType() != TokenType.WHITE_SPACE && c.getElementType() != StvnTypes.COMMENT) {
                    return c;
                }
            }
            return null;
        }

        private static @Nullable ASTNode nextCodeSibling(ASTNode node) {
            for (ASTNode c = node.getTreeNext(); c != null; c = c.getTreeNext()) {
                if (c.getElementType() != TokenType.WHITE_SPACE && c.getElementType() != StvnTypes.COMMENT) {
                    return c;
                }
            }
            return null;
        }
    }

    private static final class Objects {
        static boolean equals(@Nullable Object a, @Nullable Object b) {
            return java.util.Objects.equals(a, b);
        }
    }
}
