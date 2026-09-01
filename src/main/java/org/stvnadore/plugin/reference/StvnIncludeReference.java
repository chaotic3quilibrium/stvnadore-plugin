package org.stvnadore.plugin.reference;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReferenceBase;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.psi.StringLiteral;

/**
 * Resolves STVN include string literals to their corresponding target files.
 */
@NullMarked
public final class StvnIncludeReference extends PsiReferenceBase<StringLiteral> {

    /**
     * Constructs an StvnIncludeReference for the given string literal element.
     *
     * @param element the string literal element
     */
    public StvnIncludeReference(StringLiteral element) {
        super(element, getInnerRange(element));
    }

    private static TextRange getInnerRange(StringLiteral element) {
        var text = element.getText();
        if (text.startsWith("\"\"\"")) {
            if (text.startsWith("\"\"\"->")) {
                var closeIndex = text.indexOf(']');
                if (closeIndex != -1 && text.endsWith("\"\"\"") && text.length() > closeIndex + 4) {
                    return new TextRange(closeIndex + 1, text.length() - 3);
                }
            } else if (text.endsWith("\"\"\"") && text.length() >= 6) {
                return new TextRange(3, text.length() - 3);
            }
        } else if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            return new TextRange(1, text.length() - 1);
        }
        return new TextRange(0, text.length());
    }

    @Override
    public @Nullable PsiElement resolve() {
        return StvnTypeReference.resolveIncludeFile(getElement());
    }
}
