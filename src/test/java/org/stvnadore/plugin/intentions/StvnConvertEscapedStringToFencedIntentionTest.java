package org.stvnadore.plugin.intentions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.printer.StvnSourceFormatter;

import java.util.List;

/**
 * Platform integration test suite verifying {@link StvnConvertEscapedStringToFencedIntention}
 * availability, soundness guards, fence-tag escalation, sibling declaration isolation, and
 * {@code IntentionPreviewInfo} diff generation.
 */
@NullMarked
public final class StvnConvertEscapedStringToFencedIntentionTest extends BasePlatformTestCase {

    private static final String INTENTION_NAME = "Convert escaped string to fenced string";

    public void testSingleLineMetadataRegexExpandsToCanonicalMultiLineFencedBlockAndPreservesSiblingDeclarations() {
        String scrambledSibling = """
                :ScrambledCommentedStr {
                  // regex comment
                  #regex "^[a-z]+$"
                  // minSize comment
                  #minSize 1
                } :String""".indent(4).stripTrailing();

        var before = """
            {
              :meta {
                #name "fenced_intention_regex"
                #kind #DEFS
              }
              :defs {
                :DomainHost { #minSize 3 #maxSize 128 #regex "^[a-z0-9]+(?:\\\\<caret>.[a-z0-9]+)+$" } :String
            %s
              }
            }
            """.formatted(scrambledSibling);

        myFixture.configureByText("fenced_intention_regex.stvn_d", before);
        List<IntentionAction> actions = myFixture.filterAvailableIntentions(INTENTION_NAME);
        assertEquals("Expected intention on escaped #regex string", 1, actions.size());

        // Verify IntentionPreviewInfo diff preview renders without mutating live buffer
        String previewText = myFixture.getIntentionPreviewText(actions.get(0));
        assertNotNull("Intention preview must produce non-null diff target text", previewText);
        assertTrue("Preview must contain [REGEX] fence:\n" + previewText, previewText.contains("\"\"\"[REGEX]"));
        assertTrue("Live buffer must remain untouched during preview",
            myFixture.getEditor().getDocument().getText().contains("#regex \"^[a-z0-9]+"));

        myFixture.launchAction(actions.get(0));
        String after = myFixture.getFile().getText();

        assertTrue("Converted #regex must use [REGEX] fence:\n" + after,
            after.contains("""
                    :DomainHost {
                      #minSize 3
                      #maxSize 128
                      #regex \"\"\"[REGEX]
                        ^[a-z0-9]+(?:\\.[a-z0-9]+)+$[REGEX]\"\"\"
                    } :String
                """.stripTrailing()));
        assertTrue("Unrelated sibling :ScrambledCommentedStr must remain byte-for-byte untouched:\n" + after,
            after.contains(scrambledSibling));
        var mixedResult = StvnCompiler.compileToResult(after, "fenced_intention_regex.stvn_d");
        assertTrue("Converted :DomainHost must produce zero errors (only intentional ERR_FACET_ORDER_VIOLATION on :ScrambledCommentedStr may remain): " + mixedResult.diagnostics(),
            mixedResult.diagnostics().stream()
                .filter(d -> d.severity().name().equals("ERROR"))
                .allMatch(d -> "ERR_FACET_ORDER_VIOLATION".equals(d.symbolicId().orElse(""))
                    && d.message().contains(":ScrambledCommentedStr")));

        // Verify converted :DomainHost itself compiles cleanly and is a StvnSourceFormatter fixed point when isolated
        var cleanDoc = """
            {
              :meta {
                #name "clean_check"
                #domain "core"
                #kind #DEFS
              }
              :defs {
                :DomainHost {
                  #minSize 3
                  #maxSize 128
                  #regex \"\"\"[REGEX]
                    ^[a-z0-9]+(?:\\.[a-z0-9]+)+$[REGEX]\"\"\"
                } :String
              }
            }
            """;
        assertFalse("Isolated converted :DomainHost must compile with zero errors",
            StvnCompiler.compileToResult(cleanDoc, "clean_check.core.stvn_d").hasErrors());
        assertEquals(cleanDoc.trim(), StvnSourceFormatter.tryFormat(cleanDoc).orElseThrow().trim());
    }

    public void testRegexVsRawTagSelectionCollisionEscalationAndBodyTupleExpansion() {
        var code = """
            {
              :meta {
                #name "tag_and_tuple_test"
                #domain "core"
                #kind #BODY
              }
              :defs {
                :TextBlock { #minSize 1 #maxSize 512 } :String
                :VerbatimBlock { #minSize 1 #maxSize 512 #preserveIndent #TRUE } :String
                #SLUG_REGEX :TextBlock "^[a-z0-9]+(?:\\\\.[a-z0-9]+)*$"
                #COLLIDING_NOTE :TextBlock "contains [RAW]\\\"\\\"\\\" marker\\nand second line"
                :Row :Tuple( :TextBlock :VerbatimBlock )
              }
              :type :Row
              :body ( "alpha\\nbeta" "  leading\\n\\tverbatim" )
            }
            """;

        var psiFile = myFixture.configureByText("tag_and_tuple_test.stvn", code);

        // 1. #SLUG_REGEX -> [REGEX]
        myFixture.getEditor().getCaretModel().moveToOffset(psiFile.getText().indexOf("(?:\\\\.") + 2);
        var slugActions = myFixture.filterAvailableIntentions(INTENTION_NAME);
        assertEquals(1, slugActions.size());
        myFixture.launchAction(slugActions.get(0));
        assertTrue(myFixture.getFile().getText().contains("#SLUG_REGEX :TextBlock \"\"\"[REGEX]\n      ^[a-z0-9]+(?:\\.[a-z0-9]+)*$[REGEX]\"\"\""));

        // 2. #COLLIDING_NOTE containing [RAW]""" -> escalates to [RAW_1]
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("contains [RAW]") + 3);
        var collideActions = myFixture.filterAvailableIntentions(INTENTION_NAME);
        assertEquals(1, collideActions.size());
        myFixture.launchAction(collideActions.get(0));
        assertTrue("Fence tag must escalate to [RAW_1]:\n" + myFixture.getFile().getText(),
            myFixture.getFile().getText().contains("\"\"\"[RAW_1]\n      contains [RAW]\"\"\" marker\n      and second line[RAW_1]\"\"\""));

        // 3. Single-line :body tuple expands cleanly and converts first element to [RAW]
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("alpha\\nbeta") + 2);
        var tupleActions = myFixture.filterAvailableIntentions(INTENTION_NAME);
        assertEquals(1, tupleActions.size());
        myFixture.launchAction(tupleActions.get(0));
        assertTrue(myFixture.getFile().getText().contains("\"\"\"[RAW]\n      alpha\n      beta[RAW]\"\"\""));

        // 4. Second tuple slot carries #preserveIndent #TRUE -> converts with verbatim 0-margin body
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("leading\\n") + 2);
        var verbatimActions = myFixture.filterAvailableIntentions(INTENTION_NAME);
        assertEquals(1, verbatimActions.size());
        myFixture.launchAction(verbatimActions.get(0));
        String finalDoc = myFixture.getFile().getText();
        assertTrue("Verbatim #preserveIndent #TRUE slot must emit 0-margin body:\n" + finalDoc,
            finalDoc.contains("\"\"\"[RAW]\n  leading\n\tverbatim[RAW]\"\"\""));
        assertFalse("Final document must compile cleanly with zero errors:\n" + finalDoc,
            StvnCompiler.compileToResult(finalDoc, "tag_and_tuple_test.stvn").hasErrors());
    }

    public void testSuppressionOnMetaIncludeDateTimePlainAndNonRoundTrippableStrings() {
        var code = """
            {
              :meta {
                #name "suppression_guards"
                #domain "core"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "sub\\\\module.stvn_di" ]
                :AuditTs { #offset #minIncl "2026-01-01T00:00:00Z" } :DateTime
                :CleanStr { #minSize 1 #maxSize 128 } :String
                #PLAIN_STR :CleanStr "plain_ascii_identifier"
                #LEADING_SPACE_DEFAULT :CleanStr "  leading_space\\\\escaped"
                #COMMON_INDENT_MULTILINE :CleanStr "  indented\\n  line"
                #SPACE_ONLY_BLANK_LINE :CleanStr "first\\n   \\nthird"
                #CR_STRING :CleanStr "first\\r\\nsecond"
                #CTRL_STRING :CleanStr "alert\\u0007\\nnext"
              }
              :type :AuditTs
              :body "2026-11-14T22:00:00Z"
            }
            """;

        var file = myFixture.configureByText("suppression_guards.stvn_i", code);
        String text = file.getText();

        int[] suppressedOffsets = new int[]{
            text.indexOf("\"suppression_guards\"") + 2,
            text.indexOf("\"sub\\\\module.stvn_di\"") + 2,
            text.indexOf("\"2026-01-01T00:00:00Z\"") + 2,
            text.indexOf("\"plain_ascii_identifier\"") + 2,
            text.indexOf("\"  leading_space\\\\escaped\"") + 2,
            text.indexOf("\"  indented\\n  line\"") + 2,
            text.indexOf("\"first\\n   \\nthird\"") + 2,
            text.indexOf("\"first\\r\\nsecond\"") + 2,
            text.indexOf("\"alert\\u0007\\nnext\"") + 2,
            text.indexOf("\"2026-11-14T22:00:00Z\"") + 2
        };

        for (int offset : suppressedOffsets) {
            myFixture.getEditor().getCaretModel().moveToOffset(offset);
            var available = myFixture.filterAvailableIntentions(INTENTION_NAME);
            assertTrue("Intention must be suppressed at offset " + offset + " ("
                + file.findElementAt(offset).getText() + ")", available.isEmpty());
        }
    }
}
