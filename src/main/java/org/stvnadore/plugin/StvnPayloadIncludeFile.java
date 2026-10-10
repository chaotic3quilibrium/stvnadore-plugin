package org.stvnadore.plugin;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jspecify.annotations.NullMarked;

/**
 * PSI File representation for modular STVN payload documents (.stvn_i).
 */
@NullMarked
public final class StvnPayloadIncludeFile extends PsiFileBase implements StvnFile {

    /**
     * Constructs an StvnPayloadIncludeFile with backing view provider.
     *
     * @param viewProvider file view provider
     */
    public StvnPayloadIncludeFile(FileViewProvider viewProvider) {
        super(viewProvider, StvnLanguage.INSTANCE);
    }

    @Override
    public FileType getFileType() {
        return StvnFileType.PayloadInclude.INSTANCE;
    }

    @Override
    public String toString() {
        return "STVN Modular Payload File";
    }
}
