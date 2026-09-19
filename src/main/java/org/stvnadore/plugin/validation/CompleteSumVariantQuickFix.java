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
 * Intention action and quick-fix that replaces an incomplete bare '#' token with
 * a complete sum variant constructor tag (#Right, #Left, #1, #2, etc.) in value position.
 */
@NullMarked
public final class CompleteSumVariantQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    private final String tagText;
    private final String targetTypeName;
    private final Priority priority;

    public CompleteSumVariantQuickFix(PsiElement element, String tagText, String targetTypeName) {
        this(element, tagText, targetTypeName, Priority.NORMAL);
    }

    public CompleteSumVariantQuickFix(PsiElement element, String tagText, String targetTypeName, Priority priority) {
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
        return "Complete with " + tagText + " (-> " + targetTypeName + ")";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Complete Sum Variant";
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
        // Replaces the bare '#' token with the complete variant tag
        doc.replaceString(range.getStartOffset(), range.getEndOffset(), tagText);
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        if (editor != null) {
            editor.getCaretModel().moveToOffset(range.getStartOffset() + tagText.length());
        }
    }
}
