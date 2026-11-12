package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
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
import org.stvnadore.psi.StvnTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Reorders {@code :meta} entries into canonical {@code #name -> #domain -> #kind} order
 * while preserving all leading, same-line trailing, and footer {@code //} comments.
 */
@NullMarked
public final class StvnReorderMetaEntriesQuickFix implements LocalQuickFix, IntentionAction, PriorityAction {

    @SafeFieldForPreview
    private final @Nullable SmartPsiElementPointer<MetaBlock> metaBlockPointer;

    /**
     * Constructs a reordering quick-fix without a pre-bound PSI reference.
     */
    public StvnReorderMetaEntriesQuickFix() {
        this.metaBlockPointer = null;
    }

    /**
     * Constructs a reordering quick-fix for the specified {@code :meta} block.
     *
     * @param metaBlock the {@code :meta} block containing unordered entries
     */
    public StvnReorderMetaEntriesQuickFix(@Nullable MetaBlock metaBlock) {
        this.metaBlockPointer = metaBlock != null && metaBlock.isValid()
            ? SmartPointerManager.getInstance(metaBlock.getProject()).createSmartPsiElementPointer(metaBlock)
            : null;
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
        return Priority.NORMAL;
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        MetaBlock metaBlock = metaBlockPointer != null ? metaBlockPointer.getElement() : null;
        return metaBlock != null && metaBlock.isValid();
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        MetaBlock metaBlock = metaBlockPointer != null ? metaBlockPointer.getElement() : null;
        if (metaBlock != null && metaBlock.isValid()) {
            reorderMetaBlock(project, metaBlock);
        }
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        MetaBlock original = metaBlockPointer != null ? metaBlockPointer.getElement() : null;
        if (original != null && original.isValid()) {
            MetaBlock copyBlock = PsiTreeUtil.findSameElementInCopy(original, file);
            reorderMetaBlock(project, copyBlock);
            return IntentionPreviewInfo.DIFF;
        }
        PsiElement atCaret = file.findElementAt(editor.getCaretModel().getOffset());
        MetaBlock copyAtCaret = PsiTreeUtil.getParentOfType(atCaret, MetaBlock.class, false);
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
        PsiElement previewElement = previewDescriptor.getPsiElement();
        MetaBlock copyBlock = previewElement instanceof MetaBlock mb
            ? mb
            : PsiTreeUtil.getParentOfType(previewElement, MetaBlock.class, false);
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

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement targetElement = descriptor.getPsiElement();
        MetaBlock targetBlock = targetElement instanceof MetaBlock mb
            ? mb
            : PsiTreeUtil.getParentOfType(targetElement, MetaBlock.class, false);
        MetaBlock metaBlock = metaBlockPointer != null ? metaBlockPointer.getElement() : null;
        if (targetBlock == null && metaBlock != null && metaBlock.isValid()) {
            targetBlock = metaBlock;
        }
        if (targetBlock != null && targetBlock.isValid()) {
            reorderMetaBlock(project, targetBlock);
        }
    }

    record CommentLine(
        String text,
        boolean blankLineBefore
    ) {}

    record CommentedEntrySlice(
        int rank,
        int originalIndex,
        List<CommentLine> leadingComments,
        boolean hadBlankLineBefore,
        boolean blankLineBeforeEntry,
        String entryText,
        @Nullable String sameLineComment
    ) {}

    record PartitionedBlockInterior(
        @Nullable String openBraceSameLineComment,
        List<CommentedEntrySlice> slices,
        List<CommentLine> footerComments,
        boolean footerHadBlankLineBefore,
        boolean hasAnyComment
    ) {}

    /**
     * Sorts the entries of the given {@code :meta} block in-place into canonical
     * {@code #name -> #domain -> #kind} order while preserving all leading, same-line trailing,
     * and footer {@code //} comments.
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
        String baseIndent = detectElementLineIndent(block, "  ");
        String entryIndent = detectEntryIndent(rawBlockText, baseIndent + "  ");
        String closingIndent = detectClosingBraceIndent(rawBlockText, baseIndent);

        String rebuiltText = renderSortedBlockText(
            partitioned.openBraceSameLineComment(),
            sorted,
            partitioned.footerComments(),
            partitioned.footerHadBlankLineBefore(),
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
            return new PartitionedBlockInterior(null, List.of(), List.of(), false, false);
        }

        boolean hasAnyComment = false;
        String openBraceComment = extractSameLineTrailingComment(lbrace);
        ASTNode cursor = lbrace.getTreeNext();
        if (openBraceComment != null) {
            hasAnyComment = true;
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
        boolean sliceHadBlankLineBefore = false;
        boolean nextItemHasBlankLineBefore = false;
        boolean seenNewlineAfterPrevAnchor = openBraceComment == null;
        int entryIdx = 0;

        while (cursor != null && cursor != rbrace) {
            var type = cursor.getElementType();
            if (type == TokenType.WHITE_SPACE) {
                int newlines = countNewlines(cursor.getText());
                if (newlines >= 2 || (!seenNewlineAfterPrevAnchor && newlines >= 1 && !pendingComments.isEmpty())) {
                    if (pendingComments.isEmpty()) {
                        sliceHadBlankLineBefore = true;
                    } else {
                        nextItemHasBlankLineBefore = true;
                    }
                }
                if (newlines >= 1) {
                    seenNewlineAfterPrevAnchor = true;
                }
                cursor = cursor.getTreeNext();
            } else if (type == StvnTypes.COMMENT) {
                hasAnyComment = true;
                pendingComments.add(new CommentLine(
                    cursor.getText().trim(),
                    !pendingComments.isEmpty() && nextItemHasBlankLineBefore
                ));
                nextItemHasBlankLineBefore = false;
                seenNewlineAfterPrevAnchor = false;
                cursor = cursor.getTreeNext();
            } else if (type == entryElementType) {
                String trailing = extractSameLineTrailingComment(cursor);
                ASTNode next = cursor.getTreeNext();
                if (trailing != null) {
                    hasAnyComment = true;
                    while (next != null && next != rbrace) {
                        if (next.getElementType() == StvnTypes.COMMENT) {
                            next = next.getTreeNext();
                            break;
                        }
                        next = next.getTreeNext();
                    }
                }
                slices.add(new CommentedEntrySlice(
                    ranker.applyAsInt(cursor),
                    entryIdx++,
                    List.copyOf(pendingComments),
                    sliceHadBlankLineBefore,
                    !pendingComments.isEmpty() && nextItemHasBlankLineBefore,
                    cursor.getText().trim(),
                    trailing
                ));
                pendingComments.clear();
                sliceHadBlankLineBefore = false;
                nextItemHasBlankLineBefore = false;
                seenNewlineAfterPrevAnchor = false;
                cursor = next;
            } else {
                cursor = cursor.getTreeNext();
            }
        }

        return new PartitionedBlockInterior(
            openBraceComment,
            slices,
            List.copyOf(pendingComments),
            sliceHadBlankLineBefore,
            hasAnyComment
        );
    }

    static String renderSortedBlockText(
            @Nullable String openBraceSameLineComment,
            List<CommentedEntrySlice> sortedSlices,
            List<CommentLine> footerComments,
            boolean footerHadBlankLineBefore,
            boolean multiLine,
            String entryIndent,
            String closingIndent
    ) {
        if (!multiLine) {
            StringBuilder inline = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACE);
            for (CommentedEntrySlice slice : sortedSlices) {
                inline.append(' ').append(slice.entryText());
            }
            inline.append(' ').append(StvnVocabulary.DELIM_CLOSE_BRACE);
            return inline.toString();
        }

        StringBuilder sb = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACE);
        if (openBraceSameLineComment != null) {
            sb.append(openBraceSameLineComment);
        }
        sb.append('\n');

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
            sb.append(entryIndent).append(slice.entryText());
            if (slice.sameLineComment() != null) {
                sb.append(slice.sameLineComment());
            }
            sb.append('\n');
        }

        if (!footerComments.isEmpty()) {
            if (footerHadBlankLineBefore && !sortedSlices.isEmpty()) {
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

    static @Nullable String extractSameLineTrailingComment(ASTNode entryNode) {
        StringBuilder wsBeforeComment = new StringBuilder();
        for (ASTNode next = entryNode.getTreeNext(); next != null; next = next.getTreeNext()) {
            var type = next.getElementType();
            if (type == TokenType.WHITE_SPACE) {
                String text = next.getText();
                if (text.contains("\n")) {
                    return null;
                }
                wsBeforeComment.append(text);
            } else if (type == StvnTypes.COMMENT) {
                String prefix = wsBeforeComment.isEmpty() ? " " : wsBeforeComment.toString();
                return prefix + next.getText().trim();
            } else {
                return null;
            }
        }
        return null;
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

    static String detectEntryIndent(String blockText, String fallback) {
        String[] lines = blockText.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("}") && !trimmed.startsWith("//")) {
                int firstNonWs = 0;
                while (firstNonWs < line.length() && (line.charAt(firstNonWs) == ' ' || line.charAt(firstNonWs) == '\t')) {
                    firstNonWs++;
                }
                return line.substring(0, firstNonWs);
            }
        }
        return fallback;
    }

    static String detectClosingBraceIndent(String blockText, String fallback) {
        int lastNewline = blockText.lastIndexOf('\n');
        if (lastNewline >= 0) {
            String lastLine = blockText.substring(lastNewline + 1);
            int braceIdx = lastLine.indexOf('}');
            if (braceIdx >= 0) {
                String prefix = lastLine.substring(0, braceIdx);
                boolean whitespaceOnly = true;
                for (int i = 0; i < prefix.length(); i++) {
                    char c = prefix.charAt(i);
                    if (c != ' ' && c != '\t') {
                        whitespaceOnly = false;
                        break;
                    }
                }
                if (whitespaceOnly) {
                    return prefix;
                }
            }
        }
        return fallback;
    }
}
