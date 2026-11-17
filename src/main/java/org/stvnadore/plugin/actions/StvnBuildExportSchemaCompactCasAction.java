package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.actions.StvnVopProjectionService.SchemaFormatProfile;
import org.stvnadore.plugin.icons.StvnIcons;

/**
 * {@code BuildMenu} action: {@code Export Hermetic STVN Schema (.stvn_d, Compact CAS)}.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnBuildExportSchemaCompactCasAction extends AbstractStvnVopProjectionAction {

    /**
     * Constructs a new {@code StvnBuildExportSchemaCompactCasAction} bound to {@link StvnIcons#STVN}.
     */
    public StvnBuildExportSchemaCompactCasAction() {
        super("Export Hermetic STVN Schema (.stvn_d, Compact CAS)", StvnIcons.STVN);
    }

    @Override
    protected boolean isEnabledForTextualStvnFile(Project project, VirtualFile vf, AnActionEvent event) {
        return StvnVopProjectionService.canExportSiblingSchema(vf);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile vf = StvnVopProjectionService.resolveTargetVirtualFile(event);
        if (project == null || vf == null) {
            return;
        }
        StvnVopProjectionService.exportHermeticSchemaSibling(project, vf, SchemaFormatProfile.COMPACT_CAS);
    }
}
