package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.validation.CyclicDependencyException;
import org.stvnadore.core.validation.DuplicateModuleImportException;
import org.stvnadore.core.validation.NamespaceCollisionException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@NullMarked
public final class StvnFlattenWorkspaceAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile entryFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || entryFile == null) {
            return;
        }

        Map<String, String> workspaceMap = new HashMap<>();
        ProjectFileIndex.getInstance(project).iterateContent(fileOrDir -> {
            if (!fileOrDir.isDirectory() && (fileOrDir.getName().endsWith(".stvn") || fileOrDir.getName().endsWith(".stvn_incl"))) {
                String path = StvnSchemaFlattener.normalizePath(fileOrDir.getPath());
                String content = getLatestContent(project, fileOrDir);
                workspaceMap.put(path, content);
            }
            return true;
        });

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

    private static String getLatestContent(Project project, VirtualFile file) {
        Document doc = FileDocumentManager.getInstance().getCachedDocument(file);
        if (doc != null && FileDocumentManager.getInstance().isDocumentUnsaved(doc)) {
            return doc.getText();
        }
        return LoadTextUtil.loadText(file).toString();
    }

    private void writeOutputAsset(Project project, VirtualFile entryFile, String content) {
        String outputName;
        if (entryFile.getName().endsWith(".stvn")) {
            outputName = entryFile.getName().substring(0, entryFile.getName().length() - 5) + ".stvn_inclf";
        } else {
            outputName = entryFile.getName() + ".stvn_inclf";
        }

        VirtualFile parent = entryFile.getParent();
        if (parent != null) {
            try {
                com.intellij.openapi.application.WriteAction.run(() -> {
                    VirtualFile outputFile = parent.findOrCreateChildData(this, outputName);
                    VfsUtil.saveText(outputFile, content);
                });
                StvnWorkspaceNotificationHelper.showSuccess(project, entryFile.getName(), outputName);
            } catch (IOException e) {
                StvnWorkspaceNotificationHelper.showGenericError(project, e);
            }
        }
    }

    @Override
    public void update(AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean visible = project != null && file != null && !file.isDirectory() &&
                (file.getName().endsWith(".stvn") || file.getName().endsWith(".stvn_incl"));
        e.getPresentation().setEnabledAndVisible(visible);
    }
}
