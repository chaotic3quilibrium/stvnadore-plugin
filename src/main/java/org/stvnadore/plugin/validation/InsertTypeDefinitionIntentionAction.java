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
 * Intention action that populates an empty ':defs {}' block with a canonical type definition ':Type :String'
 * in definitions schemas (.stvn_d).
 */
@NullMarked
public final class InsertTypeDefinitionIntentionAction implements IntentionAction {

    private final TextRange targetRange;

    public InsertTypeDefinitionIntentionAction(TextRange targetRange) {
        this.targetRange = targetRange;
    }

    @Override
    public @NotNull String getText() {
        return "Insert type definition ':Type :String'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Insert type definition";
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
        String lineSep = doc.getText().contains("\r\n") ? "\r\n" : "\n";
        String indent = computeLineIndent(chars, s);
        String innerIndent = indent + "  ";

        String replacement = "{" + lineSep + innerIndent + ":Type :String" + lineSep + indent + "}";
        doc.replaceString(s, e, replacement);
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        repositionCaret(project, doc, s + 1 + lineSep.length() + innerIndent.length());
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
