package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end IntelliJ Platform verification suite for Hover Trait-Violation Gating,
 * Constructor-Accurate Constituent Labels, and Named Leaf Attribution
 * ({@code HTVG-01} through {@code HTVG-03} and Mandatory Safeguards 1 through 3).
 */
@NullMarked
public final class StvnHoverTraitViolationGatingTest extends BasePlatformTestCase {

    private static final Path SHARED_FIXTURES_ROOT = Paths.get("src", "test", "resources", "shared-fixtures");

    private static final Pattern CONSTITUENT_LINK_PATTERN =
        Pattern.compile("psi_element://((?:file_)?offset/[^\"]+)");

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

    public void testHtvg01And02And03_Stage8HealthySumAndProductSchemasOmitAllRedTextAndAttributeNamedLeaves() throws IOException {
        enableAll26StvnInspections();
        var docProvider = new StvnDocumentationProvider();

        // 1. Mirrored Stage 8 fixture: verify :TelemetryBranch, :TelemetryBranchAlias, :InnerOutcome, :OuterDispatchEnvelope
        Path stage8Path = SHARED_FIXTURES_ROOT.resolve(
            "syntax/invalid/composites/stage8_union_branch_overflow_and_const_sum_spine.stvn"
        );
        String stage8Src = Files.readString(stage8Path, StandardCharsets.UTF_8);
        PsiFile stage8File = myFixture.configureByText(
            "stage8_union_branch_overflow_and_const_sum_spine.stvn",
            stage8Src
        );
        myFixture.doHighlighting();

        String telemetryHover = generateHoverAt(docProvider, stage8File, stage8Src.indexOf(":TelemetryBranch :") + 2);
        assertZeroRedTraitViolationHtml("Stage 8 :TelemetryBranch", telemetryHover);
        assertTrue("Expected Derived (non-equatable: :ConfidenceRatio) on :TelemetryBranch:\n" + telemetryHover,
            telemetryHover.contains("Derived (non-equatable: :ConfidenceRatio)"));

        String innerHover = generateHoverAt(docProvider, stage8File, stage8Src.indexOf(":InnerOutcome :") + 2);
        assertZeroRedTraitViolationHtml("Stage 8 :InnerOutcome", innerHover);
        assertTrue("Expected concise named leaf Derived (non-equatable: :TelemetryBranchAlias) on :InnerOutcome:\n" + innerHover,
            innerHover.contains("Derived (non-equatable: :TelemetryBranchAlias)"));
        assertFalse("Must NOT dump raw inline :Either constructor in :InnerOutcome Origin:\n" + innerHover,
            innerHover.contains("non-equatable: :Either"));

        String outerHover = generateHoverAt(docProvider, stage8File, stage8Src.indexOf(":OuterDispatchEnvelope :") + 2);
        assertZeroRedTraitViolationHtml("Stage 8 :OuterDispatchEnvelope", outerHover);
        assertTrue("Expected concise named leaf Derived (non-equatable: :InnerOutcome) on :OuterDispatchEnvelope:\n" + outerHover,
            outerHover.contains("Derived (non-equatable: :InnerOutcome)"));
        assertFalse("Must NOT dump raw inline :Either constructor in :OuterDispatchEnvelope Origin:\n" + outerHover,
            outerHover.contains("non-equatable: :Either"));

        // RHS constructor hovers (:Union, :Option, :Either) on Stage 8 healthy definitions must also be 100% red-free
        int outerDefStart = stage8Src.indexOf(":OuterDispatchEnvelope :");
        String outerOptionCtorHover = generateHoverAt(docProvider, stage8File, stage8Src.indexOf(":Option(", outerDefStart) + 2);
        String outerEitherCtorHover = generateHoverAt(docProvider, stage8File, stage8Src.indexOf(":Either(", outerDefStart) + 2);
        assertZeroRedTraitViolationHtml("Stage 8 RHS :Option", outerOptionCtorHover);
        assertZeroRedTraitViolationHtml("Stage 8 RHS :Either", outerEitherCtorHover);

        // 2. Healthy composite matrix: :Tuple(:Int :ConfidenceRatio), :Tuple(:Int :Set(:Int)), :Seq(:ConfidenceRatio),
        //    non-invertible :Map(:SensorId :ConfidenceRatio), and valid capability-restoration overrides (TC-TUP-04)
        String healthyMatrixDoc = """
            {
              :meta {
                #name "healthy_inductive_traits_matrix"
                #kind #BODY
              }
              :defs {
                :SensorId { #minSize 1 #maxSize 32 } :String
                :RetryMillis { #unsigned #size 16 #minIncl 10 #maxExcl 5000 } :Int
                :ConfidenceRatio { #minIncl 0.0 #maxExcl 1.0 } :Float
                :TelemetryBranch :Union( :Boolean :ConfidenceRatio )
                :TelemetryBranchAlias :TelemetryBranch
                :InnerOutcome :Option( :Either( :SensorId :TelemetryBranchAlias ) )
                :OuterDispatchEnvelope :Option(
                  :Either(
                    :RetryMillis
                    :InnerOutcome
                  )
                )
                :FloatTuple :Tuple( :Int :ConfidenceRatio )
                :SetTuple :Tuple( :Int :Set( :Int ) )
                :FloatSeq :Seq( :ConfidenceRatio )
                :StandardFloatMap :Map( :SensorId :ConfidenceRatio )
                :RestoredEquatableTuple { #equatable #TRUE } :Tuple( :Int :ConfidenceRatio )
                :RestoredComparableTuple { #comparable #TRUE } :Tuple( :Int :Set( :Int ) )

                #ENVELOPE_SAMPLE :OuterDispatchEnvelope 0.75
              }
              :type :Tuple(
                :OuterDispatchEnvelope
                :TelemetryBranch
                :FloatTuple
                :SetTuple
                :FloatSeq
                :StandardFloatMap
                :RestoredEquatableTuple
                :RestoredComparableTuple
              )
              :body (
                #ENVELOPE_SAMPLE
                #TRUE
                ( 1 0.5 )
                ( 2 [ 10 ] )
                [ 0.25 ]
                { [ "s1" 0.1 ] }
                ( 3 0.9 )
                ( 4 [ 20 ] )
              )
            }
            """;
        PsiFile healthyFile = myFixture.configureByText("healthy_inductive_traits_matrix.stvn", healthyMatrixDoc);
        List<HighlightInfo> healthyErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue("Healthy Stage 8 composite matrix must emit 0 ERROR highlights: " + healthyErrors,
            healthyErrors.isEmpty());

        String[] healthyAliases = {
            ":TelemetryBranch ",
            ":InnerOutcome ",
            ":OuterDispatchEnvelope ",
            ":FloatTuple ",
            ":SetTuple ",
            ":FloatSeq ",
            ":StandardFloatMap ",
            ":RestoredEquatableTuple ",
            ":RestoredComparableTuple "
        };
        for (String aliasProbe : healthyAliases) {
            int declOffset = healthyMatrixDoc.indexOf(aliasProbe) + 2;
            String declHover = generateHoverAt(docProvider, healthyFile, declOffset);
            assertZeroRedTraitViolationHtml("Declaration " + aliasProbe.trim(), declHover);
        }

        // Verify multi-line inline :Either inside :OuterDispatchEnvelope still resolves cleanly to :InnerOutcome
        String multiLineOuterHover = generateHoverAt(
            docProvider, healthyFile, healthyMatrixDoc.indexOf(":OuterDispatchEnvelope ") + 2
        );
        assertTrue("Multi-line :OuterDispatchEnvelope must attribute :InnerOutcome:\n" + multiLineOuterHover,
            multiLineOuterHover.contains("Derived (non-equatable: :InnerOutcome)"));

        // Verify :Tuple(:Int :Set(:Int)) reports Derived (non-comparable: :Set) with 0 red HTML
        String setTupleHover = generateHoverAt(docProvider, healthyFile, healthyMatrixDoc.indexOf(":SetTuple ") + 2);
        assertTrue("Expected Derived (all constituent fields conform) for #equatable on :SetTuple:\n" + setTupleHover,
            setTupleHover.contains("Derived (all constituent fields conform)"));
        assertTrue("Expected concise Derived (non-comparable: :Set) for #comparable on :SetTuple:\n" + setTupleHover,
            setTupleHover.contains("Derived (non-comparable: :Set)"));

        // Verify valid capability-restoration overrides render Active Override and 0 red HTML
        String restoredEqHover = generateHoverAt(
            docProvider, healthyFile, healthyMatrixDoc.indexOf(":RestoredEquatableTuple ") + 2
        );
        assertTrue("Capability restoration { #equatable #TRUE } must render Active Override:\n" + restoredEqHover,
            restoredEqHover.contains("Active Override"));
    }

    public void testSafeguard2_OuterDispatchEnvelopeInSetHighlightsOffersElementJumpAndRendersViolationHover() {
        enableAll26StvnInspections();
        var docProvider = new StvnDocumentationProvider();

        String envelopeSetDoc = """
            {
              :meta {
                #name "safeguard2_envelope_set_violation"
                #kind #DEFS
              }
              :defs {
                :SensorId { #minSize 1 #maxSize 32 } :String
                :RetryMillis { #unsigned #size 16 #minIncl 10 #maxExcl 5000 } :Int
                :ConfidenceRatio { #minIncl 0.0 #maxExcl 1.0 } :Float
                :TelemetryBranch :Union( :Boolean :ConfidenceRatio )
                :TelemetryBranchAlias :TelemetryBranch
                :InnerOutcome :Option( :Either( :SensorId :TelemetryBranchAlias ) )
                :OuterDispatchEnvelope :Option( :Either( :RetryMillis :InnerOutcome ) )
                :BadEnvelopeSet :Set( :OuterDispatchEnvelope )
              }
            }
            """;
        PsiFile file = myFixture.configureByText("safeguard2_envelope_set_violation.stvn_d", envelopeSetDoc);
        List<HighlightInfo> errors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected exact 1 E008-TRAIT_VIOLATION error on :Set( :OuterDispatchEnvelope ): " + errors,
            1, errors.size());
        HighlightInfo traitErr = errors.get(0);
        assertEquals(":OuterDispatchEnvelope", traitErr.getText());
        assertTrue("Expected [stvn-core:E008-TRAIT_VIOLATION] suffix: " + traitErr.getDescription(),
            traitErr.getDescription() != null
                && traitErr.getDescription().contains("[stvn-core:E008-TRAIT_VIOLATION]"));

        // 1. Declaration site of :OuterDispatchEnvelope STILL remains calm (0 red sections!)
        int declOffset = envelopeSetDoc.indexOf(":OuterDispatchEnvelope :Option") + 2;
        String declHover = generateHoverAt(docProvider, file, declOffset);
        assertZeroRedTraitViolationHtml("Declaration-site :OuterDispatchEnvelope", declHover);
        assertTrue(declHover.contains("Derived (non-equatable: :InnerOutcome)"));

        // 2. Usage site inside :Set( :OuterDispatchEnvelope ): Alt+Enter quick-fixes + Case 3 red hover breakdown
        int setUsageOffset = envelopeSetDoc.indexOf(":OuterDispatchEnvelope", envelopeSetDoc.indexOf(":BadEnvelopeSet")) + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(setUsageOffset);

        IntentionAction jumpIntention = findIntentionContaining(
            "Jump to non-equatable constituent in :OuterDispatchEnvelope (Element: :InnerOutcome)"
        );
        assertNotNull("Expected Element: :InnerOutcome jump intention on :OuterDispatchEnvelope in :Set", jumpIntention);
        IntentionAction overrideFix = findIntentionContaining(
            "Override '#equatable #TRUE' on :OuterDispatchEnvelope in :defs"
        );
        assertNotNull("Expected Override '#equatable #TRUE' quick-fix on :OuterDispatchEnvelope in :Set", overrideFix);

        String usageHover = generateHoverAt(docProvider, file, setUsageOffset);
        assertTrue("Usage-site hover must render Trait Violation at Usage Site banner:\n" + usageHover,
            usageHover.contains("Trait Violation at Usage Site"));
        assertTrue("Usage-site hover must mark #equatable Status: Invalid:\n" + usageHover,
            usageHover.contains(">Invalid</b>"));
        assertTrue("Usage-site hover must render Non-Conforming Constituents with Element: :InnerOutcome:\n" + usageHover,
            usageHover.contains("<b>Non-Conforming Constituents:</b>")
                && usageHover.contains("Element:")
                && usageHover.contains("<code>:InnerOutcome</code>"));
        assertAllConstituentLinksResolve(docProvider, file, setUsageOffset, usageHover);

        // 3. Container declaration :BadEnvelopeSet hover (Case 1): renders Element: :OuterDispatchEnvelope
        int badSetOffset = envelopeSetDoc.indexOf(":BadEnvelopeSet") + 2;
        String badSetHover = generateHoverAt(docProvider, file, badSetOffset);
        assertTrue("Container :BadEnvelopeSet hover must render Degraded Schema Warning:\n" + badSetHover,
            badSetHover.contains("Degraded Schema Warning"));
        assertTrue("Container :BadEnvelopeSet hover must render Non-Conforming Constituents with Element: :OuterDispatchEnvelope:\n"
            + badSetHover,
            badSetHover.contains("<b>Non-Conforming Constituents:</b>")
                && badSetHover.contains("Element:")
                && badSetHover.contains("<code>:OuterDispatchEnvelope</code>"));
        assertAllConstituentLinksResolve(docProvider, file, badSetOffset, badSetHover);

        // Launch jump intention and verify caret lands on :InnerOutcome inside :OuterDispatchEnvelope's RHS
        myFixture.getEditor().getCaretModel().moveToOffset(setUsageOffset);
        myFixture.launchAction(jumpIntention);
        assertEquals(":InnerOutcome", myFixture.getEditor().getSelectionModel().getSelectedText());
    }

    public void testHtvg01And02And03_Stage7ContainersCase2OverridesCase3UsageSitesAndCrossFileLinks() {
        enableAll26StvnInspections();
        var docProvider = new StvnDocumentationProvider();

        // Cross-file included module with a violating :Set container to verify file_offset/ link resolution
        myFixture.addFileToProject(
            "remote_bad_containers.telemetry.stvn_di",
            """
            {
              :meta {
                #name "remote_bad_containers"
                #domain "telemetry"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :RemoteSample :Float
                :RemoteTrapSet :Set( :RemoteSample )
              }
            }
            """
        );

        String violationsDoc = """
            {
              :meta {
                #name "stage7_and_constructor_role_matrix"
                #domain "telemetry"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "remote_bad_containers.telemetry.stvn_di" ]
                :SensorId { #minSize 1 #maxSize 32 } :String
                :SampleReading :Float
                :ConfidenceRatio { #minIncl 0.0 #maxExcl 1.0 } :Float

                // Case 1: Intrinsic Container Violations (Stage 7 + :Map Key + Derived #invertible :Map)
                :TrapFloatInSet { #maxSize 8 } :Set( :SampleReading )
                :TrapNonEquatableInvertibleMap { #invertible #maxSize 8 } :Map( :SensorId :SampleReading )
                :HealthyBaseMap :Map( :SensorId :SampleReading )
                :DerivedInvertibleMap { #invertible } :HealthyBaseMap
                :TrapFloatKeyMap :Map( :SampleReading :SensorId )
                :LocalChildOfRemoteSet :RemoteTrapSet

                // Case 2: Illegal Explicit #comparable #TRUE Overrides Across All Constructors
                :BadCompOption { #comparable #TRUE } :Option( :ConfidenceRatio )
                :BadCompSeq { #comparable #TRUE } :Seq( :ConfidenceRatio )
                :BadCompEitherLeft { #comparable #TRUE } :Either( :ConfidenceRatio :Int )
                :BadCompEitherRight { #comparable #TRUE } :Either( :Int :ConfidenceRatio )
                :BadCompUnion { #comparable #TRUE } :Union( :Boolean :ConfidenceRatio )
                :BadCompTuple { #comparable #TRUE } :Tuple( :Int :ConfidenceRatio )

                // Healthy composite aliases tested at Case 3 violating usage sites
                :HealthyUnion :Union( :Boolean :ConfidenceRatio )
                :HealthyEitherRight :Either( :Int :ConfidenceRatio )
                :HealthyTuple :Tuple( :Int :ConfidenceRatio )
                :UsageSetUnion :Set( :HealthyUnion )
                :UsageMapKeyEither :Map( :HealthyEitherRight :SensorId )
                :UsageInvMapTuple { #invertible } :Map( :SensorId :HealthyTuple )
              }
              :type :Set( :SampleReading )
              :body []
            }
            """;
        PsiFile file = myFixture.configureByText("stage7_and_constructor_role_matrix.telemetry.stvn_i", violationsDoc);
        myFixture.doHighlighting();

        // 1. Stage 7 Case 1 Containers: Element, Value, Key, and Derived #invertible Map
        String floatSetHover = generateHoverAt(docProvider, file, violationsDoc.indexOf(":TrapFloatInSet") + 2);
        assertTrue(floatSetHover.contains("<b>Non-Conforming Constituents:</b>")
            && floatSetHover.contains("Element:")
            && floatSetHover.contains("<code>:SampleReading</code>"));
        assertFalse(":Set must never label slot as Field #1:\n" + floatSetHover, floatSetHover.contains("Field #1:"));

        String invMapHover = generateHoverAt(docProvider, file, violationsDoc.indexOf(":TrapNonEquatableInvertibleMap") + 2);
        assertTrue(invMapHover.contains("<b>Non-Conforming Constituents:</b>")
            && invMapHover.contains("Value:")
            && invMapHover.contains("<code>:SampleReading</code>"));
        assertFalse(":Map must never label value slot as Field #2:\n" + invMapHover, invMapHover.contains("Field #2:"));

        String healthyBaseMapHover = generateHoverAt(docProvider, file, violationsDoc.indexOf(":HealthyBaseMap ") + 2);
        assertZeroRedTraitViolationHtml(":HealthyBaseMap", healthyBaseMapHover);

        String derivedInvHover = generateHoverAt(docProvider, file, violationsDoc.indexOf(":DerivedInvertibleMap") + 2);
        assertTrue("Derived #invertible map must report Value: :SampleReading:\n" + derivedInvHover,
            derivedInvHover.contains("Derived (non-equatable: :SampleReading)")
                && derivedInvHover.contains("<b>Non-Conforming Constituents:</b>")
                && derivedInvHover.contains("Value:")
                && derivedInvHover.contains("<code>:SampleReading</code>"));

        String keyMapHover = generateHoverAt(docProvider, file, violationsDoc.indexOf(":TrapFloatKeyMap") + 2);
        assertTrue("Non-equatable :Map key must render Key: :SampleReading:\n" + keyMapHover,
            keyMapHover.contains("<b>Non-Conforming Constituents:</b>")
                && keyMapHover.contains("Key:")
                && keyMapHover.contains("<code>:SampleReading</code>"));

        // Cross-file :LocalChildOfRemoteSet must resolve file_offset/ constituent link into remote_bad_containers
        int crossChildOffset = violationsDoc.indexOf(":LocalChildOfRemoteSet") + 2;
        String crossChildHover = generateHoverAt(docProvider, file, crossChildOffset);
        assertTrue(crossChildHover.contains("Element:") && crossChildHover.contains("<code>:RemoteSample</code>"));
        assertAllConstituentLinksResolve(docProvider, file, crossChildOffset, crossChildHover);

        // Inline :type :Set( :SampleReading ) constructor hover
        int inlineTypeSetOffset = violationsDoc.indexOf(":Set( :SampleReading )", violationsDoc.indexOf(":type")) + 2;
        String inlineSetHover = generateHoverAt(docProvider, file, inlineTypeSetOffset);
        assertTrue("Inline violating :Set constructor hover must render Element: :SampleReading:\n" + inlineSetHover,
            inlineSetHover.contains("<b>Non-Conforming Constituents:</b>")
                && inlineSetHover.contains("Element:")
                && inlineSetHover.contains("<code>:SampleReading</code>"));

        // 2. Case 2: Illegal Explicit #comparable #TRUE Overrides Across All Constructors
        assertCase2IllegalComparableHover(docProvider, file, violationsDoc, ":BadCompOption", "Element:");
        assertCase2IllegalComparableHover(docProvider, file, violationsDoc, ":BadCompSeq", "Element:");
        assertCase2IllegalComparableHover(docProvider, file, violationsDoc, ":BadCompEitherLeft", "Left branch:");
        assertCase2IllegalComparableHover(docProvider, file, violationsDoc, ":BadCompEitherRight", "Right branch:");
        assertCase2IllegalComparableHover(docProvider, file, violationsDoc, ":BadCompUnion", "Branch #2:");
        assertCase2IllegalComparableHover(docProvider, file, violationsDoc, ":BadCompTuple", "Field #2:");

        // 3. Case 3: Healthy Aliases Hovered at Violating Usage Sites + Quick-Fix Intention Role Labels
        int unionUsageOffset = violationsDoc.indexOf(":HealthyUnion", violationsDoc.indexOf(":UsageSetUnion")) + 2;
        String unionUsageHover = generateHoverAt(docProvider, file, unionUsageOffset);
        assertTrue(unionUsageHover.contains("Trait Violation at Usage Site")
            && unionUsageHover.contains("Branch #2:")
            && unionUsageHover.contains("<code>:ConfidenceRatio</code>"));

        myFixture.getEditor().getCaretModel().moveToOffset(unionUsageOffset);
        assertNotNull("Expected (Branch #2: :ConfidenceRatio) jump intention",
            findIntentionContaining("Jump to non-equatable constituent in :HealthyUnion (Branch #2: :ConfidenceRatio)"));

        int eitherKeyOffset = violationsDoc.indexOf(":HealthyEitherRight", violationsDoc.indexOf(":UsageMapKeyEither")) + 2;
        String eitherKeyHover = generateHoverAt(docProvider, file, eitherKeyOffset);
        assertTrue(eitherKeyHover.contains("Trait Violation at Usage Site")
            && eitherKeyHover.contains("Right branch:")
            && eitherKeyHover.contains("<code>:ConfidenceRatio</code>"));

        int tupleValOffset = violationsDoc.indexOf(":HealthyTuple", violationsDoc.indexOf(":UsageInvMapTuple")) + 2;
        String tupleValHover = generateHoverAt(docProvider, file, tupleValOffset);
        assertTrue(tupleValHover.contains("Trait Violation at Usage Site")
            && tupleValHover.contains("Field #2:")
            && tupleValHover.contains("<code>:ConfidenceRatio</code>"));
    }

    private void assertCase2IllegalComparableHover(
        StvnDocumentationProvider docProvider,
        PsiFile file,
        String src,
        String aliasName,
        String expectedRolePrefix
    ) {
        int offset = src.indexOf(aliasName) + 2;
        String hover = generateHoverAt(docProvider, file, offset);
        assertTrue(aliasName + " must mark illegal #comparable #TRUE override Invalid:\n" + hover,
            hover.contains(">Invalid</b>"));
        assertTrue(aliasName + " must render Non-Conforming Constituents with " + expectedRolePrefix + ":\n" + hover,
            hover.contains("<b>Non-Conforming Constituents:</b>")
                && hover.contains(expectedRolePrefix)
                && hover.contains("<code>:ConfidenceRatio</code>"));
        assertAllConstituentLinksResolve(docProvider, file, offset, hover);
    }

    private void assertZeroRedTraitViolationHtml(String label, String hoverHtml) {
        String lower = hoverHtml.toLowerCase(Locale.ROOT);
        assertFalse(label + " must omit 'Non-Conforming Constituents':\n" + hoverHtml,
            hoverHtml.contains("Non-Conforming Constituents"));
        assertFalse(label + " must omit 'breaking inductive trait bubbling':\n" + hoverHtml,
            hoverHtml.contains("breaking inductive trait bubbling"));
        assertFalse(label + " must omit 'Trait Violation at Usage Site':\n" + hoverHtml,
            hoverHtml.contains("Trait Violation at Usage Site"));
        for (String redHex : List.of("#ff6b6b", "#d32f2f", "#e57373", "#c62828")) {
            assertFalse(label + " must contain 0 red hex spans (" + redHex + "):\n" + hoverHtml,
                lower.contains(redHex));
        }
    }

    private void assertAllConstituentLinksResolve(
        StvnDocumentationProvider provider,
        PsiFile file,
        int hoverOffset,
        String html
    ) {
        Matcher m = CONSTITUENT_LINK_PATTERN.matcher(html);
        int count = 0;
        PsiElement leaf = file.findElementAt(hoverOffset);
        assertNotNull(leaf);
        PsiElement docTarget = provider.getCustomDocumentationElement(myFixture.getEditor(), file, leaf, hoverOffset);
        if (docTarget == null) {
            var ref = file.findReferenceAt(hoverOffset);
            docTarget = ref != null ? ref.resolve() : leaf;
        }
        assertNotNull(docTarget);
        PsiManager psiMgr = PsiManager.getInstance(getProject());
        while (m.find()) {
            count++;
            String link = m.group(1);
            PsiElement resolved = provider.getDocumentationElementForLink(psiMgr, link, docTarget);
            assertNotNull("Expected constituent link '" + link + "' to resolve to non-null PSI element", resolved);
        }
        assertTrue("Expected at least 1 constituent hyperlink in:\n" + html, count >= 1);
    }

    private IntentionAction findIntentionContaining(String substring) {
        return myFixture.getAvailableIntentions().stream()
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
