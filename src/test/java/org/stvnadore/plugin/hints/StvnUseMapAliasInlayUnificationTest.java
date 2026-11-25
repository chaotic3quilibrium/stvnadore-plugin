package org.stvnadore.plugin.hints;

import com.intellij.codeInsight.hints.InlayHintsSink;
import com.intellij.codeInsight.hints.presentation.InlayPresentation;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.printer.StvnSourceFormatter;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.AliasExpansionMode;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.plugin.util.StvnTypePresentationUtil;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.MapLiteral;
import org.stvnadore.psi.TupleLiteral;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.Value;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies unified renamed {@code UseMapAlias} head-hop inlay rendering, unrenamed {@code #strip}
 * and identity {@code UseMapAlias} deduplication, all five {@link AliasExpansionMode} modes,
 * degraded {@code ⚠} prefixes, State A vs. State B parity, and per-hop {@code Ctrl+Click} targets.
 */
@NullMarked
public final class StvnUseMapAliasInlayUnificationTest extends BasePlatformTestCase {

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

    public void testFmtProbe02RenamedUseMapAliasInlaysExpansionModesStateBAndPerHopClickability() throws Exception {
        Path rawInputPath = Path.of("src/test/resources/shared-fixtures/formatting/input/fmt_probe_02_constants_meta_use_and_maps.core_test.stvn");
        if (!Files.exists(rawInputPath)) {
            rawInputPath = Path.of("../ij_stvnadore_core/shared-fixtures/formatting/input/fmt_probe_02_constants_meta_use_and_maps.core_test.stvn");
        }
        String rawProbeSrc = Files.readString(rawInputPath);
        String probeSrc = StvnSourceFormatter.tryFormat(rawProbeSrc).orElseThrow();
        PsiFile stateAFile = myFixture.configureByText("fmt_probe_02_constants_meta_use_and_maps.core_test.stvn", probeSrc);
        assertTrue(
            "Expected fmt_probe_02 must lower Pass 3 payload in State A",
            StvnCompiledDocumentCache.getSnapshot(stateAFile).document().flatMap(StvnDocument::payload).isPresent()
        );

        List<Value> outerSlots = extractOuterBodyTupleSlots(stateAFile);
        assertEquals(5, outerSlots.size());

        // Slot 0 (#INIT_VALVE_MODE) & Slot 1 (#STANDBY_TEMP_C)
        assertEquals(":ValveMode (-> :cooling/manifold/CoolantValveMode -> :Enum)",
            StvnTypeResolver.resolveValueType(outerSlots.get(0)));
        assertEquals(":LoopTempC (-> :cooling/manifold/ManifoldTempC -> :Int)",
            StvnTypeResolver.resolveValueType(outerSlots.get(1)));
        assertEquals(":Int", StvnTypeResolver.resolveValueType(outerSlots.get(2)));
        assertEquals(":Boolean", StvnTypeResolver.resolveValueType(outerSlots.get(3)));
        assertEquals(":ZoneFlowMap", StvnTypeResolver.resolveValueType(outerSlots.get(4)));

        // Map values: 120, 480, #MAX_FLOW_LPM governed by :LoopFlowLpm
        MapLiteral zoneMap = outerSlots.get(4).getCollectionValue().getMapLiteral();
        assertNotNull(zoneMap);
        List<Value> mapValues = zoneMap.getValueList();
        assertEquals(6, mapValues.size());
        for (int i = 0; i < mapValues.size(); i += 2) {
            assertEquals(":String", StvnTypeResolver.resolveValueType(mapValues.get(i)));
            assertEquals(
                ":LoopFlowLpm (-> :cooling/manifold/FlowTargetLpm -> :Int)",
                StvnTypeResolver.resolveValueType(mapValues.get(i + 1))
            );
        }

        // Verify all 5 AliasExpansionModes on :ValveMode (#INIT_VALVE_MODE)
        String fullValve = StvnTypeResolver.resolveValueType(outerSlots.get(0));
        assertNotNull(fullValve);
        assertEquals(":ValveMode (-> :cooling/manifold/CoolantValveMode -> :Enum)",
            StvnTypePresentationUtil.formatAliasChain(fullValve, AliasExpansionMode.FULL).orElse(null));
        assertEquals(":ValveMode (-> :cooling/manifold/CoolantValveMode)",
            StvnTypePresentationUtil.formatAliasChain(fullValve, AliasExpansionMode.FIRST_HOP).orElse(null));
        assertEquals(":ValveMode (-> :Enum)",
            StvnTypePresentationUtil.formatAliasChain(fullValve, AliasExpansionMode.ALIAS_TO_BASE).orElse(null));
        assertEquals(":ValveMode",
            StvnTypePresentationUtil.formatAliasChain(fullValve, AliasExpansionMode.SYMBOL_ONLY).orElse(null));
        assertTrue(StvnTypePresentationUtil.formatAliasChain(fullValve, AliasExpansionMode.NONE).isEmpty());

        // Per-hop click navigation: :ValveMode -> UseMapAlias RHS, :cooling/manifold/CoolantValveMode -> TypeDefinition
        var valveLinks = StvnInlayHintsCollector.extractBoundClickableTypeSegments(outerSlots.get(0), fullValve);
        assertEquals(2, valveLinks.size());
        assertEquals(":ValveMode", valveLinks.get(0).tokenText());
        assertTrue(valveLinks.get(0).targetElement().getParent() instanceof UseMapAlias);
        assertEquals(":cooling/manifold",
            PsiTreeUtil.getParentOfType(valveLinks.get(0).targetElement(), org.stvnadore.psi.UseElement.class)
                .getUseTarget().getText().trim());
        assertEquals(":cooling/manifold/CoolantValveMode", valveLinks.get(1).tokenText());
        TypeDefinition valveTd = PsiTreeUtil.getParentOfType(valveLinks.get(1).targetElement(), TypeDefinition.class, false);
        assertNotNull(valveTd);
        assertEquals(":CoolantValveMode", valveTd.getTypeKeyword().getText().trim());

        // Also verify raw unformatted input/fmt_probe_02 AND State B (Pass-3-Suspended via duplicate :defs section) parity
        PsiFile rawProbeFile = myFixture.configureByText("raw_fmt_probe_02.core_test.stvn", rawProbeSrc);
        List<Value> rawProbeSlots = extractOuterBodyTupleSlots(rawProbeFile);
        assertEquals(":ValveMode (-> :cooling/manifold/CoolantValveMode -> :Enum)",
            StvnTypeResolver.resolveValueType(rawProbeSlots.get(0)));
        assertEquals(":LoopTempC (-> :cooling/manifold/ManifoldTempC -> :Int)",
            StvnTypeResolver.resolveValueType(rawProbeSlots.get(1)));

        String suspendedSrc = probeSrc.replace(":type :ManifoldReport", ":defs { :SuspendPass3 :Int }\n  :type :ManifoldReport");
        PsiFile stateBFile = myFixture.configureByText("fmt_probe_02_suspended.core_test.stvn", suspendedSrc);
        assertTrue(
            "State B must suspend Pass 3 payload lowering",
            StvnCompiledDocumentCache.getSnapshot(stateBFile).document().flatMap(StvnDocument::payload).isEmpty()
        );
        List<Value> stateBSlots = extractOuterBodyTupleSlots(stateBFile);
        assertEquals(":ValveMode (-> :cooling/manifold/CoolantValveMode -> :Enum)",
            StvnTypeResolver.resolveValueType(stateBSlots.get(0)));
        assertEquals(":LoopTempC (-> :cooling/manifold/ManifoldTempC -> :Int)",
            StvnTypeResolver.resolveValueType(stateBSlots.get(1)));
        MapLiteral stateBMap = stateBSlots.get(4).getCollectionValue().getMapLiteral();
        assertNotNull(stateBMap);
        List<Value> stateBMapValues = stateBMap.getValueList();
        assertEquals(6, stateBMapValues.size());
        for (int i = 0; i < stateBMapValues.size(); i += 2) {
            assertEquals(
                ":LoopFlowLpm (-> :cooling/manifold/FlowTargetLpm -> :Int)",
                StvnTypeResolver.resolveValueType(stateBMapValues.get(i + 1))
            );
        }
    }

    public void testNeutrinoStripIdentityPreludeSumBranchCompositeLiteralAndPoisonedDegradedParity() {
        myFixture.addFileToProject("neutrino/neutrino_optics.physics.stvn_d", """
            {
              :meta {
                #name "neutrino_optics"
                #domain "physics"
                #kind #DEFS
              }
              :defs {
                :package :neutrino/optics {
                  :Photoelectrons { #unsigned #size 32 } :Int
                  :HitEpochNs { #ns } :TimeEpoch
                  :DomHitTuple :Tuple(:Photoelectrons :HitEpochNs)
                  #CALIB_DOM_HIT :DomHitTuple (42 1700000000000000000)
                }
              }
            }
            """);

        String src = """
            {
              :meta {
                #name "icecube_event_frame"
                #domain "physics"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "neutrino_optics.physics.stvn_d" ]
                :use [
                  :neutrino/optics { #strip } {
                    :DomHitTuple   :ArrayDomHit
                    :HitEpochNs    :TriggerNs
                  }
                  :org/stvnadore/prelude {
                    :Port :Port
                  }
                ]
                :OptTrigger :Option(:TriggerNs)
                #POISON_TRIGGER :TriggerNs #POISON_TRIGGER
                :EventEnvelope :Tuple(
                  :Photoelectrons
                  :Port
                  :ArrayDomHit
                  :ArrayDomHit
                  :OptTrigger
                  :TriggerNs
                )
              }
              :type :EventEnvelope
              :body (
                128
                8080
                #CALIB_DOM_HIT
                (256 1700000000000000500)
                1700000000000000999
                #POISON_TRIGGER
              )
            }
            """;

        PsiFile file = myFixture.addFileToProject("neutrino/icecube_event_frame.physics.stvn_i", src);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        List<Value> slots = extractOuterBodyTupleSlots(file);
        assertEquals(6, slots.size());

        // 1. Unrenamed #strip (:Photoelectrons) -> zero stutter!
        assertEquals(":neutrino/optics/Photoelectrons (-> :Int)", StvnTypeResolver.resolveValueType(slots.get(0)));
        // 2. Identity UseMapAlias on Prelude (:Port :Port) -> zero stutter!
        assertEquals(":Port", StvnTypeResolver.resolveValueType(slots.get(1)));
        // 3. Composite #CONST & Composite Literal on renamed UseMapAlias (:DomHitTuple :ArrayDomHit)
        assertEquals(":ArrayDomHit (-> :neutrino/optics/DomHitTuple -> :Tuple)",
            StvnTypeResolver.resolveValueType(slots.get(2)));
        assertEquals(":ArrayDomHit (-> :neutrino/optics/DomHitTuple -> :Tuple)",
            StvnTypeResolver.resolveValueType(slots.get(3)));
        // 4. Nominal sum branch referencing renamed UseMapAlias (:TriggerNs -> :neutrino/optics/HitEpochNs -> { #ns } :TimeEpoch)
        assertEquals(":OptTrigger [#Some] (-> :TriggerNs -> :neutrino/optics/HitEpochNs -> { #ns } :TimeEpoch)",
            StvnTypeResolver.resolveValueType(slots.get(4)));
        // 5. Poisoned #CONST slot governed by renamed UseMapAlias -> degraded ⚠ prefix preserved across modes
        String poisonedFull = StvnTypeResolver.resolveValueType(slots.get(5));
        assertEquals("\u26A0 :TriggerNs (-> :neutrino/optics/HitEpochNs -> { #ns } :TimeEpoch)", poisonedFull);
        assertEquals("\u26A0 :TriggerNs (-> :neutrino/optics/HitEpochNs)",
            StvnTypePresentationUtil.formatAliasChain(poisonedFull, AliasExpansionMode.FIRST_HOP).orElse(null));
        assertEquals("\u26A0 :TriggerNs (-> { #ns } :TimeEpoch)",
            StvnTypePresentationUtil.formatAliasChain(poisonedFull, AliasExpansionMode.ALIAS_TO_BASE).orElse(null));
        assertEquals("\u26A0 :TriggerNs",
            StvnTypePresentationUtil.formatAliasChain(poisonedFull, AliasExpansionMode.SYMBOL_ONLY).orElse(null));

        // Verify collector renders non-empty presentations on every valid slot
        Map<Integer, String> rendered = collectInlayMap(file);
        assertTrue(rendered.values().contains(":neutrino/optics/Photoelectrons (-> :Int)"));
        assertTrue(rendered.values().contains(":Port"));
        assertTrue(rendered.values().contains(":ArrayDomHit (-> :neutrino/optics/DomHitTuple -> :Tuple)"));
    }

    private List<Value> extractOuterBodyTupleSlots(PsiFile file) {
        var bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
        assertNotNull(bodyEntry);
        var bodyVal = bodyEntry.getValue();
        assertNotNull(bodyVal);
        assertNotNull(bodyVal.getCollectionValue());
        TupleLiteral tuple = bodyVal.getCollectionValue().getTupleLiteral();
        assertNotNull(tuple);
        return tuple.getValueList();
    }

    private Map<Integer, String> collectInlayMap(PsiFile file) {
        Map<Integer, String> byOffset = new LinkedHashMap<>();
        var collector = new StvnInlayHintsCollector(myFixture.getEditor());
        collector.collect(file, myFixture.getEditor(), new InlayHintsSink() {
            @Override
            public void addInlineElement(
                int offset,
                boolean relatesToPrecedingText,
                @NotNull InlayPresentation presentation,
                boolean placeAtTheEndOfLine
            ) {
                byOffset.putIfAbsent(offset, presentation.toString());
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
                @NotNull com.intellij.codeInsight.hints.presentation.RootInlayPresentation<?> presentation,
                @Nullable com.intellij.codeInsight.hints.HorizontalConstraints constraints
            ) {
            }

            @Override
            public void addBlockElement(
                int logicalLine,
                boolean showAbove,
                @NotNull com.intellij.codeInsight.hints.presentation.RootInlayPresentation<?> presentation,
                @Nullable com.intellij.codeInsight.hints.BlockConstraints constraints
            ) {
            }
        });
        return byOffset;
    }
}
