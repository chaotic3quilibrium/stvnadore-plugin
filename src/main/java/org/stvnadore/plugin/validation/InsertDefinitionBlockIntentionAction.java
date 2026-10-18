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
 * Intention action that inserts a canonical definition block '{ }' after an incomplete ':defs' clause.
 */
@NullMarked
public final class InsertDefinitionBlockIntentionAction implements IntentionAction {

    private final TextRange targetRange;

    public InsertDefinitionBlockIntentionAction(TextRange targetRange) {
        this.targetRange = targetRange;
    }

    @Override
    public @NotNull String getText() {
        return "Insert definition block '{ :Type :String }'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Insert definition block";
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

        int defsEnd = targetRange.getEndOffset();
        if (defsEnd < 0 || defsEnd > doc.getTextLength()) return;

        CharSequence chars = doc.getCharsSequence();
        String lineSep = doc.getText().contains("\r\n") ? "\r\n" : "\n";
        String indent = computeLineIndent(chars, targetRange.getStartOffset());
        String innerIndent = indent + "  ";

        String insertion = " {" + lineSep + innerIndent + ":Type :String" + lineSep + indent + "}";
        doc.insertString(defsEnd, insertion);
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        repositionCaret(project, doc, defsEnd + 2 + lineSep.length() + innerIndent.length() + ":Type :String".length());
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }

    private static String computeLineIndent(CharSequence docText, int startOffset) {
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
