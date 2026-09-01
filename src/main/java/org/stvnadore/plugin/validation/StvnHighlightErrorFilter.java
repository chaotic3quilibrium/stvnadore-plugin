package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.highlighting.HighlightErrorFilter;
import com.intellij.psi.PsiErrorElement;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.StvnLanguage;

/**
 * Suppresses default Grammar-Kit {@link PsiErrorElement} error highlights for all STVN files,
 * delegating all syntax and semantic diagnostic reporting exclusively to {@link StvnExternalAnnotator}.
 */
@NullMarked
public final class StvnHighlightErrorFilter extends HighlightErrorFilter {

    @Override
    public boolean shouldHighlightErrorElement(PsiErrorElement element) {
        var file = element.getContainingFile();
        if (file instanceof StvnFile || (file != null && file.getLanguage().isKindOf(StvnLanguage.INSTANCE))) {
            return false;
        }
        return true;
    }
}
