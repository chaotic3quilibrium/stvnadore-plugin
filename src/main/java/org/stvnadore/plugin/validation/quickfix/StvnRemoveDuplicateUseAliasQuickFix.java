package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.psi.UseMapAlias;

/**
 * Surgical quick-fix removing a duplicate {@link UseMapAlias} mapping entry inside a
 * {@code :use} alias block without leaving orphan blank lines.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnRemoveDuplicateUseAliasQuickFix
        extends LocalQuickFixAndIntentionActionOnPsiElement
        implements PriorityAction {

    /**
     * Canonical STE quick-fix label.
     */
    public static final String QUICK_FIX_NAME =
        "Remove duplicate '" + StvnVocabulary.KEYWORD_USE + "' alias mapping";

    /**
     * Constructs a new {@code StvnRemoveDuplicateUseAliasQuickFix}.
     *
     * @param duplicateAlias duplicate later {@link UseMapAlias}
     */
    public StvnRemoveDuplicateUseAliasQuickFix(UseMapAlias duplicateAlias) {
        super(duplicateAlias);
    }

    @Override
    public @NotNull String getText() {
        return QUICK_FIX_NAME;
    }

    @Override
    public @NotNull String getFamilyName() {
        return QUICK_FIX_NAME;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        if (!(startElement instanceof UseMapAlias alias) || !alias.isValid()) {
            return;
        }
        Document doc = file.getViewProvider().getDocument();
        if (doc == null) {
            return;
        }
        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        boolean commitPhysical = file.isPhysical() && !IntentionPreviewUtils.isPreviewElement(alias);
        if (commitPhysical) {
            docManager.doPostponedOperationsAndUnblockDocument(doc);
        }
        StvnRemoveDuplicateUseEntryQuickFix.deleteLineOrSpanCleanly(
            doc,
            alias.getTextRange().getStartOffset(),
            alias.getTextRange().getEndOffset()
        );
        if (commitPhysical) {
            docManager.commitDocument(doc);
        }
    }
}
