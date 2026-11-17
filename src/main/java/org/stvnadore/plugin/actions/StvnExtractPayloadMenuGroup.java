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
 * Submenu 2 group: {@code Extract Hermetic Payload (.stvn — Includes :body)}.
 * <p>
 * Remains visible across all textual STVN files while disabling itself on {@code .stvn_d} and
 * {@code .stvn_di} definitions schemas that prohibit {@code :type} and {@code :body}.
 * </p>
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnExtractPayloadMenuGroup extends DefaultActionGroup {

    private static final String EXACT_TEXT =
        "Extract Hermetic Payload (.stvn \u2014 Includes " + StvnVocabulary.KEYWORD_BODY + ")";

    /**
     * Constructs Submenu 2 bound to {@link StvnIcons#STVN}.
     */
    public StvnExtractPayloadMenuGroup() {
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
        if (project == null || vf == null || !StvnVopProjectionService.isTextualStvnVirtualFile(vf)) {
            event.getPresentation().setEnabledAndVisible(false);
            return;
        }
        event.getPresentation().setVisible(true);
        event.getPresentation().setEnabled(StvnProjectionStemUtil.supportsPayloadExtraction(vf.getName()));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
