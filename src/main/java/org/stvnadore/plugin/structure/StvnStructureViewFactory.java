package org.stvnadore.plugin.structure;

import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import com.intellij.lang.PsiStructureViewFactory;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;

/**
 * Factory providing structure view builders for STVN source documents.
 */
@NullMarked
public final class StvnStructureViewFactory implements PsiStructureViewFactory {

    /**
     * Constructs a new {@code StvnStructureViewFactory} instance.
     */
    public StvnStructureViewFactory() {
    }

    @Override
    public @Nullable StructureViewBuilder getStructureViewBuilder(@NotNull PsiFile psiFile) {
        if (!(psiFile instanceof StvnFile stvnFile)) {
            return null;
        }
        return new TreeBasedStructureViewBuilder() {
            @Override
            public @NotNull StructureViewModel createStructureViewModel(@Nullable Editor editor) {
                return new StvnStructureViewModel(editor, stvnFile);
            }
        };
    }
}
