package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.actions.StvnVopProjectionService.InPlaceFormatProfile;

/**
 * Submenu 3 action: {@code Replace Buffer with Compact IR}.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnReplaceBufferCompactIrAction extends AbstractStvnVopProjectionAction {

    /**
     * Constructs a new {@code StvnReplaceBufferCompactIrAction}.
     */
    public StvnReplaceBufferCompactIrAction() {
        super("Replace Buffer with Compact IR", null);
    }

    @Override
    protected boolean isEnabledForTextualStvnFile(Project project, VirtualFile vf, AnActionEvent event) {
        return StvnVopProjectionService.canReplaceBufferInPlace(vf);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        StvnVopProjectionService.replaceActiveBufferInPlace(project, event, InPlaceFormatProfile.COMPACT_IR);
    }
}
