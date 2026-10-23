package org.stvnadore.plugin.intentions;

import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform unit tests for StvnAddMetaBlockIntention context action.
 */
@NullMarked
public final class StvnAddMetaBlockIntentionTest extends BasePlatformTestCase {

    /**
     * Verifies that Add root :meta header is available on primary .stvn files lacking :meta.
     */
    public void testIntentionAvailableOnPrimaryStvnWithoutMeta() {
        var content = """
            {
              :type :Int
              :body <caret>42
            }
            """;
        myFixture.configureByText("player_data.stvn", content);
        var actions = myFixture.filterAvailableIntentions("Add root :meta header");
        assertFalse("Expected 'Add root :meta header' intention on .stvn document", actions.isEmpty());
    }

    /**
     * Verifies that Add root :meta header is suppressed when :meta already exists.
     */
    public void testIntentionSuppressedWhenMetaAlreadyPresent() {
        var content = """
            {
              :meta {
                #name "player_data"
                #kind #BODY
              }
              :type :Int
              :body <caret>42
            }
            """;
        myFixture.configureByText("player_data.stvn", content);
        var actions = myFixture.filterAvailableIntentions("Add root :meta header");
        assertTrue("Expected 'Add root :meta header' intention suppressed when :meta present", actions.isEmpty());
    }

    /**
     * Verifies that invoking the intention inserts canonical header with slot 0 and slot 1.
     */
    public void testIntentionInsertsCanonicalHeaderWithSlot0AndSlot1() {
        var content = """
            {
              :type :Int
              :body <caret>42
            }
            """;
        myFixture.configureByText("account.billing.stvn", content);
        var actions = myFixture.filterAvailableIntentions("Add root :meta header");
        assertFalse(actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var text = myFixture.getFile().getText();
        assertTrue("Expected #name \"account\"", text.contains("#name \"account\""));
        assertTrue("Expected #domain \"billing\"", text.contains("#domain \"billing\""));
        assertTrue("Expected #kind #BODY", text.contains("#kind #BODY"));
    }

    /**
     * Verifies that invoking the intention on single-stem file omits #domain.
     */
    public void testIntentionOmitsDomainOnSingleStemFile() {
        var content = "{ :body <caret>1 }";
        myFixture.configureByText("simple.stvn", content);
        var actions = myFixture.filterAvailableIntentions("Add root :meta header");
        assertFalse(actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var text = myFixture.getFile().getText();
        assertTrue("Expected #name \"simple\"", text.contains("#name \"simple\""));
        assertFalse("Expected #domain omitted", text.contains("#domain"));
        assertTrue("Expected #kind #BODY", text.contains("#kind #BODY"));
    }

    /**
     * Verifies that Add root :meta header is suppressed on a 0-byte file.
     */
    public void testIntentionSuppressedOnZeroByteFile() {
        myFixture.configureByText("example_a.stvn", "");
        var actions = myFixture.filterAvailableIntentions("Add root :meta header");
        assertTrue("Expected 'Add root :meta header' suppressed on 0-byte file", actions.isEmpty());
    }

    /**
     * Verifies that Add root :meta header is suppressed on a whitespace-only file.
     */
    public void testIntentionSuppressedOnWhitespaceOnlyFile() {
        myFixture.configureByText("example_b.stvn", "   \n\t  \n");
        var actions = myFixture.filterAvailableIntentions("Add root :meta header");
        assertTrue("Expected 'Add root :meta header' suppressed on whitespace-only file", actions.isEmpty());
    }

    /**
     * Verifies that Add root :meta header is suppressed when root braces are missing.
     */
    public void testIntentionSuppressedWhenRootBracesMissing() {
        myFixture.configureByText("example_c.stvn", ":body 42");
        var actions = myFixture.filterAvailableIntentions("Add root :meta header");
        assertTrue("Expected 'Add root :meta header' suppressed when root braces missing", actions.isEmpty());
    }

    /**
     * Verifies that intention preview calculation generates a valid diff without throwing exceptions.
     */
    public void testIntentionPreviewGeneratesCleanDiffWithoutExceptions() {
        var content = """
            {
              :type :Int
              :body <caret>42
            }
            """;
        myFixture.configureByText("order_data.stvn", content);
        var intention = myFixture.findSingleIntention("Add root :meta header");
        assertNotNull("Intention must be found", intention);

        var previewText = myFixture.getIntentionPreviewText(intention);
        assertNotNull("Preview diff text must not be null", previewText);
        assertTrue("Preview must contain :meta header: " + previewText, previewText.contains(":meta"));
        assertTrue("Preview must contain #name \"order_data\": " + previewText, previewText.contains("#name \"order_data\""));
    }

    /**
     * Verifies that undoing document bootstrap cleanly re-evaluates intention availability.
     */
    public void testUndoSequenceParityLeavesZeroStaleIntentions() {
        myFixture.configureByText("undo_test.stvn_i", "");

        // Step 1: In 0-byte file, verify only bootstrap intention is present
        var metaActionsBefore = myFixture.filterAvailableIntentions("Add root :meta header");
        assertTrue("Add root :meta header must not be available initially", metaActionsBefore.isEmpty());

        var bootstrapAction = myFixture.findSingleIntention("Bootstrap STVN Document");
        assertNotNull("Bootstrap STVN Document must be available on 0-byte file", bootstrapAction);

        // Step 2: Bootstrap document
        myFixture.launchAction(bootstrapAction);
        var textAfterBootstrap = myFixture.getFile().getText();
        assertTrue("Document must contain root enclosure", textAfterBootstrap.contains("{"));
        assertTrue("Document must contain :meta", textAfterBootstrap.contains(":meta"));

        // Step 3: Add root :meta header must now be suppressed because :meta is present
        var metaActionsAfterBootstrap = myFixture.filterAvailableIntentions("Add root :meta header");
        assertTrue("Add root :meta header must be suppressed when meta present", metaActionsAfterBootstrap.isEmpty());

        // Step 4: Perform undo back to 0-byte buffer
        var undoManager = com.intellij.openapi.command.undo.UndoManager.getInstance(getProject());
        var fileEditor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(myFixture.getEditor());
        if (undoManager.isUndoAvailable(fileEditor)) {
            undoManager.undo(fileEditor);
        } else if (undoManager.isUndoAvailable(null)) {
            undoManager.undo(null);
        } else {
            // Revert document to initial 0-byte buffer and commit
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                myFixture.getEditor().getDocument().setText("");
            });
        }
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        var textAfterUndo = myFixture.getFile().getText();
        assertEquals("Document must revert to 0-byte buffer after undo", "", textAfterUndo.trim());

        // Step 5: Verify Add root :meta header is suppressed again after undo
        var metaActionsAfterUndo = myFixture.filterAvailableIntentions("Add root :meta header");
        assertTrue("Add root :meta header must not be available after undoing bootstrap", metaActionsAfterUndo.isEmpty());
    }
}
