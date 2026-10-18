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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intention action that inserts an inferred default value literal after an incomplete ':body' clause.
 */
@NullMarked
public final class InsertDefaultBodyValueIntentionAction implements IntentionAction {

    private static final Pattern TYPE_PATTERN = Pattern.compile(":type\\s+(:[a-zA-Z0-9_/]+|\\(:[a-zA-Z0-9_/]+)");
    private final TextRange targetRange;

    public InsertDefaultBodyValueIntentionAction(TextRange targetRange) {
        this.targetRange = targetRange;
    }

    @Override
    public @NotNull String getText() {
        return "Insert default body value";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Insert default body value";
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

        int bodyEnd = targetRange.getEndOffset();
        if (bodyEnd < 0 || bodyEnd > doc.getTextLength()) return;

        String defaultValue = inferDefaultValue(doc.getText(), targetRange.getStartOffset());

        CharSequence chars = doc.getCharsSequence();
        boolean hasTrailingSpace = bodyEnd < chars.length() && (chars.charAt(bodyEnd) == ' ' || chars.charAt(bodyEnd) == '\t');
        String insertion = hasTrailingSpace ? defaultValue : " " + defaultValue;

        doc.insertString(bodyEnd, insertion);
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        repositionCaret(project, doc, bodyEnd + insertion.length());
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }

    private static String inferDefaultValue(String fullText, int bodyStartOffset) {
        String prefix = fullText.substring(0, Math.min(bodyStartOffset, fullText.length()));
        Matcher matcher = TYPE_PATTERN.matcher(prefix);
        String lastType = null;
        while (matcher.find()) {
            lastType = matcher.group(1);
        }

        if (lastType == null) {
            return "\"\"";
        }

        if (lastType.startsWith("(") && lastType.length() > 1) {
            lastType = lastType.substring(1);
        }

        return switch (lastType) {
            case StvnVocabulary.TYPE_STRING -> "\"\"";
            case StvnVocabulary.TYPE_INT, ":Nat", ":PosInt", ":Byte", ":Short", ":Long", ":BigInt" -> "0";
            case StvnVocabulary.TYPE_BOOLEAN -> "#TRUE";
            case StvnVocabulary.TYPE_FLOAT, ":Double", ":Decimal" -> "0.0";
            case StvnVocabulary.TYPE_SEQ, ":List" -> "[]";
            case StvnVocabulary.TYPE_SET -> "#{}";
            case StvnVocabulary.TYPE_MAP -> "{}";
            case StvnVocabulary.TYPE_TUPLE -> "()";
            case StvnVocabulary.TYPE_OPTION -> "#None";
            default -> {
                if (lastType.contains("Seq") || lastType.contains("List")) yield "[]";
                if (lastType.contains("Set")) yield "#{}";
                if (lastType.contains("Map")) yield "{}";
                if (lastType.contains("Tuple")) yield "()";
                yield "\"\"";
            }
        };
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
