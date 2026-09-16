package org.stvnadore.plugin.browser;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;

/**
 * Action triggered via Ctrl+Alt+N / Cmd+Alt+N to browse namespace dependencies at caret position.
 */
@NullMarked
public final class StvnBrowseNamespaceAction extends AnAction {

    /**
     * Default constructor for the namespace dependency browser action.
     */
    public StvnBrowseNamespaceAction() {
        super("Browse Namespace Dependencies");
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        var project = e.getProject();
        var editor = e.getData(CommonDataKeys.EDITOR);
        var file = e.getData(CommonDataKeys.PSI_FILE);

        if (project == null || editor == null || file == null || !(file instanceof StvnFile)) {
            return;
        }

        var offset = editor.getCaretModel().getOffset();
        var element = file.findElementAt(offset);
        var scope = StvnNamespaceScope.resolveFromElement(element);

        StvnNamespaceBrowserPopup.showPopup(project, file, scope);
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
