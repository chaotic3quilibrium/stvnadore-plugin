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

import org.stvnadore.core.StvnVocabulary;

/**
 * Intention action that inserts a default schema type ':String' after an incomplete ':type' clause.
 */
@NullMarked
public final class InsertSchemaTypeIntentionAction implements IntentionAction {

    private final TextRange targetRange;

    public InsertSchemaTypeIntentionAction(TextRange targetRange) {
        this.targetRange = targetRange;
    }

    @Override
    public @NotNull String getText() {
        return "Insert schema type ':String'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Insert schema type";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (file == null) return false;
        int start = targetRange.getStartOffset();
        return start >= 0 && start <= file.getTextLength();
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) throws IncorrectOperationException {
        if (file == null) return;
        Document doc = file.getViewProvider().getDocument();
        if (doc == null && editor != null) {
            doc = editor.getDocument();
        }
        if (doc == null) return;

        int typeEnd = targetRange.getEndOffset();
        if (typeEnd < 0 || typeEnd > doc.getTextLength()) return;

        CharSequence chars = doc.getCharsSequence();
        boolean hasTrailingSpace = typeEnd < chars.length() && (chars.charAt(typeEnd) == ' ' || chars.charAt(typeEnd) == '\t');
        String insertion = hasTrailingSpace ? StvnVocabulary.TYPE_STRING : " " + StvnVocabulary.TYPE_STRING;

        doc.insertString(typeEnd, insertion);
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        repositionCaret(project, doc, typeEnd + insertion.length());
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }

    private static void repositionCaret(@NotNull Project project, @NotNull Document doc, int targetOffset) {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || editor.getDocument() != doc) {
            var editors = EditorFactory.getInstance().getEditors(doc, project);
            if (editors.length > 0) {
                editor = editors[0];
            }
        }
        if (editor != null && editor.getDocument() == doc && targetOffset <= doc.getTextLength()) {
            editor.getCaretModel().moveToOffset(targetOffset);
        }
    }
}
