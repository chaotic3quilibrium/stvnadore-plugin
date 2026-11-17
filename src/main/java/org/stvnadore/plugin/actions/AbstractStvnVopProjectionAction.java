package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import javax.swing.Icon;

/**
 * Package-private abstract base class for all VOP projection and canonicalization actions.
 * <p>
 * Enforces {@link ActionUpdateThread#BGT} across every subclass, preserves exact non-mnemonic
 * labels ({@code .stvn_d}), and centralizes textual-STVN visibility vs. state-space enablement updates.
 * </p>
 *
 * @since 2.0.0
 */
@NullMarked
abstract class AbstractStvnVopProjectionAction extends AnAction {

    private final String exactText;

    /**
     * Constructs an action with exact non-mnemonic presentation text and optional icon.
     *
     * @param exactText exact menu label text
     * @param icon      optional action icon, or {@code null}
     */
    protected AbstractStvnVopProjectionAction(String exactText, @Nullable Icon icon) {
        super();
        this.exactText = exactText;
        getTemplatePresentation().setText(exactText, false);
        if (icon != null) {
            getTemplatePresentation().setIcon(icon);
        }
    }

    /**
     * Evaluates whether this action is enabled for a confirmed textual STVN {@code VirtualFile}.
     *
     * @param project active IntelliJ project
     * @param vf      confirmed textual STVN virtual file
     * @param event   action event context
     * @return {@code true} when legal under the VOP state-space matrix
     */
    protected abstract boolean isEnabledForTextualStvnFile(
        Project project,
        VirtualFile vf,
        AnActionEvent event
    );

    @Override
    public final void update(@NotNull AnActionEvent event) {
        event.getPresentation().setText(exactText, false);
        Project project = event.getProject();
        VirtualFile vf = StvnVopProjectionService.resolveTargetVirtualFile(event);
        if (project == null || vf == null || !StvnVopProjectionService.isTextualStvnVirtualFile(vf)) {
            event.getPresentation().setEnabledAndVisible(false);
            return;
        }
        event.getPresentation().setVisible(true);
        event.getPresentation().setEnabled(isEnabledForTextualStvnFile(project, vf, event));
    }

    @Override
    public final @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
