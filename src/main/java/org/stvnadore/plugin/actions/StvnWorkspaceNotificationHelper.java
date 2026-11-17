package org.stvnadore.plugin.actions;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.validation.CyclicDependencyException;
import org.stvnadore.core.validation.DuplicateModuleImportException;
import org.stvnadore.core.validation.NamespaceCollisionException;

import java.io.File;
import java.util.List;

/**
 * Balloon notification helper for STVN VOP schema extraction, payload extraction,
 * in-place buffer canonicalization, and workspace resolution diagnostics.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnWorkspaceNotificationHelper {

    private static final String GROUP_ID = "STVN Workspace Group";

    private StvnWorkspaceNotificationHelper() {
    }

    /**
     * Displays an error notification detailing a cyclic {@code :include} dependency chain.
     *
     * @param project active IntelliJ project
     * @param ex      cyclic dependency exception
     */
    public static void showCycleError(Project project, CyclicDependencyException ex) {
        StringBuilder htmlMsg = new StringBuilder("<b>Cyclic dependency detected:</b><br>");
        List<String> rawPaths = ex.getOffendingIncludePathsRaw();
        List<String> canonicalPaths = ex.getOffendingIncludePathsCanonical();

        for (int i = 0; i < canonicalPaths.size(); i++) {
            String cp = canonicalPaths.get(i);
            String rp = rawPaths.size() > i ? rawPaths.get(i) : new File(cp).getName();
            htmlMsg.append(String.format(" -> <a href=\"open:%s\">%s</a>", cp, StringUtil.escapeXmlEntities(rp)));
            if (i < canonicalPaths.size() - 1) {
                htmlMsg.append("<br>");
            }
        }

        showNotification(project, "STVN Workspace Resolution Failed", htmlMsg.toString(), NotificationType.ERROR);
    }

    /**
     * Displays an error notification for nominal type or constant namespace collisions.
     *
     * @param project active IntelliJ project
     * @param ex      namespace collision exception
     */
    public static void showCollisionError(Project project, NamespaceCollisionException ex) {
        showNotification(
            project,
            "STVN Workspace Resolution Failed",
            "<b>Namespace collision detected:</b><br>" + StringUtil.escapeXmlEntities(String.valueOf(ex.getMessage())),
            NotificationType.ERROR
        );
    }

    /**
     * Displays an error notification for duplicate {@code :include} module imports.
     *
     * @param project active IntelliJ project
     * @param ex      duplicate module import exception
     */
    public static void showDuplicateImportError(Project project, DuplicateModuleImportException ex) {
        showNotification(
            project,
            "STVN Workspace Resolution Failed",
            "<b>Duplicate module import detected:</b><br>" + StringUtil.escapeXmlEntities(String.valueOf(ex.getMessage())),
            NotificationType.ERROR
        );
    }

    /**
     * Displays an error notification for unexpected workspace or VFS I/O errors.
     *
     * @param project active IntelliJ project
     * @param ex      throwable error
     */
    public static void showGenericError(Project project, Throwable ex) {
        showNotification(
            project,
            "STVN Workspace Resolution Failed",
            "<b>Projection error:</b><br>" + StringUtil.escapeXmlEntities(String.valueOf(ex.getMessage())),
            NotificationType.ERROR
        );
    }

    /**
     * Displays an error notification when {@code StvnCompiler} reports a fatal diagnostic during projection.
     *
     * @param project       active IntelliJ project
     * @param entryFileName source compilation unit filename
     * @param primaryError  primary fatal {@link StvnDiagnostic}
     */
    public static void showCompilationError(Project project, String entryFileName, StvnDiagnostic primaryError) {
        String codePrefix = primaryError.canonicalCode()
            .map(code -> "[" + code + "] ")
            .orElse("");
        String location = primaryError.line() > 0
            ? " (line " + primaryError.line() + ", col " + (primaryError.column() + 1) + ")"
            : "";
        String content = String.format(
            "Cannot project <b>%s</b>%s:<br>%s%s",
            StringUtil.escapeXmlEntities(entryFileName),
            location,
            StringUtil.escapeXmlEntities(codePrefix),
            StringUtil.escapeXmlEntities(primaryError.message())
        );
        showNotification(project, "STVN Projection Failed", content, NotificationType.ERROR);
    }

    /**
     * Displays a warning notification when a payload unit declares zero non-prelude nominal definitions
     * or constants to export as a {@code .stvn_d} schema (preventing invalid {@code E002} empty {@code :defs}).
     *
     * @param project        active IntelliJ project
     * @param entryFileName  source compilation unit filename
     * @param targetDefsName derived target {@code .stvn_d} filename
     */
    public static void showEmptySchemaDefsWarning(
            Project project,
            String entryFileName,
            String targetDefsName
    ) {
        String content = String.format(
            "Cannot extract hermetic <code>%s</code> schema <b>%s</b> from <b>%s</b>: "
                + "document declares zero non-prelude nominal types or constants in <code>%s</code>.",
            StvnVocabulary.KIND_KW_DEFS,
            StringUtil.escapeXmlEntities(targetDefsName),
            StringUtil.escapeXmlEntities(entryFileName),
            StvnVocabulary.KEYWORD_DEFS
        );
        showNotification(project, "STVN Hermetic Schema Extraction Skipped", content, NotificationType.WARNING);
    }

    /**
     * Displays a warning notification when canonicalizing a definitions module in place would produce
     * an empty {@code :defs} block ({@code E002 / ERR_EMPTY_DEFS_BLOCK}).
     *
     * @param project        active IntelliJ project
     * @param activeFileName active definitions schema filename
     */
    public static void showEmptyInPlaceDefsWarning(Project project, String activeFileName) {
        String content = String.format(
            "Cannot canonicalize <b>%s</b> in place: desugaring produces zero non-prelude definitions in <code>%s</code>.",
            StringUtil.escapeXmlEntities(activeFileName),
            StvnVocabulary.KEYWORD_DEFS
        );
        showNotification(project, "STVN In-Place Canonicalization Skipped", content, NotificationType.WARNING);
    }

    /**
     * Displays a confirmation balloon when a hermetic {@code .stvn_d} schema is copied to the clipboard.
     *
     * @param project        active IntelliJ project
     * @param targetDefsName derived {@code .stvn_d} schema filename
     * @param formatLabel    format profile label ({@code "pretty"} or {@code "compact CAS"})
     */
    public static void showSchemaClipboardSuccess(Project project, String targetDefsName, String formatLabel) {
        String content = String.format(
            "Copied %s hermetic schema <b>%s</b> (<code>%s</code>) to clipboard.",
            StringUtil.escapeXmlEntities(formatLabel),
            StringUtil.escapeXmlEntities(targetDefsName),
            StvnVocabulary.KIND_KW_DEFS
        );
        showNotification(project, "STVN Schema Copied to Clipboard", content, NotificationType.INFORMATION);
    }

    /**
     * Displays a confirmation balloon when a hermetic sibling {@code .stvn_d} schema file is written to disk.
     *
     * @param project        active IntelliJ project
     * @param targetDefsName exported {@code .stvn_d} filename
     * @param formatLabel    format profile label ({@code "pretty"} or {@code "compact CAS"})
     */
    public static void showSchemaExportSuccess(Project project, String targetDefsName, String formatLabel) {
        String content = String.format(
            "Exported %s hermetic schema <b>%s</b> (<code>%s</code>).",
            StringUtil.escapeXmlEntities(formatLabel),
            StringUtil.escapeXmlEntities(targetDefsName),
            StvnVocabulary.KIND_KW_DEFS
        );
        showNotification(project, "STVN Schema Exported", content, NotificationType.INFORMATION);
    }

    /**
     * Displays a confirmation balloon when a hermetic {@code .stvn} payload is copied to the clipboard.
     *
     * @param project           active IntelliJ project
     * @param targetPayloadName derived {@code .stvn} payload filename
     * @param formatLabel       format profile label ({@code "pretty"} or {@code "compact"})
     */
    public static void showPayloadClipboardSuccess(Project project, String targetPayloadName, String formatLabel) {
        String content = String.format(
            "Copied %s hermetic payload <b>%s</b> (<code>%s</code>) to clipboard.",
            StringUtil.escapeXmlEntities(formatLabel),
            StringUtil.escapeXmlEntities(targetPayloadName),
            StvnVocabulary.KIND_KW_BODY
        );
        showNotification(project, "STVN Payload Copied to Clipboard", content, NotificationType.INFORMATION);
    }

    /**
     * Displays a confirmation balloon when a hermetic sibling {@code .stvn} payload file is written to disk.
     *
     * @param project           active IntelliJ project
     * @param targetPayloadName exported {@code .stvn} filename
     * @param formatLabel       format profile label ({@code "pretty"} or {@code "compact"})
     */
    public static void showPayloadExportSuccess(Project project, String targetPayloadName, String formatLabel) {
        String content = String.format(
            "Exported %s hermetic payload <b>%s</b> (<code>%s</code>).",
            StringUtil.escapeXmlEntities(formatLabel),
            StringUtil.escapeXmlEntities(targetPayloadName),
            StvnVocabulary.KIND_KW_BODY
        );
        showNotification(project, "STVN Payload Exported", content, NotificationType.INFORMATION);
    }

    /**
     * Displays a confirmation balloon when the active buffer is replaced in-place with canonical IR.
     *
     * @param project        active IntelliJ project
     * @param activeFileName active compilation unit filename
     * @param kindKeyword    preserved document kind keyword ({@code #BODY}, {@code #BODY_INCLUDE}, {@code #DEFS}, {@code #DEFS_INCLUDE})
     * @param formatLabel    format profile label ({@code "pretty IR"} or {@code "compact IR"})
     */
    public static void showInPlaceReplaceSuccess(
            Project project,
            String activeFileName,
            String kindKeyword,
            String formatLabel
    ) {
        String content = String.format(
            "Replaced active buffer <b>%s</b> (<code>%s</code>) with %s.",
            StringUtil.escapeXmlEntities(activeFileName),
            StringUtil.escapeXmlEntities(kindKeyword),
            StringUtil.escapeXmlEntities(formatLabel)
        );
        showNotification(project, "STVN Buffer Canonicalized", content, NotificationType.INFORMATION);
    }

    @SuppressWarnings("deprecation")
    private static void showNotification(Project project, String title, String htmlContent, NotificationType type) {
        NotificationGroup group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID);
        if (group == null) {
            return;
        }
        Notification notification = group.createNotification(title, htmlContent, type);
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
