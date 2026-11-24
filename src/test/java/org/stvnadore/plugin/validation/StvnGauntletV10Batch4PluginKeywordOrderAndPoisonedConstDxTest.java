package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.hints.InlayHintsSink;
import com.intellij.codeInsight.hints.presentation.InlayPresentation;
import com.intellij.codeInsight.hints.presentation.RootInlayPresentation;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.hints.StvnInlayHintsCollector;
import org.stvnadore.plugin.hints.StvnTypeInferenceHelper;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.AliasExpansionMode;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.plugin.util.StvnTypePresentationUtil;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetadataValue;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.VariantList;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end IntelliJ Platform verification suite for Gauntlet v10 Batch 4
 * ({@code G10B4-01} and {@code G10B4-02}) across {@code Pass A} ({@code 0} inspections)
 * and {@code Pass B} ({@code 26} inspections).
 */
@NullMarked
public final class StvnGauntletV10Batch4PluginKeywordOrderAndPoisonedConstDxTest extends BasePlatformTestCase {

    private static final Pattern COMPLETE_SUFFIX_PATTERN =
        Pattern.compile("\\[stvn-(?:core|plugin):([A-Z0-9]+-[A-Z0-9_]+)\\]");

    private static final String REORDER_FACETS_FIX = "Reorder metadata facets to canonical 5-tier order";

    private boolean savedUseLongFormSumTypes = true;
    private boolean savedShowTypeHints = true;
    private AliasExpansionMode savedAliasExpansionMode = AliasExpansionMode.FULL;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        LocalInspectionTool[] initTools = createAll26Inspections(false);
        myFixture.enableInspections(initTools);
        myFixture.disableInspections(initTools);

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
            var styleSettings = StvnSettings.getInstance(getProject());
            styleSettings.getState().useLongFormSumTypes = savedUseLongFormSumTypes;

            var projSettings = StvnProjectSettings.getInstance(getProject());
            projSettings.getState().showTypeHints = savedShowTypeHints;
            projSettings.getState().aliasExpansionMode = savedAliasExpansionMode;
        } finally {
            super.tearDown();
        }
    }

    private LocalInspectionTool[] createAll26Inspections(boolean highlightDegradedBody) {
        var degradedSchemaInspection = new StvnDegradedSchemaInspection();
        degradedSchemaInspection.highlightBodyValuesBoundToDegradedSchemas = highlightDegradedBody;
        return new LocalInspectionTool[]{
            new StvnVariantStyleInspection(),
            new StvnBooleanValidityInspection(),
            new StvnMapStructuralInspection(),
            degradedSchemaInspection,
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
        runInPassAAndPassB(false, assertions);
    }

    private void runInPassAAndPassB(boolean highlightDegradedBody, Runnable assertions) {
        assertions.run();
        LocalInspectionTool[] all = createAll26Inspections(highlightDegradedBody);
        myFixture.enableInspections(all);
        try {
            assertions.run();
        } finally {
            myFixture.disableInspections(all);
        }
    }

    public void testTarget1_ValveTag12xKeywordOnlyE008FullEntryReorderAndDisjointHoverParity() {
        String valveTagSrc = """
            {
              :meta {
                #name "schema_facet_order_keyword_pinning_valve_tag"
                #kind #BODY
              }
              :defs {
                :DisorderedValveTag {
                  #regex "^VLV-[A-Z0-9]{2,8}$"
                  #preserveIndent #TRUE
                  #maxIncl "VLV-ZZZZ"
                  #minIncl "VLV-0000"
                  #comparable #FALSE
                  #maxSize 12
                  #minSize 6
                } :String
                :DisorderedSensorGain {
                  #maxExcl 100.0
                  #minExcl 0.0
                  #equatable #TRUE
                  #size 32
                } :Float
                :DisorderedValvePort {
                  #minIncl 1024
                  #size 16
                  #unsigned
                } :Int
                :ValveMode :Enum [ #A #B #C ]
                :DisorderedValveMode {
                  #filterIncl [ #A #B ]
                  #comparable #FALSE
                } :ValveMode
              }
              :type :DisorderedValveTag
              :body "VLV"
            }
            """;

        runInPassAAndPassB(() -> {
            PsiFile file = myFixture.configureByText("schema_facet_order_keyword_pinning_valve_tag.stvn", valveTagSrc);
            List<HighlightInfo> diags = collectDiagnosticHighlights(myFixture.doHighlighting());
            assertNoDuplicateDiagnosticRanges("ValveTag", diags);

            List<HighlightInfo> orderErrors = diags.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
                    && h.getDescription() != null
                    && h.getDescription().contains("E008-ERR_FACET_ORDER_VIOLATION"))
                .toList();
            assertEquals("Expected strictly 12 keyword-pinned E008 order violations: " + orderErrors, 12, orderErrors.size());

            int[][] expectedKeywordSpans = {
                spanOf(valveTagSrc, "#preserveIndent", 0),
                spanOf(valveTagSrc, "#maxIncl", 0),
                spanOf(valveTagSrc, "#minIncl \"VLV-0000\"", "#minIncl"),
                spanOf(valveTagSrc, "#comparable #FALSE", "#comparable"),
                spanOf(valveTagSrc, "#maxSize", 0),
                spanOf(valveTagSrc, "#minSize", 0),
                spanOf(valveTagSrc, "#minExcl", 0),
                spanOf(valveTagSrc, "#equatable", 0),
                spanOf(valveTagSrc, "#size 32", "#size"),
                spanOf(valveTagSrc, "#size 16", "#size"),
                spanOf(valveTagSrc, "#unsigned", 0),
                spanOf(valveTagSrc, "#comparable #FALSE\n    } :ValveMode", "#comparable")
            };
            for (int[] expectedSpan : expectedKeywordSpans) {
                assertTrue(
                    "Missing keyword-only E008 span [" + expectedSpan[0] + ", " + expectedSpan[1] + ") in " + orderErrors,
                    orderErrors.stream().anyMatch(h -> h.getStartOffset() == expectedSpan[0] && h.getEndOffset() == expectedSpan[1])
                );
            }

            // Pass 3 E012 remains active because E008 is non-poisoning
            int bodyLitStart = valveTagSrc.indexOf("\"VLV\"");
            assertTrue(
                "Expected Pass 3 E012 on \"VLV\" because E008 is non-poisoning: " + diags,
                diags.stream().anyMatch(h -> h.getSeverity() == HighlightSeverity.ERROR
                    && h.getStartOffset() == bodyLitStart
                    && h.getEndOffset() == bodyLitStart + "\"VLV\"".length()
                    && h.getDescription().contains("E012-"))
            );

            // Full-entry Alt+Enter reorder availability (count == 1 + DIFF preview) across keywords and trailing arguments
            int[] reorderCaretOffsets = {
                valveTagSrc.indexOf(":DisorderedValveTag") + 2,
                valveTagSrc.indexOf("#regex") + 2,
                valveTagSrc.indexOf("\"^VLV-[A-Z0-9]{2,8}$\"") + 3,
                valveTagSrc.indexOf("#preserveIndent") + 2,
                valveTagSrc.indexOf("#TRUE") + 2,
                valveTagSrc.indexOf("#maxIncl") + 2,
                valveTagSrc.indexOf("\"VLV-ZZZZ\"") + 3,
                valveTagSrc.indexOf("#minIncl \"VLV-0000\"") + 2,
                valveTagSrc.indexOf("\"VLV-0000\"") + 3,
                valveTagSrc.indexOf("#comparable #FALSE") + 2,
                valveTagSrc.indexOf("#FALSE") + 2,
                valveTagSrc.indexOf("#maxSize") + 2,
                valveTagSrc.indexOf("12") + 1,
                valveTagSrc.indexOf("#minSize") + 2,
                valveTagSrc.indexOf("6\n") ,
                valveTagSrc.indexOf("100.0") + 1,
                valveTagSrc.indexOf("0.0") + 1,
                valveTagSrc.indexOf("32\n") + 1,
                valveTagSrc.indexOf("16\n") + 1,
                valveTagSrc.indexOf("[ #A #B ]"),
                valveTagSrc.indexOf("#A #B ]") + 1,
                valveTagSrc.indexOf("#B ]") + 1
            };
            for (int caretOffset : reorderCaretOffsets) {
                myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
                assertSingleIntentionWithDiffPreview(REORDER_FACETS_FIX);
            }

            // Disjoint hover bounding: keyword -> Schema Metadata Facet; trailing argument -> Metadata Facet Value; whitespace -> null
            var docProvider = new StvnDocumentationProvider();

            int wsOff = valveTagSrc.indexOf("#preserveIndent #TRUE") + "#preserveIndent".length();
            PsiElement wsLeaf = file.findElementAt(wsOff);
            assertNotNull(wsLeaf);
            assertNull(
                "Interior whitespace between #preserveIndent and #TRUE must return null hover target",
                docProvider.getCustomDocumentationElement(myFixture.getEditor(), file, wsLeaf, wsOff)
            );

            int preserveKwOff = valveTagSrc.indexOf("#preserveIndent") + 3;
            String preserveKwDoc = renderHoverAt(docProvider, file, preserveKwOff);
            assertNotNull(preserveKwDoc);
            assertTrue("Expected Metadata Facet card on #preserveIndent: " + preserveKwDoc,
                preserveKwDoc.contains("<b>Metadata Facet:</b>") && preserveKwDoc.contains("#preserveIndent"));

            int trueValOff = valveTagSrc.indexOf("#TRUE") + 2;
            PsiElement trueLeaf = file.findElementAt(trueValOff);
            assertNotNull(trueLeaf);
            PsiElement trueTarget = docProvider.getCustomDocumentationElement(myFixture.getEditor(), file, trueLeaf, trueValOff);
            assertTrue("Expected MetadataValue hover target on #TRUE, got: " + trueTarget, trueTarget instanceof MetadataValue);
            String trueValDoc = docProvider.generateDoc(trueTarget, trueLeaf);
            assertNotNull(trueValDoc);
            assertTrue("Expected Metadata Facet Argument card on #TRUE: " + trueValDoc,
                trueValDoc.contains("<b>Metadata Facet Argument:</b>") && trueValDoc.contains("#TRUE"));
            assertFalse("Trailing #TRUE argument must carry 0 sticky E008 text: " + trueValDoc,
                trueValDoc.contains("E008") || trueValDoc.contains("violates canonical 5-tier order"));

            int falseValOff = valveTagSrc.indexOf("#FALSE") + 2;
            String falseValDoc = renderHoverAt(docProvider, file, falseValOff);
            assertNotNull(falseValDoc);
            assertTrue(falseValDoc.contains("<b>Metadata Facet Argument:</b>") && falseValDoc.contains("#FALSE"));
            assertFalse(falseValDoc.contains("E008") || falseValDoc.contains("violates canonical 5-tier order"));

            int strBoundOff = valveTagSrc.indexOf("\"VLV-ZZZZ\"") + 3;
            String strBoundDoc = renderHoverAt(docProvider, file, strBoundOff);
            assertNotNull(strBoundDoc);
            assertTrue(strBoundDoc.contains("<b>Metadata Facet Argument:</b>") && strBoundDoc.contains("\"VLV-ZZZZ\""));

            int intBoundOff = valveTagSrc.indexOf("12\n") + 1;
            String intBoundDoc = renderHoverAt(docProvider, file, intBoundOff);
            assertNotNull(intBoundDoc);
            assertTrue(intBoundDoc.contains("<b>Metadata Facet Argument:</b>") && intBoundDoc.contains("12"));

            int filterBracketOff = valveTagSrc.indexOf("[ #A #B ]");
            PsiElement bracketLeaf = file.findElementAt(filterBracketOff);
            assertNotNull(bracketLeaf);
            PsiElement bracketTarget = docProvider.getCustomDocumentationElement(myFixture.getEditor(), file, bracketLeaf, filterBracketOff);
            assertTrue("Expected VariantList hover target on '[' of #filterIncl [ #A #B ]", bracketTarget instanceof VariantList);
            String filterListDoc = docProvider.generateDoc(bracketTarget, bracketLeaf);
            assertNotNull(filterListDoc);
            assertTrue(filterListDoc.contains("<b>Enum Filter Variant List:</b>") && filterListDoc.contains("2"));

            int variantInsideFilterOff = valveTagSrc.indexOf("#A #B ]") + 1;
            String variantDoc = renderHoverAt(docProvider, file, variantInsideFilterOff);
            assertNotNull(variantDoc);
            assertTrue("Individual #A inside #filterIncl must preserve Enum Variant hover: " + variantDoc,
                variantDoc.contains("#A") && variantDoc.contains(":ValveMode"));
        });

        // Verify launching reorder on trailing #TRUE argument heals :DisorderedValveTag to canonical 5-tier order
        myFixture.configureByText("schema_facet_order_keyword_pinning_valve_tag.stvn", valveTagSrc);
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(valveTagSrc.indexOf("#TRUE") + 2);
        IntentionAction reorderFix = assertSingleIntentionWithDiffPreview(REORDER_FACETS_FIX);
        myFixture.launchAction(reorderFix);
        String mutated = myFixture.getFile().getText();
        int idxMinSize = mutated.indexOf("#minSize 6");
        int idxMaxSize = mutated.indexOf("#maxSize 12");
        int idxComparable = mutated.indexOf("#comparable #FALSE");
        int idxMinIncl = mutated.indexOf("#minIncl \"VLV-0000\"");
        int idxMaxIncl = mutated.indexOf("#maxIncl \"VLV-ZZZZ\"");
        int idxPreserve = mutated.indexOf("#preserveIndent #TRUE");
        int idxRegex = mutated.indexOf("#regex \"^VLV-[A-Z0-9]{2,8}$\"");
        assertTrue(idxMinSize < idxMaxSize && idxMaxSize < idxComparable
            && idxComparable < idxMinIncl && idxMinIncl < idxMaxIncl
            && idxMaxIncl < idxPreserve && idxPreserve < idxRegex);
    }

    public void testTarget1_AllMetadataEntryAlternativesZeroWhitespaceCoLocatedW003AndAdjacentPredecessorParity() {
        String matrixSrc = """
            {
              :meta {
                #name "metadata_entry_alternatives_matrix"
                #kind #BODY
              }
              :defs {
                #MAX_LIMIT :Int 500
                :ZeroWsString {
                  #regex "^A+$"
                  #preserveIndent#TRUE
                } :String
                :CoLocatedRedundant {
                  #minIncl 10
                  #size 32
                } :Int
                :NonStrictlyDescending {
                  #minIncl 10
                  #unsigned
                  #comparable #TRUE
                } :Int
                :ConstBackedBound {
                  #maxExcl #MAX_LIMIT
                  #size 32
                } :Int
                :TemporalEpoch {
                  #minIncl 0
                  #ms
                } :TimeEpoch
                :TemporalAudited {
                  #comparable #TRUE
                  #audited
                } :DateTime
                :ExactDecimal {
                  #maxSize 18
                  #exact
                } :Float
                :InvertibleLookup {
                  #minSize 1
                  #invertible
                } :Map(:Int :Int)
                :FilterBase :Enum [ #X #Y #Z ]
                :FilterExclOrder {
                  #filterExcl [ #Z ]
                  #equatable #TRUE
                } :FilterBase
                #DISORDERED_CONST { #minIncl 5 #size 16 } :Int 10
              }
              :type { #minIncl 1 #size 16 } :Int
              :body 4
            }
            """;

        runInPassAAndPassB(() -> {
            PsiFile file = myFixture.configureByText("metadata_entry_alternatives_matrix.stvn", matrixSrc);
            List<HighlightInfo> diags = collectDiagnosticHighlights(myFixture.doHighlighting());
            assertNoDuplicateDiagnosticRanges("AlternativesMatrix", diags);

            // 1. Zero-whitespace #preserveIndent#TRUE pins strictly to #preserveIndent (15 chars)
            int zeroWsStart = matrixSrc.indexOf("#preserveIndent#TRUE");
            int zeroWsKwEnd = zeroWsStart + "#preserveIndent".length();
            assertTrue(
                "Zero-whitespace #preserveIndent#TRUE must pin E008 strictly to #preserveIndent: " + diags,
                diags.stream().anyMatch(h -> h.getSeverity() == HighlightSeverity.ERROR
                    && h.getStartOffset() == zeroWsStart
                    && h.getEndOffset() == zeroWsKwEnd
                    && h.getDescription().contains("E008-ERR_FACET_ORDER_VIOLATION"))
            );
            myFixture.getEditor().getCaretModel().moveToOffset(zeroWsKwEnd + 2);
            assertSingleIntentionWithDiffPreview(REORDER_FACETS_FIX);

            var docProvider = new StvnDocumentationProvider();
            String zeroWsKwDoc = renderHoverAt(docProvider, file, zeroWsStart + 3);
            String zeroWsValDoc = renderHoverAt(docProvider, file, zeroWsKwEnd + 2);
            assertNotNull(zeroWsKwDoc);
            assertNotNull(zeroWsValDoc);
            assertTrue(zeroWsKwDoc.contains("<b>Metadata Facet:</b>"));
            assertTrue(zeroWsValDoc.contains("<b>Metadata Facet Argument:</b>") && zeroWsValDoc.contains("#TRUE"));

            // 2. Co-located { #minIncl 10 #size 32 } :Int -> E008 on #size (5 chars) + W003 on #size 32 (8 chars)
            int coSizeStart = matrixSrc.indexOf("#size 32\n    } :Int");
            assertTrue(
                "Expected keyword-only E008 on #size in :CoLocatedRedundant: " + diags,
                diags.stream().anyMatch(h -> h.getSeverity() == HighlightSeverity.ERROR
                    && h.getStartOffset() == coSizeStart
                    && h.getEndOffset() == coSizeStart + "#size".length()
                    && h.getDescription().contains("E008-ERR_FACET_ORDER_VIOLATION"))
            );
            assertTrue(
                "Expected full-entry W003 on #size 32 in :CoLocatedRedundant: " + diags,
                diags.stream().anyMatch(h -> h.getSeverity() == HighlightSeverity.WARNING
                    && h.getStartOffset() == coSizeStart
                    && h.getEndOffset() == coSizeStart + "#size 32".length()
                    && h.getDescription().contains("W003-WARN_REDUNDANT_METADATA_FACET"))
            );
            myFixture.getEditor().getCaretModel().moveToOffset(coSizeStart + "#size ".length() + 1);
            assertSingleIntentionWithDiffPreview(REORDER_FACETS_FIX);

            // 3. Non-strictly-descending { #minIncl 10 #unsigned #comparable #TRUE } -> only #unsigned is E008
            int nonStrictBlockStart = matrixSrc.indexOf(":NonStrictlyDescending");
            int nonStrictUnsignedStart = matrixSrc.indexOf("#unsigned", nonStrictBlockStart);
            int nonStrictCompStart = matrixSrc.indexOf("#comparable #TRUE", nonStrictBlockStart);
            assertTrue(
                diags.stream().anyMatch(h -> h.getSeverity() == HighlightSeverity.ERROR
                    && h.getStartOffset() == nonStrictUnsignedStart
                    && h.getEndOffset() == nonStrictUnsignedStart + "#unsigned".length()
                    && h.getDescription().contains("E008-ERR_FACET_ORDER_VIOLATION"))
            );
            assertFalse(
                "Adjacent-predecessor lastRank must NOT flag #comparable after #unsigned: " + diags,
                diags.stream().anyMatch(h -> h.getStartOffset() == nonStrictCompStart
                    && h.getDescription().contains("E008-ERR_FACET_ORDER_VIOLATION"))
            );

            // 4. #CONST-backed bound hover preserves ConstantDefinition card on #MAX_LIMIT while #maxExcl shows Facet card
            int maxExclKwOff = matrixSrc.indexOf("#maxExcl #MAX_LIMIT") + 2;
            int maxLimitRefOff = matrixSrc.indexOf("#MAX_LIMIT", maxExclKwOff) + 2;
            String maxExclDoc = renderHoverAt(docProvider, file, maxExclKwOff);
            String maxLimitDoc = renderHoverAt(docProvider, file, maxLimitRefOff);
            assertNotNull(maxExclDoc);
            assertNotNull(maxLimitDoc);
            assertTrue(maxExclDoc.contains("<b>Metadata Facet:</b>") && maxExclDoc.contains("#maxExcl"));
            assertTrue("Expected ConstantDefinition card on #MAX_LIMIT facet argument: " + maxLimitDoc,
                maxLimitDoc.contains("#MAX_LIMIT") && maxLimitDoc.contains("500"));

            // 5. Bare flags (#ms, #audited, #exact, #invertible), #filterExcl, ConstantDefinition, and inline :type { ... }
            int[][] otherKeywordSpans = {
                spanOf(matrixSrc, "#ms", 0),
                spanOf(matrixSrc, "#audited", 0),
                spanOf(matrixSrc, "#exact", 0),
                spanOf(matrixSrc, "#invertible", 0),
                spanOf(matrixSrc, "#equatable #TRUE", "#equatable"),
                spanOf(matrixSrc, "#size 16 } :Int 10", "#size"),
                spanOf(matrixSrc, "#size 16 } :Int\n", "#size")
            };
            for (int[] expectedSpan : otherKeywordSpans) {
                assertTrue(
                    "Missing keyword-only E008 at [" + expectedSpan[0] + ", " + expectedSpan[1] + ") in " + diags,
                    diags.stream().anyMatch(h -> h.getSeverity() == HighlightSeverity.ERROR
                        && h.getStartOffset() == expectedSpan[0]
                        && h.getEndOffset() == expectedSpan[1]
                        && h.getDescription().contains("E008-ERR_FACET_ORDER_VIOLATION"))
                );
            }

            // 6. Standalone orphan :type { #regex "^VLV$" #filterIncl [ #A ] #comparable #FALSE } keyword-only E008 + reorder
            String orphanTypeSrc = """
                {
                  :meta {
                    #name "standalone_orphan_type_filter_order_keyword_pinning"
                    #kind #BODY
                  }
                  :type {
                    #regex "^VLV$"
                    #filterIncl [ #A ]
                    #comparable #FALSE
                  }
                  :body 0
                }
                """;
            myFixture.configureByText("standalone_orphan_type_filter_order_keyword_pinning.stvn", orphanTypeSrc);
            List<HighlightInfo> orphanDiags = collectDiagnosticHighlights(myFixture.doHighlighting());
            assertNoDuplicateDiagnosticRanges("OrphanType", orphanDiags);
            int orphanFilterStart = orphanTypeSrc.indexOf("#filterIncl");
            int orphanCompStart = orphanTypeSrc.indexOf("#comparable");
            assertTrue(orphanDiags.stream().anyMatch(h -> h.getSeverity() == HighlightSeverity.ERROR
                && h.getStartOffset() == orphanFilterStart
                && h.getEndOffset() == orphanFilterStart + "#filterIncl".length()
                && h.getDescription().contains("E008-ERR_FACET_ORDER_VIOLATION")));
            assertTrue(orphanDiags.stream().anyMatch(h -> h.getSeverity() == HighlightSeverity.ERROR
                && h.getStartOffset() == orphanCompStart
                && h.getEndOffset() == orphanCompStart + "#comparable".length()
                && h.getDescription().contains("E008-ERR_FACET_ORDER_VIOLATION")));
            myFixture.getEditor().getCaretModel().moveToOffset(orphanTypeSrc.indexOf("[ #A ]") + 2);
            assertSingleIntentionWithDiffPreview(REORDER_FACETS_FIX);
            myFixture.getEditor().getCaretModel().moveToOffset(orphanTypeSrc.indexOf("#FALSE") + 2);
            assertSingleIntentionWithDiffPreview(REORDER_FACETS_FIX);
        });
    }

    public void testTarget2_PoisonedConstantAdvisoryWarningJumpToDefsCrossFileUseAndZeroFalsePositives() {
        PsiFile includedModule = myFixture.addFileToProject(
            "b4_poison/poison_provider.telemetry.stvn_di",
            """
            {
              :meta {
                #name "poison_provider"
                #domain "telemetry"
                #kind #DEFS_INCLUDE
              }
              :defs {
                #EXTERNAL_POISON :Int #EXTERNAL_POISON
                :package :sensors {
                  :GainCode :Int
                  #STRIPPED_POISON :GainCode #STRIPPED_POISON
                  #REMOTE_POISON :GainCode #REMOTE_POISON
                }
              }
            }
            """
        );

        String consumerSrc = """
            {
              :meta {
                #name "poison_consumer"
                #domain "telemetry"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "./poison_provider.telemetry.stvn_di" ]
                :use [
                  :sensors { #strip } { #REMOTE_POISON #ALIASED_POISON }
                ]
                :Mode :Enum [ #ACTIVE #IDLE ]
                :OtherBrand :Int
                :PairTuple :Tuple(:Int :Int)
                #CONST_PING :Int #CONST_PONG
                #CONST_PONG :Int #CONST_PING
                #STEM_FROM_CYCLE :Int #CONST_PING
                #INVALID_RHS_CONST :Int "not_an_int"
                #WRONG_BRAND_POISON :OtherBrand #WRONG_BRAND_POISON
                #TUPLE_POISON :PairTuple #TUPLE_POISON
                #HEALTHY_WRONG_BRAND :OtherBrand 7
                #HEALTHY_TUPLE :PairTuple (1 2)
                #NON_POISON_E008_CONST { #minIncl 1 #size 16 } :Int 4
              }
              :type :Tuple(
                :Int
                :Int
                :Int
                :GainCode
                :GainCode
                :Int
                :Mode
                :Boolean
                :Option(:Int)
                :Int
                :GainCode
                :Int
                :GainCode
                :Int
                :Int
              )
              :body (
                #CONST_PING
                #STEM_FROM_CYCLE
                #INVALID_RHS_CONST
                #STRIPPED_POISON
                #ALIASED_POISON
                #EXTERNAL_POISON
                #ACTIVE
                #TRUE
                #None
                #UNDECLARED_CONST
                #WRONG_BRAND_POISON
                #TUPLE_POISON
                #HEALTHY_WRONG_BRAND
                #HEALTHY_TUPLE
                #NON_POISON_E008_CONST
              )
            }
            """;

        PsiFile consumerFile = myFixture.addFileToProject("b4_poison/poison_consumer.telemetry.stvn_i", consumerSrc);

        runInPassAAndPassB(() -> {
            myFixture.configureFromExistingVirtualFile(consumerFile.getVirtualFile());
            List<HighlightInfo> diags = collectDiagnosticHighlights(myFixture.doHighlighting());
            assertNoDuplicateDiagnosticRanges("PoisonConsumer", diags);

            int bodySectionIdx = consumerSrc.indexOf(":body (");
            List<HighlightInfo> bodyPoisonWarnings = diags.stream()
                .filter(h -> h.getStartOffset() > bodySectionIdx
                    && h.getSeverity() == HighlightSeverity.WARNING
                    && h.getDescription() != null
                    && h.getDescription().contains("[stvn-plugin:PW006-WARN_POISONED_CONSTANT_USAGE]"))
                .toList();

            String[] expectedWarnedTokens = {
                "#CONST_PING",
                "#STEM_FROM_CYCLE",
                "#INVALID_RHS_CONST",
                "#STRIPPED_POISON",
                "#ALIASED_POISON",
                "#EXTERNAL_POISON",
                "#WRONG_BRAND_POISON",
                "#TUPLE_POISON"
            };
            assertEquals(
                "Expected strictly 8 PW006-WARN_POISONED_CONSTANT_USAGE warnings in :body: " + bodyPoisonWarnings,
                expectedWarnedTokens.length,
                bodyPoisonWarnings.size()
            );

            for (String token : expectedWarnedTokens) {
                int start = consumerSrc.indexOf(token, bodySectionIdx);
                int end = start + token.length();
                assertTrue(
                    "Missing PW006 warning on " + token + ": " + bodyPoisonWarnings,
                    bodyPoisonWarnings.stream().anyMatch(h -> h.getStartOffset() == start && h.getEndOffset() == end)
                );
                myFixture.getEditor().getCaretModel().moveToOffset(start + 2);
                String targetDefName = "#ALIASED_POISON".equals(token) ? "#REMOTE_POISON" : token;
                String jumpText = "Jump to '" + targetDefName + "' declaration in :defs";
                IntentionAction jumpAction = findSingleExactIntention(jumpText);
                assertFalse("Jump intention must not start in write action", jumpAction.startInWriteAction());
                assertNull("Jump intention must return EMPTY preview", myFixture.getIntentionPreviewText(jumpAction));
            }

            // Verify 0 false positives on #ACTIVE, #TRUE, #None, #UNDECLARED_CONST (E030), #HEALTHY_WRONG_BRAND (E027), #HEALTHY_TUPLE (E028), #NON_POISON_E008_CONST
            String[] cleanOfPw006 = {
                "#ACTIVE",
                "#TRUE",
                "#None",
                "#UNDECLARED_CONST",
                "#HEALTHY_WRONG_BRAND",
                "#HEALTHY_TUPLE",
                "#NON_POISON_E008_CONST"
            };
            for (String token : cleanOfPw006) {
                int start = consumerSrc.indexOf(token, bodySectionIdx);
                assertFalse(
                    "Token " + token + " must NOT emit PW006-WARN_POISONED_CONSTANT_USAGE: " + bodyPoisonWarnings,
                    bodyPoisonWarnings.stream().anyMatch(h -> h.getStartOffset() == start)
                );
            }

            // Hover on #CONST_PING shows Suspended (Circular Constant) with [stvn-plugin:PW006-WARN_POISONED_CONSTANT_USAGE]
            var docProvider = new StvnDocumentationProvider();
            int bodyPingOff = consumerSrc.indexOf("#CONST_PING", bodySectionIdx) + 2;
            String pingHover = renderHoverAt(docProvider, consumerFile, bodyPingOff);
            assertNotNull(pingHover);
            assertTrue("Expected Suspended (Circular Constant) with PW006 code in hover: " + pingHover,
                pingHover.contains("Suspended (Circular Constant)")
                    && pingHover.contains("[stvn-plugin:PW006-WARN_POISONED_CONSTANT_USAGE]"));
        });

        // Verify same-file JumpToConstantDeclarationIntention lands on #CONST_PING declaration in :defs
        myFixture.configureFromExistingVirtualFile(consumerFile.getVirtualFile());
        myFixture.doHighlighting();
        int bodySectionIdx = consumerSrc.indexOf(":body (");
        myFixture.getEditor().getCaretModel().moveToOffset(consumerSrc.indexOf("#CONST_PING", bodySectionIdx) + 2);
        IntentionAction sameFileJump = findSingleExactIntention("Jump to '#CONST_PING' declaration in :defs");
        sameFileJump.invoke(getProject(), myFixture.getEditor(), consumerFile);
        ConstantDefinition pingDef = PsiTreeUtil.findChildrenOfType(consumerFile, ConstantDefinition.class).stream()
            .filter(cd -> cd.getValueKeyword() != null && "#CONST_PING".equals(cd.getValueKeyword().getText()))
            .findFirst()
            .orElseThrow();
        assertEquals(
            pingDef.getValueKeyword().getTextOffset(),
            myFixture.getEditor().getCaretModel().getOffset()
        );

        // Verify cross-file JumpToConstantDeclarationIntention opens included module at #EXTERNAL_POISON
        myFixture.getEditor().getCaretModel().moveToOffset(consumerSrc.indexOf("#EXTERNAL_POISON", bodySectionIdx) + 2);
        myFixture.doHighlighting();
        IntentionAction crossFileJump = findSingleExactIntention("Jump to '#EXTERNAL_POISON' declaration in :defs");
        crossFileJump.invoke(getProject(), myFixture.getEditor(), consumerFile);
        Editor selectedEditor = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
        assertNotNull(selectedEditor);
        ConstantDefinition extDef = PsiTreeUtil.findChildrenOfType(includedModule, ConstantDefinition.class).stream()
            .filter(cd -> cd.getValueKeyword() != null && "#EXTERNAL_POISON".equals(cd.getValueKeyword().getText()))
            .findFirst()
            .orElseThrow();
        assertEquals(extDef.getValueKeyword().getTextOffset(), selectedEditor.getCaretModel().getOffset());

        // Verify already-degraded schema slot yields StvnDegradedSchemaInspection (0 stacked PW006 warnings)
        String degradedSlotSrc = """
            {
              :meta {
                #name "degraded_slot_poison_const"
                #kind #BODY
              }
              :defs {
                :BrokenRange { #minIncl 50 #maxExcl 10 } :Int
                #POISONED_IN_DEGRADED :BrokenRange #POISONED_IN_DEGRADED
              }
              :type :BrokenRange
              :body #POISONED_IN_DEGRADED
            }
            """;
        for (boolean highlightDegradedBody : new boolean[]{false, true}) {
            runInPassAAndPassB(highlightDegradedBody, () -> {
                myFixture.configureByText("degraded_slot_poison_const.stvn", degradedSlotSrc);
                List<HighlightInfo> diags = collectDiagnosticHighlights(myFixture.doHighlighting());
                assertNoDuplicateDiagnosticRanges("DegradedSlot_" + highlightDegradedBody, diags);
                int bodyConstStart = degradedSlotSrc.lastIndexOf("#POISONED_IN_DEGRADED");
                List<HighlightInfo> slotWarnings = diags.stream()
                    .filter(h -> h.getStartOffset() == bodyConstStart && h.getSeverity() == HighlightSeverity.WARNING)
                    .toList();
                assertEquals("Expected strictly 1 warning on #POISONED_IN_DEGRADED: " + slotWarnings, 1, slotWarnings.size());
                assertTrue(slotWarnings.get(0).getDescription().contains("[stvn-plugin:PW006-WARN_POISONED_CONSTANT_USAGE]"));
            });
        }
    }

    public void testTarget2_PoisonedConstantDegradedSlotInlayBadgesAcrossStateAStateBAndAllFiveExpansionModes() {
        String stateASrc = """
            {
              :meta {
                #name "poison_inlay_matrix"
                #kind #BODY
              }
              :defs {
                :SensorGainCode :Int
                :AltitudeCm { #unsigned #size 16 } :Int
                :CouplerPairTuple :Tuple(:Int :Int)
                :OptAltitudeCm :Option(:AltitudeCm)
                :WrongBrand :Int
                #CONST_PING :Int #CONST_PONG
                #CONST_PONG :Int #CONST_PING
                #GAIN_POISON :SensorGainCode #GAIN_POISON
                #TUPLE_POISON :CouplerPairTuple #TUPLE_POISON
                #OPT_ALT_POISON :OptAltitudeCm #OPT_ALT_POISON
                #ALT_POISON :AltitudeCm #ALT_POISON
                #WRONG_BRAND_POISON :WrongBrand #WRONG_BRAND_POISON
              }
              :type :Tuple(
                :Int
                :SensorGainCode
                :CouplerPairTuple
                :OptAltitudeCm
                :OptAltitudeCm
                :SensorGainCode
                :Int
                :Int
              )
              :body (
                #CONST_PING
                #GAIN_POISON
                #TUPLE_POISON
                #OPT_ALT_POISON
                #Some #ALT_POISON
                #WRONG_BRAND_POISON
                #TUPLE_POISON
                #UNDECLARED_CONST
              )
            }
            """;

        PsiFile stateAFile = myFixture.configureByText("poison_inlay_matrix.stvn", stateASrc);
        assertTrue(
            "State A fixture must lower Pass 3 payload IR",
            StvnCompiledDocumentCache.getSnapshot(stateAFile).document().flatMap(StvnDocument::payload).isPresent()
        );

        String[] expectedFull = {
            "\u26A0 :Int",
            "\u26A0 :SensorGainCode (-> :Int)",
            "\u26A0 :CouplerPairTuple (-> :Tuple)",
            "\u26A0 :OptAltitudeCm",
            "\u26A0 :OptAltitudeCm #Some (-> :AltitudeCm -> :Int)"
        };

        List<Value> stateASlots = extractOuterBodyTupleSlots(stateAFile);
        assertEquals(8, stateASlots.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("State A slot " + i, expectedFull[i], StvnTypeResolver.resolveValueType(stateASlots.get(i)));
        }
        // Slots 5 (E027), 6 (E028), and 7 (E030) must suppress inlay badges (CCIH-01)
        for (int errIdx = 5; errIdx < 8; errIdx++) {
            assertNull("Error slot " + errIdx + " must return null resolveValueType",
                StvnTypeResolver.resolveValueType(stateASlots.get(errIdx)));
        }

        // Verify live collector output across all 5 AliasExpansionModes in State A
        Map<AliasExpansionMode, String[]> expectedByMode = Map.of(
            AliasExpansionMode.FULL, new String[]{
                "\u26A0 :Int",
                "\u26A0 :SensorGainCode (-> :Int)",
                "\u26A0 :CouplerPairTuple (-> :Tuple)",
                "\u26A0 :OptAltitudeCm",
                "\u26A0 :OptAltitudeCm #Some (-> :AltitudeCm -> :Int)"
            },
            AliasExpansionMode.FIRST_HOP, new String[]{
                "\u26A0 :Int",
                "\u26A0 :SensorGainCode (-> :Int)",
                "\u26A0 :CouplerPairTuple (-> :Tuple)",
                "\u26A0 :OptAltitudeCm",
                "\u26A0 :OptAltitudeCm #Some (-> :AltitudeCm)"
            },
            AliasExpansionMode.ALIAS_TO_BASE, new String[]{
                "\u26A0 :Int",
                "\u26A0 :SensorGainCode (-> :Int)",
                "\u26A0 :CouplerPairTuple (-> :Tuple)",
                "\u26A0 :OptAltitudeCm",
                "\u26A0 :OptAltitudeCm #Some (-> :Int)"
            },
            AliasExpansionMode.SYMBOL_ONLY, new String[]{
                "\u26A0 :Int",
                "\u26A0 :SensorGainCode",
                "\u26A0 :CouplerPairTuple",
                "\u26A0 :OptAltitudeCm",
                "\u26A0 :OptAltitudeCm #Some"
            }
        );

        for (var entry : expectedByMode.entrySet()) {
            AliasExpansionMode mode = entry.getKey();
            String[] expectedBadges = entry.getValue();
            StvnProjectSettings.getInstance(getProject()).getState().aliasExpansionMode = mode;
            Map<Integer, String> liveInlays = collectInlayMap(stateAFile);
            for (int i = 0; i < 5; i++) {
                Value slot = stateASlots.get(i);
                int slotOffset = StvnTypeInferenceHelper.calculateInlayBadgeOffset(
                    slot,
                    StvnTypeResolver.resolveCoreValue(slot)
                );
                String actual = liveInlays.get(slotOffset);
                assertNotNull("Expected inlay at slot " + i + " in mode " + mode + ", all=" + liveInlays, actual);
                assertTrue(
                    "Expected [" + expectedBadges[i] + "] in presentation [" + actual + "] for mode " + mode,
                    actual.contains(expectedBadges[i])
                );
            }
            for (int errIdx = 5; errIdx < 8; errIdx++) {
                Value errSlot = stateASlots.get(errIdx);
                int errOffset = StvnTypeInferenceHelper.calculateInlayBadgeOffset(
                    errSlot,
                    StvnTypeResolver.resolveCoreValue(errSlot)
                );
                assertFalse("Error slot " + errIdx + " must emit 0 inlays in mode " + mode + ": " + liveInlays,
                    liveInlays.containsKey(errOffset));
            }
        }

        // AliasExpansionMode.NONE suppresses all type badges
        StvnProjectSettings.getInstance(getProject()).getState().aliasExpansionMode = AliasExpansionMode.NONE;
        for (int i = 0; i < 5; i++) {
            assertTrue(StvnTypePresentationUtil.formatAliasChain(expectedFull[i], AliasExpansionMode.NONE).isEmpty());
        }
        StvnProjectSettings.getInstance(getProject()).getState().aliasExpansionMode = AliasExpansionMode.FULL;

        // Verify clickable :Type segments bind cleanly past the 2-char "⚠ " prefix and whole-sum #OPT_ALT_POISON has 0 materializable [#tag]
        var gainClickable = StvnInlayHintsCollector.extractBoundClickableTypeSegments(
            stateASlots.get(1),
            expectedFull[1]
        );
        assertEquals(1, gainClickable.size());
        assertEquals(":SensorGainCode", gainClickable.get(0).tokenText());
        assertNotNull(gainClickable.get(0).targetElement());

        assertTrue(
            "Poisoned whole-sum #OPT_ALT_POISON must expose 0 materializable [#tag] segments",
            StvnInlayHintsCollector.extractBoundMaterializableTagSegments(stateASlots.get(3), expectedFull[3]).isEmpty()
        );

        // Verify State B (Pass-3-Suspended pure-PSI fallback) 1:1 parity across slots 0..7
        String stateBSrc = stateASrc.replace("#kind #BODY", "#kind #DEFS");
        PsiFile stateBFile = myFixture.configureByText("poison_inlay_matrix_suspended.stvn", stateBSrc);
        assertTrue(
            "State B fixture must suspend Pass 3 payload lowering",
            StvnCompiledDocumentCache.getSnapshot(stateBFile).document().flatMap(StvnDocument::payload).isEmpty()
        );
        List<Value> stateBSlots = extractOuterBodyTupleSlots(stateBFile);
        for (int i = 0; i < 5; i++) {
            assertEquals("State B must match State A on slot " + i,
                expectedFull[i], StvnTypeResolver.resolveValueType(stateBSlots.get(i)));
        }
        for (int errIdx = 5; errIdx < 8; errIdx++) {
            assertNull("State B error slot " + errIdx + " must suppress inlay badge",
                StvnTypeResolver.resolveValueType(stateBSlots.get(errIdx)));
        }
    }

    private static int[] spanOf(String source, String searchNeedle, int offsetInNeedle) {
        int idx = source.indexOf(searchNeedle);
        assertTrue("Needle not found: " + searchNeedle, idx >= 0);
        return new int[]{idx + offsetInNeedle, idx + searchNeedle.length()};
    }

    private static int[] spanOf(String source, String contextSnippet, String keywordPrefix) {
        int idx = source.indexOf(contextSnippet);
        assertTrue("Context snippet not found: " + contextSnippet, idx >= 0);
        return new int[]{idx, idx + keywordPrefix.length()};
    }

    private @Nullable String renderHoverAt(StvnDocumentationProvider provider, PsiFile file, int offset) {
        PsiElement leaf = file.findElementAt(offset);
        assertNotNull(leaf);
        PsiElement target = provider.getCustomDocumentationElement(myFixture.getEditor(), file, leaf, offset);
        if (target == null) {
            return null;
        }
        return provider.generateDoc(target, leaf);
    }

    private List<HighlightInfo> collectDiagnosticHighlights(List<HighlightInfo> all) {
        return all.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
                || h.getSeverity() == HighlightSeverity.WARNING
                || h.getSeverity() == HighlightSeverity.WEAK_WARNING)
            .filter(h -> h.getDescription() != null && !h.getDescription().isBlank())
            .toList();
    }

    private void assertNoDuplicateDiagnosticRanges(String label, List<HighlightInfo> highlights) {
        Set<String> seen = new HashSet<>();
        for (HighlightInfo h : highlights) {
            String desc = h.getDescription() != null ? h.getDescription() : "";
            Matcher m = COMPLETE_SUFFIX_PATTERN.matcher(desc);
            assertTrue(label + " highlight missing complete suffix: " + desc, m.find());
            String key = h.getStartOffset() + ":" + h.getEndOffset() + ":" + m.group(1);
            assertTrue(label + " stacked duplicate highlight [" + key + "] -> " + desc, seen.add(key));
        }
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
