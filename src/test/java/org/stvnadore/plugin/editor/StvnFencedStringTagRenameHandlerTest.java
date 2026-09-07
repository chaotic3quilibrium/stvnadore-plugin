package org.stvnadore.plugin.editor;

import com.intellij.codeInsight.template.impl.TemplateManagerImpl;
import com.intellij.codeInsight.template.impl.TemplateState;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.validation.StvnFencedStringInspection;

/**
 * Platform tests verifying Shift+F6 fenced string tag renaming,
 * live template dual-delimiter synchronization, and delimiter collision protection guards.
 */
@NullMarked
public final class StvnFencedStringTagRenameHandlerTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        TemplateManagerImpl.setTemplateTesting(getTestRootDisposable());
        myFixture.enableInspections(new StvnFencedStringInspection());
    }

    public void testShiftF6OnOpeningTagRenamesBothDelimiters() {
        String code = """
            {
              :type :String
              :body \"\"\"[SQL<caret>]
              SELECT * FROM users;
              [SQL]\"\"\"
            }
            """;
        myFixture.configureByText("rename_open.stvn", code);

        var handler = new StvnFencedStringTagRenameHandler();
        DataContext dataContext = ((EditorEx) myFixture.getEditor()).getDataContext();
        assertTrue("Handler must be available on opening tag", handler.isAvailableOnDataContext(dataContext));

        handler.invoke(getProject(), myFixture.getEditor(), myFixture.getFile(), dataContext);

        TemplateState state = TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        assertNotNull("Live template state must be active", state);

        myFixture.type("QUERY\n");

        String expected = """
            {
              :type :String
              :body \"\"\"[QUERY]
              SELECT * FROM users;
              [QUERY]\"\"\"
            }
            """;
        myFixture.checkResult(expected);

        int expectedCaretOffset = expected.indexOf("[QUERY]") + "[QUERY".length();
        assertEquals("Caret must sit immediately after new tag inside opening bracket",
            expectedCaretOffset, myFixture.getEditor().getCaretModel().getOffset());
    }

    public void testShiftF6OnClosingTagRenamesBothDelimiters() {
        String code = """
            {
              :type :String
              :body \"\"\"[JSON]
              {"key": "value"}
              [JSON<caret>]\"\"\"
            }
            """;
        myFixture.configureByText("rename_close.stvn", code);

        var handler = new StvnFencedStringTagRenameHandler();
        DataContext dataContext = ((EditorEx) myFixture.getEditor()).getDataContext();
        assertTrue("Handler must be available on closing tag", handler.isAvailableOnDataContext(dataContext));

        int closingDelimiterLine = myFixture.getEditor().getCaretModel().getLogicalPosition().line;

        handler.invoke(getProject(), myFixture.getEditor(), myFixture.getFile(), dataContext);

        TemplateState state = TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        assertNotNull("Live template state must be active", state);
        assertEquals("Caret line must remain on closing delimiter immediately after startTemplate",
            closingDelimiterLine, myFixture.getEditor().getCaretModel().getLogicalPosition().line);

        myFixture.type("PAYLOAD\n");
        assertEquals("Caret line must remain on closing delimiter after template completion",
            closingDelimiterLine, myFixture.getEditor().getCaretModel().getLogicalPosition().line);

        String expected = """
            {
              :type :String
              :body \"\"\"[PAYLOAD]
              {"key": "value"}
              [PAYLOAD]\"\"\"
            }
            """;
        myFixture.checkResult(expected);

        int expectedCaretOffset = expected.lastIndexOf("[PAYLOAD]") + "[PAYLOAD".length();
        assertEquals("Caret must sit immediately after new tag inside closing bracket",
            expectedCaretOffset, myFixture.getEditor().getCaretModel().getOffset());
    }

    public void testRenameToInvalidCharacterClassIsRejected() {
        String code = """
            {
              :type :String
              :body \"\"\"[TAG<caret>]
              hello world
              [TAG]\"\"\"
            }
            """;
        myFixture.configureByText("invalid_class.stvn", code);

        var handler = new StvnFencedStringTagRenameHandler();
        DataContext dataContext = ((EditorEx) myFixture.getEditor()).getDataContext();
        assertTrue(handler.isAvailableOnDataContext(dataContext));

        handler.invoke(getProject(), myFixture.getEditor(), myFixture.getFile(), dataContext);

        TemplateState state = TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        assertNotNull(state);

        myFixture.type("C++\n");

        // Invalid character class '+' triggers rollback to TAG
        String expected = """
            {
              :type :String
              :body \"\"\"[TAG]
              hello world
              [TAG]\"\"\"
            }
            """;
        myFixture.checkResult(expected);
    }

    public void testRenameCollidingWithPayloadClosingDelimiterIsBlocked() {
        String code = """
            {
              :type :String
              :body \"\"\"[OUTER<caret>]
              Some nested code:
              [INNER]\"\"\"
              more text
              [OUTER]\"\"\"
            }
            """;
        myFixture.configureByText("collision_close.stvn", code);

        var handler = new StvnFencedStringTagRenameHandler();
        DataContext dataContext = ((EditorEx) myFixture.getEditor()).getDataContext();
        assertTrue(handler.isAvailableOnDataContext(dataContext));

        handler.invoke(getProject(), myFixture.getEditor(), myFixture.getFile(), dataContext);

        TemplateState state = TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        assertNotNull(state);

        myFixture.type("INNER\n");

        // Collision with [INNER]""" in payload causes rollback to OUTER
        String expected = """
            {
              :type :String
              :body \"\"\"[OUTER]
              Some nested code:
              [INNER]\"\"\"
              more text
              [OUTER]\"\"\"
            }
            """;
        myFixture.checkResult(expected);
    }

    public void testRenameCollidingWithPayloadOpeningDelimiterIsBlocked() {
        String code = """
            {
              :type :String
              :body \"\"\"[CONTAINER<caret>]
              \"\"\"[SUB]
              data
              [SUB]\"\"\"
              [CONTAINER]\"\"\"
            }
            """;
        myFixture.configureByText("collision_open.stvn", code);

        var handler = new StvnFencedStringTagRenameHandler();
        DataContext dataContext = ((EditorEx) myFixture.getEditor()).getDataContext();
        assertTrue(handler.isAvailableOnDataContext(dataContext));

        handler.invoke(getProject(), myFixture.getEditor(), myFixture.getFile(), dataContext);

        TemplateState state = TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        assertNotNull(state);

        myFixture.type("SUB\n");

        // Collision with """[SUB] in payload causes rollback to CONTAINER
        String expected = """
            {
              :type :String
              :body \"\"\"[CONTAINER]
              \"\"\"[SUB]
              data
              [SUB]\"\"\"
              [CONTAINER]\"\"\"
            }
            """;
        myFixture.checkResult(expected);
    }

    public void testRenameWhenTagsAreAlreadyMismatchedDoesNotTriggerHandler() {
        String code = """
            {
              :type :String
              :body \"\"\"[OPEN_TAG<caret>]
              SELECT 1;
              [DIFFERENT_CLOSE]\"\"\"
            }
            """;
        myFixture.configureByText("mismatched.stvn", code);

        var handler = new StvnFencedStringTagRenameHandler();
        DataContext dataContext = ((EditorEx) myFixture.getEditor()).getDataContext();
        assertFalse("Handler must reject mismatched block so inspection quick-fixes can handle it",
            handler.isAvailableOnDataContext(dataContext));
    }

    public void testRenameWhenBlockIsUnclosedDoesNotTriggerHandler() {
        String code = """
            {
              :type :String
              :body \"\"\"[UNCLOSED<caret>]
              SELECT 1;
            }
            """;
        myFixture.configureByText("unclosed.stvn", code);

        var handler = new StvnFencedStringTagRenameHandler();
        DataContext dataContext = ((EditorEx) myFixture.getEditor()).getDataContext();
        assertFalse("Handler must reject unclosed block",
            handler.isAvailableOnDataContext(dataContext));
    }

    public void testRenameWhenCaretIsInPayloadDoesNotTriggerHandler() {
        String code = """
            {
              :type :String
              :body \"\"\"[VALID]
              SELECT <caret>1;
              [VALID]\"\"\"
            }
            """;
        myFixture.configureByText("caret_in_payload.stvn", code);

        var handler = new StvnFencedStringTagRenameHandler();
        DataContext dataContext = ((EditorEx) myFixture.getEditor()).getDataContext();
        assertFalse("Handler must not trigger when caret is inside string body",
            handler.isAvailableOnDataContext(dataContext));
    }

    public void testRenameCancelledRestoresInitialCaretOffset() {
        String code = """
            {
              :type :String
              :body \"\"\"[JSON]
              {"key": "value"}
              [JSON<caret>]\"\"\"
            }
            """;
        myFixture.configureByText("rename_cancel.stvn", code);

        var handler = new StvnFencedStringTagRenameHandler();
        DataContext dataContext = ((EditorEx) myFixture.getEditor()).getDataContext();
        assertTrue(handler.isAvailableOnDataContext(dataContext));

        int initialCaretOffset = myFixture.getEditor().getCaretModel().getOffset();
        int initialLine = myFixture.getEditor().getCaretModel().getLogicalPosition().line;

        handler.invoke(getProject(), myFixture.getEditor(), myFixture.getFile(), dataContext);

        TemplateState state = TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        assertNotNull(state);

        state.cancelTemplate();

        assertEquals("Caret offset must be restored to initial position upon cancellation",
            initialCaretOffset, myFixture.getEditor().getCaretModel().getOffset());
        assertEquals("Caret line must match closing delimiter line upon cancellation",
            initialLine, myFixture.getEditor().getCaretModel().getLogicalPosition().line);

        myFixture.checkResult(code.replace("<caret>", ""));
    }

    public void testRenameCollidingWithPayloadClosingDelimiterRestoresCaret() {
        String code = """
            {
              :type :String
              :body \"\"\"[OUTER]
              Some nested code:
              [INNER]\"\"\"
              more text
              [OUTER<caret>]\"\"\"
            }
            """;
        myFixture.configureByText("collision_close_caret.stvn", code);

        var handler = new StvnFencedStringTagRenameHandler();
        DataContext dataContext = ((EditorEx) myFixture.getEditor()).getDataContext();
        assertTrue(handler.isAvailableOnDataContext(dataContext));

        int initialCaretOffset = myFixture.getEditor().getCaretModel().getOffset();
        int initialLine = myFixture.getEditor().getCaretModel().getLogicalPosition().line;

        handler.invoke(getProject(), myFixture.getEditor(), myFixture.getFile(), dataContext);

        TemplateState state = TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        assertNotNull(state);

        myFixture.type("INNER\n");

        assertEquals("Caret offset must be restored to initial position upon collision rollback",
            initialCaretOffset, myFixture.getEditor().getCaretModel().getOffset());
        assertEquals("Caret line must remain on closing delimiter after collision rollback",
            initialLine, myFixture.getEditor().getCaretModel().getLogicalPosition().line);
    }

    public void testShiftF6TagShorteningOnOpeningTagRenamesAndAnchorsCaret() {
        String code = """
            {
              :type :String
              :body \"\"\"[FENCE<caret>]
              payload content
              [FENCE]\"\"\"
            }
            """;
        myFixture.configureByText("shorten_open.stvn", code);

        var handler = new StvnFencedStringTagRenameHandler();
        DataContext dataContext = ((EditorEx) myFixture.getEditor()).getDataContext();
        assertTrue("Handler must be available on opening tag", handler.isAvailableOnDataContext(dataContext));

        int openLine = myFixture.getEditor().getCaretModel().getLogicalPosition().line;

        handler.invoke(getProject(), myFixture.getEditor(), myFixture.getFile(), dataContext);

        TemplateState state = TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        assertNotNull("Live template state must be active", state);

        myFixture.type("F\n");

        String expected = """
            {
              :type :String
              :body \"\"\"[F]
              payload content
              [F]\"\"\"
            }
            """;
        myFixture.checkResult(expected);

        int expectedCaretOffset = expected.indexOf("[F]") + "[F".length();
        int actualCaretOffset = myFixture.getEditor().getCaretModel().getOffset();
        assertEquals("Caret must sit immediately after shortened tag inside opening bracket",
            expectedCaretOffset, actualCaretOffset);
        assertEquals("Caret must remain on opening delimiter line",
            openLine, myFixture.getEditor().getCaretModel().getLogicalPosition().line);
        assertTrue("Caret must not advance past closing bracket",
            actualCaretOffset <= expected.indexOf(']', expected.indexOf("[F]")));
    }

    public void testShiftF6TagShorteningOnClosingTagRenamesAndAnchorsCaret() {
        String code = """
            {
              :type :String
              :body \"\"\"[FENCE]
              payload content
              [FENCE<caret>]\"\"\"
            }
            """;
        myFixture.configureByText("shorten_close.stvn", code);

        var handler = new StvnFencedStringTagRenameHandler();
        DataContext dataContext = ((EditorEx) myFixture.getEditor()).getDataContext();
        assertTrue("Handler must be available on closing tag", handler.isAvailableOnDataContext(dataContext));

        int closeLine = myFixture.getEditor().getCaretModel().getLogicalPosition().line;

        handler.invoke(getProject(), myFixture.getEditor(), myFixture.getFile(), dataContext);

        TemplateState state = TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        assertNotNull("Live template state must be active", state);

        myFixture.type("F\n");

        String expected = """
            {
              :type :String
              :body \"\"\"[F]
              payload content
              [F]\"\"\"
            }
            """;
        myFixture.checkResult(expected);

        int expectedCaretOffset = expected.lastIndexOf("[F]") + "[F".length();
        int actualCaretOffset = myFixture.getEditor().getCaretModel().getOffset();
        assertEquals("Caret must sit immediately after shortened tag inside closing bracket",
            expectedCaretOffset, actualCaretOffset);
        assertEquals("Caret must remain on closing delimiter line",
            closeLine, myFixture.getEditor().getCaretModel().getLogicalPosition().line);
        assertTrue("Caret must not advance past closing bracket",
            actualCaretOffset <= expected.indexOf(']', expected.lastIndexOf("[F]")));
    }
}
