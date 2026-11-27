package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.IntentionActionDelegate;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.options.OptCheckbox;
import com.intellij.codeInspection.options.OptNumber;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.printer.NestedContainerWrapPolicy;
import org.stvnadore.plugin.intentions.StvnCollapseAllMapEntriesIntention;
import org.stvnadore.plugin.intentions.StvnCollapseChildElementsIntention;
import org.stvnadore.plugin.intentions.StvnCollapseContainerIntention;
import org.stvnadore.plugin.intentions.StvnCollapseMapEntryIntention;
import org.stvnadore.plugin.intentions.StvnExpandAllMapEntriesIntention;
import org.stvnadore.plugin.intentions.StvnExpandContainerIntention;
import org.stvnadore.plugin.intentions.StvnExpandMapEntryIntention;
import org.stvnadore.plugin.settings.StvnSettings;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Comprehensive IntelliJ Platform regression suite verifying:
 * <ul>
 *   <li>Target 1: 4-way {@code STVN | Governance | Capacity} inspection split ({@code :Map}, {@code :Seq},
 *       {@code :Set}, {@code :String}), {@link OptPane} metadata, {@code PsiElement}-identity homonymous
 *       {@code :package} cycle guards, and {@code PW004} literal overflow quick-fixes.</li>
 *   <li>Target 2: Dedicated per-entry and whole-map 4-slot layout intentions, comment-preserving involution,
 *       clean-vs-mixed map child-collapse deconfliction, and 4-zone {@link PriorityAction} ordering.</li>
 *   <li>Target 3: 4-slot {@code MapEntry} auto-expansion and zero sibling bracket padding when fencing
 *       plain, escaped, nested, cramped, or incomplete-opener strings inside {@code :Map} literals.</li>
 * </ul>
 */
@NullMarked
public final class StvnCapacitySplitAndMapEntryIntentionsTest extends BasePlatformTestCase {

    private static final String PLAIN_TO_FENCED_TEXT = "Convert string literal to fenced string block";
    private static final String ESCAPED_TO_FENCED_TEXT = "Convert escaped string to fenced string";
    private static final String ESCAPE_NON_ASCII_TEXT = "Escape non-ASCII characters as \\uXXXX";

    public void testVector1_CapacityGroupPathTriStateMetadataAndOptPaneControls() {
        var epsByShortName = LocalInspectionEP.LOCAL_INSPECTION.getExtensionList().stream()
            .filter(ep -> "STVN".equals(ep.language))
            .collect(Collectors.toMap(LocalInspectionEP::getShortName, ep -> ep));

        for (String shortName : List.of("StvnMapCapacity", "StvnSeqCapacity", "StvnSetCapacity", "StvnStringCapacity")) {
            LocalInspectionEP ep = epsByShortName.get(shortName);
            assertNotNull("Expected registered LocalInspectionEP for " + shortName, ep);
            assertEquals("STVN,Governance", ep.groupPath);
            assertEquals("Capacity", ep.getGroupDisplayName());
            assertEquals(List.of("STVN", "Governance", "Capacity"), List.of(ep.getGroupPath()));
            assertTrue(ep.enabledByDefault);
            assertEquals("WARNING", ep.level);
        }

        assertOptPaneShape(
            new StvnMapCapacityInspection(),
            "maxCapacityThreshold",
            "Capacity threshold (entries):",
            "inspectBodyAndConstantLiterals",
            "Inspect map literals in body payloads and constants"
        );
        assertOptPaneShape(
            new StvnSeqCapacityInspection(),
            "maxCapacityThreshold",
            "Capacity threshold (elements):",
            "inspectBodyAndConstantLiterals",
            "Inspect sequence literals in body payloads and constants"
        );
        assertOptPaneShape(
            new StvnSetCapacityInspection(),
            "maxCapacityThreshold",
            "Capacity threshold (elements):",
            "inspectBodyAndConstantLiterals",
            "Inspect set literals in body payloads and constants"
        );
        assertOptPaneShape(
            new StvnStringCapacityInspection(),
            "thresholdCapacity",
            "Capacity threshold (characters):",
            "inspectPayload",
            "Inspect string literals in body payloads and constants"
        );
    }

    private static void assertOptPaneShape(
            AbstractStvnCapacityInspection inspection,
            String expectedNumberBindId,
            String expectedNumberLabel,
            String expectedCheckboxBindId,
            String expectedCheckboxLabel
    ) {
        assertEquals(List.of("STVN", "Governance", "Capacity"), List.of(inspection.getGroupPath()));
        assertEquals("Capacity", inspection.getGroupDisplayName());

        OptPane pane = inspection.getOptionsPane();
        assertEquals(2, pane.components().size());
        assertTrue(pane.components().get(0) instanceof OptNumber);
        assertTrue(pane.components().get(1) instanceof OptCheckbox);

        OptNumber num = (OptNumber) pane.components().get(0);
        assertEquals(expectedNumberBindId, num.bindId());
        assertEquals(1, num.minValue());
        assertEquals(16_777_216, num.maxValue());
        assertTrue(num.splitLabel().label().contains(expectedNumberLabel));

        OptCheckbox cb = (OptCheckbox) pane.components().get(1);
        assertEquals(expectedCheckboxBindId, cb.bindId());
        assertEquals(expectedCheckboxLabel, cb.label().label());
    }

    public void testVector2_FourWayOrthogonalityOnNestedMapStringSeqSetString() {
        String source = """
            {
              :meta {
                #name   "nested_orthogonality"
                #domain "capacity"
                #kind   #BODY
              }
              :type :Map(:String :Seq(:Set(:String)))
              :body {["k" [["s"]]]}
            }
            """;

        // 1. Only StvnMapCapacityInspection enabled -> strictly ':Map' ('entries')
        var mapOnly = new StvnMapCapacityInspection();
        myFixture.enableInspections(mapOnly);
        try {
            myFixture.configureByText("nested_orthogonality.capacity.stvn", source);
            var warnings = collectCapacityWarnings();
            assertEquals(1, warnings.size());
            assertEquals(":Map", highlightedText(source, warnings.get(0)));
            assertTrue(warnings.get(0).getDescription().contains("16777216 entries"));
            assertTrue(warnings.get(0).getDescription().contains("PW001-WARN_NOMINAL_COLLECTION_UNADORNED"));
        } finally {
            myFixture.disableInspections(mapOnly);
        }

        // 2. Only StvnSeqCapacityInspection enabled -> strictly ':Seq' ('elements')
        var seqOnly = new StvnSeqCapacityInspection();
        myFixture.enableInspections(seqOnly);
        try {
            myFixture.configureByText("nested_orthogonality.capacity.stvn", source);
            var warnings = collectCapacityWarnings();
            assertEquals(1, warnings.size());
            assertEquals(":Seq", highlightedText(source, warnings.get(0)));
            assertTrue(warnings.get(0).getDescription().contains("16777216 elements"));
        } finally {
            myFixture.disableInspections(seqOnly);
        }

        // 3. Only StvnSetCapacityInspection enabled -> strictly ':Set' ('elements')
        var setOnly = new StvnSetCapacityInspection();
        myFixture.enableInspections(setOnly);
        try {
            myFixture.configureByText("nested_orthogonality.capacity.stvn", source);
            var warnings = collectCapacityWarnings();
            assertEquals(1, warnings.size());
            assertEquals(":Set", highlightedText(source, warnings.get(0)));
            assertTrue(warnings.get(0).getDescription().contains("16777216 elements"));
        } finally {
            myFixture.disableInspections(setOnly);
        }

        // 4. Only StvnStringCapacityInspection enabled -> strictly the two ':String' tokens ('characters')
        var strOnly = new StvnStringCapacityInspection();
        myFixture.enableInspections(strOnly);
        try {
            myFixture.configureByText("nested_orthogonality.capacity.stvn", source);
            var warnings = collectCapacityWarnings();
            assertEquals(2, warnings.size());
            assertEquals(":String", highlightedText(source, warnings.get(0)));
            assertEquals(":String", highlightedText(source, warnings.get(1)));
            assertTrue(warnings.get(0).getDescription().contains("16777216 characters"));
            assertTrue(warnings.get(0).getDescription().contains("PW002-WARN_NOMINAL_STRING_UNADORNED"));
        } finally {
            myFixture.disableInspections(strOnly);
        }
    }

    public void testVector3_NominalLineageHomonymousPackagesConstMaxSizeAndErrorSeverity() {
        var mapInsp = new StvnMapCapacityInspection();
        mapInsp.maxCapacityThreshold = 8;
        mapInsp.configuredSeverity = "ERROR";

        var seqInsp = new StvnSeqCapacityInspection();
        seqInsp.maxCapacityThreshold = 16;

        var setInsp = new StvnSetCapacityInspection();
        setInsp.maxCapacityThreshold = 32;

        var strInsp = new StvnStringCapacityInspection();
        strInsp.thresholdCapacity = 64;

        myFixture.enableInspections(mapInsp, seqInsp, setInsp, strInsp);
        try {
            String source = """
                {
                  :meta {
                    #name   "homonymous_pkg"
                    #domain "capacity"
                    #kind   #BODY
                  }
                  :defs {
                    #MAP_CAP :Int 12
                    :package :alpha {
                      :Item { #maxSize #MAP_CAP } :Map({ #maxSize 32 } :String :Int)
                    }
                    :package :beta {
                      :use [ :alpha { :Item :UpstreamItem } ]
                      // Homonymous ':Item' in :beta derives from :alpha/:Item without false cycle abort
                      :Item :UpstreamItem
                    }
                    :use [ :beta { :Item :LocalMap } ]
                    :TieredMap { #invertible } :Map({ #maxSize 32 } :String :Int)
                  }
                  :type :LocalMap
                  :body {
                    ["a" 1] ["b" 2] ["c" 3] ["d" 4] ["e" 5]
                    ["f" 6] ["g" 7] ["h" 8] ["i" 9]
                  }
                }
                """;
            mapInsp.inspectBodyAndConstantLiterals = true;
            PsiFile file = myFixture.configureByText("homonymous_pkg.capacity.stvn", source);
            var warnings = collectCapacityWarnings();

            // 1. :alpha/:Item has #maxSize 12 > threshold 8 ('PW003'), :beta/:Item is derived alias (0 E040-inducing warnings),
            //    :TieredMap is unadorned ('PW001'), and :body has 9 entries > threshold 8 ('PW004' via homonymous lineage!)
            assertTrue("Expected PW003 on :alpha/:Item (#maxSize #MAP_CAP = 12 > 8): " + warnings,
                warnings.stream().anyMatch(h -> h.getDescription().contains("specifies capacity 12, exceeding configured threshold of 8 entries")));
            assertTrue("Expected PW004 on :body MapLiteral traced across homonymous :beta/:Item -> :alpha/:Item: " + warnings,
                warnings.stream().anyMatch(h -> h.getDescription().contains("Map literal entry count (9) exceeds configured capacity threshold (8 entries)")));

            // 2. Under ERROR severity on StvnMapCapacityInspection, 16777216 fixes are suppressed on :TieredMap
            myFixture.getEditor().getCaretModel().moveToOffset(file.getText().indexOf(":TieredMap"));
            myFixture.doHighlighting();
            assertEquals(1, myFixture.filterAvailableIntentions("Set nominal capacity to 8").size());
            assertTrue("16777216 default cap fix must be suppressed under ERROR severity",
                myFixture.filterAvailableIntentions("Set nominal capacity to 16777216 (default allocation cap)").isEmpty());

            // 3. Launching 'Set nominal capacity to 1..8 (non-empty)' inserts #minSize/#maxSize AFTER Tier-1.1 #invertible?
            //    Verify StvnMetadataFacetInsertionUtil places capacity facets in valid STVN 2.0.0 5-tier order!
            myFixture.launchAction(myFixture.findSingleIntention("Set nominal capacity to 1..8 (non-empty)"));
            assertFalse("5-tier insertion must compile with 0 errors:\n" + myFixture.getFile().getText(),
                StvnCompiler.compileToResult(myFixture.getFile().getText(), "homonymous_pkg.capacity.stvn").hasErrors());
        } finally {
            myFixture.disableInspections(mapInsp, seqInsp, setInsp, strInsp);
        }
    }

    public void testVector4_BodyAndConstantLiteralCapacityInspectionAndQuickFixesAcrossAllFourKinds() {
        var mapInsp = new StvnMapCapacityInspection();
        mapInsp.maxCapacityThreshold = 2;
        mapInsp.inspectBodyAndConstantLiterals = true;

        var seqInsp = new StvnSeqCapacityInspection();
        seqInsp.maxCapacityThreshold = 2;
        seqInsp.inspectBodyAndConstantLiterals = true;

        var setInsp = new StvnSetCapacityInspection();
        setInsp.maxCapacityThreshold = 2;
        setInsp.inspectBodyAndConstantLiterals = true;

        var strInsp = new StvnStringCapacityInspection();
        strInsp.thresholdCapacity = 4;
        strInsp.inspectPayload = true;

        // 1. Disambiguate :Seq ListLiteral vs :Set ListLiteral when ONLY StvnSeqCapacityInspection is enabled
        String seqVsSetDoc = """
            {
              :meta {
                #name   "seq_vs_set"
                #domain "capacity"
                #kind   #BODY
              }
              :defs {
                :SmallSeq { #maxSize 10 } :Seq(:Int)
                :SmallSet { #maxSize 10 } :Set(:Int)
                #SEQ_CONST :SmallSeq [1 2 3]
                #SET_CONST :SmallSet [10 20 30]
              }
              :type :Tuple(:Option(:SmallSeq) :SmallSet)
              :body ([4 5 6] [40 50 60])
            }
            """;
        myFixture.enableInspections(seqInsp);
        try {
            myFixture.configureByText("seq_vs_set.capacity.stvn", seqVsSetDoc);
            var seqWarnings = collectPw004Warnings();
            assertEquals("StvnSeqCapacityInspection must flag strictly the 2 :Seq literals (#SEQ_CONST and :Option(:SmallSeq)): "
                + seqWarnings, 2, seqWarnings.size());
            assertTrue(seqWarnings.stream().allMatch(w -> w.getDescription().contains("Sequence literal element count (3)")));
        } finally {
            myFixture.disableInspections(seqInsp);
        }

        // 2. Truncate & Widen quick-fixes + IntentionPreviewInfo.DIFF on :Map literal
        myFixture.enableInspections(mapInsp);
        try {
            String mapLitDoc = """
                {
                  :meta {
                    #name   "map_lit_fix"
                    #domain "capacity"
                    #kind   #BODY
                  }
                  :defs {
                    :TinyMap { #maxSize 2 } :Map({ #maxSize 16 } :String :Int)
                  }
                  :type :TinyMap
                  :body {["a" 1] ["b" 2] ["c" 3]}
                }
                """;
            PsiFile mapFile = myFixture.configureByText("map_lit_fix.capacity.stvn", mapLitDoc);
            var pw004 = collectPw004Warnings();
            assertEquals(1, pw004.size());
            assertTrue(pw004.get(0).getDescription().contains("Map literal entry count (3) exceeds declared schema capacity (2 entries)"));

            myFixture.getEditor().getCaretModel().moveToOffset(mapFile.getText().indexOf("[\"a\" 1]"));
            IntentionAction truncateMap = myFixture.findSingleIntention("Truncate map literal to 2 entries");
            IntentionAction widenMap = myFixture.findSingleIntention("Widen schema map capacity to 3");
            assertTrue(myFixture.getIntentionPreviewText(truncateMap).contains("{[\"a\" 1] [\"b\" 2]}"));
            assertTrue(myFixture.getIntentionPreviewText(widenMap).contains("{ #maxSize 3 }"));

            myFixture.launchAction(truncateMap);
            assertTrue(myFixture.getFile().getText().contains(":body {[\"a\" 1] [\"b\" 2]}"));
        } finally {
            myFixture.disableInspections(mapInsp);
        }

        // 3. String literal threshold overflow on unadorned :String when inspectPayload = true
        myFixture.enableInspections(strInsp);
        try {
            String strDoc = """
                {
                  :type :String
                  :body "abcdef"
                }
                """;
            myFixture.configureByText("str_thresh.stvn", strDoc);
            var pw004 = collectPw004Warnings();
            assertEquals(1, pw004.size());
            assertTrue(pw004.get(0).getDescription().contains("exceeds configured capacity threshold (4 characters)"));
        } finally {
            myFixture.disableInspections(strInsp);
        }
    }

    public void testVector5_SingleMapEntryExpandAndCollapseAcrossMultiLineAndSingleLineMaps() {
        // 1. Single-line outer MapLiteral: Expand map entry expands outer braces + 4-slot target entry ONLY,
        //    normalizing sibling interior bracket spaces!
        String singleLineMap = """
            {
              :type :Map(:String :Tuple(:String :String))
              :body { [ "room_1" ( "clock" "tower" ) ] [ "room_2" ( "vault" "key" ) ] }
            }
            """;
        PsiFile file = myFixture.configureByText("single_map_entry.stvn", singleLineMap);
        myFixture.getEditor().getCaretModel().moveToOffset(file.getText().indexOf("[ \"room_1\""));

        IntentionAction expandEntry = myFixture.findSingleIntention(StvnExpandMapEntryIntention.TEXT);
        String expectedExpandedOne = """
            {
              :type :Map(:String :Tuple(:String :String))
              :body {
                [
                  "room_1"
                  ("clock" "tower")
                ]
                ["room_2" ("vault" "key")]
              }
            }
            """;
        assertEquals(expectedExpandedOne, myFixture.getIntentionPreviewText(expandEntry));
        myFixture.launchAction(expandEntry);
        myFixture.checkResult(expectedExpandedOne);

        // 2. Verify zero duplicate Expand/Collapse container on single [k v] inside multi-line MapLiteral
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("[\"room_2\""));
        List<String> onSingleSliceTexts = myFixture.getAvailableIntentions().stream()
            .map(IntentionAction::getText)
            .toList();
        assertTrue(onSingleSliceTexts.contains(StvnExpandMapEntryIntention.TEXT));
        assertFalse("Expand container must not duplicate Expand map entry inside multi-line MapLiteral",
            onSingleSliceTexts.contains(StvnExpandContainerIntention.TEXT));

        // 3. Collapse the 4-slot room_1 entry back onto a single line
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("[\n      \"room_1\""));
        IntentionAction collapseEntry = myFixture.findSingleIntention(StvnCollapseMapEntryIntention.TEXT);
        myFixture.launchAction(collapseEntry);
        myFixture.checkResult("""
            {
              :type :Map(:String :Tuple(:String :String))
              :body {
                ["room_1" ("clock" "tower")]
                ["room_2" ("vault" "key")]
              }
            }
            """);
    }

    public void testVector6_WholeMapEntriesExpandAndCollapseInvolutionCommentsAndMixedMapDeconfliction() {
        String multiLineWithComments = """
            {
              :type :Map(:String :String)
              :body {
                // Header comment
                ["puzzle_1" "cipher_wheel"]
                // Mid-map comment
                ["puzzle_2" "brass_astrolabe"]
                // Footer comment
              }
            }
            """;
        String allFourSlotWithComments = """
            {
              :type :Map(:String :String)
              :body {
                // Header comment
                [
                  "puzzle_1"
                  "cipher_wheel"
                ]
                // Mid-map comment
                [
                  "puzzle_2"
                  "brass_astrolabe"
                ]
                // Footer comment
              }
            }
            """;

        PsiFile file = myFixture.configureByText("escape_room_map.stvn", multiLineWithComments);
        myFixture.getEditor().getCaretModel().moveToOffset(file.getText().indexOf(":body {") + ":body ".length());

        IntentionAction expandAll = myFixture.findSingleIntention(StvnExpandAllMapEntriesIntention.TEXT);
        assertEquals(allFourSlotWithComments, myFixture.getIntentionPreviewText(expandAll));
        myFixture.launchAction(expandAll);
        myFixture.checkResult(allFourSlotWithComments);

        // Clean map (all entries collapsible) -> Collapse all map entries offered, Collapse child elements suppressed
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf(":body {") + ":body ".length());
        List<String> onCleanFourSlot = myFixture.getAvailableIntentions().stream()
            .map(IntentionAction::getText)
            .toList();
        assertTrue(onCleanFourSlot.contains(StvnCollapseAllMapEntriesIntention.TEXT));
        assertFalse("StvnCollapseChildElementsIntention must yield when all map entries are collapsible",
            onCleanFourSlot.contains(StvnCollapseChildElementsIntention.TEXT));

        IntentionAction collapseAll = myFixture.findSingleIntention(StvnCollapseAllMapEntriesIntention.TEXT);
        myFixture.launchAction(collapseAll);
        myFixture.checkResult(multiLineWithComments);

        // Mixed map (1 fenced entry + 1 collapsible 4-slot entry) -> Collapse all hidden, Collapse child elements active!
        String mixedMap = """
            {
              :type :Map(:String :String)
              :body {
                [
                  "fenced_clue"
                  \"\"\"[RAW]
                    Turn the dial three clicks east.[RAW]\"\"\"
                ]
                [
                  "short_clue"
                  "north"
                ]
              }
            }
            """;
        PsiFile mixedFile = myFixture.configureByText("mixed_escape_room.stvn", mixedMap);
        myFixture.getEditor().getCaretModel().moveToOffset(mixedFile.getText().indexOf(":body {") + ":body ".length());
        List<String> mixedTexts = myFixture.getAvailableIntentions().stream()
            .map(IntentionAction::getText)
            .toList();
        assertFalse("Collapse all map entries must hide when an entry holds a fenced string",
            mixedTexts.contains(StvnCollapseAllMapEntriesIntention.TEXT));
        assertTrue("Collapse child elements must remain active to collapse the eligible subset",
            mixedTexts.contains(StvnCollapseChildElementsIntention.TEXT));
    }

    public void testVector7_FourZoneCaretProximityPriorityOrdering() {
        String doc = """
            {
              :type :Map(:String :String)
              :body {
                ["alpha" "café"]
                ["beta" "plain"]
              }
            }
            """;
        PsiFile file = myFixture.configureByText("four_zone_priority.stvn", doc);

        // Zone 1: Inside '"café"' leaf -> TOP fencing > NORMAL \u005CuXXXX > LOW map-entry & container layout
        myFixture.getEditor().getCaretModel().moveToOffset(file.getText().indexOf("café") + 1);
        List<IntentionAction> zone1 = myFixture.getAvailableIntentions();
        assertEquals(PriorityAction.Priority.TOP, unwrapPriority(findByText(zone1, PLAIN_TO_FENCED_TEXT)));
        assertEquals(PriorityAction.Priority.NORMAL, unwrapPriority(findByText(zone1, ESCAPE_NON_ASCII_TEXT)));
        assertEquals(PriorityAction.Priority.LOW, unwrapPriority(findByText(zone1, StvnExpandMapEntryIntention.TEXT)));
        assertEquals(PriorityAction.Priority.LOW, unwrapPriority(findByText(zone1, StvnExpandAllMapEntriesIntention.TEXT)));
        assertEquals(PriorityAction.Priority.LOW, unwrapPriority(findByText(zone1, StvnCollapseContainerIntention.TEXT)));

        // Zone 2: On '[' of '["alpha" "café"]' -> HIGH single-entry > NORMAL whole-map & outer container
        myFixture.getEditor().getCaretModel().moveToOffset(file.getText().indexOf("[\"alpha\""));
        List<IntentionAction> zone2 = myFixture.getAvailableIntentions();
        assertEquals(PriorityAction.Priority.HIGH, unwrapPriority(findByText(zone2, StvnExpandMapEntryIntention.TEXT)));
        assertEquals(PriorityAction.Priority.NORMAL, unwrapPriority(findByText(zone2, StvnExpandAllMapEntriesIntention.TEXT)));
        assertEquals(PriorityAction.Priority.NORMAL, unwrapPriority(findByText(zone2, StvnCollapseContainerIntention.TEXT)));

        // Zone 3: On '{' of ':body {' -> HIGH whole-map & outer map container
        myFixture.getEditor().getCaretModel().moveToOffset(file.getText().indexOf(":body {") + ":body ".length());
        List<IntentionAction> zone3 = myFixture.getAvailableIntentions();
        assertEquals(PriorityAction.Priority.HIGH, unwrapPriority(findByText(zone3, StvnExpandAllMapEntriesIntention.TEXT)));
        assertEquals(PriorityAction.Priority.HIGH, unwrapPriority(findByText(zone3, StvnCollapseContainerIntention.TEXT)));
    }

    public void testVector8_PlainAndEscapedStringFencingFourSlotMapEntryAutoExpansionAndIncompleteOpenerRecovery() {
        // 1. Plain string inside single-line MapLiteral with padded sibling tuple -> 4-slot entry + zero sibling bracket spaces
        String singleMapPlain = """
            {
              :type :Map(:String :Tuple(:String :String))
              :body { [ "k1" ( "hello world" "tag" ) ] [ "k2" ( "a" "b" ) ] }
            }
            """;
        PsiFile f1 = myFixture.configureByText("fence_single_map.stvn", singleMapPlain);
        myFixture.getEditor().getCaretModel().moveToOffset(f1.getText().indexOf("hello world") + 2);
        myFixture.launchAction(myFixture.findSingleIntention(PLAIN_TO_FENCED_TEXT));
        String expected1 = """
            {
              :type :Map(:String :Tuple(:String :String))
              :body {
                [
                  "k1"
                  (
                    \"\"\"[RAW]
                      hello world[RAW]\"\"\"
                    "tag"
                  )
                ]
                ["k2" ("a" "b")]
              }
            }
            """;
        myFixture.checkResult(expected1);
        assertFormatterFixedPoint(expected1);

        // 2. Plain string inside nested single-line TupleLiteral inside a single-line MapEntry of a multi-line MapLiteral
        String multiMapNestedTuple = """
            {
              :type :Map(:String :Tuple(:String :Int))
              :body {
                ["k1" ("plain_nested" 42)]
                ["k2" ("ok" 7)]
              }
            }
            """;
        PsiFile f2 = myFixture.configureByText("fence_multi_nested.stvn", multiMapNestedTuple);
        myFixture.getEditor().getCaretModel().moveToOffset(f2.getText().indexOf("plain_nested") + 3);
        myFixture.launchAction(myFixture.findSingleIntention(PLAIN_TO_FENCED_TEXT));
        String expected2 = """
            {
              :type :Map(:String :Tuple(:String :Int))
              :body {
                [
                  "k1"
                  (
                    \"\"\"[RAW]
                      plain_nested[RAW]\"\"\"
                    42
                  )
                ]
                ["k2" ("ok" 7)]
              }
            }
            """;
        myFixture.checkResult(expected2);
        assertFormatterFixedPoint(expected2);

        // 3. Cramped 2-line MapEntry ("k1"\n "escaped\\nval"] -> canonical 4-slot symmetry
        String crampedTwoLine = """
            {
              :type :Map(:String :String)
              :body {
                ["k1"
                  "line1\\nline2"]
              }
            }
            """;
        PsiFile f3 = myFixture.configureByText("fence_cramped.stvn", crampedTwoLine);
        myFixture.getEditor().getCaretModel().moveToOffset(f3.getText().indexOf("line1") + 2);
        myFixture.launchAction(myFixture.findSingleIntention(ESCAPED_TO_FENCED_TEXT));
        String expected3 = """
            {
              :type :Map(:String :String)
              :body {
                [
                  "k1"
                  \"\"\"[RAW]
                    line1
                    line2[RAW]\"\"\"
                ]
              }
            }
            """;
        myFixture.checkResult(expected3);
        assertFormatterFixedPoint(expected3);

        // 4. Incomplete """ opener recovery inside single-line MapLiteral -> 4-slot [FENCE] symmetry + caret on inner line
        String incompleteInMap = """
            {
              :type :Map(:String :String)
              :body { [ "room_clue" \"\"\"<caret> ] [ "status" "locked" ] }
            }
            """;
        myFixture.configureByText("fence_recovery_map.stvn", incompleteInMap);
        myFixture.launchAction(myFixture.findSingleIntention(PLAIN_TO_FENCED_TEXT));
        myFixture.checkResult("""
            {
              :type :Map(:String :String)
              :body {
                [
                  "room_clue"
                  \"\"\"[FENCE]
                    <caret>
                  [FENCE]\"\"\"
                ]
                ["status" "locked"]
              }
            }
            """);
        assertFalse(StvnCompiler.compileToResult(myFixture.getFile().getText(), "fence_recovery_map.stvn").hasErrors());
    }

    private void assertFormatterFixedPoint(String text) {
        assertFalse("Expected valid STVN document:\n" + text,
            StvnCompiler.compileToResult(text, "fixed_point.stvn").hasErrors());
        var settings = StvnSettings.getInstance(getProject());
        var prevPayload = settings.getState().payloadNestedWrapPolicy;
        try {
            settings.getState().payloadNestedWrapPolicy = NestedContainerWrapPolicy.PRESERVE_AUTHORED;
            PsiFile file = myFixture.getFile();
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(file);
            });
            assertEquals("Fenced MapEntry output must be a byte-for-byte Ctrl+Alt+L fixed point",
                text, file.getText());
        } finally {
            settings.getState().payloadNestedWrapPolicy = prevPayload;
        }
    }

    private List<com.intellij.codeInsight.daemon.impl.HighlightInfo> collectCapacityWarnings() {
        return myFixture.doHighlighting().stream()
            .filter(h -> h.getDescription() != null
                && (h.getDescription().contains("PW001-")
                    || h.getDescription().contains("PW002-")
                    || h.getDescription().contains("PW003-")
                    || h.getDescription().contains("PW004-")))
            .toList();
    }

    private List<com.intellij.codeInsight.daemon.impl.HighlightInfo> collectPw004Warnings() {
        return myFixture.doHighlighting().stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("PW004-"))
            .toList();
    }

    private static String highlightedText(String source, com.intellij.codeInsight.daemon.impl.HighlightInfo info) {
        return source.substring(info.getStartOffset(), info.getEndOffset());
    }

    private static IntentionAction findByText(List<IntentionAction> actions, String text) {
        return actions.stream()
            .filter(a -> text.equals(a.getText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing intention '" + text + "' in "
                + actions.stream().map(IntentionAction::getText).toList()));
    }

    private static PriorityAction.Priority unwrapPriority(IntentionAction action) {
        IntentionAction cur = action;
        while (cur instanceof IntentionActionDelegate delegate) {
            if (cur instanceof PriorityAction pa) {
                return pa.getPriority();
            }
            cur = delegate.getDelegate();
        }
        if (cur instanceof PriorityAction pa) {
            return pa.getPriority();
        }
        return PriorityAction.Priority.NORMAL;
    }
}
