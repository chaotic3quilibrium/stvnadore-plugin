package org.stvnadore.plugin.actions;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;

/**
 * Intention action available on bare triple quotes to convert them into a fenced string block.
 */
@NullMarked
public final class StvnCreateFencedStringIntention extends PsiElementBaseIntentionAction {

    public StvnCreateFencedStringIntention() {}

    @Override
    public @NotNull String getText() {
        return "Convert to Fenced String Block";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Convert to Fenced String Block";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element) {
        if (editor == null || !element.isValid()) {
            return false;
        }
        var file = element.getContainingFile();
        if (!(file instanceof StvnFile)) {
            return false;
        }

        var doc = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        if (offset < 0 || offset > doc.getTextLength()) {
            return false;
        }

        int lineNum = doc.getLineNumber(offset);
        int lineStart = doc.getLineStartOffset(lineNum);
        int lineEnd = doc.getLineEndOffset(lineNum);
        String lineText = doc.getText(new TextRange(lineStart, lineEnd));

        int tripleIdx = lineText.indexOf("\"\"\"");
        if (tripleIdx < 0) {
            return false;
        }

        int tripleStart = lineStart + tripleIdx;
        int tripleEnd = tripleStart + 3;
        if (offset < tripleStart || offset > tripleEnd + 1) {
            return false;
        }

        String afterTriple = lineText.substring(tripleIdx + 3).trim();
        if (afterTriple.startsWith("[") || afterTriple.startsWith("->[")) {
            return false;
        }

        String beforeTriple = lineText.substring(0, tripleIdx).trim();
        return !beforeTriple.endsWith("]");
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element) throws IncorrectOperationException {
        if (editor == null) return;
        var file = element.getContainingFile();
        if (file == null) return;

        var doc = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        int lineNum = doc.getLineNumber(offset);
        int lineStart = doc.getLineStartOffset(lineNum);
        int lineEnd = doc.getLineEndOffset(lineNum);
        String lineText = doc.getText(new TextRange(lineStart, lineEnd));

        int tripleIdx = lineText.indexOf("\"\"\"");
        if (tripleIdx < 0) return;

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
        String lineSep = doc.getText().contains("\r\n") ? "\r\n" : "\n";

        int insertOffset = lineStart + tripleIdx + 3;
        String insertion = "[TEXT]" + lineSep + innerIndent + lineSep + baseIndent + "[TEXT]\"\"\"";
        doc.insertString(insertOffset, insertion);
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        int caretPos = insertOffset + "[TEXT]".length() + lineSep.length() + innerIndent.length();
        editor.getCaretModel().moveToOffset(caretPos);
    }
}
