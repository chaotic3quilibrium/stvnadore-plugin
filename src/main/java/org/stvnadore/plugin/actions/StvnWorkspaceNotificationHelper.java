package org.stvnadore.plugin.actions;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.validation.CyclicDependencyException;
import org.stvnadore.core.validation.DuplicateModuleImportException;
import org.stvnadore.core.validation.NamespaceCollisionException;

import java.io.File;
import java.util.List;

/**
 * Notification helper for STVN workspace analysis, flattening, and error reporting.
 */
@NullMarked
public final class StvnWorkspaceNotificationHelper {

    private static final String GROUP_ID = "STVN Workspace Group";

    private StvnWorkspaceNotificationHelper() {}

    /**
     * Displays a notification detailing a cyclic dependency detected across workspace schema files.
     *
     * @param project the active IntelliJ project
     * @param ex the cyclic dependency exception
     */
    public static void showCycleError(Project project, CyclicDependencyException ex) {
        StringBuilder htmlMsg = new StringBuilder("<b>Cyclic dependency detected:</b><br>");
        List<String> rawPaths = ex.getOffendingIncludePathsRaw();
        List<String> canonicalPaths = ex.getOffendingIncludePathsCanonical();

        for (int i = 0; i < canonicalPaths.size(); i++) {
            String cp = canonicalPaths.get(i);
            String rp = rawPaths.size() > i ? rawPaths.get(i) : new File(cp).getName();
            htmlMsg.append(String.format(" -> <a href=\"open:%s\">%s</a>", cp, rp));
            if (i < canonicalPaths.size() - 1) {
                htmlMsg.append("<br>");
            }
        }

        showNotification(project, "STVN Workspace Resolution Failed", htmlMsg.toString(), NotificationType.ERROR);
    }

    /**
     * Displays a notification for nominal type or constant namespace collisions.
     *
     * @param project the active IntelliJ project
     * @param ex the namespace collision exception
     */
    public static void showCollisionError(Project project, NamespaceCollisionException ex) {
        showNotification(project, "STVN Workspace Resolution Failed", "<b>Namespace collision detected:</b><br>" + ex.getMessage(), NotificationType.ERROR);
    }

    /**
     * Displays a notification for duplicate module imports.
     *
     * @param project the active IntelliJ project
     * @param ex the duplicate module import exception
     */
    public static void showDuplicateImportError(Project project, DuplicateModuleImportException ex) {
        showNotification(project, "STVN Workspace Resolution Failed", "<b>Duplicate module import detected:</b><br>" + ex.getMessage(), NotificationType.ERROR);
    }

    /**
     * Displays a notification for generic or unexpected workspace errors.
     *
     * @param project the active IntelliJ project
     * @param ex the throwable error
     */
    public static void showGenericError(Project project, Throwable ex) {
        showNotification(project, "STVN Workspace Resolution Failed", "<b>Internal error:</b><br>" + ex.getMessage(), NotificationType.ERROR);
    }

    /**
     * Displays a success notification when a workspace is successfully flattened.
     *
     * @param project the active IntelliJ project
     * @param entryFileName name of entry schema file
     * @param outputFileName name of generated flattened output file
     */
    public static void showSuccess(Project project, String entryFileName, String outputFileName) {
        showNotification(project, "STVN Workspace Flattened", String.format("Successfully flattened workspace for <b>%s</b>. Output written to <b>%s</b>.", entryFileName, outputFileName), NotificationType.INFORMATION);
    }

    @SuppressWarnings("deprecation")
    private static void showNotification(Project project, String title, String htmlContent, NotificationType type) {
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(title, htmlContent, type);

        notification.setListener((notif, event) -> {
            if (event.getURL() != null && "open".equals(event.getURL().getProtocol())) {
                String targetPath = event.getURL().getPath();
                VirtualFile targetFile = LocalFileSystem.getInstance().findFileByPath(targetPath);
                if (targetFile != null) {
                    FileEditorManager.getInstance(project).openFile(targetFile, true);
                }
            }
        });

        notification.notify(project);
    }
}
