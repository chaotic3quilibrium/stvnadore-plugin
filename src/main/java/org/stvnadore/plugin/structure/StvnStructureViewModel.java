package org.stvnadore.plugin.structure;

import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewModelBase;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.Sorter;
import com.intellij.openapi.editor.Editor;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.psi.*;

/**
 * Structure view model for STVN files organizing definitions and root entries.
 */
@NullMarked
public final class StvnStructureViewModel extends StructureViewModelBase implements StructureViewModel.ElementInfoProvider {

    /**
     * Constructs a structure view model for the specified STVN file.
     *
     * @param editor the current text editor, or {@code null} if headless
     * @param psiFile the target STVN source file
     */
    public StvnStructureViewModel(@Nullable Editor editor, StvnFile psiFile) {
        super(psiFile, editor, new StvnStructureViewElement(psiFile));
        withSuitableClasses(
            TypeDefinition.class,
            ConstantDefinition.class,
            TypeEntry.class,
            DefsEntry.class,
            BodyEntry.class
        );
        withSorters(Sorter.ALPHA_SORTER);
    }

    @Override
    public boolean isAlwaysShowsPlus(StructureViewTreeElement element) {
        return false;
    }

    @Override
    public boolean isAlwaysLeaf(StructureViewTreeElement element) {
        var value = element.getValue();
        return value instanceof TypeDefinition || value instanceof ConstantDefinition || value instanceof BodyEntry || value instanceof TypeEntry;
    }
}
