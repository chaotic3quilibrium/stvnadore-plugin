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
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.StvnTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sorts metadata facets in a metadata map according to the canonical 5-Tier Decimal Hierarchy (§ 6.1)
 * while preserving all leading, same-line trailing, and footer {@code //} comments.
 */
@NullMarked
public final class StvnReorderMetadataFacetsQuickFix implements LocalQuickFix, IntentionAction, PriorityAction {

    @SafeFieldForPreview
    private final SmartPsiElementPointer<MetadataMap> metadataMapPointer;

    /**
     * Constructs a reordering quick-fix for the specified metadata map.
     *
     * @param metadataMap the metadata map containing unordered facets
     */
    public StvnReorderMetadataFacetsQuickFix(MetadataMap metadataMap) {
        this.metadataMapPointer = SmartPointerManager.getInstance(metadataMap.getProject())
            .createSmartPsiElementPointer(metadataMap);
    }

    @Override
    public @NotNull String getText() {
        return getFamilyName();
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Reorder metadata facets to canonical 5-tier order";
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.NORMAL;
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        MetadataMap map = metadataMapPointer.getElement();
        return map != null && map.isValid();
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        MetadataMap map = metadataMapPointer.getElement();
        if (map != null && map.isValid()) {
            reorderMetadataMap(project, map);
        }
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        MetadataMap original = metadataMapPointer.getElement();
        if (original != null && original.isValid()) {
            MetadataMap copyMap = PsiTreeUtil.findSameElementInCopy(original, file);
            reorderMetadataMap(project, copyMap);
            return IntentionPreviewInfo.DIFF;
        }
        PsiElement atCaret = file.findElementAt(editor.getCaretModel().getOffset());
        MetadataMap copyAtCaret = PsiTreeUtil.getParentOfType(atCaret, MetadataMap.class, false);
        if (copyAtCaret != null && copyAtCaret.isValid()) {
            reorderMetadataMap(project, copyAtCaret);
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
        MetadataMap copyMap = previewElement instanceof MetadataMap mm
            ? mm
            : PsiTreeUtil.getParentOfType(previewElement, MetadataMap.class, false);
        if (copyMap == null || !copyMap.isValid()) {
            return IntentionPreviewInfo.EMPTY;
        }
        reorderMetadataMap(project, copyMap);
        return IntentionPreviewInfo.DIFF;
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement targetElement = descriptor.getPsiElement();
        MetadataMap targetMap = targetElement instanceof MetadataMap mm
            ? mm
            : PsiTreeUtil.getParentOfType(targetElement, MetadataMap.class, false);
        MetadataMap metadataMap = metadataMapPointer.getElement();
        if (targetMap == null && metadataMap != null && metadataMap.isValid()) {
            targetMap = metadataMap;
        }
        if (targetMap != null && targetMap.isValid()) {
            reorderMetadataMap(project, targetMap);
        }
    }

    /**
     * Sorts the entries of the given metadata map in-place into the canonical 5-tier order
     * while preserving all leading, same-line trailing, and footer {@code //} comments.
     *
     * @param project the active project
     * @param map the metadata map to reorder
     */
    public static void reorderMetadataMap(@NotNull Project project, @NotNull MetadataMap map) {
        List<MetadataEntry> entries = map.getMetadataEntryList();
        if (entries.size() <= 1 || PsiTreeUtil.hasErrorElements(map)) {
            return;
        }

        for (MetadataEntry entry : entries) {
            if (!StvnMetadataFacetAllowlist.isFacetPermittedForOrdering(entry, map)) {
                return;
            }
        }

        var partitioned = StvnReorderMetaEntriesQuickFix.partitionBlockInterior(
            map.getNode(),
            StvnTypes.METADATA_ENTRY,
            node -> node.getPsi() instanceof MetadataEntry entry
                ? StvnMetadataOrderInspection.getFacetRank(StvnMetadataOrderInspection.extractFacetKeyword(entry))
                : 5
        );
        if (partitioned.slices().size() <= 1 || StvnReorderMetaEntriesQuickFix.isAlreadySorted(partitioned.slices())) {
            return;
        }

        var sorted = new ArrayList<>(partitioned.slices());
        sorted.sort(Comparator.comparingInt(StvnReorderMetaEntriesQuickFix.CommentedEntrySlice::rank)
            .thenComparingInt(StvnReorderMetaEntriesQuickFix.CommentedEntrySlice::originalIndex));

        String rawMapText = map.getText();
        boolean multiLine = rawMapText.indexOf('\n') >= 0 || partitioned.hasAnyComment();
        String baseIndent = StvnReorderMetaEntriesQuickFix.detectElementLineIndent(map, "    ");
        String entryIndent = StvnReorderMetaEntriesQuickFix.detectEntryIndent(rawMapText, baseIndent + "  ");
        String closingIndent = StvnReorderMetaEntriesQuickFix.detectClosingBraceIndent(rawMapText, baseIndent);

        String rebuiltText = StvnReorderMetaEntriesQuickFix.renderSortedBlockText(
            partitioned.openBraceSameLineComment(),
            sorted,
            partitioned.footerComments(),
            partitioned.footerHadBlankLineBefore(),
            multiLine,
            entryIndent,
            closingIndent
        );
        MetadataMap newMap = StvnElementFactory.createMetadataMap(project, rebuiltText);
        replaceMetadataMapInteriorInPlace(map, newMap);
    }

    private static void replaceMetadataMapInteriorInPlace(MetadataMap target, MetadataMap replacement) {
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
}
