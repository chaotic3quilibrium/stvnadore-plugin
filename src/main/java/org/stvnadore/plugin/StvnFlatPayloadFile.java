package org.stvnadore.plugin;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jspecify.annotations.NullMarked;

/**
 * PSI File representation for flat STVN payload documents (.stvn_f).
 */
@NullMarked
public final class StvnFlatPayloadFile extends PsiFileBase implements StvnFile {

    public StvnFlatPayloadFile(FileViewProvider viewProvider) {
        super(viewProvider, StvnLanguage.INSTANCE);
    }

    @Override
    public FileType getFileType() {
        return StvnFileType.FlatPayload.INSTANCE;
    }

    @Override
    public String toString() {
        return "STVN Flat Payload File";
    }
}
