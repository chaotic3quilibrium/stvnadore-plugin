package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.printer.AstPrettyPrinter;
import org.stvnadore.plugin.StvnFile;

import java.awt.datatransfer.StringSelection;
import java.util.Optional;

/**
 * Editor context action that compiles active document text and copies canonical pretty-printed format
 * directly to the system clipboard without mutating the editor document buffer.
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnCopyCanonicalPrettyPrintAction extends AnAction {

    /**
     * Default constructor for copying canonical pretty-print projection.
     */
    public StvnCopyCanonicalPrettyPrintAction() {
        super("STVN: Copy Canonical Pretty Print to Clipboard");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || !(psiFile instanceof StvnFile)) {
            return;
        }

        String text = editor.getDocument().getText();
        Optional<StvnValue> astOptional;
        try {
            astOptional = StvnCompiler.compile(text);
        } catch (Exception ex) {
            StvnWorkspaceNotificationHelper.showGenericError(project, ex);
            return;
        }

        if (astOptional.isEmpty()) {
            return;
        }

        String formatted = AstPrettyPrinter.print(astOptional.get());
        CopyPasteManager.getInstance().setContents(new StringSelection(formatted));
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
