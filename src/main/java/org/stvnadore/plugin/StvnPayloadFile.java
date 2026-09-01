package org.stvnadore.plugin;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jspecify.annotations.NullMarked;

/**
 * PSI File representation for standard STVN payload documents (.stvn).
 */
@NullMarked
public final class StvnPayloadFile extends PsiFileBase implements StvnFile {

    /**
     * Constructs an StvnPayloadFile with backing view provider.
     *
     * @param viewProvider file view provider
     */
    public StvnPayloadFile(FileViewProvider viewProvider) {
        super(viewProvider, StvnLanguage.INSTANCE);
    }

    @Override
    public FileType getFileType() {
        return StvnFileType.Payload.INSTANCE;
    }

    @Override
    public String toString() {
        return "STVN Payload File";
    }
}