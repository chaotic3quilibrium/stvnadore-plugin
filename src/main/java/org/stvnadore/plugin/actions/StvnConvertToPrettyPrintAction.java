package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.printer.AstPrettyPrinter;
import org.stvnadore.core.printer.StvnSourceFormatter;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;

/**
 * Editor context action converting active STVN document text into canonical pretty-printed format.
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnConvertToPrettyPrintAction extends AnAction {

    /**
     * Default constructor for the pretty-print projection action.
     */
    public StvnConvertToPrettyPrintAction() {
        super();
        getTemplatePresentation().setText("Canonicalize & Pretty Print", false);
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
        String normalizedText = StvnSourceFormatter.tryFormat(text).orElse(text);
        VirtualFile vf = psiFile.getVirtualFile();
        String fileName = vf != null ? vf.getName() : psiFile.getName();
        String docPath = vf != null
            ? StvnCompiledDocumentCache.resolvePhysicalPath(vf, project, normalizedText)
            : fileName;
        StvnParserConfig config = StvnCompiledDocumentCache.isSystemSubstrateFileName(fileName)
            ? new StvnParserConfig(false, StvnParserConfig.DEFAULT_MAX_DIAGNOSTICS, true)
            : StvnParserConfig.DEFAULT;

        StvnCompilationResult<StvnDocument> result;
        try {
            result = StvnCompiler.compileToResult(normalizedText, docPath, config);
        } catch (Exception ex) {
            StvnWorkspaceNotificationHelper.showGenericError(project, ex);
            return;
        }

        if (result.hasErrors() || result.document().isEmpty()) {
            return;
        }

        StvnDocument canonicalDoc = result.document().get();
        String formatted = AstPrettyPrinter.print(canonicalDoc);

        WriteCommandAction.runWriteCommandAction(project, "STVN: Canonicalize & Pretty Print", null, () -> {
            document.replaceString(0, document.getTextLength(), formatted);
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
