package org.stvnadore.plugin.intentions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.IntentionActionDelegate;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.plugin.actions.StvnCreateFencedStringIntention;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.validation.ConvertToFencedStringIntentionAction;

import java.util.List;

/**
 * IntelliJ Platform integration tests verifying forward fencing mutual exclusion, {@code 1:1}
 * {@code getText().equals(getFamilyName())} parity, multi-hop and implicit-sum {@code :DateTime}
 * fencing guards, caret-proximity {@link PriorityAction} ordering, symmetric inverse
 * {@link StvnConvertFencedStringToSimpleIntention} round-trip closure, and cross-intention
 * availability inside {@code LITERAL_STRING_FENCED} tokens.
 */
@NullMarked
public final class StvnFencedStringRoundTripAndIntentionPriorityTest extends BasePlatformTestCase {

    private static final String PLAIN_TO_FENCED_TEXT = "Convert string literal to fenced string block";
    private static final String ESCAPED_TO_FENCED_TEXT = "Convert escaped string to fenced string";
    private static final String FENCED_TO_SIMPLE_TEXT = "Convert fenced string to escaped string";
    private static final String ADD_META_TEXT = "Add root :meta header";
    private static final String EXPAND_CONTAINER_TEXT = "Expand container to multiple lines";
    private static final String COLLAPSE_CONTAINER_TEXT = "Collapse container to single line";
    private static final String COLLAPSE_CHILDREN_TEXT = "Collapse child elements to single lines";

    /**
     * Verifies Target 1: strict mutual exclusion between escaped and escape-free forward fencing
     * intentions, {@code 1:1} {@code getText() == getFamilyName()} parity, {@code IntentionPreviewInfo.DIFF}
     * safety, and exclusion of all {@code :DateTime} contexts (direct, multi-hop nominal, {@code UseMapAlias},
     * {@code MetadataEntry} bounds, {@code #CONST}, composite slots, and implicit/explicit sum branches).
     */
    public void testTarget1ForwardFencingMutualExclusionLabelParityAndDateTimeExclusion() {
        var createIntention = new StvnCreateFencedStringIntention();
        var convertEscapedIntention = new StvnConvertEscapedStringToFencedIntention();
        var convertFencedIntention = new StvnConvertFencedStringToSimpleIntention();

        assertEquals(PLAIN_TO_FENCED_TEXT, createIntention.getText());
        assertEquals(PLAIN_TO_FENCED_TEXT, createIntention.getFamilyName());
        assertEquals(PriorityAction.Priority.TOP, createIntention.getPriority());

        assertEquals(ESCAPED_TO_FENCED_TEXT, convertEscapedIntention.getText());
        assertEquals(ESCAPED_TO_FENCED_TEXT, convertEscapedIntention.getFamilyName());
        assertEquals(PriorityAction.Priority.TOP, convertEscapedIntention.getPriority());

        assertEquals(FENCED_TO_SIMPLE_TEXT, convertFencedIntention.getText());
        assertEquals(FENCED_TO_SIMPLE_TEXT, convertFencedIntention.getFamilyName());
        assertEquals(PriorityAction.Priority.TOP, convertFencedIntention.getPriority());

        String doc = """
            {
              :meta {
                #name   "target1_partition"
                #domain "core"
                #kind   #BODY
              }
              :defs {
                :package :time/pkg {
                  :PkgOffsetDt { #offset } :DateTime
                }
                :use [
                  :time/pkg {
                    :PkgOffsetDt :ImportedDt
                  }
                ]
                :ParentZonedDt { #zoned } :DateTime
                :ChildZonedDt  { #minIncl "2026-01-01T00:00:00[UTC]" #maxExcl "2027-01-01T00:00:00[UTC]" } :ParentZonedDt
                #CONST_DT      :ImportedDt "2026-06-01T12:00:00Z"
                #PLAIN_SQL     :String "SELECT * FROM nodes"
                #SLASH_URL     :String "https:\\/\\/example.com"
                #ESC_EMOJI     :String "\\uD83D\\uDE80"
                #PLAIN_EMOJI   :String "\uD83D\uDE80"
                :PayloadType   :Tuple(
                  :String
                  :String
                  :ChildZonedDt
                  :Option(:ImportedDt)
                  :Either(:Int :ChildZonedDt)
                  :Union(:Int :ImportedDt)
                )
              }
              :type :PayloadType
              :body (
                "^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\\\.?\\\\b){4}$"
                "plain_payload_token"
                "2026-08-18T18:00:00[UTC]"
                "2026-08-18T18:00:00Z"
                "2026-09-01T00:00:00[UTC]"
                "2026-10-01T00:00:00Z"
              )
            }
            """;

        PsiFile psiFile = myFixture.configureByText("target1_partition.core.stvn", doc);
        String text = psiFile.getText();

        // 1. Escaped literals -> ONLY Convert escaped string to fenced string (interior + trailing quote)
        int[] escapedOffsets = new int[]{
            text.indexOf("^((25[0-5]") + 4,
            text.indexOf("{4}$\"") + "{4}$\"".length(),
            text.indexOf("https:\\/\\/") + 3,
            text.indexOf("\\uD83D\\uDE80") + 2
        };
        for (int offset : escapedOffsets) {
            myFixture.getEditor().getCaretModel().moveToOffset(offset);
            List<IntentionAction> escapedMatches = myFixture.filterAvailableIntentions(ESCAPED_TO_FENCED_TEXT);
            List<IntentionAction> plainMatches = myFixture.filterAvailableIntentions(PLAIN_TO_FENCED_TEXT);
            assertEquals("Expected 1 escaped-to-fenced intention at offset " + offset, 1, escapedMatches.size());
            assertEquals("Expected 0 plain-to-fenced intentions at offset " + offset, 0, plainMatches.size());
            String preview = myFixture.getIntentionPreviewText(escapedMatches.get(0));
            assertNotNull("Expected non-null DIFF preview for escaped-to-fenced intention", preview);
            assertTrue("Preview must contain Rule STR-04 opening fence", preview.contains("\"\"\"["));
        }

        // 2. Escape-free literals -> ONLY Convert string literal to fenced string block (interior + trailing quote)
        int[] plainOffsets = new int[]{
            text.indexOf("SELECT * FROM nodes") + 3,
            text.indexOf("plain_payload_token") + 3,
            text.indexOf("plain_payload_token\"") + "plain_payload_token\"".length(),
            text.indexOf("\"\uD83D\uDE80\"") + 1
        };
        for (int offset : plainOffsets) {
            myFixture.getEditor().getCaretModel().moveToOffset(offset);
            List<IntentionAction> plainMatches = myFixture.filterAvailableIntentions(PLAIN_TO_FENCED_TEXT);
            List<IntentionAction> escapedMatches = myFixture.filterAvailableIntentions(ESCAPED_TO_FENCED_TEXT);
            assertEquals("Expected 1 plain-to-fenced intention at offset " + offset, 1, plainMatches.size());
            assertEquals("Expected 0 escaped-to-fenced intentions at offset " + offset, 0, escapedMatches.size());
            String preview = myFixture.getIntentionPreviewText(plainMatches.get(0));
            assertNotNull("Expected non-null DIFF preview for plain-to-fenced intention", preview);
            assertTrue("Preview must contain Rule STR-04 opening fence", preview.contains("\"\"\"[RAW]"));
        }

        // 3. All :DateTime contexts (bounds, #CONST, multi-hop nominal, UseMapAlias, implicit Option/Either/Union) -> 0
        int[] dateTimeOffsets = new int[]{
            text.indexOf("2026-01-01T00:00:00[UTC]") + 4,
            text.indexOf("2027-01-01T00:00:00[UTC]") + 4,
            text.indexOf("2026-06-01T12:00:00Z") + 4,
            text.indexOf("2026-08-18T18:00:00[UTC]") + 4,
            text.indexOf("2026-08-18T18:00:00Z") + 4,
            text.indexOf("2026-09-01T00:00:00[UTC]") + 4,
            text.indexOf("2026-10-01T00:00:00Z") + 4
        };
        for (int offset : dateTimeOffsets) {
            myFixture.getEditor().getCaretModel().moveToOffset(offset);
            assertTrue(
                "Plain fencing intention must be suppressed on :DateTime at offset " + offset,
                myFixture.filterAvailableIntentions(PLAIN_TO_FENCED_TEXT).isEmpty()
            );
            assertTrue(
                "Escaped fencing intention must be suppressed on :DateTime at offset " + offset,
                myFixture.filterAvailableIntentions(ESCAPED_TO_FENCED_TEXT).isEmpty()
            );
        }

        // 4. Case 2 incomplete triple-quote opener preview and conversion
        myFixture.configureByText("incomplete_opener.stvn", "{\n  :type :String\n  :body \"\"\"<caret>\n}\n");
        IntentionAction recoveryAction = myFixture.findSingleIntention(PLAIN_TO_FENCED_TEXT);
        assertTrue(myFixture.filterAvailableIntentions(ESCAPED_TO_FENCED_TEXT).isEmpty());
        String recoveryPreview = myFixture.getIntentionPreviewText(recoveryAction);
        assertNotNull(recoveryPreview);
        assertTrue(recoveryPreview.contains("\"\"\"[FENCE]"));
    }

    /**
     * Verifies Target 2: caret-proximity {@link PriorityAction} ordering ensures leaf string
     * conversion intentions ({@code Priority.TOP}) always rank #1 ahead of enclosing container
     * collapse/expand intentions ({@code Priority.LOW} inside leaf tokens, {@code Priority.NORMAL}
     * on container delimiters and container whitespace).
     */
    public void testTarget2CaretProximityPriorityOrdersLeafStringIntentionsFirst() {
        String mapDoc = """
            {
              :meta {
                #name   "priority_order"
                #domain "core"
                #kind   #BODY
              }
              :defs {
                :use [ :org/stvnadore/prelude { #strip } ]
              }
              :type :Map(:PosixId :String)
              :body {
                [
                  "regex"
                  "^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\\\.?\\\\b){4}$"
                ]
              }
            }
            """;

        PsiFile file = myFixture.configureByText("priority_order.core.stvn", mapDoc);

        // 1. Caret inside escaped IPv4 regex string -> Convert escaped string to fenced string is #1
        int ipv4Offset = file.getText().indexOf("^((25[0-5]") + 5;
        myFixture.getEditor().getCaretModel().moveToOffset(ipv4Offset);
        List<IntentionAction> atEscaped = myFixture.getAvailableIntentions();
        List<String> atEscapedTexts = atEscaped.stream().map(IntentionAction::getText).toList();
        int escIdx = atEscapedTexts.indexOf(ESCAPED_TO_FENCED_TEXT);
        int collapseIdx = atEscapedTexts.indexOf(COLLAPSE_CONTAINER_TEXT);
        int collapseEntryIdx = atEscapedTexts.indexOf(StvnCollapseMapEntryIntention.TEXT);
        int collapseAllEntriesIdx = atEscapedTexts.indexOf(StvnCollapseAllMapEntriesIntention.TEXT);
        assertEquals("String conversion intention must rank #1 at index 0: " + atEscapedTexts, 0, escIdx);
        assertTrue("Expected Collapse container to single line to be present", collapseIdx > escIdx);
        assertTrue("Expected Collapse map entry to single line to be present", collapseEntryIdx > escIdx);
        assertTrue("Expected Collapse all map entries to single lines to be present", collapseAllEntriesIdx > escIdx);
        assertEquals(PriorityAction.Priority.LOW, unwrapPriority(atEscaped.get(collapseIdx)));
        assertEquals(PriorityAction.Priority.LOW, unwrapPriority(atEscaped.get(collapseEntryIdx)));
        assertEquals(PriorityAction.Priority.LOW, unwrapPriority(atEscaped.get(collapseAllEntriesIdx)));

        // 2. Caret inside escape-free "regex" key -> Convert string literal to fenced string block is #1
        int keyOffset = file.getText().indexOf("\"regex\"") + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(keyOffset);
        List<IntentionAction> atPlain = myFixture.getAvailableIntentions();
        assertEquals(PLAIN_TO_FENCED_TEXT, atPlain.get(0).getText());
        assertFalse(atPlain.stream().map(IntentionAction::getText).toList().contains(ESCAPED_TO_FENCED_TEXT));

        // 3. Caret directly on '[' delimiter of map entry -> single-entry HIGH, whole-map & outer container NORMAL
        int openBrackOffset = file.getText().indexOf("[\n");
        myFixture.getEditor().getCaretModel().moveToOffset(openBrackOffset);
        List<IntentionAction> atDelim = myFixture.getAvailableIntentions();
        IntentionAction delimCollapseEntry = atDelim.stream()
            .filter(a -> StvnCollapseMapEntryIntention.TEXT.equals(a.getText()))
            .findFirst()
            .orElseThrow();
        IntentionAction delimCollapse = atDelim.stream()
            .filter(a -> COLLAPSE_CONTAINER_TEXT.equals(a.getText()))
            .findFirst()
            .orElseThrow();
        IntentionAction delimCollapseAllEntries = atDelim.stream()
            .filter(a -> StvnCollapseAllMapEntriesIntention.TEXT.equals(a.getText()))
            .findFirst()
            .orElseThrow();
        assertEquals(PriorityAction.Priority.HIGH, unwrapPriority(delimCollapseEntry));
        assertEquals(PriorityAction.Priority.NORMAL, unwrapPriority(delimCollapse));
        assertEquals(PriorityAction.Priority.NORMAL, unwrapPriority(delimCollapseAllEntries));

        // 4. Single-line container expand priority: NORMAL on '(' delimiter vs LOW inside inner string leaf
        String singleLineTupleDoc = """
            {
              :meta {
                #name   "expand_prio"
                #domain "core"
                #kind   #BODY
              }
              :type :Tuple(:String :String)
              :body ( "alpha" "beta\\nline2" )
            }
            """;
        PsiFile tupleFile = myFixture.configureByText("expand_prio.core.stvn", singleLineTupleDoc);
        int insideBeta = tupleFile.getText().indexOf("beta\\n") + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(insideBeta);
        List<IntentionAction> atInnerLeaf = myFixture.getAvailableIntentions();
        assertEquals(ESCAPED_TO_FENCED_TEXT, atInnerLeaf.get(0).getText());
        IntentionAction leafExpand = atInnerLeaf.stream()
            .filter(a -> EXPAND_CONTAINER_TEXT.equals(a.getText()))
            .findFirst()
            .orElseThrow();
        assertEquals(PriorityAction.Priority.LOW, unwrapPriority(leafExpand));

        int onOpenParen = tupleFile.getText().indexOf("( \"alpha\"");
        myFixture.getEditor().getCaretModel().moveToOffset(onOpenParen);
        IntentionAction delimExpand = myFixture.filterAvailableIntentions(EXPAND_CONTAINER_TEXT).get(0);
        assertEquals(PriorityAction.Priority.NORMAL, unwrapPriority(delimExpand));
    }

    /**
     * Verifies Target 3: {@link StvnConvertFencedStringToSimpleIntention} availability, context-sensitive
     * {@code #preserveIndent} decoding (including Spec Section 5.3 item 6 {@code false} on all
     * {@code MetadataEntry} string facets), {@code IntentionPreviewInfo.DIFF} preview, error-token
     * suppression, and exact byte-for-byte round-trip closure on {@code post_1_escape_torture_before.stvn}
     * and {@code post_1_escape_torture_after.stvn}.
     */
    public void testTarget3InverseFencedToSimpleContextsPreserveIndentPreviewAndTortureRoundTrip() {
        // 1. Spec Section 5.3 item 6: MetadataEntry string facets ALWAYS use preserveIndent == false
        String facetAndSumDoc = """
            {
              :meta {
                #name   \"\"\"[META_TAG]
                  fenced_contexts[META_TAG]\"\"\"
                #domain "core"
                #kind   #BODY
              }
              :defs {
                :package :text/pkg {
                  :VerbatimBlock { #maxExcl \"\"\"[BOUND]
                      zzzz[BOUND]\"\"\" #preserveIndent #TRUE } :String
                }
                :use [
                  :text/pkg {
                    :VerbatimBlock :LocalVerbatim
                  }
                ]
                #INDENTED_CONST :LocalVerbatim \"\"\"[RAW]
              exact_two_spaces
            \tsecond_line[RAW]\"\"\"
                :PayloadSchema :Tuple(:LocalVerbatim :Option(:LocalVerbatim) :Either(:Int :String))
              }
              :type :PayloadSchema
              :body (
                \"\"\"[RAW]
              body_preserved[RAW]\"\"\"
                \"\"\"[RAW]
              opt_preserved[RAW]\"\"\"
                \"\"\"[RAW]
                  either_stripped[RAW]\"\"\"
              )
            }
            """;

        PsiFile ctxFile = myFixture.configureByText("fenced_contexts.core.stvn", facetAndSumDoc);

        // Verify ConvertToFencedStringIntentionAction.resolveEffectivePreserveIndent & evaluator on #maxExcl
        int maxExclOffset = ctxFile.getText().indexOf("zzzz[BOUND]");
        assertFalse(
            "Spec Section 5.3 item 6 requires preserveIndent == false on #maxExcl even under #preserveIndent #TRUE",
            StvnPsiLiteralEvaluator.resolvePreserveIndentForStringLiteral(ctxFile.findElementAt(maxExclOffset))
        );
        assertFalse(ConvertToFencedStringIntentionAction.resolveEffectivePreserveIndent(ctxFile, maxExclOffset));

        myFixture.getEditor().getCaretModel().moveToOffset(maxExclOffset);
        IntentionAction boundToSimple = myFixture.findSingleIntention(FENCED_TO_SIMPLE_TEXT);
        String boundPreview = myFixture.getIntentionPreviewText(boundToSimple);
        assertNotNull(boundPreview);
        assertTrue("Preview must decode #maxExcl to stripped \"zzzz\":\n" + boundPreview,
            boundPreview.contains("#maxExcl \"zzzz\""));
        myFixture.launchAction(boundToSimple);
        assertTrue(myFixture.getFile().getText().contains("#maxExcl \"zzzz\""));

        // Convert :meta #name fenced string (at trailing delimiter boundary [META_TAG]"""<caret>)
        int metaFenceEnd = myFixture.getFile().getText().indexOf("[META_TAG]\"\"\"") + "[META_TAG]\"\"\"".length();
        myFixture.getEditor().getCaretModel().moveToOffset(metaFenceEnd);
        myFixture.launchAction(myFixture.findSingleIntention(FENCED_TO_SIMPLE_TEXT));
        assertTrue(myFixture.getFile().getText().contains("#name   \"fenced_contexts\""));

        // Convert #INDENTED_CONST governed by UseMapAlias :LocalVerbatim (#preserveIndent #TRUE)
        int constOffset = myFixture.getFile().getText().indexOf("exact_two_spaces");
        myFixture.getEditor().getCaretModel().moveToOffset(constOffset);
        myFixture.launchAction(myFixture.findSingleIntention(FENCED_TO_SIMPLE_TEXT));
        assertTrue(
            "UseMapAlias #preserveIndent #TRUE constant must retain leading two spaces",
            myFixture.getFile().getText().contains("#INDENTED_CONST :LocalVerbatim \"  exact_two_spaces\\n\\tsecond_line\"")
        );

        // Convert :body tuple fields (direct :LocalVerbatim, implicit :Option(:LocalVerbatim), implicit :Either(:Int :String))
        int bodyPreservedOffset = myFixture.getFile().getText().indexOf("body_preserved");
        myFixture.getEditor().getCaretModel().moveToOffset(bodyPreservedOffset);
        myFixture.launchAction(myFixture.findSingleIntention(FENCED_TO_SIMPLE_TEXT));

        int optPreservedOffset = myFixture.getFile().getText().indexOf("opt_preserved");
        myFixture.getEditor().getCaretModel().moveToOffset(optPreservedOffset);
        myFixture.launchAction(myFixture.findSingleIntention(FENCED_TO_SIMPLE_TEXT));

        int eitherStrippedOffset = myFixture.getFile().getText().indexOf("either_stripped");
        myFixture.getEditor().getCaretModel().moveToOffset(eitherStrippedOffset);
        myFixture.launchAction(myFixture.findSingleIntention(FENCED_TO_SIMPLE_TEXT));

        String convertedCtxText = myFixture.getFile().getText();
        assertTrue(convertedCtxText.contains("\"  body_preserved\""));
        assertTrue(convertedCtxText.contains("\"  opt_preserved\""));
        assertTrue(convertedCtxText.contains("\"either_stripped\""));
        assertFalse(
            "Converted context document must compile with zero errors: "
                + StvnCompiler.compileToResult(convertedCtxText, "fenced_contexts.core.stvn").diagnostics(),
            StvnCompiler.compileToResult(convertedCtxText, "fenced_contexts.core.stvn").hasErrors()
        );

        // 2. Suppression on malformed fences, raw forbidden U+202E (E001), and secondary duplicate root sections
        String malformedAndDupDoc = """
            {
              :type :String
              :body \"\"\"untagged_inline\"\"\"
              :body \"\"\"[RAW]
                duplicate_secondary_section[RAW]\"\"\"
            }
            """;
        PsiFile dupFile = myFixture.configureByText("malformed_and_dup.stvn", malformedAndDupDoc);
        myFixture.getEditor().getCaretModel().moveToOffset(dupFile.getText().indexOf("untagged_inline") + 2);
        assertTrue(myFixture.filterAvailableIntentions(FENCED_TO_SIMPLE_TEXT).isEmpty());
        myFixture.getEditor().getCaretModel().moveToOffset(dupFile.getText().indexOf("duplicate_secondary_section") + 2);
        assertTrue(myFixture.filterAvailableIntentions(FENCED_TO_SIMPLE_TEXT).isEmpty());

        String forbiddenRawFenceDoc = "{\n  :type :String\n  :body \"\"\"[RAW]\n    \u202Ehidden[RAW]\"\"\"\n}\n";
        PsiFile e001File = myFixture.configureByText("forbidden_raw_fence.stvn", forbiddenRawFenceDoc);
        myFixture.getEditor().getCaretModel().moveToOffset(e001File.getText().indexOf("hidden"));
        assertTrue(
            "Pass 1 E001 raw forbidden character fence must suppress inverse intention",
            myFixture.filterAvailableIntentions(FENCED_TO_SIMPLE_TEXT).isEmpty()
        );

        // 3. Exact byte-for-byte round-trip closure on post_1_escape_torture_before.stvn <-> post_1_escape_torture_after.stvn
        String post1EscapeTortureBefore = """
            {
              :meta {
                #name   "post_1_escape_torture"
                #domain "core"
                #kind   #BODY
              }
              :defs {
                :use [ :org/stvnadore/prelude { #strip } ]
              }
              :type :Map(:PosixId :String)
              :body {
                [
                  "regex"
                  "^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\\\.?\\\\b){4}$"
                ]
                [
                  "json"
                  "{\\n  \\"service\\": \\"stvn\\\\core\\",\\n  \\"tabs\\": \\"a\\tb\\"\\n}\\n"
                ]
              }
            }
            """;

        String post1EscapeTortureAfter = """
            {
              :meta {
                #name   "post_1_escape_torture"
                #domain "core"
                #kind   #BODY
              }
              :defs {
                :use [ :org/stvnadore/prelude { #strip } ]
              }
              :type :Map(:PosixId :String)
              :body {
                [
                  "regex"
                  \"\"\"[RAW]
                    ^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\.?\\b){4}$[RAW]\"\"\"
                ]
                [
                  "json"
                  \"\"\"[RAW]
                    {
                      "service": "stvn\\core",
                      "tabs": "a\tb"
                    }
                  [RAW]\"\"\"
                ]
              }
            }
            """;

        assertFalse(StvnCompiler.compileToResult(post1EscapeTortureBefore, "post_1_escape_torture.core.stvn").hasErrors());
        assertFalse(StvnCompiler.compileToResult(post1EscapeTortureAfter, "post_1_escape_torture.core.stvn").hasErrors());

        myFixture.configureByText("post_1_escape_torture.core.stvn", post1EscapeTortureBefore);

        // Forward step: before -> after
        int beforeRegexOffset = myFixture.getFile().getText().indexOf("^((25[0-5]") + 4;
        myFixture.getEditor().getCaretModel().moveToOffset(beforeRegexOffset);
        myFixture.launchAction(myFixture.findSingleIntention(ESCAPED_TO_FENCED_TEXT));

        int beforeJsonOffset = myFixture.getFile().getText().indexOf("{\\n  \\\"service\\\"") + 4;
        myFixture.getEditor().getCaretModel().moveToOffset(beforeJsonOffset);
        myFixture.launchAction(myFixture.findSingleIntention(ESCAPED_TO_FENCED_TEXT));

        assertEquals(
            "Forward escaped-to-fenced conversions must match post_1_escape_torture_after.stvn byte-for-byte",
            post1EscapeTortureAfter,
            myFixture.getEditor().getDocument().getText()
        );

        // Inverse step: after -> before
        int afterRegexOffset = myFixture.getFile().getText().indexOf("^((25[0-5]") + 4;
        myFixture.getEditor().getCaretModel().moveToOffset(afterRegexOffset);
        myFixture.launchAction(myFixture.findSingleIntention(FENCED_TO_SIMPLE_TEXT));

        int afterJsonOffset = myFixture.getFile().getText().indexOf("\"service\":") + 3;
        myFixture.getEditor().getCaretModel().moveToOffset(afterJsonOffset);
        myFixture.launchAction(myFixture.findSingleIntention(FENCED_TO_SIMPLE_TEXT));

        assertEquals(
            "Inverse fenced-to-simple conversions must restore post_1_escape_torture_before.stvn byte-for-byte",
            post1EscapeTortureBefore,
            myFixture.getEditor().getDocument().getText()
        );
    }

    /**
     * Verifies Target 4: inside a valid {@code LITERAL_STRING_FENCED} (including when root {@code :meta}
     * is absent and interior body lines contain {@code :meta} or triple-quote substrings),
     * {@link StvnConvertFencedStringToSimpleIntention} ranks #1 ({@code Priority.TOP}),
     * {@link StvnAddMetaBlockIntention} coexists cleanly at {@code Priority.NORMAL},
     * {@link StvnCreateFencedStringIntention} never misfires on interior triple quotes, and
     * container collapse intentions immediately re-enable once the fenced string is converted back to {@code "..."}.
     */
    public void testTarget4CrossIntentionAuditInsideFencedStringAndImmediateCollapseRestoration() {
        // 1. Missing :meta + interior ":meta {" + blank line + interior triple-quote docstring
        String missingMetaFencedDoc = """
            {
              :type :String
              :body \"\"\"[RAW]
                :meta {
            
                \"\"\"embedded_docstring\"\"\"[RAW]\"\"\"
            }
            """;

        PsiFile file = myFixture.configureByText("missing_meta_fenced.stvn", missingMetaFencedDoc);
        String text = file.getText();
        int[] auditOffsets = new int[]{
            text.indexOf("\"\"\"[RAW]") + 4,
            text.indexOf(":meta {") + 2,
            text.indexOf(":meta {\n") + ":meta {\n".length() + 1,
            text.indexOf("\"\"\"embedded_docstring") + 2,
            text.indexOf("[RAW]\"\"\"") + 3
        };
        for (int offset : auditOffsets) {
            myFixture.getEditor().getCaretModel().moveToOffset(offset);
            List<String> availableTexts = myFixture.getAvailableIntentions().stream()
                .map(IntentionAction::getText)
                .toList();
            assertEquals(
                "Fenced-to-simple intention must rank #1 at offset " + offset + ": " + availableTexts,
                0,
                availableTexts.indexOf(FENCED_TO_SIMPLE_TEXT)
            );
            assertTrue(
                "Add root :meta header must remain available at offset " + offset + ": " + availableTexts,
                availableTexts.contains(ADD_META_TEXT)
            );
            assertFalse(
                "Create fenced string intention must not misfire inside LITERAL_STRING_FENCED at offset " + offset,
                availableTexts.contains(PLAIN_TO_FENCED_TEXT)
            );
        }

        // 2. Immediate re-availability of Collapse container & Collapse child elements after inverse conversion
        String fencedInMapDoc = """
            {
              :meta {
                #name   "collapse_restore"
                #domain "core"
                #kind   #BODY
              }
              :defs {
                :use [ :org/stvnadore/prelude { #strip } ]
              }
              :type :Map(:PosixId :String)
              :body {
                [
                  "regex"
                  \"\"\"[RAW]
                    ^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\.?\\b){4}$[RAW]\"\"\"
                ]
              }
            }
            """;

        PsiFile restoreFile = myFixture.configureByText("collapse_restore.core.stvn", fencedInMapDoc);
        int fencedRegexOffset = restoreFile.getText().indexOf("^((25[0-5]") + 4;
        myFixture.getEditor().getCaretModel().moveToOffset(fencedRegexOffset);

        assertTrue("Collapse container must be blocked while fenced string is present",
            myFixture.filterAvailableIntentions(COLLAPSE_CONTAINER_TEXT).isEmpty());
        assertTrue("Collapse map entry must be blocked while fenced string is present",
            myFixture.filterAvailableIntentions(StvnCollapseMapEntryIntention.TEXT).isEmpty());
        assertTrue("Collapse all map entries must be blocked while fenced string is present",
            myFixture.filterAvailableIntentions(StvnCollapseAllMapEntriesIntention.TEXT).isEmpty());
        assertTrue("Collapse child elements must be blocked while fenced string is present",
            myFixture.filterAvailableIntentions(COLLAPSE_CHILDREN_TEXT).isEmpty());

        // Convert fenced string -> simple escaped string
        myFixture.launchAction(myFixture.findSingleIntention(FENCED_TO_SIMPLE_TEXT));

        // Immediately at the resulting caret position, map-entry & container collapse intentions re-enable (below #1 string action)
        List<String> postConvertTexts = myFixture.getAvailableIntentions().stream()
            .map(IntentionAction::getText)
            .toList();
        assertEquals(0, postConvertTexts.indexOf(ESCAPED_TO_FENCED_TEXT));
        assertTrue("Collapse container must immediately re-enable: " + postConvertTexts,
            postConvertTexts.contains(COLLAPSE_CONTAINER_TEXT));
        assertTrue("Collapse map entry must immediately re-enable: " + postConvertTexts,
            postConvertTexts.contains(StvnCollapseMapEntryIntention.TEXT));
        assertTrue("Collapse all map entries must immediately re-enable: " + postConvertTexts,
            postConvertTexts.contains(StvnCollapseAllMapEntriesIntention.TEXT));

        // Launch Collapse map entry to single line on the [ "regex" "..." ] slice
        myFixture.launchAction(myFixture.findSingleIntention(StvnCollapseMapEntryIntention.TEXT));
        String finalDoc = myFixture.getEditor().getDocument().getText();
        assertTrue(
            "Collapsed map entry must be canonical single-line:\n" + finalDoc,
            finalDoc.contains("[\"regex\" \"^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\\\.?\\\\b){4}$\"]")
        );
        assertFalse(StvnCompiler.compileToResult(finalDoc, "collapse_restore.core.stvn").hasErrors());
    }

    private static PriorityAction.Priority unwrapPriority(IntentionAction action) {
        IntentionAction cur = action;
        while (cur instanceof IntentionActionDelegate delegate) {
            IntentionAction next = delegate.getDelegate();
            if (next == cur) {
                break;
            }
            cur = next;
        }
        return cur instanceof PriorityAction pa ? pa.getPriority() : PriorityAction.Priority.NORMAL;
    }
}
