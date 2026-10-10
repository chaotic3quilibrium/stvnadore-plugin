package org.stvnadore.plugin;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jspecify.annotations.NullMarked;

/**
 * PSI File representation for modular STVN definitions schemas (.stvn_di).
 */
@NullMarked
public final class StvnDefsIncludeFile extends PsiFileBase implements StvnFile {

    /**
     * Constructs an StvnDefsIncludeFile with backing view provider.
     *
     * @param viewProvider file view provider
     */
    public StvnDefsIncludeFile(FileViewProvider viewProvider) {
        super(viewProvider, StvnLanguage.INSTANCE);
    }

    @Override
    public FileType getFileType() {
        return StvnFileType.DefsInclude.INSTANCE;
    }

    @Override
    public String toString() {
        return "STVN Modular Definitions File";
    }
}
