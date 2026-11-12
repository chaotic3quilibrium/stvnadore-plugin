package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetaDomainFacet;

import java.io.IOException;
import java.util.List;

/**
 * Quick-fix remediating Layer 1 ERR_RESERVED_DOMAIN_PROHIBITED (E038) by replacing
 * the reserved domain segment with a compliant user domain placeholder ('domain').
 */
@NullMarked
public final class StvnRenameDomainQuickFix implements LocalQuickFix, IntentionAction, PriorityAction {

    private static final Logger LOG = Logger.getInstance(StvnRenameDomainQuickFix.class);
    private static final String DEFAULT_USER_DOMAIN = "domain";

    @SafeFieldForPreview
    private final String targetFileName;

    public StvnRenameDomainQuickFix(String targetFileName) {
        this.targetFileName = targetFileName;
    }

    public StvnRenameDomainQuickFix(@NotNull PsiFile file) {
        String computed = computeTargetFileName(file.getName());
        this.targetFileName = computed != null ? computed : file.getName();
    }

    public static @Nullable StvnRenameDomainQuickFix create(@NotNull PsiFile file) {
        String newName = computeTargetFileName(file.getName());
        if (newName == null) return null;
        return new StvnRenameDomainQuickFix(newName);
    }

    public static @Nullable String computeTargetFileName(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0) return null;
        String ext = fileName.substring(lastDot);
        String stem = fileName.substring(0, lastDot);
        String[] dotParts = stem.split("\\.");
        if (dotParts.length < 2 || dotParts[0].isEmpty()) return null;
        return dotParts[0] + "." + DEFAULT_USER_DOMAIN + ext;
    }

    @Override
    public @NotNull String getText() {
        return "Replace domain with user domain (rename to '" + targetFileName + "')";
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
        return Priority.NORMAL;
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (file == null || !file.isValid()) return false;
        VirtualFile vFile = file.getVirtualFile();
        if (vFile == null || !vFile.isValid()) return false;
        if (file.getName().equals(targetFileName)) return false;
        VirtualFile parent = vFile.getParent();
        return parent == null || parent.findChild(targetFileName) == null;
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        return renamePreview(file, targetFileName);
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull ProblemDescriptor previewDescriptor
    ) {
        PsiElement element = previewDescriptor.getPsiElement();
        return renamePreview(element != null ? element.getContainingFile() : null, targetFileName);
    }

    private static @NotNull IntentionPreviewInfo renamePreview(@Nullable PsiFile file, String targetFileName) {
        if (file == null) {
            return IntentionPreviewInfo.EMPTY;
        }
        VirtualFile vFile = file.getOriginalFile().getVirtualFile();
        if (vFile == null) {
            vFile = file.getVirtualFile();
        }
        return vFile != null ? IntentionPreviewInfo.rename(vFile, targetFileName) : IntentionPreviewInfo.EMPTY;
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
        if (file == null || !file.isValid()) return;
        VirtualFile vFile = file.getVirtualFile();
        if (vFile == null || !vFile.isValid()) return;

        var readonlyStatus = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(List.of(vFile));
        if (readonlyStatus.hasReadonlyFiles()) return;

        WriteCommandAction.runWriteCommandAction(project, getText(), null, () -> {
            try {
                // 1. Buffer synchronization: Update #domain facet value to "domain"
                if (file.getTextLength() > 0) {
                    MetaDomainFacet facet = PsiTreeUtil.findChildOfType(file, MetaDomainFacet.class);
                    if (facet != null) {
                        var stringLit = facet.getStringLiteral();
                        if (stringLit != null) {
                            var replacement = StvnElementFactory.createStringLiteral(project, "\"" + DEFAULT_USER_DOMAIN + "\"");
                            stringLit.replace(replacement);
                            MetaBlock metaBlock = PsiTreeUtil.getParentOfType(facet, MetaBlock.class);
                            if (metaBlock != null && metaBlock.isValid()) {
                                CodeStyleManager.getInstance(project).reformat(metaBlock);
                            }
                            var doc = PsiDocumentManager.getInstance(project).getDocument(file);
                            if (doc != null) {
                                PsiDocumentManager.getInstance(project).commitDocument(doc);
                            }
                        }
                    }
                }

                // 2. VFS Operation: Rename virtual file
                vFile.rename(this, targetFileName);
            } catch (IOException e) {
                LOG.error("Failed to rename file '" + vFile.getName() + "' to '" + targetFileName + "'", e);
            }
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
