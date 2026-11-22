package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;

/**
 * Primary atomic quick-fix ({@link PriorityAction.Priority#TOP}) offered on every mismatching
 * {@code :meta} identity facet ({@code #name}, {@code #domain}, {@code #kind}) whenever {@code >= 2}
 * {@code :meta} identity components mismatch the physical filename.
 * <p>
 * Renames {@code scratch.stvn} directly to {@code <name>[.<domain>].<ext>} in a single undoable
 * {@code WriteCommandAction} without intermediate single-stem collisions.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnMatchMetaFileRenameQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
        implements PriorityAction {

    @SafeFieldForPreview
    private final String previewTargetFileName;

    /**
     * Constructs an atomic {@code :meta} file-rename quick-fix anchored to {@code owningFacet}.
     *
     * @param owningFacet           owning {@code MetaNameFacet}, {@code MetaDomainFacet}, or {@code MetaKindFacet}
     * @param previewTargetFileName synthesized target filename displayed in the intention label
     */
    public StvnMatchMetaFileRenameQuickFix(PsiElement owningFacet, String previewTargetFileName) {
        super(owningFacet);
        this.previewTargetFileName = previewTargetFileName;
    }

    @Override
    public @NotNull String getText() {
        return "Rename file to '" + previewTargetFileName + "' (match " + StvnVocabulary.KEYWORD_META + ")";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Rename file to match " + StvnVocabulary.KEYWORD_META;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public boolean isAvailable(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        if (!startElement.isValid() || !StvnFileRenameExecutor.isCaretWithinFacet(editor, startElement)) {
            return false;
        }
        var summary = StvnFileRenameExecutor.resolveMetaIdentitySummary(file);
        return summary != null
            && summary.mismatchedComponentCount() >= 2
            && !file.getName().equals(summary.targetFileName());
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        return StvnFileRenameExecutor.buildRenamePreview(file, previewTargetFileName);
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull ProblemDescriptor previewDescriptor
    ) {
        PsiElement el = previewDescriptor.getPsiElement();
        return StvnFileRenameExecutor.buildRenamePreview(el != null ? el.getContainingFile() : null, previewTargetFileName);
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        var liveSummary = StvnFileRenameExecutor.resolveMetaIdentitySummary(file);
        String liveTarget = liveSummary != null ? liveSummary.targetFileName() : previewTargetFileName;
        StvnFileRenameExecutor.executeRenameWithCollisionFeedback(
            project,
            editor,
            file,
            liveTarget,
            getText(),
            null
        );
    }
}
