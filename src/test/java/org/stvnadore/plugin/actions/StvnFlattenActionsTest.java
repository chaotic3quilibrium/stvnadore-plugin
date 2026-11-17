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
 * 3-submenu VOP hierarchy, {@code BuildMenu} export action, and 2-stem unsaved buffer
 * schema extraction across modular workspaces.
 */
@NullMarked
public final class StvnFlattenActionsTest extends BasePlatformTestCase {

    public void testStvnPopupGroupIconsSeparatorsAndMenuRegistrations() {
        ActionManager actionManager = ActionManager.getInstance();

        AnAction popupGroupAction = actionManager.getAction("org.stvnadore.plugin.actions.StvnEditorPopupMenu");
        assertNotNull("StvnEditorPopupMenu must be registered", popupGroupAction);
        assertTrue(popupGroupAction instanceof StvnEditorPopupMenuGroup);
        assertEquals("STVN", popupGroupAction.getTemplatePresentation().getText());
        var popupIcon = popupGroupAction.getTemplatePresentation().getIcon();
        assertNotNull("StvnEditorPopupMenu must bind STVN cube icon", popupIcon);
        assertTrue("StvnEditorPopupMenu icon must resolve to stvn_file.svg: " + popupIcon,
            popupIcon.toString().contains("icons/stvn_file.svg"));

        AnAction schemaGroup = actionManager.getAction("org.stvnadore.plugin.actions.StvnExtractSchemaMenuGroup");
        AnAction payloadGroup = actionManager.getAction("org.stvnadore.plugin.actions.StvnExtractPayloadMenuGroup");
        AnAction inPlaceGroup = actionManager.getAction("org.stvnadore.plugin.actions.StvnCanonicalizeInPlaceMenuGroup");
        AnAction buildExport = actionManager.getAction("org.stvnadore.plugin.actions.StvnBuildExportSchemaCompactCasAction");
        assertNotNull(schemaGroup);
        assertNotNull(payloadGroup);
        assertNotNull(inPlaceGroup);
        assertNotNull(buildExport);
        assertEquals(StvnIcons.STVN, new StvnBuildExportSchemaCompactCasAction().getTemplatePresentation().getIcon());
        assertEquals("Export Hermetic STVN Schema (.stvn_d, Compact CAS)",
            buildExport.getTemplatePresentation().getText());

        var probeEvent = TestActionEvent.createTestEvent();
        DefaultActionGroup stvnGroup = (DefaultActionGroup) popupGroupAction;
        AnAction[] children = stvnGroup.getChildren(probeEvent);
        assertEquals("Expected Browse + Sep + Submenu1 + Sep + Submenu2 + Sep + Submenu3 = 7 children",
            7, children.length);
        assertTrue("Child[1] must be Separator", children[1] instanceof Separator);
        assertEquals(schemaGroup, children[2]);
        assertTrue("Child[3] must be Separator", children[3] instanceof Separator);
        assertEquals(payloadGroup, children[4]);
        assertTrue("Child[5] must be Separator", children[5] instanceof Separator);
        assertEquals(inPlaceGroup, children[6]);

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
        assertTrue("BuildMenu must contain Export Hermetic STVN Schema (.stvn_d, Compact CAS)",
            buildChildren.contains(buildExport));
    }

    public void testVisibilityAcrossFileExtensions() {
        var stvnFile = myFixture.addFileToProject("sample.stvn", "{ :defs { :A :Int } :type :A :body 1 }");
        var stvnIFile = myFixture.addFileToProject("sample_mod.stvn_i",
            "{ :meta { #name \"sample_mod\" #kind #BODY_INCLUDE } :defs { :A :Int } :type :A :body 1 }");
        var stvnDFile = myFixture.addFileToProject("sample_defs.stvn_d",
            "{ :meta { #name \"sample_defs\" #kind #DEFS } :defs { :A :Int } }");
        var stvnDiFile = myFixture.addFileToProject("sample_defs_mod.stvn_di",
            "{ :meta { #name \"sample_defs_mod\" #kind #DEFS_INCLUDE } :defs { :A :Int } }");
        var txtFile = myFixture.addFileToProject("notes.txt", "hello");

        var diskAction = new StvnExportHermeticSchemaCompactCasAction();
        var clipAction = new StvnCopyHermeticSchemaPrettyAction();

        assertActionEnabledAndVisible(diskAction, stvnFile.getVirtualFile(), true, true);
        assertActionEnabledAndVisible(diskAction, stvnIFile.getVirtualFile(), true, true);
        assertActionEnabledAndVisible(diskAction, stvnDiFile.getVirtualFile(), true, true);
        assertActionEnabledAndVisible(diskAction, stvnDFile.getVirtualFile(), true, false);
        assertActionEnabledAndVisible(diskAction, txtFile.getVirtualFile(), false, false);

        assertActionEnabledAndVisible(clipAction, stvnFile.getVirtualFile(), true, true);
        assertActionEnabledAndVisible(clipAction, stvnIFile.getVirtualFile(), true, true);
        assertActionEnabledAndVisible(clipAction, stvnDiFile.getVirtualFile(), true, true);
        assertActionEnabledAndVisible(clipAction, stvnDFile.getVirtualFile(), true, true);
        assertActionEnabledAndVisible(clipAction, txtFile.getVirtualFile(), false, false);
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

        String updatedUnsavedEntry = initialEntry.replace(":LocalWrapper", ":UnsavedWrapper");
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
            myFixture.getEditor().getDocument().setText(updatedUnsavedEntry)
        );

        var clipAction = new StvnCopyHermeticSchemaPrettyAction();
        var dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, getProject())
            .add(CommonDataKeys.EDITOR, myFixture.getEditor())
            .add(CommonDataKeys.PSI_FILE, psiEntry)
            .add(CommonDataKeys.VIRTUAL_FILE, psiEntry.getVirtualFile())
            .build();

        clipAction.actionPerformed(TestActionEvent.createTestEvent(clipAction, dataContext));

        assertEquals(updatedUnsavedEntry, myFixture.getEditor().getDocument().getText());
        assertNull("Clipboard schema extraction must not create .stvn_d on disk",
            psiEntry.getVirtualFile().getParent().findChild(
                "gauntlet_v7_stage10_modular_includes_and_flattening.core_test.stvn_d"
            ));

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

        var diskAction = new StvnExportHermeticSchemaCompactCasAction();
        diskAction.actionPerformed(TestActionEvent.createTestEvent(diskAction, dataContext));
        assertNotNull("Disk export action must create 2-stem .stvn_d sibling",
            psiEntry.getVirtualFile().getParent().findChild(
                "gauntlet_v7_stage10_modular_includes_and_flattening.core_test.stvn_d"
            ));
    }

    private void assertActionEnabledAndVisible(
            AnAction action,
            com.intellij.openapi.vfs.VirtualFile vf,
            boolean expectedVisible,
            boolean expectedEnabled
    ) {
        var ctx = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, getProject())
            .add(CommonDataKeys.VIRTUAL_FILE, vf)
            .build();
        var event = TestActionEvent.createTestEvent(action, ctx);
        action.update(event);
        assertEquals("Unexpected visibility for " + vf.getName(),
            expectedVisible, event.getPresentation().isVisible());
        assertEquals("Unexpected enablement for " + vf.getName(),
            expectedEnabled, event.getPresentation().isEnabled());
    }
}
