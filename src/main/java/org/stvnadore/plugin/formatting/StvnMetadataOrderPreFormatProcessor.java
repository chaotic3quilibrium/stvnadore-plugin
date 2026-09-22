package org.stvnadore.plugin.formatting;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.codeStyle.PreFormatProcessor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.validation.StvnMetadataOrderInspection;
import org.stvnadore.plugin.validation.StvnReorderMetadataFacetsQuickFix;
import org.stvnadore.psi.MetadataMap;

import java.util.Collection;

/**
 * Pre-format processor executing ahead of code reformatting (Ctrl+Alt+L)
 * to automatically normalize all metadata maps into the canonical 7-tier Semantic Category Order.
 */
@NullMarked
public final class StvnMetadataOrderPreFormatProcessor implements PreFormatProcessor {

    @Override
    public @NotNull TextRange process(@NotNull ASTNode element, @NotNull TextRange range) {
        PsiElement psi = element.getPsi();
        if (psi == null || !psi.isValid() || !psi.getLanguage().isKindOf(StvnLanguage.INSTANCE)) {
            return range;
        }

        if (psi instanceof MetadataMap map) {
            normalizeMapIfDisordered(map, range);
        } else {
            Collection<MetadataMap> maps = PsiTreeUtil.findChildrenOfType(psi, MetadataMap.class);
            for (MetadataMap map : maps) {
                normalizeMapIfDisordered(map, range);
            }
        }
        return range;
    }

    private static void normalizeMapIfDisordered(MetadataMap map, TextRange range) {
        if (!map.isValid()) return;
        TextRange mapRange = map.getTextRange();
        if (mapRange != null && range.intersects(mapRange)) {
            if (StvnMetadataOrderInspection.isDisordered(map)) {
                StvnReorderMetadataFacetsQuickFix.reorderMetadataMap(map.getProject(), map);
            }
        }
    }

    @Override
    public boolean changesWhitespacesOnly() {
        return false;
    }
}
