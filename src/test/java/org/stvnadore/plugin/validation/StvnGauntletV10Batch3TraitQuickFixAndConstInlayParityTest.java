package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.hints.InlayHintsSink;
import com.intellij.codeInsight.hints.presentation.InlayPresentation;
import com.intellij.codeInsight.hints.presentation.RootInlayPresentation;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.plugin.documentation.StvnConstantSumSpineRenderer;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.hints.StvnInlayHintsCollector;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.AliasExpansionMode;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.plugin.util.StvnTypePresentationUtil;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end IntelliJ Platform verification suite for Gauntlet v10 Batch 3
 * ({@code G10B3-01} through {@code G10B3-03}) across {@code Pass A} ({@code 0} inspections)
 * and {@code Pass B} ({@code 26} inspections), asserting {@code count == 1} intention
 * deduplication, {@code DIFF} / {@code CustomDiff} / {@code EMPTY} previews, nested usage-site
 * hover attribution, and {@code :body} {@code #CONST} sum-spine {@code [...]} bracket parity
 * with click-materialization isolation.
 */
@NullMarked
public final class StvnGauntletV10Batch3TraitQuickFixAndConstInlayParityTest extends BasePlatformTestCase {

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
        assertions.run();
        LocalInspectionTool[] all = createAll26Inspections();
        myFixture.enableInspections(all);
        try {
            assertions.run();
        } finally {
            myFixture.disableInspections(all);
        }
    }

    public void testTarget1_InlineCompositeAndCoproductTraitViolationTargetingCaretInvarianceAndNestedUsageHover() {
        String hazardSrc = """
            {
              :meta {
                #name "inline_hazard_set"
                #kind #BODY
              }
              :defs {
                :AltitudeCm { #unsigned #size 16 #maxExcl 50000 } :Int
                :RawLidarSlope :Float
                :InvalidInlineHazardSet :Set( :Union( :AltitudeCm :RawLidarSlope ) )
              }
              :type :AltitudeCm
              :body 1200
            }
            """;

        runInPassAAndPassB(() -> {
            PsiFile file = myFixture.configureByText("inline_hazard_set.stvn", hazardSrc);
            List<HighlightInfo> highlights = myFixture.doHighlighting();
            assertTrue(
                "Expected E008-TRAIT_VIOLATION on inline :Union inside :Set",
                highlights.stream().anyMatch(h -> h.getSeverity() == HighlightSeverity.ERROR
                    && h.getDescription() != null
                    && h.getDescription().contains("Set elements require types to be #equatable #TRUE"))
            );

            int unionDeclStart = hazardSrc.indexOf(":Union(");
            int[] testCarets = {
                unionDeclStart + 1,
                hazardSrc.indexOf(":AltitudeCm", unionDeclStart) + 2,
                hazardSrc.indexOf(":RawLidarSlope", unionDeclStart) + 2
            };
            for (int caretOffset : testCarets) {
                myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
                List<String> allLabels = myFixture.getAvailableIntentions().stream()
                    .map(IntentionAction::getText)
                    .toList();
                assertTrue(
                    "Zero intentions may target conformant :AltitudeCm at offset " + caretOffset + ": " + allLabels,
                    allLabels.stream().noneMatch(t -> t.contains(":AltitudeCm"))
                );
                assertSingleIntentionWithDiffPreview("Add '{ #equatable #TRUE }' to inline :Union");
                assertSingleNavigationIntentionWithEmptyPreview("Jump to ':RawLidarSlope' declaration in :defs");
                assertSingleIntentionWithDiffPreview("Add '#exact' to :RawLidarSlope in :defs");
                assertSingleIntentionWithDiffPreview("Override '#equatable #TRUE' on :RawLidarSlope in :defs");
            }

            // Nested usage-site hover (PB3R-04): :RawLidarSlope gets red usage banner; :AltitudeCm stays clean
            var docProvider = new StvnDocumentationProvider();
            int rawUsageOffset = hazardSrc.indexOf(":RawLidarSlope", unionDeclStart) + 2;
            PsiElement rawLeaf = file.findElementAt(rawUsageOffset);
            var rawRef = file.findReferenceAt(rawUsageOffset);
            assertNotNull(rawLeaf);
            assertNotNull(rawRef);
            PsiElement rawTarget = rawRef.resolve();
            assertNotNull(rawTarget);
            String rawHover = docProvider.generateDoc(rawTarget, rawLeaf);
            assertNotNull(rawHover);
            assertTrue("Nested :RawLidarSlope must render 'Trait Violation at Usage Site':\n" + rawHover,
                rawHover.contains("Trait Violation at Usage Site"));
            assertTrue("Nested :RawLidarSlope must mark #equatable Invalid:\n" + rawHover,
                rawHover.contains(">Invalid<"));

            int altUsageOffset = hazardSrc.indexOf(":AltitudeCm", unionDeclStart) + 2;
            PsiElement altLeaf = file.findElementAt(altUsageOffset);
            var altRef = file.findReferenceAt(altUsageOffset);
            assertNotNull(altLeaf);
            assertNotNull(altRef);
            PsiElement altTarget = altRef.resolve();
            assertNotNull(altTarget);
            String altHover = docProvider.generateDoc(altTarget, altLeaf);
            assertNotNull(altHover);
            assertFalse("Conformant :AltitudeCm must NOT render 'Trait Violation at Usage Site':\n" + altHover,
                altHover.contains("Trait Violation at Usage Site"));
            assertFalse("Conformant :AltitudeCm must NOT render Status: Invalid:\n" + altHover,
                altHover.contains(">Invalid<"));
            assertFalse("Conformant :AltitudeCm must NOT contain red violation badge hex:\n" + altHover,
                altHover.contains("#E57373") || altHover.contains("#C62828"));
        });

        // Verify each mutating quick-fix compiles cleanly with 0 errors and 0 warnings
        String activeHazardSrc = hazardSrc
            .replace(":type :AltitudeCm", ":type :InvalidInlineHazardSet")
            .replace(":body 1200", ":body [ #1 1200 ]");
        assertQuickFixHealsToZeroDiagnostics(
            "inline_hazard_restore.stvn",
            activeHazardSrc.replace("#name \"inline_hazard_set\"", "#name \"inline_hazard_restore\""),
            activeHazardSrc.replace("#name \"inline_hazard_set\"", "#name \"inline_hazard_restore\"")
                .indexOf(":RawLidarSlope", activeHazardSrc.indexOf(":Union(")) + 2,
            "Add '{ #equatable #TRUE }' to inline :Union",
            "{ #equatable #TRUE } :Union("
        );
        // Verify healed inline wrapper clears usage-site violation banner on :RawLidarSlope
        PsiFile healedFile = myFixture.getFile();
        int healedRawOffset = healedFile.getText().indexOf(":RawLidarSlope", healedFile.getText().indexOf(":Union(")) + 2;
        PsiElement healedRawLeaf = healedFile.findElementAt(healedRawOffset);
        var healedRawRef = healedFile.findReferenceAt(healedRawOffset);
        assertNotNull(healedRawLeaf);
        assertNotNull(healedRawRef);
        String healedRawHover = new StvnDocumentationProvider().generateDoc(healedRawRef.resolve(), healedRawLeaf);
        assertNotNull(healedRawHover);
        assertFalse("Healed inline { #equatable #TRUE } :Union must suppress usage-site violation banner:\n" + healedRawHover,
            healedRawHover.contains("Trait Violation at Usage Site"));

        String exactHazardSrc = activeHazardSrc.replace("#name \"inline_hazard_set\"", "#name \"inline_hazard_exact\"");
        assertQuickFixHealsToZeroDiagnostics(
            "inline_hazard_exact.stvn",
            exactHazardSrc,
            exactHazardSrc.indexOf(":Union(") + 2,
            "Add '#exact' to :RawLidarSlope in :defs",
            "{ #exact } :Float"
        );
        String overrideHazardSrc = activeHazardSrc.replace("#name \"inline_hazard_set\"", "#name \"inline_hazard_override\"");
        assertQuickFixHealsToZeroDiagnostics(
            "inline_hazard_override.stvn",
            overrideHazardSrc,
            overrideHazardSrc.indexOf(":AltitudeCm", overrideHazardSrc.indexOf(":Union(")) + 2,
            "Override '#equatable #TRUE' on :RawLidarSlope in :defs",
            "{ #equatable #TRUE } :Float"
        );

        // Deduplication when same non-equatable nominal appears twice & multiple inline :Float leaves + invertible :Map
        String dedupAndInlineFloatSrc = """
            {
              :meta {
                #name "dedup_and_inline_floats"
                #kind #BODY
              }
              :defs {
                :AltitudeCm { #unsigned #size 16 } :Int
                :RawLidarSlope :Float
                :DupSet :Set( :Union( :RawLidarSlope :Option( :RawLidarSlope ) ) )
                :DualFloatSet :Set( :Tuple( :Float :Float ) )
                :InvUnionMap { #invertible } :Map( :String :Union( :AltitudeCm :RawLidarSlope ) )
              }
              :type :AltitudeCm
              :body 10
            }
            """;
        runInPassAAndPassB(() -> {
            myFixture.configureByText("dedup_and_inline_floats.stvn", dedupAndInlineFloatSrc);
            myFixture.doHighlighting();

            myFixture.getEditor().getCaretModel().moveToOffset(
                dedupAndInlineFloatSrc.indexOf(":Union( :RawLidarSlope") + 2);
            assertSingleNavigationIntentionWithEmptyPreview("Jump to ':RawLidarSlope' declaration in :defs");
            assertSingleIntentionWithDiffPreview("Add '#exact' to :RawLidarSlope in :defs");
            assertSingleIntentionWithDiffPreview("Override '#equatable #TRUE' on :RawLidarSlope in :defs");

            myFixture.getEditor().getCaretModel().moveToOffset(
                dedupAndInlineFloatSrc.indexOf(":Tuple( :Float :Float )") + 2);
            assertSingleIntentionWithDiffPreview("Add '{ #equatable #TRUE }' to inline :Tuple");
            assertSingleIntentionWithDiffPreview("Add '{ #exact }' constraint to :Float (Field #1)");
            assertSingleIntentionWithDiffPreview("Add '{ #exact }' constraint to :Float (Field #2)");

            int invUnionPos = dedupAndInlineFloatSrc.indexOf(":Union( :AltitudeCm :RawLidarSlope )") + 2;
            myFixture.getEditor().getCaretModel().moveToOffset(invUnionPos);
            assertTrue(
                myFixture.getAvailableIntentions().stream()
                    .map(IntentionAction::getText)
                    .noneMatch(t -> t.contains(":AltitudeCm"))
            );
            assertSingleIntentionWithDiffPreview("Add '{ #equatable #TRUE }' to inline :Union");
            assertSingleIntentionWithDiffPreview("Add '#exact' to :RawLidarSlope in :defs");
            assertSingleIntentionWithDiffPreview("Remove '#invertible' facet from :InvUnionMap");
        });

        // Cross-file :include + :use UseMapAlias CustomDiff preview verification
        myFixture.addFileToProject("b3_xref/lidar_defs.telemetry.stvn_di", """
            {
              :meta {
                #name "lidar_defs"
                #domain "telemetry"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :package :lidar/sensors {
                  :AltitudeCm { #unsigned #size 16 } :Int
                  :RawLidarSlope :Float
                }
              }
            }
            """);
        PsiFile crossConsumer = myFixture.addFileToProject("b3_xref/consumer_set.telemetry.stvn_i", """
            {
              :meta {
                #name "consumer_set"
                #domain "telemetry"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "lidar_defs.telemetry.stvn_di" ]
                :use [ :lidar/sensors { :AltitudeCm :LocalAlt :RawLidarSlope :LocalSlope } ]
                :BadCrossSet :Set( :Union( :LocalAlt :LocalSlope ) )
              }
              :type :LocalAlt
              :body 500
            }
            """);
        myFixture.configureFromExistingVirtualFile(crossConsumer.getVirtualFile());
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(
            crossConsumer.getText().indexOf(":Union( :LocalAlt :LocalSlope )") + 2);
        assertTrue(
            myFixture.getAvailableIntentions().stream()
                .map(IntentionAction::getText)
                .noneMatch(t -> t.contains(":LocalAlt") || t.contains(":AltitudeCm"))
        );
        IntentionAction crossExact = findSingleExactIntention("Add '#exact' to :RawLidarSlope in :defs");
        IntentionPreviewInfo crossPreview = crossExact.generatePreview(
            getProject(), myFixture.getEditor(), (PsiFile) crossConsumer.copy());
        assertTrue("Cross-file nominal fix must return CustomDiff preview: " + crossPreview,
            crossPreview instanceof IntentionPreviewInfo.CustomDiff);
    }

    public void testTarget2_ComparableRequiresEquatableLocalAndConstituentQuickFixPriorityReorderAndHoverAttribution() {
        String compHazardSrc = """
            {
              :meta {
                #name "comparable_hazard"
                #kind #BODY
              }
              :defs {
                :AltitudeCm { #unsigned #size 16 #maxExcl 50000 } :Int
                :RawLidarSlope :Float
                :IllegalComparableHazard { #comparable #TRUE } :Union( :RawLidarSlope :Set( :AltitudeCm ) )
              }
              :type :AltitudeCm
              :body 400
            }
            """;

        runInPassAAndPassB(() -> {
            PsiFile file = myFixture.configureByText("comparable_hazard.stvn", compHazardSrc);
            myFixture.doHighlighting();

            int compOffset = compHazardSrc.indexOf("#comparable #TRUE") + 2;
            myFixture.getEditor().getCaretModel().moveToOffset(compOffset);

            List<String> orderedTexts = myFixture.getAvailableIntentions().stream()
                .map(IntentionAction::getText)
                .toList();
            assertSingleIntentionWithDiffPreview("Add '#equatable #TRUE' to ':IllegalComparableHazard'");
            assertSingleIntentionWithDiffPreview("Remove '#comparable #TRUE' facet");
            assertSingleNavigationIntentionWithEmptyPreview("Jump to ':RawLidarSlope' declaration in :defs");
            assertSingleIntentionWithDiffPreview("Add '#exact' to :RawLidarSlope in :defs");
            assertSingleIntentionWithDiffPreview("Override '#equatable #TRUE' on :RawLidarSlope in :defs");
            assertTrue(
                "Zero intentions may target conformant Branch #2 :Set(:AltitudeCm): " + orderedTexts,
                orderedTexts.stream().noneMatch(t -> t.contains(":AltitudeCm"))
            );

            int idxAddLocal = orderedTexts.indexOf("Add '#equatable #TRUE' to ':IllegalComparableHazard'");
            int idxRemoveComp = orderedTexts.indexOf("Remove '#comparable #TRUE' facet");
            int idxJumpConst = orderedTexts.indexOf("Jump to ':RawLidarSlope' declaration in :defs");
            int idxExactConst = orderedTexts.indexOf("Add '#exact' to :RawLidarSlope in :defs");
            int idxOverrideConst = orderedTexts.indexOf("Override '#equatable #TRUE' on :RawLidarSlope in :defs");
            assertTrue("Primary local Add '#equatable #TRUE' must precede Remove '#comparable #TRUE': " + orderedTexts,
                idxAddLocal >= 0 && idxAddLocal < idxRemoveComp);
            assertTrue("Both local fixes must precede remote constituent fixes: " + orderedTexts,
                idxRemoveComp < idxJumpConst && idxRemoveComp < idxExactConst && idxExactConst < idxOverrideConst);

            // Hover attribution check (HTVG-01..02) on :IllegalComparableHazard
            int lhsOffset = compHazardSrc.indexOf(":IllegalComparableHazard") + 2;
            PsiElement lhsElem = file.findElementAt(lhsOffset);
            assertNotNull(lhsElem);
            String hazardHover = new StvnDocumentationProvider().generateDoc(lhsElem, lhsElem);
            assertNotNull(hazardHover);
            assertTrue("Hover must display refined prerequisite subtitle:\n" + hazardHover,
                hazardHover.contains("Constituents lacking <code>#equatable #TRUE</code> required by declared <code>#comparable #TRUE</code> override:"));
            assertTrue("Hover must attribute Branch #1: :RawLidarSlope:\n" + hazardHover,
                hazardHover.contains("Branch #1:") && hazardHover.contains(":RawLidarSlope"));
            assertFalse("Hover must NOT blame conformant Branch #2: :Set(:AltitudeCm):\n" + hazardHover,
                hazardHover.contains("Branch #2:"));
        });

        String activeCompHazardSrc = compHazardSrc
            .replace(":type :AltitudeCm", ":type :IllegalComparableHazard")
            .replace(":body 400", ":body #2 [ 400 ]");
        String addEqHazardSrc = activeCompHazardSrc.replace("#name \"comparable_hazard\"", "#name \"comp_hazard_add_eq\"");
        assertQuickFixHealsToZeroDiagnostics(
            "comp_hazard_add_eq.stvn",
            addEqHazardSrc,
            addEqHazardSrc.indexOf("#comparable #TRUE") + 2,
            "Add '#equatable #TRUE' to ':IllegalComparableHazard'",
            "{ #equatable #TRUE #comparable #TRUE } :Union("
        );
        String remCompHazardSrc = activeCompHazardSrc.replace("#name \"comparable_hazard\"", "#name \"comp_hazard_remove_comp\"");
        assertQuickFixHealsToZeroDiagnostics(
            "comp_hazard_remove_comp.stvn",
            remCompHazardSrc,
            remCompHazardSrc.indexOf("#comparable #TRUE") + 2,
            "Remove '#comparable #TRUE' facet",
            ":IllegalComparableHazard :Union("
        );

        // Scenarios 2B, 2C, short-form #T, anonymous inline schema, partner #equatable #FALSE anchor & disordered reorder
        String matrixTarget2Src = """
            {
              :meta {
                #name "target2_matrix"
                #kind #BODY
              }
              :defs {
                :AltitudeCm { #unsigned #size 16 } :Int
                :RawLidarSlope :Float
                :BadSet {
                  #equatable #FALSE
                  #comparable #TRUE
                } :Set( :Int )
                :BadUnion {
                  #equatable #FALSE
                  #comparable #TRUE
                } :Union( :RawLidarSlope :Set( :AltitudeCm ) )
                :ShortBoolHazard { #comparable #T } :Union( :RawLidarSlope :Set( :AltitudeCm ) )
                :DisorderedSeq { #comparable #TRUE #minSize 1 } :Seq( :RawLidarSlope )
              }
              :type { #comparable #TRUE } :Union( :RawLidarSlope :Set( :AltitudeCm ) )
              :body #2 [ 10 ]
            }
            """;
        runInPassAAndPassB(() -> {
            PsiFile file = myFixture.configureByText("target2_matrix.stvn", matrixTarget2Src);
            myFixture.doHighlighting();

            // Scenario 2B: :BadSet { #equatable #FALSE #comparable #TRUE } :Set(:Int) at both #comparable and partner #equatable
            int badSetBlock = matrixTarget2Src.indexOf(":BadSet");
            for (String token : List.of("#comparable #TRUE", "#equatable #FALSE")) {
                myFixture.getEditor().getCaretModel().moveToOffset(matrixTarget2Src.indexOf(token, badSetBlock) + 2);
                assertSingleIntentionWithDiffPreview("Remove conflicting '#equatable #FALSE' facet");
                assertSingleIntentionWithDiffPreview("Remove '#comparable #TRUE' facet");
            }

            // Scenario 2C: :BadUnion { #equatable #FALSE #comparable #TRUE } :Union(...) at both #comparable and partner #equatable
            int badUnionBlock = matrixTarget2Src.indexOf(":BadUnion");
            for (String token : List.of("#comparable #TRUE", "#equatable #FALSE")) {
                myFixture.getEditor().getCaretModel().moveToOffset(matrixTarget2Src.indexOf(token, badUnionBlock) + 2);
                assertSingleIntentionWithDiffPreview("Change '#equatable #FALSE' to '#equatable #TRUE'");
                assertSingleIntentionWithDiffPreview("Remove '#comparable #TRUE' facet");
            }

            // Short-form #T snippet preservation
            myFixture.getEditor().getCaretModel().moveToOffset(
                matrixTarget2Src.indexOf("#comparable #T", matrixTarget2Src.indexOf(":ShortBoolHazard")) + 2);
            assertSingleIntentionWithDiffPreview("Remove '#comparable #T' facet");

            // Disordered MetadataMap co-existence with reorder quick-fix (count == 1)
            myFixture.getEditor().getCaretModel().moveToOffset(
                matrixTarget2Src.indexOf("#comparable #TRUE #minSize 1") + 2);
            assertSingleIntentionWithDiffPreview("Add '#equatable #TRUE' to ':DisorderedSeq'");
            assertSingleIntentionWithDiffPreview("Remove '#comparable #TRUE' facet");
            assertSingleIntentionWithDiffPreview("Reorder metadata facets to canonical 5-tier order");

            // Anonymous inline :type { #comparable #TRUE } :Union(...)
            int anonTypeBlock = matrixTarget2Src.indexOf(":type { #comparable #TRUE }");
            myFixture.getEditor().getCaretModel().moveToOffset(
                matrixTarget2Src.indexOf("#comparable #TRUE", anonTypeBlock) + 2);
            assertSingleIntentionWithDiffPreview("Add '#equatable #TRUE'");
            assertSingleIntentionWithDiffPreview("Remove '#comparable #TRUE' facet");

            int anonUnionPos = matrixTarget2Src.indexOf(":Union(", anonTypeBlock) + 2;
            PsiElement anonUnionElem = file.findElementAt(anonUnionPos);
            assertNotNull(anonUnionElem);
            String anonUnionHover = new StvnDocumentationProvider().generateDoc(anonUnionElem, anonUnionElem);
            assertNotNull(anonUnionHover);
            assertTrue("Inline illegal #comparable #TRUE :Union hover must list Branch #1:\n" + anonUnionHover,
                anonUnionHover.contains("Branch #1:") && !anonUnionHover.contains("Branch #2:"));
        });

        // Verify Scenario 2B healing on :BadSet produces 0 errors AND 0 W003 warnings
        String badSetDoc = """
            {
              :meta {
                #name "bad_set_heal"
                #kind #BODY
              }
              :defs {
                :BadSet { #equatable #FALSE #comparable #TRUE } :Set( :Int )
              }
              :type :BadSet
              :body [ 1 2 ]
            }
            """;
        assertQuickFixHealsToZeroDiagnostics(
            "bad_set_heal.stvn",
            badSetDoc,
            badSetDoc.indexOf("#comparable #TRUE") + 2,
            "Remove conflicting '#equatable #FALSE' facet",
            ":BadSet { #comparable #TRUE } :Set( :Int )"
        );
    }

    public void testTarget3_BodyConstSumSpineBracketParityExpansionModesStateAStateBAndClickIsolation() {
        myFixture.addFileToProject("b3_spine/hopper_pkg.telemetry.stvn_di", """
            {
              :meta {
                #name "hopper_pkg"
                #domain "telemetry"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :package :hopper/guidance {
                  :BurnDurationMs { #unsigned #size 16 #minIncl 50 #maxExcl 10000 } :Int
                  :NestedHopperEnvelope :Option( :Either( :String :Option( :BurnDurationMs ) ) )
                  #PKG_DESCENT_PLAN :NestedHopperEnvelope 625
                }
              }
            }
            """);

        String spineMatrixSrc = """
            {
              :meta {
                #name "const_sum_spine_parity"
                #domain "telemetry"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "hopper_pkg.telemetry.stvn_di" ]
                :use [ :hopper/guidance { :NestedHopperEnvelope :LocalHopper #PKG_DESCENT_PLAN #LOCAL_PKG_PLAN } ]
                :AltitudeCm { #unsigned #size 16 #maxExcl 50000 } :Int
                :BurnDurationMs { #unsigned #size 16 #minIncl 50 #maxExcl 10000 } :Int
                :OptAltitudeCm :Option( :AltitudeCm )
                :NestedHopperEnvelope :Option( :Either( :String :Option( :BurnDurationMs ) ) )
                :InnerOption :Option( :BurnDurationMs )
                :OuterOption :Option( :InnerOption )
                :OuterHopper :Option( :NestedHopperEnvelope )
                :DoubleOuter :Option( :OuterHopper )
                #DEFAULT_DESCENT_PLAN :NestedHopperEnvelope 625
                #ALIAS_DESCENT_PLAN :NestedHopperEnvelope #DEFAULT_DESCENT_PLAN
                #PARTIAL_CONST :NestedHopperEnvelope #Right 625
                #CONST_EXPLICIT :OptAltitudeCm #Some 120
                #IRREDUCIBLE_HOLD_NONE :OptAltitudeCm #None
                #INNER_EXPLICIT :InnerOption #Some 250
                #OUTER_CONST :OuterOption #INNER_EXPLICIT
                :Matrix :Tuple(
                  :NestedHopperEnvelope
                  :NestedHopperEnvelope
                  :NestedHopperEnvelope
                  :OptAltitudeCm
                  :OptAltitudeCm
                  :OuterOption
                  :LocalHopper
                  :OuterHopper
                  :Option( :NestedHopperEnvelope )
                  :DoubleOuter
                )
              }
              :type :Matrix
              :body (
                #DEFAULT_DESCENT_PLAN
                #ALIAS_DESCENT_PLAN
                #PARTIAL_CONST
                #CONST_EXPLICIT
                #IRREDUCIBLE_HOLD_NONE
                #OUTER_CONST
                #LOCAL_PKG_PLAN
                #DEFAULT_DESCENT_PLAN
                #DEFAULT_DESCENT_PLAN
                #DEFAULT_DESCENT_PLAN
              )
            }
            """;

        PsiFile stateAFile = myFixture.addFileToProject(
            "b3_spine/const_sum_spine_parity.telemetry.stvn_i",
            spineMatrixSrc
        );
        myFixture.configureFromExistingVirtualFile(stateAFile.getVirtualFile());
        List<HighlightInfo> stateAErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue("State A matrix must compile with 0 errors: " + stateAErrors, stateAErrors.isEmpty());
        assertTrue(
            "State A must produce lowered Pass 3 payload",
            StvnCompiledDocumentCache.getSnapshot(stateAFile).document().flatMap(StvnDocument::payload).isPresent()
        );

        List<Value> stateASlots = extractOuterBodyTupleSlots(stateAFile);
        assertEquals(10, stateASlots.size());
        Map<Integer, String> stateAInlays = collectInlayMap(stateAFile);

        String[] expectedFull = {
            ":NestedHopperEnvelope [#Some] [#Right] [#Some] (-> :BurnDurationMs -> :Int)",
            ":NestedHopperEnvelope [#Some] [#Right] [#Some] (-> :BurnDurationMs -> :Int)",
            ":NestedHopperEnvelope [#Some] #Right [#Some] (-> :BurnDurationMs -> :Int)",
            ":OptAltitudeCm #Some (-> :AltitudeCm -> :Int)",
            ":OptAltitudeCm #None",
            ":OuterOption [#Some] (-> :InnerOption #Some (-> :BurnDurationMs -> :Int))",
            ":LocalHopper [#Some] [#Right] [#Some] (-> :hopper/guidance/NestedHopperEnvelope -> :hopper/guidance/BurnDurationMs -> :Int)",
            ":OuterHopper [#Some] (-> :NestedHopperEnvelope [#Some] [#Right] [#Some] (-> :BurnDurationMs -> :Int))",
            ":Option( :NestedHopperEnvelope ) [#Some] [#Some] [#Right] [#Some]",
            ":DoubleOuter [#Some] (-> :OuterHopper [#Some] (-> :NestedHopperEnvelope [#Some] [#Right] [#Some] (-> :BurnDurationMs -> :Int)))"
        };
        for (int i = 0; i < expectedFull.length; i++) {
            assertEquals("Slot " + i + " inlay mismatch in State A",
                expectedFull[i], stateAInlays.get(stateASlots.get(i).getTextRange().getEndOffset()));
        }

        // Verify 1:1 bracket parity with StvnConstantSumSpineRenderer 'Expanded Value:'
        Map<String, ConstantDefinition> constByName = new LinkedHashMap<>();
        for (ConstantDefinition cd : PsiTreeUtil.findChildrenOfType(stateAFile, ConstantDefinition.class)) {
            if (cd.getValueKeyword() != null) {
                constByName.put(cd.getValueKeyword().getText().trim(), cd);
            }
        }
        String defaultSpineHtml = StvnConstantSumSpineRenderer.renderConstantSumSpineRows(
            constByName.get("#DEFAULT_DESCENT_PLAN"), true);
        assertNotNull(defaultSpineHtml);
        assertTrue(defaultSpineHtml.contains("<b>Expanded Value:</b> <code>[#Some] [#Right] [#Some] 625</code>"));

        String partialSpineHtml = StvnConstantSumSpineRenderer.renderConstantSumSpineRows(
            constByName.get("#PARTIAL_CONST"), true);
        assertNotNull(partialSpineHtml);
        assertTrue(partialSpineHtml.contains("<b>Expanded Value:</b> <code>[#Some] #Right [#Some] 625</code>"));

        String outerConstSpineHtml = StvnConstantSumSpineRenderer.renderConstantSumSpineRows(
            constByName.get("#OUTER_CONST"), true);
        assertNotNull(outerConstSpineHtml);
        assertTrue("Cross-layer #OUTER_CONST -> #INNER_EXPLICIT must preserve [#Some] #Some:\n" + outerConstSpineHtml,
            outerConstSpineHtml.contains("<b>Expanded Value:</b> <code>[#Some] #Some 250</code>"));

        // Verify all 5 AliasExpansionModes + short-form tags on Slot 0 (#DEFAULT_DESCENT_PLAN)
        Map<AliasExpansionMode, @Nullable String> slot0Modes = evaluateFormattedModes(stateASlots.get(0));
        assertEquals(":NestedHopperEnvelope [#Some] [#Right] [#Some] (-> :BurnDurationMs -> :Int)",
            slot0Modes.get(AliasExpansionMode.FULL));
        assertEquals(":NestedHopperEnvelope [#Some] [#Right] [#Some] (-> :BurnDurationMs)",
            slot0Modes.get(AliasExpansionMode.FIRST_HOP));
        assertEquals(":NestedHopperEnvelope [#Some] [#Right] [#Some] (-> :Int)",
            slot0Modes.get(AliasExpansionMode.ALIAS_TO_BASE));
        assertEquals(":NestedHopperEnvelope [#Some] [#Right] [#Some]",
            slot0Modes.get(AliasExpansionMode.SYMBOL_ONLY));
        assertNull(slot0Modes.get(AliasExpansionMode.NONE));

        StvnSettings.getInstance(getProject()).getState().useLongFormSumTypes = false;
        try {
            assertEquals(
                ":NestedHopperEnvelope [#S] [#R] [#S] (-> :BurnDurationMs -> :Int)",
                StvnTypeResolver.resolveValueType(stateASlots.get(0))
            );
        } finally {
            StvnSettings.getInstance(getProject()).getState().useLongFormSumTypes = true;
        }

        // Verify State B (Pass-3-Suspended pure-PSI fallback) parity across slots 0..5 and 7..9
        String suspendedSrc = spineMatrixSrc.replace(
            ":type :Matrix",
            ":defs { :SuspendPass3 :Int }\n  :type :Matrix"
        );
        PsiFile stateBFile = myFixture.addFileToProject(
            "b3_spine/const_sum_spine_suspended.telemetry.stvn_i",
            suspendedSrc.replace("#name \"const_sum_spine_parity\"", "#name \"const_sum_spine_suspended\"")
        );
        myFixture.configureFromExistingVirtualFile(stateBFile.getVirtualFile());
        assertTrue(
            "State B fixture must suspend Pass 3 payload lowering",
            StvnCompiledDocumentCache.getSnapshot(stateBFile).document().flatMap(StvnDocument::payload).isEmpty()
        );
        List<Value> stateBSlots = extractOuterBodyTupleSlots(stateBFile);
        for (int idx : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}) {
            assertEquals("State B must match State A on slot " + idx,
                expectedFull[idx], StvnTypeResolver.resolveValueType(stateBSlots.get(idx)));
        }

        // Verify click-materialization isolation across FULL, FIRST_HOP, ALIAS_TO_BASE, and SYMBOL_ONLY
        myFixture.configureFromExistingVirtualFile(stateAFile.getVirtualFile());
        List<Value> liveSlots = extractOuterBodyTupleSlots(stateAFile);
        Value directSlot = liveSlots.get(0);
        Value nominalOuterSlot = liveSlots.get(7);
        Value anonOuterSlot = liveSlots.get(8);
        Value doubleOuterSlot = liveSlots.get(9);

        for (AliasExpansionMode mode : List.of(
                AliasExpansionMode.FULL,
                AliasExpansionMode.FIRST_HOP,
                AliasExpansionMode.ALIAS_TO_BASE,
                AliasExpansionMode.SYMBOL_ONLY)) {
            // 1. Direct #DEFAULT_DESCENT_PLAN slot: 0 materializable tags, clickable :Type hops preserved
            String directBadge = StvnTypePresentationUtil.formatAliasChain(expectedFull[0], mode).orElseThrow();
            assertEquals(0, StvnTypeResolver.resolveConstBarrierStartInBadge(directSlot, directBadge));
            assertTrue("Direct #CONST slot must expose 0 materializable [#tag] segments in " + mode,
                StvnInlayHintsCollector.extractBoundMaterializableTagSegments(directSlot, directBadge).isEmpty());

            // 2. Nominal outer :OuterHopper :Option(:NestedHopperEnvelope): strictly 1 outer [#Some] before (-> :NestedHopperEnvelope
            String nomOuterBadge = StvnTypePresentationUtil.formatAliasChain(expectedFull[7], mode).orElseThrow();
            var nomBound = StvnInlayHintsCollector.extractBoundMaterializableTagSegments(nominalOuterSlot, nomOuterBadge);
            assertEquals("Nominal outer slot must bind strictly 1 outer [#Some] in " + mode, 1, nomBound.size());
            assertEquals(0, nomBound.get(0).stepIndex());
            assertEquals(":OuterHopper ".length(), nomBound.get(0).startInBadge());

            // 3. Anonymous outer :Option( :NestedHopperEnvelope ) [#Some] [#Some] [#Right] [#Some]: strictly 1st [#Some] after ')'
            String anonOuterBadge = StvnTypePresentationUtil.formatAliasChain(expectedFull[8], mode).orElseThrow();
            var anonBound = StvnInlayHintsCollector.extractBoundMaterializableTagSegments(anonOuterSlot, anonOuterBadge);
            assertEquals("Anonymous outer slot must bind strictly 1 outer [#Some] after ')' in " + mode,
                1, anonBound.size());
            assertEquals(0, anonBound.get(0).stepIndex());
            assertEquals(":Option( :NestedHopperEnvelope ) ".length(), anonBound.get(0).startInBadge());

            // 4. Two-hop outer :DoubleOuter: 2 outer steps in FULL/FIRST_HOP, 1 outer step when truncated in ALIAS_TO_BASE/SYMBOL_ONLY
            String dblBadge = StvnTypePresentationUtil.formatAliasChain(expectedFull[9], mode).orElseThrow();
            var dblBound = StvnInlayHintsCollector.extractBoundMaterializableTagSegments(doubleOuterSlot, dblBadge);
            int expectedDblCount = (mode == AliasExpansionMode.FULL || mode == AliasExpansionMode.FIRST_HOP) ? 2 : 1;
            assertEquals("DoubleOuter bound tag count mismatch in " + mode, expectedDblCount, dblBound.size());
        }

        // Verify per-hop :Type clickability on direct #DEFAULT_DESCENT_PLAN and safe :body materialization on outer slots
        List<StvnInlayHintsCollector.ClickableTypeSegment> directTypeLinks =
            StvnInlayHintsCollector.extractBoundClickableTypeSegments(directSlot, expectedFull[0]);
        assertEquals(2, directTypeLinks.size());
        assertEquals(":NestedHopperEnvelope", directTypeLinks.get(0).tokenText());
        assertEquals(":BurnDurationMs", directTypeLinks.get(1).tokenText());
        assertNotNull(PsiTreeUtil.getParentOfType(directTypeLinks.get(1).targetElement(), TypeDefinition.class, false));
        assertFalse("Direct #CONST slot must refuse materializeInferredSumStep",
            StvnInlayHintsCollector.materializeInferredSumStep(directSlot, 0));

        assertTrue("Anonymous outer slot must materialize outer #Some before #DEFAULT_DESCENT_PLAN",
            StvnInlayHintsCollector.materializeInferredSumStep(anonOuterSlot, 0));
        List<Value> refreshedSlots = extractOuterBodyTupleSlots(stateAFile);
        assertTrue("Nominal outer slot must materialize outer #Some before #DEFAULT_DESCENT_PLAN",
            StvnInlayHintsCollector.materializeInferredSumStep(refreshedSlots.get(7), 0));
        var postMaterializeSnapshot = StvnCompiledDocumentCache.getSnapshot(stateAFile);
        assertTrue(
            "Expected 0 compiler diagnostics after outer #Some materialization, got: "
                + postMaterializeSnapshot.diagnostics(),
            postMaterializeSnapshot.diagnostics().isEmpty()
        );
        List<HighlightInfo> postMaterializeIssues = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR || h.getSeverity() == HighlightSeverity.WARNING)
            .toList();
        assertTrue("Expected 0 highlight errors/warnings after outer #Some materialization: " + postMaterializeIssues,
            postMaterializeIssues.isEmpty());
    }

    private IntentionAction findSingleExactIntention(String exactText) {
        List<IntentionAction> matches = myFixture.getAvailableIntentions().stream()
            .filter(a -> exactText.equals(a.getText()))
            .toList();
        assertEquals(
            "Expected strictly count == 1 for intention [" + exactText + "], all available: "
                + myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList(),
            1,
            matches.size()
        );
        return matches.get(0);
    }

    private IntentionAction assertSingleIntentionWithDiffPreview(String exactText) {
        IntentionAction action = findSingleExactIntention(exactText);
        String preview = myFixture.getIntentionPreviewText(action);
        assertNotNull("Expected non-null DIFF preview for [" + exactText + "]", preview);
        assertFalse("Preview text must not be blank for [" + exactText + "]", preview.isBlank());
        return action;
    }

    private void assertSingleNavigationIntentionWithEmptyPreview(String exactText) {
        IntentionAction action = findSingleExactIntention(exactText);
        String preview = myFixture.getIntentionPreviewText(action);
        assertNull("Pure navigation intention [" + exactText + "] must return EMPTY (null) preview", preview);
    }

    private void assertQuickFixHealsToZeroDiagnostics(
        String fileName,
        String source,
        int caretOffset,
        String exactFixText,
        String expectedSubstring
    ) {
        myFixture.configureByText(fileName, source);
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        IntentionAction fix = assertSingleIntentionWithDiffPreview(exactFixText);
        myFixture.launchAction(fix);
        String mutated = myFixture.getFile().getText();
        assertTrue(
            "Expected [" + expectedSubstring + "] after [" + exactFixText + "], got:\n" + mutated,
            mutated.contains(expectedSubstring)
        );
        assertCompilesZeroErrorsAndZeroWarnings(mutated, fileName);
    }

    private static void assertCompilesZeroErrorsAndZeroWarnings(String content, String docPath) {
        var result = StvnCompiler.compile(content, docPath);
        assertTrue(
            "Expected 0 compiler diagnostics on " + docPath + ", got: " + result.diagnostics(),
            result.diagnostics().isEmpty()
        );
    }

    private Map<AliasExpansionMode, @Nullable String> evaluateFormattedModes(Value slot) {
        String full = StvnTypeResolver.resolveValueType(slot);
        assertNotNull(full);
        Map<AliasExpansionMode, @Nullable String> out = new LinkedHashMap<>();
        for (AliasExpansionMode mode : AliasExpansionMode.values()) {
            out.put(mode, StvnTypePresentationUtil.formatAliasChain(full, mode).orElse(null));
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
}
