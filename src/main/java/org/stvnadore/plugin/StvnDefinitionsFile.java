package org.stvnadore.plugin;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jspecify.annotations.NullMarked;

/**
 * PSI File representation for canonical STVN definitions schemas (.stvn_d).
 */
@NullMarked
public final class StvnDefinitionsFile extends PsiFileBase implements StvnFile {

    /**
     * Constructs an StvnDefinitionsFile with backing view provider.
     *
     * @param viewProvider file view provider
     */
    public StvnDefinitionsFile(FileViewProvider viewProvider) {
        super(viewProvider, StvnLanguage.INSTANCE);
    }

    @Override
    public FileType getFileType() {
        return StvnFileType.Defs.INSTANCE;
    }

    @Override
    public String toString() {
        return "STVN Definitions File";
    }
}
