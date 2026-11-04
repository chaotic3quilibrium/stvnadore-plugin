package org.stvnadore.plugin.formatting;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.plugin.settings.StvnSettings;

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
        var settings = StvnSettings.getInstance(getProject());
        var previousMode = settings.getState().reformatDelegationMode;
        try {
            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING;
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
            assertTrue("Comment following closing brace in PSI mode must appear on dedicated indented line:\n" + text,
                    text.contains("  }\n  // comment\n"));

            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_SOURCE_FORMATTER;
            var coreFile = myFixture.configureByText("closing_brace_comment_core.stvn", unformatted);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(coreFile);
            });
            var coreText = coreFile.getText();
            assertFalse("Closing brace must not collapse directly against comment prefix:\n" + coreText,
                    coreText.contains("}//"));
            assertTrue("Comment following closing brace in Core Source Formatter mode must appear on dedicated indented line:\n" + coreText,
                    coreText.contains("  }\n  // comment\n"));
        } finally {
            settings.getState().reformatDelegationMode = previousMode;
        }
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
            text.contains("  :body {\n    [\"alpha\" 10]\n    [\"beta\" 20]\n  }"));
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
            text.contains("    [\"outer\" {\n"));
        assertTrue("Inner map entry must be indented at 6 spaces:\n" + text,
            text.contains("      [\"inner\" 99]\n"));
        assertTrue("Inner map closing brace must be indented at 4 spaces:\n" + text,
            text.contains("    }]\n"));
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
            text.contains("    // Entry one comment\n    [\"host\" \"localhost\"]"));
        assertTrue("Entry two comment must be indented at 4 spaces on its own line:\n" + text,
            text.contains("    // Entry two comment\n    [\"port\" \"8080\"]"));
        assertFalse("Comments must never collapse onto map entry bracket:\n" + text,
            text.contains("localhost\" ]//"));
    }

    public void testMultilineUseAliasBlockCanonicalIndentation() {
        var unformatted = """
            {
              :defs {
                :use [ :org/stvnadore/prelude/meta {
                         :StvnPosixId64 :StvnPosixId64
                       } ]
              }
            }
            """;
        var psiFile = myFixture.configureByText("format_use.stvn", unformatted);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Alias entry must indent by 8 spaces (+4 relative to :use) and closing brace by 6 spaces:\n" + text,
            text.contains("    :use [ :org/stvnadore/prelude/meta {\n        :StvnPosixId64 :StvnPosixId64\n      } ]"));
        assertFalse("Alias entry must not align to opening brace column:\n" + text,
            text.contains("             :StvnPosixId64"));
    }

    public void testMultilineUseSameLineBraceWithCommentsAndDoubleSpace() {
        var unformatted = """
            {
              :defs {
                :use [ :org/stvnadore/prelude  {                   // <-- comment
                    :Port :Port                                    // <-- comment
                  #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX // <-- comment
                  }                                                // <-- comment
                ]
              }
            }
            """;
        var psiFile = myFixture.configureByText("format_use_same_line_brace.stvn", unformatted);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Double space before '{' must collapse to 1 space while preserving trailing comment column alignment:\n" + text,
            text.contains("    :use [ :org/stvnadore/prelude {                    // <-- comment"));
        assertTrue("Alias pairs must indent at column 8:\n" + text,
            text.contains("\n        :Port :Port")
                && text.contains("\n        #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX // <-- comment"));
        assertTrue("Closing '}' must indent at column 6 and closing ']' at column 4:\n" + text,
            text.contains("\n      }") && text.contains("\n    ]"));
    }

    public void testMultilineUseNewLineTargetBraceIndentation() {
        var unformatted = """
            {
              :defs {
                :use [
                  :org/stvnadore/prelude  {
                  :Port :Port
                  #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX
                }
                ]
              }
            }
            """;
        var expectedBlock = """
                :use [
                  :org/stvnadore/prelude {
                    :Port :Port
                    #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX
                  }
                ]
            """.stripTrailing();

        var settings = StvnSettings.getInstance(getProject());
        var previousMode = settings.getState().reformatDelegationMode;
        try {
            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_SOURCE_FORMATTER;
            var coreFile = myFixture.configureByText("format_use_newline_target_core.stvn", unformatted);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(coreFile);
            });
            assertTrue("Core Source Formatter must indent Layout 2 :use at columns 4/6/8/6/4:\n" + coreFile.getText(),
                coreFile.getText().contains(expectedBlock));

            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING;
            var psiFile = myFixture.configureByText("format_use_newline_target.stvn", unformatted);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(psiFile);
            });
            assertTrue("PSI formatter must indent Layout 2 :use at columns 4/6/8/6/4:\n" + psiFile.getText(),
                psiFile.getText().contains(expectedBlock));
        } finally {
            settings.getState().reformatDelegationMode = previousMode;
        }
    }

    public void testAdjustLineIndentForMultiLineUseLayouts1And2() {
        var layout1Unindented = """
            {
              :defs {
            :use [ :org/stvnadore/prelude {
            :Port :Port
            #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX
            }
            ]
              }
            }
            """;
        var expectedLayout1 = """
            {
              :defs {
                :use [ :org/stvnadore/prelude {
                    :Port :Port
                    #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX
                  }
                ]
              }
            }
            """;
        var psiFile1 = myFixture.configureByText("paste_layout1.stvn", layout1Unindented);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).adjustLineIndent(psiFile1, psiFile1.getTextRange());
        });
        assertEquals("adjustLineIndent on Layout 1 :use must enforce 4/8/6/4 columns",
            expectedLayout1, psiFile1.getText());

        var layout2Unindented = """
            {
              :defs {
            :use [
            :org/stvnadore/prelude {
            :Port :Port
            #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX
            }
            ]
              }
            }
            """;
        var expectedLayout2 = """
            {
              :defs {
                :use [
                  :org/stvnadore/prelude {
                    :Port :Port
                    #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX
                  }
                ]
              }
            }
            """;
        var psiFile2 = myFixture.configureByText("paste_layout2.stvn", layout2Unindented);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).adjustLineIndent(psiFile2, psiFile2.getTextRange());
        });
        assertEquals("adjustLineIndent on Layout 2 :use must enforce 4/6/8/6/4 columns",
            expectedLayout2, psiFile2.getText());
    }

    public void testGauntletSeamsFormatsWithPass3SemanticErrorInBothCoreSourceAndPsiModes() {
        var gauntletUnformatted = """
            // Gauntlet seams interactive smoke test with Pass 3 #DRAINING subset violation
            {
              :meta {
                #kind #BODY_INCLUDE
                #domain "core_test"
                #name "gauntlet_seams"
              }



              :defs {
                :use [
              :org/stvnadore/prelude {
                :Port :Port
                #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX
              }
                ]
                :NodeState :Enum [ #STARTING   #ACTIVE   #DRAINING   #STOPPED ]
                :ActiveNodeState { #filterExcl [ #DRAINING #STOPPED ] } :NodeState
                :ScrambledInline :Tuple(
                  :Port
                  :ActiveNodeState
                  { #maxExcl 1000 #unsigned #minIncl 1 #size 16 } :Int
                  { #maxSize 128 #regex #SHA256_LOWER_CASE_REGEX } :String
                )
                #DEFAULT_PORT :Port 8080
              }
              :type :ScrambledInline
              :body (
                #DEFAULT_PORT // Preserved constant reference
                #DRAINING // Intentionally excluded variant triggering Pass 3 semantic error
                42
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
              )
            }
            """;

        var expected = """
            // Gauntlet seams interactive smoke test with Pass 3 #DRAINING subset violation
            {
              :meta {
                #name "gauntlet_seams"
                #domain "core_test"
                #kind #BODY_INCLUDE
              }

              :defs {
                :use [
                  :org/stvnadore/prelude {
                    :Port :Port
                    #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX
                  }
                ]
                :NodeState :Enum[#STARTING #ACTIVE #DRAINING #STOPPED]
                :ActiveNodeState { #filterExcl [#DRAINING #STOPPED] } :NodeState
                :ScrambledInline :Tuple(
                  :Port
                  :ActiveNodeState
                  { #unsigned #size 16 #minIncl 1 #maxExcl 1000 } :Int
                  { #maxSize 128 #regex #SHA256_LOWER_CASE_REGEX } :String
                )
                #DEFAULT_PORT :Port 8080
              }
              :type :ScrambledInline
              :body (
                #DEFAULT_PORT // Preserved constant reference
                #DRAINING // Intentionally excluded variant triggering Pass 3 semantic error
                42
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
              )
            }
            """;

        var settings = StvnSettings.getInstance(getProject());
        var previousMode = settings.getState().reformatDelegationMode;
        try {
            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_SOURCE_FORMATTER;
            var coreFile = myFixture.configureByText("gauntlet_seams.core_test.stvn_i", gauntletUnformatted);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(coreFile);
            });
            assertEquals("CORE_SOURCE_FORMATTER must format gauntlet_seams.core_test.stvn_i non-destructively despite Pass 3 #DRAINING error",
                expected, coreFile.getText());

            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING;
            var psiFile = myFixture.configureByText("gauntlet_seams_psi.core_test.stvn_i", gauntletUnformatted);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(psiFile);
            });
            assertEquals("PSI_STRUCTURE_PRESERVING must format gauntlet_seams.core_test.stvn_i identically to CORE_SOURCE_FORMATTER",
                expected, psiFile.getText());

            // Verify 3 authored multi-line :body tuples in gauntlet_seams.core_test.stvn_i remain multi-line in both modes
            var unformattedThreeTuplesGauntlet = """
                {
                  :meta {
                    #kind #BODY_INCLUDE
                    #domain "core_test"
                    #name "gauntlet_seams"
                  }
                  :defs {
                    :use [
                  :org/stvnadore/prelude {
                    :Port :Port
                    :Uint64 :Uint64
                  }
                    ]
                    :NodeState :Enum [ #INIT   #RUNNING   #DRAINING ]
                    :Outcome :Option(:Either(:String :Union(:Port :Uint64)))
                    #DEFAULT_OUTCOME :Outcome #Some #Right #1 8080
                  }
                  :type :Seq(:Tuple(:Outcome :NodeState :Uint64))
                  :body [
                    // Row 0 (100% Valid):
                    (
                      #Some #Right #1 8080
                      #RUNNING
                      18446744073709551615
                    )

                    // Row 1 (Fault Isolation & Constant Barrier):
                    (
                      #DEFAULT_OUTCOME
                      #DRAINING
                      1773561600000000000
                    )

                    // Row 2 (Union Branch Overflow):
                    (
                      #Some #Right #99999999999999999999 443
                      #INIT
                      0
                    )
                  ]
                }
                """;
            var expectedCoreSource = """
                {
                  :meta {
                    #name "gauntlet_seams"
                    #domain "core_test"
                    #kind #BODY_INCLUDE
                  }
                  :defs {
                    :use [
                      :org/stvnadore/prelude {
                        :Port :Port
                        :Uint64 :Uint64
                      }
                    ]
                    :NodeState :Enum[#INIT #RUNNING #DRAINING]
                    :Outcome :Option(:Either(:String :Union(:Port :Uint64)))
                    #DEFAULT_OUTCOME :Outcome #Some #Right #1 8080
                  }
                  :type :Seq(:Tuple(:Outcome :NodeState :Uint64))
                  :body [
                    // Row 0 (100% Valid):
                    (
                      #Some #Right #1 8080
                      #RUNNING
                      18446744073709551615
                    )

                    // Row 1 (Fault Isolation & Constant Barrier):
                    (
                      #DEFAULT_OUTCOME
                      #DRAINING
                      1773561600000000000
                    )

                    // Row 2 (Union Branch Overflow):
                    (
                      #Some #Right #99999999999999999999 443
                      #INIT
                      0
                    )
                  ]
                }
                """;
            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_SOURCE_FORMATTER;
            var threeTuplesCoreFile = myFixture.configureByText("gauntlet_seams.core_test.stvn_i", unformattedThreeTuplesGauntlet);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(threeTuplesCoreFile);
            });
            myFixture.checkResult(expectedCoreSource);

            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING;
            var threeTuplesPsiFile = myFixture.configureByText("gauntlet_seams.core_test.stvn_i", unformattedThreeTuplesGauntlet);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(threeTuplesPsiFile);
            });
            myFixture.checkResult(expectedCoreSource);
        } finally {
            settings.getState().reformatDelegationMode = previousMode;
        }
    }

    public void testMultilineMapEntryNestedCollectionIndentation() {
        var unformatted = """
            {
              :type :Map(:String :Seq(:String))
              :body {
                [ "features"
                [ "auth" "logging" ]
                ]
              }
            }
            """;
        var psiFile = myFixture.configureByText("format_map_nested_list.stvn", unformatted);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Nested list payload on dedicated line must indent by 6 spaces (+2 relative to '['):\n" + text,
            text.contains("    [\"features\"\n      [\"auth\" \"logging\"]\n    ]"));
        assertFalse("Nested list must not align flush with entry bracket:\n" + text,
            text.contains("    [\"features\"\n    [\"auth\""));

        // Mathematical AST equivalence verification
        var docOriginal = StvnCompiler.compile(unformatted).document().orElseThrow();
        var docFormatted = StvnCompiler.compile(text).document().orElseThrow();
        assertEquals("Round-trip AST must be mathematically equivalent",
            docOriginal.requirePayload(), docFormatted.requirePayload());
    }

    public void testMultilineTupleSchemaAndEnumSubsetCanonical200Formatting() {
        var unformatted = """
            {
              :defs {
                :Role :Enum [ #ADMIN #USER #GUEST ]
                :ActiveRole { #filterIncl [ #ADMIN #USER ] } :Role
                :DateTimes :Tuple (
                {#offset}:DateTime
                {#zoned}:DateTime
                {#audited}:DateTime
                )
              }
              :type { #maxSize 4096 } :Seq(:DateTimes)
              :body []
            }
            """;
        var psiFile = myFixture.configureByText("tuple_schema_indent.stvn", unformatted);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
        });

        var text = psiFile.getText();
        assertTrue("Enum definition must use STVN 2.0.0 zero interior bracket spaces:\n" + text,
            text.contains(":Role :Enum[#ADMIN #USER #GUEST]"));
        assertTrue("Subset filter variant list must use single space after keyword and zero interior bracket spaces:\n" + text,
            text.contains(":ActiveRole { #filterIncl [#ADMIN #USER] } :Role"));
        assertTrue("Multiline :Tuple schema children must indent by +2 spaces (column 6) with canonical '{ #mode } :DateTime' spacing:\n" + text,
            text.contains("""
                    :DateTimes :Tuple(
                      { #offset } :DateTime
                      { #zoned } :DateTime
                      { #audited } :DateTime
                    )
                """.stripTrailing()));
    }

    public void testConfigurableCorePrettyDelegationModeAndCommentLosslessFallback() {
        var settings = StvnSettings.getInstance(getProject());
        var previousMode = settings.getState().reformatDelegationMode;
        try {
            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_PRETTY_WHEN_LOSSLESS;

            var compactClean = "{:meta{#name \"demo\" #kind #BODY}:type :Tuple(:Boolean :Option(:Int)):body(#T #S 42)}\n";
            var cleanFile = myFixture.configureByText("demo.stvn", compactClean);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(cleanFile);
            });
            var cleanFormatted = cleanFile.getText();
            assertTrue("Lossless Core delegation must expand short-form booleans via AstPrettyPrinter:\n" + cleanFormatted,
                cleanFormatted.contains("#TRUE"));

            var commented = """
                {
                  // Keep this comment intact in lossless mode
                  :type :Boolean
                  :body #T
                }
                """;
            var commentedFile = myFixture.configureByText("commented_lossless.stvn", commented);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(commentedFile);
            });
            var commentedFormatted = commentedFile.getText();
            assertTrue("Lossless mode must fall back to PSI formatter and preserve comments:\n" + commentedFormatted,
                commentedFormatted.contains("// Keep this comment intact in lossless mode"));
            assertTrue("PSI fallback must retain authored #T token when comments are present:\n" + commentedFormatted,
                commentedFormatted.contains(":body #T"));
        } finally {
            settings.getState().reformatDelegationMode = previousMode;
        }
    }

    public void testSharedTypesTrailingSameLineCommentPreservedInBothFormatterModes() {
        var settings = StvnSettings.getInstance(getProject());
        var previousMode = settings.getState().reformatDelegationMode;
        var input = """
            {
              :meta {
                #name "shared_types"
                #domain "core_test"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :package :com/acme/gauntlet/common {
                  #DEFAULT_PORT { #unsigned #size 16 #minIncl 1 } :ServicePort 8080 // Why is this comment is being wrapped
                  :ServicePort { #unsigned #size 16 #minIncl 1 } :Int
                }
              }
            }
            """;
        var expectedLine = "      #DEFAULT_PORT { #unsigned #size 16 #minIncl 1 } :ServicePort 8080 // Why is this comment is being wrapped";
        try {
            // 1. Verify PSI_STRUCTURE_PRESERVING mode keeps trailing comment on the same line
            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING;
            var psiFile = myFixture.configureByText("shared_types_psi.core_test.stvn_di", input);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(psiFile);
            });
            var psiFormatted = psiFile.getText();
            assertTrue("PSI_STRUCTURE_PRESERVING mode must keep same-line trailing comment on the same line:\n" + psiFormatted,
                psiFormatted.contains(expectedLine));

            // 2. Verify CORE_SOURCE_FORMATTER mode keeps trailing comment on the same line
            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_SOURCE_FORMATTER;
            var coreFile = myFixture.configureByText("shared_types_core.core_test.stvn_di", input);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(coreFile);
            });
            var coreFormatted = coreFile.getText();
            assertTrue("CORE_SOURCE_FORMATTER mode must keep same-line trailing comment on the same line:\n" + coreFormatted,
                coreFormatted.contains(expectedLine));
        } finally {
            settings.getState().reformatDelegationMode = previousMode;
        }
    }

    /**
     * Verifies STVN_LANGUAGE_SPEC.md § 3.3.1 on {@code use_nested.json.stvn_i}:
     * collapsing {@code :prelude  {} (2 spaces) to {@code :prelude {} (1 space) expands post-brace
     * padding from 18 spaces to 19 spaces so {@code //} stays aligned at 0-based column 54
     * in both {@code CORE_SOURCE_FORMATTER} and {@code PSI_STRUCTURE_PRESERVING} modes.
     */
    public void testUseNestedAlignedTrailingCommentsPreservedInBothDelegationModes() {
        var settings = StvnSettings.getInstance(getProject());
        var previousMode = settings.getState().reformatDelegationMode;
        var input = """
            {
              :meta {
                #name "use_nested"
                #domain "json"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :use [ :org/stvnadore/prelude/meta { :StvnPosixId64 :StvnPosixId64 } ]
                :use [ :org/stvnadore/prelude  {                  // <-- Aligned trailing comment
                    :Port :Port                                   // <-- Aligned trailing comment
                    #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX // <-- Aligned trailing comment
                  }                                               // <-- Aligned trailing comment
                ]
              }
            }
            """;
        var expectedAlignedBlock = """
                :use [ :org/stvnadore/prelude {                   // <-- Aligned trailing comment
                    :Port :Port                                   // <-- Aligned trailing comment
                    #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX // <-- Aligned trailing comment
                  }                                               // <-- Aligned trailing comment
                ]
            """;
        try {
            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_SOURCE_FORMATTER;
            var coreFile = myFixture.configureByText("use_nested.json.stvn_i", input);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(coreFile);
            });
            var coreFormatted = coreFile.getText();
            assertTrue("CORE_SOURCE_FORMATTER must keep Line 9 '//' aligned at column 54 (19 spaces after '{'):\n" + coreFormatted,
                coreFormatted.contains(expectedAlignedBlock));

            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING;
            var psiFile = myFixture.configureByText("use_nested_psi.json.stvn_i", input);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(psiFile);
            });
            var psiFormatted = psiFile.getText();
            assertTrue("PSI_STRUCTURE_PRESERVING must keep Line 9 '//' aligned at column 54 (19 spaces after '{'):\n" + psiFormatted,
                psiFormatted.contains(expectedAlignedBlock));
        } finally {
            settings.getState().reformatDelegationMode = previousMode;
        }
    }
}
