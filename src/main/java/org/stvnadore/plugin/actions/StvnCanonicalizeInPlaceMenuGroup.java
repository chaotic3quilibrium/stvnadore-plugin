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
 * Submenu 3 group: {@code Canonicalize Active Buffer In-Place}.
 * <p>
 * Remains visible across all textual STVN files while disabling itself on read-only or synthetic
 * Prelude {@link VirtualFile} instances.
 * </p>
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnCanonicalizeInPlaceMenuGroup extends DefaultActionGroup {

    private static final String EXACT_TEXT = "Canonicalize Active Buffer In-Place";

    /**
     * Constructs Submenu 3 bound to {@link StvnIcons#STVN_IR}.
     */
    public StvnCanonicalizeInPlaceMenuGroup() {
        super();
        setPopup(true);
        getTemplatePresentation().setText(EXACT_TEXT, false);
        getTemplatePresentation().setIcon(StvnIcons.STVN_IR);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setText(EXACT_TEXT, false);
        Project project = event.getProject();
        VirtualFile vf = StvnVopProjectionService.resolveTargetVirtualFile(event);
        if (project == null || vf == null || !StvnVopProjectionService.isTextualStvnVirtualFile(vf)) {
            event.getPresentation().setEnabledAndVisible(false);
            return;
        }
        event.getPresentation().setVisible(true);
        event.getPresentation().setEnabled(StvnVopProjectionService.canReplaceBufferInPlace(vf));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
