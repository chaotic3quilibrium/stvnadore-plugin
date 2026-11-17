package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.actions.StvnFlattenWorkspaceAction;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.hints.StvnInlayHintsCollector;
import org.stvnadore.plugin.navigation.StvnGotoDeclarationHandler;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.AliasExpansionMode;
import org.stvnadore.plugin.util.StvnTypePresentationUtil;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.EnumDef;
import org.stvnadore.psi.IncludeMapAlias;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * End-to-end IntelliJ Platform regression suite covering Gauntlet v8 Batch 4
 * ({@code B4-01} through {@code B4-05}: Stage 10 diamond module PSI parity,
 * Stage 10A constant capacity suppression, Stage 10C workspace flatten VFS indexing,
 * Stage 11 Track A {@code C4-01..C4-03} parity, and Stage 12 / Stage 1 reorder quick-fix DX).
 */
@NullMarked
public final class StvnGauntletV8Batch4CrossFilePsiAndReorderDxTest extends BasePlatformTestCase {

    private static final String STAGE_10A = """
        {
          :meta {
            #name "gauntlet_v8_stage10a_diamond_root_schema"
            #domain "core_test"
            #kind #DEFS
          }
          :defs {
            :package :satcom/uplink {
              #FRAME_ID_REGEX :String "^FRM\\\\.[0-9]{2}\\\\.[A-Z]{3}$"
              :FrameTag { #minSize 10 #maxSize 10 #regex #FRAME_ID_REGEX } :String
              #HEARTBEAT_FRAME :FrameTag "FRM.00.HBT"
              :FirmwareRelease { #minSize 3 #maxSize 24 } :String
              #ACTIVE_FIRMWARE :FirmwareRelease "REL-2026.11"
              :UplinkFreqMhz { #unsigned #size 16 #minIncl 2000 #maxExcl 30000 } :Int
              :StationCode { #minSize 4 #maxSize 16 } :String
              :LinkStatus :Enum[#LOCKED #ACQUIRING #LOST]
            }
            :DishAzimuth { #unsigned #size 16 #maxExcl 360 } :Int
            :PriorityLevel :Enum[#ROUTINE #URGENT]
          }
        }
        """;

    private static final String STAGE_10B = """
        {
          :meta {
            #name "gauntlet_v8_stage10b_diamond_mid_module"
            #domain "core_test"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :include [ "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d" ]
            :package :satcom/relay {
              :use [ :satcom/uplink { #strip } ]
              :RelayHop :Tuple(:FrameTag :UplinkFreqMhz :DishAzimuth)
              #DEFAULT_RELAY_FREQ :UplinkFreqMhz 8450
              :StationCode { #minSize 3 #maxSize 12 } :String
              :LinkStatus :Enum[#ACTIVE_RELAY #STANDBY_RELAY]
            }
          }
        }
        """;

    private static final String STAGE_10C = """
        {
          :meta {
            #name "gauntlet_v8_stage10c_diamond_app_payload"
            #domain "core_test"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d" ]
            :include [ "gauntlet_v8_stage10b_diamond_mid_module.core_test.stvn_di" ]
            :PriorityLevel :Enum[#CRITICAL_OVERRIDE #STANDARD_PASS]
            :use [ :satcom/uplink { #strip } {
                :StationCode :UplinkStationCode
                :LinkStatus :UplinkStatus
                :FirmwareRelease :ReleaseTag
                #ACTIVE_FIRMWARE #ACTIVE_RELEASE
              }
            ]
            :use [ :satcom/relay { #strip } {
                :LinkStatus :RelayStatus
              }
            ]
            :Stage10Envelope :Tuple(
              :DishAzimuth
              :PriorityLevel
              :UplinkStationCode
              :StationCode
              :UplinkStatus
              :RelayStatus
              :ReleaseTag
              :FrameTag
              :RelayHop
            )
          }
          :type :Stage10Envelope
          :body (
            185
            #CRITICAL_OVERRIDE
            "STN-MADRID"
            "RLY-04"
            #LOCKED
            #ACTIVE_RELAY
            #ACTIVE_RELEASE
            #HEARTBEAT_FRAME
            ("FRM.12.ACK" #DEFAULT_RELAY_FREQ 270)
          )
        }
        """;

    public void testB401Stage10aConstantDefinitionSuppressesUnadornedCapacityWarning() {
        myFixture.enableInspections(new StvnStringCapacityInspection());
        var file10a = myFixture.addFileToProject(
            "examples/gauntlet_v8/gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d",
            STAGE_10A
        );
        myFixture.configureFromExistingVirtualFile(file10a.getVirtualFile());
        var warnings = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .toList();
        assertTrue("Expected zero capacity warnings on Stage 10A #FRAME_ID_REGEX, got: " + warnings, warnings.isEmpty());
    }

    public void testB402Stage10cDiamondNavigationHoverEvictionAndInlayParity() {
        myFixture.addFileToProject(
            "examples/gauntlet_v8/gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d",
            STAGE_10A
        );
        myFixture.addFileToProject(
            "examples/gauntlet_v8/gauntlet_v8_stage10b_diamond_mid_module.core_test.stvn_di",
            STAGE_10B
        );
        var file10c = myFixture.addFileToProject(
            "examples/gauntlet_v8/gauntlet_v8_stage10c_diamond_app_payload.core_test.stvn_i",
            STAGE_10C
        );
        myFixture.configureFromExistingVirtualFile(file10c.getVirtualFile());

        var errors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue("Stage 10C must compile with 0 errors: " + errors, errors.isEmpty());

        String text = file10c.getText();
        int envelopeIdx = text.indexOf(":Stage10Envelope :Tuple(");
        int bodyIdx = text.indexOf(":body (");

        // 1-hop Ctrl+Click jumps across diamond :include & :use #strip
        assertGotoTypeDefInFile(file10c, text.indexOf(":DishAzimuth", envelopeIdx),
            "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d", ":DishAzimuth");
        assertGotoTypeDefInFile(file10c, text.indexOf(":FrameTag", envelopeIdx),
            "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d", ":FrameTag");
        assertGotoTypeDefInFile(file10c, text.indexOf(":RelayHop", envelopeIdx),
            "gauntlet_v8_stage10b_diamond_mid_module.core_test.stvn_di", ":RelayHop");
        // Scenario 1 (Local Priority) & Scenario 2 (Asymmetric Alias vs. Raw)
        assertGotoTypeDefInFile(file10c, text.indexOf(":PriorityLevel", envelopeIdx),
            "gauntlet_v8_stage10c_diamond_app_payload.core_test.stvn_i", ":PriorityLevel");
        assertGotoTypeDefInFile(file10c, text.indexOf(":StationCode", envelopeIdx),
            "gauntlet_v8_stage10b_diamond_mid_module.core_test.stvn_di", ":StationCode");

        // 1-hop :body scalar literal & #CONST / #ENUM jumps
        assertGotoTypeDefInFile(file10c, text.indexOf("185", bodyIdx),
            "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d", ":DishAzimuth");
        assertGotoTypeDefInFile(file10c, text.indexOf("270", bodyIdx),
            "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d", ":DishAzimuth");
        assertGotoTypeDefInFile(file10c, text.indexOf("\"STN-MADRID\"", bodyIdx) + 1,
            "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d", ":StationCode");
        assertGotoTypeDefInFile(file10c, text.indexOf("\"RLY-04\"", bodyIdx) + 1,
            "gauntlet_v8_stage10b_diamond_mid_module.core_test.stvn_di", ":StationCode");
        assertGotoTypeDefInFile(file10c, text.indexOf("\"FRM.12.ACK\"", bodyIdx) + 1,
            "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d", ":FrameTag");

        assertGotoConstDefInFile(file10c, text.indexOf("#HEARTBEAT_FRAME", bodyIdx),
            "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d", "#HEARTBEAT_FRAME");
        assertGotoConstDefInFile(file10c, text.indexOf("#DEFAULT_RELAY_FREQ", bodyIdx),
            "gauntlet_v8_stage10b_diamond_mid_module.core_test.stvn_di", "#DEFAULT_RELAY_FREQ");
        assertGotoEnumVariantInFile(file10c, text.indexOf("#LOCKED", bodyIdx),
            "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d", "#LOCKED");
        assertGotoEnumVariantInFile(file10c, text.indexOf("#ACTIVE_RELAY", bodyIdx),
            "gauntlet_v8_stage10b_diamond_mid_module.core_test.stvn_di", "#ACTIVE_RELAY");

        // 2-hop Ctrl+Click on renamed :use symbols
        assertTwoHopUseTypeAlias(file10c, text.indexOf(":ReleaseTag", envelopeIdx),
            "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d", ":FirmwareRelease");
        assertTwoHopUseTypeAlias(file10c, text.indexOf(":UplinkStationCode", envelopeIdx),
            "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d", ":StationCode");
        assertTwoHopUseTypeAlias(file10c, text.indexOf(":UplinkStatus", envelopeIdx),
            "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d", ":LinkStatus");
        assertTwoHopUseTypeAlias(file10c, text.indexOf(":RelayStatus", envelopeIdx),
            "gauntlet_v8_stage10b_diamond_mid_module.core_test.stvn_di", ":LinkStatus");
        assertTwoHopUseConstAlias(file10c, text.indexOf("#ACTIVE_RELEASE", bodyIdx),
            "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d", "#ACTIVE_FIRMWARE");

        // Rich Hover cards in :defs and :body (never unresolved fallback)
        var docProvider = new StvnDocumentationProvider();
        for (String sym : new String[]{
            ":DishAzimuth", ":FrameTag", ":RelayHop", ":StationCode",
            ":ReleaseTag", ":UplinkStationCode", ":UplinkStatus", ":RelayStatus"
        }) {
            String doc = generateHoverAt(docProvider, file10c, text.indexOf(sym, envelopeIdx));
            assertNotNull("Expected hover card for " + sym, doc);
            assertTrue("Expected Type Alias card for " + sym + ":\n" + doc, doc.contains("<b>Type Alias:</b>"));
            assertFalse("Must never render unresolved fallback for " + sym + ":\n" + doc,
                doc.contains("Active Constraints:</b> <i>None (defaults active)</i>"));
        }
        for (String bodySym : new String[]{
            "#ACTIVE_RELEASE", "#HEARTBEAT_FRAME", "#DEFAULT_RELAY_FREQ", "#LOCKED", "#ACTIVE_RELAY"
        }) {
            String doc = generateHoverAt(docProvider, file10c, text.indexOf(bodySym, bodyIdx));
            assertNotNull("Expected :body hover card for " + bodySym, doc);
            assertTrue("Expected Slot Schema row for " + bodySym + ":\n" + doc, doc.contains("<b>Slot Schema:</b>"));
            assertTrue("Expected Satisfied compliance status for " + bodySym + ":\n" + doc, doc.contains("Satisfied"));
        }
    }

    public void testB402Stage10cInlayHintsIncludeCrossFileBaseResolutionTraces() {
        myFixture.addFileToProject(
            "examples/gauntlet_v8/gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d",
            STAGE_10A
        );
        myFixture.addFileToProject(
            "examples/gauntlet_v8/gauntlet_v8_stage10b_diamond_mid_module.core_test.stvn_di",
            STAGE_10B
        );
        var file10c = myFixture.addFileToProject(
            "examples/gauntlet_v8/gauntlet_v8_stage10c_diamond_app_payload.core_test.stvn_i",
            STAGE_10C
        );
        myFixture.configureFromExistingVirtualFile(file10c.getVirtualFile());

        var bodyEntry = PsiTreeUtil.findChildOfType(file10c, BodyEntry.class);
        assertNotNull("Stage 10C BodyEntry must not be null", bodyEntry);
        var rootValue = bodyEntry.getValue();
        assertNotNull("Stage 10C root :body Value must not be null", rootValue);
        var collector = new StvnInlayHintsCollector(myFixture.getEditor());
        assertInlayHintBadge(collector, rootValue, ":Stage10Envelope");

        var outerTuple = rootValue.getCollectionValue() != null
            ? rootValue.getCollectionValue().getTupleLiteral()
            : null;
        assertNotNull("Stage 10C outer TupleLiteral must not be null", outerTuple);
        var outerSlots = outerTuple.getValueList();
        assertEquals("Expected 9 outer Stage10Envelope slots", 9, outerSlots.size());

        String[] expectedOuterHints = {
            ":DishAzimuth (-> :Int)",
            ":PriorityLevel (-> :Enum)",
            ":satcom/uplink/StationCode (-> :String)",
            ":satcom/relay/StationCode (-> :String)",
            ":satcom/uplink/LinkStatus (-> :Enum)",
            ":satcom/relay/LinkStatus (-> :Enum)",
            ":satcom/uplink/FirmwareRelease (-> :String)",
            ":satcom/uplink/FrameTag (-> :String)",
            ":satcom/relay/RelayHop"
        };
        for (int i = 0; i < expectedOuterHints.length; i++) {
            assertInlayHintBadge(collector, outerSlots.get(i), expectedOuterHints[i]);
        }

        var innerTuple = outerSlots.get(8).getCollectionValue() != null
            ? outerSlots.get(8).getCollectionValue().getTupleLiteral()
            : null;
        assertNotNull("Stage 10C RelayHop inner TupleLiteral must not be null", innerTuple);
        var innerSlots = innerTuple.getValueList();
        assertEquals("Expected 3 inner RelayHop slots", 3, innerSlots.size());
        assertInlayHintBadge(collector, innerSlots.get(0), ":satcom/uplink/FrameTag (-> :String)");
        assertInlayHintBadge(collector, innerSlots.get(1), ":satcom/uplink/UplinkFreqMhz (-> :Int)");
        assertInlayHintBadge(collector, innerSlots.get(2), ":DishAzimuth (-> :Int)");
    }

    private static void assertInlayHintBadge(
            StvnInlayHintsCollector collector,
            Value value,
            String expectedBadge
    ) {
        String resolved = StvnTypeResolver.resolveValueType(value);
        assertEquals("Unexpected resolved inlay type for '" + value.getText() + "'", expectedBadge, resolved);
        var formatted = StvnTypePresentationUtil.formatAliasChain(resolved, AliasExpansionMode.FULL);
        assertTrue("Formatted FULL inlay badge must be present for '" + value.getText() + "'", formatted.isPresent());
        assertEquals(expectedBadge, formatted.get());
        assertNotNull(collector.buildInlayPresentation(value, formatted.get()));
    }

    public void testB402Scenario3DualAliasEvictionAndTwoHopIncludeMapAlias() {
        myFixture.addFileToProject("examples/gauntlet_v8/gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d", STAGE_10A);
        myFixture.addFileToProject("examples/gauntlet_v8/gauntlet_v8_stage10b_diamond_mid_module.core_test.stvn_di", STAGE_10B);
        var trapSource = """
            {
              :meta {
                #name "stage10_eviction_and_include_alias"
                #domain "core_test"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :include [ "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d" { :DishAzimuth :LocalAzimuth } ]
                :include [ "gauntlet_v8_stage10b_diamond_mid_module.core_test.stvn_di" ]
                :use [ :satcom/uplink { #strip } { :LinkStatus :UplinkStatus #ACTIVE_FIRMWARE #ACTIVE_RELEASE } ]
                :use [ :satcom/relay { #strip } { :LinkStatus :RelayStatus } ]
                :TrapEvictedDualAliasRaw :LinkStatus
                :ValidIncludeAliased :LocalAzimuth
                #TRAP_EVICTED_CONST :String #ACTIVE_FIRMWARE
              }
            }
            """;
        var trapFile = myFixture.addFileToProject(
            "examples/gauntlet_v8/stage10_eviction_and_include_alias.core_test.stvn_di",
            trapSource
        );
        myFixture.configureFromExistingVirtualFile(trapFile.getVirtualFile());
        String text = trapFile.getText();

        // Scenario 3: Dual-aliased raw :LinkStatus and single-aliased raw #ACTIVE_FIRMWARE must resolve to null
        myFixture.getEditor().getCaretModel().moveToOffset(text.indexOf(":LinkStatus", text.indexOf(":TrapEvictedDualAliasRaw")));
        var dualAliasRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull(dualAliasRef);
        assertNull("Dual-aliased raw :LinkStatus must resolve to null", dualAliasRef.resolve());

        myFixture.getEditor().getCaretModel().moveToOffset(text.indexOf("#ACTIVE_FIRMWARE", text.indexOf("#TRAP_EVICTED_CONST")));
        var evictedConstRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull(evictedConstRef);
        assertNull("Renamed raw #ACTIVE_FIRMWARE must resolve to null", evictedConstRef.resolve());

        // 2-hop IncludeMapAlias navigation: Hop 1 -> IncludeMapAlias RHS; Hop 2 -> 10A :DishAzimuth
        var handler = new StvnGotoDeclarationHandler();
        int usageOffset = text.indexOf(":LocalAzimuth", text.indexOf(":ValidIncludeAliased"));
        var hop1 = handler.getGotoDeclarationTargets(trapFile.findElementAt(usageOffset), usageOffset, myFixture.getEditor());
        assertNotNull(hop1);
        assertEquals(1, hop1.length);
        assertTrue(hop1[0].getParent() instanceof IncludeMapAlias);

        int rhsOffset = hop1[0].getTextRange().getStartOffset();
        var hop2 = handler.getGotoDeclarationTargets(trapFile.findElementAt(rhsOffset), rhsOffset, myFixture.getEditor());
        assertNotNull(hop2);
        assertEquals(1, hop2.length);
        assertTrue(hop2[0] instanceof TypeDefinition td
            && td.getTypeKeyword() != null
            && ":DishAzimuth".equals(td.getTypeKeyword().getText())
            && "gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d".equals(td.getContainingFile().getName()));
    }

    public void testB403FlattenWorkspaceActionWritesNormalizedLfDefsAndRegistersIndexableFile() throws Exception {
        myFixture.addFileToProject("examples/gauntlet_v8/gauntlet_v8_stage10a_diamond_root_schema.core_test.stvn_d", STAGE_10A);
        myFixture.addFileToProject("examples/gauntlet_v8/gauntlet_v8_stage10b_diamond_mid_module.core_test.stvn_di", STAGE_10B);
        var file10c = myFixture.addFileToProject(
            "examples/gauntlet_v8/gauntlet_v8_stage10c_diamond_app_payload.core_test.stvn_i",
            STAGE_10C
        );
        myFixture.configureFromExistingVirtualFile(file10c.getVirtualFile());

        var flattenAction = new StvnFlattenWorkspaceAction();
        var ctx = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, getProject())
            .add(CommonDataKeys.EDITOR, myFixture.getEditor())
            .add(CommonDataKeys.PSI_FILE, file10c)
            .add(CommonDataKeys.VIRTUAL_FILE, file10c.getVirtualFile())
            .build();
        flattenAction.actionPerformed(TestActionEvent.createTestEvent(flattenAction, ctx));

        var outVf = file10c.getVirtualFile().getParent().findChild(
            "gauntlet_v8_stage10c_diamond_app_payload.core_test.stvn_d"
        );
        assertNotNull("Expected flattened 2-stem .stvn_d sibling in VFS", outVf);
        assertTrue("Flattened VFS file must be valid and indexed in project content",
            outVf.isValid() && ProjectFileIndex.getInstance(getProject()).isInContent(outVf));
        String flattenedText = new String(outVf.contentsToByteArray(), StandardCharsets.UTF_8);
        assertFalse("Flattened UTF-8 bytes must contain zero CR characters", flattenedText.contains("\r"));
        assertTrue("Flattened schema must carry #kind #DEFS", flattenedText.contains("#kind #DEFS"));

        // Verify cached-Document overwrite path updates cleanly without desynchronization
        assertNotNull(FileDocumentManager.getInstance().getDocument(outVf));
        flattenAction.actionPerformed(TestActionEvent.createTestEvent(flattenAction, ctx));
        assertTrue(outVf.isValid() && ProjectFileIndex.getInstance(getProject()).isInContent(outVf));
    }

    public void testB404TrackAC402AndC403Stage11HoverAndDegradedSchemaParity() throws Exception {
        var stage11Path = Paths.get(
            "src/test/resources/shared-fixtures/syntax/invalid/defs/inv_stage11_cyclic_coproduct_stems_and_symmetric_const_cycles.core_test.stvn_i"
        );
        assertTrue("Mirrored Stage 11 fixture must exist", Files.isRegularFile(stage11Path));
        String source = Files.readString(stage11Path);

        var degradedInspection = new StvnDegradedSchemaInspection();
        degradedInspection.highlightBodyValuesBoundToDegradedSchemas = true;
        myFixture.enableInspections(degradedInspection);

        var psiFile = myFixture.configureByText(
            "inv_stage11_cyclic_coproduct_stems_and_symmetric_const_cycles.core_test.stvn_i",
            source
        );
        long startNs = System.nanoTime();
        var highlights = myFixture.doHighlighting();
        var docProvider = new StvnDocumentationProvider();

        String unionHover = generateHoverAt(docProvider, psiFile, source.indexOf(":AllBranchesCyclicUnion :Union"));
        String pingHover = generateHoverAt(docProvider, psiFile, source.indexOf("#CONST_PING :MetricWeight"));
        String bodyDerivedHover = generateHoverAt(docProvider, psiFile, source.indexOf("#DERIVED_CONST", source.indexOf(":body (")));
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertNotNull(unionHover);
        assertTrue("Expected ungrounded Degraded Schema Warning on :AllBranchesCyclicUnion:\n" + unionHover,
            unionHover.contains("Degraded Schema Warning") && unionHover.contains("circular or unresolved lineage"));

        assertNotNull(pingHover);
        assertTrue("Expected Constant Cycle row on #CONST_PING:\n" + pingHover,
            pingHover.contains("<b>Constant Cycle:</b>") && pingHover.contains("#CONST_PING &rarr; #CONST_PONG &rarr; #CONST_PING"));

        assertNotNull(bodyDerivedHover);
        assertTrue("Expected Suspended (Circular Constant) on :body #DERIVED_CONST:\n" + bodyDerivedHover,
            bodyDerivedHover.contains("Suspended (Circular Constant)"));
        assertFalse("Must never render green Satisfied on cyclic #DERIVED_CONST:\n" + bodyDerivedHover,
            bodyDerivedHover.contains(">Satisfied<"));

        boolean foundUnionSlotWarning = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WEAK_WARNING)
            .anyMatch(h -> h.getDescription() != null
                && h.getDescription().contains("Value bound to degraded schema ':AllBranchesCyclicUnion' (payload evaluation is suspended for ungrounded schema)"));
        assertTrue("Expected PW006 ungrounded warning on :AllBranchesCyclicUnion slot; elapsed=" + elapsedMs + "ms",
            foundUnionSlotWarning);
    }

    public void testB405ReorderQuickFixAvailableAcrossAllAnchorsWithIntentionPreviewAndHighlightParity() {
        var source = """
            {
              :meta { // Opening meta header
                #kind #BODY // Primary kind
                #name "stage12_reorder_anchors"
              }
              :defs {
                :ScrambledCommentedString { // Opening-brace header comment
                  #regex "^TAG-[0-9]{2}$"
                  #preserveIndent #TRUE
                  #maxSize 6
                  #minSize 6
                } :String
                #SCRAMBLED_CONST {
                  #maxExcl 100
                  #unsigned
                } :Int 42
              }
              :type {
                #maxExcl 50
                #minIncl 1
              } :Int
              :body 10
            }
            """;

        // 0-inspection vs 26-inspection ERROR highlight parity check
        myFixture.configureByText("stage12_reorder_anchors.stvn", source);
        long errorsWithZeroInspections = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .count();

        var allInspections = LocalInspectionEP.LOCAL_INSPECTION.getExtensionList().stream()
            .filter(ep -> "STVN".equals(ep.language))
            .map(ep -> (LocalInspectionTool) ep.instantiateTool())
            .toArray(LocalInspectionTool[]::new);
        myFixture.enableInspections(allInspections);
        try {
            myFixture.configureByText("stage12_reorder_anchors.stvn", source);
            var highlights26 = myFixture.doHighlighting();
            long errorsWithAllInspections = highlights26.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .count();
            assertEquals(6L, errorsWithZeroInspections);
            assertEquals("Visible ERROR count must match 1:1 between 0 and 26 inspections",
                errorsWithZeroInspections, errorsWithAllInspections);

            String text = myFixture.getFile().getText();

            // Verify :meta reorder availability + DIFF preview at :meta, {, #kind (index 0), #name, and }
            int metaIdx = text.indexOf(":meta");
            int metaLBrace = text.indexOf("{", metaIdx);
            int metaKindIdx0 = text.indexOf("#kind #BODY", metaIdx);
            int metaNameIdx1 = text.indexOf("#name", metaIdx);
            int metaRBrace = text.indexOf("}", metaNameIdx1);
            for (int offset : new int[]{metaIdx + 1, metaLBrace, metaKindIdx0 + 1, metaNameIdx1 + 1, metaRBrace}) {
                assertSingleIntentionWithDiffPreview(
                    offset,
                    "Reorder ':meta' entries to canonical order (#name, #domain, #kind)",
                    "#name \"stage12_reorder_anchors\""
                );
            }

            // Verify MetadataMap reorder availability + DIFF preview on :TypeName, {, index 0 #regex, 2nd+ #minSize, and }
            int typeDefIdx = text.indexOf(":ScrambledCommentedString");
            int typeLBrace = text.indexOf("{", typeDefIdx);
            int regexIdx0 = text.indexOf("#regex", typeDefIdx);
            int minSizeIdx = text.indexOf("#minSize", typeDefIdx);
            int typeRBrace = text.indexOf("} :String", typeDefIdx);
            for (int offset : new int[]{typeDefIdx + 2, typeLBrace, regexIdx0 + 2, minSizeIdx + 2, typeRBrace}) {
                assertSingleIntentionWithDiffPreview(
                    offset,
                    "Reorder metadata facets to canonical 5-tier order",
                    "#minSize 6\n      #maxSize 6\n      #preserveIndent #TRUE\n      #regex \"^TAG-[0-9]{2}$\""
                );
            }

            // Verify #CONST and root :type anchors + DIFF preview
            assertSingleIntentionWithDiffPreview(
                text.indexOf("#SCRAMBLED_CONST") + 2,
                "Reorder metadata facets to canonical 5-tier order",
                "#unsigned\n      #maxExcl 100"
            );
            assertSingleIntentionWithDiffPreview(
                text.indexOf(":type {") + 2,
                "Reorder metadata facets to canonical 5-tier order",
                "#minIncl 1\n    #maxExcl 50"
            );
        } finally {
            myFixture.disableInspections(allInspections);
        }
    }

    private void assertSingleIntentionWithDiffPreview(int offset, String intentionName, String expectedPreviewSnippet) {
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions(intentionName);
        assertEquals("Expected strictly 1 '" + intentionName + "' intention at offset " + offset, 1, actions.size());
        String preview = myFixture.getIntentionPreviewText(actions.get(0));
        assertNotNull("Expected non-null IntentionPreviewInfo.DIFF at offset " + offset, preview);
        assertTrue("Preview at offset " + offset + " missing snippet '" + expectedPreviewSnippet + "':\n" + preview,
            preview.contains(expectedPreviewSnippet));
    }

    private void assertGotoTypeDefInFile(PsiFile sourceFile, int offset, String expectedFile, String expectedType) {
        var handler = new StvnGotoDeclarationHandler();
        PsiElement[] targets = handler.getGotoDeclarationTargets(sourceFile.findElementAt(offset), offset, myFixture.getEditor());
        assertNotNull("Expected navigation target at offset " + offset, targets);
        assertEquals(1, targets.length);
        assertTrue("Expected TypeDefinition, got: " + targets[0], targets[0] instanceof TypeDefinition);
        TypeDefinition td = (TypeDefinition) targets[0];
        assertEquals(expectedFile, td.getContainingFile().getName());
        assertNotNull(td.getTypeKeyword());
        assertEquals(expectedType, td.getTypeKeyword().getText());
    }

    private void assertGotoConstDefInFile(PsiFile sourceFile, int offset, String expectedFile, String expectedConst) {
        var handler = new StvnGotoDeclarationHandler();
        PsiElement[] targets = handler.getGotoDeclarationTargets(sourceFile.findElementAt(offset), offset, myFixture.getEditor());
        assertNotNull("Expected constant navigation target at offset " + offset, targets);
        assertEquals(1, targets.length);
        assertTrue("Expected ConstantDefinition, got: " + targets[0], targets[0] instanceof ConstantDefinition);
        ConstantDefinition cd = (ConstantDefinition) targets[0];
        assertEquals(expectedFile, cd.getContainingFile().getName());
        assertNotNull(cd.getValueKeyword());
        assertEquals(expectedConst, cd.getValueKeyword().getText());
    }

    private void assertGotoEnumVariantInFile(PsiFile sourceFile, int offset, String expectedFile, String expectedVariant) {
        var handler = new StvnGotoDeclarationHandler();
        PsiElement[] targets = handler.getGotoDeclarationTargets(sourceFile.findElementAt(offset), offset, myFixture.getEditor());
        assertNotNull("Expected enum variant target at offset " + offset, targets);
        assertEquals(1, targets.length);
        assertTrue("Expected EnumDef ValueKeyword, got: " + targets[0],
            targets[0] instanceof ValueKeyword vk && vk.getParent() instanceof EnumDef && expectedVariant.equals(vk.getText()));
        assertEquals(expectedFile, targets[0].getContainingFile().getName());
    }

    private void assertTwoHopUseTypeAlias(PsiFile sourceFile, int usageOffset, String expectedUpstreamFile, String expectedUpstreamType) {
        var handler = new StvnGotoDeclarationHandler();
        PsiElement[] hop1 = handler.getGotoDeclarationTargets(sourceFile.findElementAt(usageOffset), usageOffset, myFixture.getEditor());
        assertNotNull(hop1);
        assertEquals(1, hop1.length);
        assertTrue("Hop 1 must land on local UseMapAlias RHS, got: " + hop1[0], hop1[0].getParent() instanceof UseMapAlias);
        assertEquals(sourceFile.getName(), hop1[0].getContainingFile().getName());

        int rhsOffset = hop1[0].getTextRange().getStartOffset();
        PsiElement[] hop2 = handler.getGotoDeclarationTargets(sourceFile.findElementAt(rhsOffset), rhsOffset, myFixture.getEditor());
        assertNotNull(hop2);
        assertEquals(1, hop2.length);
        assertTrue("Hop 2 must land on upstream TypeDefinition, got: " + hop2[0], hop2[0] instanceof TypeDefinition);
        TypeDefinition td = (TypeDefinition) hop2[0];
        assertEquals(expectedUpstreamFile, td.getContainingFile().getName());
        assertNotNull(td.getTypeKeyword());
        assertEquals(expectedUpstreamType, td.getTypeKeyword().getText());
    }

    private void assertTwoHopUseConstAlias(PsiFile sourceFile, int usageOffset, String expectedUpstreamFile, String expectedUpstreamConst) {
        var handler = new StvnGotoDeclarationHandler();
        PsiElement[] hop1 = handler.getGotoDeclarationTargets(sourceFile.findElementAt(usageOffset), usageOffset, myFixture.getEditor());
        assertNotNull(hop1);
        assertEquals(1, hop1.length);
        assertTrue("Hop 1 must land on local UseMapAlias RHS, got: " + hop1[0], hop1[0].getParent() instanceof UseMapAlias);
        assertEquals(sourceFile.getName(), hop1[0].getContainingFile().getName());

        int rhsOffset = hop1[0].getTextRange().getStartOffset();
        PsiElement[] hop2 = handler.getGotoDeclarationTargets(sourceFile.findElementAt(rhsOffset), rhsOffset, myFixture.getEditor());
        assertNotNull(hop2);
        assertEquals(1, hop2.length);
        assertTrue("Hop 2 must land on upstream ConstantDefinition, got: " + hop2[0], hop2[0] instanceof ConstantDefinition);
        ConstantDefinition cd = (ConstantDefinition) hop2[0];
        assertEquals(expectedUpstreamFile, cd.getContainingFile().getName());
        assertNotNull(cd.getValueKeyword());
        assertEquals(expectedUpstreamConst, cd.getValueKeyword().getText());
    }

    private String generateHoverAt(StvnDocumentationProvider provider, PsiFile file, int offset) {
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        PsiElement leaf = file.findElementAt(offset);
        assertNotNull("Expected PSI leaf at offset " + offset, leaf);
        PsiElement target = provider.getCustomDocumentationElement(myFixture.getEditor(), file, leaf, offset);
        if (target == null) {
            var ref = file.findReferenceAt(offset);
            target = ref != null ? ref.resolve() : leaf;
        }
        assertNotNull("Expected documentation target at offset " + offset, target);
        String doc = provider.generateDoc(target, leaf);
        assertNotNull("Expected generated hover documentation at offset " + offset, doc);
        return doc;
    }
}
