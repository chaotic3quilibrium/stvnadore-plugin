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
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.validation.StvnMetadataOrderInspection;
import org.stvnadore.plugin.validation.StvnReorderMetaEntriesQuickFix;
import org.stvnadore.plugin.validation.StvnReorderMetadataFacetsQuickFix;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetadataMap;

import java.util.Collection;

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
        int initialElementLength = element.getTextLength();
        TextRange activeRange = range;

        if (psi instanceof StvnFile stvnFile) {
            TextRange fileRange = stvnFile.getTextRange();
            if (fileRange != null && range.contains(fileRange)) {
                if (StvnRootSectionReorderer.reorderIfNeeded(stvnFile.getProject(), stvnFile)) {
                    TextRange reorderedRange = stvnFile.getTextRange();
                    if (reorderedRange != null) {
                        activeRange = reorderedRange;
                    }
                }
            }
        }

        if (psi instanceof MetaBlock metaBlock) {
            normalizeMetaBlockIfDisordered(metaBlock, activeRange);
        } else {
            Collection<MetaBlock> metaBlocks = PsiTreeUtil.findChildrenOfType(psi, MetaBlock.class);
            for (MetaBlock metaBlock : metaBlocks) {
                normalizeMetaBlockIfDisordered(metaBlock, activeRange);
            }
        }

        if (psi instanceof MetadataMap map) {
            normalizeMapIfDisordered(map, activeRange);
        } else {
            Collection<MetadataMap> maps = PsiTreeUtil.findChildrenOfType(psi, MetadataMap.class);
            for (MetadataMap map : maps) {
                normalizeMapIfDisordered(map, activeRange);
            }
        }
        int lengthDelta = element.getTextLength() - initialElementLength;
        TextRange adjustedRange = lengthDelta == 0
            ? range
            : new TextRange(range.getStartOffset(), Math.max(range.getStartOffset(), range.getEndOffset() + lengthDelta));
        PsiFile containingFile = psi.getContainingFile();
        if (containingFile != null) {
            TextRange fileRange = containingFile.getTextRange();
            if (fileRange != null && adjustedRange.contains(fileRange)) {
                containingFile.putUserData(PRE_PSI_FORMAT_TEXT_KEY, containingFile.getText());
            } else {
                containingFile.putUserData(PRE_PSI_FORMAT_TEXT_KEY, null);
            }
        }
        return adjustedRange;
    }

    private static void normalizeMetaBlockIfDisordered(MetaBlock metaBlock, TextRange range) {
        if (!metaBlock.isValid()) {
            return;
        }
        TextRange blockRange = metaBlock.getTextRange();
        if (blockRange == null || !range.intersects(blockRange)) {
            return;
        }
        if (StvnMetadataOrderInspection.isMetaBlockDisordered(metaBlock)) {
            StvnReorderMetaEntriesQuickFix.reorderMetaBlock(metaBlock.getProject(), metaBlock);
        }
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
