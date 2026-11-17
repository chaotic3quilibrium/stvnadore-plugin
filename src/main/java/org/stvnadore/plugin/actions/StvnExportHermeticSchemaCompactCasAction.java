package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.actions.StvnVopProjectionService.SchemaFormatProfile;

/**
 * Submenu 1 action: {@code Export Sibling .stvn_d File (Compact CAS)}.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnExportHermeticSchemaCompactCasAction extends AbstractStvnVopProjectionAction {

    /**
     * Constructs a new {@code StvnExportHermeticSchemaCompactCasAction}.
     */
    public StvnExportHermeticSchemaCompactCasAction() {
        super("Export Sibling .stvn_d File (Compact CAS)", null);
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
