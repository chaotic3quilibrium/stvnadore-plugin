package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Intention quick-fix that sorts metadata facets in a metadata map according to
 * the canonical 7-tier Semantic Category Order.
 */
@NullMarked
public final class StvnReorderMetadataFacetsQuickFix implements LocalQuickFix {

    private final MetadataMap metadataMap;

    public StvnReorderMetadataFacetsQuickFix(MetadataMap metadataMap) {
        this.metadataMap = metadataMap;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Reorder metadata facets to canonical 7-tier order";
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

    /**
     * Sorts the entries of the given metadata map in-place into the canonical 7-tier order.
     *
     * @param project the active project
     * @param map the metadata map to reorder
     */
    public static void reorderMetadataMap(@NotNull Project project, @NotNull MetadataMap map) {
        List<MetadataEntry> entries = map.getMetadataEntryList();
        if (entries.size() <= 1) {
            return;
        }

        List<MetadataEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(e -> StvnMetadataOrderInspection.getFacetRank(StvnMetadataOrderInspection.extractFacetKeyword(e))));

        StringBuilder sb = new StringBuilder("{");
        for (MetadataEntry entry : sorted) {
            sb.append(" ").append(entry.getText().trim());
        }
        sb.append(" }");

        MetadataMap newMap = StvnElementFactory.createMetadataMap(project, sb.toString());
        map.replace(newMap);
    }
}
