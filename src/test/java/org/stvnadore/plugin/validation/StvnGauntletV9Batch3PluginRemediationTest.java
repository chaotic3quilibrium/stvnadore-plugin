package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.browser.StvnNamespaceScope;
import org.stvnadore.plugin.browser.StvnNamespaceSymbolCollector;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.psi.TypeDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * End-to-end IntelliJ Platform verification suite for Gauntlet v9 Batch 3 Plugin Remediation
 * ({@code PB3R-01} through {@code PB3R-05} and Mandatory Safeguards 1 through 4).
 */
@NullMarked
public final class StvnGauntletV9Batch3PluginRemediationTest extends BasePlatformTestCase {

    private static final Path SHARED_FIXTURES_ROOT = Paths.get("src", "test", "resources", "shared-fixtures");

    private void enableAll26StvnInspections() {
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
            new StvnFloatPrecisionLossInspection()
        );
    }

    public void testPb3r01And05c_UnionMinimumBranchCountAndMalformedConstructorHoverTable() throws IOException {
        Path stage78Path = SHARED_FIXTURES_ROOT.resolve(
            "syntax/invalid/composites/stage7_and_stage8_empty_composite_defs_isolation.stvn"
        );
        String stage78Src = Files.readString(stage78Path, StandardCharsets.UTF_8);

        // Pass A (0 inspections)
        myFixture.configureByText("stage7_and_stage8_empty_composite_defs_isolation.stvn", stage78Src);
        var passAErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue(
            "Pass A :Union() must report 'requires at least 2 schema types': " + passAErrors,
            passAErrors.stream().anyMatch(h -> ":Union()".equals(h.getText())
                && h.getDescription() != null
                && h.getDescription().contains("empty composite argument list: :Union() requires at least 2 schema types"))
        );

        // Pass B (26 inspections)
        enableAll26StvnInspections();
        PsiFile stage78File = myFixture.configureByText(
            "stage7_and_stage8_empty_composite_defs_isolation.stvn",
            stage78Src
        );
        var passBErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass A and Pass B must emit identical ERROR count", passAErrors.size(), passBErrors.size());

        var docProvider = new StvnDocumentationProvider();
        int emptyUnionAliasOffset = stage78Src.indexOf(":TrapEmptyUnion") + 2;
        String emptyUnionHover = generateHoverAt(docProvider, stage78File, emptyUnionAliasOffset);
        assertTrue("0-branch :Union() nominal hover must show Malformed constructor (requires at least 2 branches):\n" + emptyUnionHover,
            emptyUnionHover.contains("Malformed constructor (requires at least 2 branches)"));
        assertTrue("0-branch :Union() nominal hover must render em-dash effective value:\n" + emptyUnionHover,
            emptyUnionHover.contains("<code>\u2014</code>"));
        assertTrue("0-branch :Union() nominal hover must render Status: Invalid:\n" + emptyUnionHover,
            emptyUnionHover.contains(">Invalid</b>"));
        assertFalse("0-branch :Union() nominal hover must never claim 'Derived (constituent lacks trait)':\n" + emptyUnionHover,
            emptyUnionHover.contains("Derived (constituent lacks trait)"));

        int inlineEmptyUnionOffset = stage78Src.lastIndexOf(":Union()") + 2;
        String inlineUnionHover = generateHoverAt(docProvider, stage78File, inlineEmptyUnionOffset);
        assertTrue("Direct :Union() constructor hover must also render Malformed constructor row:\n" + inlineUnionHover,
            inlineUnionHover.contains("Malformed constructor (requires at least 2 branches)"));

        String allMalformedDoc = """
            {
              :meta {
                #name "malformed_ctor_hover_matrix"
                #kind #DEFS
              }
              :defs {
                :BadEnum :Enum[]
                :BadTuple :Tuple()
                :BadOpt :Option()
                :BadEither :Either(:Int)
                :BadSeq :Seq()
                :BadMap :Map(:String)
              }
            }
            """;
        PsiFile malformedFile = myFixture.configureByText("malformed_ctor_hover_matrix.stvn_d", allMalformedDoc);
        assertTrue(generateHoverAt(docProvider, malformedFile, allMalformedDoc.indexOf(":BadEnum") + 2)
            .contains("Malformed constructor (requires at least 1 variant)"));
        assertTrue(generateHoverAt(docProvider, malformedFile, allMalformedDoc.indexOf(":BadTuple") + 2)
            .contains("Malformed constructor (requires at least 1 field)"));
        assertTrue(generateHoverAt(docProvider, malformedFile, allMalformedDoc.indexOf(":BadOpt") + 2)
            .contains("Malformed constructor (requires 1 inner type)"));
        assertTrue(generateHoverAt(docProvider, malformedFile, allMalformedDoc.indexOf(":BadEither") + 2)
            .contains("Malformed constructor (requires 2 branch types)"));
        assertTrue(generateHoverAt(docProvider, malformedFile, allMalformedDoc.indexOf(":BadSeq") + 2)
            .contains("Malformed constructor (requires 1 element type)"));
        assertTrue(generateHoverAt(docProvider, malformedFile, allMalformedDoc.indexOf(":BadMap") + 2)
            .contains("Malformed constructor (requires key and value types)"));
    }

    public void testPb3r02_TrailingSlashPackageRecoveryQuickFixAndPreludeIsolation() throws IOException {
        Path stage9Path = SHARED_FIXTURES_ROOT.resolve(
            "syntax/invalid/packages/stage9_trailing_slash_package_recovery_and_nominal_brand_matrix.stvn"
        );
        String stage9Src = Files.readString(stage9Path, StandardCharsets.UTF_8);

        // Pass A (0 inspections): exact 3 ERROR highlights matching .contract.stvn_i
        myFixture.configureByText("stage9_trailing_slash_package_recovery_and_nominal_brand_matrix.stvn", stage9Src);
        var passAErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass A must emit exact 3 ERROR highlights: " + passAErrors, 3, passAErrors.size());
        assertEquals(":propulsion/trailing_slash/", passAErrors.get(0).getText());
        assertEquals("#ATTITUDE_PULSE", passAErrors.get(1).getText());
        assertEquals("50", passAErrors.get(2).getText());

        // Verify Pass A quick-fix preview (DIFF)
        int slashOffset = stage9Src.indexOf(":propulsion/trailing_slash/") + 5;
        myFixture.getEditor().getCaretModel().moveToOffset(slashOffset);
        IntentionAction passAFix = findIntentionContaining("Remove trailing slash ':propulsion/trailing_slash'");
        assertNotNull("Pass A must offer Remove trailing slash quick-fix", passAFix);
        String passAPreview = myFixture.getIntentionPreviewText(passAFix);
        assertNotNull("Pass A trailing-slash quick-fix must produce DIFF preview", passAPreview);
        assertTrue(passAPreview.contains(":package :propulsion/trailing_slash {"));

        // Pass B (26 inspections): Safeguard 1 — 0 duplicate highlights, exact 3 ERROR highlights
        enableAll26StvnInspections();
        PsiFile stage9File = myFixture.configureByText(
            "stage9_trailing_slash_package_recovery_and_nominal_brand_matrix.stvn",
            stage9Src
        );
        var passBErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass B must emit exact 3 ERROR highlights with 0 duplicates: " + passBErrors,
            3, passBErrors.size());
        assertTrue(passBErrors.get(0).getDescription().contains(
            "Trailing slash prohibited in :package path ':propulsion/trailing_slash/'; use ':propulsion/trailing_slash'"));

        // Verify 0 corrupt '//' FQNIs in NamespaceSymbolCollector
        var symbols = StvnNamespaceSymbolCollector.collectSymbols(stage9File, StvnNamespaceScope.DEFS);
        assertTrue("Symbols must never contain corrupt '//' FQNIs: " + symbols,
            symbols.stream().noneMatch(s -> s.name().contains("//")));

        // Apply Pass B RemoveTrailingSlashQuickFix and verify DIFF preview + clean transformation
        myFixture.getEditor().getCaretModel().moveToOffset(slashOffset);
        IntentionAction passBFix = findIntentionContaining("Remove trailing slash ':propulsion/trailing_slash'");
        assertNotNull("Pass B must offer Remove trailing slash quick-fix", passBFix);
        String passBPreview = myFixture.getIntentionPreviewText(passBFix);
        assertNotNull("Pass B trailing-slash quick-fix must produce DIFF preview", passBPreview);
        assertTrue(passBPreview.contains(":package :propulsion/trailing_slash {"));
        myFixture.launchAction(passBFix);
        assertTrue("Quick-fix must strip trailing slash cleanly while preserving '{'",
            myFixture.getEditor().getDocument().getText().contains(":package :propulsion/trailing_slash {"));

        // Verify NestedPackageIllegal skip + Prelude homonym (:Port/) isolation + local E035 strip normalization
        String isolationDoc = """
            {
              :meta {
                #name "trailing_slash_prelude_and_nested_isolation"
                #kind #BODY
              }
              :defs {
                :package :Port/ {
                  :LocalPort :Int
                }
                :package :outer {
                  :package :outer/nested_slash/ {
                    :Blocked :Int
                  }
                }
                :use [
                  :Port/
                ]
                :Healthy :Int
              }
              :type :Healthy
              :body 42
            }
            """;
        PsiFile isoFile = myFixture.configureByText("trailing_slash_prelude_and_nested_isolation.stvn", isolationDoc);
        var isoHighlights = myFixture.doHighlighting();
        var isoErrors = isoHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals(
            "Expected exact 3 ERROR highlights (:Port/ package, nested_slash/ nested package, :Port/ use) with 0 stacked E002 and 0 Prelude warnings: "
                + isoHighlights,
            3,
            isoErrors.size()
        );
        assertTrue("Must not emit false Unqualified Prelude Reference warnings on :Port/",
            isoHighlights.stream().noneMatch(h -> h.getDescription() != null
                && h.getDescription().contains("Standard Library Prelude")));
        assertNull(":package :Port/ must not attach a PsiReference to Prelude :Port",
            isoFile.findReferenceAt(isolationDoc.indexOf(":Port/") + 2));
        assertNull(":use [ :Port/ ] must not attach a PsiReference to Prelude :Port",
            isoFile.findReferenceAt(isolationDoc.lastIndexOf(":Port/") + 2));
    }

    public void testPb3r03_Stage9ConstantNominalBrandHoverAndSuspendedDocumentErrorsGuard() throws IOException {
        enableAll26StvnInspections();

        // 1. Verify 5 exact E027 highlights on composite_and_sum_const_nominal_brand_isolation.stvn
        Path brandIsoPath = SHARED_FIXTURES_ROOT.resolve(
            "syntax/invalid/modules/composite_and_sum_const_nominal_brand_isolation.stvn"
        );
        String brandIsoSrc = Files.readString(brandIsoPath, StandardCharsets.UTF_8);
        myFixture.configureByText("composite_and_sum_const_nominal_brand_isolation.stvn", brandIsoSrc);
        var brandIsoErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected exact 5 E027 ERROR highlights: " + brandIsoErrors, 5, brandIsoErrors.size());

        // 2. Verify 0 errors on nominal_brand_sum_inference_and_nested_option_consts.stvn
        Path validBrandPath = SHARED_FIXTURES_ROOT.resolve(
            "syntax/valid/modules/nominal_brand_sum_inference_and_nested_option_consts.stvn"
        );
        String validBrandSrc = Files.readString(validBrandPath, StandardCharsets.UTF_8);
        myFixture.configureByText("nominal_brand_sum_inference_and_nested_option_consts.stvn", validBrandSrc);
        var validBrandErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue("Valid branded sum inference fixture must emit 0 ERROR highlights: " + validBrandErrors,
            validBrandErrors.isEmpty());

        // 3. Verify Stage 9 cross-package homonym FQNI disambiguation & red In Violation hover
        Path stage9Path = SHARED_FIXTURES_ROOT.resolve(
            "syntax/invalid/packages/stage9_trailing_slash_package_recovery_and_nominal_brand_matrix.stvn"
        );
        String stage9Src = Files.readString(stage9Path, StandardCharsets.UTF_8);
        PsiFile stage9File = myFixture.configureByText(
            "stage9_trailing_slash_package_recovery_and_nominal_brand_matrix.stvn",
            stage9Src
        );
        myFixture.doHighlighting();
        var docProvider = new StvnDocumentationProvider();

        int slot3MismatchOffset = stage9Src.lastIndexOf("#ATTITUDE_PULSE") + 2;
        String slot3Hover = generateHoverAt(docProvider, stage9File, slot3MismatchOffset);
        assertTrue("Hover must preserve <nobr><b>Type:</b> <code>:ThrustMillinewtons</code></nobr>:\n" + slot3Hover,
            slot3Hover.contains("<nobr><b>Type:</b> <code>:ThrustMillinewtons</code></nobr>"));
        assertTrue("Hover must append (:propulsion/attitude/ThrustMillinewtons) outside </nobr>:\n" + slot3Hover,
            slot3Hover.contains("(<code>:propulsion/attitude/ThrustMillinewtons</code>)"));
        assertTrue("Hover must preserve <nobr><b>Slot Schema:</b> <code>:ThrustMillinewtons</code></nobr>:\n" + slot3Hover,
            slot3Hover.contains("<nobr><b>Slot Schema:</b> <code>:ThrustMillinewtons</code></nobr>"));
        assertTrue("Hover must append (:propulsion/thruster/ThrustMillinewtons) outside </nobr>:\n" + slot3Hover,
            slot3Hover.contains("(<code>:propulsion/thruster/ThrustMillinewtons</code>)"));
        assertTrue("Mismatched nominal #CONST in :body must render red In Violation:\n" + slot3Hover,
            slot3Hover.contains(">In Violation</b>"));
        assertTrue("Mismatched nominal #CONST hover must include Core E027 prose:\n" + slot3Hover,
            slot3Hover.contains("Incompatible nominal type: constant '#ATTITUDE_PULSE'"));
        assertTrue("Mismatched nominal #CONST hover must include Exemplar Value: 100:\n" + slot3Hover,
            slot3Hover.contains("<b>Exemplar Value:</b> <code>100</code>"));

        // 4. Safeguard 2: Pass 3 suspended document (missing :type -> payload().isEmpty() == true, BodyEntry present)
        String suspendedDoc = """
            {
              :meta {
                #name "suspended_pass3_hover_guard"
                #kind #BODY
              }
              :defs {
                :package :propulsion/thruster {
                  :ThrustMillinewtons { #unsigned #minIncl 100 #maxExcl 500000 } :Int
                  #CRUISE_THRUST :ThrustMillinewtons 12500
                }
                :package :propulsion/attitude {
                  :ThrustMillinewtons { #unsigned #minIncl 100 #maxExcl 500000 } :Int
                  #RCS_PULSE_THRUST :ThrustMillinewtons 12500
                }
                :use [
                  :propulsion/thruster { #strip }
                  :propulsion/attitude {
                    :ThrustMillinewtons :AttitudeThrust
                    #RCS_PULSE_THRUST #ATTITUDE_PULSE
                  }
                ]
                :RootPair :Tuple(:ThrustMillinewtons :ThrustMillinewtons)
              }
              :type :RootPair
              :defs {
                :DuplicateDefsSuspendsPass3 :Int
              }
              :body (
                #ATTITUDE_PULSE
                #CRUISE_THRUST
              )
            }
            """;
        PsiFile suspendedFile = myFixture.configureByText("suspended_pass3_hover_guard.stvn", suspendedDoc);
        myFixture.doHighlighting();

        // Even when Pass 3 is suspended, pure-PSI § 9.1 brand verification flags #ATTITUDE_PULSE as In Violation!
        int suspMismatchOffset = suspendedDoc.lastIndexOf("#ATTITUDE_PULSE") + 2;
        String suspMismatchHover = generateHoverAt(docProvider, suspendedFile, suspMismatchOffset);
        assertTrue("Pure-PSI brand verification must still render In Violation when Pass 3 is suspended:\n" + suspMismatchHover,
            suspMismatchHover.contains(">In Violation</b>"));
        assertTrue(suspMismatchHover.contains("Incompatible nominal type: constant '#ATTITUDE_PULSE'"));

        // Brand-compatible #CRUISE_THRUST in :body renders Suspended (Document Errors) instead of false Satisfied
        int suspValidConstOffset = suspendedDoc.lastIndexOf("#CRUISE_THRUST") + 2;
        String suspValidConstHover = generateHoverAt(docProvider, suspendedFile, suspValidConstOffset);
        assertTrue("Pass-3-suspended :body #CONST must render Suspended (Document Errors):\n" + suspValidConstHover,
            suspValidConstHover.contains("Suspended (Document Errors)"));
        assertFalse("Pass-3-suspended :body #CONST must omit Exemplar Value:\n" + suspValidConstHover,
            suspValidConstHover.contains("Exemplar Value:"));

        // Safeguard 2: :defs ConstantDefinition RHS literal 12500 must NEVER show Suspended (Document Errors)
        int defsRhsLiteralOffset = suspendedDoc.indexOf("12500") + 1;
        String defsRhsHover = generateHoverAt(docProvider, suspendedFile, defsRhsLiteralOffset);
        assertFalse(":defs ConstantDefinition RHS literal must never render Suspended (Document Errors):\n" + defsRhsHover,
            defsRhsHover.contains("Suspended (Document Errors)"));
        assertTrue(":defs ConstantDefinition RHS literal must remain Satisfied:\n" + defsRhsHover,
            defsRhsHover.contains(">Satisfied</b>"));
    }

    public void testPb3r04_Stage7SampleReadingTraitViolationQuickFixesFiveTierInsertionAndUsageHover() {
        enableAll26StvnInspections();
        String stage7TraitDoc = """
            {
              :meta {
                #name "stage7_sample_reading_trait_remediation"
                #kind #DEFS
              }
              :defs {
                :SensorId { #minSize 1 #maxSize 32 } :String
                :SampleReading { #size 32 } :Float
                :RawReading :Float
                :AliasReading :SampleReading
                :TrapFloatInSet { #maxSize 8 } :Set(:SampleReading)
                :TrapRawInSet { #maxSize 8 } :Set(:RawReading)
                :TrapAliasInSet { #maxSize 8 } :Set(:AliasReading)
                :TrapNonEquatableInvertibleMap { #invertible #maxSize 8 } :Map(:SensorId :SampleReading)
              }
            }
            """;
        PsiFile file = myFixture.configureByText("stage7_sample_reading_trait_remediation.stvn_d", stage7TraitDoc);
        myFixture.doHighlighting();
        var docProvider = new StvnDocumentationProvider();

        // 1. Check :SampleReading inside :Set(:SampleReading) (has #size 32 -> Jump + Override #equatable #TRUE; no #exact)
        int setSampleOffset = stage7TraitDoc.indexOf(":Set(:SampleReading)") + ":Set(".length() + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(setSampleOffset);
        IntentionAction jumpSample = findIntentionContaining("Jump to ':SampleReading' declaration in :defs");
        assertNotNull("Must offer Jump to ':SampleReading' declaration in :defs", jumpSample);
        assertNull("Jump intention must return EMPTY (null diff) preview",
            myFixture.getIntentionPreviewText(jumpSample));
        IntentionAction overrideOnSample = findIntentionContaining("Override '#equatable #TRUE' on :SampleReading in :defs");
        assertNotNull("Must offer Override '#equatable #TRUE' on :SampleReading in :defs", overrideOnSample);
        String overridePreview = myFixture.getIntentionPreviewText(overrideOnSample);
        assertNotNull("Override '#equatable #TRUE' must produce DIFF preview", overridePreview);
        assertTrue(overridePreview.contains(":SampleReading { #size 32 #equatable #TRUE } :Float"));
        assertNull("Must suppress Add '#exact' when :SampleReading declares mutually exclusive #size 32",
            findIntentionContaining("Add '#exact' to :SampleReading in :defs"));

        // 2. Check :RawReading inside :Set(:RawReading) (unadorned :Float -> Jump + Add '#exact' + Override)
        int setRawOffset = stage7TraitDoc.indexOf(":Set(:RawReading)") + ":Set(".length() + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(setRawOffset);
        assertNotNull("Must offer Jump to ':RawReading' declaration in :defs",
            findIntentionContaining("Jump to ':RawReading' declaration in :defs"));
        IntentionAction addExactOnRaw = findIntentionContaining("Add '#exact' to :RawReading in :defs");
        assertNotNull("Must offer Add '#exact' to :RawReading in :defs on unrestricted nominal :Float", addExactOnRaw);

        // 3. Check :SampleReading inside { #invertible #maxSize 8 } :Map(:SensorId :SampleReading) (Subcase 3 unlocked!)
        int invMapSampleOffset = stage7TraitDoc.indexOf(":Map(:SensorId :SampleReading)")
            + ":Map(:SensorId ".length() + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(invMapSampleOffset);
        assertNotNull("Inverted map nominal value must offer Jump to ':SampleReading' declaration in :defs",
            findIntentionContaining("Jump to ':SampleReading' declaration in :defs"));
        assertNotNull("Inverted map nominal value must offer Override '#equatable #TRUE' on :SampleReading in :defs",
            findIntentionContaining("Override '#equatable #TRUE' on :SampleReading in :defs"));
        assertNotNull("Inverted map nominal value must ALSO offer co-located Remove '#invertible' facet",
            findIntentionContaining("Remove '#invertible' facet from :TrapNonEquatableInvertibleMap"));

        // 4. Safeguard 3: Usage-site hover vs. declaration-site hover on :SampleReading and :AliasReading
        String setUsageHover = generateHoverAt(docProvider, file, setSampleOffset);
        assertTrue("Set usage-site hover must render Trait Violation at Usage Site banner:\n" + setUsageHover,
            setUsageHover.contains("Trait Violation at Usage Site"));
        assertTrue("Set usage-site hover must include psi_element://TypeDefinition/:SampleReading link:\n" + setUsageHover,
            setUsageHover.contains("psi_element://TypeDefinition/:SampleReading"));
        assertTrue("Set usage-site hover must show Derived from :Float provenance:\n" + setUsageHover,
            setUsageHover.contains("continuous float lacks <code>#equatable</code>"));
        assertTrue("Set usage-site hover must mark #equatable Status: Invalid:\n" + setUsageHover,
            setUsageHover.contains(">Invalid</b>"));
        assertFalse("Set usage-site hover must suppress Exemplar Value:\n" + setUsageHover,
            setUsageHover.contains("Exemplar Value:"));

        PsiElement linkedElem = docProvider.getDocumentationElementForLink(
            PsiManager.getInstance(getProject()),
            "TypeDefinition/:SampleReading",
            file.findElementAt(setSampleOffset)
        );
        assertTrue("psi_element://TypeDefinition/:SampleReading must resolve to TypeDefinition",
            linkedElem instanceof TypeDefinition);

        String invMapUsageHover = generateHoverAt(docProvider, file, invMapSampleOffset);
        assertTrue("Invertible map value hover must render Trait Violation at Usage Site banner:\n" + invMapUsageHover,
            invMapUsageHover.contains("Trait Violation at Usage Site")
                && invMapUsageHover.contains("Inverted map values"));

        int aliasSetOffset = stage7TraitDoc.indexOf(":Set(:AliasReading)") + ":Set(".length() + 2;
        String aliasUsageHover = generateHoverAt(docProvider, file, aliasSetOffset);
        assertTrue("Multi-hop alias usage-site hover must render Inherited from :SampleReading provenance:\n" + aliasUsageHover,
            aliasUsageHover.contains("Inherited from") && aliasUsageHover.contains(":SampleReading"));

        // Declaration-site hover on :RawReading :Float must preserve Default (:Float) and Exemplar Value: 0.0
        int rawDeclOffset = stage7TraitDoc.indexOf(":RawReading :Float") + 2;
        String rawDeclHover = generateHoverAt(docProvider, file, rawDeclOffset);
        assertFalse("Declaration-site hover must NOT show Trait Violation at Usage Site banner:\n" + rawDeclHover,
            rawDeclHover.contains("Trait Violation at Usage Site"));
        assertTrue("Declaration-site hover must preserve Default (:Float):\n" + rawDeclHover,
            rawDeclHover.contains("Default (:Float)"));
        assertTrue("Declaration-site hover must preserve Exemplar Value: 0.0:\n" + rawDeclHover,
            rawDeclHover.contains("<b>Exemplar Value:</b> <code>0.0</code>"));

        // 5. Apply Override '#equatable #TRUE' on :SampleReading { #size 32 } :Float and verify 5-tier rank (after #size 32)
        myFixture.getEditor().getCaretModel().moveToOffset(setSampleOffset);
        IntentionAction liveOverride = findIntentionContaining("Override '#equatable #TRUE' on :SampleReading in :defs");
        assertNotNull(liveOverride);
        myFixture.launchAction(liveOverride);
        String mutated = myFixture.getEditor().getDocument().getText();
        assertTrue("5-tier insertion must place #equatable #TRUE AFTER Tier-1 #size 32:\n" + mutated,
            mutated.contains(":SampleReading { #size 32 #equatable #TRUE } :Float"));
        var postHealFacetOrderErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
                && h.getDescription() != null
                && h.getDescription().contains("ERR_FACET_ORDER_VIOLATION"))
            .toList();
        assertTrue("5-tier insertion must produce 0 ERR_FACET_ORDER_VIOLATION cascades: " + postHealFacetOrderErrors,
            postHealFacetOrderErrors.isEmpty());
    }

    public void testPb3r05a_Stage8OutOfRangeUnionWrapperDualLocusHoverViolated() {
        enableAll26StvnInspections();
        String stage8WrapperDoc = """
            {
              :meta {
                #name "stage8_invalid_union_wrapper_hover"
                #kind #BODY
              }
              :defs {
                :ConfidenceRatio { #minIncl 0.0 #maxExcl 1.0 } :Float
                :TelemetryBranch :Union(:Boolean :ConfidenceRatio)
              }
              :type :Tuple(
                :TelemetryBranch
                :Int
              )
              :body (
                #3 #TRUE
                #Left 42
              )
            }
            """;
        PsiFile file = myFixture.configureByText("stage8_invalid_union_wrapper_hover.stvn", stage8WrapperDoc);
        myFixture.doHighlighting();
        var docProvider = new StvnDocumentationProvider();

        int tag3Offset = stage8WrapperDoc.indexOf("#3 #TRUE") + 1;
        int innerTrueOffset = stage8WrapperDoc.indexOf("#3 #TRUE") + "#3 #".length();

        String hoverOnTag3 = generateHoverAt(docProvider, file, tag3Offset);
        assertTrue("Hovering #3 on 2-branch :Union must render Violated:\n" + hoverOnTag3,
            hoverOnTag3.contains(">Violated</b>"));
        assertTrue("Hovering #3 must display Union variant tag '#3' exceeds branch count (2):\n" + hoverOnTag3,
            hoverOnTag3.contains("Union variant tag '#3' exceeds branch count (2)"));
        assertFalse("Hovering #3 must never leak 'Boolean literal #TRUE conforms to :Boolean':\n" + hoverOnTag3,
            hoverOnTag3.contains("conforms to <code>:Boolean</code>"));

        String hoverOnTrue = generateHoverAt(docProvider, file, innerTrueOffset);
        assertTrue("Hovering #TRUE inside '#3 #TRUE' must ALSO render Violated:\n" + hoverOnTrue,
            hoverOnTrue.contains(">Violated</b>"));
        assertTrue("Hovering #TRUE inside '#3 #TRUE' must display Union variant tag '#3' exceeds branch count (2):\n" + hoverOnTrue,
            hoverOnTrue.contains("Union variant tag '#3' exceeds branch count (2)"));
        assertFalse("Hovering #TRUE inside '#3 #TRUE' must never claim Satisfied:\n" + hoverOnTrue,
            hoverOnTrue.contains(">Satisfied</b>"));

        int badLeftOffset = stage8WrapperDoc.indexOf("#Left 42") + "#Left ".length();
        String hoverOnBadLeft = generateHoverAt(docProvider, file, badLeftOffset);
        assertTrue("Hovering 42 inside '#Left 42' on :Int must render Violated:\n" + hoverOnBadLeft,
            hoverOnBadLeft.contains(">Violated</b>"));
        assertFalse("Hovering 42 inside '#Left 42' on :Int must never claim Satisfied:\n" + hoverOnBadLeft,
            hoverOnBadLeft.contains(">Satisfied</b>"));
    }

    private IntentionAction findIntentionContaining(String substring) {
        List<IntentionAction> available = myFixture.getAvailableIntentions();
        return available.stream()
            .filter(a -> a.getText().contains(substring))
            .findFirst()
            .orElse(null);
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
