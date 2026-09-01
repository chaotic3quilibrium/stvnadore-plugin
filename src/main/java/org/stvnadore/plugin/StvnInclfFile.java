package org.stvnadore.plugin;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jspecify.annotations.NullMarked;

/**
 * PSI File representation for flat STVN includes modules (.stvn_inclf).
 */
@NullMarked
public final class StvnInclfFile extends PsiFileBase implements StvnFile {

    public StvnInclfFile(FileViewProvider viewProvider) {
        super(viewProvider, StvnLanguage.INSTANCE);
    }

    @Override
    public FileType getFileType() {
        return StvnFileType.Inclf.INSTANCE;
    }

    @Override
    public String toString() {
        return "STVN Flat Includes Module File";
    }
}
