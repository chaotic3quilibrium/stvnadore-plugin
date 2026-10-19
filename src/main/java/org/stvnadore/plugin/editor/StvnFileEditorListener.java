package org.stvnadore.plugin.editor;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;

/**
 * Listens for editor tab switch events, immediately flushing all unsaved editor
 * document buffers to physical disk on the EDT so that the external compiler
 * (stvnadore-core) ingests the latest revisions of included modules.
 */
@NullMarked
public final class StvnFileEditorListener implements FileEditorManagerListener {

  @Override
  public void selectionChanged(@NotNull FileEditorManagerEvent event) {
    var oldFile = event.getOldFile();
    var newFile = event.getNewFile();

    boolean oldIsStvn = oldFile != null && isStvnFileName(oldFile.getName());
    boolean newIsStvn = newFile != null && isStvnFileName(newFile.getName());

    if (oldIsStvn || newIsStvn) {
      var app = ApplicationManager.getApplication();
      if (app != null && app.isDispatchThread()) {
        FileDocumentManager.getInstance().saveAllDocuments();
      }
    }
  }

  private static boolean isStvnFileName(String name) {
    return name.endsWith(".stvn")
        || name.endsWith(".stvn_d")
        || name.endsWith(".stvn_di")
        || name.endsWith(".stvn_i")
        || name.endsWith(".stvn_b")
        || name.endsWith(".stvn_bd");
  }
}
