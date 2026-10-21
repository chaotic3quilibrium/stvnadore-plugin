package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.refactoring.rename.RenameDialog;
import com.intellij.refactoring.rename.RenameHandler;
import com.intellij.refactoring.rename.RenameHandlerRegistry;
import com.intellij.refactoring.rename.RenameProcessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Quick-fix triggering IntelliJ's native refactoring rename dialog directly
 * from the editor error banner or Alt+Enter intention menu.
 */
@NullMarked
public final class StvnInteractiveRenameQuickFix implements IntentionAction, PriorityAction {

    @Override
    public @NotNull String getText() {
        return "Rename file interactively...";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Document Identity";
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.LOW;
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (file == null || !file.isValid()) return false;
        VirtualFile vFile = file.getVirtualFile();
        return vFile != null && vFile.isValid();
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (file == null || !file.isValid()) return;

        // In headless testing mode, modal dialogs cannot open
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            String fallbackName = StvnOmitDomainQuickFix.computeTargetFileName(file.getName());
            if (fallbackName == null) {
                int lastDot = file.getName().lastIndexOf('.');
                if (lastDot > 0) {
                    fallbackName = file.getName().substring(0, lastDot) + ".stvn_di";
                }
            }
            if (fallbackName != null) {
                new RenameProcessor(project, file, fallbackName, false, false).run();
            }
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed() || !file.isValid()) return;
            if (editor != null) {
                DataContext dataContext = DataManager.getInstance().getDataContext(editor.getComponent());
                RenameHandler renameHandler = RenameHandlerRegistry.getInstance().getRenameHandler(dataContext);
                if (renameHandler != null) {
                    renameHandler.invoke(project, editor, file, dataContext);
                    return;
                }
            }
            new RenameDialog(project, file, null, editor).show();
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
