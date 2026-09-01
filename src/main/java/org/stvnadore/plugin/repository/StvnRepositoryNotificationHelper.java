package org.stvnadore.plugin.repository;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.repository.dto.CompileDiagnosticDto;
import org.stvnadore.plugin.repository.dto.SchemaMetadataDto;

import java.util.List;

/**
 * Balloon notification manager for STVN Schema Repository operations.
 */
@NullMarked
public final class StvnRepositoryNotificationHelper {

    private static final String GROUP_ID = "STVN Repository Group";

    private StvnRepositoryNotificationHelper() {}

    /**
     * Displays a success notification when a schema is published (HTTP 201 Created).
     *
     * @param project the active IntelliJ project
     * @param metadata the published schema metadata
     */
    public static void showSuccess(Project project, SchemaMetadataDto metadata) {
        String msg = String.format(
                "Schema <b>%s</b> published successfully.<br><b>Shape:</b> <code>%s</code><br><b>CAS Hash:</b> <code>%s</code>",
                metadata.schemaName(),
                metadata.shapeSignature(),
                metadata.casHash()
        );
        showNotification(project, "STVN Schema Published (201 Created)", msg, NotificationType.INFORMATION);
    }

    /**
     * Displays a notification when a schema already exists with identical content (HTTP 200 OK).
     *
     * @param project the active IntelliJ project
     * @param metadata the existing schema metadata
     */
    public static void showIdempotent(Project project, SchemaMetadataDto metadata) {
        String msg = String.format(
                "Schema <b>%s</b> is already registered with identical content.<br><b>CAS Hash:</b> <code>%s</code>",
                metadata.schemaName(),
                metadata.casHash()
        );
        showNotification(project, "Schema Already Registered (200 OK)", msg, NotificationType.INFORMATION);
    }

    /**
     * Displays a notification when a schema mutation conflict occurs (HTTP 409 Conflict).
     *
     * @param project the active IntelliJ project
     * @param schemaName the conflicting schema name
     * @param message conflict details
     */
    public static void showConflict(Project project, String schemaName, String message) {
        String msg = String.format(
                "Cannot overwrite schema <b>%s</b>.<br><b>Conflict Details:</b> %s<br><i>STVN schemas are content-addressable and strictly immutable.</i>",
                schemaName,
                message
        );
        showNotification(project, "Schema Mutation Prohibited (409 Conflict)", msg, NotificationType.ERROR);
    }

    /**
     * Displays a notification when remote validation fails (HTTP 422 Unprocessable Entity).
     *
     * @param project the active IntelliJ project
     * @param schemaName the rejected schema name
     * @param diagnostics compilation diagnostic error list
     */
    public static void showValidationError(Project project, String schemaName, List<CompileDiagnosticDto> diagnostics) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Remote repository rejected schema <b>%s</b> with %d diagnostic error(s):<br>", schemaName, diagnostics.size()));
        for (CompileDiagnosticDto diag : diagnostics) {
            sb.append(String.format(" • [Line %d:%d] %s<br>", diag.line(), diag.column(), diag.message()));
        }
        showNotification(project, "Schema Validation Failed (422 Unprocessable Entity)", sb.toString(), NotificationType.ERROR);
    }

    /**
     * Displays a notification when request content type is unsupported (HTTP 415).
     *
     * @param project the active IntelliJ project
     * @param message error description
     */
    public static void showUnsupportedMediaType(Project project, String message) {
        showNotification(project, "Repository Error (415 Unsupported Media Type)", message, NotificationType.ERROR);
    }

    /**
     * Displays a notification when transport or network failure prevents communication with the repository.
     *
     * @param project the active IntelliJ project
     * @param repoUrl repository base URL
     * @param message error description
     */
    public static void showTransportError(Project project, String repoUrl, String message) {
        String msg = String.format(
                "Failed to connect to STVN Schema Repository at <code>%s</code>.<br><b>Error:</b> %s<br><i>Verify repository status under Settings > Tools > STVN Schema Repository.</i>",
                repoUrl,
                message
        );
        showNotification(project, "Repository Connection Failed", msg, NotificationType.ERROR);
    }

    private static void showNotification(Project project, String title, String htmlContent, NotificationType type) {
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(title, htmlContent, type);
        notification.notify(project);
    }
}