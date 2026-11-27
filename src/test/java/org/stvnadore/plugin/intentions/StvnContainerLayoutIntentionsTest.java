package org.stvnadore.plugin.intentions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.printer.NestedContainerWrapPolicy;
import org.stvnadore.plugin.settings.StvnSettings;

import java.util.List;

/**
 * IntelliJ Platform test suite verifying {@link StvnExpandContainerIntention},
 * {@link StvnCollapseContainerIntention}, and {@link StvnCollapseChildElementsIntention}
 * across schema and payload containers, safety guards, {@code Alt+Enter} coexistence,
 * {@code IntentionPreviewInfo.DIFF} previews, and {@code Ctrl+Alt+L} fixed-point stability.
 */
@NullMarked
public final class StvnContainerLayoutIntentionsTest extends BasePlatformTestCase {

    public void testExpandMapLiteralFromCaretInsideBracketedEntryAndPreviewDiff() {
        String initial = """
            {
              :type :Map(:String :Int)
              :body {["zone<caret>_a" 120] ["zone_b" 145]}
            }
            """;
        myFixture.configureByText("expand_map.stvn", initial);

        IntentionAction action = myFixture.findSingleIntention(StvnExpandContainerIntention.TEXT);
        String expected = """
            {
              :type :Map(:String :Int)
              :body {
                ["zone_a" 120]
                ["zone_b" 145]
              }
            }
            """;
        assertEquals("IntentionPreviewInfo.DIFF must match expanded MapLiteral",
            expected, myFixture.getIntentionPreviewText(action));

        myFixture.launchAction(action);
        myFixture.checkResult(expected);

        var file = myFixture.getFile();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(file);
        });
        assertEquals("Expanded MapLiteral must remain a fixed point under Ctrl+Alt+L", expected, file.getText());
    }

    public void testExpandSchemaContainersAndRejectEmptyOrBrokenContainers() {
        myFixture.configureByText("expand_schema.stvn_d", """
            {
              :defs {
                :Status :Enum[#IDLE<caret> #ACTIVE]
                :Pair :Tuple(:Int :String)
                :Constrained { #unsigned #size 16 } :Int
              }
            }
            """);
        IntentionAction expandEnum = myFixture.findSingleIntention(StvnExpandContainerIntention.TEXT);
        myFixture.launchAction(expandEnum);
        myFixture.checkResult("""
            {
              :defs {
                :Status :Enum[
                  #IDLE
                  #ACTIVE
                ]
                :Pair :Tuple(:Int :String)
                :Constrained { #unsigned #size 16 } :Int
              }
            }
            """);

        // Empty list literal must not offer Expand
        myFixture.configureByText("empty_list.stvn", """
            {
              :type :Seq(:Int)
              :body [<caret>]
            }
            """);
        assertTrue(
            "Empty container must not offer Expand",
            myFixture.filterAvailableIntentions(StvnExpandContainerIntention.TEXT).isEmpty()
        );

        // Broken syntax container must not offer Expand
        myFixture.configureByText("broken_tuple.stvn", """
            {
              :type :Tuple(:Int :Int)
              :body (1 <caret>
            }
            """);
        assertTrue(
            "Container with syntax error must not offer Expand",
            myFixture.filterAvailableIntentions(StvnExpandContainerIntention.TEXT).isEmpty()
        );
    }

    public void testCollapseContainerSplitMapEntryAndFixedPointUnderPreserveAuthored() {
        var settings = StvnSettings.getInstance(getProject());
        var prevSchema = settings.getState().schemaNestedWrapPolicy;
        var prevPayload = settings.getState().payloadNestedWrapPolicy;
        try {
            settings.getState().schemaNestedWrapPolicy = NestedContainerWrapPolicy.PRESERVE_AUTHORED;
            settings.getState().payloadNestedWrapPolicy = NestedContainerWrapPolicy.PRESERVE_AUTHORED;

            // 1. Caret inside a split [key \n val] map entry collapses the split entry first
            myFixture.configureByText("split_map_entry.stvn", """
                {
                  :type :Map(:String :Int)
                  :body {
                    ["zone_a"
                      <caret>120
                    ]
                    ["zone_b" 145]
                  }
                }
                """);
            IntentionAction collapseSlice = myFixture.findSingleIntention(StvnCollapseMapEntryIntention.TEXT);
            myFixture.launchAction(collapseSlice);
            myFixture.checkResult("""
                {
                  :type :Map(:String :Int)
                  :body {
                    ["zone_a" 120]
                    ["zone_b" 145]
                  }
                }
                """);

            // 2. Caret now inside single-line ["zone_a" 120] climbs to collapse the enclosing MapLiteral
            myFixture.getEditor().getCaretModel().moveToOffset(
                myFixture.getFile().getText().indexOf("\"zone_a\"") + 2
            );
            IntentionAction collapseMap = myFixture.findSingleIntention(StvnCollapseContainerIntention.TEXT);
            String expectedSingleLineMap = """
                {
                  :type :Map(:String :Int)
                  :body {["zone_a" 120] ["zone_b" 145]}
                }
                """;
            assertEquals(expectedSingleLineMap, myFixture.getIntentionPreviewText(collapseMap));
            myFixture.launchAction(collapseMap);
            myFixture.checkResult(expectedSingleLineMap);

            var file = myFixture.getFile();
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(file);
            });
            assertEquals("Collapsed MapLiteral must stay fixed under Ctrl+Alt+L with PRESERVE_AUTHORED",
                expectedSingleLineMap, file.getText());
        } finally {
            settings.getState().schemaNestedWrapPolicy = prevSchema;
            settings.getState().payloadNestedWrapPolicy = prevPayload;
        }
    }

    public void testCollapseSafetyGuardsRejectCommentsFencedStringsAndErrors() {
        // 1. Line comment inside container blocks Collapse
        myFixture.configureByText("comment_guard.stvn", """
            {
              :type :Tuple(:Int :Int)
              :body <caret>(
                1 // inline comment
                2
              )
            }
            """);
        assertTrue(
            "Container with interior comment must reject Collapse",
            myFixture.filterAvailableIntentions(StvnCollapseContainerIntention.TEXT).isEmpty()
        );

        // 2. Rule STR-04 fenced string inside container blocks Collapse
        myFixture.configureByText("fenced_guard.stvn", """
            {
              :type :Tuple(:Int :String)
              :body <caret>(
                1
                \"\"\"[RAW]
              multi-line text
              [RAW]\"\"\"
              )
            }
            """);
        assertTrue(
            "Container with fenced string must reject Collapse",
            myFixture.filterAvailableIntentions(StvnCollapseContainerIntention.TEXT).isEmpty()
        );
    }

    public void testAltEnterCoexistenceAndCollapseChildElementsPreservesCommentsAndSkipsPackages() {
        // 1. Coexistence on comment-free outer list of multi-line tuples: both actions offered cleanly
        String commentFreeNested = """
            {
              :type :Seq(:Tuple(:String :Int :Seq(:Float)))
              :body <caret>[
                (
                  "RK-01"
                  7
                  [
                    0.5
                    0.75
                  ]
                )
                (
                  "RK-02"
                  9
                  [1.0]
                )
              ]
            }
            """;
        myFixture.configureByText("coexist.stvn", commentFreeNested);
        List<IntentionAction> collapseAllMatches = myFixture.filterAvailableIntentions(StvnCollapseContainerIntention.TEXT);
        List<IntentionAction> collapseChildMatches = myFixture.filterAvailableIntentions(StvnCollapseChildElementsIntention.TEXT);
        assertEquals("Exactly 1 Collapse container intention must appear", 1, collapseAllMatches.size());
        assertEquals("Exactly 1 Collapse child elements intention must appear", 1, collapseChildMatches.size());

        String expectedChildrenCollapsed = """
            {
              :type :Seq(:Tuple(:String :Int :Seq(:Float)))
              :body [
                ("RK-01" 7 [0.5 0.75])
                ("RK-02" 9 [1.0])
              ]
            }
            """;
        assertEquals(expectedChildrenCollapsed, myFixture.getIntentionPreviewText(collapseChildMatches.get(0)));
        myFixture.launchAction(collapseChildMatches.get(0));
        myFixture.checkResult(expectedChildrenCollapsed);

        // 2. Standalone comments between rows and comment-bearing sibling children stay untouched; :package in :defs is skipped
        myFixture.configureByText("defs_children.stvn_d", """
            {
              :defs <caret>{
                :package :telemetry/core {
                  :Gain {
                    #size 32
                  } :Float
                }
                // Standalone header comment
                :CleanPair :Tuple(
                  :Int
                  :Boolean
                )
                :CommentedPair :Tuple(
                  :Int // keep multi-line
                  :Boolean
                )
              }
            }
            """);
        IntentionAction defsCollapseChildren = myFixture.findSingleIntention(StvnCollapseChildElementsIntention.TEXT);
        myFixture.launchAction(defsCollapseChildren);
        myFixture.checkResult("""
            {
              :defs {
                :package :telemetry/core {
                  :Gain {
                    #size 32
                  } :Float
                }
                // Standalone header comment
                :CleanPair :Tuple(:Int :Boolean)
                :CommentedPair :Tuple(
                  :Int // keep multi-line
                  :Boolean
                )
              }
            }
            """);
    }
}
