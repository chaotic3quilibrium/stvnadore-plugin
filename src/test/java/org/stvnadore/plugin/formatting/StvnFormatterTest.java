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
            {:defs{:UserID:Int
            :Status:Enum[#ACTIVE #INACTIVE]}
            :type:Tuple(:UserID :Status):body(1001 #ACTIVE)}
            """;
        var psiFile = myFixture.configureByText("schema.stvn", unformatted);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Formatted text must enforce 2-space indentation hierarchy:\n" + text,
                text.contains("  :defs {\n    :UserID :Int"));
        assertTrue("Formatted text must separate body entries with 2 spaces:\n" + text,
                text.contains("  :body"));
        assertFalse("Formatted text must obey Zero-Tab Invariant and contain no tabs:\n" + text,
                text.contains("\t"));

        // Mathematical round-trip AST equivalence
        var docOriginal = StvnCompiler.compile(unformatted).document().orElseThrow();
        var docFormatted = StvnCompiler.compile(text).document().orElseThrow();
        assertEquals("Round-trip AST must be mathematically equivalent", docOriginal.requirePayload(), docFormatted.requirePayload());
    }

    public void testPreserveCommentsAndAlignments() {
        var commented = """
            {
              // Leading schema comment
              :defs {
                // User ID definition
                :UserID :Int
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

    public void testReformatPreservesConsecutiveCommentLinesWithoutCollapsing() {
        var unformatted = """
            {
              :defs {
                // Line 1: Comment describing UserID
                // Line 2: Deprecated legacy comment
                :UserID :Int
                // Line 3: Comment following definition
                // Line 4: Another consecutive comment
              }
            }
            """;
        var psiFile = myFixture.configureByText("comments_preserve.stvn", unformatted);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Line 1 comment must be on its own line:\n" + text, text.contains("    // Line 1: Comment describing UserID\n"));
        assertTrue("Line 2 comment must be on its own line:\n" + text, text.contains("    // Line 2: Deprecated legacy comment\n"));
        assertTrue("Line 3 comment must be on its own line:\n" + text, text.contains("    // Line 3: Comment following definition\n"));
        assertTrue("Line 4 comment must be on its own line:\n" + text, text.contains("    // Line 4: Another consecutive comment\n"));
        assertFalse("Comments must never collapse onto the same line:\n" + text, text.contains("// Line 1: Comment describing UserID//"));
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
            :UserID :Int
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
        assertTrue("Selected section must be reformatted:\n" + text, text.contains("  :defs {\n    :UserID :Int\n  }"));
    }

    public void testCommentFollowingClosingBraceFormatsOnDedicatedLine() {
        var unformatted = """
            {
              :defs {
                :UserID :Int
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

    public void testReformatNormalizesMetadataEntriesToFiveTierOrder() {
        var unformatted = """
            {
              :defs {
                :DisorderedType {
                  #regex "^[A-Z]+$"
                  #maxSize 16
                  #minSize 4
                  #preserveIndent #TRUE
                  #equatable #TRUE
                } :String
              }
              :type :DisorderedType
              :body "TEST"
            }
            """;
        var psiFile = myFixture.configureByText("metadata_order.stvn", unformatted);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Reformatting must normalize metadata entries into 5-tier canonical order:\n" + text,
                text.contains("#minSize 4 #maxSize 16 #equatable #TRUE #preserveIndent #TRUE #regex \"^[A-Z]+$\""));
    }

    public void testReformatReordersPortMetadataToCanonicalSevenTierOrder() {
        var unformatted = """
            {
              :defs {
                :Port { #maxExcl 65536 #size 16 #unsigned #minIncl 1 } :Int
              }
              :type :Port
              :body 8080
            }
            """;
        var psiFile = myFixture.configureByText("format_port_order.stvn", unformatted);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Reformatting must normalize Port metadata to '{ #unsigned #size 16 #minIncl 1 #maxExcl 65536 }':\n" + text,
            text.contains("{ #unsigned #size 16 #minIncl 1 #maxExcl 65536 }"));
    }

    public void testMetaBlockFormatterEnforcesFourSpaceChildIndentation() {
        var unformatted = """
            {
              :meta {
            #name "json_example"
            #kind #BODY_INCLUDE
              }
              :body [ 1 2 3 ]
            }
            """;
        var psiFile = myFixture.configureByText("format_meta.stvn_i", unformatted);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Children of :meta must indent by 4 spaces:\n" + text,
            text.contains("  :meta {\n    #name \"json_example\"\n    #kind #BODY_INCLUDE\n  }"));
    }

    public void testMapLiteralReformatPreservesCanonicalTwoSpaceIndentation() {
        var unformatted = """
            {
              :type :Map(:String :Int)
              :body {
              [ "alpha" 10 ]
              [ "beta" 20 ]
              }
            }
            """;
        var psiFile = myFixture.configureByText("map_indent.stvn", unformatted);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Map entries must indent by 4 spaces (2 relative to :body):\n" + text,
            text.contains("  :body {\n    [ \"alpha\" 10 ]\n    [ \"beta\" 20 ]\n  }"));
        assertFalse("Formatted output must contain no tabs:\n" + text, text.contains("\t"));

        // Mathematical AST equivalence verification
        var docOriginal = StvnCompiler.compile(unformatted).document().orElseThrow();
        var docFormatted = StvnCompiler.compile(text).document().orElseThrow();
        assertEquals("Round-trip AST must be mathematically equivalent", docOriginal.requirePayload(), docFormatted.requirePayload());
    }

    public void testNestedMapLiteralIndentationHierarchy() {
        var unformatted = """
            {
              :type :Map(:String :Map(:String :Int))
              :body {
              [ "outer" {
              [ "inner" 99 ]
              } ]
              }
            }
            """;
        var psiFile = myFixture.configureByText("nested_map.stvn", unformatted);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Outer map entry must be indented at 4 spaces:\n" + text,
            text.contains("    [ \"outer\" {\n"));
        assertTrue("Inner map entry must be indented at 6 spaces:\n" + text,
            text.contains("      [ \"inner\" 99 ]\n"));
        assertTrue("Inner map closing brace must be indented at 4 spaces:\n" + text,
            text.contains("    } ]\n"));
        assertTrue("Outer map closing brace must be indented at 2 spaces:\n" + text,
            text.contains("  }\n"));
    }

    public void testMapEntriesWithCommentsPreserveIndentationAndDedicatedLines() {
        var unformatted = """
            {
              :type :Map(:String :String)
              :body {
              // Entry one comment
              [ "host" "localhost" ]
              // Entry two comment
              [ "port" "8080" ]
              }
            }
            """;
        var psiFile = myFixture.configureByText("map_comments.stvn", unformatted);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Entry one comment must be indented at 4 spaces on its own line:\n" + text,
            text.contains("    // Entry one comment\n    [ \"host\" \"localhost\" ]"));
        assertTrue("Entry two comment must be indented at 4 spaces on its own line:\n" + text,
            text.contains("    // Entry two comment\n    [ \"port\" \"8080\" ]"));
        assertFalse("Comments must never collapse onto map entry bracket:\n" + text,
            text.contains("localhost\" ]//"));
    }
}
