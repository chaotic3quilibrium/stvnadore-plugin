package org.stvnadore.plugin;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jspecify.annotations.NullMarked;

/**
 * PSI File representation for standard STVN includes modules (.stvn_incl).
 */
@NullMarked
public final class StvnInclFile extends PsiFileBase implements StvnFile {

    public StvnInclFile(FileViewProvider viewProvider) {
        super(viewProvider, StvnLanguage.INSTANCE);
    }

    @Override
    public FileType getFileType() {
        return StvnFileType.Incl.INSTANCE;
    }

    @Override
    public String toString() {
        return "STVN Includes Module File";
    }
}
