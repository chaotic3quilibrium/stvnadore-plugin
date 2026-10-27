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

    /** Centralized singleton instance for standard in-map completions. */
    public static final StvnMetadataFacetInsertHandler INSTANCE = new StvnMetadataFacetInsertHandler(false);

    /** Auto-wrapping singleton instance for pre-schema whitespace completions. */
    public static final StvnMetadataFacetInsertHandler AUTO_WRAPPING_INSTANCE = new StvnMetadataFacetInsertHandler(true);

    private final boolean autoWrap;

    private StvnMetadataFacetInsertHandler(boolean autoWrap) {
        this.autoWrap = autoWrap;
    }

    @Override
    public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
        if (autoWrap) {
            handleAutoWrappingInsert(context, item);
            return;
        }

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

    private void handleAutoWrappingInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
        var document = context.getDocument();
        int startOffset = context.getStartOffset();
        var chars = document.getCharsSequence();

        // 1. Consume preceding '#' if present
        if (startOffset > 0 && chars.charAt(startOffset - 1) == '#') {
            document.deleteString(startOffset - 1, startOffset);
            startOffset--;
        }

        // 2. Prepend '{ ' with proper leading whitespace
        String prefix = "{ ";
        if (startOffset > 0 && !Character.isWhitespace(chars.charAt(startOffset - 1))) {
            prefix = " { ";
        }
        document.insertString(startOffset, prefix);

        // 3. Calculate position after the inserted facet text
        int itemLength = item.getLookupString().length();
        int facetEndOffset = startOffset + prefix.length() + itemLength;

        // 4. Append ' }' with proper trailing whitespace before following token
        String suffix = " }";
        if (facetEndOffset < document.getTextLength()) {
            char nextChar = document.getCharsSequence().charAt(facetEndOffset);
            if (!Character.isWhitespace(nextChar)) {
                suffix = " } ";
            }
        } else {
            suffix = " } ";
        }
        document.insertString(facetEndOffset, suffix);

        // 5. Position the editor caret immediately after the inserted facet text inside the closing brace
        context.getEditor().getCaretModel().moveToOffset(facetEndOffset);
    }
}
