package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ast.StvnDocument;
import org.stvnadore.core.printer.AstCompactPrinter;
import org.stvnadore.plugin.StvnFile;

/**
 * Editor context action converting active STVN document text into canonical compact single-line layout.
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnConvertToCompactPrintAction extends AnAction {

    /**
     * Default constructor for the compact-print projection action.
     */
    public StvnConvertToCompactPrintAction() {
        super();
        getTemplatePresentation().setText("Canonicalize & Compact Print", false);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || !(psiFile instanceof StvnFile)) {
            return;
        }

        if (!StvnCanonicalizationGuard.confirmCanonicalization(project, psiFile)) {
            return;
        }

        Document document = editor.getDocument();
        String text = document.getText();

        StvnCompilationResult<StvnDocument> result;
        try {
            result = StvnCompiler.compile(text);
        } catch (Exception ex) {
            StvnWorkspaceNotificationHelper.showGenericError(project, ex);
            return;
        }

        if (result.hasErrors() || result.document().isEmpty()) {
            return;
        }

        String compact = AstCompactPrinter.print(result.document().get());
        WriteCommandAction.runWriteCommandAction(project, "STVN: Canonicalize & Compact Print", null, () -> {
            document.replaceString(0, document.getTextLength(), compact);
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        boolean enabled = project != null && editor != null && (psiFile instanceof StvnFile);
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
