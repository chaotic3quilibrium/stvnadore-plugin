package org.stvnadore.plugin.validation;

import com.intellij.openapi.util.Condition;
import com.intellij.openapi.vfs.VirtualFile;
import org.jspecify.annotations.NullMarked;

/**
 * Declares STVN files as eligible for project-wide problem tracking in WolfTheProblemSolver.
 */
@NullMarked
public final class StvnProblemFileHighlightFilter implements Condition<VirtualFile> {

    /** Constructs an StvnProblemFileHighlightFilter instance. */
    public StvnProblemFileHighlightFilter() {}

    @Override
    public boolean value(VirtualFile virtualFile) {
        var ext = virtualFile.getExtension();
        return "stvn".equals(ext) || "stvn_incl".equals(ext) || "stvn_inclf".equals(ext);
    }
}