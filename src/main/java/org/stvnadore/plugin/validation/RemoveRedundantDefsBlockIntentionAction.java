package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Intention action that removes an empty, redundant ':defs {}' clause from an STVN payload document (.stvn).
 */
@NullMarked
public final class RemoveRedundantDefsBlockIntentionAction implements IntentionAction {

    private final TextRange targetRange;

    public RemoveRedundantDefsBlockIntentionAction(TextRange targetRange) {
        this.targetRange = targetRange;
    }

    @Override
    public @NotNull String getText() {
        return "Remove redundant ':defs {}' block";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Remove redundant ':defs {}' block";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (file == null) return false;
        int start = targetRange.getStartOffset();
        return start >= 0 && targetRange.getEndOffset() <= file.getTextLength();
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) throws IncorrectOperationException {
        if (file == null) return;
        Document doc = file.getViewProvider().getDocument();
        if (doc == null && editor != null) {
            doc = editor.getDocument();
        }
        if (doc == null) return;

        int s = targetRange.getStartOffset();
        int e = targetRange.getEndOffset();
        if (s < 0 || e > doc.getTextLength() || s > e) return;

        CharSequence chars = doc.getCharsSequence();
        // Scan backwards from '{' to find preceding ':defs' keyword
        int defsStart = -1;
        for (int i = s - 1; i >= 4; i--) {
            if (chars.charAt(i - 4) == ':' && chars.charAt(i - 3) == 'd' && chars.charAt(i - 2) == 'e' && chars.charAt(i - 1) == 'f' && chars.charAt(i) == 's') {
                defsStart = i - 4;
                break;
            }
        }
        if (defsStart < 0) {
            defsStart = s;
        }

        // Expand range backwards to include line-leading indentation
        int removeStart = defsStart;
        while (removeStart > 0 && (chars.charAt(removeStart - 1) == ' ' || chars.charAt(removeStart - 1) == '\t')) {
            removeStart--;
        }

        // Expand range forwards to consume trailing newline if present
        int removeEnd = e;
        if (removeEnd < chars.length() && chars.charAt(removeEnd) == '\r') {
            removeEnd++;
        }
        if (removeEnd < chars.length() && chars.charAt(removeEnd) == '\n') {
            removeEnd++;
        }

        doc.deleteString(removeStart, removeEnd);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }
}
