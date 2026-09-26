package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Intention action and quick-fix that wraps an untagged literal in an explicit
 * sum variant constructor tag (#Right, #Left, #1, #2, etc.) to resolve
 * ERR_AMBIGUOUS_SUM_INFERENCE compile errors.
 */
@NullMarked
public final class WrapSumVariantQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    private final String tagText;
    private final String targetTypeName;
    private final Priority priority;

    /**
     * Constructs a quick fix to wrap an untagged literal with a variant tag.
     *
     * @param element the PSI element to wrap
     * @param tagText the constructor tag text (e.g., #Right)
     * @param targetTypeName the target type name
     */
    public WrapSumVariantQuickFix(PsiElement element, String tagText, String targetTypeName) {
        this(element, tagText, targetTypeName, Priority.NORMAL);
    }

    /**
     * Constructs a quick fix to wrap an untagged literal with a variant tag and specific priority.
     *
     * @param element the PSI element to wrap
     * @param tagText the constructor tag text
     * @param targetTypeName the target type name
     * @param priority the quick fix priority
     */
    public WrapSumVariantQuickFix(PsiElement element, String tagText, String targetTypeName, Priority priority) {
        super(element);
        this.tagText = tagText;
        this.targetTypeName = targetTypeName;
        this.priority = priority;
    }

    @Override
    public @NotNull Priority getPriority() {
        return priority;
    }

    @Override
    public @NotNull String getText() {
        return "Wrap with " + tagText + " (-> " + targetTypeName + ")";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Wrap Sum Variant";
    }

    @Override
    public boolean isAvailable(@NotNull Project project,
                               @NotNull PsiFile file,
                               @NotNull PsiElement startElement,
                               @NotNull PsiElement endElement) {
        return startElement.isValid();
    }

    @Override
    public void invoke(@NotNull Project project,
                       @NotNull PsiFile file,
                       @Nullable Editor editor,
                       @NotNull PsiElement startElement,
                       @NotNull PsiElement endElement) {
        if (!startElement.isValid()) {
            return;
        }

        var doc = file.getViewProvider().getDocument();
        if (doc == null) {
            return;
        }

        var range = startElement.getTextRange();
        var origText = startElement.getText();
        var replacement = tagText + " " + origText;

        doc.replaceString(range.getStartOffset(), range.getEndOffset(), replacement);
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        if (editor != null) {
            editor.getCaretModel().moveToOffset(range.getStartOffset() + tagText.length() + 1);
        }
    }
}
