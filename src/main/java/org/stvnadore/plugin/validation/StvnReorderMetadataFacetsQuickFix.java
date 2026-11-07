package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
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
 * Intention quick-fix that sorts metadata facets in a metadata map according to
 * the canonical 5-Tier Decimal Hierarchy (§ 6.1).
 */
@NullMarked
public final class StvnReorderMetadataFacetsQuickFix implements LocalQuickFix, IntentionAction {

    private final MetadataMap metadataMap;

    /**
     * Constructs a reordering quick-fix for the specified metadata map.
     *
     * @param metadataMap the metadata map containing unordered facets
     */
    public StvnReorderMetadataFacetsQuickFix(MetadataMap metadataMap) {
        this.metadataMap = metadataMap;
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
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        return metadataMap.isValid();
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (metadataMap.isValid()) {
            reorderMetadataMap(project, metadataMap);
        }
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement targetElement = descriptor.getPsiElement();
        MetadataMap targetMap = PsiTreeUtil.getParentOfType(targetElement, MetadataMap.class, false);
        if (targetMap == null && metadataMap.isValid()) {
            targetMap = metadataMap;
        }
        if (targetMap != null && targetMap.isValid()) {
            reorderMetadataMap(project, targetMap);
        }
    }

    private record FacetWithComment(
        MetadataEntry entry,
        int rank,
        String entryText,
        @Nullable String sameLineComment
    ) {}

    /**
     * Sorts the entries of the given metadata map in-place into the canonical 5-tier order.
     *
     * @param project the active project
     * @param map the metadata map to reorder
     */
    public static void reorderMetadataMap(@NotNull Project project, @NotNull MetadataMap map) {
        List<MetadataEntry> entries = map.getMetadataEntryList();
        if (entries.size() <= 1) {
            return;
        }

        for (MetadataEntry entry : entries) {
            if (!StvnMetadataFacetAllowlist.isFacetPermittedForOrdering(entry, map)) {
                return;
            }
        }

        List<FacetWithComment> items = new ArrayList<>(entries.size());
        boolean hasComments = map.getNode().findChildByType(StvnTypes.COMMENT) != null;
        for (MetadataEntry entry : entries) {
            String trailingComment = StvnReorderMetaEntriesQuickFix.extractSameLineTrailingComment(entry.getNode());
            if (trailingComment != null) {
                hasComments = true;
            }
            int rank = StvnMetadataOrderInspection.getFacetRank(StvnMetadataOrderInspection.extractFacetKeyword(entry));
            items.add(new FacetWithComment(entry, rank, entry.getText().trim(), trailingComment));
        }

        items.sort(Comparator.comparingInt(FacetWithComment::rank));

        String rawMapText = map.getText();
        boolean multiLine = rawMapText.indexOf('\n') >= 0 || hasComments;
        String baseIndent = StvnReorderMetaEntriesQuickFix.detectElementLineIndent(map, "    ");
        String entryIndent = StvnReorderMetaEntriesQuickFix.detectEntryIndent(rawMapText, baseIndent + "  ");
        String closingIndent = StvnReorderMetaEntriesQuickFix.detectClosingBraceIndent(rawMapText, baseIndent);
        List<String> leadingComments = StvnReorderMetaEntriesQuickFix.extractStandaloneCommentsBeforeFirstEntry(map.getNode());

        StringBuilder sb = new StringBuilder(multiLine ? "{\n" : "{");
        if (multiLine) {
            for (String leadComment : leadingComments) {
                sb.append(entryIndent).append(leadComment).append("\n");
            }
        }
        for (FacetWithComment item : items) {
            if (multiLine) {
                sb.append(entryIndent).append(item.entryText());
                if (item.sameLineComment() != null) {
                    sb.append(item.sameLineComment());
                }
                sb.append("\n");
            } else {
                sb.append(" ").append(item.entryText());
            }
        }
        sb.append(multiLine ? closingIndent + "}" : " }");

        MetadataMap newMap = StvnElementFactory.createMetadataMap(project, sb.toString());
        map.replace(newMap);
    }
}
