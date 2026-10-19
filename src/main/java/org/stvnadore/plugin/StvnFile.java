package org.stvnadore.plugin;

import com.intellij.psi.PsiFile;
import org.jspecify.annotations.NullMarked;

/**
 * Common marker interface for all STVN PSI file representations
 * (.stvn, .stvn_i, .stvn_d, .stvn_di).
 */
@NullMarked
public interface StvnFile extends PsiFile {
}
