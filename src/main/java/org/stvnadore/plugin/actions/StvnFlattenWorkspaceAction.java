package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.validation.CyclicDependencyException;
import org.stvnadore.core.validation.DuplicateModuleImportException;
import org.stvnadore.core.validation.NamespaceCollisionException;
import org.stvnadore.plugin.icons.StvnIcons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Action that flattens modular STVN schemas into hermetic self-contained flat representations.
 */
@NullMarked
public final class StvnFlattenWorkspaceAction extends AnAction {

    /**
     * Constructs a new {@code StvnFlattenWorkspaceAction} instance with the STVN cube icon.
     */
    public StvnFlattenWorkspaceAction() {
        getTemplatePresentation().setIcon(StvnIcons.STVN);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile entryFile = resolveActiveVirtualFile(e);
        if (project == null || entryFile == null || entryFile.isDirectory()) {
            return;
        }

        Map<String, String> workspaceMap = collectWorkspaceSources(project, entryFile);
        String entryPointPath = StvnSchemaFlattener.normalizePath(entryFile.getPath());

        try {
            String flattenedContent = StvnSchemaFlattener.flatten(workspaceMap, entryPointPath);
            writeOutputAsset(project, entryFile, flattenedContent);
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

    /**
     * Collects all textual STVN schema and payload files in the project workspace, giving
     * precedence to unsaved editor document buffers over disk state.
     *
     * @param project   active IntelliJ project
     * @param entryFile entry-point STVN file
     * @return map of normalized workspace paths to STVN source text
     */
    static Map<String, String> collectWorkspaceSources(Project project, VirtualFile entryFile) {
        Map<String, String> workspaceMap = new HashMap<>();
        ProjectFileIndex.getInstance(project).iterateContent(fileOrDir -> {
            if (!fileOrDir.isDirectory() && isTextualStvnSchemaOrPayload(fileOrDir.getName())) {
                String path = StvnSchemaFlattener.normalizePath(fileOrDir.getPath());
                String content = getLatestContent(fileOrDir);
                workspaceMap.put(path, content);
            }
            return true;
        });
        String entryPath = StvnSchemaFlattener.normalizePath(entryFile.getPath());
        workspaceMap.putIfAbsent(entryPath, getLatestContent(entryFile));
        return workspaceMap;
    }

    /**
     * Returns {@code true} when {@code fileName} carries one of the four textual STVN extensions
     * ({@code .stvn}, {@code .stvn_i}, {@code .stvn_d}, or {@code .stvn_di}).
     *
     * @param fileName candidate filename
     * @return {@code true} for textual STVN schema or payload files
     */
    static boolean isTextualStvnSchemaOrPayload(String fileName) {
        return fileName.endsWith(".stvn_d")
            || fileName.endsWith(".stvn_di")
            || fileName.endsWith(".stvn")
            || fileName.endsWith(".stvn_i");
    }

    private static boolean isFlattenWorkspaceEntryExtension(String fileName) {
        return fileName.endsWith(".stvn")
            || fileName.endsWith(".stvn_di")
            || fileName.endsWith(".stvn_i");
    }

    private static @Nullable VirtualFile resolveActiveVirtualFile(AnActionEvent e) {
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (vf != null) {
            return vf;
        }
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        return psiFile != null ? psiFile.getVirtualFile() : null;
    }

    private static String getLatestContent(VirtualFile file) {
        Document doc = FileDocumentManager.getInstance().getCachedDocument(file);
        if (doc != null && FileDocumentManager.getInstance().isDocumentUnsaved(doc)) {
            return doc.getText();
        }
        return LoadTextUtil.loadText(file).toString();
    }

    private void writeOutputAsset(Project project, VirtualFile entryFile, String content) {
        String outputName;
        if (entryFile.getName().endsWith(".stvn")
                || entryFile.getName().endsWith(".stvn_di")
                || entryFile.getName().endsWith(".stvn_i")) {
            outputName = StvnCopyFlattenedSchemaAction.deriveDefsStemName(entryFile.getName());
        } else {
            outputName = entryFile.getName() + ".stvn_d";
        }

        String normalizedLfContent = content.replace("\r\n", "\n").replace("\r", "\n");
        VirtualFile parent = entryFile.getParent();
        if (parent != null) {
            Runnable vfsWriteTask = () -> {
                try {
                    WriteAction.run(() -> {
                        VirtualFile targetParent = !parent.exists()
                                && LocalFileSystem.PROTOCOL.equals(parent.getFileSystem().getProtocol())
                            ? VfsUtil.createDirectories(parent.getPath())
                            : parent;
                        VirtualFile outputFile = targetParent.findOrCreateChildData(this, outputName);
                        Document cachedDoc = FileDocumentManager.getInstance().getCachedDocument(outputFile);
                        if (cachedDoc != null) {
                            cachedDoc.setText(normalizedLfContent);
                            PsiDocumentManager.getInstance(project).commitDocument(cachedDoc);
                            FileDocumentManager.getInstance().saveDocument(cachedDoc);
                        } else {
                            outputFile.setBinaryContent(normalizedLfContent.getBytes(StandardCharsets.UTF_8));
                        }
                        VfsUtil.markDirty(true, false, outputFile);
                        FileBasedIndex.getInstance().requestReindex(outputFile);
                    });
                    StvnWorkspaceNotificationHelper.showSuccess(project, entryFile.getName(), outputName);
                } catch (IOException ex) {
                    StvnWorkspaceNotificationHelper.showGenericError(project, ex);
                }
            };
            var app = ApplicationManager.getApplication();
            if (app.isDispatchThread()) {
                vfsWriteTask.run();
            } else {
                app.invokeAndWait(vfsWriteTask);
            }
            return;
        }

        try {
            Path fallbackParent = Path.of(entryFile.getPath()).getParent();
            if (fallbackParent != null) {
                Files.createDirectories(fallbackParent);
                Files.writeString(fallbackParent.resolve(outputName), normalizedLfContent, StandardCharsets.UTF_8);
                StvnWorkspaceNotificationHelper.showSuccess(project, entryFile.getName(), outputName);
            }
        } catch (IOException ex) {
            StvnWorkspaceNotificationHelper.showGenericError(project, ex);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = resolveActiveVirtualFile(e);
        boolean visible = project != null
            && file != null
            && !file.isDirectory()
            && isFlattenWorkspaceEntryExtension(file.getName());
        e.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
