package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.icons.StvnIcons;

/**
 * Top-level {@code STVN >} popup action group surfaced in {@code EditorPopupMenu} and {@code ProjectViewPopupMenu}.
 * <p>
 * Remains visible and enabled strictly on the four textual STVN 2.0.0 extensions ({@code .stvn},
 * {@code .stvn_i}, {@code .stvn_d}, {@code .stvn_di}), hiding cleanly on directories, binary STVN
 * archives ({@code .stvn_b}, {@code .stvn_bd}), and non-STVN files.
 * </p>
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnEditorPopupMenuGroup extends DefaultActionGroup {

    private static final String EXACT_TEXT = "STVN";

    /**
     * Constructs a new {@code StvnEditorPopupMenuGroup} bound to {@link StvnIcons#STVN}.
     */
    public StvnEditorPopupMenuGroup() {
        super();
        setPopup(true);
        getTemplatePresentation().setText(EXACT_TEXT, false);
        getTemplatePresentation().setIcon(StvnIcons.STVN);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setText(EXACT_TEXT, false);
        Project project = event.getProject();
        VirtualFile vf = StvnVopProjectionService.resolveTargetVirtualFile(event);
        boolean visible = project != null && StvnVopProjectionService.isTextualStvnVirtualFile(vf);
        event.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
