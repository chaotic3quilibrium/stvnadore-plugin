package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeDefTarget;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Sorts metadata facets in a metadata map according to the canonical 5-Tier Decimal Hierarchy (§ 6.1)
 * while preserving all leading, same-line trailing, and footer {@code //} comments.
 */
@NullMarked
public final class StvnReorderMetadataFacetsQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
        implements PriorityAction {

    /**
     * Constructs a reordering quick-fix for the specified metadata map.
     *
     * @param metadataMap the metadata map containing unordered facets
     */
    public StvnReorderMetadataFacetsQuickFix(MetadataMap metadataMap) {
        super(metadataMap);
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
        MetadataMap map = startElement instanceof MetadataMap mm ? mm : resolveTargetMetadataMap(startElement);
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
        PsiElement original = getStartElement();
        if (original instanceof MetadataMap mm && mm.isValid()) {
            MetadataMap copyMap = PsiTreeUtil.findSameElementInCopy(mm, file);
            reorderMetadataMap(project, copyMap);
            return IntentionPreviewInfo.DIFF;
        }
        PsiElement atCaret = file.findElementAt(editor.getCaretModel().getOffset());
        MetadataMap copyAtCaret = resolveTargetMetadataMap(atCaret);
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
        MetadataMap copyMap = resolveTargetMetadataMap(previewDescriptor.getPsiElement());
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

    /**
     * Resolves the target {@link MetadataMap} from any inspection descriptor anchor
     * ({@link MetadataMap}, {@link MetadataEntry}, {@code LBRACE}, {@code RBRACE},
     * {@link TypeDefTarget}, {@link ConstantDefinition} keyword, or {@link TypeEntry} {@code :type} keyword).
     *
     * @param anchor descriptor or caret PSI element, or {@code null}
     * @return target {@link MetadataMap}, or {@code null} if not found
     */
    static @Nullable MetadataMap resolveTargetMetadataMap(@Nullable PsiElement anchor) {
        if (anchor == null) {
            return null;
        }
        MetadataMap enclosingMap = PsiTreeUtil.getParentOfType(anchor, MetadataMap.class, false);
        if (enclosingMap != null) {
            return enclosingMap;
        }
        TypeDefTarget defTarget = PsiTreeUtil.getParentOfType(anchor, TypeDefTarget.class, false);
        if (defTarget != null && defTarget.getParent() instanceof TypeDefinition typeDef) {
            return typeDef.getMetadataMap() != null
                ? typeDef.getMetadataMap()
                : (typeDef.getSchemaType() != null ? typeDef.getSchemaType().getMetadataMap() : null);
        }
        if (anchor instanceof TypeDefinition typeDef) {
            return typeDef.getMetadataMap() != null
                ? typeDef.getMetadataMap()
                : (typeDef.getSchemaType() != null ? typeDef.getSchemaType().getMetadataMap() : null);
        }
        ConstantDefinition constDef = PsiTreeUtil.getParentOfType(anchor, ConstantDefinition.class, false);
        if (constDef != null) {
            if (constDef.getMetadataMap() != null) {
                return constDef.getMetadataMap();
            }
            if (constDef.getSchemaType() != null) {
                return constDef.getSchemaType().getMetadataMap();
            }
        }
        TypeEntry typeEntry = PsiTreeUtil.getParentOfType(anchor, TypeEntry.class, false);
        if (typeEntry != null) {
            if (typeEntry.getMetadataMap() != null) {
                return typeEntry.getMetadataMap();
            }
            if (typeEntry.getSchemaType() != null) {
                return typeEntry.getSchemaType().getMetadataMap();
            }
        }
        return null;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof StvnReorderMetadataFacetsQuickFix other)) {
            return false;
        }
        return Objects.equals(getStartElement(), other.getStartElement());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getStartElement());
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
        if (entries.size() <= 1
                || PsiTreeUtil.hasErrorElements(map)
                || StvnMetadataOrderInspection.hasIncompleteTraitEntry(map)) {
            return;
        }

        var partitioned = StvnReorderMetaEntriesQuickFix.partitionBlockInterior(
            map.getNode(),
            StvnTypes.METADATA_ENTRY,
            StvnMetadataOrderInspection.createMutexStabilizedEntryRanker()
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
