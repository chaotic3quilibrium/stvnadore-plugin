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
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.psi.MetaKindFacet;

/**
 * Quick-fix ({@link PriorityAction.Priority#HIGH}) offered on {@code E031} ({@code ERR_DOCUMENT_KIND_MISMATCH})
 * renaming the active file extension to match the declared {@code #kind} facet ({@code #BODY -> .stvn},
 * {@code #BODY_INCLUDE -> .stvn_i}, {@code #DEFS -> .stvn_d}, {@code #DEFS_INCLUDE -> .stvn_di}).
 *
 * @since 2.0.0
 */
@NullMarked
public final class RenameFileExtensionToKindQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
        implements PriorityAction {

    @SafeFieldForPreview
    private final String targetExtension;
    @SafeFieldForPreview
    private final String previewTargetFileName;

    /**
     * Constructs a {@code #kind}-driven file-extension rename quick-fix.
     *
     * @param kindFacet             owning {@link MetaKindFacet}
     * @param targetExtension       canonical extension mapped from {@code #kind} (for example, {@code ".stvn_d"})
     * @param previewTargetFileName preview target filename ({@code <currentStem><targetExtension>})
     */
    public RenameFileExtensionToKindQuickFix(
            MetaKindFacet kindFacet,
            String targetExtension,
            String previewTargetFileName
    ) {
        super(kindFacet);
        this.targetExtension = targetExtension;
        this.previewTargetFileName = previewTargetFileName;
    }

    /**
     * Creates a {@link RenameFileExtensionToKindQuickFix} if {@code kindFacet} declares a recognized kind
     * whose canonical extension differs from {@code file}'s current extension.
     *
     * @param file      containing STVN PSI file
     * @param kindFacet mismatching {@link MetaKindFacet}
     * @return configured quick-fix, or {@code null} if inapplicable
     */
    public static @Nullable RenameFileExtensionToKindQuickFix createIfApplicable(
            PsiFile file,
            MetaKindFacet kindFacet
    ) {
        var kindValue = kindFacet.getMetaKindValue();
        if (kindValue == null) {
            return null;
        }
        String targetExt = StvnFileRenameExecutor.mapKindKeywordToExtension(kindValue.getText().trim());
        if (targetExt == null) {
            return null;
        }
        String fileName = file.getName();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        String stemPart = fileName.substring(0, lastDot);
        String targetFileName = stemPart + targetExt;
        if (fileName.equals(targetFileName)) {
            return null;
        }
        return new RenameFileExtensionToKindQuickFix(kindFacet, targetExt, targetFileName);
    }

    @Override
    public @NotNull String getText() {
        return "Rename file extension to '" + targetExtension + "' (rename to '" + previewTargetFileName + "')";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Rename file extension to match " + StvnVocabulary.FACET_KW_META_KIND;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.HIGH;
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
        String liveTarget = computeLiveTargetFileName(file, startElement);
        return liveTarget != null && !file.getName().equals(liveTarget);
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
        String liveTarget = computeLiveTargetFileName(file, startElement);
        if (liveTarget == null) {
            liveTarget = previewTargetFileName;
        }
        StvnFileRenameExecutor.executeRenameWithCollisionFeedback(
            project,
            editor,
            file,
            liveTarget,
            getText(),
            null
        );
    }

    private @Nullable String computeLiveTargetFileName(PsiFile file, PsiElement startElement) {
        MetaKindFacet kf = startElement instanceof MetaKindFacet mkf
            ? mkf
            : PsiTreeUtil.getParentOfType(startElement, MetaKindFacet.class, false);
        String ext = targetExtension;
        if (kf != null && kf.getMetaKindValue() != null) {
            String mapped = StvnFileRenameExecutor.mapKindKeywordToExtension(kf.getMetaKindValue().getText().trim());
            if (mapped != null) {
                ext = mapped;
            }
        }
        String currentName = file.getName();
        int lastDot = currentName.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        return currentName.substring(0, lastDot) + ext;
    }
}
