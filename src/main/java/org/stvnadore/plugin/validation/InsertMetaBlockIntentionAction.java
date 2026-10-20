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
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;

/**
 * Intention action that inserts a canonical metadata block '{ #name ... #kind ... }'
 * immediately following an incomplete ':meta' clause without duplicating the keyword.
 */
@NullMarked
public final class InsertMetaBlockIntentionAction implements IntentionAction {

    private final TextRange targetRange;

    public InsertMetaBlockIntentionAction(TextRange targetRange) {
        this.targetRange = targetRange;
    }

    @Override
    public @NotNull String getText() {
        return "Insert metadata block '{ #name ... #kind ... }'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Insert metadata block";
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

        int metaEnd = targetRange.getEndOffset();
        if (metaEnd < 0 || metaEnd > doc.getTextLength()) return;

        CharSequence chars = doc.getCharsSequence();
        String lineSep = doc.getText().contains("\r\n") ? "\r\n" : "\n";
        String indent = computeLineIndent(chars, targetRange.getStartOffset());
        String innerIndent = indent + "  ";

        String fileName = file.getName();
        int lastDot = fileName.lastIndexOf('.');
        String stemPart = lastDot >= 0 ? fileName.substring(0, lastDot) : fileName;
        String[] dotParts = stemPart.split("\\.");

        String slot0 = dotParts.length > 0 && !dotParts[0].isEmpty() ? dotParts[0] : "document";
        String slot1 = dotParts.length > 1 && !dotParts[1].isEmpty() ? dotParts[1] : null;
        String kind = StvnMetaTemplateUtil.resolveExpectedKind(fileName);

        var sb = new StringBuilder();
        sb.append(" {").append(lineSep);
        sb.append(innerIndent).append("#name \"").append(slot0).append("\"").append(lineSep);
        if (slot1 != null) {
            sb.append(innerIndent).append("#domain \"").append(slot1).append("\"").append(lineSep);
        }
        sb.append(innerIndent).append("#kind ").append(kind).append(lineSep);
        sb.append(indent).append("}");

        doc.insertString(metaEnd, sb.toString());
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        repositionCaret(project, doc, metaEnd + sb.length());
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
