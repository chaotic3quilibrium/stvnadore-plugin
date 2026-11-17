package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.actions.StvnVopProjectionService.SchemaFormatProfile;

/**
 * Submenu 1 action: {@code Copy to Clipboard (Pretty)} for hermetic {@code .stvn_d} ({@code #DEFS}) schemas.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnCopyHermeticSchemaPrettyAction extends AbstractStvnVopProjectionAction {

    /**
     * Constructs a new {@code StvnCopyHermeticSchemaPrettyAction}.
     */
    public StvnCopyHermeticSchemaPrettyAction() {
        super("Copy to Clipboard (Pretty)", null);
    }

    @Override
    protected boolean isEnabledForTextualStvnFile(Project project, VirtualFile vf, AnActionEvent event) {
        return true;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile vf = StvnVopProjectionService.resolveTargetVirtualFile(event);
        if (project == null || vf == null || !StvnVopProjectionService.isTextualStvnVirtualFile(vf)) {
            return;
        }
        StvnVopProjectionService.copyHermeticSchemaToClipboard(project, vf, SchemaFormatProfile.PRETTY);
    }
}
