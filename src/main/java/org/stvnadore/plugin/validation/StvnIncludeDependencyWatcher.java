package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;

import java.util.List;

/**
 * Listens for VFS file changes across STVN documents, triggering re-highlighting
 * on open dependent documents that include modified modules.
 */
@NullMarked
public final class StvnIncludeDependencyWatcher implements BulkFileListener {

  @Override
  public void after(@NotNull List<? extends VFileEvent> events) {
    boolean hasStvnChange = false;
    for (var event : events) {
      if (isRelevantEvent(event)) {
        hasStvnChange = true;
        break;
      }
    }
    if (!hasStvnChange) {
      return;
    }

    var app = ApplicationManager.getApplication();
    if (app == null) {
      return;
    }

    app.invokeLater(() -> {
      var projectManager = ProjectManager.getInstance();
      if (projectManager == null) return;

      for (var project : projectManager.getOpenProjects()) {
        if (project.isDisposed()) continue;
        var fileEditorManager = FileEditorManager.getInstance(project);
        boolean hasOpenStvnFile = false;
        for (var openVf : fileEditorManager.getOpenFiles()) {
          if (openVf.isValid() && isStvnFileName(openVf.getName())) {
            hasOpenStvnFile = true;
            break;
          }
        }
        if (hasOpenStvnFile) {
          DaemonCodeAnalyzer.getInstance(project).restart(project);
        }
      }
    }, ModalityState.nonModal());
  }

  private static boolean isRelevantEvent(VFileEvent event) {
    if (event instanceof VFileContentChangeEvent
        || event instanceof VFileCreateEvent
        || event instanceof VFileDeleteEvent
        || (event instanceof VFilePropertyChangeEvent pce && VirtualFile.PROP_NAME.equals(pce.getPropertyName()))) {
      var file = event.getFile();
      if (file != null && isStvnFileName(file.getName())) {
        return true;
      }
      var path = event.getPath();
      return isStvnFileName(path);
    }
    return false;
  }

  private static boolean isStvnFileName(String name) {
    return name.endsWith(".stvn")
        || name.endsWith(".stvn_d")
        || name.endsWith(".stvn_di")
        || name.endsWith(".stvn_i")
        || name.endsWith(".stvn_b")
        || name.endsWith(".stvn_bd")
        || name.endsWith(".stvn_incl")
        || name.endsWith(".stvn_inclf");
  }
}
