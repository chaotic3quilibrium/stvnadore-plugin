package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.TypeEntry;

/**
 * Quick-fix remediating Rule E031 (ERR_DOCUMENT_KIND_MISMATCH) by excising
 * prohibited ':type' or ':body' sections from definitions schema documents.
 */
@NullMarked
public final class RemoveIllegalStructuralSectionQuickFix
        extends LocalQuickFixAndIntentionActionOnPsiElement
        implements PriorityAction {

    private final String sectionKeyword;

    /**
     * Constructs a quick-fix targeted at a prohibited structural section.
     *
     * @param element the PSI element representing the section or a descendant token
     * @param sectionKeyword the structural section keyword (':type' or ':body')
     */
    public RemoveIllegalStructuralSectionQuickFix(@NotNull PsiElement element, @NotNull String sectionKeyword) {
        super(element);
        this.sectionKeyword = sectionKeyword;
    }

    @Override
    public @NotNull String getText() {
        return "Remove illegal section '" + sectionKeyword + "'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Remove illegal structural section";
    }

    @Override
    public @NotNull Priority getPriority() {
        return PriorityAction.Priority.HIGH;
    }

    @Override
    public boolean isAvailable(@NotNull Project project,
                               @NotNull PsiFile file,
                               @NotNull PsiElement startElement,
                               @NotNull PsiElement endElement) {
        return findEnclosingSection(startElement) != null;
    }

    @Override
    public void invoke(@NotNull Project project,
                       @NotNull PsiFile file,
                       @Nullable Editor editor,
                       @NotNull PsiElement startElement,
                       @NotNull PsiElement endElement) {
        var targetSection = findEnclosingSection(startElement);
        if (targetSection == null || !targetSection.isValid()) {
            return;
        }

        WriteCommandAction.runWriteCommandAction(project, getText(), null, () -> {
            var prev = targetSection.getPrevSibling();
            var next = targetSection.getNextSibling();
            if (prev instanceof PsiWhiteSpace) {
                prev.delete();
            } else if (next instanceof PsiWhiteSpace) {
                next.delete();
            }
            targetSection.delete();
        });
    }

    private static @Nullable PsiElement findEnclosingSection(@Nullable PsiElement element) {
        PsiElement current = element;
        while (current != null && !(current instanceof PsiFile)) {
            if (current instanceof TypeEntry || current instanceof BodyEntry) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }
}
