package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.browser.StvnNamespaceScope;
import org.stvnadore.plugin.browser.StvnNamespaceSymbolCollector;
import org.stvnadore.plugin.browser.StvnNamespaceSymbolEntry;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.EnumDef;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.ValueKeyword;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * End-to-end IntelliJ Platform regression suite for Gauntlet V11 Batch 3 remediation:
 * <ul>
 *   <li>Target 1: Repo-wide {@code :package} lexical namespace isolation across PSI resolvers,
 *       all 27 local inspections, quick-fix providers, literal evaluators, and hover cards.</li>
 *   <li>Target 2: 5-tier mutex-aware nominal override facet merging and uniform
 *       {@code (-> ... -> :Base)} parent lineage in {@link StvnNamespaceSymbolCollector}.</li>
 * </ul>
 */
@NullMarked
public final class StvnGauntletV11Batch3PackageScopeAndNominalTypeTest extends BasePlatformTestCase {

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

    private List<HighlightInfo> collectErrorsAndWarnings() {
        return myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity().compareTo(HighlightSeverity.WARNING) >= 0)
            .toList();
    }

    /**
     * Target 1 (Stage 8 Blocker): Verifies zero false {@code E012} range errors and zero false clamp
     * quick-fixes on {@code #BOOST_MA :LaserDriveMa 1100} inside {@code :package :subsea/pump/raman}
     * across both Pass A (0 inspections) and Pass B (27 inspections), and verifies package-accurate
     * {@code [100, 1499]} clamp quick-fixes when {@code #BOOST_MA} genuinely exceeds {@code 1500}.
     */
    public void testStage8SubseaDualPumpPackageIsolationZeroFalseRangeErrorsAndAccurateQuickFixes() {
        String validStage8Doc = """
            {
              :meta {
                #name   "v11_stage08_package_and_use_eviction"
                #domain "subsea_telemetry"
                #kind   #BODY
              }
              :defs {
                :package :subsea/pump/primary {
                  :LaserDriveMa  { #unsigned #size 16 #minIncl 50 #maxExcl 900 }   :Int
                  :PumpMode      :Enum[#OFF #STANDBY #ACTIVE]
                  #NOMINAL_MA    :LaserDriveMa  450
                }

                :package :subsea/pump/raman {
                  :LaserDriveMa  { #unsigned #size 16 #minIncl 100 #maxExcl 1500 } :Int
                  :PumpMode      :Enum[#MUTED #PUMPING #SATURATED]
                  #BOOST_MA      :LaserDriveMa  1100
                }

                :use [
                  :subsea/pump/primary { #strip }
                  :subsea/pump/raman {
                    :LaserDriveMa  :RamanDriveMa
                    :PumpMode      :RamanMode
                    #BOOST_MA      #RAMAN_BOOST_MA
                  }
                ]
                :PumpMode        :Enum[#LOCAL_IDLE #LOCAL_LOCKED #LOCAL_BYPASS]
                :DualPumpFrame   :Tuple(:LaserDriveMa :RamanDriveMa :PumpMode :RamanMode)
              }
              :type :DualPumpFrame
              :body (450 #RAMAN_BOOST_MA #LOCAL_LOCKED #PUMPING)
            }
            """;

        // Pass A (0 local inspections; ExternalAnnotator + SemanticAnnotator only)
        PsiFile passAFile = myFixture.configureByText(
            "v11_stage08_package_and_use_eviction.subsea_telemetry.stvn",
            validStage8Doc
        );
        List<HighlightInfo> passAHighlights = collectErrorsAndWarnings();
        assertTrue("Expected 0 errors/warnings in Pass A, got: " + passAHighlights, passAHighlights.isEmpty());

        // Pass B (all 27 local inspections enabled)
        enableAll27StvnInspections();
        List<HighlightInfo> passBHighlights = collectErrorsAndWarnings();
        assertTrue("Expected 0 errors/warnings in Pass B, got: " + passBHighlights, passBHighlights.isEmpty());

        int boostLiteralOffset = validStage8Doc.indexOf("1100");
        assertTrue(boostLiteralOffset > 0);
        myFixture.getEditor().getCaretModel().moveToOffset(boostLiteralOffset + 1);
        List<String> validQuickFixNames = myFixture.getAvailableIntentions().stream()
            .map(IntentionAction::getText)
            .toList();
        assertFalse(
            "Valid packaged constant 1100 must not offer false primary-package clamp '899': " + validQuickFixNames,
            validQuickFixNames.stream().anyMatch(t -> t.contains("Clamp value"))
        );

        PsiElement leafAt1100 = passAFile.findElementAt(boostLiteralOffset + 1);
        assertNotNull(leafAt1100);
        PsiElement valueAt1100 = PsiTreeUtil.getParentOfType(leafAt1100, org.stvnadore.psi.Value.class, false);
        String hoverHtml = new StvnDocumentationProvider().generateDoc(
            valueAt1100 != null ? valueAt1100 : leafAt1100,
            leafAt1100
        );
        assertNotNull("Expected hover documentation on 1100", hoverHtml);
        assertTrue("Hover card on 1100 must report Satisfied compliance: " + hoverHtml, hoverHtml.contains("Satisfied"));

        // Mutate #BOOST_MA from 1100 -> 1600 inside :package :subsea/pump/raman and verify accurate [100, 1499] fixes
        String outOfBoundsDoc = validStage8Doc.replace("1100", "1600");
        myFixture.configureByText(
            "v11_stage08_package_and_use_eviction.subsea_telemetry.stvn",
            outOfBoundsDoc
        );
        List<HighlightInfo> violationHighlights = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals(
            "Expected exactly 1 deduplicated E012 error on 1600, got: " + violationHighlights,
            1,
            violationHighlights.size()
        );
        HighlightInfo err = violationHighlights.get(0);
        int badOffset = outOfBoundsDoc.indexOf("1600");
        assertEquals(badOffset, err.getStartOffset());
        assertEquals(badOffset + 4, err.getEndOffset());
        assertTrue(err.getDescription().contains("E012"));
        assertTrue("Expected raman upper bound 1500 in diagnostic: " + err.getDescription(), err.getDescription().contains("1500"));

        myFixture.getEditor().getCaretModel().moveToOffset(badOffset + 1);
        List<IntentionAction> intentions = myFixture.getAvailableIntentions();
        List<String> fixTexts = intentions.stream().map(IntentionAction::getText).toList();
        assertTrue("Expected raman max clamp '1499' in: " + fixTexts, fixTexts.contains("Clamp value to maximum bound '1499'"));
        assertTrue("Expected raman min clamp '100' in: " + fixTexts, fixTexts.contains("Clamp value to minimum bound '100'"));
        assertFalse("Must never leak primary max clamp '899': " + fixTexts, fixTexts.contains("Clamp value to maximum bound '899'"));
        assertFalse("Must never leak primary min clamp '50': " + fixTexts, fixTexts.contains("Clamp value to minimum bound '50'"));

        IntentionAction clamp1499 = intentions.stream()
            .filter(a -> "Clamp value to maximum bound '1499'".equals(a.getText()))
            .findFirst()
            .orElseThrow();
        myFixture.launchAction(clamp1499);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        List<HighlightInfo> postFixHighlights = collectErrorsAndWarnings();
        assertTrue("Expected 0 errors/warnings after clamping to 1499, got: " + postFixHighlights, postFixHighlights.isEmpty());
    }

    /**
     * Target 1: Verifies {@code :EnumSubset} validation ({@code E014} / {@code E015}) and
     * {@link StvnReorderEnumFilterVariantsQuickFix} inside {@code :package :subsea/pump/raman}
     * resolve strictly against the enclosing package's {@code :PumpMode} without sibling leakage.
     */
    public void testPackageScopedEnumSubsetAndReorderQuickFixIsolation() {
        enableAll27StvnInspections();

        String foreignVariantDoc = """
            {
              :meta {
                #name   "pkg_enum_subset_foreign"
                #domain "subsea"
                #kind   #DEFS
              }
              :defs {
                :package :subsea/pump/primary {
                  :PumpMode :Enum[#OFF #STANDBY #ACTIVE]
                }
                :package :subsea/pump/raman {
                  :PumpMode   :Enum[#MUTED #PUMPING #SATURATED]
                  :ValidSub   { #filterIncl [#MUTED #SATURATED] } :PumpMode
                  :ForeignSub { #filterIncl [#OFF] }              :PumpMode
                }
                :use [
                  :subsea/pump/primary { #strip }
                ]
              }
            }
            """;

        myFixture.configureByText("pkg_enum_subset_foreign.subsea.stvn_d", foreignVariantDoc);
        List<HighlightInfo> foreignErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected E014 on foreign #OFF inside raman package: " + foreignErrors, 1, foreignErrors.size());
        assertTrue(foreignErrors.get(0).getDescription().contains("E014"));

        String disorderedSubsetDoc = """
            {
              :meta {
                #name   "pkg_enum_subset_reorder"
                #domain "subsea"
                #kind   #DEFS
              }
              :defs {
                :package :subsea/pump/primary {
                  :PumpMode :Enum[#OFF #STANDBY #ACTIVE]
                }
                :package :subsea/pump/raman {
                  :PumpMode      :Enum[#MUTED #PUMPING #SATURATED]
                  :DisorderedSub { #filterIncl [#SATURATED #MUTED] } :PumpMode
                }
                :use [
                  :subsea/pump/primary { #strip }
                ]
              }
            }
            """;

        myFixture.configureByText("pkg_enum_subset_reorder.subsea.stvn_d", disorderedSubsetDoc);
        List<HighlightInfo> orderErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected E015 on disordered subset inside raman package: " + orderErrors, 1, orderErrors.size());
        assertTrue(orderErrors.get(0).getDescription().contains("E015"));

        int filterOffset = disorderedSubsetDoc.indexOf("#MUTED]");
        myFixture.getEditor().getCaretModel().moveToOffset(filterOffset + 1);
        IntentionAction reorderFix = myFixture.getAvailableIntentions().stream()
            .filter(a -> "Sort variants to match root enum declaration order".equals(a.getText()))
            .findFirst()
            .orElseThrow();
        myFixture.launchAction(reorderFix);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertTrue(myFixture.getEditor().getDocument().getText().contains("[#MUTED #SATURATED]"));
        assertTrue("Expected 0 errors after reordering: " + collectErrorsAndWarnings(), collectErrorsAndWarnings().isEmpty());
    }

    /**
     * Target 1: Verifies STVN 2.0.0 § 4.2 Rule 1 local priority eviction — unpackaged root {@code :defs}
     * {@code :PumpMode} evicts {@code :use [ :subsea/pump/primary { #strip } ]}'s {@code :PumpMode}
     * across PSI references, inspections, and {@code :body} validation.
     */
    public void testRootLocalDefinitionEvictsStrippedPackageSymbolAcrossInspectionsAndBody() {
        enableAll27StvnInspections();

        String validLocalEvictionDoc = """
            {
              :meta {
                #name   "root_eviction_check"
                #domain "subsea"
                #kind   #BODY
              }
              :defs {
                :package :subsea/pump/primary {
                  :PumpMode :Enum[#OFF #STANDBY #ACTIVE]
                }
                :use [
                  :subsea/pump/primary { #strip }
                ]
                :PumpMode :Enum[#LOCAL_IDLE #LOCAL_LOCKED #LOCAL_BYPASS]
              }
              :type :PumpMode
              :body #LOCAL_LOCKED
            }
            """;

        PsiFile validFile = myFixture.configureByText("root_eviction_check.subsea.stvn", validLocalEvictionDoc);
        assertTrue("Expected 0 errors when using root :PumpMode variant: " + collectErrorsAndWarnings(), collectErrorsAndWarnings().isEmpty());

        BodyEntry bodyEntry = PsiTreeUtil.findChildOfType(validFile, BodyEntry.class);
        assertNotNull(bodyEntry);
        ValueKeyword bodyKw = PsiTreeUtil.findChildOfType(bodyEntry, ValueKeyword.class);
        assertNotNull(bodyKw);
        var ref = bodyKw.getReference();
        assertNotNull(ref);
        PsiElement resolvedVariant = ref.resolve();
        assertNotNull(resolvedVariant);
        EnumDef resolvedEnum = PsiTreeUtil.getParentOfType(resolvedVariant, EnumDef.class);
        assertNotNull(resolvedEnum);
        assertNull(
            "Root :body variant must resolve to unpackaged root :PumpMode, not PackageEnclosure",
            PsiTreeUtil.getParentOfType(resolvedEnum, PackageEnclosure.class)
        );

        String evictedVariantDoc = validLocalEvictionDoc.replace(":body #LOCAL_LOCKED", ":body #OFF");
        myFixture.configureByText("root_eviction_check.subsea.stvn", evictedVariantDoc);
        List<HighlightInfo> evictedErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Evicted #strip variant #OFF must be rejected in root :body: " + evictedErrors, 1, evictedErrors.size());
    }

    /**
     * Target 1: Verifies package-internal {@code :use} isolation, fail-closed package {@code :use}
     * collision handling (never leaking to Prelude), and {@code #CONST}-backed metadata facets inside {@code :package}.
     */
    public void testPackageInternalUseCollisionFailClosedAndConstBackedFacetsIsolation() {
        enableAll27StvnInspections();

        String scopedUseAndConstFacetsDoc = """
            {
              :meta {
                #name   "pkg_internal_use_and_const_facets"
                #domain "subsea"
                #kind   #BODY
              }
              :defs {
                :package :subsea/shared {
                  :SharedGain { #unsigned #size 8 #maxExcl 40 } :Int
                }
                :package :subsea/pump/raman {
                  :use [
                    :subsea/shared {
                      :SharedGain :GainDb
                    }
                  ]
                  #MIN_MA       :Int     100
                  #MAX_MA       :Int     1500
                  #KEEP_INDENT  :Boolean #TRUE
                  :LaserDriveMa { #unsigned #size 16 #minIncl #MIN_MA #maxExcl #MAX_MA } :Int
                  :NoteText     { #maxSize 64 #preserveIndent #KEEP_INDENT }             :String
                  :RamanPair    :Tuple(:GainDb :LaserDriveMa :NoteText)
                  #SAMPLE_PAIR  :RamanPair (25 1100 "ok")
                }
                #MIN_MA      :Int     10
                #MAX_MA      :Int     50
                #KEEP_INDENT :Boolean #FALSE
                :use [
                  :subsea/pump/raman {
                    :RamanPair   :ExportedPair
                    #SAMPLE_PAIR #EXPORTED_SAMPLE
                  }
                ]
              }
              :type :ExportedPair
              :body #EXPORTED_SAMPLE
            }
            """;

        myFixture.configureByText("pkg_internal_use_and_const_facets.subsea.stvn", scopedUseAndConstFacetsDoc);
        List<HighlightInfo> highlights = collectErrorsAndWarnings();
        assertTrue(
            "Expected 0 diagnostics with package-internal :use and #CONST-backed facets, got: " + highlights,
            highlights.isEmpty()
        );

        // Verify fail-closed package-local :use collision does NOT fall through to Prelude :Port / #E
        String collisionDoc = """
            {
              :meta {
                #name   "pkg_collision_fail_closed"
                #domain "subsea"
                #kind   #DEFS
              }
              :defs {
                :package :pkg/a {
                  :Port :Int
                  #E    :Float 1.0
                }
                :package :pkg/b {
                  :Port :Int
                  #E    :Float 2.0
                }
                :package :pkg/consumer {
                  :use [
                    :pkg/a { #strip }
                    :pkg/b { #strip }
                  ]
                  :BrokenPort :Port
                  #BROKEN_E   :Float #E
                }
              }
            }
            """;
        PsiFile collisionFile = myFixture.configureByText("pkg_collision_fail_closed.subsea.stvn_d", collisionDoc);
        TypeDefinition brokenPortDef = PsiTreeUtil.findChildrenOfType(collisionFile, TypeDefinition.class).stream()
            .filter(td -> td.getTypeKeyword() != null && ":BrokenPort".equals(td.getTypeKeyword().getText().trim()))
            .findFirst()
            .orElseThrow();
        assertNotNull(brokenPortDef.getSchemaType());
        assertNull(
            "Package-local :use collision on :Port must fail closed and never fall back to Prelude :Port",
            StvnTypeReference.resolveTypeDefinitionAtContext(brokenPortDef.getSchemaType().getTypeKeyword(), ":Port")
        );

        ConstantDefinition brokenConstDef = PsiTreeUtil.findChildrenOfType(collisionFile, ConstantDefinition.class).stream()
            .filter(cd -> cd.getValueKeyword() != null && "#BROKEN_E".equals(cd.getValueKeyword().getText().trim()))
            .findFirst()
            .orElseThrow();
        assertNotNull(brokenConstDef.getValue());
        assertNull(
            "Package-local :use collision on #E must fail closed and never fall back to Prelude #E",
            StvnConstantReference.resolveConstantDefinitionAtContext(brokenConstDef.getValue(), "#E")
        );
    }

    /**
     * Target 2 (Stage 7 Catch): Verifies child-over-ancestor 5-tier facet override merging and
     * nominal parent lineage preservation on {@code :TrunkRepeaterId} and {@code #TRUNK_HEAD}
     * in {@link StvnNamespaceSymbolCollector}.
     */
    public void testStage7NominalOverrideFacetMergingAndParentLineageInNamespaceBrowser() {
        String stage7Doc = """
            {
              :meta {
                #name   "v11_stage07_nominal_and_recursion"
                #domain "subsea_telemetry"
                #kind   #DEFS
              }
              :defs {
                :RepeaterId       { #unsigned #size 16 #minIncl 1 #maxExcl 4096 }  :Int
                :BranchUnitId     { #unsigned #size 16 #minIncl 1 #maxExcl 4096 }  :Int
                :TrunkRepeaterId  { #minIncl 100 #maxExcl 500 }                    :RepeaterId
                #TRUNK_HEAD       :TrunkRepeaterId  101
              }
            }
            """;

        PsiFile file = myFixture.configureByText(
            "v11_stage07_nominal_and_recursion.subsea_telemetry.stvn_d",
            stage7Doc
        );
        Map<String, StvnNamespaceSymbolEntry> byName = StvnNamespaceSymbolCollector
            .collectSymbols(file, StvnNamespaceScope.DEFS)
            .stream()
            .collect(Collectors.toMap(StvnNamespaceSymbolEntry::name, Function.identity()));

        StvnNamespaceSymbolEntry repeaterId = byName.get(":RepeaterId");
        assertNotNull(repeaterId);
        assertEquals("{ #unsigned #size 16 #minIncl 1 #maxExcl 4096 } :Int", repeaterId.resolvedType());
        assertEquals(0, repeaterId.useDepth());

        StvnNamespaceSymbolEntry branchUnitId = byName.get(":BranchUnitId");
        assertNotNull(branchUnitId);
        assertEquals("{ #unsigned #size 16 #minIncl 1 #maxExcl 4096 } :Int", branchUnitId.resolvedType());
        assertEquals(0, branchUnitId.useDepth());

        StvnNamespaceSymbolEntry trunkRepeaterId = byName.get(":TrunkRepeaterId");
        assertNotNull(trunkRepeaterId);
        assertEquals(
            "{ #unsigned #size 16 #minIncl 100 #maxExcl 500 } :RepeaterId (-> :Int)",
            trunkRepeaterId.resolvedType()
        );
        assertEquals(1, trunkRepeaterId.useDepth());

        StvnNamespaceSymbolEntry trunkHead = byName.get("#TRUNK_HEAD");
        assertNotNull(trunkHead);
        assertEquals(":TrunkRepeaterId (-> :RepeaterId -> :Int)", trunkHead.resolvedType());
        assertEquals(2, trunkHead.useDepth());
    }

    /**
     * Target 2: Verifies multi-hop scalar rebrands, mutex bound-kind overrides ({@code #minExcl}
     * superseding {@code #minIncl} and {@code #maxIncl} superseding {@code #maxExcl}), single-hop and
     * nested {@code :EnumSubset} lineage with inherited Tier-2 trait overrides, Prelude {@code :Port}
     * derivations, temporal derivations without duplicated scale flags, and composite {@code UseMapAlias} brands.
     */
    public void testMultiHopMutexBoundOverridesEnumSubsetLineagePreludePortAndCompositeBrands() {
        String matrixDoc = """
            {
              :meta {
                #name   "nominal_lineage_matrix"
                #domain "subsea"
                #kind   #DEFS
              }
              :defs {
                :package :com/example/core {
                  :BaseEntity :Tuple(:Int :String)
                }
                :use [
                  :org/stvnadore/prelude {
                    :Port :Port
                  }
                  :com/example/core {
                    :BaseEntity :LocalEntity
                  }
                ]

                :RepeaterId            { #unsigned #size 16 #minIncl 1 #maxExcl 4096 } :Int
                :TrunkRepeaterId       { #minIncl 100 #maxExcl 500 }                   :RepeaterId
                :SubTrunk              :TrunkRepeaterId

                :ParentFloat           { #minIncl 0.0 #maxExcl 100.0 }                 :Float
                :NarrowFloat           { #minExcl 0.5 #maxIncl 50.0 }                  :ParentFloat

                :LaserState            { #comparable #FALSE }                          :Enum[#OFF #WARMUP #LOCKED #DEGRADED]
                :PoweredLaserState     { #filterExcl [#OFF] }                          :LaserState
                :TelemetryActiveState  { #filterIncl [#LOCKED #DEGRADED] }             :PoweredLaserState

                :HighPort              { #minIncl 1024 }                               :Port
                :SecEpoch              { #s }                                          :TimeEpoch
                :SpanEpoch             { #minIncl 1700000000 }                         :SecEpoch
                :DeepAlias             :LocalEntity
              }
            }
            """;

        PsiFile file = myFixture.configureByText("nominal_lineage_matrix.subsea.stvn_d", matrixDoc);
        Map<String, StvnNamespaceSymbolEntry> byName = StvnNamespaceSymbolCollector
            .collectSymbols(file, StvnNamespaceScope.DEFS)
            .stream()
            .collect(Collectors.toMap(StvnNamespaceSymbolEntry::name, Function.identity()));

        assertEquals(
            "{ #unsigned #size 16 #minIncl 100 #maxExcl 500 } :TrunkRepeaterId (-> :RepeaterId -> :Int)",
            byName.get(":SubTrunk").resolvedType()
        );
        assertEquals(
            "{ #minExcl 0.5 #maxIncl 50.0 } :ParentFloat (-> :Float)",
            byName.get(":NarrowFloat").resolvedType()
        );
        assertEquals(
            "{ #comparable #FALSE } :Enum[#OFF #WARMUP #LOCKED #DEGRADED]",
            byName.get(":LaserState").resolvedType()
        );
        assertEquals(
            "{ #comparable #FALSE #filterExcl [#OFF] } :LaserState (-> :Enum)",
            byName.get(":PoweredLaserState").resolvedType()
        );
        assertEquals(
            "{ #comparable #FALSE #filterIncl [#LOCKED #DEGRADED] } :PoweredLaserState (-> :LaserState -> :Enum)",
            byName.get(":TelemetryActiveState").resolvedType()
        );
        assertEquals(
            "{ #unsigned #size 16 #minIncl 1024 } :Port (-> :Int)",
            byName.get(":HighPort").resolvedType()
        );
        assertEquals(
            "{ #s #minIncl 1700000000 } :SecEpoch (-> :TimeEpoch)",
            byName.get(":SpanEpoch").resolvedType()
        );
        assertEquals(
            ":LocalEntity (-> :com/example/core/BaseEntity -> :Tuple)",
            byName.get(":DeepAlias").resolvedType()
        );
    }
}
