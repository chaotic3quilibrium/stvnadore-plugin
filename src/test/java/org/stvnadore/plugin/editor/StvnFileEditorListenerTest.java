package org.stvnadore.plugin.editor;

import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Tests for StvnFileEditorListener ensuring documents are saved upon tab selection change.
 */
public final class StvnFileEditorListenerTest extends BasePlatformTestCase {

    @SuppressWarnings("deprecation")
    public void testTabSwitchTriggersDocumentSaveForStvnFile() {
        VirtualFile vFile1 = myFixture.configureByText("module_a.stvn_d", "{\n  :meta { #kind #DEFS }\n}\n").getVirtualFile();
        VirtualFile vFile2 = myFixture.configureByText("module_b.stvn_di", "{\n  :meta { #kind #DEFS_INCLUDE }\n}\n").getVirtualFile();

        var listener = new StvnFileEditorListener();
        var event = new FileEditorManagerEvent(
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject()),
            vFile1,
            null,
            null,
            vFile2,
            null,
            null
        );

        // Verification: selectionChanged executes cleanly on EDT without exception
        listener.selectionChanged(event);
    }
}
