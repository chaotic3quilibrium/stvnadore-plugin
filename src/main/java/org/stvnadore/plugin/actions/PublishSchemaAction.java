package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.validation.CyclicDependencyException;
import org.stvnadore.core.validation.DuplicateModuleImportException;
import org.stvnadore.core.validation.NamespaceCollisionException;
import org.stvnadore.plugin.repository.PublishResponse;
import org.stvnadore.plugin.repository.StvnRepositoryClient;
import org.stvnadore.plugin.repository.StvnRepositoryNotificationHelper;
import org.stvnadore.plugin.settings.StvnRepositorySettings;

import java.util.Map;

/**
 * Publishes an STVN definitions schema ({@code .stvn_d}, {@code .stvn_di}) to the configured remote repository.
 * <p>
 * Collects workspace buffers via {@link StvnVopProjectionService#collectWorkspaceSources}, flattens modular
 * {@code .stvn_di} schemas under modal progress, and validates the schema locally with {@link StvnCompiler}
 * before network dispatch without synthesizing {@code #domain "default"} text splices.
 */
@NullMarked
public final class PublishSchemaAction extends AnAction {

    /**
     * Constructs a new {@link PublishSchemaAction} instance.
     */
    public PublishSchemaAction() {
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile entryFile = StvnVopProjectionService.resolveTargetVirtualFile(e);
        if (project == null || entryFile == null) {
            return;
        }

        String schemaContent = prepareValidatedSchemaForPublish(project, entryFile);
        if (schemaContent == null) {
            return;
        }

        String schemaName = deriveSchemaName(entryFile.getName());
        var settings = StvnRepositorySettings.getInstance(project);
        String repoUrl = settings.getRepoUrl();
        int timeoutMs = settings.getTimeoutMs();

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

    /**
     * Prepares and validates the canonical {@code .stvn_d} schema text for publication under modal progress.
     * Returns {@code null} and emits an STE error notification when flattening or compiler validation fails.
     *
     * @param project   active IntelliJ project
     * @param entryFile target {@code .stvn_d} or {@code .stvn_di} virtual file
     * @return validated schema text ready for transport, or {@code null} when invalid or cancelled
     */
    public static @Nullable String prepareValidatedSchemaForPublish(Project project, VirtualFile entryFile) {
        String fileName = entryFile.getName();
        if (!fileName.endsWith(".stvn_d") && !fileName.endsWith(".stvn_di")) {
            return null;
        }
        String schemaName = deriveSchemaName(fileName);

        return StvnVopProjectionService.computeUnderProgress(
            project,
            "Validating STVN Schema for Publication...",
            () -> {
                String candidateSchema;
                if (fileName.endsWith(".stvn_di")) {
                    Map<String, String> workspaceMap = StvnVopProjectionService.collectWorkspaceSources(project, entryFile);
                    String entryPointPath = StvnSchemaFlattener.normalizePath(entryFile.getPath());
                    try {
                        candidateSchema = StvnSchemaFlattener.flatten(workspaceMap, entryPointPath);
                    } catch (CyclicDependencyException ex) {
                        notifyOnEdt(() -> StvnWorkspaceNotificationHelper.showCycleError(project, ex));
                        return null;
                    } catch (NamespaceCollisionException ex) {
                        notifyOnEdt(() -> StvnWorkspaceNotificationHelper.showCollisionError(project, ex));
                        return null;
                    } catch (DuplicateModuleImportException ex) {
                        notifyOnEdt(() -> StvnWorkspaceNotificationHelper.showDuplicateImportError(project, ex));
                        return null;
                    } catch (Exception ex) {
                        notifyOnEdt(() -> StvnWorkspaceNotificationHelper.showGenericError(project, ex));
                        return null;
                    }
                } else {
                    candidateSchema = StvnVopProjectionService.loadLatestBufferOrDiskText(entryFile);
                }

                StvnCompilationResult<StvnDocument> validation = StvnCompiler.compileToResult(
                    candidateSchema,
                    schemaName,
                    StvnParserConfig.DEFAULT
                );
                if (validation.hasErrors() || validation.document().isEmpty()) {
                    validation.diagnostics().stream()
                        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
                        .findFirst()
                        .ifPresent(err -> notifyOnEdt(() ->
                            StvnWorkspaceNotificationHelper.showCompilationError(project, fileName, err)
                        ));
                    return null;
                }
                return candidateSchema;
            }
        );
    }

    private static void notifyOnEdt(Runnable runnable) {
        var app = ApplicationManager.getApplication();
        if (app == null || app.isDispatchThread()) {
            runnable.run();
        } else {
            app.invokeLater(runnable);
        }
    }

    @Override
    public void update(AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = StvnVopProjectionService.resolveTargetVirtualFile(e);
        boolean visible = project != null
            && file != null
            && !file.isDirectory()
            && (file.getName().endsWith(".stvn_d") || file.getName().endsWith(".stvn_di"));
        e.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /**
     * Derives the canonical STVN 2.0.0 repository schema artifact name preserving the {@code .stvn_d}
     * or {@code .stvn_bd} suffix and mapping flattened {@code .stvn_di} modules to {@code .stvn_d}.
     *
     * @param fileName active source file name
     * @return canonical target schema name for repository publication
     */
    public static String deriveSchemaName(String fileName) {
        return StvnRepositoryClient.deriveSchemaName(fileName);
    }
}
