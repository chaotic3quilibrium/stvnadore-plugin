package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;

/**
 * Handles lookup item insertion for bare-bracket include completion (:include [ <caret> ]).
 * Automatically wraps the selected schema path in double quotes ("...") and positions
 * the caret outside the closing quote (or inside the closing quote for directories).
 */
@NullMarked
public final class StvnIncludeQuoteInsertHandler implements InsertHandler<LookupElement> {

    /** Singleton instance for file path insertion. */
    public static final StvnIncludeQuoteInsertHandler FILE_INSTANCE = new StvnIncludeQuoteInsertHandler(false);

    /** Singleton instance for directory path insertion. */
    public static final StvnIncludeQuoteInsertHandler DIRECTORY_INSTANCE = new StvnIncludeQuoteInsertHandler(true);

    private final boolean isDirectory;

    private StvnIncludeQuoteInsertHandler(boolean isDirectory) {
        this.isDirectory = isDirectory;
    }

    @Override
    public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
        var document = context.getDocument();
        var startOffset = context.getStartOffset();
        var tailOffset = context.getTailOffset();
        var text = document.getText();

        boolean hasLeadingQuote = startOffset > 0 && text.charAt(startOffset - 1) == '"';
        boolean hasTrailingQuote = tailOffset < text.length() && text.charAt(tailOffset) == '"';

        if (!hasLeadingQuote && !hasTrailingQuote) {
            document.insertString(tailOffset, "\"");
            document.insertString(startOffset, "\"");
            if (isDirectory) {
                // Position caret between the trailing slash and closing quote: "dir/<caret>"
                context.getEditor().getCaretModel().moveToOffset(tailOffset + 1);
            } else {
                // Position caret after closing quote: "file.stvn_di"<caret>
                context.getEditor().getCaretModel().moveToOffset(tailOffset + 2);
            }
        } else if (hasLeadingQuote && !hasTrailingQuote) {
            document.insertString(tailOffset, "\"");
            context.getEditor().getCaretModel().moveToOffset(tailOffset + 1);
        } else if (!hasLeadingQuote && hasTrailingQuote) {
            document.insertString(startOffset, "\"");
            context.getEditor().getCaretModel().moveToOffset(tailOffset + 1);
        }
    }
}
