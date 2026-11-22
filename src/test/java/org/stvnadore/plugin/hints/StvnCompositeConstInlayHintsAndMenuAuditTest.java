package org.stvnadore.plugin.hints;

import com.intellij.codeInsight.hints.InlayHintsSink;
import com.intellij.codeInsight.hints.presentation.InlayPresentation;
import com.intellij.codeInsight.hints.presentation.RootInlayPresentation;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.plugin.actions.StvnCopyHermeticPayloadCompactAction;
import org.stvnadore.plugin.actions.StvnCopyHermeticPayloadPrettyAction;
import org.stvnadore.plugin.actions.StvnCopyHermeticSchemaCompactCasAction;
import org.stvnadore.plugin.actions.StvnCopyHermeticSchemaPrettyAction;
import org.stvnadore.plugin.actions.StvnReplaceBufferCompactIrAction;
import org.stvnadore.plugin.actions.StvnReplaceBufferPrettyIrAction;
import org.stvnadore.plugin.browser.StvnNamespaceScope;
import org.stvnadore.plugin.browser.StvnNamespaceSymbolCollector;
import org.stvnadore.plugin.browser.StvnNamespaceSymbolEntry;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.AliasExpansionMode;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.TupleLiteral;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.Value;

import java.awt.datatransfer.DataFlavor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end IntelliJ Platform verification suite for Composite, Collection &amp; Sum {@code #CONST}
 * Inlay-Hint Totality, Multi-Hop Clickable Nominal Chains, and {@code STVN} Menu Projection Audit
 * ({@code CCIH-01}, {@code CCIH-02}, and Mandatory Safeguards 1 through 4).
 */
@NullMarked
public final class StvnCompositeConstInlayHintsAndMenuAuditTest extends BasePlatformTestCase {

    private static final String STAGE_10A_ROV_UNITS = """
        {
          :meta {
            #name "gauntlet_v9_stage10_base_units"
            #domain "rov_nav"
            #kind #DEFS
          }
          :defs {
            :package :rov/waypoints {
              :WaypointTag { #minSize 3 #maxSize 32 } :String
              #HOME_WAYPOINT :WaypointTag "WP-BASE_00"
            }
            :package :rov/units {
              :DepthMeters { #minIncl 0.0 #maxIncl 12000.0 } :Float
              :HeadingDeg { #unsigned #maxExcl 360 } :Int
              #MAX_ABYSSAL_DEPTH :DepthMeters 10928.0
            }
          }
        }
        """;

    private static final String STAGE_10B_ROV_BRIDGE = """
        {
          :meta {
            #name "gauntlet_v9_stage10_mid_bridge"
            #domain "rov_nav"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :include [ "gauntlet_v9_stage10_base_units.rov_nav.stvn_d" ]
            :package :rov/nav {
              :use [
                :rov/waypoints { :WaypointTag :ImportedWaypointTag }
                :rov/units { #strip }
              ]
              :SafeDepthMeters { #maxIncl 11000.0 } :DepthMeters
              :CompassHeading :HeadingDeg
              #ABYSSAL_LIMIT :SafeDepthMeters 10928.0
              :NavFix :Tuple( :ImportedWaypointTag :SafeDepthMeters :CompassHeading )
              #DEEP_TEST_FIX :NavFix ( "WP-TRENCH_01" #ABYSSAL_LIMIT 180 )
            }
          }
        }
        """;

    private static final String STAGE_10C_TEMPLATE = """
        {
          :meta {
            #name "gauntlet_v9_stage10_modular_consumer"
            #domain "rov_nav"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [
              "gauntlet_v9_stage10_base_units.rov_nav.stvn_d"
              "gauntlet_v9_stage10_mid_bridge.rov_nav.stvn_di"
            ]
            :use [
              %s
              :rov/units { #strip }
              :rov/waypoints { #strip }
              :rov/nav { #strip } { :NavFix :BridgeNavFix }
            ]
            :StrictDockTag :WaypointTag
            :OptNav :Option( :BridgeNavFix )
            :NavUnion :Union( :Int :BridgeNavFix )
            :Stage10ModularMatrix :Tuple(
              :BridgeNavFix
              :BridgeNavFix
              :DepthMeters
              :HeadingDeg
              :OptNav
              :Option( :BridgeNavFix )
              :NavUnion
              :StrictDockTag
            )
          }
          :type :Stage10ModularMatrix
          :body (
            #DEEP_TEST_FIX
            ( #HOME_WAYPOINT 250.5 90 )
            #MAX_ABYSSAL_DEPTH
            180
            #DEEP_TEST_FIX
            #DEEP_TEST_FIX
            #DEEP_TEST_FIX
            %s
          )
        }
        """;

    private static final String HEALED_STAGE_12_STVN = """
        {
          :meta {
            #name "gauntlet_v9_stage12_recursive_tree"
            #domain "core_test"
            #kind #BODY
          }
          :defs {
            :TreeLabel { #minSize 1 #maxSize 32 } :String
            :TreeNode :Tuple(
              :TreeLabel
              :Seq( :TreeNode )
            )
            :BoundedPort { #unsigned #size 16 #minIncl 1024 #maxExcl 49152 } :Int
            :Stage12Envelope :Tuple(
              :TreeNode
              :BoundedPort
            )
          }
          :type :Stage12Envelope
          :body (
            ( "root" [ ( "child-ok" [] ) ( "leaf-ok" [ ( "grandchild" [] ) ] ) ] )
            8080
          )
        }
        """;

    private boolean savedUseLongFormSumTypes = true;
    private boolean savedShowTypeHints = true;
    private AliasExpansionMode savedAliasExpansionMode = AliasExpansionMode.FULL;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        var styleSettings = StvnSettings.getInstance(getProject());
        savedUseLongFormSumTypes = styleSettings.getState().useLongFormSumTypes;
        styleSettings.getState().useLongFormSumTypes = true;

        var projSettings = StvnProjectSettings.getInstance(getProject());
        savedShowTypeHints = projSettings.getState().showTypeHints;
        savedAliasExpansionMode = projSettings.getState().aliasExpansionMode;
        projSettings.getState().showTypeHints = true;
        projSettings.getState().aliasExpansionMode = AliasExpansionMode.FULL;
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            StvnSettings.getInstance(getProject()).getState().useLongFormSumTypes = savedUseLongFormSumTypes;
            var projSettings = StvnProjectSettings.getInstance(getProject());
            projSettings.getState().showTypeHints = savedShowTypeHints;
            projSettings.getState().aliasExpansionMode = savedAliasExpansionMode;
        } finally {
            super.tearDown();
        }
    }

    public void testStage10RovNavCompositeConstInlayHintsStateBStateAMultiHopClickabilityAndSlot8E027Suppression() {
        var projSettings = StvnProjectSettings.getInstance(getProject());
        AliasExpansionMode prevMode = projSettings.getState().aliasExpansionMode;
        boolean prevShow = projSettings.getState().showTypeHints;

        try {
            var file10a = myFixture.addFileToProject(
                "stage10_rov/gauntlet_v9_stage10_base_units.rov_nav.stvn_d",
                STAGE_10A_ROV_UNITS
            );
            assertNotNull(file10a);
            var file10b = myFixture.addFileToProject(
                "stage10_rov/gauntlet_v9_stage10_mid_bridge.rov_nav.stvn_di",
                STAGE_10B_ROV_BRIDGE
            );
            assertNotNull(file10b);

            // 1. State B (Pass-3-Suspended via sibling :use traps + secondary :defs suspender, plus Slot 8 #HOME_WAYPOINT E027 trap)
            String stateBSrc = STAGE_10C_TEMPLATE
                .formatted(":rov/missing_trap { #strip }\n              :rov/units/", "#HOME_WAYPOINT")
                .replace(":type :Stage10ModularMatrix", ":defs { :SuspendPass3Trap :Int }\n          :type :Stage10ModularMatrix");
            var file10cStateB = myFixture.addFileToProject(
                "stage10_rov/gauntlet_v9_stage10_modular_consumer.rov_nav.stvn_i",
                stateBSrc
            );
            myFixture.configureFromExistingVirtualFile(file10cStateB.getVirtualFile());
            assertTrue(
                "State B fixture must suspend Pass 3 payload lowering",
                StvnCompiledDocumentCache.getSnapshot(file10cStateB).document().flatMap(StvnDocument::payload).isEmpty()
            );

            List<Value> stateBSlots = extractOuterBodyTupleSlots(file10cStateB);
            assertEquals(8, stateBSlots.size());

            // Safeguard 1: Verify all 5 AliasExpansionModes + showTypeHints toggle in State B
            Map<AliasExpansionMode, @Nullable String> stateBModeBadges = evaluateSlotAcrossExpansionModes(stateBSlots.get(0));
            assertEquals(":BridgeNavFix (-> :rov/nav/NavFix -> :Tuple)", stateBModeBadges.get(AliasExpansionMode.FULL));
            assertEquals(":BridgeNavFix (-> :rov/nav/NavFix)", stateBModeBadges.get(AliasExpansionMode.FIRST_HOP));
            assertEquals(":BridgeNavFix (-> :Tuple)", stateBModeBadges.get(AliasExpansionMode.ALIAS_TO_BASE));
            assertEquals(":BridgeNavFix", stateBModeBadges.get(AliasExpansionMode.SYMBOL_ONLY));
            assertNull("NONE mode must suppress composite #CONST badge in State B", stateBModeBadges.get(AliasExpansionMode.NONE));

            projSettings.getState().showTypeHints = false;
            assertNull("Disabling showTypeHints must suppress resolveValueType on composite #CONST",
                StvnTypeResolver.resolveValueType(stateBSlots.get(0)));
            assertTrue("Disabling showTypeHints must emit 0 collector hints",
                collectInlayMap(file10cStateB).isEmpty());
            projSettings.getState().showTypeHints = true;
            projSettings.getState().aliasExpansionMode = AliasExpansionMode.FULL;

            Map<Integer, String> stateBInlays = collectInlayMap(file10cStateB);
            // Slot 1 (#DEEP_TEST_FIX), Slot 2 ((#HOME_WAYPOINT 250.5 90)), Slot 3 (#MAX_ABYSSAL_DEPTH), Slots 5..7 (sums)
            assertEquals(":BridgeNavFix (-> :rov/nav/NavFix -> :Tuple)",
                stateBInlays.get(stateBSlots.get(0).getTextRange().getEndOffset()));
            assertNotNull("Slot 2 closing ')' must retain its inlay hint in State B",
                stateBInlays.get(stateBSlots.get(1).getTextRange().getEndOffset()));
            TupleLiteral slot2TupleB = stateBSlots.get(1).getCollectionValue().getTupleLiteral();
            assertNotNull(slot2TupleB);
            assertNotNull("Slot 2 inner #HOME_WAYPOINT must retain its inlay hint in State B",
                stateBInlays.get(slot2TupleB.getValueList().get(0).getTextRange().getEndOffset()));
            assertNotNull("Slot 3 #MAX_ABYSSAL_DEPTH must retain its inlay hint in State B",
                stateBInlays.get(stateBSlots.get(2).getTextRange().getEndOffset()));
            assertEquals(":OptNav [#Some] (-> :BridgeNavFix -> :rov/nav/NavFix -> :Tuple)",
                stateBInlays.get(stateBSlots.get(4).getTextRange().getEndOffset()));
            assertEquals(":Option( :BridgeNavFix ) [#Some]",
                stateBInlays.get(stateBSlots.get(5).getTextRange().getEndOffset()));
            assertEquals(":NavUnion [#2] (-> :BridgeNavFix -> :rov/nav/NavFix -> :Tuple)",
                stateBInlays.get(stateBSlots.get(6).getTextRange().getEndOffset()));

            // Safeguard 2 (State B): Slot 8 #HOME_WAYPOINT on :StrictDockTag (E027) must suppress its inlay hint
            assertNull("Slot 8 E027 #HOME_WAYPOINT must suppress inlay hint in State B",
                stateBInlays.get(stateBSlots.get(7).getTextRange().getEndOffset()));
            assertNull("Slot 8 E027 #HOME_WAYPOINT resolveValueType must be null in State B",
                StvnTypeResolver.resolveValueType(stateBSlots.get(7)));

            // Safeguard 3 (State B): Verify independent clickable PSI targets on Slot 1 and Slot 5
            assertMultiHopClickableSegmentsOnSlot1AndSlot5(stateBSlots.get(0), stateBSlots.get(4));

            // 2. State A with Slot 8 E027 active (:use trap removed -> Pass 3 runs and emits E027 on Slot 8)
            String stateAE027Src = STAGE_10C_TEMPLATE.formatted("", "#HOME_WAYPOINT");
            var file10cStateAE027 = myFixture.configureByText(
                "gauntlet_v9_stage10_modular_consumer.rov_nav.stvn_i",
                stateAE027Src
            );
            // Write into stage10_rov directory so relative :include resolves
            var vfStateAE027 = myFixture.addFileToProject(
                "stage10_rov/consumer_state_a_e027.rov_nav.stvn_i",
                stateAE027Src.replace(
                    "#name \"gauntlet_v9_stage10_modular_consumer\"",
                    "#name \"consumer_state_a_e027\""
                )
            );
            assertNotNull(file10cStateAE027);
            myFixture.configureFromExistingVirtualFile(vfStateAE027.getVirtualFile());
            assertTrue(
                "State A must produce lowered Pass 3 payload",
                StvnCompiledDocumentCache.getSnapshot(vfStateAE027).document().flatMap(StvnDocument::payload).isPresent()
            );
            var e027Highlights = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .toList();
            assertEquals("Expected exact 1 E027 error on Slot 8 #HOME_WAYPOINT: " + e027Highlights, 1, e027Highlights.size());
            assertTrue(e027Highlights.get(0).getDescription().contains("E027"));

            List<Value> stateASlots = extractOuterBodyTupleSlots(vfStateAE027);
            Map<AliasExpansionMode, @Nullable String> stateAModeBadges = evaluateSlotAcrossExpansionModes(stateASlots.get(0));
            assertEquals("State A and State B must produce byte-for-byte identical badges across all 5 AliasExpansionModes",
                stateBModeBadges, stateAModeBadges);

            Map<Integer, String> stateAInlays = collectInlayMap(vfStateAE027);
            assertEquals(":BridgeNavFix (-> :rov/nav/NavFix -> :Tuple)",
                stateAInlays.get(stateASlots.get(0).getTextRange().getEndOffset()));
            assertEquals(":rov/nav/NavFix",
                stateAInlays.get(stateASlots.get(1).getTextRange().getEndOffset()));
            assertEquals(":rov/units/DepthMeters (-> :Float)",
                stateAInlays.get(stateASlots.get(2).getTextRange().getEndOffset()));
            assertEquals(":OptNav [#Some] (-> :BridgeNavFix -> :rov/nav/NavFix -> :Tuple)",
                stateAInlays.get(stateASlots.get(4).getTextRange().getEndOffset()));
            assertEquals(":Option( :rov/nav/NavFix ) [#Some]",
                stateAInlays.get(stateASlots.get(5).getTextRange().getEndOffset()));
            assertEquals(":NavUnion [#2] (-> :BridgeNavFix -> :rov/nav/NavFix -> :Tuple)",
                stateAInlays.get(stateASlots.get(6).getTextRange().getEndOffset()));
            assertNull("Safeguard 2 (State A): Slot 8 E027 #HOME_WAYPOINT must suppress inlay hint",
                stateAInlays.get(stateASlots.get(7).getTextRange().getEndOffset()));

            assertMultiHopClickableSegmentsOnSlot1AndSlot5(stateASlots.get(0), stateASlots.get(4));
        } finally {
            projSettings.getState().aliasExpansionMode = prevMode;
            projSettings.getState().showTypeHints = prevShow;
        }
    }

    public void testAllFiveBodyConstCategoriesSeqSetMapWholeSumsEnumsAndErrorSuppression() {
        String source = """
            {
              :meta {
                #name "five_category_const_matrix"
                #kind #BODY
              }
              :defs {
                :Mode :Enum [ #ACTIVE #IDLE #FAULT ]
                :ActiveMode { #filterIncl [ #ACTIVE #IDLE ] } :Mode
                :PrimaryPort { #unsigned #size 16 #minIncl 1 } :Int
                :FallbackPort { #unsigned #size 16 #minIncl 1 } :Int
                :RetryMillis { #unsigned #size 16 #minIncl 10 #maxExcl 5000 } :Int
                :FixTuple :Tuple( :String :Int )
                :FixSeq :Seq( :Int )
                :TagSet :Set( :String )
                :RouteMap :Map( :String :Int )
                :OptFix :Option( :FixTuple )
                :InnerOption :Option( :RetryMillis )
                :OuterOption :Option( :InnerOption )
                :FixUnion :Union( :Int :FixTuple )

                #CONST_TUPLE :FixTuple ( "fix-1" 42 )
                #CONST_SEQ :FixSeq [ 10 20 ]
                #CONST_SET :TagSet [ "alpha" "beta" ]
                #CONST_MAP :RouteMap { [ "k1" 1 ] }
                #ANON_TUPLE :Tuple( :String :Boolean ) ( "anon" #TRUE )
                #IRREDUCIBLE_DEF_NONE :OptFix #None
                #INNER_DISPATCH :InnerOption #Some 250
                #FALLBACK_DEFAULT :FallbackPort 8443
                #DEFAULT_MODE :Mode #ACTIVE
                #SUBSET_MODE :ActiveMode #IDLE

                :Matrix :Tuple(
                  :FixTuple
                  :FixSeq
                  :TagSet
                  :RouteMap
                  :Tuple( :String :Boolean )
                  :OptFix
                  :OuterOption
                  :OptFix
                  :FixUnion
                  :Union( :PrimaryPort :FallbackPort )
                  :Mode
                  :Mode
                  :ActiveMode
                )
              }
              :type :Matrix
              :body (
                #CONST_TUPLE
                #CONST_SEQ
                #CONST_SET
                #CONST_MAP
                #ANON_TUPLE
                #IRREDUCIBLE_DEF_NONE
                #INNER_DISPATCH
                #CONST_TUPLE
                #CONST_TUPLE
                #FALLBACK_DEFAULT
                #ACTIVE
                #DEFAULT_MODE
                #SUBSET_MODE
              )
            }
            """;
        var file = myFixture.configureByText("five_category_const_matrix.stvn", source);
        var errors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue("Valid 5-category #CONST matrix must compile with 0 errors: " + errors, errors.isEmpty());

        List<Value> slots = extractOuterBodyTupleSlots(file);
        assertEquals(13, slots.size());
        Map<Integer, List<String>> multiMap = collectInlayMultiMap(file);

        String[] expected = {
            ":FixTuple (-> :Tuple)",
            ":FixSeq (-> :Seq)",
            ":TagSet (-> :Set)",
            ":RouteMap (-> :Map)",
            ":Tuple( :String :Boolean )",
            ":OptFix #None",
            ":OuterOption [#Some] (-> :InnerOption #Some (-> :RetryMillis -> :Int))",
            ":OptFix [#Some] (-> :FixTuple -> :Tuple)",
            ":FixUnion [#2] (-> :FixTuple -> :Tuple)",
            ":Union( :PrimaryPort :FallbackPort ) [#2]",
            ":Mode (-> :Enum)",
            ":Mode (-> :Enum)",
            ":ActiveMode (-> :Mode -> :Enum)"
        };
        for (int i = 0; i < expected.length; i++) {
            int endOffset = slots.get(i).getTextRange().getEndOffset();
            List<String> atOffset = multiMap.get(endOffset);
            assertNotNull("Expected inlay hint on slot " + i + " (" + slots.get(i).getText() + ")", atOffset);
            assertEquals("Expected strictly 1 inlay hint (0 duplicates) on slot " + i, 1, atOffset.size());
            assertEquals(expected[i], atOffset.get(0));
        }

        // Whole-sum #IRREDUCIBLE_DEF_NONE must expose 0 clickable inferred steps
        assertTrue(
            "#IRREDUCIBLE_DEF_NONE must not expose clickable inferred sum steps",
            StvnTypeResolver.resolveSumTrajectorySteps(slots.get(5)).stream().noneMatch(StvnTypeResolver.SumTrajectoryStep::inferred)
        );

        // Materializing inferred [#Some] on #CONST_TUPLE in :OptFix (slot 7) and [#2] in :FixUnion (slot 8) compiles cleanly
        assertTrue(StvnInlayHintsCollector.materializeInferredSumStep(slots.get(8), 0));
        List<Value> updatedSlots = extractOuterBodyTupleSlots(myFixture.getFile());
        assertTrue(StvnInlayHintsCollector.materializeInferredSumStep(updatedSlots.get(7), 0));
        String materializedDoc = myFixture.getFile().getText();
        assertTrue(materializedDoc.contains("#Some #CONST_TUPLE"));
        assertTrue(materializedDoc.contains("#2 #CONST_TUPLE"));
        assertCompilesZeroErrorsAndZeroWarnings(materializedDoc, "five_category_const_matrix.stvn");

        // Verify State B (Pass-3-Suspended via secondary :defs) parity on #IRREDUCIBLE_DEF_NONE and branded #FALLBACK_DEFAULT
        String suspendedMatrix = source.replace(":type :Matrix", ":defs { :ExtraSuspender :Int }\n  :type :Matrix");
        var suspFile = myFixture.configureByText("five_category_suspended.stvn", suspendedMatrix);
        assertTrue(StvnCompiledDocumentCache.getSnapshot(suspFile).document().flatMap(StvnDocument::payload).isEmpty());
        List<Value> suspSlots = extractOuterBodyTupleSlots(suspFile);
        assertEquals(":OptFix #None", StvnTypeResolver.resolveValueType(suspSlots.get(5)));
        assertTrue(StvnTypeResolver.resolveSumTrajectorySteps(suspSlots.get(5)).isEmpty());
        assertEquals(":Union( :PrimaryPort :FallbackPort ) [#2]", StvnTypeResolver.resolveValueType(suspSlots.get(9)));

        // Verify Category 5 error suppression (E022 unresolved, E028 wrong structural kind, E027 composite brand mismatch)
        String errorMatrix = """
            {
              :meta {
                #name "const_error_suppression"
                #kind #BODY
              }
              :defs {
                :PrimaryPort { #unsigned #size 16 #minIncl 1 } :Int
                :FallbackPort { #unsigned #size 16 #minIncl 1 } :Int
                :RetryCount { #unsigned #size 8 #minIncl 0 #maxExcl 10 } :Int
                :BrokenStr { #size 16 } :String
                :FixTuple :Tuple( :String :Int )
                #SCALAR_INT :Int 42
                #FALLBACK_SEQ :Seq( :FallbackPort ) [ 8443 ]
                #OVERFLOW_RAW :Int 99
                :ErrMatrix :Tuple(
                  :FixTuple
                  :FixTuple
                  :Seq( :PrimaryPort )
                  :RetryCount
                  :BrokenStr
                )
              }
              :type :ErrMatrix
              :body (
                #DOES_NOT_EXIST
                #SCALAR_INT
                #FALLBACK_SEQ
                #OVERFLOW_RAW
                "degraded_ok"
              )
            }
            """;
        var errFile = myFixture.configureByText("const_error_suppression.stvn", errorMatrix);
        List<Value> errSlots = extractOuterBodyTupleSlots(errFile);
        Map<Integer, String> errInlays = collectInlayMap(errFile);
        assertNull("E022 #DOES_NOT_EXIST must suppress inlay hint", errInlays.get(errSlots.get(0).getTextRange().getEndOffset()));
        assertNull("E028 #SCALAR_INT in :FixTuple must suppress inlay hint", errInlays.get(errSlots.get(1).getTextRange().getEndOffset()));
        assertNull("E027 #FALLBACK_SEQ in :Seq(:PrimaryPort) must suppress inlay hint", errInlays.get(errSlots.get(2).getTextRange().getEndOffset()));
        assertEquals("E012 unbranded #OVERFLOW_RAW must recover slot hint",
            ":RetryCount (-> :Int)", errInlays.get(errSlots.get(3).getTextRange().getEndOffset()));
        assertEquals("E008 degraded schema must retain degraded prefix hint",
            "\u26A0 :BrokenStr (-> :String)", errInlays.get(errSlots.get(4).getTextRange().getEndOffset()));
    }

    public void testHealedStage10cAndStage12MenuProjectionsPostCanonicalizationInlaysAndNamespaceBrowser() throws Exception {
        var projSettings = StvnProjectSettings.getInstance(getProject());
        boolean prevSuppress = projSettings.getState().suppressCanonicalizationWarning;
        projSettings.getState().suppressCanonicalizationWarning = true;

        try {
            myFixture.addFileToProject(
                "menu_audit/gauntlet_v9_stage10_base_units.rov_nav.stvn_d",
                STAGE_10A_ROV_UNITS
            );
            myFixture.addFileToProject(
                "menu_audit/gauntlet_v9_stage10_mid_bridge.rov_nav.stvn_di",
                STAGE_10B_ROV_BRIDGE
            );
            // Include ONLY 10B in 10C to verify transitive 10C -> 10B -> 10A :include resolution in Namespace Browser!
            String healed10cSrc = STAGE_10C_TEMPLATE.formatted("", "\"WP-DOCK_01\"")
                .replace("\"gauntlet_v9_stage10_base_units.rov_nav.stvn_d\"\n      ", "");
            var file10c = myFixture.addFileToProject(
                "menu_audit/gauntlet_v9_stage10_modular_consumer.rov_nav.stvn_i",
                healed10cSrc
            );
            myFixture.configureFromExistingVirtualFile(file10c.getVirtualFile());
            var snapshot10c = StvnCompiledDocumentCache.getSnapshot(file10c);
            assertTrue(
                "Expected 0 diagnostics on healed Stage 10C, got: " + snapshot10c.diagnostics(),
                snapshot10c.diagnostics().isEmpty()
            );

            // 1. Menu Group 1: Browse Namespace Dependencies on Stage 10C (DEFS, TYPE, BODY scopes)
            List<StvnNamespaceSymbolEntry> defs10c = StvnNamespaceSymbolCollector.collectSymbols(file10c, StvnNamespaceScope.DEFS);
            List<StvnNamespaceSymbolEntry> type10c = StvnNamespaceSymbolCollector.collectSymbols(file10c, StvnNamespaceScope.TYPE);
            List<StvnNamespaceSymbolEntry> body10c = StvnNamespaceSymbolCollector.collectSymbols(file10c, StvnNamespaceScope.BODY);

            assertNotNull("Transitive 10C -> 10B -> 10A #strip :DepthMeters must appear in DEFS",
                findSymbol(defs10c, ":DepthMeters"));
            assertNotNull("Aliased :BridgeNavFix must appear in DEFS", findSymbol(defs10c, ":BridgeNavFix"));
            assertNull("Evicted raw :NavFix must NOT appear in DEFS", findSymbol(defs10c, ":NavFix"));
            assertNotNull("Aliased :BridgeNavFix must appear in TYPE", findSymbol(type10c, ":BridgeNavFix"));
            assertNotNull("Aliased :BridgeNavFix must appear in BODY (including :NavUnion/#OptNav slots)",
                findSymbol(body10c, ":BridgeNavFix"));
            assertNull("Evicted raw :NavFix must NOT leak into BODY", findSymbol(body10c, ":NavFix"));

            StvnNamespaceSymbolEntry deepFixEntry = findSymbol(body10c, "#DEEP_TEST_FIX");
            assertNotNull("BODY scope must include #DEEP_TEST_FIX", deepFixEntry);
            assertEquals(":rov/nav", deepFixEntry.source());
            assertEquals(":NavFix", deepFixEntry.typeStructure());
            assertTrue("All Namespace Browser typeStructure strings must be single-line",
                defs10c.stream().noneMatch(e -> e.typeStructure().contains("\n")));

            // 2. Menu Group 2: Extract Hermetic Schema (.stvn_d) Pretty & Compact CAS on Stage 10C
            invokeAction(new StvnCopyHermeticSchemaPrettyAction(), file10c.getVirtualFile(), file10c);
            String schema10cPretty = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
            assertNotNull(schema10cPretty);
            assertTrue(schema10cPretty.contains("#kind #DEFS"));
            assertTrue(schema10cPretty.contains("#domain \"rov_nav\""));
            assertCompilesZeroErrorsAndZeroWarnings(schema10cPretty, "gauntlet_v9_stage10_modular_consumer.rov_nav.stvn_d");

            invokeAction(new StvnCopyHermeticSchemaCompactCasAction(), file10c.getVirtualFile(), file10c);
            String schema10cCompact = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
            assertNotNull(schema10cCompact);
            assertTrue(schema10cCompact.contains("#kind #DEFS"));
            assertCompilesZeroErrorsAndZeroWarnings(schema10cCompact, "gauntlet_v9_stage10_modular_consumer.rov_nav.stvn_d");

            // 3. Menu Group 3: Extract Hermetic Payload (.stvn) Pretty & Compact on Stage 10C + Post-Extraction Inlay Hints
            invokeAction(new StvnCopyHermeticPayloadPrettyAction(), file10c.getVirtualFile(), file10c);
            String payload10cPretty = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
            assertNotNull(payload10cPretty);
            assertTrue(payload10cPretty.contains("#kind #BODY"));
            assertFalse(payload10cPretty.contains("#DEEP_TEST_FIX"));
            assertTrue(payload10cPretty.contains("\"WP-TRENCH_01\""));
            assertTrue(payload10cPretty.contains("10928.0"));
            assertCompilesZeroErrorsAndZeroWarnings(payload10cPretty, "gauntlet_v9_stage10_modular_consumer.rov_nav.stvn");

            invokeAction(new StvnCopyHermeticPayloadCompactAction(), file10c.getVirtualFile(), file10c);
            String payload10cCompact = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
            assertNotNull(payload10cCompact);
            assertTrue(payload10cCompact.contains("#kind #BODY"));
            assertCompilesZeroErrorsAndZeroWarnings(payload10cCompact, "gauntlet_v9_stage10_modular_consumer.rov_nav.stvn");

            // Open extracted Pretty .stvn and verify inlay hints + multi-hop clickability on inlined ("WP-TRENCH_01" 10928.0 180)
            var extractedPayloadFile = myFixture.configureByText(
                "gauntlet_v9_stage10_modular_consumer.rov_nav.stvn",
                payload10cPretty
            );
            List<Value> extractedSlots = extractOuterBodyTupleSlots(extractedPayloadFile);
            Value inlinedSlot1 = extractedSlots.get(0);
            assertEquals(":rov/nav/NavFix", StvnTypeResolver.resolveValueType(inlinedSlot1));
            TupleLiteral inlinedTuple = inlinedSlot1.getCollectionValue().getTupleLiteral();
            assertNotNull(inlinedTuple);
            List<Value> inlinedFields = inlinedTuple.getValueList();
            assertEquals(3, inlinedFields.size());
            assertEquals(":rov/waypoints/WaypointTag (-> :String)", StvnTypeResolver.resolveValueType(inlinedFields.get(0)));
            String depthBadge = StvnTypeResolver.resolveValueType(inlinedFields.get(1));
            assertEquals(":rov/nav/SafeDepthMeters (-> :Float)", depthBadge);
            assertEquals(":rov/nav/CompassHeading (-> :Int)", StvnTypeResolver.resolveValueType(inlinedFields.get(2)));

            List<StvnInlayHintsCollector.ClickableTypeSegment> depthLinks =
                StvnInlayHintsCollector.extractBoundClickableTypeSegments(inlinedFields.get(1), depthBadge);
            assertEquals(1, depthLinks.size());
            assertEquals(":rov/nav/SafeDepthMeters", depthLinks.get(0).tokenText());
            assertNotNull(PsiTreeUtil.getParentOfType(depthLinks.get(0).targetElement(), TypeDefinition.class, false));

            List<StvnNamespaceSymbolEntry> extractedDefs =
                StvnNamespaceSymbolCollector.collectSymbols(extractedPayloadFile, StvnNamespaceScope.DEFS);
            assertTrue("Canonicalized multi-line schemas must normalize to single-line in Namespace Browser",
                extractedDefs.stream().noneMatch(e -> e.typeStructure().contains("\n")));

            // 4. Menu Group 4: Canonicalize Active Buffer In-Place (Pretty & Compact) on Stage 10C (.stvn_i) and Stage 12 (.stvn)
            myFixture.configureFromExistingVirtualFile(file10c.getVirtualFile());
            invokeAction(new StvnReplaceBufferPrettyIrAction(), file10c.getVirtualFile(), file10c);
            Document doc10c = FileDocumentManager.getInstance().getDocument(file10c.getVirtualFile());
            assertNotNull(doc10c);
            assertTrue(doc10c.getText().contains("#kind #BODY_INCLUDE"));
            assertCompilesZeroErrorsAndZeroWarnings(doc10c.getText(), "gauntlet_v9_stage10_modular_consumer.rov_nav.stvn_i");

            invokeAction(new StvnReplaceBufferCompactIrAction(), file10c.getVirtualFile(), file10c);
            assertTrue(doc10c.getText().contains("#kind #BODY_INCLUDE"));
            assertCompilesZeroErrorsAndZeroWarnings(doc10c.getText(), "gauntlet_v9_stage10_modular_consumer.rov_nav.stvn_i");

            // Healed Stage 12 (.stvn with recursive :TreeNode)
            var file12 = myFixture.addFileToProject(
                "menu_audit/gauntlet_v9_stage12_recursive_tree.core_test.stvn",
                HEALED_STAGE_12_STVN
            );
            myFixture.configureFromExistingVirtualFile(file12.getVirtualFile());

            invokeAction(new StvnCopyHermeticSchemaPrettyAction(), file12.getVirtualFile(), file12);
            String schema12Pretty = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
            assertNotNull(schema12Pretty);
            assertTrue(schema12Pretty.contains("#kind #DEFS"));
            assertTrue(schema12Pretty.contains(":TreeNode"));
            assertCompilesZeroErrorsAndZeroWarnings(schema12Pretty, "gauntlet_v9_stage12_recursive_tree.core_test.stvn_d");

            invokeAction(new StvnCopyHermeticSchemaCompactCasAction(), file12.getVirtualFile(), file12);
            String schema12Compact = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
            assertNotNull(schema12Compact);
            assertCompilesZeroErrorsAndZeroWarnings(schema12Compact, "gauntlet_v9_stage12_recursive_tree.core_test.stvn_d");

            invokeAction(new StvnReplaceBufferPrettyIrAction(), file12.getVirtualFile(), file12);
            Document doc12 = FileDocumentManager.getInstance().getDocument(file12.getVirtualFile());
            assertNotNull(doc12);
            assertTrue(doc12.getText().contains("#kind #BODY"));
            assertCompilesZeroErrorsAndZeroWarnings(doc12.getText(), "gauntlet_v9_stage12_recursive_tree.core_test.stvn");

            List<Value> stage12Slots = extractOuterBodyTupleSlots(file12);
            assertEquals(":TreeNode", StvnTypeResolver.resolveValueType(stage12Slots.get(0)));
            assertEquals(":BoundedPort (-> :Int)", StvnTypeResolver.resolveValueType(stage12Slots.get(1)));
            List<StvnNamespaceSymbolEntry> body12 = StvnNamespaceSymbolCollector.collectSymbols(file12, StvnNamespaceScope.BODY);
            assertNotNull(findSymbol(body12, ":TreeNode"));
            assertNotNull(findSymbol(body12, ":TreeLabel"));
            assertNotNull(findSymbol(body12, ":BoundedPort"));

            invokeAction(new StvnReplaceBufferCompactIrAction(), file12.getVirtualFile(), file12);
            assertTrue(doc12.getText().contains("#kind #BODY"));
            assertCompilesZeroErrorsAndZeroWarnings(doc12.getText(), "gauntlet_v9_stage12_recursive_tree.core_test.stvn");
        } finally {
            projSettings.getState().suppressCanonicalizationWarning = prevSuppress;
        }
    }

    private void assertMultiHopClickableSegmentsOnSlot1AndSlot5(Value slot1, Value slot5) {
        var collector = new StvnInlayHintsCollector(myFixture.getEditor());
        String badge1 = ":BridgeNavFix (-> :rov/nav/NavFix -> :Tuple)";
        InlayPresentation pres1 = collector.buildInlayPresentation(slot1, badge1);
        assertEquals(badge1, pres1.toString());

        List<StvnInlayHintsCollector.ClickableTypeSegment> segs1 =
            StvnInlayHintsCollector.extractBoundClickableTypeSegments(slot1, badge1);
        assertEquals("Slot 1 must bind exact 2 clickable nominal/alias segments (:Tuple excluded)", 2, segs1.size());
        assertEquals(":BridgeNavFix", segs1.get(0).tokenText());
        assertTrue("1st hop :BridgeNavFix must target UseMapAlias in 10C",
            segs1.get(0).targetElement().getParent() instanceof UseMapAlias);
        assertEquals(":rov/nav/NavFix", segs1.get(1).tokenText());
        TypeDefinition navFixDef = PsiTreeUtil.getParentOfType(segs1.get(1).targetElement(), TypeDefinition.class, false);
        assertNotNull("2nd hop :rov/nav/NavFix must target TypeDefinition :NavFix in 10B", navFixDef);
        assertEquals("gauntlet_v9_stage10_mid_bridge.rov_nav.stvn_di", navFixDef.getContainingFile().getName());

        String badge5 = ":OptNav [#Some] (-> :BridgeNavFix -> :rov/nav/NavFix -> :Tuple)";
        InlayPresentation pres5 = collector.buildInlayPresentation(slot5, badge5);
        assertEquals(badge5, pres5.toString());

        List<StvnInlayHintsCollector.ClickableTypeSegment> segs5 =
            StvnInlayHintsCollector.extractBoundClickableTypeSegments(slot5, badge5);
        assertEquals("Slot 5 sum-wrapped #DEEP_TEST_FIX must bind all 3 nominal/alias hops", 3, segs5.size());
        assertEquals(":OptNav", segs5.get(0).tokenText());
        assertEquals(":BridgeNavFix", segs5.get(1).tokenText());
        assertEquals(":rov/nav/NavFix", segs5.get(2).tokenText());
    }

    private Map<AliasExpansionMode, @Nullable String> evaluateSlotAcrossExpansionModes(Value slot) {
        var projSettings = StvnProjectSettings.getInstance(getProject());
        AliasExpansionMode orig = projSettings.getState().aliasExpansionMode;
        Map<AliasExpansionMode, @Nullable String> out = new LinkedHashMap<>();
        try {
            for (AliasExpansionMode mode : AliasExpansionMode.values()) {
                projSettings.getState().aliasExpansionMode = mode;
                out.put(mode, StvnTypeResolver.resolveValueType(slot));
            }
        } finally {
            projSettings.getState().aliasExpansionMode = orig;
        }
        return out;
    }

    private List<Value> extractOuterBodyTupleSlots(PsiFile file) {
        var bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
        assertNotNull(bodyEntry);
        var bodyVal = bodyEntry.getValue();
        assertNotNull(bodyVal);
        assertNotNull(bodyVal.getCollectionValue());
        var tuple = bodyVal.getCollectionValue().getTupleLiteral();
        assertNotNull(tuple);
        return tuple.getValueList();
    }

    private Map<Integer, String> collectInlayMap(PsiFile file) {
        Map<Integer, String> byOffset = new LinkedHashMap<>();
        for (var entry : collectInlayMultiMap(file).entrySet()) {
            if (!entry.getValue().isEmpty()) {
                byOffset.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        return byOffset;
    }

    private Map<Integer, List<String>> collectInlayMultiMap(PsiFile file) {
        Map<Integer, List<String>> byOffset = new LinkedHashMap<>();
        var collector = new StvnInlayHintsCollector(myFixture.getEditor());
        collector.collect(file, myFixture.getEditor(), new InlayHintsSink() {
            @Override
            public void addInlineElement(
                int offset,
                boolean relatesToPrecedingText,
                @NotNull InlayPresentation presentation,
                boolean placeAtTheEndOfLine
            ) {
                byOffset.computeIfAbsent(offset, k -> new ArrayList<>()).add(presentation.toString());
            }

            @Override
            public void addBlockElement(
                int offset,
                boolean relatesToPrecedingText,
                boolean showAbove,
                int priority,
                @NotNull InlayPresentation presentation
            ) {
            }

            @Override
            public void addInlineElement(
                int offset,
                @NotNull RootInlayPresentation<?> presentation,
                @NotNull com.intellij.codeInsight.hints.HorizontalConstraints constraints
            ) {
            }

            @Override
            public void addBlockElement(
                int logicalLine,
                boolean showAbove,
                @NotNull RootInlayPresentation<?> presentation,
                @NotNull com.intellij.codeInsight.hints.BlockConstraints constraints
            ) {
            }
        });
        return byOffset;
    }

    private void invokeAction(AnAction action, VirtualFile vf, PsiFile psiFile) {
        var ctx = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, getProject())
            .add(CommonDataKeys.VIRTUAL_FILE, vf)
            .add(CommonDataKeys.PSI_FILE, psiFile)
            .add(CommonDataKeys.EDITOR, myFixture.getEditor())
            .build();
        action.actionPerformed(TestActionEvent.createTestEvent(action, ctx));
    }

    private static @Nullable StvnNamespaceSymbolEntry findSymbol(
        List<StvnNamespaceSymbolEntry> entries,
        String expectedName
    ) {
        return entries.stream()
            .filter(e -> expectedName.equals(e.name()))
            .findFirst()
            .orElse(null);
    }

    private static void assertCompilesZeroErrorsAndZeroWarnings(String content, String filePath) {
        var result = StvnCompiler.compileToResult(content, filePath, StvnParserConfig.DEFAULT);
        assertTrue(
            "Expected 0 compiler errors and 0 compiler warnings on " + filePath + ", got: " + result.diagnostics(),
            result.diagnostics().isEmpty()
        );
    }
}
