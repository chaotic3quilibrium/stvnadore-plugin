package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Quick-fix intention action navigating directly from a parent include statement
 * to an included child module containing compilation errors.
 */
@NullMarked
public final class OpenIncludedFileQuickFix implements IntentionAction, PriorityAction {

    private final String includePath;
    private final @Nullable VirtualFile targetVirtualFile;

    /**
     * Constructs a quick-fix to open an included file.
     *
     * @param includePath relative or physical include path string
     * @param targetVirtualFile target virtual file to open in the editor
     */
    public OpenIncludedFileQuickFix(String includePath, @Nullable VirtualFile targetVirtualFile) {
        this.includePath = includePath;
        this.targetVirtualFile = targetVirtualFile;
    }

    @Override
    public @NotNull String getText() {
        return "Open included module '" + includePath + "'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Module Ingestion";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        return targetVirtualFile != null && targetVirtualFile.isValid();
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (targetVirtualFile != null && targetVirtualFile.isValid()) {
            FileEditorManager.getInstance(project).openFile(targetVirtualFile, true);
        }
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.HIGH;
    }
}
