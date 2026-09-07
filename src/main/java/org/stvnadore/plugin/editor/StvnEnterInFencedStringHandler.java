package org.stvnadore.plugin.editor;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.settings.StvnSettings;

import java.util.regex.Pattern;

/**
 * Intercepts Enter keystrokes immediately following unclosed multiline string opening delimiters
 * (bare """ or fenced """[TAG] / """->[TAG]) and automatically inserts the newline, indentation,
 * and symmetrical closing delimiter based on the configured BlockStringEnterStyle.
 */
@NullMarked
public final class StvnEnterInFencedStringHandler implements EnterHandlerDelegate {

    private static final Pattern OPEN_FENCE_PATTERN = Pattern.compile("^.*\"\"\"(->)?\\[([a-zA-Z0-9_-]{1,256})\\][ \t]*$");
    private static final Pattern BARE_BLOCK_PATTERN = Pattern.compile("^.*\"\"\"[ \t]*$");

    @Override
    public Result preprocessEnter(@NotNull PsiFile file,
                                  @NotNull Editor editor,
                                  @NotNull Ref<Integer> caretOffset,
                                  @NotNull Ref<Integer> caretAdvance,
                                  @NotNull DataContext dataContext,
                                  @Nullable EditorActionHandler originalHandler) {
        if (!(file instanceof StvnFile)) {
            return Result.Continue;
        }

        if (editor.getSelectionModel().hasSelection()) {
            return Result.Continue;
        }

        var doc = editor.getDocument();
        int offset = caretOffset.get();
        if (offset < 0 || offset > doc.getTextLength()) {
            return Result.Continue;
        }

        int lineNumber = doc.getLineNumber(offset);
        int lineStart = doc.getLineStartOffset(lineNumber);
        int lineEnd = doc.getLineEndOffset(lineNumber);
        String lineText = doc.getText(new TextRange(lineStart, lineEnd));

        var fenceMatcher = OPEN_FENCE_PATTERN.matcher(lineText);
        if (fenceMatcher.matches()) {
            // Caret Position Guard: verify caret is positioned at or after the closing delimiter bracket
            int closeBracketIdx = lineText.lastIndexOf(']');
            if (closeBracketIdx >= 0 && offset <= lineStart + closeBracketIdx) {
                return Result.Continue;
            }

            String tag = fenceMatcher.group(2);
            String closingFence = "[" + tag + "]\"\"\"";

            // Check whether this opening fence is already balanced downstream
            int docLength = doc.getTextLength();
            String forwardText = doc.getText(new TextRange(offset, docLength));
            int nextCloseIdx = forwardText.indexOf(closingFence);
            if (nextCloseIdx >= 0) {
                int nextOpenIdx = forwardText.indexOf("\"\"\"[" + tag + "]");
                int nextArrowOpenIdx = forwardText.indexOf("\"\"\"->[" + tag + "]");
                int minNextOpen = -1;
                if (nextOpenIdx >= 0 && nextArrowOpenIdx >= 0) minNextOpen = Math.min(nextOpenIdx, nextArrowOpenIdx);
                else if (nextOpenIdx >= 0) minNextOpen = nextOpenIdx;
                else if (nextArrowOpenIdx >= 0) minNextOpen = nextArrowOpenIdx;

                // If a closing fence exists without an intervening opening fence of the same tag,
                // the current block is already closed. Do not insert a duplicate closing delimiter.
                if (minNextOpen < 0 || nextCloseIdx < minNextOpen) {
                    return Result.Continue;
                }
            }

            return applyAutoClose(file, editor, doc, lineStart, lineEnd, offset, closingFence);
        }

        var bareMatcher = BARE_BLOCK_PATTERN.matcher(lineText);
        if (bareMatcher.matches()) {
            int lastTriple = lineText.lastIndexOf("\"\"\"");
            if (lastTriple < 0 || offset < lineStart + lastTriple + 3) {
                return Result.Continue;
            }

            // Forward parity check: count bare triple-quotes in downstream text
            int docLength = doc.getTextLength();
            String forwardText = doc.getText(new TextRange(offset, docLength));
            int bareCount = countBareTripleQuotes(forwardText);
            if (bareCount % 2 != 0) {
                // Odd bare triple-quote count indicates block is already closed downstream
                return Result.Continue;
            }

            return applyAutoClose(file, editor, doc, lineStart, lineEnd, offset, "\"\"\"");
        }

        return Result.Continue;
    }

    private static int countBareTripleQuotes(String text) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf("\"\"\"", idx)) >= 0) {
            boolean isClosingFence = (idx > 0 && text.charAt(idx - 1) == ']');
            boolean isOpeningFence = false;
            int after = idx + 3;
            while (after < text.length() && (text.charAt(after) == ' ' || text.charAt(after) == '\t')) {
                after++;
            }
            if (after < text.length() && text.charAt(after) == '[') {
                isOpeningFence = true;
            } else if (after + 2 < text.length() && text.startsWith("->[", after)) {
                isOpeningFence = true;
            }

            if (!isClosingFence && !isOpeningFence) {
                count++;
            }
            idx += 3;
        }
        return count;
    }

    private static Result applyAutoClose(PsiFile file,
                                         Editor editor,
                                         com.intellij.openapi.editor.Document doc,
                                         int lineStart,
                                         int lineEnd,
                                         int offset,
                                         String closingDelimiter) {
        String docString = doc.getText();
        String lineSep = docString.contains("\r\n") ? "\r\n" : "\n";

        StringBuilder baseIndentSb = new StringBuilder();
        for (int i = lineStart; i < lineEnd; i++) {
            char c = doc.getCharsSequence().charAt(i);
            if (c == ' ' || c == '\t') {
                baseIndentSb.append(c);
            } else {
                break;
            }
        }
        String baseIndent = baseIndentSb.toString();
        String innerIndent = baseIndent + "  ";

        var settings = StvnSettings.getInstance(file.getProject());
        var style = settings.getState().blockStringEnterStyle;

        String insertion;
        int newCaretOffset;
        if (style == StvnSettings.BlockStringEnterStyle.TIGHT_TWO_LINE) {
            insertion = lineSep + innerIndent + closingDelimiter;
            newCaretOffset = offset + lineSep.length() + innerIndent.length();
        } else {
            insertion = lineSep + innerIndent + lineSep + baseIndent + closingDelimiter;
            newCaretOffset = offset + lineSep.length() + innerIndent.length();
        }

        doc.insertString(offset, insertion);
        PsiDocumentManager.getInstance(file.getProject()).commitDocument(doc);
        editor.getCaretModel().moveToOffset(newCaretOffset);
        return Result.Stop;
    }

    @Override
    public Result postProcessEnter(@NotNull PsiFile file,
                                   @NotNull Editor editor,
                                   @NotNull DataContext dataContext) {
        return Result.Continue;
    }
}
