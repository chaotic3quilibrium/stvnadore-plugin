package org.stvnadore.plugin.formatting;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.codeStyle.PreFormatProcessor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.validation.StvnMetadataOrderInspection;
import org.stvnadore.plugin.validation.StvnReorderMetadataFacetsQuickFix;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetaElement;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.StvnTypes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Pre-format processor executing ahead of code reformatting (Ctrl+Alt+L)
 * to automatically normalize {@code :meta} blocks into canonical {@code #name -> #domain -> #kind}
 * order and all metadata maps into the canonical 5-Tier Decimal Hierarchy (§ 6.1).
 */
@NullMarked
public final class StvnMetadataOrderPreFormatProcessor implements PreFormatProcessor {

    /**
     * UserData key storing the document text after pre-format metadata reordering
     * and immediately prior to Stage 2 IntelliJ PSI whitespace formatting.
     */
    public static final Key<String> PRE_PSI_FORMAT_TEXT_KEY = Key.create("STVN_PRE_PSI_FORMAT_TEXT");

    /**
     * Constructs a new StvnMetadataOrderPreFormatProcessor instance.
     */
    public StvnMetadataOrderPreFormatProcessor() {
    }

    @Override
    public @NotNull TextRange process(@NotNull ASTNode element, @NotNull TextRange range) {
        PsiElement psi = element.getPsi();
        if (psi == null || !psi.isValid() || !psi.getLanguage().isKindOf(StvnLanguage.INSTANCE)) {
            return range;
        }

        if (psi instanceof MetaBlock metaBlock) {
            normalizeMetaBlockIfDisordered(metaBlock, range);
        } else {
            Collection<MetaBlock> metaBlocks = PsiTreeUtil.findChildrenOfType(psi, MetaBlock.class);
            for (MetaBlock metaBlock : metaBlocks) {
                normalizeMetaBlockIfDisordered(metaBlock, range);
            }
        }

        if (psi instanceof MetadataMap map) {
            normalizeMapIfDisordered(map, range);
        } else {
            Collection<MetadataMap> maps = PsiTreeUtil.findChildrenOfType(psi, MetadataMap.class);
            for (MetadataMap map : maps) {
                normalizeMapIfDisordered(map, range);
            }
        }
        PsiFile containingFile = psi.getContainingFile();
        if (containingFile != null) {
            TextRange fileRange = containingFile.getTextRange();
            if (fileRange != null && range.contains(fileRange)) {
                containingFile.putUserData(PRE_PSI_FORMAT_TEXT_KEY, containingFile.getText());
            } else {
                containingFile.putUserData(PRE_PSI_FORMAT_TEXT_KEY, null);
            }
        }
        return range;
    }

    private static void normalizeMetaBlockIfDisordered(MetaBlock metaBlock, TextRange range) {
        if (!metaBlock.isValid()) {
            return;
        }
        TextRange blockRange = metaBlock.getTextRange();
        if (blockRange == null || !range.intersects(blockRange)) {
            return;
        }
        if (metaBlock.getNode().findChildByType(StvnTypes.COMMENT) != null) {
            return;
        }
        List<MetaElement> elements = metaBlock.getMetaElementList();
        if (elements.size() <= 1 || !isMetaBlockDisordered(elements)) {
            return;
        }

        List<MetaElement> sorted = new ArrayList<>(elements);
        sorted.sort(Comparator.comparingInt(StvnMetadataOrderPreFormatProcessor::getMetaElementRank));

        boolean multiLine = metaBlock.getText().contains("\n");
        StringBuilder sb = new StringBuilder(multiLine ? "{\n" : "{");
        for (MetaElement el : sorted) {
            if (multiLine) {
                sb.append("    ").append(el.getText().trim()).append("\n");
            } else {
                sb.append(" ").append(el.getText().trim());
            }
        }
        sb.append(multiLine ? "  }" : " }");

        MetaBlock replacement = StvnElementFactory.createMetaBlock(metaBlock.getProject(), sb.toString());
        metaBlock.replace(replacement);
    }

    private static boolean isMetaBlockDisordered(List<MetaElement> elements) {
        int maxRank = -1;
        for (MetaElement element : elements) {
            int rank = getMetaElementRank(element);
            if (rank < maxRank) {
                return true;
            }
            maxRank = rank;
        }
        return false;
    }

    private static int getMetaElementRank(MetaElement element) {
        if (element.getMetaNameFacet() != null) {
            return 1;
        }
        if (element.getMetaDomainFacet() != null) {
            return 2;
        }
        if (element.getMetaKindFacet() != null) {
            return 3;
        }
        return 4;
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
