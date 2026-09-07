package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.settings.StvnSettings;

import java.util.List;

/**
 * Platform tests verifying Rule STR-04 fenced string inspection diagnostics,
 * resilient quick-fixes, arbitrary recursive nesting, and orphan fence guards.
 */
@NullMarked
public final class StvnFencedStringInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new StvnFencedStringInspection());
    }

    @Override
    protected void tearDown() throws Exception {
        StvnSettings.getInstance(getProject()).getState().blockStringEnterStyle = StvnSettings.BlockStringEnterStyle.EXPANDED_THREE_LINE;
        super.tearDown();
    }

    public void testValidFencedStringWithoutArrowPassesCleanly() {
        String code = """
            {
              :type :String
              :body \"\"\"[SQL]
              SELECT * FROM users;
              [SQL]\"\"\"
            }
            """;
        myFixture.configureByText("valid_no_arrow.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        boolean hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Valid fenced string without arrow must produce zero errors", hasErrors);
    }

    public void testValidFencedStringWithArrowPassesCleanly() {
        String code = """
            {
              :type :String
              :body \"\"\"->[SHA256-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad]
              nested content
              [SHA256-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad]\"\"\"
            }
            """;
        myFixture.configureByText("valid_with_arrow.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        boolean hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Valid fenced string with arrow must produce zero errors", hasErrors);
    }

    public void testRecursiveNestedFencedStringsPreserveInnerDelimiters() {
        String code = """
            {
              :type :String
              :body \"\"\"[OUTER]
              Here is an embedded inner block:
              \"\"\"[INNER]
              SELECT id FROM inner_table;
              [INNER]\"\"\"
              Trailing outer content.
              [OUTER]\"\"\"
            }
            """;
        myFixture.configureByText("recursive_nesting.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        boolean hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Recursive nested fenced strings must parse with zero errors and preserve inner delimiters", hasErrors);
    }

    public void testEmptyTagRejectedWithRuleStr04() {
        String code = """
            {
              :type :String
              :body \"\"\"[]
              content
              []\"\"\"
            }
            """;
        myFixture.configureByText("empty_tag.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue(highlights.stream().anyMatch(h -> h.getDescription().contains("Rule STR-04 violation: Fenced string delimiter tag must not be empty")));
    }

    public void testWhitespaceInTagRejectedWithRuleStr04() {
        String code = """
            {
              :type :String
              :body \"\"\"[ ]
              content
              [ ]\"\"\"
            }
            """;
        myFixture.configureByText("whitespace_tag.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue(highlights.stream().anyMatch(h -> h.getDescription().contains("Rule STR-04 violation: Fenced string delimiter tag must not contain whitespace")));
    }

    public void testIllegalCharactersRejectedWithRuleStr04() {
        String code = """
            {
              :type :String
              :body \"\"\"[C++]
              int main() {}
              [C++]\"\"\"
            }
            """;
        myFixture.configureByText("illegal_char_tag.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue(highlights.stream().anyMatch(h -> h.getDescription().contains("Rule STR-04 violation: Fenced string delimiter tag 'C++' contains invalid characters")));
    }

    public void testTagLengthExceeding256Rejected() {
        String longTag = "A".repeat(257);
        String code = "{\n  :type :String\n  :body \"\"\"[" + longTag + "]\n  content\n  [" + longTag + "]\"\"\"\n}\n";
        myFixture.configureByText("long_tag.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue(highlights.stream().anyMatch(h -> h.getDescription().contains("Rule STR-04 violation: Fenced string delimiter tag length exceeds maximum of 256 characters")));
    }

    public void testMismatchedClosingTagRejected() {
        String code = """
            {
              :type :String
              :body \"\"\"[SQL]
              SELECT 1;
              [JSON]\"\"\"
            }
            """;
        myFixture.configureByText("mismatched_close.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue(highlights.stream().anyMatch(h -> h.getDescription().contains("Rule STR-04 violation: Mismatched closing fence tag '[JSON]', expected '[SQL]'")));
    }

    public void testQuickFixBalancesClosingTagWithTrailingWhitespace() {
        String code = "{\n  :type :String\n  :body \"\"\"[SQL]\n  SELECT 1;\n  [JSON]\"\"\"  \n}\n";
        myFixture.configureByText("quickfix_balance_trailing.stvn", code);
        myFixture.doHighlighting();
        var action = myFixture.getAllQuickFixes().stream()
            .filter(f -> f.getText().contains("Replace '[JSON]\"\"\"' with '[SQL]\"\"\"'"))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected balance closing tag quick-fix to be registered", action);
        myFixture.launchAction(action);
        myFixture.checkResult("{\n  :type :String\n  :body \"\"\"[SQL]\n  SELECT 1;\n  [SQL]\"\"\"  \n}\n");
    }

    public void testAppendClosingFenceDoesNotSwallowDownstreamContent() {
        String code = """
            {
              :type :Tuple( :String :Tuple( :Int32 :Int32 ) )
              :body (
                \"\"\"[SQL]
                (10 20)
              )
            }
            """;
        myFixture.configureByText("unclosed_before_tuple.stvn", code);
        myFixture.doHighlighting();
        var action = myFixture.getAllQuickFixes().stream()
            .filter(f -> f.getText().contains("Append closing delimiter '[SQL]\"\"\"'"))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected append closing delimiter quick-fix to be registered", action);
        myFixture.launchAction(action);
        String expected = """
            {
              :type :Tuple( :String :Tuple( :Int32 :Int32 ) )
              :body (
                \"\"\"[SQL]
                [SQL]\"\"\"
                (10 20)
              )
            }
            """;
        myFixture.checkResult(expected);
        List<HighlightInfo> postHighlights = myFixture.doHighlighting();
        boolean hasErrors = postHighlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Repaired fenced string block must produce zero errors and preserve downstream tuple", hasErrors);
    }

    public void testSupplyDefaultTagAtomicallyClosesUnclosedBlock() {
        String code = """
            {
              :type :Tuple( :String :Tuple( :Int32 :Int32 ) )
              :body (
                \"\"\"[]
                (10 20)
              )
            }
            """;
        myFixture.configureByText("empty_tag_unclosed.stvn", code);
        myFixture.doHighlighting();
        var action = myFixture.getAllQuickFixes().stream()
            .filter(f -> f.getText().contains("Supply default tag '[TEXT]'"))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected supply default tag quick-fix to be registered", action);
        myFixture.launchAction(action);
        String expected = """
            {
              :type :Tuple( :String :Tuple( :Int32 :Int32 ) )
              :body (
                \"\"\"[TEXT]
                [TEXT]\"\"\"
                (10 20)
              )
            }
            """;
        myFixture.checkResult(expected);
        List<HighlightInfo> postHighlights = myFixture.doHighlighting();
        boolean hasErrors = postHighlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Atomically supplied tag and closed block must produce zero errors", hasErrors);
    }

    public void testOpeningFencePrecedingExistingFencedBlockRegistersErrorOnOpeningLine() {
        String code = """
            {
              :type :Tuple( :String :String )
              :body (
                \"\"\"[SQL]
                \"\"\"[TEXT]
                Hello world
                [TEXT]\"\"\"
              )
            }
            """;
        myFixture.configureByText("preceding_block.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        var sqlHighlight = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("expected closing delimiter '[SQL]\"\"\"'"))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected unclosed error on opening line for [SQL]", sqlHighlight);

        var action = myFixture.getAllQuickFixes().stream()
            .filter(f -> f.getText().contains("Append closing delimiter '[SQL]\"\"\"'"))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected AppendClosingFenceQuickFix to be available on opening line", action);
        myFixture.launchAction(action);
        assertTrue(myFixture.getEditor().getDocument().getText().contains("\"\"\"[SQL]\n    [SQL]\"\"\""));
    }

    public void testBidirectionalQuickFixesOnMismatchedFences() {
        String code = """
            {
              :type :String
              :body \"\"\"[SQL]
              SELECT 1;
              [JSON]\"\"\"
            }
            """;
        myFixture.configureByText("mismatched_bidirectional.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue("Highlight on closing range must exist", highlights.stream().anyMatch(h -> h.getDescription() != null && h.getDescription().contains("Mismatched closing fence tag '[JSON]', expected '[SQL]'")));
        assertTrue("Highlight on opening range must exist", highlights.stream().anyMatch(h -> h.getDescription() != null && h.getDescription().contains("Mismatched opening fence tag '[SQL]', closing fence has '[JSON]'")));

        var updateOpeningAction = myFixture.getAllQuickFixes().stream()
            .filter(f -> f.getText().contains("Replace '[SQL]' with '[JSON]'"))
            .findFirst()
            .orElse(null);
        assertNotNull("Quick-fix updating opening from closing must be available", updateOpeningAction);
        myFixture.launchAction(updateOpeningAction);
        String expected = """
            {
              :type :String
              :body \"\"\"[JSON]
              SELECT 1;
              [JSON]\"\"\"
            }
            """;
        myFixture.checkResult(expected);
    }

    public void testEnterKeyAutoClosesBareBlockStringExpandedThreeLine() {
        StvnSettings.getInstance(getProject()).getState().blockStringEnterStyle = StvnSettings.BlockStringEnterStyle.EXPANDED_THREE_LINE;
        String code = "{\n  :type :String\n  :body \"\"\"<caret>\n}\n";
        myFixture.configureByText("enter_bare_expanded.stvn", code);
        myFixture.type('\n');
        String expected = "{\n  :type :String\n  :body \"\"\"\n    \n  \"\"\"\n}\n";
        myFixture.checkResult(expected);
    }

    public void testEnterKeyAutoClosesBareBlockStringTightTwoLine() {
        StvnSettings.getInstance(getProject()).getState().blockStringEnterStyle = StvnSettings.BlockStringEnterStyle.TIGHT_TWO_LINE;
        String code = "{\n  :type :String\n  :body \"\"\"<caret>\n}\n";
        myFixture.configureByText("enter_bare_tight.stvn", code);
        myFixture.type('\n');
        String expected = "{\n  :type :String\n  :body \"\"\"\n    \"\"\"\n}\n";
        myFixture.checkResult(expected);
    }

    public void testEnterKeyAutoClosesFencedStringTightTwoLine() {
        StvnSettings.getInstance(getProject()).getState().blockStringEnterStyle = StvnSettings.BlockStringEnterStyle.TIGHT_TWO_LINE;
        String code = "{\n  :type :String\n  :body \"\"\"[SQL]<caret>\n}\n";
        myFixture.configureByText("enter_fenced_tight.stvn", code);
        myFixture.type('\n');
        String expected = "{\n  :type :String\n  :body \"\"\"[SQL]\n    [SQL]\"\"\"\n}\n";
        myFixture.checkResult(expected);
    }

    public void testCreateFencedStringIntentionExecution() {
        String code = "{\n  :type :String\n  :body \"\"\"<caret>\n}\n";
        myFixture.configureByText("convert_intention.stvn", code);
        var intention = myFixture.findSingleIntention("Convert to Fenced String Block");
        assertNotNull("Expected 'Convert to Fenced String Block' intention", intention);
        myFixture.launchAction(intention);
        String expected = "{\n  :type :String\n  :body \"\"\"[TEXT]\n    \n  [TEXT]\"\"\"\n}\n";
        myFixture.checkResult(expected);
    }

    public void testBracketTypingAutoPairsFencedString() {
        String code = "{\n  :type :String\n  :body \"\"\"<caret>\n}\n";
        myFixture.configureByText("typed_bracket.stvn", code);
        myFixture.type('[');
        assertEquals("{\n  :type :String\n  :body \"\"\"[]\n    \n  []\"\"\"\n}\n", myFixture.getEditor().getDocument().getText());
        int caretOffset = myFixture.getEditor().getCaretModel().getOffset();
        int openBracketPos = myFixture.getEditor().getDocument().getText().indexOf('[');
        assertEquals("Caret must be placed inside opening brackets", openBracketPos + 1, caretOffset);
    }

    public void testEnterKeyAutoClosesFencedString() {
        String code = "{\n  :type :String\n  :body \"\"\"[SQL]<caret>\n}\n";
        myFixture.configureByText("enter_auto_close.stvn", code);
        myFixture.type('\n');
        String expected = "{\n  :type :String\n  :body \"\"\"[SQL]\n    \n  [SQL]\"\"\"\n}\n";
        myFixture.checkResult(expected);
    }

    public void testEnterKeyAutoClosesWithArrowSyntax() {
        String code = "{\n  :type :String\n  :body \"\"\"->[MARKDOWN]<caret>\n}\n";
        myFixture.configureByText("enter_arrow_close.stvn", code);
        myFixture.type('\n');
        String expected = "{\n  :type :String\n  :body \"\"\"->[MARKDOWN]\n    \n  [MARKDOWN]\"\"\"\n}\n";
        myFixture.checkResult(expected);
    }

    public void testEnterKeyDoesNotDuplicateExistingClosingFence() {
        String code = "{\n  :type :String\n  :body \"\"\"[SQL]<caret>\n  [SQL]\"\"\"\n}\n";
        myFixture.configureByText("enter_no_dup.stvn", code);
        myFixture.type('\n');
        String expected = "{\n  :type :String\n  :body \"\"\"[SQL]\n  \n  [SQL]\"\"\"\n}\n";
        myFixture.checkResult(expected);
    }

    public void testEnterKeyCaretInsideTagDoesNotAutoClose() {
        String code = "{\n  :type :String\n  :body \"\"\"[S<caret>QL]\n}\n";
        myFixture.configureByText("enter_inside_tag.stvn", code);
        myFixture.type('\n');
        String expected = "{\n  :type :String\n  :body \"\"\"[S\n  QL]\n}\n";
        myFixture.checkResult(expected);
    }
}
