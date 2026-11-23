package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnSchema.DateTimeMode;
import org.stvnadore.plugin.documentation.StvnExemplarValueGenerator;
import org.stvnadore.plugin.validation.quickfix.StvnDateTimeBoundQuickFixProvider;
import org.stvnadore.plugin.validation.quickfix.StvnPayloadValueQuickFixProvider;
import org.stvnadore.psi.MetadataEntry;

/**
 * End-to-end IntelliJ Platform verification suite for Gauntlet v10 Batch 2
 * ({@code G10B2-01} through {@code G10B2-03}) across {@code Pass A} ({@code 0} inspections)
 * and {@code Pass B} ({@code 26} inspections), asserting {@code count == 1} intention
 * deduplication, {@code IntentionPreviewInfo.DIFF} previews, and zero post-fix errors or {@code W003} warnings.
 */
@NullMarked
public final class StvnGauntletV10Batch2PluginQuickFixParityTest extends BasePlatformTestCase {

    private LocalInspectionTool[] createAll26Inspections() {
        return new LocalInspectionTool[]{
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
            new StvnFloatPrecisionLossInspection()
        };
    }

    private void runInPassAAndPassB(Runnable assertions) {
        // Pass A: 0 local inspections enabled (default BasePlatformTestCase profile)
        assertions.run();
        // Pass B: all 26 local inspections enabled
        LocalInspectionTool[] all = createAll26Inspections();
        myFixture.enableInspections(all);
        try {
            assertions.run();
        } finally {
            myFixture.disableInspections(all);
        }
    }

    private List<HighlightInfo> collectErrorsAndWarnings(List<HighlightInfo> highlights) {
        return highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR || h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && !h.getDescription().isBlank())
            .toList();
    }

    private IntentionAction assertSingleIntentionWithDiffPreview(String exactText) {
        List<IntentionAction> matches = myFixture.getAvailableIntentions().stream()
            .filter(a -> exactText.equals(a.getText()))
            .toList();
        assertEquals(
            "Expected strictly count == 1 for intention [" + exactText + "], all available: "
                + myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList(),
            1,
            matches.size()
        );
        IntentionAction action = matches.getFirst();
        String preview = myFixture.getIntentionPreviewText(action);
        assertNotNull("Expected non-null IntentionPreviewInfo.DIFF preview for [" + exactText + "]", preview);
        assertFalse("Expected non-blank IntentionPreviewInfo.DIFF preview for [" + exactText + "]", preview.isBlank());
        return action;
    }

    // =========================================================================
    // Target 1 (Stage 4): :String #size <N> Primary #maxSize <N> & Edge Cases
    // =========================================================================

    public void testStage4StringSizePrimaryMaxSizeOrderingAndPassAPassBParity() {
        runInPassAAndPassB(() -> {
            String doc = """
                {
                  :meta {
                    #name "stage4_callsign"
                    #kind #BODY
                  }
                  :defs {
                    :BadBitSizedCallsign { #si<caret>ze 12 } :String
                  }
                  :type :BadBitSizedCallsign
                  :body ""
                }
                """;
            myFixture.configureByText("stage4_callsign.stvn", doc);
            myFixture.doHighlighting();

            IntentionAction topFix = assertSingleIntentionWithDiffPreview("Convert '#size 12' to '#maxSize 12'");
            assertSingleIntentionWithDiffPreview("Convert '#size 12' to '#minSize 1 #maxSize 12'");
            assertSingleIntentionWithDiffPreview("Convert '#size 12' to '#minSize 12 #maxSize 12'");

            List<String> orderedLabels = myFixture.getAvailableIntentions().stream()
                .map(IntentionAction::getText)
                .toList();
            int idxMaxOnly = orderedLabels.indexOf("Convert '#size 12' to '#maxSize 12'");
            int idxBounded = orderedLabels.indexOf("Convert '#size 12' to '#minSize 1 #maxSize 12'");
            int idxFixed = orderedLabels.indexOf("Convert '#size 12' to '#minSize 12 #maxSize 12'");
            assertTrue("Expected Priority.TOP '#maxSize 12' first: " + orderedLabels,
                idxMaxOnly >= 0 && idxMaxOnly < idxBounded && idxBounded < idxFixed);

            String preview = myFixture.getIntentionPreviewText(topFix);
            assertTrue(preview.contains(":BadBitSizedCallsign { #maxSize 12 } :String"));

            myFixture.launchAction(topFix);
            assertTrue(myFixture.getFile().getText().contains(":BadBitSizedCallsign { #maxSize 12 } :String"));
            List<HighlightInfo> after = collectErrorsAndWarnings(myFixture.doHighlighting());
            assertTrue("Expected 0 errors/warnings after primary #maxSize 12 conversion: " + after, after.isEmpty());
        });
    }

    public void testStage4StringSizeEdgeCasesDefault16MiBExistingMinConstUnitAndNominal() {
        LocalInspectionTool[] all = createAll26Inspections();
        myFixture.enableInspections(all);
        try {
            // 1. <N> == 16777216 -> zero W003 default capacity quick-fixes
            String defaultCapDoc = """
                {
                  :meta {
                    #name "stage4_default_cap"
                    #kind #BODY
                  }
                  :defs {
                    :DefaultCapStr { #si<caret>ze 16777216 } :String
                  }
                  :type { #size 16777216 } :String
                  :body "ok"
                }
                """;
            myFixture.configureByText("stage4_default_cap.stvn", defaultCapDoc);
            myFixture.doHighlighting();
            IntentionAction useDefaultFix = assertSingleIntentionWithDiffPreview(
                "Use default 16777216 ':String' capacity (remove '#size 16777216')"
            );
            IntentionAction minOneFix = assertSingleIntentionWithDiffPreview("Convert '#size 16777216' to '#minSize 1'");
            assertSingleIntentionWithDiffPreview("Convert '#size 16777216' to '#minSize 16777216'");
            assertTrue(
                "Must never offer W003-inducing '#maxSize 16777216'",
                myFixture.filterAvailableIntentions("Convert '#size 16777216' to '#maxSize 16777216'").isEmpty()
            );
            assertNotNull(useDefaultFix);
            myFixture.launchAction(minOneFix);
            assertTrue(myFixture.getFile().getText().contains(":DefaultCapStr { #minSize 1 } :String"));

            myFixture.doHighlighting();
            myFixture.getEditor().getCaretModel().moveToOffset(
                myFixture.getFile().getText().indexOf("#size 16777216") + 2
            );
            IntentionAction rootTypeDefaultFix = assertSingleIntentionWithDiffPreview(
                "Use default 16777216 ':String' capacity (remove '#size 16777216')"
            );
            myFixture.launchAction(rootTypeDefaultFix);
            assertTrue(myFixture.getFile().getText().contains(":type :String"));
            List<HighlightInfo> afterDefault = collectErrorsAndWarnings(myFixture.doHighlighting());
            assertTrue(
                "Expected 0 errors after 16777216 conversions: " + afterDefault,
                afterDefault.stream().noneMatch(h -> h.getSeverity() == HighlightSeverity.ERROR)
            );
            assertTrue(
                "Expected 0 W003 redundant facet warnings after 16777216 conversions: " + afterDefault,
                afterDefault.stream().noneMatch(h -> h.getDescription() != null && h.getDescription().contains(":W003-"))
            );

            // 2. Existing compatible #minSize 4 (#minSize 4 <= 12) in disordered block (#regex before #minSize 4)
            String compatMinDoc = """
                {
                  :meta {
                    #name "stage4_compat_min"
                    #kind #BODY
                  }
                  :defs {
                    :CallsignRange { #regex "^[A-Z]+$" #size 12 #minSize 4 } :String
                  }
                  :type :CallsignRange
                  :body "ABCD"
                }
                """;
            myFixture.configureByText("stage4_compat_min.stvn", compatMinDoc);
            myFixture.getEditor().getCaretModel().moveToOffset(compatMinDoc.indexOf("#size") + 2);
            myFixture.doHighlighting();
            IntentionAction preserveMinFix = assertSingleIntentionWithDiffPreview("Convert '#size 12' to '#maxSize 12'");
            assertSingleIntentionWithDiffPreview("Reorder metadata facets to canonical 5-tier order");
            myFixture.launchAction(preserveMinFix);
            assertTrue(
                "Expected existing #minSize 4 to be preserved in canonical order: " + myFixture.getFile().getText(),
                myFixture.getFile().getText().contains(":CallsignRange { #minSize 4 #maxSize 12 #regex \"^[A-Z]+$\" } :String")
            );
            assertTrue(collectErrorsAndWarnings(myFixture.doHighlighting()).isEmpty());

            // 3. Existing incompatible #minSize 20 (20 > 12) suppresses '#maxSize 12' to prevent E009 and offers Remove
            String invertedMinDoc = """
                {
                  :meta {
                    #name "stage4_inv_min"
                    #kind #BODY
                  }
                  :defs {
                    :InvertedMinTrap { #minSize 20 #si<caret>ze 12 } :String
                  }
                  :type :InvertedMinTrap
                  :body "01234567890123456789"
                }
                """;
            myFixture.configureByText("stage4_inv_min.stvn", invertedMinDoc);
            myFixture.doHighlighting();
            assertTrue(
                "Must suppress '#maxSize 12' when existing #minSize 20 > 12 would cause E009",
                myFixture.filterAvailableIntentions("Convert '#size 12' to '#maxSize 12'").isEmpty()
            );
            IntentionAction removeProhibited = assertSingleIntentionWithDiffPreview("Remove prohibited facet '#size 12'");
            myFixture.launchAction(removeProhibited);
            assertTrue(myFixture.getFile().getText().contains(":InvertedMinTrap { #minSize 20 } :String"));

            // 4. Unit <N> == 1 deduplicates fixed-length option, #CONST <N> resolves, and nominal alias offers Remove only
            String constAndUnitDoc = """
                {
                  :meta {
                    #name "stage4_const_unit"
                    #kind #DEFS
                  }
                  :defs {
                    #MAX_CALLSIGN :Int 12
                    :UnitStr { #size 1 } :String
                    :ConstStr { #size #MAX_CALLSIGN } :String
                    :BaseStr { #maxSize 32 } :String
                    :DerivedStr { #size 12 } :BaseStr
                  }
                }
                """;
            myFixture.configureByText("stage4_const_unit.stvn_d", constAndUnitDoc);
            myFixture.getEditor().getCaretModel().moveToOffset(constAndUnitDoc.indexOf("#size 1") + 2);
            myFixture.doHighlighting();
            assertSingleIntentionWithDiffPreview("Convert '#size 1' to '#maxSize 1'");
            assertSingleIntentionWithDiffPreview("Convert '#size 1' to '#minSize 1 #maxSize 1'");

            myFixture.getEditor().getCaretModel().moveToOffset(constAndUnitDoc.indexOf("#size #MAX_CALLSIGN") + 2);
            assertSingleIntentionWithDiffPreview("Convert '#size #MAX_CALLSIGN' to '#maxSize #MAX_CALLSIGN'");
            assertSingleIntentionWithDiffPreview("Convert '#size #MAX_CALLSIGN' to '#minSize 1 #maxSize #MAX_CALLSIGN'");
            assertSingleIntentionWithDiffPreview("Convert '#size #MAX_CALLSIGN' to '#minSize #MAX_CALLSIGN #maxSize #MAX_CALLSIGN'");

            myFixture.getEditor().getCaretModel().moveToOffset(constAndUnitDoc.indexOf("#size 12") + 2);
            assertSingleIntentionWithDiffPreview("Remove prohibited facet '#size 12'");
            assertTrue(myFixture.filterAvailableIntentions("Convert '#size 12' to '#maxSize 12'").isEmpty());
        } finally {
            myFixture.disableInspections(all);
        }
    }

    // =========================================================================
    // Target 2 (Stage 5): :DateTime Mandatory :ss Seconds & Schema Bound Fixes
    // =========================================================================

    public void testStage5DateTimeMandatorySecondsAcrossDstGapConversionsAndExemplars() {
        Clock fixedChicago = Clock.fixed(Instant.parse("2026-03-08T08:00:00Z"), ZoneId.of("America/Chicago"));
        Clock fixedLondonWinter = Clock.fixed(Instant.parse("2026-01-15T00:00:00Z"), ZoneId.of("Europe/London"));

        // 1. Direct formatter & converter verification against StvnCompiler.validateDateTimeLiteral
        String healedDstZoned = StvnPayloadValueQuickFixProvider.tryConvertDateTimeLiteral(
            "\"2026-03-08T02:30:00[America/Chicago]\"",
            DateTimeMode.ZONED,
            fixedChicago
        );
        assertEquals("\"2026-03-08T03:30:00[America/Chicago]\"", healedDstZoned);
        assertTrue(StvnCompiler.validateDateTimeLiteral(healedDstZoned, DateTimeMode.ZONED).isEmpty());

        String healedDstAudited = StvnPayloadValueQuickFixProvider.tryConvertDateTimeLiteral(
            "\"2026-03-08T02:30:00[America/Chicago]\"",
            DateTimeMode.AUDITED,
            fixedChicago
        );
        assertEquals("\"2026-03-08T03:30:00-05:00[America/Chicago]\"", healedDstAudited);
        assertTrue(StvnCompiler.validateDateTimeLiteral(healedDstAudited, DateTimeMode.AUDITED).isEmpty());

        String healedDstOffset = StvnPayloadValueQuickFixProvider.tryConvertDateTimeLiteral(
            "\"2026-03-08T02:30:00[America/Chicago]\"",
            DateTimeMode.OFFSET,
            fixedChicago
        );
        assertEquals("\"2026-03-08T03:30:00-05:00\"", healedDstOffset);
        assertTrue(StvnCompiler.validateDateTimeLiteral(healedDstOffset, DateTimeMode.OFFSET).isEmpty());

        // Preserves fractional seconds when nano != 0
        String healedFraction = StvnPayloadValueQuickFixProvider.tryConvertDateTimeLiteral(
            "\"2026-03-08T02:30:00.250[America/Chicago]\"",
            DateTimeMode.ZONED,
            fixedChicago
        );
        assertEquals("\"2026-03-08T03:30:00.250[America/Chicago]\"", healedFraction);
        assertTrue(StvnCompiler.validateDateTimeLiteral(healedFraction, DateTimeMode.ZONED).isEmpty());

        // Heals seconds-omitted input strings
        String healedMissingSec = StvnPayloadValueQuickFixProvider.tryConvertDateTimeLiteral(
            "\"2026-03-08T03:30[America/Chicago]\"",
            DateTimeMode.ZONED,
            fixedChicago
        );
        assertEquals("\"2026-03-08T03:30:00[America/Chicago]\"", healedMissingSec);
        assertTrue(StvnCompiler.validateDateTimeLiteral(healedMissingSec, DateTimeMode.ZONED).isEmpty());

        // Deterministic [UTC] / Z[UTC] on Z offset even when host zone is Europe/London in winter
        assertEquals(
            "\"2026-01-01T00:00:00[UTC]\"",
            StvnPayloadValueQuickFixProvider.tryConvertDateTimeLiteral(
                "\"2026-01-01T00:00:00Z\"",
                DateTimeMode.ZONED,
                fixedLondonWinter
            )
        );
        assertEquals(
            "\"2026-01-01T00:00:00Z[UTC]\"",
            StvnPayloadValueQuickFixProvider.tryConvertDateTimeLiteral(
                "\"2026-01-01T00:00:00Z\"",
                DateTimeMode.AUDITED,
                fixedLondonWinter
            )
        );

        // Sub-second clamped exemplar preserves sub-second nanos instead of truncating below #minIncl
        Clock beforeSubSecond = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
        String subSecondExemplar = StvnExemplarValueGenerator.generateDateTimeExemplar(
            DateTimeMode.OFFSET,
            "\"2026-01-01T00:00:00.5Z\"",
            "\"2026-01-01T00:00:01Z\"",
            beforeSubSecond
        );
        assertEquals("\"2026-01-01T00:00:00.5Z\"", subSecondExemplar);
        assertTrue(StvnCompiler.validateDateTimeLiteral(subSecondExemplar, DateTimeMode.OFFSET).isEmpty());

        // 2. Live :body Slot DST-gap quick-fix preview & invocation across Pass A and Pass B
        runInPassAAndPassB(() -> {
            String bodyDstDoc = """
                {
                  :meta {
                    #name "stage5_body_dst"
                    #kind #BODY
                  }
                  :defs {
                    :ZonedSchedule { #zoned } :DateTime
                  }
                  :type :ZonedSchedule
                  :body "2026-03-08T02:30:<caret>00[America/Chicago]"
                }
                """;
            myFixture.configureByText("stage5_body_dst.stvn", bodyDstDoc);
            myFixture.doHighlighting();

            IntentionAction dstFix = assertSingleIntentionWithDiffPreview(
                "Convert to #zoned :DateTime '\"2026-03-08T03:30:00[America/Chicago]\"'"
            );
            String preview = myFixture.getIntentionPreviewText(dstFix);
            assertTrue("Preview must retain :00 seconds: " + preview, preview.contains("\"2026-03-08T03:30:00[America/Chicago]\""));
            myFixture.launchAction(dstFix);
            assertTrue(myFixture.getFile().getText().contains(":body \"2026-03-08T03:30:00[America/Chicago]\""));
            assertTrue(collectErrorsAndWarnings(myFixture.doHighlighting()).isEmpty());
        });
    }

    public void testStage5SchemaDateTimeBoundsRemediationAcrossPassAAndPassB() {
        runInPassAAndPassB(() -> {
            // 1. :PoisonedZonedBoundTrap — per-bound Convert (TOP), Convert all bounds (HIGH), and Change '#zoned' to '#offset' (HIGH)
            String poisonedTrapDoc = """
                {
                  :meta {
                    #name "stage5_poisoned_bounds"
                    #kind #BODY
                  }
                  :defs {
                    :PoisonedZonedBoundTrap { #zoned #min<caret>Incl "2026-01-01T00:00:00Z" #maxExcl "2027-01-01T00:00:00Z" } :DateTime
                  }
                  :type :PoisonedZonedBoundTrap
                  :body "2026-06-01T12:00:00[UTC]"
                }
                """;
            myFixture.configureByText("stage5_poisoned_bounds.stvn", poisonedTrapDoc);
            myFixture.doHighlighting();

            assertSingleIntentionWithDiffPreview("Convert '#minIncl' to #zoned :DateTime \"2026-01-01T00:00:00[UTC]\"");
            IntentionAction convertAllFix = assertSingleIntentionWithDiffPreview("Convert all bounds to #zoned :DateTime");
            assertSingleIntentionWithDiffPreview("Change '#zoned' to '#offset'");
            assertSingleIntentionWithDiffPreview("Remove invalid '#minIncl' facet");

            myFixture.launchAction(convertAllFix);
            assertTrue(
                myFixture.getFile().getText().contains(
                    "{ #zoned #minIncl \"2026-01-01T00:00:00[UTC]\" #maxExcl \"2027-01-01T00:00:00[UTC]\" } :DateTime"
                )
            );
            assertTrue("Expected 0 errors after Convert all bounds: " + collectErrorsAndWarnings(myFixture.doHighlighting()),
                collectErrorsAndWarnings(myFixture.doHighlighting()).isEmpty());

            // 2. Verify 1-click Change '#zoned' to '#offset' when payload & bounds are #offset
            String switchModeDoc = """
                {
                  :meta {
                    #name "stage5_switch_mode"
                    #kind #BODY
                  }
                  :defs {
                    :PoisonedZonedBoundTrap { #zoned #min<caret>Incl "2026-01-01T00:00:00Z" #maxExcl "2027-01-01T00:00:00Z" } :DateTime
                  }
                  :type :PoisonedZonedBoundTrap
                  :body "2026-06-01T12:00:00Z"
                }
                """;
            myFixture.configureByText("stage5_switch_mode.stvn", switchModeDoc);
            myFixture.doHighlighting();
            IntentionAction switchModeFix = assertSingleIntentionWithDiffPreview("Change '#zoned' to '#offset'");
            myFixture.launchAction(switchModeFix);
            assertTrue(
                myFixture.getFile().getText().contains(
                    "{ #offset #minIncl \"2026-01-01T00:00:00Z\" #maxExcl \"2027-01-01T00:00:00Z\" } :DateTime"
                )
            );
            assertTrue(collectErrorsAndWarnings(myFixture.doHighlighting()).isEmpty());

            // 3. Fenced block string bound (E028-DATETIME_FENCED_STRING_PROHIBITED) reachability on #minIncl keyword AND fence value
            String fencedBoundDoc = """
                {
                  :meta {
                    #name "stage5_fenced_bound"
                    #kind #DEFS
                  }
                  :defs {
                    :FencedWindow { #offset #minIncl \"\"\"[ISO]
                2026-01-01T00:00:00Z
                [ISO]\"\"\" } :DateTime
                  }
                }
                """;
            myFixture.configureByText("stage5_fenced_bound.stvn_d", fencedBoundDoc);
            myFixture.getEditor().getCaretModel().moveToOffset(fencedBoundDoc.indexOf("#minIncl") + 2);
            myFixture.doHighlighting();
            assertSingleIntentionWithDiffPreview("Convert '#minIncl' to #offset :DateTime \"2026-01-01T00:00:00Z\"");

            myFixture.getEditor().getCaretModel().moveToOffset(fencedBoundDoc.indexOf("2026-01-01") + 2);
            IntentionAction fencedConvert = assertSingleIntentionWithDiffPreview(
                "Convert '#minIncl' to #offset :DateTime \"2026-01-01T00:00:00Z\""
            );
            myFixture.launchAction(fencedConvert);
            assertTrue(myFixture.getFile().getText().contains(":FencedWindow { #offset #minIncl \"2026-01-01T00:00:00Z\" } :DateTime"));
            assertTrue(collectErrorsAndWarnings(myFixture.doHighlighting()).isEmpty());

            // 4. Derived nominal :DateTime alias with unparseable junk bound -> Remove invalid '#minIncl' facet excises sole {}
            String derivedJunkDoc = """
                {
                  :meta {
                    #name "stage5_derived_junk"
                    #kind #DEFS
                  }
                  :defs {
                    :BaseAudited { #audited } :DateTime
                    :DerivedWindow { #min<caret>Incl "not-a-date" } :BaseAudited
                  }
                }
                """;
            myFixture.configureByText("stage5_derived_junk.stvn_d", derivedJunkDoc);
            myFixture.doHighlighting();
            IntentionAction removeInvalidMin = assertSingleIntentionWithDiffPreview("Remove invalid '#minIncl' facet");
            myFixture.launchAction(removeInvalidMin);
            assertTrue("Sole comment-free {} must be excised: " + myFixture.getFile().getText(),
                myFixture.getFile().getText().contains(":DerivedWindow :BaseAudited"));
            assertTrue(collectErrorsAndWarnings(myFixture.doHighlighting()).isEmpty());
        });

        // 5. Interval soundness guard: conversion that would invert [#minIncl >= #maxExcl) is suppressed
        String invertedCandidateDoc = """
            {
              :meta {
                #name "stage5_interval_guard"
                #kind #DEFS
              }
              :defs {
                :InvertedGuard { #zoned #minIncl "2028-01-01T00:00:00Z" #maxExcl "2027-01-01T00:00:00[UTC]" } :DateTime
              }
            }
            """;
        myFixture.configureByText("stage5_interval_guard.stvn_d", invertedCandidateDoc);
        MetadataEntry minEntry = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), MetadataEntry.class).stream()
            .filter(e -> e.getText().startsWith("#minIncl"))
            .findFirst()
            .orElseThrow();
        Clock clockIn2029 = Clock.fixed(Instant.parse("2029-01-01T00:00:00Z"), ZoneId.of("UTC"));
        List<String> guardedLabels = StvnDateTimeBoundQuickFixProvider.computeBoundQuickFixes(minEntry, clockIn2029)
            .stream()
            .map(IntentionAction::getText)
            .toList();
        assertEquals(List.of("Remove invalid '#minIncl' facet"), guardedLabels);
    }

    // =========================================================================
    // Target 3 (Stage 6): :Enum Filter Facet Quick-Fixes Across E014, E016, E008
    // =========================================================================

    public void testStage6EnumSubsetNarrowingAndSingleTokenRemovalAcrossPassAAndPassB() {
        runInPassAAndPassB(() -> {
            // 1. >= 2 variants (trailing, leading, middle) -> zero interior bracket padding
            String multiVariantDoc = """
                {
                  :meta {
                    #name "stage6_narrowing"
                    #kind #DEFS
                  }
                  :defs {
                    :WaferStage :Enum[#QUEUED #COATED #ALIGNED #BAKED #SCRAPPED]
                    :SalvageableWaferStage { #filterExcl [#SCRAPPED] } :WaferStage
                    :TrailingTrap { #filterIncl [#ALIGNED #SCRAPPED] } :SalvageableWaferStage
                    :LeadingTrap { #filterIncl [#SCRAPPED #ALIGNED] } :SalvageableWaferStage
                    :MiddleTrap { #filterIncl [#COATED #SCRAPPED #BAKED] } :SalvageableWaferStage
                    :SingleTrap { #filterIncl [#SCRAPPED] } :SalvageableWaferStage
                  }
                }
                """;
            myFixture.configureByText("stage6_narrowing.stvn_d", multiVariantDoc);
            myFixture.doHighlighting();

            // Trailing #SCRAPPED
            int trailingOff = myFixture.getFile().getText().indexOf("#SCRAPPED", myFixture.getFile().getText().indexOf(":TrailingTrap"));
            myFixture.getEditor().getCaretModel().moveToOffset(trailingOff + 2);
            IntentionAction remTrailing = assertSingleIntentionWithDiffPreview("Remove '#SCRAPPED' from '#filterIncl'");
            myFixture.launchAction(remTrailing);
            assertTrue(myFixture.getFile().getText().contains(":TrailingTrap { #filterIncl [#ALIGNED] } :SalvageableWaferStage"));

            // Leading #SCRAPPED
            myFixture.doHighlighting();
            int leadingOff = myFixture.getFile().getText().indexOf("#SCRAPPED", myFixture.getFile().getText().indexOf(":LeadingTrap"));
            myFixture.getEditor().getCaretModel().moveToOffset(leadingOff + 2);
            IntentionAction remLeading = assertSingleIntentionWithDiffPreview("Remove '#SCRAPPED' from '#filterIncl'");
            myFixture.launchAction(remLeading);
            assertTrue(myFixture.getFile().getText().contains(":LeadingTrap { #filterIncl [#ALIGNED] } :SalvageableWaferStage"));

            // Middle #SCRAPPED
            myFixture.doHighlighting();
            int middleOff = myFixture.getFile().getText().indexOf("#SCRAPPED", myFixture.getFile().getText().indexOf(":MiddleTrap"));
            myFixture.getEditor().getCaretModel().moveToOffset(middleOff + 2);
            IntentionAction remMiddle = assertSingleIntentionWithDiffPreview("Remove '#SCRAPPED' from '#filterIncl'");
            myFixture.launchAction(remMiddle);
            assertTrue(myFixture.getFile().getText().contains(":MiddleTrap { #filterIncl [#COATED #BAKED] } :SalvageableWaferStage"));

            // Single-token [#SCRAPPED] -> strictly Remove '#filterIncl [#SCRAPPED]' facet, excising sole {}
            myFixture.doHighlighting();
            int singleOff = myFixture.getFile().getText().indexOf("#SCRAPPED", myFixture.getFile().getText().indexOf(":SingleTrap"));
            myFixture.getEditor().getCaretModel().moveToOffset(singleOff + 2);
            assertTrue(
                "Single-token invalid filter must never offer per-token removal leaving []",
                myFixture.filterAvailableIntentions("Remove '#SCRAPPED' from '#filterIncl'").isEmpty()
            );
            IntentionAction remSingleFacet = assertSingleIntentionWithDiffPreview("Remove '#filterIncl [#SCRAPPED]' facet");
            myFixture.launchAction(remSingleFacet);
            assertTrue(myFixture.getFile().getText().contains(":SingleTrap :SalvageableWaferStage"));
            assertTrue("Expected 0 errors/warnings after fixing all 4 traps: " + collectErrorsAndWarnings(myFixture.doHighlighting()),
                collectErrorsAndWarnings(myFixture.doHighlighting()).isEmpty());
        });
    }

    public void testStage6EmptyFilterDisjointReachabilityTotalExclusionAndProhibitedTargets() {
        runInPassAAndPassB(() -> {
            // 3B: Empty filter list (#filterIncl []) disjoint reachability on #filterIncl AND [] + Total exclusion
            String emptyAndTotalDoc = """
                {
                  :meta {
                    #name "stage6_empty_total"
                    #kind #DEFS
                  }
                  :defs {
                    :WaferStage :Enum[#QUEUED #COATED]
                    :EmptyFilterListTrap { #filterIncl [] } :WaferStage
                    :TotalExclusionTrap { #filterExcl [#QUEUED #COATED] } :WaferStage
                  }
                }
                """;
            myFixture.configureByText("stage6_empty_total.stvn_d", emptyAndTotalDoc);
            myFixture.doHighlighting();

            // Caret on #filterIncl keyword
            myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("#filterIncl") + 3);
            assertSingleIntentionWithDiffPreview("Remove '#filterIncl []' facet");

            // Caret inside []
            myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("[]") + 1);
            IntentionAction remEmpty = assertSingleIntentionWithDiffPreview("Remove '#filterIncl []' facet");
            myFixture.launchAction(remEmpty);
            assertTrue(myFixture.getFile().getText().contains(":EmptyFilterListTrap :WaferStage"));

            // Caret on TotalExclusionTrap
            myFixture.doHighlighting();
            myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("#filterExcl") + 3);
            IntentionAction remTotal = assertSingleIntentionWithDiffPreview("Remove '#filterExcl [#QUEUED #COATED]' facet");
            myFixture.launchAction(remTotal);
            assertTrue(myFixture.getFile().getText().contains(":TotalExclusionTrap :WaferStage"));
            assertTrue(collectErrorsAndWarnings(myFixture.doHighlighting()).isEmpty());

            // 3C: Prohibited filter attachments (inline :Enum[...], ConstantDefinition, and non-:Enum :Int)
            // Also verifies 0 duplicate E008 highlights on :BadInt in 26-inspection mode!
            String prohibitedDoc = """
                {
                  :meta {
                    #name "stage6_prohibited"
                    #kind #DEFS
                  }
                  :defs {
                    :LaserStage :Enum[#QUEUED #ALIGNED]
                    :InlineEnumFilterTrap { #filterIncl [#QUEUED] } :Enum[#QUEUED #ALIGNED]
                    #CONST_FILTER_TRAP { #filterIncl [#ALIGNED] } :LaserStage #ALIGNED
                    :BadInt { #filterIncl [#QUEUED] } :Int
                  }
                }
                """;
            myFixture.configureByText("stage6_prohibited.stvn_d", prohibitedDoc);
            List<HighlightInfo> prohibitedErrors = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .toList();
            assertEquals(
                "Expected strictly 3 deduplicated E008 errors (zero duplicate StvnMetadataFacet overlap): "
                    + prohibitedErrors,
                3,
                prohibitedErrors.size()
            );

            // Fix 1: Inline :Enum[...]
            myFixture.getEditor().getCaretModel().moveToOffset(
                myFixture.getFile().getText().indexOf("#filterIncl [#QUEUED]", myFixture.getFile().getText().indexOf(":InlineEnumFilterTrap")) + 3
            );
            IntentionAction remInline = assertSingleIntentionWithDiffPreview("Remove '#filterIncl [#QUEUED]' facet");
            myFixture.launchAction(remInline);
            assertTrue(myFixture.getFile().getText().contains(":InlineEnumFilterTrap :Enum[#QUEUED #ALIGNED]"));

            // Fix 2: ConstantDefinition
            myFixture.doHighlighting();
            myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("#filterIncl [#ALIGNED]") + 3);
            IntentionAction remConst = assertSingleIntentionWithDiffPreview("Remove '#filterIncl [#ALIGNED]' facet");
            myFixture.launchAction(remConst);
            assertTrue(myFixture.getFile().getText().contains("#CONST_FILTER_TRAP :LaserStage #ALIGNED"));

            // Fix 3: Non-:Enum :Int
            myFixture.doHighlighting();
            myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("#filterIncl [#QUEUED]") + 3);
            IntentionAction remBadInt = assertSingleIntentionWithDiffPreview("Remove '#filterIncl [#QUEUED]' facet");
            myFixture.launchAction(remBadInt);
            assertTrue(myFixture.getFile().getText().contains(":BadInt :Int"));
            assertTrue(collectErrorsAndWarnings(myFixture.doHighlighting()).isEmpty());
        });
    }
}
