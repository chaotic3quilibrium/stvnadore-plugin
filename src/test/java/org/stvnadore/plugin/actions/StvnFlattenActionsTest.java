package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.plugin.icons.StvnIcons;

import java.awt.datatransfer.DataFlavor;
import java.util.Arrays;
import java.util.List;

/**
 * Platform integration test suite verifying {@code STVN >} popup group surfacing,
 * STVN cube icons, {@link StvnFlattenWorkspaceAction}, and {@link StvnCopyFlattenedSchemaAction}
 * across 1-stem and 2-stem modular workspaces.
 */
@NullMarked
public final class StvnFlattenActionsTest extends BasePlatformTestCase {

    public void testStvnPopupGroupIconsSeparatorsAndMenuRegistrations() {
        ActionManager actionManager = ActionManager.getInstance();

        AnAction popupGroupAction = actionManager.getAction("org.stvnadore.plugin.actions.StvnEditorPopupMenu");
        assertNotNull("StvnEditorPopupMenu must be registered", popupGroupAction);
        assertTrue(popupGroupAction instanceof DefaultActionGroup);
        assertEquals("STVN", popupGroupAction.getTemplatePresentation().getText());
        var popupIcon = popupGroupAction.getTemplatePresentation().getIcon();
        assertNotNull("StvnEditorPopupMenu must bind STVN cube icon", popupIcon);
        assertTrue("StvnEditorPopupMenu icon must resolve to stvn_file.svg: " + popupIcon,
            popupIcon.toString().contains("icons/stvn_file.svg"));

        AnAction flattenDisk = actionManager.getAction("org.stvnadore.plugin.actions.StvnFlattenWorkspaceAction");
        AnAction flattenClipboard = actionManager.getAction("org.stvnadore.plugin.actions.StvnCopyFlattenedSchemaAction");
        assertNotNull(flattenDisk);
        assertNotNull(flattenClipboard);
        var diskIcon = flattenDisk.getTemplatePresentation().getIcon();
        var clipIcon = flattenClipboard.getTemplatePresentation().getIcon();
        assertNotNull(diskIcon);
        assertNotNull(clipIcon);
        assertTrue(diskIcon.toString().contains("icons/stvn_file.svg"));
        assertTrue(clipIcon.toString().contains("icons/stvn_file.svg"));
        assertEquals(StvnIcons.STVN, new StvnFlattenWorkspaceAction().getTemplatePresentation().getIcon());
        assertEquals(StvnIcons.STVN, new StvnCopyFlattenedSchemaAction().getTemplatePresentation().getIcon());

        var probeEvent = TestActionEvent.createTestEvent();
        DefaultActionGroup stvnGroup = (DefaultActionGroup) popupGroupAction;
        AnAction[] children = stvnGroup.getChildren(probeEvent);
        assertEquals("Expected Browse + Separator + 2 Flatten + Separator + 4 Projection = 9 children", 9, children.length);
        assertTrue("Child[1] must be Separator", children[1] instanceof Separator);
        assertEquals(flattenDisk, children[2]);
        assertEquals(flattenClipboard, children[3]);
        assertTrue("Child[4] must be Separator", children[4] instanceof Separator);

        // Verify StvnEditorPopupMenu is attached to both EditorPopupMenu and ProjectViewPopupMenu
        ActionGroup editorPopup = (ActionGroup) actionManager.getAction("EditorPopupMenu");
        ActionGroup projectViewPopup = (ActionGroup) actionManager.getAction("ProjectViewPopupMenu");
        ActionGroup buildMenu = (ActionGroup) actionManager.getAction("BuildMenu");
        assertNotNull(editorPopup);
        assertNotNull(projectViewPopup);
        assertNotNull(buildMenu);

        List<AnAction> editorChildren = Arrays.asList(editorPopup.getChildren(probeEvent));
        List<AnAction> projectViewChildren = Arrays.asList(projectViewPopup.getChildren(probeEvent));
        List<AnAction> buildChildren = Arrays.asList(buildMenu.getChildren(probeEvent));
        assertTrue("EditorPopupMenu must contain STVN popup group", editorChildren.contains(popupGroupAction));
        assertTrue("ProjectViewPopupMenu must contain STVN popup group", projectViewChildren.contains(popupGroupAction));
        assertTrue("BuildMenu must retain Flatten STVN Workspace", buildChildren.contains(flattenDisk));
    }

    public void testVisibilityAcrossFileExtensions() {
        var stvnFile = myFixture.addFileToProject("sample.stvn", "{ :type :Int :body 1 }");
        var stvnIFile = myFixture.addFileToProject("sample_mod.stvn_i", "{ :type :Int :body 1 }");
        var stvnDFile = myFixture.addFileToProject("sample_defs.stvn_d", "{ :defs { :A :Int } }");
        var stvnDiFile = myFixture.addFileToProject("sample_defs_mod.stvn_di", "{ :defs { :A :Int } }");
        var txtFile = myFixture.addFileToProject("notes.txt", "hello");

        var diskAction = new StvnFlattenWorkspaceAction();
        var clipAction = new StvnCopyFlattenedSchemaAction();

        // Disk action: enabled on .stvn, .stvn_i, .stvn_di; hidden on .stvn_d and .txt
        assertActionVisible(diskAction, stvnFile.getVirtualFile(), true);
        assertActionVisible(diskAction, stvnIFile.getVirtualFile(), true);
        assertActionVisible(diskAction, stvnDiFile.getVirtualFile(), true);
        assertActionVisible(diskAction, stvnDFile.getVirtualFile(), false);
        assertActionVisible(diskAction, txtFile.getVirtualFile(), false);

        // Clipboard action: enabled on all 4 textual STVN extensions; hidden on .txt
        assertActionVisible(clipAction, stvnFile.getVirtualFile(), true);
        assertActionVisible(clipAction, stvnIFile.getVirtualFile(), true);
        assertActionVisible(clipAction, stvnDiFile.getVirtualFile(), true);
        assertActionVisible(clipAction, stvnDFile.getVirtualFile(), true);
        assertActionVisible(clipAction, txtFile.getVirtualFile(), false);
    }

    public void testFlattenActiveSchemaToClipboardSupportsTwoStemFilesAndUnsavedBuffersWithoutWritingDisk() throws Exception {
        myFixture.addFileToProject("stage10_base.core_test.stvn_di", """
            {
              :meta {
                #name "stage10_base"
                #domain "core_test"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :package :acme/ledger {
                  :ReleaseCode { #minSize 3 #maxSize 32 #regex "^[a-z]+\\\\.[0-9]+$" } :String
                  #DEFAULT_RELEASE :ReleaseCode "rel.1"
                }
              }
            }
            """);

        var initialEntry = """
            {
              :meta {
                #name "gauntlet_v7_stage10_modular_includes_and_flattening"
                #domain "core_test"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "stage10_base.core_test.stvn_di" ]
                :use [ :acme/ledger { :ReleaseCode :LocalRelease #DEFAULT_RELEASE #ACTIVE_REL } ]
                :LocalWrapper :Tuple( :LocalRelease :LocalRelease )
              }
              :type :LocalWrapper
              :body ( #ACTIVE_REL "rel.2" )
            }
            """;

        var psiEntry = myFixture.configureByText(
            "gauntlet_v7_stage10_modular_includes_and_flattening.core_test.stvn_i",
            initialEntry
        );

        // Mutate buffer in-memory without saving to disk to verify unsaved buffer precedence
        String updatedUnsavedEntry = initialEntry.replace(":LocalWrapper", ":UnsavedWrapper");
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
            myFixture.getEditor().getDocument().setText(updatedUnsavedEntry)
        );

        var clipAction = new StvnCopyFlattenedSchemaAction();
        var dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, getProject())
            .add(CommonDataKeys.EDITOR, myFixture.getEditor())
            .add(CommonDataKeys.PSI_FILE, psiEntry)
            .add(CommonDataKeys.VIRTUAL_FILE, psiEntry.getVirtualFile())
            .build();

        clipAction.actionPerformed(TestActionEvent.createTestEvent(clipAction, dataContext));

        // 1. Active editor buffer is completely untouched
        assertEquals(updatedUnsavedEntry, myFixture.getEditor().getDocument().getText());

        // 2. Zero .stvn_d file written to disk
        assertNull("Clipboard flatten must not create .stvn_d on disk",
            psiEntry.getVirtualFile().getParent().findChild(
                "gauntlet_v7_stage10_modular_includes_and_flattening.core_test.stvn_d"
            ));

        // 3. Clipboard contains pretty-printed hermetic #DEFS schema with [REGEX] fenced bias and unsaved symbol
        String clipboard = (String) CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        assertNotNull(clipboard);
        assertTrue("Clipboard schema must carry #kind #DEFS:\n" + clipboard, clipboard.contains("#kind #DEFS"));
        assertTrue("Clipboard schema must omit :include:\n" + clipboard, !clipboard.contains(":include"));
        assertTrue("Clipboard schema must reflect unsaved buffer edit:\n" + clipboard,
            clipboard.contains(":UnsavedWrapper"));
        assertTrue("Clipboard schema must be formatted via AstPrettyPrinter with [REGEX] fence bias:\n" + clipboard,
            clipboard.contains("\"\"\"[REGEX]"));

        var compileRes = StvnCompiler.compileToResult(
            clipboard,
            "gauntlet_v7_stage10_modular_includes_and_flattening.core_test.stvn_d",
            new StvnParserConfig(false, StvnParserConfig.DEFAULT_MAX_DIAGNOSTICS, true)
        );
        assertFalse("Flattened clipboard schema must compile with zero errors:\n" + compileRes.diagnostics(),
            compileRes.hasErrors());

        // 4. Disk flatten action writes 2-stem .stvn_d sibling cleanly when explicitly invoked
        var diskAction = new StvnFlattenWorkspaceAction();
        diskAction.actionPerformed(TestActionEvent.createTestEvent(diskAction, dataContext));
        assertNotNull("Disk flatten action must create 2-stem .stvn_d sibling",
            psiEntry.getVirtualFile().getParent().findChild(
                "gauntlet_v7_stage10_modular_includes_and_flattening.core_test.stvn_d"
            ));
    }

    private void assertActionVisible(AnAction action, com.intellij.openapi.vfs.VirtualFile vf, boolean expectedVisible) {
        var ctx = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, getProject())
            .add(CommonDataKeys.VIRTUAL_FILE, vf)
            .build();
        var event = TestActionEvent.createTestEvent(action, ctx);
        action.update(event);
        assertEquals("Unexpected visibility for " + vf.getName(),
            expectedVisible, event.getPresentation().isEnabledAndVisible());
    }
}
