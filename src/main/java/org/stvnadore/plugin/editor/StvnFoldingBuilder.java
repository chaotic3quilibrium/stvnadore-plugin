package org.stvnadore.plugin.editor;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.CustomFoldingBuilder;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.PackageEnclosure;

import java.util.List;

/**
 * Custom folding builder for STVN structural blocks (:meta, :defs, :package).
 */
@NullMarked
public final class StvnFoldingBuilder extends CustomFoldingBuilder implements DumbAware {

    @Override
    protected void buildLanguageFoldRegions(@NotNull List<FoldingDescriptor> descriptors,
                                            @NotNull PsiElement root,
                                            @NotNull Document document,
                                            boolean quick) {
        var metaEntries = PsiTreeUtil.findChildrenOfType(root, MetaEntry.class);
        for (var meta : metaEntries) {
            var block = meta.getMetaBlock();
            if (block != null && isMultiLine(block.getTextRange(), document)) {
                descriptors.add(new FoldingDescriptor(meta.getNode(), block.getTextRange()));
            }
        }

        var defsEntries = PsiTreeUtil.findChildrenOfType(root, DefsEntry.class);
        for (var defs : defsEntries) {
            var range = defs.getTextRange();
            if (isMultiLine(range, document)) {
                descriptors.add(new FoldingDescriptor(defs.getNode(), range));
            }
        }

        var packageEnclosures = PsiTreeUtil.findChildrenOfType(root, PackageEnclosure.class);
        for (var pkg : packageEnclosures) {
            var range = pkg.getTextRange();
            if (isMultiLine(range, document)) {
                descriptors.add(new FoldingDescriptor(pkg.getNode(), range));
            }
        }
    }

    private static boolean isMultiLine(TextRange range, Document document) {
        if (range.getLength() <= 0) {
            return false;
        }
        int startLine = document.getLineNumber(range.getStartOffset());
        int endLine = document.getLineNumber(range.getEndOffset());
        return endLine > startLine;
    }

    @Override
    protected String getLanguagePlaceholderText(@NotNull ASTNode node, @NotNull TextRange range) {
        return "{...}";
    }

    @Override
    protected boolean isRegionCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }
}
