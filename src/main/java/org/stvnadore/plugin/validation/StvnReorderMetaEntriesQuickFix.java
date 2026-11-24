package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetaElement;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.StvnTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Reorders {@code :meta} entries into canonical {@code #name -> #domain -> #kind} order
 * while preserving leading, peer-anchored same-line trailing, and footer {@code //} comments
 * as well as Law 3 {@code #facet -> arg} target columns.
 */
@NullMarked
public final class StvnReorderMetaEntriesQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
        implements PriorityAction {

    /**
     * Constructs a reordering quick-fix without a pre-bound PSI reference.
     */
    public StvnReorderMetaEntriesQuickFix() {
        super(null);
    }

    /**
     * Constructs a reordering quick-fix for the specified {@code :meta} block.
     *
     * @param metaBlock the {@code :meta} block containing unordered entries
     */
    public StvnReorderMetaEntriesQuickFix(@Nullable MetaBlock metaBlock) {
        super(metaBlock);
    }

    @Override
    public @NotNull String getText() {
        return getFamilyName();
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Reorder '" + StvnVocabulary.KEYWORD_META + "' entries to canonical order ("
            + StvnVocabulary.FACET_KW_META_NAME + ", "
            + StvnVocabulary.FACET_KW_META_DOMAIN + ", "
            + StvnVocabulary.FACET_KW_META_KIND + ")";
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.HIGH;
    }

    @Override
    public boolean isAvailable(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        return startElement.isValid();
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        MetaBlock targetBlock = startElement instanceof MetaBlock mb ? mb : resolveTargetMetaBlock(startElement);
        if (targetBlock != null && targetBlock.isValid()) {
            reorderMetaBlock(project, targetBlock);
        }
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        PsiElement original = getStartElement();
        if (original instanceof MetaBlock mb && mb.isValid()) {
            MetaBlock copyBlock = PsiTreeUtil.findSameElementInCopy(mb, file);
            reorderMetaBlock(project, copyBlock);
            return IntentionPreviewInfo.DIFF;
        }
        PsiElement atCaret = file.findElementAt(editor.getCaretModel().getOffset());
        MetaBlock copyAtCaret = resolveTargetMetaBlock(atCaret);
        if (copyAtCaret != null && copyAtCaret.isValid()) {
            reorderMetaBlock(project, copyAtCaret);
            return IntentionPreviewInfo.DIFF;
        }
        return IntentionPreviewInfo.EMPTY;
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull ProblemDescriptor previewDescriptor
    ) {
        MetaBlock copyBlock = resolveTargetMetaBlock(previewDescriptor.getPsiElement());
        if (copyBlock == null || !copyBlock.isValid()) {
            return IntentionPreviewInfo.EMPTY;
        }
        reorderMetaBlock(project, copyBlock);
        return IntentionPreviewInfo.DIFF;
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }

    static @Nullable MetaBlock resolveTargetMetaBlock(@Nullable PsiElement anchor) {
        if (anchor == null) {
            return null;
        }
        MetaBlock enclosingBlock = PsiTreeUtil.getParentOfType(anchor, MetaBlock.class, false);
        if (enclosingBlock != null) {
            return enclosingBlock;
        }
        MetaEntry metaEntry = PsiTreeUtil.getParentOfType(anchor, MetaEntry.class, false);
        return metaEntry != null ? metaEntry.getMetaBlock() : null;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof StvnReorderMetaEntriesQuickFix other)) {
            return false;
        }
        return Objects.equals(getStartElement(), other.getStartElement());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getStartElement());
    }

    record CommentLine(
        String text,
        boolean blankLineBefore
    ) {}

    record TrailingCommentCapture(
        String commentText,
        int authoredSpaces,
        int origCol,
        int origLine,
        boolean tabTainted
    ) {}

    record CommentedEntrySlice(
        int rank,
        int originalIndex,
        List<CommentLine> leadingComments,
        boolean hadBlankLineBefore,
        boolean blankLineBeforeEntry,
        String keyText,
        @Nullable String argText,
        boolean lineAnchored,
        int authoredArgSpaces,
        int origArgTargetCol,
        boolean argTabTainted,
        @Nullable TrailingCommentCapture trailingComment
    ) {}

    record PartitionedBlockInterior(
        @Nullable String openBraceSameLineComment,
        List<CommentedEntrySlice> slices,
        List<CommentLine> footerComments,
        boolean footerHadBlankLineBefore,
        boolean hasAnyComment,
        Map<Integer, Integer> peerTrailingCommentColCounts,
        Map<Integer, Integer> fileTrailingCommentColByLine
    ) {}

    /**
     * Sorts the entries of the given {@code :meta} block in-place into canonical
     * {@code #name -> #domain -> #kind} order while preserving comments and target columns.
     *
     * @param project the active project
     * @param block the {@code :meta} block to reorder
     */
    public static void reorderMetaBlock(@NotNull Project project, @NotNull MetaBlock block) {
        List<MetaElement> elements = block.getMetaElementList();
        if (elements.size() <= 1 || PsiTreeUtil.hasErrorElements(block)) {
            return;
        }

        PartitionedBlockInterior partitioned = partitionBlockInterior(
            block.getNode(),
            StvnTypes.META_ELEMENT,
            node -> node.getPsi() instanceof MetaElement me
                ? StvnMetadataOrderInspection.getMetaElementRank(me)
                : 4
        );
        if (partitioned.slices().size() <= 1 || isAlreadySorted(partitioned.slices())) {
            return;
        }

        List<CommentedEntrySlice> sorted = new ArrayList<>(partitioned.slices());
        sorted.sort(Comparator.comparingInt(CommentedEntrySlice::rank)
            .thenComparingInt(CommentedEntrySlice::originalIndex));

        String rawBlockText = block.getText();
        boolean multiLine = rawBlockText.contains("\n") || partitioned.hasAnyComment();
        String baseIndent = normalizeIndentSpaces(detectElementLineIndent(block, "  "), "  ");
        String entryIndent = baseIndent + "  ";
        String closingIndent = baseIndent;

        String rebuiltText = renderSortedBlockText(
            partitioned,
            sorted,
            multiLine,
            entryIndent,
            closingIndent
        );
        MetaBlock replacement = StvnElementFactory.createMetaBlock(project, rebuiltText);
        replaceMetaBlockInteriorInPlace(block, replacement);
    }

    static boolean isAlreadySorted(List<CommentedEntrySlice> slices) {
        int maxRank = Integer.MIN_VALUE;
        for (CommentedEntrySlice slice : slices) {
            if (slice.rank() < maxRank) {
                return false;
            }
            maxRank = slice.rank();
        }
        return true;
    }

    static PartitionedBlockInterior partitionBlockInterior(
            ASTNode blockNode,
            IElementType entryElementType,
            ToIntFunction<ASTNode> ranker
    ) {
        ASTNode lbrace = blockNode.findChildByType(StvnTypes.LBRACE);
        ASTNode rbrace = blockNode.findChildByType(StvnTypes.RBRACE);
        if (lbrace == null || rbrace == null) {
            return new PartitionedBlockInterior(null, List.of(), List.of(), false, false, Map.of(), Map.of());
        }

        PsiElement blockPsi = blockNode.getPsi();
        PsiFile file = blockPsi != null ? blockPsi.getContainingFile() : null;
        CharSequence docChars = file != null ? file.getViewProvider().getContents() : blockNode.getText();
        int coordShift = file != null ? 0 : -blockNode.getStartOffset();
        Map<Integer, Integer> fileTrailingByLine = collectFileTrailingCommentColsByLine(file, blockNode, docChars);

        boolean hasAnyComment = false;
        TrailingCommentCapture openCapture = extractSameLineTrailingCommentCapture(lbrace, docChars, coordShift);
        String openBraceComment = null;
        ASTNode cursor = lbrace.getTreeNext();
        if (openCapture != null) {
            hasAnyComment = true;
            int openBraceEndCol = columnOf(docChars, lbrace.getTextRange().getEndOffset() + coordShift);
            boolean anchorOpen = !openCapture.tabTainted() && isTrailingCommentAnchored(
                openCapture,
                Map.of(),
                fileTrailingByLine
            );
            int openSpaces = anchorOpen
                ? Math.max(1, openCapture.origCol() - openBraceEndCol)
                : 1;
            openBraceComment = " ".repeat(openSpaces) + openCapture.commentText();
            while (cursor != null && cursor != rbrace) {
                if (cursor.getElementType() == StvnTypes.COMMENT) {
                    cursor = cursor.getTreeNext();
                    break;
                }
                cursor = cursor.getTreeNext();
            }
        }

        List<CommentedEntrySlice> slices = new ArrayList<>();
        List<CommentLine> pendingComments = new ArrayList<>();
        Map<Integer, Integer> peerColCounts = new HashMap<>();
        boolean sliceHadBlankLineBefore = false;
        boolean nextItemHasBlankLineBefore = false;
        int newlinesSincePrevItem = 0;
        int entryIdx = 0;

        while (cursor != null && cursor != rbrace) {
            var type = cursor.getElementType();
            if (type == TokenType.WHITE_SPACE) {
                newlinesSincePrevItem += countNewlines(cursor.getText());
                if (newlinesSincePrevItem >= 2) {
                    if (pendingComments.isEmpty()) {
                        if (entryIdx > 0) {
                            sliceHadBlankLineBefore = true;
                        }
                    } else {
                        nextItemHasBlankLineBefore = true;
                    }
                }
                cursor = cursor.getTreeNext();
            } else if (type == StvnTypes.COMMENT) {
                hasAnyComment = true;
                pendingComments.add(new CommentLine(
                    cursor.getText().trim(),
                    !pendingComments.isEmpty() && nextItemHasBlankLineBefore
                ));
                nextItemHasBlankLineBefore = false;
                newlinesSincePrevItem = 0;
                cursor = cursor.getTreeNext();
            } else if (type == entryElementType) {
                TrailingCommentCapture trailing = extractSameLineTrailingCommentCapture(cursor, docChars, coordShift);
                ASTNode next = cursor.getTreeNext();
                if (trailing != null) {
                    hasAnyComment = true;
                    peerColCounts.merge(trailing.origCol(), 1, Integer::sum);
                    while (next != null && next != rbrace) {
                        if (next.getElementType() == StvnTypes.COMMENT) {
                            next = next.getTreeNext();
                            break;
                        }
                        next = next.getTreeNext();
                    }
                }
                EntryKeyArgSplit split = splitEntryKeyAndArgument(cursor, docChars, coordShift);
                slices.add(new CommentedEntrySlice(
                    ranker.applyAsInt(cursor),
                    entryIdx++,
                    List.copyOf(pendingComments),
                    sliceHadBlankLineBefore,
                    !pendingComments.isEmpty() && nextItemHasBlankLineBefore,
                    split.keyText(),
                    split.argText(),
                    split.lineAnchored(),
                    split.authoredArgSpaces(),
                    split.origArgTargetCol(),
                    split.argTabTainted(),
                    trailing
                ));
                pendingComments.clear();
                sliceHadBlankLineBefore = false;
                nextItemHasBlankLineBefore = false;
                newlinesSincePrevItem = 0;
                cursor = next;
            } else {
                newlinesSincePrevItem += countNewlines(cursor.getText());
                cursor = cursor.getTreeNext();
            }
        }

        return new PartitionedBlockInterior(
            openBraceComment,
            slices,
            List.copyOf(pendingComments),
            sliceHadBlankLineBefore,
            hasAnyComment,
            Map.copyOf(peerColCounts),
            fileTrailingByLine
        );
    }

    static String renderSortedBlockText(
            PartitionedBlockInterior partitioned,
            List<CommentedEntrySlice> sortedSlices,
            boolean multiLine,
            String entryIndent,
            String closingIndent
    ) {
        if (!multiLine) {
            StringBuilder inline = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACE);
            for (CommentedEntrySlice slice : sortedSlices) {
                inline.append(' ').append(slice.keyText());
                if (slice.argText() != null) {
                    inline.append(' ').append(slice.argText());
                }
            }
            inline.append(' ').append(StvnVocabulary.DELIM_CLOSE_BRACE);
            return inline.toString();
        }

        StringBuilder sb = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACE);
        if (partitioned.openBraceSameLineComment() != null) {
            sb.append(partitioned.openBraceSameLineComment());
        }
        sb.append('\n');

        Map<Integer, Integer> peerCols = partitioned.peerTrailingCommentColCounts();
        Map<Integer, Integer> fileColsByLine = partitioned.fileTrailingCommentColByLine();

        for (int i = 0; i < sortedSlices.size(); i++) {
            CommentedEntrySlice slice = sortedSlices.get(i);
            if (i > 0 && slice.hadBlankLineBefore()) {
                sb.append('\n');
            }
            for (CommentLine commentLine : slice.leadingComments()) {
                if (commentLine.blankLineBefore()) {
                    sb.append('\n');
                }
                sb.append(entryIndent).append(commentLine.text()).append('\n');
            }
            if (slice.blankLineBeforeEntry()) {
                sb.append('\n');
            }
            int lineStartIdx = sb.length();
            sb.append(entryIndent).append(slice.keyText());
            if (slice.argText() != null) {
                int postKeyCol = entryIndent.length() + slice.keyText().length();
                int argSpaces = (slice.lineAnchored() && !slice.argTabTainted() && slice.authoredArgSpaces() >= 2)
                    ? Math.max(1, slice.origArgTargetCol() - postKeyCol)
                    : 1;
                sb.append(" ".repeat(argSpaces)).append(slice.argText());
            }
            TrailingCommentCapture tc = slice.trailingComment();
            if (tc != null) {
                int lastNl = sb.lastIndexOf("\n");
                int postEntryEndCol = sb.length() - (lastNl >= lineStartIdx ? lastNl + 1 : lineStartIdx);
                boolean anchorComment = slice.lineAnchored()
                    && !tc.tabTainted()
                    && isTrailingCommentAnchored(tc, peerCols, fileColsByLine);
                int commentSpaces = anchorComment
                    ? Math.max(1, tc.origCol() - postEntryEndCol)
                    : 1;
                sb.append(" ".repeat(commentSpaces)).append(tc.commentText());
            }
            sb.append('\n');
        }

        List<CommentLine> footerComments = partitioned.footerComments();
        if (!footerComments.isEmpty()) {
            if (partitioned.footerHadBlankLineBefore() && !sortedSlices.isEmpty()) {
                sb.append('\n');
            }
            for (CommentLine footerLine : footerComments) {
                if (footerLine.blankLineBefore()) {
                    sb.append('\n');
                }
                sb.append(entryIndent).append(footerLine.text()).append('\n');
            }
        }

        sb.append(closingIndent).append(StvnVocabulary.DELIM_CLOSE_BRACE);
        return sb.toString();
    }

    private record EntryKeyArgSplit(
        String keyText,
        @Nullable String argText,
        boolean lineAnchored,
        int authoredArgSpaces,
        int origArgTargetCol,
        boolean argTabTainted
    ) {}

    private static EntryKeyArgSplit splitEntryKeyAndArgument(
            ASTNode entryNode,
            CharSequence docChars,
            int coordShift
    ) {
        ASTNode keyEndNode = resolveFacetKeywordUnit(entryNode);
        if (keyEndNode == null) {
            String raw = entryNode.getText().trim();
            return new EntryKeyArgSplit(raw, null, false, 0, 0, false);
        }
        int keyStartOff = keyEndNode.getTextRange().getStartOffset() + coordShift;
        int keyEndOff = keyEndNode.getTextRange().getEndOffset() + coordShift;
        String keyText = keyEndNode.getText().trim();
        boolean lineAnchored = isFirstCodeTokenOnLine(docChars, keyStartOff);

        ASTNode argNode = findFirstNonWhitespaceNonCommentAfter(entryNode, keyEndNode);
        if (argNode == null) {
            return new EntryKeyArgSplit(keyText, null, lineAnchored, 0, 0, false);
        }
        int argStartOff = argNode.getTextRange().getStartOffset() + coordShift;
        boolean sameLine = !containsNewline(docChars, keyEndOff, argStartOff);
        boolean tabTainted = containsTab(docChars, keyEndOff, argStartOff);
        boolean hasInterveningComment = hasCommentBetween(entryNode, keyEndNode, argNode);
        int authoredArgSpaces = (sameLine && !hasInterveningComment) ? Math.max(0, argStartOff - keyEndOff) : 1;
        int origTargetCol = columnOf(docChars, argStartOff);
        String formattedArg = formatFacetArgument(entryNode, argNode);
        return new EntryKeyArgSplit(
            keyText,
            formattedArg,
            lineAnchored && sameLine && !hasInterveningComment,
            authoredArgSpaces,
            origTargetCol,
            tabTainted
        );
    }

    private static @Nullable ASTNode resolveFacetKeywordUnit(ASTNode entryNode) {
        ASTNode firstChild = firstNonWsChild(entryNode);
        if (firstChild == null) {
            return null;
        }
        IElementType t = firstChild.getElementType();
        if (t == StvnTypes.VALUE_KEYWORD) {
            return firstChild;
        }
        if (firstChild.getFirstChildNode() != null && t != StvnTypes.METADATA_BARE_FLAG && t != StvnTypes.METADATA_DIRECTIVE) {
            ASTNode nestedFirst = firstNonWsChild(firstChild);
            if (nestedFirst != null) {
                return nestedFirst;
            }
        }
        return firstChild;
    }

    private static @Nullable ASTNode findFirstNonWhitespaceNonCommentAfter(ASTNode entryNode, ASTNode keyUnit) {
        ASTNode cur = keyUnit;
        while (cur != null && cur != entryNode) {
            for (ASTNode next = cur.getTreeNext(); next != null; next = next.getTreeNext()) {
                if (next.getElementType() != TokenType.WHITE_SPACE && next.getElementType() != StvnTypes.COMMENT) {
                    return next;
                }
            }
            cur = cur.getTreeParent();
        }
        return null;
    }

    private static boolean hasCommentBetween(ASTNode entryNode, ASTNode keyUnit, ASTNode argNode) {
        int start = keyUnit.getTextRange().getEndOffset();
        int end = argNode.getTextRange().getStartOffset();
        return containsCommentInOffsetWindow(entryNode, start, end);
    }

    private static boolean containsCommentInOffsetWindow(ASTNode root, int startOffset, int endOffset) {
        for (ASTNode child = root.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            TextRange r = child.getTextRange();
            if (r.getEndOffset() <= startOffset || r.getStartOffset() >= endOffset) {
                continue;
            }
            if (child.getElementType() == StvnTypes.COMMENT) {
                return true;
            }
            if (containsCommentInOffsetWindow(child, startOffset, endOffset)) {
                return true;
            }
        }
        return false;
    }

    private static String formatFacetArgument(ASTNode entryNode, ASTNode firstArgNode) {
        if (firstArgNode.getElementType() == StvnTypes.VARIANT_LIST) {
            StringBuilder b = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACKET);
            boolean first = true;
            for (ASTNode c = firstArgNode.getFirstChildNode(); c != null; c = c.getTreeNext()) {
                IElementType ct = c.getElementType();
                if (ct == TokenType.WHITE_SPACE || ct == StvnTypes.LBRACK || ct == StvnTypes.RBRACK || ct == StvnTypes.COMMENT) {
                    continue;
                }
                if (!first) {
                    b.append(' ');
                }
                b.append(c.getText().trim());
                first = false;
            }
            b.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
            return b.toString();
        }
        int start = firstArgNode.getTextRange().getStartOffset() - entryNode.getStartOffset();
        String rawTail = entryNode.getText().substring(Math.max(0, start)).trim();
        if (firstArgNode.findChildByType(StvnTypes.LITERAL_STRING_FENCED) != null
                || firstArgNode.getElementType() == StvnTypes.LITERAL_STRING_FENCED
                || firstArgNode.getElementType() == StvnTypes.STRING_LITERAL) {
            return rawTail.replace("\r\n", "\n").replace('\r', '\n');
        }
        return normalizeSingleLineTokensOutsideQuotes(rawTail);
    }

    private static String normalizeSingleLineTokensOutsideQuotes(String text) {
        if (text.indexOf('\n') >= 0) {
            return text.replace("\r\n", "\n").replace('\r', '\n');
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean inQuotes = false;
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                sb.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    sb.append(text.charAt(++i));
                } else if (c == '"') {
                    inQuotes = false;
                }
            } else if (c == '"') {
                if (pendingSpace && !sb.isEmpty()) {
                    char prev = sb.charAt(sb.length() - 1);
                    if (prev != '(' && prev != '[') {
                        sb.append(' ');
                    }
                }
                pendingSpace = false;
                inQuotes = true;
                sb.append(c);
            } else if (c == ' ' || c == '\t') {
                pendingSpace = true;
            } else {
                if (pendingSpace && !sb.isEmpty()) {
                    char prev = sb.charAt(sb.length() - 1);
                    if (prev != '(' && prev != '[' && c != ')' && c != ']') {
                        sb.append(' ');
                    } else if (prev == '{' || c == '}') {
                        sb.append(' ');
                    }
                }
                pendingSpace = false;
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static @Nullable ASTNode firstNonWsChild(ASTNode node) {
        for (ASTNode c = node.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            if (c.getElementType() != TokenType.WHITE_SPACE && c.getElementType() != StvnTypes.COMMENT) {
                return c;
            }
        }
        return null;
    }

    private static boolean isTrailingCommentAnchored(
            TrailingCommentCapture tc,
            Map<Integer, Integer> peerCols,
            Map<Integer, Integer> fileColsByLine
    ) {
        if (tc.authoredSpaces() >= 2) {
            return true;
        }
        if (peerCols.getOrDefault(tc.origCol(), 0) >= 2) {
            return true;
        }
        Integer prevCol = fileColsByLine.get(tc.origLine() - 1);
        if (prevCol != null && prevCol == tc.origCol()) {
            return true;
        }
        Integer nextCol = fileColsByLine.get(tc.origLine() + 1);
        return nextCol != null && nextCol == tc.origCol();
    }

    private static Map<Integer, Integer> collectFileTrailingCommentColsByLine(
            @Nullable PsiFile file,
            ASTNode fallbackRoot,
            CharSequence docChars
    ) {
        ASTNode root = file != null ? file.getNode() : fallbackRoot;
        int shift = file != null ? 0 : -fallbackRoot.getStartOffset();
        Map<Integer, Integer> result = new HashMap<>();
        int[] lastCodeLine = {-1};
        collectTrailingCommentColsRec(root, docChars, shift, lastCodeLine, result);
        return Map.copyOf(result);
    }

    private static void collectTrailingCommentColsRec(
            ASTNode node,
            CharSequence docChars,
            int shift,
            int[] lastCodeLine,
            Map<Integer, Integer> out
    ) {
        IElementType type = node.getElementType();
        if (type == TokenType.WHITE_SPACE) {
            return;
        }
        if (type == StvnTypes.COMMENT) {
            int start = node.getTextRange().getStartOffset() + shift;
            int line = lineOf(docChars, start);
            if (lastCodeLine[0] == line) {
                out.put(line, columnOf(docChars, start));
            }
            return;
        }
        if (node.getFirstChildNode() == null
                || type == StvnTypes.LITERAL_STRING_FENCED
                || type == StvnTypes.LITERAL_STRING_SIMPLE
                || type == StvnTypes.MALFORMED_FENCE) {
            int end = Math.max(0, node.getTextRange().getEndOffset() + shift - 1);
            lastCodeLine[0] = lineOf(docChars, end);
            return;
        }
        for (ASTNode child = node.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            collectTrailingCommentColsRec(child, docChars, shift, lastCodeLine, out);
        }
    }

    static @Nullable TrailingCommentCapture extractSameLineTrailingCommentCapture(
            ASTNode entryNode,
            CharSequence docChars,
            int coordShift
    ) {
        StringBuilder wsBeforeComment = new StringBuilder();
        for (ASTNode next = entryNode.getTreeNext(); next != null; next = next.getTreeNext()) {
            var type = next.getElementType();
            if (type == TokenType.WHITE_SPACE) {
                String text = next.getText();
                if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                    return null;
                }
                wsBeforeComment.append(text);
            } else if (type == StvnTypes.COMMENT) {
                int commentStart = next.getTextRange().getStartOffset() + coordShift;
                String ws = wsBeforeComment.toString();
                return new TrailingCommentCapture(
                    next.getText().trim(),
                    ws.length(),
                    columnOf(docChars, commentStart),
                    lineOf(docChars, commentStart),
                    ws.indexOf('\t') >= 0
                );
            } else {
                return null;
            }
        }
        return null;
    }

    private static boolean isFirstCodeTokenOnLine(CharSequence chars, int tokenStartOffset) {
        if (tokenStartOffset < 0 || tokenStartOffset > chars.length()) {
            return false;
        }
        for (int i = tokenStartOffset - 1; i >= 0; i--) {
            char c = chars.charAt(i);
            if (c == '\n' || c == '\r') {
                return true;
            }
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private static boolean containsNewline(CharSequence chars, int start, int end) {
        int lo = Math.max(0, start);
        int hi = Math.min(chars.length(), end);
        for (int i = lo; i < hi; i++) {
            char c = chars.charAt(i);
            if (c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTab(CharSequence chars, int start, int end) {
        int lo = Math.max(0, start);
        int hi = Math.min(chars.length(), end);
        for (int i = lo; i < hi; i++) {
            if (chars.charAt(i) == '\t') {
                return true;
            }
        }
        return false;
    }

    private static int columnOf(CharSequence chars, int offset) {
        int clamped = Math.max(0, Math.min(offset, chars.length()));
        int lineStart = clamped;
        while (lineStart > 0 && chars.charAt(lineStart - 1) != '\n' && chars.charAt(lineStart - 1) != '\r') {
            lineStart--;
        }
        return clamped - lineStart;
    }

    private static int lineOf(CharSequence chars, int offset) {
        int clamped = Math.max(0, Math.min(offset, chars.length()));
        int line = 0;
        for (int i = 0; i < clamped; i++) {
            if (chars.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String normalizeIndentSpaces(String rawIndent, String fallback) {
        if (rawIndent.indexOf('\t') >= 0) {
            return fallback;
        }
        return rawIndent;
    }

    private static int countNewlines(String ws) {
        int count = 0;
        for (int i = 0; i < ws.length(); i++) {
            if (ws.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static void replaceMetaBlockInteriorInPlace(MetaBlock target, MetaBlock replacement) {
        ASTNode targetNode = target.getNode();
        ASTNode replacementNode = replacement.getNode();
        ASTNode targetRBrace = targetNode.findChildByType(StvnTypes.RBRACE);
        ASTNode targetLBrace = targetNode.findChildByType(StvnTypes.LBRACE);
        if (targetLBrace == null || targetRBrace == null) {
            target.replace(replacement);
            return;
        }
        ASTNode current = targetLBrace.getTreeNext();
        while (current != null && current != targetRBrace) {
            ASTNode next = current.getTreeNext();
            targetNode.removeChild(current);
            current = next;
        }
        ASTNode replLBrace = replacementNode.findChildByType(StvnTypes.LBRACE);
        ASTNode replRBrace = replacementNode.findChildByType(StvnTypes.RBRACE);
        if (replLBrace != null && replRBrace != null) {
            ASTNode replChild = replLBrace.getTreeNext();
            while (replChild != null && replChild != replRBrace) {
                targetNode.addChild(replChild.copyElement(), targetRBrace);
                replChild = replChild.getTreeNext();
            }
        }
    }

    static String detectElementLineIndent(PsiElement element, String fallback) {
        PsiFile file = element.getContainingFile();
        if (file == null) {
            return fallback;
        }
        CharSequence chars = file.getViewProvider().getContents();
        int startOffset = element.getTextRange().getStartOffset();
        if (startOffset < 0 || startOffset > chars.length()) {
            return fallback;
        }
        int lineStart = startOffset;
        while (lineStart > 0 && chars.charAt(lineStart - 1) != '\n' && chars.charAt(lineStart - 1) != '\r') {
            lineStart--;
        }
        int wsEnd = lineStart;
        while (wsEnd < startOffset && (chars.charAt(wsEnd) == ' ' || chars.charAt(wsEnd) == '\t')) {
            wsEnd++;
        }
        return chars.subSequence(lineStart, wsEnd).toString();
    }
}
