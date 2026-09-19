package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;

/**
 * Handles insertion of sum variant constructors (#Some, #Left, #Right, #1),
 * ensuring a trailing space is present and positioning the caret ready for typing the inner payload.
 */
@NullMarked
public final class StvnSumConstructorInsertionHandler implements InsertHandler<LookupElement> {

    /** Singleton insertion handler instance. */
    public static final StvnSumConstructorInsertionHandler INSTANCE = new StvnSumConstructorInsertionHandler();

    private StvnSumConstructorInsertionHandler() {}

    @Override
    public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
        var editor = context.getEditor();
        var document = context.getDocument();
        int startOffset = context.getStartOffset();
        var text = document.getText();

        // 1. Leading Whitespace Hygiene: Prepend space if preceding character is a token
        if (startOffset > 0) {
            char prevChar = text.charAt(startOffset - 1);
            if (!Character.isWhitespace(prevChar) && prevChar != '(' && prevChar != '[' && prevChar != '{') {
                document.insertString(startOffset, " ");
            }
        }

        // 2. Trailing Whitespace Hygiene & Caret Positioning
        var tailOffset = context.getTailOffset();
        text = document.getText();

        if (tailOffset > 0 && text.charAt(tailOffset - 1) == ' ') {
            editor.getCaretModel().moveToOffset(tailOffset);
        } else if (tailOffset < text.length() && text.charAt(tailOffset) == ' ') {
            editor.getCaretModel().moveToOffset(tailOffset + 1);
        } else if (tailOffset <= text.length()) {
            document.insertString(tailOffset, " ");
            editor.getCaretModel().moveToOffset(tailOffset + 1);
        }
    }
}