package org.stvnadore.plugin.formatting;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;

/**
 * Integration test suite verifying native formatting, 2-space indentation hierarchy,
 * Zero-Tab Invariant, comment preservation, and fenced string stability.
 */
@NullMarked
public final class StvnFormatterTest extends BasePlatformTestCase {

    public void testNativeFormatterCanonical2SpaceIndentation() {
        var unformatted = """
            {:defs{:UserID:Uint64
            :Status:Enum[#ACTIVE #INACTIVE]}
            :type:Tuple(:UserID :Status):body(1001 #ACTIVE)}
            """;
        var psiFile = myFixture.configureByText("schema.stvn", unformatted);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Formatted text must enforce 2-space indentation hierarchy:\n" + text,
                text.contains("  :defs {\n    :UserID :Uint64"));
        assertTrue("Formatted text must separate body entries with 2 spaces:\n" + text,
                text.contains("  :body"));
        assertFalse("Formatted text must obey Zero-Tab Invariant and contain no tabs:\n" + text,
                text.contains("\t"));

        // Mathematical round-trip AST equivalence
        StvnValue astOriginal = StvnCompiler.compile(unformatted).orElseThrow();
        StvnValue astFormatted = StvnCompiler.compile(text).orElseThrow();
        assertEquals("Round-trip AST must be mathematically equivalent", astOriginal, astFormatted);
    }

    public void testPreserveCommentsAndAlignments() {
        var commented = """
            {
              // Leading schema comment
              :defs {
                // User ID definition
                :UserID :Uint64
              }
              // Target type definition
              :type :UserID
              // Payload body section
              :body 42
            }
            """;
        var psiFile = myFixture.configureByText("commented.stvn", commented);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Comments must be retained intact:\n" + text, text.contains("// Leading schema comment"));
        assertTrue("Nested comments must be retained intact:\n" + text, text.contains("// User ID definition"));
        assertFalse("Formatted output must contain no tabs:\n" + text, text.contains("\t"));
    }

    public void testPreserveMultilineFencedStringBodies() {
        var fenced = """
            {
              :type :String
              :body \"\"\"[SQL]
            SELECT *
              FROM users
             WHERE id = 10;
            [SQL]\"\"\"
            }
            """;
        var psiFile = myFixture.configureByText("query.stvn", fenced);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Fenced string internal newlines and spaces must be preserved:\n" + text,
                text.contains("SELECT *\n  FROM users\n WHERE id = 10;"));
    }

    public void testSyntaxTolerantBrokenAstFormatting() {
        var broken = """
            {
              :defs {
                :Unfinished
              :type :Int32
            """;
        var psiFile = myFixture.configureByText("broken.stvn", broken);

        // Must execute cleanly without throwing exceptions during broken AST states
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertNotNull("Formatted text must not be null", text);
    }

    public void testSelectionRangeReformatting() {
        var source = """
            {
            <selection>:defs {
            :UserID :Uint64
            }</selection>
            :type :UserID
            :body 100
            }
            """;
        var psiFile = myFixture.configureByText("selection.stvn", source);

        var selectionModel = myFixture.getEditor().getSelectionModel();
        int start = selectionModel.getSelectionStart();
        int end = selectionModel.getSelectionEnd();

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformatText(psiFile, start, end);
        });

        var text = psiFile.getText();
        assertTrue("Selected section must be reformatted:\n" + text, text.contains("  :defs {\n    :UserID :Uint64\n  }"));
    }

    public void testCommentFollowingClosingBraceFormatsOnDedicatedLine() {
        var unformatted = """
            {
              :defs {
                :UserID :Uint64
              }// comment
              :type :UserID
              :body 1001
            }
            """;
        var psiFile = myFixture.configureByText("closing_brace_comment.stvn", unformatted);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertFalse("Closing brace must not collapse directly against comment prefix:\n" + text,
                text.contains("}//"));
        assertTrue("Comment following closing brace must appear on dedicated indented line:\n" + text,
                text.contains("  }\n  // comment\n"));
    }
}
