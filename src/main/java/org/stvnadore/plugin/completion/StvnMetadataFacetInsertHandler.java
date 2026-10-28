package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

import java.util.regex.Pattern;

/**
 * Handles insertion of metadata facet items, enforcing the Sigil Symmetrical Consumption Invariant,
 * proper whitespace separation before subsequent facets, and template argument pre-selection.
 */
@NullMarked
public final class StvnMetadataFacetInsertHandler implements InsertHandler<LookupElement> {

    private static final Pattern NUMERIC_ARGUMENT_PATTERN = Pattern.compile("^\\d+(?:\\.\\d+)?$");

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
            } else if (startOffset >= 2 && !Character.isWhitespace(chars.charAt(startOffset - 2))) {
                // Preceded by non-whitespace character without space (e.g. '8#'): replace '#' with a single space
                document.replaceString(startOffset - 1, startOffset, " ");
            } else {
                // Delete the preceding bare '#' character
                document.deleteString(startOffset - 1, startOffset);
            }
        } else if (startOffset > 0 && chars.charAt(startOffset - 1) == '{') {
            // '{' touching insertion site without space: ensure leading space
            document.insertString(startOffset, " ");
        } else if (startOffset > 0 && !Character.isWhitespace(chars.charAt(startOffset - 1)) && chars.charAt(startOffset - 1) != '{') {
            // Token touching insertion site without space (e.g. '8<caret>'): ensure leading space
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

        // 3. Template Argument Pre-Selection:
        var numericArg = extractTrailingNumericArgument(item.getLookupString());
        if (numericArg != null) {
            int argEnd = tailOffset;
            int argStart = tailOffset - numericArg.length();
            if (argStart >= 0 && argEnd <= document.getTextLength()) {
                context.getEditor().getSelectionModel().setSelection(argStart, argEnd);
                context.getEditor().getCaretModel().moveToOffset(argEnd);
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
        if (startOffset > 0 && !Character.isWhitespace(chars.charAt(startOffset - 1)) && chars.charAt(startOffset - 1) != '{') {
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

        // 5. Template Argument Pre-Selection or Caret Placement
        var numericArg = extractTrailingNumericArgument(item.getLookupString());
        if (numericArg != null) {
            int argEnd = facetEndOffset;
            int argStart = facetEndOffset - numericArg.length();
            if (argStart >= 0 && argEnd <= document.getTextLength()) {
                context.getEditor().getSelectionModel().setSelection(argStart, argEnd);
                context.getEditor().getCaretModel().moveToOffset(argEnd);
                return;
            }
        }

        // Position the editor caret immediately after the inserted facet text inside the closing brace
        context.getEditor().getCaretModel().moveToOffset(facetEndOffset);
    }

    private static @Nullable String extractTrailingNumericArgument(String lookupString) {
        int lastSpace = lookupString.lastIndexOf(' ');
        if (lastSpace == -1) {
            return null;
        }
        String candidate = lookupString.substring(lastSpace + 1);
        if (NUMERIC_ARGUMENT_PATTERN.matcher(candidate).matches()) {
            return candidate;
        }
        return null;
    }
}
