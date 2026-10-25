package org.stvnadore.plugin.psi;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.AbstractElementManipulator;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.StringLiteral;

/**
 * Element manipulator for StringLiteral elements, supporting safe in-place content
 * updates during file rename refactoring without corrupting outer quotation marks.
 */
@NullMarked
public final class StvnStringLiteralManipulator extends AbstractElementManipulator<StringLiteral> {

    @Override
    public @Nullable StringLiteral handleContentChange(
            @NotNull StringLiteral element,
            @NotNull TextRange range,
            String newContent
    ) throws IncorrectOperationException {
        var oldText = element.getText();
        var newText = oldText.substring(0, range.getStartOffset())
            + newContent
            + oldText.substring(range.getEndOffset());

        var replacement = StvnElementFactory.createStringLiteral(element.getProject(), newText);
        return (StringLiteral) element.replace(replacement);
    }

    @Override
    public @NotNull TextRange getRangeInElement(@NotNull StringLiteral element) {
        var text = element.getText();
        if (text.startsWith("\"\"\"") && text.endsWith("\"\"\"") && text.length() >= 6) {
            return new TextRange(3, text.length() - 3);
        } else if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            return new TextRange(1, text.length() - 1);
        } else if (text.startsWith("\"")) {
            return new TextRange(1, text.length());
        }
        return new TextRange(0, text.length());
    }
}
