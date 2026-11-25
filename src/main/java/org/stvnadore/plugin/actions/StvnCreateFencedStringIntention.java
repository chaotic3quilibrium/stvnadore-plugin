package org.stvnadore.plugin.actions;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.intentions.StvnConvertEscapedStringToFencedIntention;

/**
 * Intention action that converts a double-quoted simple string literal (or an incomplete opening
 * triple-quote recovery token) into a canonical STVN 2.0.0 Rule {@code STR-04} fenced string block.
 */
@NullMarked
public final class StvnCreateFencedStringIntention extends PsiElementBaseIntentionAction {

    private static final String INTENTION_TEXT = "Convert to Fenced String Block";
    private static final String FAMILY_NAME = "Convert string literal to fenced string block";
    private static final String DEFAULT_RECOVERY_TAG = "FENCE";

    /**
     * Constructs a new {@code StvnCreateFencedStringIntention} instance.
     */
    public StvnCreateFencedStringIntention() {
        setText(INTENTION_TEXT);
    }

    @Override
    public @NotNull String getText() {
        return INTENTION_TEXT;
    }

    @Override
    public @NotNull String getFamilyName() {
        return FAMILY_NAME;
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element) {
        if (!element.isValid()) {
            return false;
        }
        var file = element.getContainingFile();
        if (!(file instanceof StvnFile)) {
            return false;
        }
        if (StvnConvertEscapedStringToFencedIntention.canConvertAnySimpleStringToFenced(element)) {
            return true;
        }
        if (editor == null) {
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
        if (afterTriple.startsWith(StvnVocabulary.DELIM_OPEN_BRACKET)) {
            return false;
        }

        String beforeTriple = lineText.substring(0, tripleIdx).trim();
        return !beforeTriple.endsWith(StvnVocabulary.DELIM_CLOSE_BRACKET);
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        var element = getElement(editor, file);
        if (element == null || !isAvailable(project, editor, element)) {
            return IntentionPreviewInfo.EMPTY;
        }
        invoke(project, editor, element);
        return IntentionPreviewInfo.DIFF;
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element)
            throws IncorrectOperationException {
        if (editor == null) {
            return;
        }
        var file = element.getContainingFile();
        if (file == null) {
            return;
        }

        if (StvnConvertEscapedStringToFencedIntention.invokeAnySimpleStringToFenced(project, editor, element)) {
            return;
        }

        var doc = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        int lineNum = doc.getLineNumber(offset);
        int lineStart = doc.getLineStartOffset(lineNum);
        int lineEnd = doc.getLineEndOffset(lineNum);
        String lineText = doc.getText(new TextRange(lineStart, lineEnd));

        int tripleIdx = lineText.indexOf("\"\"\"");
        if (tripleIdx < 0) {
            return;
        }

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
        String tagToken = StvnVocabulary.DELIM_OPEN_BRACKET + DEFAULT_RECOVERY_TAG + StvnVocabulary.DELIM_CLOSE_BRACKET;

        int insertOffset = lineStart + tripleIdx + 3;
        String insertion = tagToken + lineSep + innerIndent + lineSep + baseIndent + tagToken + "\"\"\"";
        doc.insertString(insertOffset, insertion);
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        int caretPos = insertOffset + tagToken.length() + lineSep.length() + innerIndent.length();
        editor.getCaretModel().moveToOffset(caretPos);
    }
}
