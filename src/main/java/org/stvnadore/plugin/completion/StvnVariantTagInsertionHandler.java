package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;

/**
 * Handles insertion of nullary variant tags (#None, #N, #TRUE, #FALSE, enum variants),
 * verifying leading whitespace separation from preceding value tokens.
 */
@NullMarked
public final class StvnVariantTagInsertionHandler implements InsertHandler<LookupElement> {

    /** Singleton instance for nullary variant tag insertion. */
    public static final StvnVariantTagInsertionHandler NULLARY = new StvnVariantTagInsertionHandler();

    private StvnVariantTagInsertionHandler() {}

    @Override
    public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
        var document = context.getDocument();
        int startOffset = context.getStartOffset();
        var text = document.getText();

        // Leading Whitespace Hygiene:
        // Prepend space if preceding character is not a delimiter or whitespace
        if (startOffset > 0) {
            char prevChar = text.charAt(startOffset - 1);
            if (!Character.isWhitespace(prevChar) && prevChar != '(' && prevChar != '[' && prevChar != '{') {
                document.insertString(startOffset, " ");
            }
        }
    }
}
