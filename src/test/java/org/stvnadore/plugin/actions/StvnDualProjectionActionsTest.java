package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.psi.PsiComment;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.settings.StvnProjectSettings;

/**
 * Integration test suite verifying dual projection actions and comment destruction guard.
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

    public void testCommentDestructionGuardDetectsComments() {
        var commented = """
            {
              // Comment here
              :type :Int32
              :body 10
            }
            """;
        var psiFile = myFixture.configureByText("commented.stvn", commented);
        var comment = PsiTreeUtil.findChildOfType(psiFile, PsiComment.class);
        assertNotNull("Must detect PsiComment in commented document", comment);
    }

    public void testCommentDestructionGuardSuppressionSetting() {
        var settings = StvnProjectSettings.getInstance(getProject());
        settings.getState().suppressCompactCommentWarning = true;

        var commented = """
            {
              // Comment here
              :type :Int32
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
        assertEquals("{:type :Int32 :body 10}", result);
    }

    public void testActionUpdateThreadReturnsBgt() {
        var prettyAction = new StvnConvertToPrettyPrintAction();
        assertEquals("Pretty print action must run update on BGT",
                ActionUpdateThread.BGT, prettyAction.getActionUpdateThread());

        var compactAction = new StvnConvertToCompactPrintAction();
        assertEquals("Compact print action must run update on BGT",
                ActionUpdateThread.BGT, compactAction.getActionUpdateThread());
    }
}
