package org.stvnadore.plugin.reference;

import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference;
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.icons.StvnIcons;

/**
 * Resolves STVN include path segments to corresponding target files and directories.
 * Extends FileReference to enable segment navigation, completion, and rename refactoring.
 */
@NullMarked
public final class StvnIncludeReference extends FileReference {

    /**
     * Constructs an StvnIncludeReference instance.
     *
     * @param fileReferenceSet the owning FileReferenceSet
     * @param range            the text range of the reference within the element
     * @param index            the index of the segment
     * @param text             the path segment text
     */
    public StvnIncludeReference(
            FileReferenceSet fileReferenceSet,
            TextRange range,
            int index,
            String text
    ) {
        super(fileReferenceSet, range, index, text);
    }

    @Override
    protected @Nullable Object createLookupItem(PsiElement candidate) {
        if (candidate instanceof PsiFile psiFile) {
            var name = psiFile.getName();
            var icon = psiFile.getIcon(0);
            if (icon == null) {
                icon = name.endsWith(".stvn_di")
                    ? StvnIcons.STVN_DI
                    : (name.endsWith(".stvn_d") ? StvnIcons.STVN_D : StvnIcons.STVN_BD);
            }
            var typeText = name.endsWith(".stvn_di")
                ? "Modular Schema Module"
                : (name.endsWith(".stvn_d") ? "Hermetic Schema" : "Binary Schema");

            return LookupElementBuilder.create(psiFile, name)
                .withIcon(icon)
                .withTypeText(typeText, true);
        }
        return super.createLookupItem(candidate);
    }
}
