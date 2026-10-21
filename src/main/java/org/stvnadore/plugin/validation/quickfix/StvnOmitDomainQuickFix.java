package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
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

import java.io.IOException;
import java.util.List;

/**
 * Quick-fix remediating Layer 1 ERR_RESERVED_DOMAIN_PROHIBITED (E038) by omitting
 * the reserved domain segment from the filename and deleting the #domain facet from :meta.
 */
@NullMarked
public final class StvnOmitDomainQuickFix implements IntentionAction, PriorityAction {

    private static final Logger LOG = Logger.getInstance(StvnOmitDomainQuickFix.class);

    private final String targetFileName;

    public StvnOmitDomainQuickFix(String targetFileName) {
        this.targetFileName = targetFileName;
    }

    public StvnOmitDomainQuickFix(@NotNull PsiFile file) {
        String computed = computeTargetFileName(file.getName());
        this.targetFileName = computed != null ? computed : file.getName();
    }

    public static @Nullable StvnOmitDomainQuickFix create(@NotNull PsiFile file) {
        String newName = computeTargetFileName(file.getName());
        if (newName == null) return null;
        return new StvnOmitDomainQuickFix(newName);
    }

    public static @Nullable String computeTargetFileName(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0) return null;
        String ext = fileName.substring(lastDot);
        String stem = fileName.substring(0, lastDot);
        String[] dotParts = stem.split("\\.");
        if (dotParts.length < 2 || dotParts[0].isEmpty()) return null;
        return dotParts[0] + ext;
    }

    @Override
    public @NotNull String getText() {
        return "Omit domain from filename (rename to '" + targetFileName + "')";
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
        if (file == null || !file.isValid()) return false;
        VirtualFile vFile = file.getVirtualFile();
        if (vFile == null || !vFile.isValid()) return false;
        if (file.getName().equals(targetFileName)) return false;
        VirtualFile parent = vFile.getParent();
        return parent == null || parent.findChild(targetFileName) == null;
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
                // 1. Buffer synchronization: Remove #domain facet if present
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
                        var doc = PsiDocumentManager.getInstance(project).getDocument(file);
                        if (doc != null) {
                            PsiDocumentManager.getInstance(project).commitDocument(doc);
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
