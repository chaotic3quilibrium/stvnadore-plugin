package org.stvnadore.plugin;

import com.intellij.psi.PsiFile;
import org.jspecify.annotations.NullMarked;

/**
 * Common marker interface for all STVN PSI file representations
 * (.stvn, .stvn_incl, .stvn_inclf).
 */
@NullMarked
public interface StvnFile extends PsiFile {
}
