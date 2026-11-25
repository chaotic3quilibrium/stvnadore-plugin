package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.editor.Document;
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
import org.stvnadore.core.StvnStringCharacterPolicy;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;

/**
 * Secondary intention action offering 1-click conversion from a single-line untagged triple-quoted
 * token ({@code """inline..."""}) into a canonically escaped double-quoted STVN string literal ({@code "inline..."}).
 */
@NullMarked
public final class ConvertToSimpleStringIntentionAction implements IntentionAction, PriorityAction {

    private static final int MAX_LABEL_PREVIEW_CHARS = 48;
    @SafeFieldForPreview
    private final TextRange targetRange;
    @SafeFieldForPreview
    private final String canonicalQuoted;

    private ConvertToSimpleStringIntentionAction(TextRange targetRange, String canonicalQuoted) {
        this.targetRange = targetRange;
        this.canonicalQuoted = canonicalQuoted;
    }

    /**
     * Creates a {@link ConvertToSimpleStringIntentionAction} when the token at {@code range} is a
     * closed single-line untagged triple-quoted literal containing no newline characters.
     *
     * @param file  the active PSI file
     * @param range diagnostic opening range
     * @return configured intention action, or {@code null} if multi-line or unclosed
     */
    public static @Nullable ConvertToSimpleStringIntentionAction createIfSingleLineUntagged(
            PsiFile file,
            TextRange range
    ) {
        int start = range.getStartOffset();
        if (start < 0 || start + 6 > file.getTextLength()) {
            return null;
        }
        PsiElement elem = file.findElementAt(start);
        if (elem == null) {
            return null;
        }
        String text = elem.getText();
        if (!text.startsWith("\"\"\"")
                || text.startsWith("\"\"\"[")
                || text.startsWith("\"\"\"->")
                || !text.endsWith("\"\"\"")
                || text.length() < 6) {
            return null;
        }
        String rawBody = text.substring(3, text.length() - 3);
        if (rawBody.indexOf('\n') >= 0
                || rawBody.indexOf('\r') >= 0
                || StvnStringCharacterPolicy.containsUnpairedSurrogate(rawBody)) {
            return null;
        }
        String quoted = StvnPsiLiteralEvaluator.formatCanonicalQuotedString(rawBody);
        return new ConvertToSimpleStringIntentionAction(elem.getTextRange(), quoted);
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.NORMAL;
    }

    @Override
    public @NotNull String getText() {
        String preview = canonicalQuoted.length() > MAX_LABEL_PREVIEW_CHARS
            ? canonicalQuoted.substring(0, MAX_LABEL_PREVIEW_CHARS - 4) + "...\""
            : canonicalQuoted;
        return "Convert to simple string '" + preview + "'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Convert to simple string";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        return file != null && targetRange.getEndOffset() <= file.getTextLength();
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) throws IncorrectOperationException {
        if (file == null) return;
        applyReplacement(project, editor, file);
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        applyReplacement(project, editor, file);
        return IntentionPreviewInfo.DIFF;
    }

    private void applyReplacement(@NotNull Project project, @Nullable Editor editor, @NotNull PsiFile file) {
        Document doc = file.getViewProvider().getDocument();
        if (doc == null && editor != null) {
            doc = editor.getDocument();
        }
        if (doc == null || targetRange.getEndOffset() > doc.getTextLength()) {
            return;
        }
        doc.replaceString(targetRange.getStartOffset(), targetRange.getEndOffset(), canonicalQuoted);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }
}
