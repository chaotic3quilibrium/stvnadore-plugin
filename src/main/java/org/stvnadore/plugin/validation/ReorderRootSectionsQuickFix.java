package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.formatting.StvnRootSectionReorderer;

/**
 * High-priority intention quick-fix that reorders misplaced root sections into canonical
 * {@code :meta -> :defs -> :type -> :body} order while migrating leading and trailing {@code //} comments.
 */
@NullMarked
public final class ReorderRootSectionsQuickFix implements IntentionAction, PriorityAction {

    private static final String ACTION_TEXT =
        "Reorder document sections to canonical order ("
            + StvnVocabulary.KEYWORD_META + " -> "
            + StvnVocabulary.KEYWORD_DEFS + " -> "
            + StvnVocabulary.KEYWORD_TYPE + " -> "
            + StvnVocabulary.KEYWORD_BODY + ")";

    /**
     * Constructs a stateless, Intention-Preview-safe root-section reordering quick-fix.
     */
    public ReorderRootSectionsQuickFix() {}

    @Override
    public @NotNull String getText() {
        return ACTION_TEXT;
    }

    @Override
    public @NotNull String getFamilyName() {
        return ACTION_TEXT;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        return StvnRootSectionReorderer.canReorderRootSections(file);
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file)
            throws IncorrectOperationException {
        StvnRootSectionReorderer.reorderIfNeeded(project, file);
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }
}
