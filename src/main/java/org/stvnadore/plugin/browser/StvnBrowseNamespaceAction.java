package org.stvnadore.plugin.browser;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;

/**
 * Action triggered via {@code Ctrl+Alt+N} / {@code Cmd+Alt+N} to open the 3-scope
 * {@code STVN Namespace Dependencies} browser initialized from the caret section.
 */
@NullMarked
public final class StvnBrowseNamespaceAction extends AnAction {

    /**
     * Constructs the namespace dependency browser action.
     */
    public StvnBrowseNamespaceAction() {
        super("Browse Namespace Dependencies");
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        var project = e.getProject();
        var editor = e.getData(CommonDataKeys.EDITOR);
        var file = e.getData(CommonDataKeys.PSI_FILE);

        if (project == null || editor == null || !(file instanceof StvnFile)) {
            return;
        }

        int offset = editor.getCaretModel().getOffset();
        var initialScope = ReadAction.compute(() -> {
            int safeOffset = Math.min(Math.max(0, offset), Math.max(0, file.getTextLength() - 1));
            var element = file.getTextLength() > 0 ? file.findElementAt(safeOffset) : null;
            return StvnNamespaceScope.resolveFromElement(element);
        });

        StvnNamespaceBrowserPopup.showPopup(project, file, initialScope);
    }

    @Override
    public void update(AnActionEvent e) {
        var project = e.getProject();
        var editor = e.getData(CommonDataKeys.EDITOR);
        var file = e.getData(CommonDataKeys.PSI_FILE);

        boolean isAvailable = project != null && editor != null && file instanceof StvnFile;
        e.getPresentation().setEnabledAndVisible(isAvailable);
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
