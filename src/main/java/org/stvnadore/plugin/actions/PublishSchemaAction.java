package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.validation.CyclicDependencyException;
import org.stvnadore.core.validation.DuplicateModuleImportException;
import org.stvnadore.core.validation.NamespaceCollisionException;
import org.stvnadore.plugin.repository.PublishResponse;
import org.stvnadore.plugin.repository.StvnRepositoryClient;
import org.stvnadore.plugin.repository.StvnRepositoryNotificationHelper;
import org.stvnadore.plugin.settings.StvnRepositorySettings;

import java.util.HashMap;
import java.util.Map;

/**
 * Action to flatten on-the-fly and publish an STVN schema to the configured remote repository.
 * Visible and enabled strictly for .stvn_incl and .stvn_inclf files.
 */
@NullMarked
public final class PublishSchemaAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile entryFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || entryFile == null) {
            return;
        }

        String fileName = entryFile.getName();
        String schemaName = deriveSchemaName(fileName);
        String schemaContent;

        if (fileName.endsWith(".stvn_incl")) {
            // 1. Perform in-memory workspace flattening on-the-fly
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
                schemaContent = StvnSchemaFlattener.flatten(workspaceMap, entryPointPath);
            } catch (CyclicDependencyException ex) {
                StvnWorkspaceNotificationHelper.showCycleError(project, ex);
                return;
            } catch (NamespaceCollisionException ex) {
                StvnWorkspaceNotificationHelper.showCollisionError(project, ex);
                return;
            } catch (DuplicateModuleImportException ex) {
                StvnWorkspaceNotificationHelper.showDuplicateImportError(project, ex);
                return;
            } catch (Exception ex) {
                StvnWorkspaceNotificationHelper.showGenericError(project, ex);
                return;
            }
        } else if (fileName.endsWith(".stvn_inclf")) {
            // 2. Standalone flattened schema: transmit buffer directly
            schemaContent = getLatestContent(project, entryFile);
        } else {
            return;
        }

        // 3. Obtain project repository settings
        var settings = StvnRepositorySettings.getInstance(project);
        String repoUrl = settings.getRepoUrl();
        int timeoutMs = settings.getTimeoutMs();

        // 4. Dispatch async publish request on Java 21 Virtual Thread
        StvnRepositoryClient.publishSchemaAsync(repoUrl, schemaName, schemaContent, timeoutMs)
                .thenAccept(response -> ApplicationManager.getApplication().invokeLater(() -> {
                    switch (response) {
                        case PublishResponse.Success(var metadata) ->
                                StvnRepositoryNotificationHelper.showSuccess(project, metadata);
                        case PublishResponse.Idempotent(var metadata) ->
                                StvnRepositoryNotificationHelper.showIdempotent(project, metadata);
                        case PublishResponse.Conflict(var msg) ->
                                StvnRepositoryNotificationHelper.showConflict(project, schemaName, msg);
                        case PublishResponse.ValidationError(var diags) ->
                                StvnRepositoryNotificationHelper.showValidationError(project, schemaName, diags);
                        case PublishResponse.UnsupportedMediaType(var msg) ->
                                StvnRepositoryNotificationHelper.showUnsupportedMediaType(project, msg);
                        case PublishResponse.TransportError(var msg, var cause) ->
                                StvnRepositoryNotificationHelper.showTransportError(project, repoUrl, msg);
                    }
                }));
    }

    @Override
    public void update(AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean visible = project != null && file != null && !file.isDirectory() &&
                (file.getName().endsWith(".stvn_incl") || file.getName().endsWith(".stvn_inclf"));
        e.getPresentation().setEnabledAndVisible(visible);
    }

    private static String deriveSchemaName(String fileName) {
        if (fileName.endsWith(".stvn_incl")) {
            return fileName.substring(0, fileName.length() - ".stvn_incl".length());
        }
        if (fileName.endsWith(".stvn_inclf")) {
            return fileName.substring(0, fileName.length() - ".stvn_inclf".length());
        }
        return fileName;
    }

    private static String getLatestContent(Project project, VirtualFile file) {
        Document doc = FileDocumentManager.getInstance().getCachedDocument(file);
        if (doc != null && FileDocumentManager.getInstance().isDocumentUnsaved(doc)) {
            return doc.getText();
        }
        return LoadTextUtil.loadText(file).toString();
    }
}