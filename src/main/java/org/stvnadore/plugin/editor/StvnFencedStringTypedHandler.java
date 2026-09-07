package org.stvnadore.plugin.editor;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;

/**
 * Automatically inserts matching bracket and closing fence when typing '[' immediately following '"""'.
 */
@NullMarked
public final class StvnFencedStringTypedHandler extends TypedHandlerDelegate {

    public StvnFencedStringTypedHandler() {}

    @Override
    public Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (c != '[' || !(file instanceof StvnFile)) {
            return Result.CONTINUE;
        }

        var doc = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        if (offset < 4) {
            return Result.CONTINUE;
        }

        String prevChars = doc.getText(new TextRange(offset - 4, offset));
        if (!"\"\"\"[".equals(prevChars)) {
            return Result.CONTINUE;
        }

        if (offset >= 5 && doc.getCharsSequence().charAt(offset - 5) == '"') {
            return Result.CONTINUE;
        }

        if (offset < doc.getTextLength() && doc.getCharsSequence().charAt(offset) == ']') {
            return Result.CONTINUE;
        }

        int lineNum = doc.getLineNumber(offset);
        int lineStart = doc.getLineStartOffset(lineNum);
        int lineEnd = doc.getLineEndOffset(lineNum);

        StringBuilder baseIndentSb = new StringBuilder();
        for (int i = lineStart; i < lineEnd; i++) {
            char ch = doc.getCharsSequence().charAt(i);
            if (ch == ' ' || ch == '\t') {
                baseIndentSb.append(ch);
            } else {
                break;
            }
        }
        String baseIndent = baseIndentSb.toString();
        String innerIndent = baseIndent + "  ";
        String lineSep = doc.getText().contains("\r\n") ? "\r\n" : "\n";

        String insertion = "]" + lineSep + innerIndent + lineSep + baseIndent + "[]\"\"\"";
        doc.insertString(offset, insertion);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        editor.getCaretModel().moveToOffset(offset);
        return Result.STOP;
    }
}
