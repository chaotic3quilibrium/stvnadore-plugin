package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.actions.StvnVopProjectionService.PayloadFormatProfile;

/**
 * Submenu 2 action: {@code Export Sibling .stvn File (Compact)}.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnExportHermeticPayloadCompactAction extends AbstractStvnVopProjectionAction {

    /**
     * Constructs a new {@code StvnExportHermeticPayloadCompactAction}.
     */
    public StvnExportHermeticPayloadCompactAction() {
        super("Export Sibling .stvn File (Compact)", null);
    }

    @Override
    protected boolean isEnabledForTextualStvnFile(Project project, VirtualFile vf, AnActionEvent event) {
        return StvnVopProjectionService.canExportSiblingPayload(vf);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile vf = StvnVopProjectionService.resolveTargetVirtualFile(event);
        if (project == null || vf == null) {
            return;
        }
        StvnVopProjectionService.exportHermeticPayloadSibling(project, vf, PayloadFormatProfile.COMPACT);
    }
}
