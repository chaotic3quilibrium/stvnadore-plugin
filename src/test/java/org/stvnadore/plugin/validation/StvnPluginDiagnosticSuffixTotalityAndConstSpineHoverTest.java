package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end IntelliJ Platform verification suite for {@code PDST-01} through {@code PDST-04}
 * and Mandatory Safeguards 1 through 4:
 * <ul>
 *   <li>100% standardized {@code [stvn-(core|plugin):<CODE>-<SYMBOLIC_ID>]} suffix totality across
 *       editor annotators and all 26 local inspections ({@code Pass A} vs. {@code Pass B})</li>
 *   <li>Multi-layer explicit and implicit sum wrapper unwrapping ({@code #3 #TRUE}, {@code #Some #None},
 *       {@code #Right #None}, {@code #Some #Left 10}, {@code #2 #1}, {@code #2 #3}, {@code #Left #3 #TRUE})</li>
 *   <li>Hover violation/warning suffix totality and concrete {@code :Union} / composite valid lexical forms</li>
 *   <li>{@code :defs} {@code #CONST} &amp; RHS {@code Value} Pure-IR inferred sum-spine hover
 *       ({@code Expanded Value:}, {@code Inferred Defaults:}, clickable {@code Resolution Path:})</li>
 * </ul>
 */
@NullMarked
public final class StvnPluginDiagnosticSuffixTotalityAndConstSpineHoverTest extends BasePlatformTestCase {

    private static final Path SHARED_FIXTURES_ROOT = Paths.get("src", "test", "resources", "shared-fixtures");

    private static final Pattern COMPLETE_SUFFIX_ANYWHERE_PATTERN =
        Pattern.compile("\\[stvn-(?:core|plugin):(?:E0[0-9]{2}|W00[1-3]|PW0[0-9]{2})-[A-Z0-9_]+\\]");

    private static final Pattern TYPE_DEF_HREF_PATTERN =
        Pattern.compile("psi_element://(TypeDefinition/[^\"]+)");

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

    public void testPdst01_PassAAndPassBSuffixTotalityMultiLayerUnionUnwrappingAndIncludeTooltip() throws IOException {
        Path stage8Path = SHARED_FIXTURES_ROOT.resolve(
            "syntax/invalid/composites/stage8_union_branch_overflow_and_const_sum_spine.stvn"
        );
        String stage8Src = Files.readString(stage8Path, StandardCharsets.UTF_8);

        // Pass A (0 inspections): verify exact [1218, 1220) #3 pinning & standardized suffix
        myFixture.configureByText("stage8_union_branch_overflow_and_const_sum_spine.stvn", stage8Src);
        List<HighlightInfo> passA = collectDiagnosticHighlights(myFixture.doHighlighting());
        assertAllHighlightsCarryCompleteSuffixAndZeroDuplicates("Stage 8 Pass A", passA);
        HighlightInfo passAOverflow = passA.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .findFirst()
            .orElse(null);
        assertNotNull("Expected Stage 8 #3 overflow ERROR in Pass A", passAOverflow);
        assertEquals(1218, passAOverflow.getStartOffset());
        assertEquals(1220, passAOverflow.getEndOffset());
        assertEquals(
            "Union variant tag '#3' exceeds branch count (2) [stvn-core:E028-UNION_BRANCH_OVERFLOW]",
            passAOverflow.getDescription()
        );

        // Pass B (26 inspections): verify 1:1 parity, zero duplicates, and standardized suffix
        enableAll26StvnInspections();
        myFixture.configureByText("stage8_union_branch_overflow_and_const_sum_spine.stvn", stage8Src);
        List<HighlightInfo> passB = collectDiagnosticHighlights(myFixture.doHighlighting());
        assertAllHighlightsCarryCompleteSuffixAndZeroDuplicates("Stage 8 Pass B", passB);
        List<HighlightInfo> passBErrors = passB.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Stage 8 Pass B must emit exactly 1 ERROR without duplicates: " + passBErrors, 1, passBErrors.size());
        assertEquals(passAOverflow.getDescription(), passBErrors.get(0).getDescription());
        assertEquals(1218, passBErrors.get(0).getStartOffset());
        assertEquals(1220, passBErrors.get(0).getEndOffset());

        // Mirrored defs_with_type_and_body.stvn_d: verify E031 suffix without duplicate [stvn-core:ERR_...]
        Path defsWithBodyPath = SHARED_FIXTURES_ROOT.resolve(
            "syntax/invalid/structure/defs_with_type_and_body.stvn_d"
        );
        String defsWithBodySrc = Files.readString(defsWithBodyPath, StandardCharsets.UTF_8);
        myFixture.configureByText("defs_with_type_and_body.stvn_d", defsWithBodySrc);
        List<HighlightInfo> defsBodyHighlights = collectDiagnosticHighlights(myFixture.doHighlighting());
        assertAllHighlightsCarryCompleteSuffixAndZeroDuplicates("defs_with_type_and_body", defsBodyHighlights);

        // Safeguard 2: Multi-layer explicit + implicit sum unwrapping (#3 #TRUE, #Right #None, #Some #None,
        // #Some #Left 10, #2 #1 "inner-branch", #2 #3 "bad", #Left #3 #TRUE)
        String multiLayerDoc = """
            {
              :meta {
                #name "safeguard2_wrappers"
                #kind #BODY
              }
              :defs {
                :SensorId { #minSize 1 #maxSize 32 } :String
                :RetryMillis { #unsigned #size 16 #minIncl 10 #maxExcl 5000 } :Int
                :TelemetryBranch :Union( :Boolean :Int )
                :TelemetryBranchAlias :TelemetryBranch
                :InnerOutcome :Option( :Either( :SensorId :TelemetryBranchAlias ) )
                :OuterDispatchEnvelope :Option( :Either( :RetryMillis :InnerOutcome ) )
                :NestedUnion :Union( :Int :Union( :String :Boolean ) )
                :LeftUnionEither :Either( :Union( :Int :String ) :Boolean )
              }
              :type :Tuple(
                :OuterDispatchEnvelope
                :OuterDispatchEnvelope
                :OuterDispatchEnvelope
                :OuterDispatchEnvelope
                :NestedUnion
                :NestedUnion
                :LeftUnionEither
              )
              :body (
                #Some #None
                #Right #None
                #Some #Left 10
                #3 #TRUE
                #2 #1 "inner-branch"
                #2 #3 "bad"
                #Left #3 #TRUE
              )
            }
            """;
        myFixture.configureByText("safeguard2_wrappers.stvn", multiLayerDoc);
        List<HighlightInfo> sg2Highlights = collectDiagnosticHighlights(myFixture.doHighlighting());
        assertAllHighlightsCarryCompleteSuffixAndZeroDuplicates("Safeguard 2 wrappers", sg2Highlights);
        List<HighlightInfo> sg2Errors = sg2Highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected exactly 3 E028-UNION_BRANCH_OVERFLOW errors (0 false positives on valid wrappers): " + sg2Errors,
            3, sg2Errors.size());
        for (HighlightInfo err : sg2Errors) {
            assertEquals("#3", err.getText());
            assertEquals(
                "Union variant tag '#3' exceeds branch count (2) [stvn-core:E028-UNION_BRANCH_OVERFLOW]",
                err.getDescription()
            );
        }

        // :include E005 highlight & HTML tooltip + degraded :type PW006 + multi-inspection suffix sweep
        myFixture.addFileToProject(
            "broken_child.stvn_di",
            """
            {
              :meta {
                #name "broken_child"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :BadChildInt { #minExcl 0 } :Int
              }
            }
            """
        );
        String sweepDoc = """
            {
              :meta {
                #name "wrong_stem"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "broken_child.stvn_di" ]
                :PoisonedLocal { #maxIncl 5 } :Int
                :UnknownFacetType { #bogusFacet 1 } :String
                :BadEpoch :TimeEpoch
                :RootEnum :Enum [ #ALPHA #BETA #GAMMA ]
                :BadOrderSubset { #filterIncl [ #GAMMA #ALPHA ] } :RootEnum
                #OVERFLOW_CONST { #size 8 } :Int 999
              }
              :type :PoisonedLocal
              :body 42
            }
            """;
        myFixture.configureByText("actual_stem.stvn_i", sweepDoc);
        List<HighlightInfo> sweepHighlights = collectDiagnosticHighlights(myFixture.doHighlighting());
        assertTrue("Expected multiple error/warning highlights in sweep document", sweepHighlights.size() >= 6);
        assertAllHighlightsCarryCompleteSuffixAndZeroDuplicates("Multi-inspection sweep", sweepHighlights);

        HighlightInfo includeErr = sweepHighlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("[stvn-core:E005-MODULE_IMPORT_FAILED]"))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected [stvn-core:E005-MODULE_IMPORT_FAILED] on :include statement", includeErr);
        assertNotNull("Expected HTML tooltip on :include highlight", includeErr.getToolTip());
        assertTrue("Include HTML tooltip must carry [stvn-core:E005-MODULE_IMPORT_FAILED]:\n" + includeErr.getToolTip(),
            includeErr.getToolTip().contains("[stvn-core:E005-MODULE_IMPORT_FAILED]"));

        assertTrue("Expected degraded :type warning carrying PW006-WARN_DEGRADED_SCHEMA_USAGE: " + sweepHighlights,
            sweepHighlights.stream().anyMatch(h -> h.getDescription() != null
                && h.getDescription().contains("[stvn-plugin:PW006-WARN_DEGRADED_SCHEMA_USAGE]")));
    }

    public void testPdst02_HoverViolationSuffixTotalityAndUnionValidLexicalForms() {
        var docProvider = new StvnDocumentationProvider();

        // 1. Cached-IR + Pass-3-suspended pure-PSI hover across violations, precision loss, and :Union forms
        String hoverDoc = """
            {
              :meta {
                #name "pdst02_hover"
                #kind #BODY
              }
              :defs {
                :ConfidenceRatio { #minIncl 0.0 #maxIncl 1.0 } :Float
                :TelemetryBranch :Union( :Boolean :ConfidenceRatio )
                :BoundedPort { #unsigned #size 16 #minIncl 1024 #maxExcl 65535 } :Int
                :ShortTag { #minSize 2 #maxSize 5 #regex "^[A-Z]+$" } :String
                :AuditTime { #offset #minIncl "2026-01-01T00:00:00Z" } :DateTime
                :ModeEnum :Enum [ #FAST #SAFE #AUDIT ]
                :SafeSubset { #filterIncl [ #FAST #SAFE ] } :ModeEnum
                :OtherBrand :Int
                #OTHER_BRAND_VAL :OtherBrand 2048
                #BAD_UNION_CONST :TelemetryBranch #3 #TRUE
              }
              :type :Tuple(
                :TelemetryBranch
                :BoundedPort
                :BoundedPort
                :ShortTag
                :ShortTag
                :ConfidenceRatio
                :Float
                :AuditTime
                :SafeSubset
                :BoundedPort
                :Tuple( :Int :Int )
                { #minSize 1 } :Seq( :Int )
              )
              :body (
                #3 #TRUE
                500
                999999
                "A"
                "abc"
                1.5
                0.1234567890123456789012345
                "2025-06-01T00:00:00Z"
                #AUDIT
                #OTHER_BRAND_VAL
                ( 1 )
                []
              )
            }
            """;
        PsiFile file = myFixture.configureByText("pdst02_hover.stvn", hoverDoc);

        // Hover both #3 and #TRUE on '#3 #TRUE' in :body
        int bodyIdx = hoverDoc.indexOf(":body");
        int hash3Offset = hoverDoc.indexOf("#3 #TRUE", bodyIdx) + 1;
        int trueOffset = hoverDoc.indexOf("#3 #TRUE", bodyIdx) + 4;
        String hoverHash3 = generateHoverAt(docProvider, file, hash3Offset);
        String hoverTrue = generateHoverAt(docProvider, file, trueOffset);

        for (String unionHover : List.of(hoverHash3, hoverTrue)) {
            assertTrue("Union overflow hover must report Violated:\n" + unionHover, unionHover.contains(">Violated</b>"));
            assertTrue("Union overflow hover must carry [stvn-core:E028-UNION_BRANCH_OVERFLOW]:\n" + unionHover,
                unionHover.contains("[stvn-core:E028-UNION_BRANCH_OVERFLOW]"));
            assertTrue("Union hover Zone 3 must list Branch #1:\n" + unionHover,
                unionHover.contains("Branch #1: <code>#1 :Boolean</code>"));
            assertTrue("Union hover Zone 3 must list Branch #2:\n" + unionHover,
                unionHover.contains("Branch #2: <code>#2 :ConfidenceRatio</code>"));
            assertTrue("Union hover Zone 3 must document Rule C:\n" + unionHover,
                unionHover.contains("Implicit Unambiguous Branch (Rule C)"));
            assertFalse("Union hover must never fall back to generic 'Standard STVN literal':\n" + unionHover,
                unionHover.contains("Standard STVN literal"));
            assertFalse("Hover must never show illegal numeric underscore example '1_000_000':\n" + unionHover,
                unionHover.contains("1_000_000"));
        }

        // Verify ill-typed :defs ConstantDefinition RHS hover (#BAD_UNION_CONST '#3 #TRUE') carries E028 suffix,
        // while :defs LHS preserves non-injection isolation (TB-02 / Safeguard 3)
        int badConstLhsOffset = hoverDoc.indexOf("#BAD_UNION_CONST") + 2;
        String badConstLhsHover = generateHoverAt(docProvider, file, badConstLhsOffset);
        assertFalse(":defs LHS constant hover must preserve TB-02 non-injection isolation:\n" + badConstLhsHover,
            badConstLhsHover.contains("Compliance Status:"));
        int badConstRhsOffset = hoverDoc.indexOf("#BAD_UNION_CONST :TelemetryBranch #3") + "#BAD_UNION_CONST :TelemetryBranch ".length() + 1;
        String badConstRhsHover = generateHoverAt(docProvider, file, badConstRhsOffset);
        assertTrue("Ill-typed :defs #BAD_UNION_CONST RHS hover must report Violated with [stvn-core:E028-UNION_BRANCH_OVERFLOW]:\n"
            + badConstRhsHover,
            badConstRhsHover.contains("Violated")
                && badConstRhsHover.contains("[stvn-core:E028-UNION_BRANCH_OVERFLOW]"));

        // Sweep all :body violation & precision-loss slots and assert standardized suffix totality
        String[] probes = {
            "500",
            "999999",
            "\"A\"",
            "\"abc\"",
            "1.5",
            "0.1234567890123456789012345",
            "\"2025-06-01T00:00:00Z\"",
            "#AUDIT",
            "#OTHER_BRAND_VAL",
            "( 1 )",
            "[]"
        };
        for (String probe : probes) {
            int probeOffset = hoverDoc.indexOf(probe, bodyIdx) + (probe.startsWith("(") || probe.startsWith("[") ? 0 : 1);
            String card = generateHoverAt(docProvider, file, probeOffset);
            assertTrue("Hover card for '" + probe + "' must contain standardized [stvn-(core|plugin):<CODE>-<SYMBOLIC_ID>] suffix:\n" + card,
                COMPLETE_SUFFIX_ANYWHERE_PATTERN.matcher(card).find());
            assertFalse("Hover card for '" + probe + "' must not contain '1_000_000':\n" + card,
                card.contains("1_000_000"));
        }

        // Verify precision loss specifically carries [stvn-plugin:PW006-WARN_FLOAT_PRECISION_LOSS]
        int precOffset = hoverDoc.indexOf("0.1234567890123456789012345", bodyIdx) + 2;
        String precCard = generateHoverAt(docProvider, file, precOffset);
        assertTrue("Precision-loss hover must carry [stvn-plugin:PW006-WARN_FLOAT_PRECISION_LOSS]:\n" + precCard,
            precCard.contains("[stvn-plugin:PW006-WARN_FLOAT_PRECISION_LOSS]"));
    }

    public void testPdst03_Stage8ConstantSumSpineHoverAcrossLhsRhsBodyPackagedShortFormAndRecovery() throws IOException {
        var docProvider = new StvnDocumentationProvider();
        StvnSettings settings = StvnSettings.getInstance(getProject());
        boolean origLongForm = settings.getState().useLongFormSumTypes;
        try {
            settings.getState().useLongFormSumTypes = true;

            Path stage8Path = SHARED_FIXTURES_ROOT.resolve(
                "syntax/invalid/composites/stage8_union_branch_overflow_and_const_sum_spine.stvn"
            );
            String stage8Src = Files.readString(stage8Path, StandardCharsets.UTF_8);
            PsiFile stage8File = myFixture.configureByText(
                "stage8_union_branch_overflow_and_const_sum_spine.stvn",
                stage8Src
            );

            // Safeguard 3: Verify :defs RHS Value nodes are mapped in resolveCoreValue while :defs receives 0 inlay hints
            for (ConstantDefinition cd : PsiTreeUtil.findChildrenOfType(stage8File, ConstantDefinition.class)) {
                Value rhs = cd.getValue();
                assertNotNull(rhs);
                assertFalse("ConstantDefinition RHS must never satisfy isInsideBody (Safeguard 3 :body-only inlays)",
                    StvnPsiUtils.isInsideBody(rhs));
            }

            // 1. #REDUCIBLE_DEF_LEAF: LHS, RHS literal 0.85, and :body #REDUCIBLE_DEF_LEAF
            int reducibleLhsOffset = stage8Src.indexOf("#REDUCIBLE_DEF_LEAF") + 2;
            int reducibleRhsOffset = stage8Src.indexOf("0.85") + 1;
            int bodyStart = stage8Src.indexOf(":body");
            int reducibleBodyRefOffset = stage8Src.indexOf("#REDUCIBLE_DEF_LEAF", bodyStart) + 2;

            String reducibleLhsDoc = generateHoverAt(docProvider, stage8File, reducibleLhsOffset);
            String reducibleRhsDoc = generateHoverAt(docProvider, stage8File, reducibleRhsOffset);
            String reducibleBodyDoc = generateHoverAt(docProvider, stage8File, reducibleBodyRefOffset);

            // Safeguard 3: Healthy :defs LHS card must NOT contain Compliance Status:
            assertFalse("Healthy :defs ConstantDefinition LHS hover must NOT contain 'Compliance Status:':\n" + reducibleLhsDoc,
                reducibleLhsDoc.contains("Compliance Status:"));
            assertTrue(":body #REDUCIBLE_DEF_LEAF hover must contain 'Compliance Status:' Satisfied:\n" + reducibleBodyDoc,
                reducibleBodyDoc.contains("Compliance Status:") && reducibleBodyDoc.contains("Satisfied"));

            for (String card : List.of(reducibleLhsDoc, reducibleRhsDoc, reducibleBodyDoc)) {
                assertTrue("Expected Expanded Value '[#Some] [#Right] [#Some] [#Right] [#2] 0.85':\n" + card,
                    card.contains("<b>Expanded Value:</b> <code>[#Some] [#Right] [#Some] [#Right] [#2] 0.85</code>"));
                assertTrue("Expected Inferred Defaults row:\n" + card,
                    card.contains("<b>Inferred Defaults:</b> <code>[#Some]</code> &rarr; <code>[#Right]</code> &rarr; <code>[#Some]</code> &rarr; <code>[#Right]</code> &rarr; <code>[#2]</code>"));
                assertTrue("Expected Resolution Path row with :OuterDispatchEnvelope -> ... -> :ConfidenceRatio -> :Float:\n" + card,
                    card.contains("<b>Resolution Path:</b>")
                        && card.contains(":OuterDispatchEnvelope")
                        && card.contains(":InnerOutcome")
                        && card.contains(":TelemetryBranchAlias")
                        && card.contains(":TelemetryBranch")
                        && card.contains(":ConfidenceRatio")
                        && card.contains("<code>:Float</code>"));
                assertAllTypeDefinitionLinksResolve(docProvider, stage8File, card);
            }
            assertTrue(":defs RHS 0.85 hover must resolve leaf Governing Schema / Value Type to :ConfidenceRatio:\n" + reducibleRhsDoc,
                reducibleRhsDoc.contains(":ConfidenceRatio"));

            // 2. #EXPLICIT_DEF_LEAF: 0 inferred defaults
            int explicitLhsOffset = stage8Src.indexOf("#EXPLICIT_DEF_LEAF") + 2;
            String explicitLhsDoc = generateHoverAt(docProvider, stage8File, explicitLhsOffset);
            assertTrue("Expected unbracketed Expanded Value '#Some #Right #Some #Right #2 0.85':\n" + explicitLhsDoc,
                explicitLhsDoc.contains("<b>Expanded Value:</b> <code>#Some #Right #Some #Right #2 0.85</code>"));
            assertTrue("Expected 'None (all sum tags explicitly authored)':\n" + explicitLhsDoc,
                explicitLhsDoc.contains("None (all sum tags explicitly authored)"));

            // 3. #IRREDUCIBLE_DEF_NONE (#Some #None): LHS + hovering #None on RHS
            int irredLhsOffset = stage8Src.indexOf("#IRREDUCIBLE_DEF_NONE") + 2;
            String irredLhsDoc = generateHoverAt(docProvider, stage8File, irredLhsOffset);
            assertTrue("Expected Expanded Value '#Some [#Right] #None':\n" + irredLhsDoc,
                irredLhsDoc.contains("<b>Expanded Value:</b> <code>#Some [#Right] #None</code>"));
            assertTrue("Expected Inferred Defaults '<code>[#Right]</code>':\n" + irredLhsDoc,
                irredLhsDoc.contains("<b>Inferred Defaults:</b> <code>[#Right]</code>"));

            int noneRhsOffset = stage8Src.indexOf("#Some #None") + "#Some ".length() + 1;
            String noneRhsDoc = generateHoverAt(docProvider, stage8File, noneRhsOffset);
            assertTrue("Hovering #None inside '#Some #None' on :OuterDispatchEnvelope must report Satisfied:\n" + noneRhsDoc,
                noneRhsDoc.contains(">Satisfied</b>"));

            // 4. Packaged constant :telemetry / #PKG_REDUCIBLE_LEAF
            int pkgBodyRefOffset = stage8Src.indexOf("#PKG_REDUCIBLE_LEAF", bodyStart) + 2;
            String pkgBodyDoc = generateHoverAt(docProvider, stage8File, pkgBodyRefOffset);
            assertTrue("Packaged #PKG_REDUCIBLE_LEAF must render Expanded Value '[#Some] [#Right] [#Some] [#Right] [#2] 0.25':\n" + pkgBodyDoc,
                pkgBodyDoc.contains("<b>Expanded Value:</b> <code>[#Some] [#Right] [#Some] [#Right] [#2] 0.25</code>"));
            assertAllTypeDefinitionLinksResolve(docProvider, stage8File, pkgBodyDoc);

            // 5. Short-form setting (#S / #R)
            settings.getState().useLongFormSumTypes = false;
            String shortFormDoc = generateHoverAt(docProvider, stage8File, reducibleLhsOffset);
            assertTrue("Short-form setting must render '[#S] [#R] [#S] [#R] [#2] 0.85':\n" + shortFormDoc,
                shortFormDoc.contains("<b>Expanded Value:</b> <code>[#S] [#R] [#S] [#R] [#2] 0.85</code>"));
            settings.getState().useLongFormSumTypes = true;

            // 6. Sibling :defs error (:TrapEmptyUnion :Union()), in-flight :body '#3 ', scalar #MAX_PORT, and ill-typed #BAD_DEF
            String recoveryDoc = """
                {
                  :meta {
                    #name "stage8_recovery"
                    #kind #BODY
                  }
                  :defs {
                    :Port { #unsigned #size 16 } :Int
                    :ConfidenceRatio { #minIncl 0.0 #maxExcl 1.0 } :Float
                    :TelemetryBranch :Union( :Boolean :ConfidenceRatio )
                    :OuterEnvelope :Option( :Either( :Port :TelemetryBranch ) )
                    :TrapEmptyUnion :Union()

                    #MAX_PORT :Port 8080
                    #HEALTHY_SUM :OuterEnvelope 0.5
                    #BAD_DEF :OuterEnvelope #3 #TRUE
                  }
                  :type :OuterEnvelope
                  :body #3\s
                }
                """;
            PsiFile recoveryFile = myFixture.configureByText("stage8_recovery.stvn", recoveryDoc);

            int maxPortOffset = recoveryDoc.indexOf("#MAX_PORT") + 2;
            String maxPortDoc = generateHoverAt(docProvider, recoveryFile, maxPortOffset);
            assertFalse("Non-sum scalar #MAX_PORT must omit Expanded Value / Inferred Defaults:\n" + maxPortDoc,
                maxPortDoc.contains("Expanded Value:") || maxPortDoc.contains("Inferred Defaults:"));

            int healthySumOffset = recoveryDoc.indexOf("#HEALTHY_SUM") + 2;
            String healthySumDoc = generateHoverAt(docProvider, recoveryFile, healthySumOffset);
            assertTrue("Sibling :defs error and broken :body must still recover #HEALTHY_SUM sum spine via compileDefsCatalog:\n"
                + healthySumDoc,
                healthySumDoc.contains("<b>Expanded Value:</b> <code>[#Some] [#Right] [#2] 0.5</code>"));

            int badDefOffset = recoveryDoc.indexOf("#BAD_DEF") + 2;
            String badDefDoc = generateHoverAt(docProvider, recoveryFile, badDefOffset);
            assertFalse("Ill-typed #BAD_DEF must suppress Expanded Value / Inferred Defaults:\n" + badDefDoc,
                badDefDoc.contains("Expanded Value:") || badDefDoc.contains("Inferred Defaults:"));
            assertFalse("Ill-typed #BAD_DEF LHS hover must preserve TB-02 non-injection isolation:\n" + badDefDoc,
                badDefDoc.contains("Compliance Status:"));
            int badDefRhsOffset = recoveryDoc.indexOf("#BAD_DEF :OuterEnvelope #3") + "#BAD_DEF :OuterEnvelope ".length() + 1;
            String badDefRhsDoc = generateHoverAt(docProvider, recoveryFile, badDefRhsOffset);
            assertTrue("Ill-typed #BAD_DEF RHS hover must report Violated with E028-UNION_BRANCH_OVERFLOW:\n" + badDefRhsDoc,
                badDefRhsDoc.contains("Violated") && badDefRhsDoc.contains("[stvn-core:E028-UNION_BRANCH_OVERFLOW]"));
        } finally {
            settings.getState().useLongFormSumTypes = origLongForm;
        }
    }

    private void assertAllTypeDefinitionLinksResolve(
        StvnDocumentationProvider provider,
        PsiFile contextFile,
        String html
    ) {
        Matcher m = TYPE_DEF_HREF_PATTERN.matcher(html);
        int linkCount = 0;
        PsiManager psiMgr = PsiManager.getInstance(getProject());
        while (m.find()) {
            linkCount++;
            String link = m.group(1);
            PsiElement resolved = provider.getDocumentationElementForLink(psiMgr, link, contextFile);
            assertTrue("Hyperlink '" + link + "' in Resolution Path must resolve to TypeDefinition, got: " + resolved,
                resolved instanceof TypeDefinition);
        }
        assertTrue("Expected at least 3 clickable TypeDefinition links in Resolution Path:\n" + html, linkCount >= 3);
    }

    private List<HighlightInfo> collectDiagnosticHighlights(List<HighlightInfo> all) {
        return all.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
                || h.getSeverity() == HighlightSeverity.WARNING
                || h.getSeverity() == HighlightSeverity.WEAK_WARNING)
            .toList();
    }

    private void assertAllHighlightsCarryCompleteSuffixAndZeroDuplicates(String label, List<HighlightInfo> highlights) {
        Set<String> seenSpanDescriptions = new HashSet<>();
        List<String> failures = new ArrayList<>();
        for (HighlightInfo h : highlights) {
            String desc = h.getDescription();
            if (desc == null || !StvnDiagnosticFormatter.COMPLETE_ATTRIBUTION_SUFFIX_PATTERN.matcher(desc.trim()).find()) {
                failures.add(label + ": Missing standardized suffix on " + h.getSeverity() + " ["
                    + h.getStartOffset() + "," + h.getEndOffset() + "): " + desc);
            }
            String key = h.getSeverity() + ":" + h.getStartOffset() + ":" + h.getEndOffset() + ":" + desc;
            if (!seenSpanDescriptions.add(key)) {
                failures.add(label + ": Duplicate highlight detected: " + key);
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
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
