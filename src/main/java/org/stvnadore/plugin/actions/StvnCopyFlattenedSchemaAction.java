package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.printer.AstPrettyPrinter;
import org.stvnadore.core.printer.StvnSourceFormatter;
import org.stvnadore.core.validation.CyclicDependencyException;
import org.stvnadore.core.validation.DuplicateModuleImportException;
import org.stvnadore.core.validation.NamespaceCollisionException;
import org.stvnadore.plugin.icons.StvnIcons;

import java.awt.datatransfer.StringSelection;
import java.util.Map;

/**
 * Non-destructive action that resolves transitive workspace dependencies for the active STVN
 * file and copies the canonical pretty-printed hermetic schema to the system clipboard.
 */
@NullMarked
public final class StvnCopyFlattenedSchemaAction extends AnAction {

    /**
     * Constructs a new {@code StvnCopyFlattenedSchemaAction} instance with the STVN cube icon.
     */
    public StvnCopyFlattenedSchemaAction() {
        super("Flatten Active STVN Schema to Clipboard");
        getTemplatePresentation().setIcon(StvnIcons.STVN);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile entryFile = resolveActiveVirtualFile(e);
        if (project == null || entryFile == null || entryFile.isDirectory()) {
            return;
        }

        Map<String, String> workspaceMap = StvnFlattenWorkspaceAction.collectWorkspaceSources(project, entryFile);
        String entryPointPath = StvnSchemaFlattener.normalizePath(entryFile.getPath());

        try {
            String flattenedCas = StvnSchemaFlattener.flatten(workspaceMap, entryPointPath);
            String flattenedOutput = formatPrettyFlattenedDefs(flattenedCas, entryFile.getName());
            CopyPasteManager.getInstance().setContents(new StringSelection(flattenedOutput));
            StvnWorkspaceNotificationHelper.showClipboardFlattenSuccess(project, entryFile.getName());
        } catch (CyclicDependencyException ex) {
            StvnWorkspaceNotificationHelper.showCycleError(project, ex);
        } catch (NamespaceCollisionException ex) {
            StvnWorkspaceNotificationHelper.showCollisionError(project, ex);
        } catch (DuplicateModuleImportException ex) {
            StvnWorkspaceNotificationHelper.showDuplicateImportError(project, ex);
        } catch (Exception ex) {
            StvnWorkspaceNotificationHelper.showGenericError(project, ex);
        }
    }

    static String formatPrettyFlattenedDefs(String flattenedCas, String entryFileName) {
        String syntheticDefsName = deriveDefsStemName(entryFileName);
        StvnParserConfig substrateAwareConfig = new StvnParserConfig(
            false,
            StvnParserConfig.DEFAULT_MAX_DIAGNOSTICS,
            true
        );
        StvnCompilationResult<StvnDocument> result = StvnCompiler.compileToResult(
            flattenedCas,
            syntheticDefsName,
            substrateAwareConfig
        );
        if (!result.hasErrors() && result.document().isPresent()) {
            return AstPrettyPrinter.print(result.document().get());
        }
        return StvnSourceFormatter.tryFormat(flattenedCas).orElse(flattenedCas);
    }

    /**
     * Maps 1-stem and 2-stem STVN filenames to their corresponding {@code .stvn_d} schema filename
     * under Rule {@code E032}.
     *
     * @param fileName active STVN filename
     * @return derived {@code .stvn_d} filename
     */
    public static String deriveDefsStemName(String fileName) {
        if (fileName.endsWith(".stvn_di")) {
            return fileName.substring(0, fileName.length() - 8) + ".stvn_d";
        }
        if (fileName.endsWith(".stvn_i")) {
            return fileName.substring(0, fileName.length() - 7) + ".stvn_d";
        }
        if (fileName.endsWith(".stvn")) {
            return fileName.substring(0, fileName.length() - 5) + ".stvn_d";
        }
        return fileName;
    }

    private static @Nullable VirtualFile resolveActiveVirtualFile(AnActionEvent e) {
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (vf != null) {
            return vf;
        }
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        return psiFile != null ? psiFile.getVirtualFile() : null;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = resolveActiveVirtualFile(e);
        boolean visible = project != null
            && file != null
            && !file.isDirectory()
            && StvnFlattenWorkspaceAction.isTextualStvnSchemaOrPayload(file.getName());
        e.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
