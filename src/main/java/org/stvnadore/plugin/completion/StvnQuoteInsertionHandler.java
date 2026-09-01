package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;

/**
 * Handles insertion for quotes and generated strings, ensuring double quotes
 * are not duplicated if completion is triggered inside an existing string token.
 */
@NullMarked
public final class StvnQuoteInsertionHandler implements InsertHandler<LookupElement> {

    /** Singleton insertion handler instance. */
    public static final StvnQuoteInsertionHandler INSTANCE = new StvnQuoteInsertionHandler();

    private StvnQuoteInsertionHandler() {}

    @Override
    public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
        var document = context.getDocument();
        var tailOffset = context.getTailOffset();
        var startOffset = context.getStartOffset();

        var text = document.getText();
        // If caret was already inside quotes, remove the redundant outer quotes
        if (startOffset > 0 && text.charAt(startOffset - 1) == '"' && tailOffset < text.length() && text.charAt(tailOffset) == '"') {
            document.deleteString(tailOffset, tailOffset + 1);
            document.deleteString(startOffset - 1, startOffset);
        }
    }
}