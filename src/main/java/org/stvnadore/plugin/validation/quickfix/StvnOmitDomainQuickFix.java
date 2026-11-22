package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetaDomainFacet;
import org.stvnadore.psi.MetaElement;

/**
 * Quick-fix remediating Layer 1 {@code ERR_RESERVED_DOMAIN_PROHIBITED} ({@code E038}) and {@code W002}
 * by omitting the domain segment from the filename and deleting the {@code #domain} facet from {@code :meta}.
 */
@NullMarked
public final class StvnOmitDomainQuickFix implements LocalQuickFix, IntentionAction, PriorityAction {

    @SafeFieldForPreview
    private final String targetFileName;

    /**
     * Constructs an omit-domain quick-fix targeting {@code targetFileName}.
     *
     * @param targetFileName preview target filename without stem slot 1
     */
    public StvnOmitDomainQuickFix(String targetFileName) {
        this.targetFileName = targetFileName;
    }

    /**
     * Constructs an omit-domain quick-fix derived from {@code file}.
     *
     * @param file active STVN PSI file
     */
    public StvnOmitDomainQuickFix(@NotNull PsiFile file) {
        String computed = computeTargetFileName(file.getName());
        this.targetFileName = computed != null ? computed : file.getName();
    }

    /**
     * Creates a {@link StvnOmitDomainQuickFix} when {@code file} declares a multi-segment filename stem.
     *
     * @param file active STVN PSI file
     * @return configured quick-fix, or {@code null} when inapplicable
     */
    public static @Nullable StvnOmitDomainQuickFix create(@NotNull PsiFile file) {
        String newName = computeTargetFileName(file.getName());
        if (newName == null) {
            return null;
        }
        return new StvnOmitDomainQuickFix(newName);
    }

    /**
     * Computes the single-stem target filename omitting stem slot 1.
     *
     * @param fileName current filename
     * @return single-stem target filename, or {@code null} when already single-stem
     */
    public static @Nullable String computeTargetFileName(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        String ext = fileName.substring(lastDot);
        String stem = fileName.substring(0, lastDot);
        String[] dotParts = stem.split("\\.");
        if (dotParts.length < 2 || dotParts[0].isEmpty()) {
            return null;
        }
        return dotParts[0] + ext;
    }

    @Override
    public @NotNull String getText() {
        return "Omit domain from filename (rename to '" + targetFileName + "')";
    }

    @Override
    public @NotNull String getName() {
        return getText();
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Document Identity";
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.HIGH;
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (file == null || !file.isValid()) {
            return false;
        }
        VirtualFile vFile = file.getVirtualFile();
        if (vFile == null || !vFile.isValid()) {
            return false;
        }
        if (file.getName().equals(targetFileName)) {
            return false;
        }
        VirtualFile parent = vFile.getParent();
        return parent == null || parent.findChild(targetFileName) == null;
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        return StvnFileRenameExecutor.buildRenamePreview(file, targetFileName);
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull ProblemDescriptor previewDescriptor
    ) {
        PsiElement element = previewDescriptor.getPsiElement();
        return StvnFileRenameExecutor.buildRenamePreview(
            element != null ? element.getContainingFile() : null,
            targetFileName
        );
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement();
        if (element == null) {
            return;
        }
        PsiFile file = element.getContainingFile();
        if (file != null) {
            invoke(project, null, file);
        }
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (file == null || !file.isValid()) {
            return;
        }
        String liveTarget = computeTargetFileName(file.getName());
        if (liveTarget == null) {
            liveTarget = targetFileName;
        }
        StvnFileRenameExecutor.executeRenameWithCollisionFeedback(
            project,
            editor,
            file,
            liveTarget,
            getText(),
            () -> {
                if (file.getTextLength() > 0) {
                    MetaDomainFacet facet = PsiTreeUtil.findChildOfType(file, MetaDomainFacet.class);
                    if (facet != null) {
                        MetaBlock metaBlock = PsiTreeUtil.getParentOfType(facet, MetaBlock.class);
                        PsiElement target = facet.getParent() instanceof MetaElement ? facet.getParent() : facet;
                        var prev = target.getPrevSibling();
                        if (prev instanceof PsiWhiteSpace) {
                            prev.delete();
                        }
                        target.delete();
                        if (metaBlock != null && metaBlock.isValid()) {
                            CodeStyleManager.getInstance(project).reformat(metaBlock);
                        }
                    }
                }
            }
        );
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
