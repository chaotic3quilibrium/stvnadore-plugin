package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;

/**
 * Handles insertion of metadata facet items, enforcing the Sigil Symmetrical Consumption Invariant
 * and proper whitespace separation before subsequent facets.
 */
@NullMarked
public final class StvnMetadataFacetInsertHandler implements InsertHandler<LookupElement> {

    /** Centralized singleton instance. */
    public static final StvnMetadataFacetInsertHandler INSTANCE = new StvnMetadataFacetInsertHandler();

    private StvnMetadataFacetInsertHandler() {}

    @Override
    public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
        var document = context.getDocument();
        int startOffset = context.getStartOffset();
        var chars = document.getCharsSequence();

        // 1. Sigil Symmetrical Consumption Invariant:
        // If the preceding character in the document buffer is '#', delete or replace it
        // so that the insertion of '#facet' does not produce '##facet'.
        if (startOffset > 0 && chars.charAt(startOffset - 1) == '#') {
            if (startOffset >= 2 && chars.charAt(startOffset - 2) == '{') {
                // '{#' without space: replace '#' with a single space to yield '{ #facet'
                document.replaceString(startOffset - 1, startOffset, " ");
            } else {
                // Delete the preceding bare '#' character
                document.deleteString(startOffset - 1, startOffset);
            }
        } else if (startOffset > 0 && chars.charAt(startOffset - 1) == '{') {
            // '{' touching insertion site without space: ensure leading space
            document.insertString(startOffset, " ");
        }

        // 2. Trailing Spacing Preservation:
        // Ensure a space exists before subsequent tokens (such as following '#facet')
        int tailOffset = context.getTailOffset();
        if (tailOffset < document.getTextLength()) {
            char nextChar = document.getCharsSequence().charAt(tailOffset);
            if (!Character.isWhitespace(nextChar) && nextChar != '}') {
                document.insertString(tailOffset, " ");
            }
        }
    }
}
