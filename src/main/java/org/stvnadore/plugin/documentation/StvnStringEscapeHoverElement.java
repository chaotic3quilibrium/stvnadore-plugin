package org.stvnadore.plugin.documentation;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.light.LightElement;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;

/**
 * Lightweight synthetic PSI target representing a single valid backslash escape sequence
 * inside a {@code LITERAL_STRING_SIMPLE} ({@code "..."}) token during hover or Quick Documentation.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnStringEscapeHoverElement extends LightElement {

    private final PsiElement literalLeaf;
    private final TextRange absoluteEscapeRange;
    private final StvnStringEscapeHoverSupport.EscapeHoverModel model;

    StvnStringEscapeHoverElement(
            PsiElement literalLeaf,
            TextRange absoluteEscapeRange,
            StvnStringEscapeHoverSupport.EscapeHoverModel model
    ) {
        super(literalLeaf.getManager(), StvnLanguage.INSTANCE);
        this.literalLeaf = literalLeaf;
        this.absoluteEscapeRange = absoluteEscapeRange;
        this.model = model;
    }

    /**
     * Returns the parsed escape hover model for HTML rendering.
     *
     * @return immutable escape hover metadata model
     */
    public StvnStringEscapeHoverSupport.EscapeHoverModel model() {
        return model;
    }

    @Override
    public PsiElement getParent() {
        return literalLeaf.getParent();
    }

    @Override
    public PsiFile getContainingFile() {
        return literalLeaf.getContainingFile();
    }

    @Override
    public @NotNull TextRange getTextRange() {
        return absoluteEscapeRange;
    }

    @Override
    public int getTextOffset() {
        return absoluteEscapeRange.getStartOffset();
    }

    @Override
    public String getText() {
        return model.sourceEscape();
    }

    @Override
    public boolean isValid() {
        return literalLeaf.isValid();
    }

    @Override
    public String toString() {
        return "StvnStringEscapeHoverElement(" + model.sourceEscape() + ")";
    }
}
