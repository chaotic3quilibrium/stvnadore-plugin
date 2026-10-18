package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Intention action that converts legacy unfenced block strings into canonical fenced string blocks
 * under Rule STR-04 ("""[TEXT]...[TEXT]""").
 */
@NullMarked
public final class ConvertToFencedStringIntentionAction implements IntentionAction {

    private static final String DEFAULT_TAG = "FENCE";
    private final TextRange targetRange;

    /**
     * Constructs a new intention action bound to the opening delimiter coordinate span.
     *
     * @param targetRange the text range of the opening triple quotes
     */
    public ConvertToFencedStringIntentionAction(TextRange targetRange) {
        this.targetRange = targetRange;
    }

    @Override
    public @NotNull String getText() {
        return "Convert to fenced string '\"\"\"[" + DEFAULT_TAG + "]...[" + DEFAULT_TAG + "]\"\"\"'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Convert to fenced string";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (file == null) return false;
        int start = targetRange.getStartOffset();
        return start >= 0 && start + 3 <= file.getTextLength();
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) throws IncorrectOperationException {
        if (file == null) return;
        Document doc = file.getViewProvider().getDocument();
        if (doc == null && editor != null) {
            doc = editor.getDocument();
        }
        if (doc == null) return;

        int startOffset = targetRange.getStartOffset();
        if (startOffset < 0 || startOffset + 3 > doc.getTextLength()) return;

        var element = file.findElementAt(startOffset);
        String text = (element != null && element.getText().startsWith("\"\"\"")) ? element.getText() : null;

        String updated;
        int replaceEndOffset;

        if (text != null && text.startsWith("\"\"\"") && text.endsWith("\"\"\"") && text.length() >= 6) {
            int elemStart = element.getTextRange().getStartOffset();
            String body = text.substring(3, text.length() - 3);
            updated = "\"\"\"[" + DEFAULT_TAG + "]" + body + "[" + DEFAULT_TAG + "]\"\"\"";
            replaceEndOffset = elemStart + text.length();
            startOffset = elemStart;
        } else {
            // Scan forward in document buffer for matching closing delimiter
            CharSequence chars = doc.getCharsSequence();
            int closingIdx = -1;
            for (int i = startOffset + 3; i + 2 < chars.length(); i++) {
                if (chars.charAt(i) == '"' && chars.charAt(i + 1) == '"' && chars.charAt(i + 2) == '"') {
                    closingIdx = i;
                    break;
                }
            }

            if (closingIdx >= 0) {
                String body = doc.getText(new TextRange(startOffset + 3, closingIdx));
                updated = "\"\"\"[" + DEFAULT_TAG + "]" + body + "[" + DEFAULT_TAG + "]\"\"\"";
                replaceEndOffset = closingIdx + 3;
            } else {
                // Fallback for unclosed block string: append closing fence with newline and indentation
                String lineSep = doc.getText().contains("\r\n") ? "\r\n" : "\n";
                String indent = computeOpeningIndent(chars, startOffset);
                String rest = doc.getText(new TextRange(startOffset + 3, doc.getTextLength()));
                updated = "\"\"\"[" + DEFAULT_TAG + "]" + rest + lineSep + indent + "[" + DEFAULT_TAG + "]\"\"\"";
                replaceEndOffset = doc.getTextLength();
            }
        }

        doc.replaceString(startOffset, replaceEndOffset, updated);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        repositionCaretToBodyLine(project, doc, startOffset);
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }

    private static String computeOpeningIndent(CharSequence docText, int startOffset) {
        int lineStart = 0;
        for (int i = startOffset - 1; i >= 0; i--) {
            char c = docText.charAt(i);
            if (c == '\n') {
                lineStart = i + 1;
                break;
            }
        }
        var sb = new StringBuilder();
        for (int i = lineStart; i < startOffset; i++) {
            char c = docText.charAt(i);
            if (c == ' ' || c == '\t') {
                sb.append(c);
            } else {
                break;
            }
        }
        return sb.toString();
    }

    private static void repositionCaretToBodyLine(@NotNull Project project, @NotNull Document doc, int openOffset) {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || editor.getDocument() != doc) {
            var editors = EditorFactory.getInstance().getEditors(doc, project);
            if (editors.length > 0) {
                editor = editors[0];
            }
        }
        if (editor != null && editor.getDocument() == doc) {
            int openLine = doc.getLineNumber(openOffset);
            int targetLine = openLine + 1;
            if (targetLine < doc.getLineCount()) {
                int targetOffset = doc.getLineStartOffset(targetLine);
                CharSequence seq = doc.getCharsSequence();
                int lineEnd = doc.getLineEndOffset(targetLine);
                while (targetOffset < lineEnd && (seq.charAt(targetOffset) == ' ' || seq.charAt(targetOffset) == '\t')) {
                    targetOffset++;
                }
                editor.getCaretModel().moveToOffset(targetOffset);
            }
        }
    }
}
