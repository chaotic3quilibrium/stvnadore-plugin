package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.actions.StvnVopProjectionService.PayloadFormatProfile;

/**
 * Submenu 2 action: {@code Copy to Clipboard (Pretty)} for hermetic {@code .stvn} ({@code #BODY}) payloads.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnCopyHermeticPayloadPrettyAction extends AbstractStvnVopProjectionAction {

    /**
     * Constructs a new {@code StvnCopyHermeticPayloadPrettyAction}.
     */
    public StvnCopyHermeticPayloadPrettyAction() {
        super("Copy to Clipboard (Pretty)", null);
    }

    @Override
    protected boolean isEnabledForTextualStvnFile(Project project, VirtualFile vf, AnActionEvent event) {
        return StvnProjectionStemUtil.supportsPayloadExtraction(vf.getName());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile vf = StvnVopProjectionService.resolveTargetVirtualFile(event);
        if (project == null || vf == null) {
            return;
        }
        StvnVopProjectionService.copyHermeticPayloadToClipboard(project, vf, PayloadFormatProfile.PRETTY);
    }
}
