package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.settings.StvnProjectSettings;

import java.awt.datatransfer.DataFlavor;

/**
 * Integration test suite verifying canonical projection actions, clipboard actions, and authoring loss guards.
 */
@NullMarked
public final class StvnDualProjectionActionsTest extends BasePlatformTestCase {

    public void testConvertToPrettyPrintAction() {
        var compact = "{:type :Tuple(:Boolean :Option(:Boolean)) :body (#T #N)}";
        var psiFile = myFixture.configureByText("payload.stvn", compact);

        var action = new StvnConvertToPrettyPrintAction();
        var dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(CommonDataKeys.PSI_FILE, psiFile)
                .build();

        var event = TestActionEvent.createTestEvent(action, dataContext);
        action.actionPerformed(event);

        var result = myFixture.getEditor().getDocument().getText();
        assertTrue("Pretty printed output must contain long-form keywords:\n" + result, result.contains("#TRUE"));
        assertTrue("Pretty printed output must contain long-form None:\n" + result, result.contains("#None"));
        assertTrue("Pretty printed output must use 2-space indentation:\n" + result, result.contains("  :type"));
    }

    public void testConvertToCompactPrintActionWithoutComments() {
        var pretty = """
            {
              :type :Tuple(:Boolean :Option(:Boolean))
              :body (
                #TRUE
                #None
              )
            }
            """;
        var psiFile = myFixture.configureByText("payload.stvn", pretty);

        var action = new StvnConvertToCompactPrintAction();
        var dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(CommonDataKeys.PSI_FILE, psiFile)
                .build();

        var event = TestActionEvent.createTestEvent(action, dataContext);
        action.actionPerformed(event);

        var result = myFixture.getEditor().getDocument().getText();
        assertEquals("Compact printed output must match canonical single-line syntax",
                "{:type :Tuple(:Boolean :Option(:Boolean)) :body (#T #N)}", result);
    }

    public void testCopyCanonicalPrettyPrintAction() {
        var compact = "{:type :Tuple(:Boolean :Option(:Boolean)) :body (#T #N)}";
        var psiFile = myFixture.configureByText("payload.stvn", compact);

        var action = new StvnCopyCanonicalPrettyPrintAction();
        var dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(CommonDataKeys.PSI_FILE, psiFile)
                .build();

        var event = TestActionEvent.createTestEvent(action, dataContext);
        action.actionPerformed(event);

        var docText = myFixture.getEditor().getDocument().getText();
        assertEquals("Document buffer must remain unchanged during clipboard projection", compact, docText);

        var clipboardContent = (String) CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        assertNotNull(clipboardContent);
        assertTrue("Clipboard must contain long-form keywords", clipboardContent.contains("#TRUE"));
        assertTrue("Clipboard must contain 2-space indentation", clipboardContent.contains("  :type"));
    }

    public void testCopyCanonicalCompactPrintAction() {
        var pretty = """
            {
              :type :Tuple(:Boolean :Option(:Boolean))
              :body (
                #TRUE
                #None
              )
            }
            """;
        var psiFile = myFixture.configureByText("payload.stvn", pretty);

        var action = new StvnCopyCanonicalCompactPrintAction();
        var dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(CommonDataKeys.PSI_FILE, psiFile)
                .build();

        var event = TestActionEvent.createTestEvent(action, dataContext);
        action.actionPerformed(event);

        var docText = myFixture.getEditor().getDocument().getText();
        assertEquals("Document buffer must remain unchanged during clipboard projection", pretty, docText);

        var clipboardContent = (String) CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        assertEquals("{:type :Tuple(:Boolean :Option(:Boolean)) :body (#T #N)}", clipboardContent);
    }

    public void testDestructionGuardDetectsDestructiveConstructs() {
        var commented = "{ // comment\n :type :Int :body 10 }";
        var fileComments = myFixture.configureByText("c.stvn", commented);
        assertTrue("Guard must trigger on comments", StvnCanonicalizationGuard.hasDestructiveAuthoringConstructs(fileComments));

        var packaged = "{ :defs { :package :pkg { :T :Int } } :type :pkg/T :body 10 }";
        var filePackaged = myFixture.configureByText("p.stvn", packaged);
        assertTrue("Guard must trigger on package enclaves", StvnCanonicalizationGuard.hasDestructiveAuthoringConstructs(filePackaged));

        var used = "{ :defs { :use [ :pkg { :T :U } ] } :type :U :body 10 }";
        var fileUsed = myFixture.configureByText("u.stvn", used);
        assertTrue("Guard must trigger on use aliases", StvnCanonicalizationGuard.hasDestructiveAuthoringConstructs(fileUsed));

        var unreferenced = "{ :defs { :Unused :Int } :type :String :body \"ok\" }";
        var fileUnreferenced = myFixture.configureByText("unref.stvn", unreferenced);
        assertTrue("Guard must trigger on unreferenced definitions", StvnCanonicalizationGuard.hasDestructiveAuthoringConstructs(fileUnreferenced));

        var clean = "{ :defs { :T :Int } :type :T :body 10 }";
        var fileClean = myFixture.configureByText("clean.stvn", clean);
        assertFalse("Guard must not trigger on clean documents", StvnCanonicalizationGuard.hasDestructiveAuthoringConstructs(fileClean));
    }

    public void testCanonicalizationWarningSuppressionSetting() {
        var settings = StvnProjectSettings.getInstance(getProject());
        settings.getState().suppressCanonicalizationWarning = true;

        var commented = """
            {
              // Comment here
              :type :Int
              :body 10
            }
            """;
        var psiFile = myFixture.configureByText("commented.stvn", commented);

        var action = new StvnConvertToCompactPrintAction();
        var dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(CommonDataKeys.PSI_FILE, psiFile)
                .build();

        var event = TestActionEvent.createTestEvent(action, dataContext);
        action.actionPerformed(event);

        var result = myFixture.getEditor().getDocument().getText();
        assertEquals("{:type :Int :body 10}", result);
    }

    public void testActionNamingAndBgtExecution() {
        var browseAction = new org.stvnadore.plugin.browser.StvnBrowseNamespaceAction();
        assertEquals("Browse Namespace Dependencies", browseAction.getTemplatePresentation().getText());

        var prettyAction = new StvnConvertToPrettyPrintAction();
        assertEquals("Canonicalize & Pretty Print", prettyAction.getTemplatePresentation().getText());
        assertEquals("Pretty print action must run update on BGT",
                ActionUpdateThread.BGT, prettyAction.getActionUpdateThread());

        var compactAction = new StvnConvertToCompactPrintAction();
        assertEquals("Canonicalize & Compact Print", compactAction.getTemplatePresentation().getText());
        assertEquals("Compact print action must run update on BGT",
                ActionUpdateThread.BGT, compactAction.getActionUpdateThread());

        var copyPrettyAction = new StvnCopyCanonicalPrettyPrintAction();
        assertEquals("Copy Canonical Pretty Print to Clipboard", copyPrettyAction.getTemplatePresentation().getText());
        assertEquals("Copy pretty action must run update on BGT",
                ActionUpdateThread.BGT, copyPrettyAction.getActionUpdateThread());

        var copyCompactAction = new StvnCopyCanonicalCompactPrintAction();
        assertEquals("Copy Canonical Compact Print to Clipboard", copyCompactAction.getTemplatePresentation().getText());
        assertEquals("Copy compact action must run update on BGT",
                ActionUpdateThread.BGT, copyCompactAction.getActionUpdateThread());
    }

    public void testCopyCanonicalProjectionWithTransitiveDefinitionsAndNoDanglingAliases() {
        var content = """
            {
              :defs {
                :package :org/stvnadore/finance {
                  :Transaction :Tuple( :Int :Float )
                }
                :use [ :org/stvnadore/finance { :Transaction :LocalTx } ]
                :A :String
                :T :Tuple( :LocalTx :A )
              }
              :type :T
              :body ( ( 1001 49.99 ) "sample" )
            }
            """;
        var psiFile = myFixture.configureByText("canonical_projection.stvn", content);

        var action = new StvnCopyCanonicalCompactPrintAction();
        var dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(CommonDataKeys.PSI_FILE, psiFile)
                .build();

        var event = TestActionEvent.createTestEvent(action, dataContext);
        action.actionPerformed(event);

        var clipboardContent = (String) CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        assertNotNull(clipboardContent);
        assertFalse("Clipboard must not contain unresolved dangling alias token :LocalTx", clipboardContent.contains(":LocalTx"));
        assertTrue("Clipboard must contain desugared package reference", clipboardContent.contains(":org/stvnadore/finance/Transaction"));
        assertTrue("Clipboard must retain intermediate type definition :T", clipboardContent.contains(":T"));
    }
}
