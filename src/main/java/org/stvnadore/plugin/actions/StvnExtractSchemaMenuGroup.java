package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.icons.StvnIcons;

/**
 * Submenu 1 group: {@code Extract Hermetic Schema (.stvn_d — #DEFS Only)}.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnExtractSchemaMenuGroup extends DefaultActionGroup {

    private static final String EXACT_TEXT =
        "Extract Hermetic Schema (.stvn_d \u2014 " + StvnVocabulary.KIND_KW_DEFS + " Only)";

    /**
     * Constructs Submenu 1 bound to {@link StvnIcons#STVN_D}.
     */
    public StvnExtractSchemaMenuGroup() {
        super();
        setPopup(true);
        getTemplatePresentation().setText(EXACT_TEXT, false);
        getTemplatePresentation().setIcon(StvnIcons.STVN_D);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setText(EXACT_TEXT, false);
        Project project = event.getProject();
        VirtualFile vf = StvnVopProjectionService.resolveTargetVirtualFile(event);
        if (project == null || !StvnVopProjectionService.isTextualStvnVirtualFile(vf)) {
            event.getPresentation().setEnabledAndVisible(false);
            return;
        }
        event.getPresentation().setVisible(true);
        event.getPresentation().setEnabled(true);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
