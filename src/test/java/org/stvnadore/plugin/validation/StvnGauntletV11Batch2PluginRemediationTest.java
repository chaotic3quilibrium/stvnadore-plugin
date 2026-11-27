package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.plugin.StvnBundle;
import org.stvnadore.plugin.browser.StvnNamespaceBrowserPopup;
import org.stvnadore.plugin.browser.StvnNamespaceScope;
import org.stvnadore.plugin.browser.StvnNamespaceSymbolCollector;
import org.stvnadore.plugin.browser.StvnNamespaceSymbolEntry;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;

import javax.swing.Action;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.table.JTableHeader;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end IntelliJ Platform regression suite for Gauntlet V11 Batch 2 plugin remediation:
 * <ul>
 *   <li>Target 1: Empty container ({@code []}, {@code {}}, {@code ()}) full delimiter-pair pinning parity
 *       ({@code Pass A == Pass B}, {@code count == 1}, including interior comments), exemplar quick-fixes
 *       with {@code DIFF} preview, and hover {@code Valid Lexical & Syntax Forms} suppression when
 *       effective {@code #minSize >= 1}.</li>
 *   <li>Target 2: Zero-shift canonical {@code [#A #B]} single-line and layout-preserving multi-line
 *       {@code :Enum} subset sort ({@code E015}) plus surgical single-variant deletion ({@code E014},
 *       {@code E008}) with {@code DIFF} preview across Pass A and Pass B.</li>
 *   <li>Target 3: SCC-safe Bottom-Up VOP Grounding Depth for {@code :defs} scope and dynamic
 *       scope-aware {@code Use Depth} column header tooltips.</li>
 *   <li>Target 4: 2-stage progressive {@code Alt+R} and direct 1-step {@code Alt+Shift+R}
 *       screen-clamped window fitting with left/up origin shifting.</li>
 * </ul>
 */
@NullMarked
public final class StvnGauntletV11Batch2PluginRemediationTest extends BasePlatformTestCase {

    private static final Path SHARED_FIXTURES_ROOT = Paths.get("src", "test", "resources", "shared-fixtures");

    private record ContractRow(
        String canonicalCode,
        String symbolicId,
        int startOffset,
        int endOffset,
        String messageSubstring
    ) {}

    private void enableAll27StvnInspections() {
        myFixture.enableInspections(
            new StvnVariantStyleInspection(),
            new StvnBooleanValidityInspection(),
            new StvnMapStructuralInspection(),
            new StvnDegradedSchemaInspection(),
            new StvnDegenerateCompositeInspection(),
            new StvnEnumSubsetInspection(),
            new StvnFencedStringInspection(),
            new StvnLhsReservedTypeInspection(),
            new StvnConstantRangeInspection(),
            new StvnFlatDocumentIncludeInspection(),
            new StvnNestedPackageInspection(),
            new StvnTrailingSlashInspection(),
            new StvnStringCapacityInspection(),
            new StvnExcessiveExactFloatPrecisionInspection(),
            new StvnMetadataFacetInspection(),
            new StvnStringCardinalityInspection(),
            new StvnDiscreteIntervalInspection(),
            new StvnTemporalModeInspection(),
            new StvnMetadataOrderInspection(),
            new StvnRedundantFacetOverrideInspection(),
            new StvnUnresolvedTypeInspection(),
            new StvnTabCharacterInspection(),
            new StvnMissingMetaInspection(),
            new StvnStemMismatchInspection(),
            new StvnSuspiciousEpochMagnitudeInspection(),
            new StvnFloatPrecisionLossInspection(),
            new StvnStringEscapeScrutinyInspection()
        );
    }

    /**
     * Target 1.1: Verifies mirrored Track A empty-container coordinate contracts, multi-line
     * comment-bearing empty containers, and partial non-empty closing-delimiter spans across Pass A and Pass B.
     *
     * @throws Exception if fixture reading fails
     */
    public void testTarget1_EmptyContainerFullPairPinningInteriorCommentsAndPartialUnderflowParityPassAAndPassB()
            throws Exception {
        Path fixtureFile = SHARED_FIXTURES_ROOT.resolve(
            Paths.get("syntax", "invalid", "containers", "empty_container_pair_underflow_all_constructors.stvn")
        );
        Path contractFile = SHARED_FIXTURES_ROOT.resolve(
            Paths.get("syntax", "invalid", "containers", "empty_container_pair_underflow_all_constructors.contract.stvn_i")
        );
        assertTrue("Mirrored Track A container fixture must exist: " + fixtureFile, Files.isRegularFile(fixtureFile));
        assertTrue("Mirrored Track A contract must exist: " + contractFile, Files.isRegularFile(contractFile));

        String fixtureSrc = Files.readString(fixtureFile, StandardCharsets.UTF_8);
        List<ContractRow> expectedRows = loadContractRows(contractFile);
        assertFalse("Contract rows must not be empty", expectedRows.isEmpty());

        for (boolean passB : List.of(false, true)) {
            if (passB) {
                enableAll27StvnInspections();
            }
            myFixture.configureByText("empty_container_pair_underflow_all_constructors.stvn", fixtureSrc);
            List<HighlightInfo> errors = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .toList();
            assertEquals(
                "Error count in " + (passB ? "Pass B" : "Pass A") + " must match Track A contract exactly: " + errors,
                expectedRows.size(),
                errors.size()
            );
            for (ContractRow row : expectedRows) {
                long matches = errors.stream()
                    .filter(h -> h.getStartOffset() == row.startOffset() && h.getEndOffset() == row.endOffset())
                    .filter(h -> h.getDescription() != null && h.getDescription().contains(row.messageSubstring()))
                    .count();
                assertEquals(
                    "Expected count == 1 at [" + row.startOffset() + ", " + row.endOffset() + ") in "
                        + (passB ? "Pass B" : "Pass A") + " for " + row.canonicalCode() + "/" + row.symbolicId(),
                    1L,
                    matches
                );
            }
        }

        // Verify interior comments inside empty [] / {} / () do NOT suppress E017 / E012 / E028,
        // while partial non-empty underflows stay strictly on closing ], }, )
        String commentAndPartialDoc = """
            {
              :meta {
                #name "comment_and_partial_containers"
                #kind #BODY
              }
              :defs {
                :NonEmptySeq { #minSize 1 } :Seq(:Int)
                :MinTwoSeq   { #minSize 2 } :Seq(:Float)
                :NonEmptyMap { #minSize 1 } :Map(:String :Int)
                :MinTwoMap   { #minSize 2 } :Map(:String :Int)
                :PairTuple   :Tuple(:Int :String)
                :RootCheck   :Tuple(:NonEmptySeq :MinTwoSeq :NonEmptyMap :MinTwoMap :PairTuple :PairTuple)
              }
              :type :RootCheck
              :body (
                [
                  // comment inside empty seq
                ]
                [192.10]
                {
                  // comment inside empty map
                }
                {["k1" 1]}
                (
                  // comment inside empty tuple
                )
                (42)
              )
            }
            """;

        myFixture.configureByText("comment_and_partial_containers.stvn", commentAndPartialDoc);
        List<HighlightInfo> highlights = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected 6 container underflow errors (3 empty pairs + 3 partial closing delims): " + highlights,
            6, highlights.size());

        int emptySeqStart = commentAndPartialDoc.indexOf("[\n      // comment inside empty seq\n    ]");
        int emptySeqEnd = emptySeqStart + "[\n      // comment inside empty seq\n    ]".length();
        assertTrue(highlights.stream().anyMatch(h -> h.getStartOffset() == emptySeqStart && h.getEndOffset() == emptySeqEnd));

        int partialSeqClose = commentAndPartialDoc.indexOf("[192.10]") + "[192.10]".length() - 1;
        assertTrue(highlights.stream().anyMatch(h -> h.getStartOffset() == partialSeqClose && h.getEndOffset() == partialSeqClose + 1));

        int emptyMapStart = commentAndPartialDoc.indexOf("{\n      // comment inside empty map\n    }");
        int emptyMapEnd = emptyMapStart + "{\n      // comment inside empty map\n    }".length();
        assertTrue(highlights.stream().anyMatch(h -> h.getStartOffset() == emptyMapStart && h.getEndOffset() == emptyMapEnd));

        int partialMapClose = commentAndPartialDoc.indexOf("{[\"k1\" 1]}") + "{[\"k1\" 1]}".length() - 1;
        assertTrue(highlights.stream().anyMatch(h -> h.getStartOffset() == partialMapClose && h.getEndOffset() == partialMapClose + 1));

        int emptyTupleStart = commentAndPartialDoc.indexOf("(\n      // comment inside empty tuple\n    )");
        int emptyTupleEnd = emptyTupleStart + "(\n      // comment inside empty tuple\n    )".length();
        assertTrue(highlights.stream().anyMatch(h -> h.getStartOffset() == emptyTupleStart && h.getEndOffset() == emptyTupleEnd));

        int partialTupleClose = commentAndPartialDoc.indexOf("(42)") + "(42)".length() - 1;
        assertTrue(highlights.stream().anyMatch(h -> h.getStartOffset() == partialTupleClose && h.getEndOffset() == partialTupleClose + 1));
    }

    /**
     * Target 1.2: Verifies exemplar quick-fixes (and {@code IntentionPreviewInfo.DIFF}) on {@code E017}
     * ({@code COLLECTION_EMPTY_VIOLATION}), {@code E012} ({@code M == 0} {@code #minSize >= 2}, including nested lists),
     * and {@code E028} ({@code M == 0} {@code TUPLE_ARITY_MISMATCH}) across {@code :body}, {@code #CONST}, and untagged sums,
     * while offering zero whole-container overwrites on partial non-empty containers.
     */
    public void testTarget1_EmptyContainerExemplarQuickFixesAndDiffPreviewAcrossBodyConstAndUntaggedSums() {
        enableAll27StvnInspections();

        // 1. E017 on :Seq, :Map, E028 on :Tuple, nested [ [] ] on #minSize 2, and #CONST empty seq
        String docText = """
            {
              :meta {
                #name "empty_container_quickfixes"
                #kind #BODY
              }
              :defs {
                :FiberId      { #unsigned #size 16 #minIncl 1 } :Int
                :PilotHistory { #minSize 1 #maxSize 8 } :Seq(:FiberId)
                :MinTwoGrid   { #minSize 2 #maxSize 4 } :Seq(:FiberId)
                :ChannelMap   { #minSize 1 } :Map(:String :FiberId)
                :CoordPair    :Tuple(:FiberId :Boolean)
                #EMPTY_CONST  :PilotHistory []
                :Envelope     :Tuple(
                  :PilotHistory
                  :ChannelMap
                  :CoordPair
                  :Seq(:MinTwoGrid)
                  :Option(:PilotHistory)
                  :Union(:String :CoordPair)
                  :MinTwoGrid
                  :CoordPair
                )
              }
              :type :Envelope
              :body (
                []
                {}
                ()
                [ [] ]
                []
                ()
                [1]
                (1)
              )
            }
            """;

        myFixture.configureByText("empty_container_quickfixes.stvn", docText);
        myFixture.doHighlighting();

        // #CONST empty seq exemplar + DIFF preview
        int constBracketOffset = docText.indexOf("#EMPTY_CONST  :PilotHistory []") + "#EMPTY_CONST  :PilotHistory ".length() + 1;
        myFixture.getEditor().getCaretModel().moveToOffset(constBracketOffset);
        List<IntentionAction> constFixes = myFixture.filterAvailableIntentions("Replace with exemplar value '[1]'");
        assertFalse("Expected 'Replace with exemplar value '[1]'' on #EMPTY_CONST []", constFixes.isEmpty());
        assertTrue(myFixture.getIntentionPreviewText(constFixes.get(0)).contains("#EMPTY_CONST  :PilotHistory [1]"));

        int bodyIdx = docText.indexOf(":body (");

        // Slot 0: :PilotHistory [] -> '[1]'
        int slot0 = docText.indexOf("[]", bodyIdx) + 1;
        myFixture.getEditor().getCaretModel().moveToOffset(slot0);
        List<IntentionAction> seqFixes = myFixture.filterAvailableIntentions("Replace with exemplar value '[1]'");
        assertFalse("Expected '[1]' exemplar quick-fix on empty :PilotHistory []", seqFixes.isEmpty());
        assertTrue(myFixture.getIntentionPreviewText(seqFixes.get(0)).contains("[1]"));

        // Slot 1: :ChannelMap {} -> '{ ["example" 1] }'
        int slot1 = docText.indexOf("{}", bodyIdx) + 1;
        myFixture.getEditor().getCaretModel().moveToOffset(slot1);
        List<IntentionAction> mapFixes = myFixture.filterAvailableIntentions("Replace with exemplar value '{ [\"example\" 1] }'");
        assertFalse("Expected map exemplar quick-fix on empty :ChannelMap {}", mapFixes.isEmpty());
        assertTrue(myFixture.getIntentionPreviewText(mapFixes.get(0)).contains("{ [\"example\" 1] }"));

        // Slot 2: :CoordPair () -> '(1 #TRUE)'
        int slot2 = docText.indexOf("()", bodyIdx) + 1;
        myFixture.getEditor().getCaretModel().moveToOffset(slot2);
        List<IntentionAction> tupleFixes = myFixture.filterAvailableIntentions("Replace with exemplar value '(1 #TRUE)'");
        assertFalse("Expected tuple exemplar quick-fix on empty :CoordPair ()", tupleFixes.isEmpty());
        assertTrue(myFixture.getIntentionPreviewText(tupleFixes.get(0)).contains("(1 #TRUE)"));

        // Slot 3: Nested [ [] ] on :Seq(:MinTwoGrid) -> must offer '[1 1]', NEVER 'Remove excess element'
        int nestedEmpty = docText.indexOf("[ [] ]", bodyIdx) + 3;
        myFixture.getEditor().getCaretModel().moveToOffset(nestedEmpty);
        List<IntentionAction> nestedAllFixes = myFixture.getAvailableIntentions();
        assertFalse(
            "Nested underflow [] must NEVER be misclassified as an excess element",
            nestedAllFixes.stream().anyMatch(a -> a.getText().startsWith("Remove excess element"))
        );
        List<IntentionAction> minTwoFixes = myFixture.filterAvailableIntentions("Replace with exemplar value '[1 1]'");
        assertFalse("Expected '[1 1]' exemplar quick-fix on nested :MinTwoGrid []", minTwoFixes.isEmpty());

        // Slot 4: Untagged :Option(:PilotHistory) [] -> '[1]' (never '#None')
        int optEmpty = docText.indexOf("[]", nestedEmpty + 2) + 1;
        myFixture.getEditor().getCaretModel().moveToOffset(optEmpty);
        assertFalse(
            "Untagged :Option(:PilotHistory) [] must synthesize '[1]'",
            myFixture.filterAvailableIntentions("Replace with exemplar value '[1]'").isEmpty()
        );

        // Slot 5: Untagged :Union(:String :CoordPair) () -> '(1 #TRUE)'
        int unionEmpty = docText.indexOf("()", optEmpty) + 1;
        myFixture.getEditor().getCaretModel().moveToOffset(unionEmpty);
        assertFalse(
            "Untagged :Union(:String :CoordPair) () must synthesize '(1 #TRUE)'",
            myFixture.filterAvailableIntentions("Replace with exemplar value '(1 #TRUE)'").isEmpty()
        );

        // Slot 6 & Slot 7: Partial non-empty '[1]' and '(1)' -> 0 whole-container exemplar overwrite quick-fixes!
        int partialSeqClose = docText.indexOf("[1]", unionEmpty) + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(partialSeqClose);
        assertTrue(
            "Partial non-empty [1] must NOT offer destructive whole-list exemplar replacement",
            myFixture.getAvailableIntentions().stream().noneMatch(a -> a.getText().startsWith("Replace with exemplar value"))
        );

        int partialTupleClose = docText.indexOf("(1)", partialSeqClose) + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(partialTupleClose);
        assertTrue(
            "Partial non-empty (1) must NOT offer destructive whole-tuple exemplar replacement",
            myFixture.getAvailableIntentions().stream().noneMatch(a -> a.getText().startsWith("Replace with exemplar value"))
        );
    }

    /**
     * Target 1.3: Verifies hover {@code Valid Lexical & Syntax Forms} and {@code :type} payload rules
     * suppress {@code Empty Collection Literal: []} and {@code Empty Map Literal: {}} whenever effective
     * {@code #minSize >= 1} across nominal aliases, {@code UseMapAlias} imports, and {@code #CONST} bounds.
     */
    public void testTarget1_HoverValidLexicalFormsSuppressesEmptyLiteralsWheneverEffectiveMinSizeAtLeastOne() {
        myFixture.addFileToProject(
            "fiber_pkg.telecom.stvn_di",
            """
            {
              :meta {
                #name "fiber_pkg"
                #domain "telecom"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :package :telecom/dwm {
                  #MIN_CORE_ENTRIES :Int 1
                  :FiberCoreId      :Float
                  :RemoteCoreMap    { #minSize #MIN_CORE_ENTRIES } :Map(:String :FiberCoreId)
                }
              }
            }
            """
        );

        String mainDoc = """
            {
              :meta {
                #name "hover_min_size_check"
                #domain "telecom"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "fiber_pkg.telecom.stvn_di" ]
                :use [
                  :telecom/dwm { #strip } {
                    :RemoteCoreMap :AliasedCoreMap
                  }
                ]
                #LOCAL_MIN_ONE :Int 1
                :PilotToneHistory { #minSize #LOCAL_MIN_ONE #maxSize 8 } :Seq(:FiberCoreId)
                :DerivedPilotHistory :PilotToneHistory
                :OptionalSeq :Seq(:FiberCoreId)
                :OptionalMap :Map(:String :FiberCoreId)
                #BAD_CONST_SEQ :DerivedPilotHistory []
                :RootBundle :Tuple(:DerivedPilotHistory :AliasedCoreMap :OptionalSeq :OptionalMap)
              }
              :type :DerivedPilotHistory
              :body []
            }
            """;

        PsiFile file = myFixture.configureByText("hover_min_size_check.telecom.stvn_i", mainDoc);
        var docProvider = new StvnDocumentationProvider();

        // 1. Hover :DerivedPilotHistory in :type -> must NOT advertise 'Empty payload representation: <code>[]</code>'
        int typeRefOffset = mainDoc.indexOf(":type :DerivedPilotHistory") + ":type ".length() + 2;
        String typeHover = getHoverHtml(docProvider, myFixture.getEditor(), file, typeRefOffset);
        assertFalse(
            "Root :type hover on #minSize >= 1 collection must suppress Empty payload representation:\n" + typeHover,
            typeHover.contains("Empty payload representation")
        );

        // 2. Hover [] in :body governed by :DerivedPilotHistory (#minSize #LOCAL_MIN_ONE == 1)
        int bodyEmptyOffset = mainDoc.lastIndexOf("[]");
        String bodyEmptyHover = getHoverHtml(docProvider, myFixture.getEditor(), file, bodyEmptyOffset);
        assertTrue("Body [] hover must report Violated:\n" + bodyEmptyHover, bodyEmptyHover.contains("Violated"));
        assertTrue(
            "Body [] hover must retain Bracketed Collection Literal:\n" + bodyEmptyHover,
            bodyEmptyHover.contains("Bracketed Collection Literal:")
        );
        assertFalse(
            "Body [] hover must suppress 'Empty Collection Literal: <code>[]</code>' when #minSize >= 1:\n" + bodyEmptyHover,
            bodyEmptyHover.contains("Empty Collection Literal")
        );

        // 3. Hover [] in #BAD_CONST_SEQ
        int constEmptyOffset = mainDoc.indexOf("#BAD_CONST_SEQ :DerivedPilotHistory []")
            + "#BAD_CONST_SEQ :DerivedPilotHistory ".length();
        String constEmptyHover = getHoverHtml(docProvider, myFixture.getEditor(), file, constEmptyOffset);
        assertFalse(
            "#CONST [] hover must suppress 'Empty Collection Literal' when #minSize >= 1:\n" + constEmptyHover,
            constEmptyHover.contains("Empty Collection Literal")
        );

        // 4. Verify UseMapAlias :AliasedCoreMap (#minSize #MIN_CORE_ENTRIES in included package) vs minSize == 0 siblings
        String bundleBodyDoc = mainDoc.replace(":type :DerivedPilotHistory\n  :body []", ":type :RootBundle\n  :body ([] {} [] {})");
        PsiFile bundleFile = myFixture.configureByText("hover_bundle_check.telecom.stvn_i", bundleBodyDoc);
        int tupleBodyIdx = bundleBodyDoc.indexOf(":body (") + ":body (".length();

        int aliasedMapOffset = bundleBodyDoc.indexOf("{}", tupleBodyIdx);
        String aliasedMapHover = getHoverHtml(docProvider, myFixture.getEditor(), bundleFile, aliasedMapOffset);
        assertTrue("Aliased non-empty map {} hover must report Violated:\n" + aliasedMapHover, aliasedMapHover.contains("Violated"));
        assertFalse(
            "UseMapAlias map with package-private #CONST #minSize 1 must suppress 'Empty Map Literal':\n" + aliasedMapHover,
            aliasedMapHover.contains("Empty Map Literal")
        );

        int optionalSeqOffset = bundleBodyDoc.indexOf("[]", aliasedMapOffset);
        String optionalSeqHover = getHoverHtml(docProvider, myFixture.getEditor(), bundleFile, optionalSeqOffset);
        assertTrue(
            "Unconstrained :Seq (minSize == 0) must still advertise 'Empty Collection Literal: <code>[]</code>':\n" + optionalSeqHover,
            optionalSeqHover.contains("Empty Collection Literal: <code>[]</code>")
        );

        int optionalMapOffset = bundleBodyDoc.indexOf("{}", optionalSeqOffset);
        String optionalMapHover = getHoverHtml(docProvider, myFixture.getEditor(), bundleFile, optionalMapOffset);
        assertTrue(
            "Unconstrained :Map (minSize == 0) must still advertise 'Empty Map Literal: <code>{}</code>':\n" + optionalMapHover,
            optionalMapHover.contains("Empty Map Literal: <code>{}</code>")
        );
    }

    /**
     * Target 2: Verifies zero-shift {@code [#LOCKED #DEGRADED]} single-line sort, comment-preserving
     * multi-line sort ({@code E015}), and surgical single-variant deletion ({@code E014} and {@code E008})
     * across Pass A, Pass B, and {@code IntentionPreviewInfo.DIFF}.
     */
    public void testTarget2_EnumSubsetZeroShiftCanonicalSortAndSurgicalSingleVariantDeletionPassAAndPassB() {
        String reorderTemplate = """
            {
              :meta {
                #name "laser_telemetry"
                #kind #DEFS
              }
              :defs {
                :LaserState           :Enum[#OFF #COLD_STANDBY #WARMUP #LOCKED #DEGRADED]
                :PoweredLaserState    { #filterExcl [#OFF] } :LaserState
                :TelemetryActiveState { #filterIncl [#DEGRADED #LOCKED] }  :PoweredLaserState // aligned target
              }
            }
            """;
        String expectedReordered = reorderTemplate.replace("[#DEGRADED #LOCKED]", "[#LOCKED #DEGRADED]");
        assertEquals("Single-line sort must have exact 0-character width delta",
            reorderTemplate.length(), expectedReordered.length());

        for (String mode : List.of("PassA", "PassB")) {
            if ("PassB".equals(mode)) {
                enableAll27StvnInspections();
            }
            myFixture.configureByText("laser_telemetry.stvn_d", reorderTemplate);
            int beforeColOffset = reorderTemplate.indexOf(":PoweredLaserState // aligned target");
            myFixture.getEditor().getCaretModel().moveToOffset(reorderTemplate.indexOf("[#DEGRADED #LOCKED]") + 2);
            myFixture.doHighlighting();

            List<IntentionAction> sortFixes = myFixture.filterAvailableIntentions(
                "Sort variants to match root enum declaration order"
            );
            assertFalse("Expected E015 sort quick-fix in " + mode, sortFixes.isEmpty());
            assertEquals("DIFF preview must emit zero-padded [#LOCKED #DEGRADED] in " + mode,
                expectedReordered, myFixture.getIntentionPreviewText(sortFixes.get(0)));

            myFixture.launchAction(sortFixes.get(0));
            String afterText = myFixture.getFile().getText();
            assertEquals(expectedReordered, afterText);
            assertEquals(
                ":PoweredLaserState column offset must not shift by even 1 character in " + mode,
                beforeColOffset,
                afterText.indexOf(":PoweredLaserState // aligned target")
            );
        }

        // Multi-line reorder preserves newlines, indentation, and inline // comments
        enableAll27StvnInspections();
        String multiLineReorder = """
            {
              :defs {
                :LaserState :Enum[#OFF #LOCKED #DEGRADED]
                :Active {
                  #filterIncl [
                    #DEGRADED // degraded note
                    #LOCKED   // locked note
                  ]
                } :LaserState
              }
            }
            """;
        String expectedMultiLine = """
            {
              :defs {
                :LaserState :Enum[#OFF #LOCKED #DEGRADED]
                :Active {
                  #filterIncl [
                    #LOCKED // degraded note
                    #DEGRADED   // locked note
                  ]
                } :LaserState
              }
            }
            """;
        myFixture.configureByText("multiline_sort.stvn_d", multiLineReorder);
        myFixture.getEditor().getCaretModel().moveToOffset(multiLineReorder.indexOf("#DEGRADED // degraded") + 2);
        myFixture.doHighlighting();
        List<IntentionAction> mlSort = myFixture.filterAvailableIntentions("Sort variants to match root enum declaration order");
        assertFalse(mlSort.isEmpty());
        assertEquals(expectedMultiLine, myFixture.getIntentionPreviewText(mlSort.get(0)));
        myFixture.launchAction(mlSort.get(0));
        assertEquals(expectedMultiLine, myFixture.getFile().getText());

        // Surgical E014 removal of first, middle, and last variants preserving interior bracket padding and double-spaces
        String removeFirstInput = """
            {
              :defs {
                :LaserState           :Enum[#COLD_STANDBY #LOCKED #DEGRADED]
                :PoweredLaserState    { #filterExcl [#COLD_STANDBY] } :LaserState
                :TelemetryActiveState { #filterIncl [  #COLD_STANDBY #LOCKED   #DEGRADED  ] } :PoweredLaserState
              }
            }
            """;
        String expectedRemoveFirst = removeFirstInput.replace(
            "[  #COLD_STANDBY #LOCKED   #DEGRADED  ]",
            "[  #LOCKED   #DEGRADED  ]"
        );
        myFixture.configureByText("remove_first.stvn_d", removeFirstInput);
        myFixture.getEditor().getCaretModel().moveToOffset(
            removeFirstInput.lastIndexOf("#COLD_STANDBY") + 2
        );
        myFixture.doHighlighting();
        List<IntentionAction> remFirstFixes = myFixture.filterAvailableIntentions(
            "Remove '#COLD_STANDBY' from '#filterIncl'"
        );
        assertFalse(remFirstFixes.isEmpty());
        assertEquals(expectedRemoveFirst, myFixture.getIntentionPreviewText(remFirstFixes.get(0)));
        myFixture.launchAction(remFirstFixes.get(0));
        assertEquals(expectedRemoveFirst, myFixture.getFile().getText());

        String removeLastInput = """
            {
              :defs {
                :LaserState           :Enum[#LOCKED #DEGRADED #COLD_STANDBY]
                :PoweredLaserState    { #filterExcl [#COLD_STANDBY] } :LaserState
                :TelemetryActiveState { #filterIncl [  #LOCKED   #DEGRADED #COLD_STANDBY  ] } :PoweredLaserState
              }
            }
            """;
        String expectedRemoveLast = removeLastInput.replace(
            "[  #LOCKED   #DEGRADED #COLD_STANDBY  ]",
            "[  #LOCKED   #DEGRADED  ]"
        );
        myFixture.configureByText("remove_last.stvn_d", removeLastInput);
        myFixture.getEditor().getCaretModel().moveToOffset(
            removeLastInput.lastIndexOf("#COLD_STANDBY") + 2
        );
        myFixture.doHighlighting();
        List<IntentionAction> remLastFixes = myFixture.filterAvailableIntentions(
            "Remove '#COLD_STANDBY' from '#filterIncl'"
        );
        assertFalse(remLastFixes.isEmpty());
        assertEquals(expectedRemoveLast, myFixture.getIntentionPreviewText(remLastFixes.get(0)));
        myFixture.launchAction(remLastFixes.get(0));
        assertEquals(expectedRemoveLast, myFixture.getFile().getText());

        String removeDupMiddleInput = """
            {
              :defs {
                :LaserState           :Enum[#OFF #LOCKED #DEGRADED]
                :TelemetryActiveState { #filterIncl [  #OFF #LOCKED #LOCKED   #DEGRADED  ] } :LaserState
              }
            }
            """;
        String expectedRemoveDupMiddle = removeDupMiddleInput.replace(
            "[  #OFF #LOCKED #LOCKED   #DEGRADED  ]",
            "[  #OFF #LOCKED #DEGRADED  ]"
        );
        myFixture.configureByText("remove_dup_middle.stvn_d", removeDupMiddleInput);
        myFixture.getEditor().getCaretModel().moveToOffset(
            removeDupMiddleInput.lastIndexOf("#LOCKED") + 2
        );
        myFixture.doHighlighting();
        List<IntentionAction> remDupFixes = myFixture.filterAvailableIntentions(
            "Remove duplicate variant '#LOCKED'"
        );
        assertFalse(remDupFixes.isEmpty());
        assertEquals(expectedRemoveDupMiddle, myFixture.getIntentionPreviewText(remDupFixes.get(0)));
        myFixture.launchAction(remDupFixes.get(0));
        assertEquals(expectedRemoveDupMiddle, myFixture.getFile().getText());
    }

    /**
     * Target 3: Verifies SCC-safe Bottom-Up VOP Grounding Depth for {@code :defs} (Stage 4, Stage 6,
     * self-recursive {@code :TreeNode}, mutually recursive cycles) while keeping {@code :type}/{@code :body}
     * Top-Down Reachability unchanged, plus dynamic scope-aware {@code Use Depth} header tooltips.
     *
     * @throws Exception if async browser analysis times out
     */
    public void testTarget3_DefsBottomUpSccGroundingDepthAndScopeAwareUseDepthHeaderTooltips() throws Exception {
        String stage4And6AndRecursionDoc = """
            {
              :meta {
                #name "grounding_depth_verification"
                #kind #BODY
              }
              :defs {
                :LaserState           :Enum[#OFF #COLD_STANDBY #WARMUP #LOCKED #DEGRADED]
                :PoweredLaserState    { #filterExcl [#OFF] } :LaserState
                :TelemetryActiveState { #filterIncl [#LOCKED #DEGRADED] } :PoweredLaserState
                :RepeaterLaserBank    :Seq(:TelemetryActiveState)

                :FiberCoreId          :Float
                #PRIMARY_CORE         :FiberCoreId 192.10
                :PilotToneHistory     { #minSize 1 #maxSize 8 } :Seq(:FiberCoreId)
                :WavelengthGrid       :Map(:String :PilotToneHistory)

                :TreeLabel            { #minSize 1 #maxSize 32 } :String
                :TreeNode             :Tuple(:TreeLabel :Seq(:TreeNode))

                :MutualAlpha          :Seq(:MutualBeta)
                :MutualBeta           :Tuple(:LaserState :MutualAlpha)
              }
              :type :RepeaterLaserBank
              :body [#LOCKED]
            }
            """;

        PsiFile file = myFixture.configureByText("grounding_depth_verification.stvn", stage4And6AndRecursionDoc);
        List<StvnNamespaceSymbolEntry> defsEntries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);

        assertEquals(0, findSymbol(defsEntries, ":LaserState").useDepth());
        assertEquals(1, findSymbol(defsEntries, ":PoweredLaserState").useDepth());
        assertEquals(2, findSymbol(defsEntries, ":TelemetryActiveState").useDepth());
        assertEquals(3, findSymbol(defsEntries, ":RepeaterLaserBank").useDepth());

        assertEquals(0, findSymbol(defsEntries, ":FiberCoreId").useDepth());
        assertEquals(1, findSymbol(defsEntries, "#PRIMARY_CORE").useDepth());
        assertEquals(1, findSymbol(defsEntries, ":PilotToneHistory").useDepth());
        assertEquals(2, findSymbol(defsEntries, ":WavelengthGrid").useDepth());

        assertEquals(0, findSymbol(defsEntries, ":TreeLabel").useDepth());
        assertEquals(1, findSymbol(defsEntries, ":TreeNode").useDepth());

        assertEquals(1, findSymbol(defsEntries, ":MutualAlpha").useDepth());
        assertEquals(1, findSymbol(defsEntries, ":MutualBeta").useDepth());

        // Verify :type and :body Top-Down Reachability remain 100% unchanged (0 -> 1 -> 2 -> 3 from :RepeaterLaserBank)
        List<StvnNamespaceSymbolEntry> typeEntries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.TYPE);
        assertEquals(0, findSymbol(typeEntries, ":RepeaterLaserBank").useDepth());
        assertEquals(1, findSymbol(typeEntries, ":TelemetryActiveState").useDepth());
        assertEquals(2, findSymbol(typeEntries, ":PoweredLaserState").useDepth());
        assertEquals(3, findSymbol(typeEntries, ":LaserState").useDepth());

        // Verify scope-aware Use Depth header tooltip in JTableHeader across :defs, :type, and :body
        var disposable = Disposer.newDisposable("Target3HeaderTooltipTest");
        try {
            var ui = StvnNamespaceBrowserPopup.createBrowserContent(getProject(), file, StvnNamespaceScope.DEFS, disposable);
            ui.waitForAnalysis(5, TimeUnit.SECONDS);

            ui.panel().setSize(new Dimension(920, 440));
            doLayoutRecursively(ui.panel());
            JTableHeader header = ui.table().getTableHeader();
            assertNotNull(header);
            header.setSize(header.getPreferredSize().width, Math.max(28, header.getPreferredSize().height));
            header.doLayout();

            Rectangle col0Rect = header.getHeaderRect(0);
            Rectangle col4Rect = header.getHeaderRect(4);
            Point col0Center = new Point(col0Rect.x + col0Rect.width / 2, col0Rect.height / 2);
            Point col4Center = new Point(col4Rect.x + col4Rect.width / 2, col4Rect.height / 2);

            assertNull(
                "Columns 0..3 header tooltip must remain null",
                header.getToolTipText(new MouseEvent(header, MouseEvent.MOUSE_MOVED, 0L, 0, col0Center.x, col0Center.y, 0, false))
            );
            assertEquals(
                StvnBundle.message("stvn.browser.column.useDepth.tooltip.defs"),
                header.getToolTipText(new MouseEvent(header, MouseEvent.MOUSE_MOVED, 0L, 0, col4Center.x, col4Center.y, 0, false))
            );

            ui.scopeButtons().get(StvnNamespaceScope.TYPE).doClick();
            assertEquals(
                StvnBundle.message("stvn.browser.column.useDepth.tooltip.reachability"),
                header.getToolTipText(new MouseEvent(header, MouseEvent.MOUSE_MOVED, 0L, 0, col4Center.x, col4Center.y, 0, false))
            );

            ui.scopeButtons().get(StvnNamespaceScope.BODY).doClick();
            assertEquals(
                StvnBundle.message("stvn.browser.column.useDepth.tooltip.reachability"),
                header.getToolTipText(new MouseEvent(header, MouseEvent.MOUSE_MOVED, 0L, 0, col4Center.x, col4Center.y, 0, false))
            );
        } finally {
            Disposer.dispose(disposable);
        }
    }

    /**
     * Target 4: Verifies 2-stage progressive {@code Alt+R} (Press 1 right-sizes unlocked columns only;
     * Press 2 fits the popup window), direct 1-step {@code Alt+Shift+R}, header menu item 4,
     * {@code SearchTextField} {@code Alt+Shift+R} key-typed consume guard, minimum floor clamping,
     * and screen-clamped {@code (x, y)} origin shifting.
     *
     * @throws Exception if async browser analysis times out
     */
    public void testTarget4_TwoStageProgressiveAltRDirectAltShiftRAndScreenClampedWindowFitting() throws Exception {
        String wideUnionDoc = """
            {
              :meta {
                #name "wide_browser_fit"
                #kind #BODY
              }
              :defs {
                :IsolatedFloorCheckScalar :Int
                :AlphaLongTelemetryChannelIdentifier01 :Int
                :BravoLongTelemetryChannelIdentifier02 :Float
                :CharlieLongTelemetryChannelIdentifier03 :String
                :DeltaLongTelemetryChannelIdentifier04 :Boolean
                :EchoLongTelemetryChannelIdentifier05 { #offset } :DateTime
                :FoxtrotLongTelemetryChannelIdentifier06 { #ns } :TimeEpoch
                :WideCompositeEnvelope :Tuple(
                  :AlphaLongTelemetryChannelIdentifier01
                  :BravoLongTelemetryChannelIdentifier02
                  :CharlieLongTelemetryChannelIdentifier03
                  :DeltaLongTelemetryChannelIdentifier04
                  :EchoLongTelemetryChannelIdentifier05
                  :FoxtrotLongTelemetryChannelIdentifier06
                  :Map(:String :Tuple(:AlphaLongTelemetryChannelIdentifier01 :CharlieLongTelemetryChannelIdentifier03))
                  :Union(:Int :Float :String :Boolean :Tuple(:Int :Int :Int))
                )
              }
              :type :WideCompositeEnvelope
              :body (1 1.0 "ok" #TRUE "2026-11-26T12:00:00Z" 100 {} #1 1)
            }
            """;

        PsiFile file = myFixture.configureByText("wide_browser_fit.stvn", wideUnionDoc);
        var disposable = Disposer.newDisposable("Target4WindowFitTest");
        try {
            var ui = StvnNamespaceBrowserPopup.createBrowserContent(getProject(), file, StvnNamespaceScope.DEFS, disposable);
            ui.waitForAnalysis(5, TimeUnit.SECONDS);

            ui.panel().setSize(new Dimension(920, 440));
            doLayoutRecursively(ui.panel());

            AtomicReference<Rectangle> simulatedPopupBounds = new AtomicReference<>(new Rectangle(1200, 750, 920, 440));
            Rectangle monitorWorkArea = new Rectangle(0, 0, 1920, 1040);
            AtomicInteger applyBoundsCount = new AtomicInteger(0);

            ui.popupWindowBridgeRef().set(new StvnNamespaceBrowserPopup.PopupWindowBridge() {
                @Override
                public @Nullable Rectangle getCurrentBounds() {
                    return simulatedPopupBounds.get();
                }

                @Override
                public Rectangle getUsableScreenBounds(Point anchorScreenPoint) {
                    return monitorWorkArea;
                }

                @Override
                public void applyBounds(Rectangle targetBounds) {
                    simulatedPopupBounds.set(targetBounds);
                    applyBoundsCount.incrementAndGet();
                }
            });

            int lockedUseDepthWidth = ui.table().getColumnModel().getColumn(4).getWidth();

            // Shrink unlocked column 3 (Resolved Type) so columns need right-sizing
            ui.table().getColumnModel().getColumn(3).setPreferredWidth(200);
            ui.table().getColumnModel().getColumn(3).setWidth(200);
            assertFalse("Unlocked columns must report not-yet-right-sized",
                ui.columnManager().areAllUnlockedColumnsRightSized());

            // 1. Alt+R Press 1: right-sizes unlocked columns ONLY; popup window bounds do NOT change!
            firePopupAction(ui.rightSizeAllUnlockedAction());
            assertTrue("After Press 1, all unlocked columns must be at target right-sized width",
                ui.columnManager().areAllUnlockedColumnsRightSized());
            assertEquals("Press 1 must NOT resize or move the JBPopup window", 0, applyBoundsCount.get());
            assertEquals(new Rectangle(1200, 750, 920, 440), simulatedPopupBounds.get());
            assertEquals("Locked Use Depth column must remain untouched",
                lockedUseDepthWidth, ui.table().getColumnModel().getColumn(4).getWidth());

            // 2. Alt+R Press 2: columns are already right-sized -> fits popup window and shifts (x, y) inside screenRect - 24px!
            firePopupAction(ui.rightSizeAllUnlockedAction());
            assertEquals("Press 2 must invoke applyBounds exactly once", 1, applyBoundsCount.get());
            Rectangle afterPress2 = simulatedPopupBounds.get();
            assertTrue("Popup width must expand beyond initial 920px to fit wide Resolved Type: " + afterPress2,
                afterPress2.width > 920);
            assertTrue("Expanded popup maxX must stay within monitorWorkArea.maxX - 24px: " + afterPress2,
                afterPress2.x + afterPress2.width <= monitorWorkArea.x + monitorWorkArea.width - 24);
            assertTrue("Expanded popup maxY must stay within monitorWorkArea.maxY - 24px: " + afterPress2,
                afterPress2.y + afterPress2.height <= monitorWorkArea.y + monitorWorkArea.height - 24);
            assertTrue("Origin x must shift left from 1200 to prevent right-edge overflow: " + afterPress2,
                afterPress2.x < 1200);

            // 3. Direct 1-step Alt+Shift+R: reset column 3 to 180px and window to (1300, 800, 920, 440) -> fits both in 1 step!
            ui.table().getColumnModel().getColumn(3).setPreferredWidth(180);
            ui.table().getColumnModel().getColumn(3).setWidth(180);
            simulatedPopupBounds.set(new Rectangle(1300, 800, 920, 440));
            assertFalse(ui.columnManager().areAllUnlockedColumnsRightSized());

            assertTrue("Alt+Shift+R presentation must stay enabled inside popup",
                isPopupActionPresentationEnabled(ui.fitWindowToContentAction()));
            firePopupAction(ui.fitWindowToContentAction());
            assertTrue("Alt+Shift+R must expand Column 3 from 180px to water-filled screen cap",
                ui.table().getColumnModel().getColumn(3).getWidth() > 500);
            assertFalse("When water-fill-capped on 1920x1040, unlocked Column 3 remains narrower than unconstrained natural width",
                ui.columnManager().areAllUnlockedColumnsRightSized());
            assertEquals("Alt+Shift+R must also fit popup window in the same single step", 2, applyBoundsCount.get());
            assertEquals(afterPress2.width, simulatedPopupBounds.get().width);
            // Subsequent Alt+R expands water-filled Column 3 out to full unconstrained natural width
            firePopupAction(ui.rightSizeAllUnlockedAction());
            assertTrue("Pressing Alt+R after water-filled Alt+Shift+R must expand to unconstrained natural width",
                ui.columnManager().areAllUnlockedColumnsRightSized());

            // 4. SearchTextField Alt+Shift+R key-typed consume guard ('R' and macOS Option+Shift+R '\u2030')
            var filterEditor = ui.filterField().getTextEditor();
            filterEditor.setText("Wide");
            long now = System.currentTimeMillis();
            int altShiftMask = InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;
            for (char ch : new char[]{'R', 'r', '\u2030', '\u00AE'}) {
                KeyEvent typed = new KeyEvent(filterEditor, KeyEvent.KEY_TYPED, now, altShiftMask, KeyEvent.VK_UNDEFINED, ch);
                for (KeyListener kl : filterEditor.getKeyListeners()) {
                    kl.keyTyped(typed);
                }
                assertTrue("SearchTextField must consume Alt/Alt+Shift keyChar '" + ch + "'", typed.isConsumed());
            }
            assertEquals("Filter text must remain uncorrupted by Alt+Shift+R", "Wide", filterEditor.getText());

            // 5. Swing InputMap/ActionMap binding + Header Context Menu Item 4 + 1-row minimum floor check
            // With filter "Wide", only 1 row is visible -> fitted width must still respect MIN_POPUP_WIDTH / northPanel floor!
            KeyStroke altShiftR = KeyStroke.getKeyStroke(KeyEvent.VK_R, altShiftMask);
            Object actionKey = ui.panel().getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(altShiftR);
            assertNotNull("WHEN_ANCESTOR_OF_FOCUSED_COMPONENT must bind Alt+Shift+R", actionKey);
            Action swingFitAction = ui.panel().getActionMap().get(actionKey);
            assertNotNull(swingFitAction);

            filterEditor.setText(":IsolatedFloorCheckScalar");
            assertEquals(1, ui.table().getRowCount());
            swingFitAction.actionPerformed(new ActionEvent(filterEditor, ActionEvent.ACTION_PERFORMED, "altShiftR"));
            Rectangle oneRowBounds = simulatedPopupBounds.get();
            assertTrue("1-row fitted popup width must never collapse narrower than header floor: " + oneRowBounds,
                oneRowBounds.width >= ui.northPanel().getPreferredSize().width);
            assertTrue("1-row fitted popup height must respect minimum height floor: " + oneRowBounds,
                oneRowBounds.height >= 260);

            // 6. Header Context Menu Item 4 invokes window fit directly, while 0-unlocked-columns Alt+R jumps straight to Stage 2
            var menu = ui.columnManager().buildHeaderContextMenu(0);
            assertEquals(4, menu.getComponentCount());
            JMenuItem fitMenuItem = (JMenuItem) menu.getComponent(3);
            assertEquals("Fit Window to Rows & Columns (Alt+Shift+R)", fitMenuItem.getText());
            int beforeMenuClicks = applyBoundsCount.get();
            fitMenuItem.doClick();
            assertEquals(beforeMenuClicks + 1, applyBoundsCount.get());

            for (int col = 0; col < 4; col++) {
                var lock = (JCheckBoxMenuItem) ui.columnManager().buildHeaderContextMenu(col).getComponent(0);
                if (!lock.isSelected()) {
                    lock.doClick();
                }
            }
            assertFalse(ui.columnManager().hasAnyUnlockedColumn());
            int beforeZeroUnlockedAltR = applyBoundsCount.get();
            firePopupAction(ui.rightSizeAllUnlockedAction());
            assertEquals("When 0 columns are unlocked, Alt+R must advance directly to Stage 2 window fit",
                beforeZeroUnlockedAltR + 1, applyBoundsCount.get());
        } finally {
            Disposer.dispose(disposable);
        }
    }

    private static String getHoverHtml(StvnDocumentationProvider provider, Editor editor, PsiFile file, int offset) {
        PsiElement leaf = file.findElementAt(offset);
        assertNotNull("Expected PSI element at offset " + offset, leaf);
        PsiElement docElem = provider.getCustomDocumentationElement(editor, file, leaf, offset);
        PsiElement target = docElem != null ? docElem : leaf;
        String html = provider.generateDoc(target, leaf);
        assertNotNull("Expected non-null hover HTML at offset " + offset, html);
        return html;
    }

    private static StvnNamespaceSymbolEntry findSymbol(List<StvnNamespaceSymbolEntry> entries, String name) {
        for (StvnNamespaceSymbolEntry entry : entries) {
            if (name.equals(entry.name())) {
                return entry;
            }
        }
        fail("Missing expected symbol '" + name + "' in: " + entries.stream().map(StvnNamespaceSymbolEntry::name).toList());
        throw new AssertionError();
    }

    private static boolean isPopupActionPresentationEnabled(AnAction action) {
        AnActionEvent event = TestActionEvent.createTestEvent(action);
        action.update(event);
        return event.getPresentation().isEnabled();
    }

    private static void firePopupAction(AnAction action) {
        AnActionEvent event = TestActionEvent.createTestEvent(action);
        action.update(event);
        if (event.getPresentation().isEnabled()) {
            action.actionPerformed(event);
        }
    }

    private static void doLayoutRecursively(Component component) {
        component.doLayout();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                doLayoutRecursively(child);
            }
        }
    }

    private static List<ContractRow> loadContractRows(Path contractPath) throws IOException {
        String content = Files.readString(contractPath, StandardCharsets.UTF_8);
        var compiled = StvnCompiler.compile(content, contractPath.toAbsolutePath().toString(), StvnParserConfig.DEFAULT);
        assertFalse("Contract must compile cleanly: " + compiled.diagnostics(), compiled.hasErrors());
        StvnValue payload = compiled.document().orElseThrow().requirePayload();
        StvnValue.StvnSeq seq = (StvnValue.StvnSeq) payload;
        List<ContractRow> rows = new ArrayList<>();
        for (StvnValue elem : seq.elements()) {
            StvnValue.StvnTuple tup = (StvnValue.StvnTuple) elem;
            String code = ((StvnValue.StvnEnum) tup.elements().get(0)).variantTag().substring(1);
            String sym = ((StvnValue.StvnEnum) tup.elements().get(1)).variantTag().substring(1);
            int start = ((StvnValue.StvnInt) tup.elements().get(5)).value().intValueExact();
            int end = ((StvnValue.StvnInt) tup.elements().get(6)).value().intValueExact();
            String msg = ((StvnValue.StvnString) tup.elements().get(7)).value();
            rows.add(new ContractRow(code, sym, start, end, msg));
        }
        return rows;
    }
}
