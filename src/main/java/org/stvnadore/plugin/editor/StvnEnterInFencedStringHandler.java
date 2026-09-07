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

import java.util.regex.Pattern;

/**
 * Intercepts Enter keystrokes immediately following unclosed fenced string opening delimiters
 * ("""[TAG] or """->[TAG]) and automatically inserts the newline, proper indentation,
 * and the matching symmetrical closing fence ([TAG]"""), placing the editor caret between them.
 */
@NullMarked
public final class StvnEnterInFencedStringHandler implements EnterHandlerDelegate {

    private static final Pattern OPEN_FENCE_PATTERN = Pattern.compile("^.*\"\"\"(->)?\\[([a-zA-Z0-9_-]{1,256})\\][ \t]*$");

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

        // Caret Position Guard: verify caret is positioned at or after the closing delimiter bracket
        int closeBracketIdx = lineText.lastIndexOf(']');
        if (closeBracketIdx >= 0 && offset <= lineStart + closeBracketIdx) {
            return Result.Continue;
        }

        var matcher = OPEN_FENCE_PATTERN.matcher(lineText);
        if (!matcher.matches()) {
            return Result.Continue;
        }

        String tag = matcher.group(2);
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

        String docString = doc.getText();
        String lineSep = docString.contains("\r\n") ? "\r\n" : "\n";

        // Compute base indentation of the current line
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

        String insertion = lineSep + innerIndent + lineSep + baseIndent + closingFence;
        doc.insertString(offset, insertion);
        PsiDocumentManager.getInstance(file.getProject()).commitDocument(doc);

        int newCaretOffset = offset + lineSep.length() + innerIndent.length();
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
