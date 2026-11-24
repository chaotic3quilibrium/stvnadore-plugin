package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetaKindValue;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end IntelliJ Platform verification suite for Gauntlet v10 Stage 1
 * ({@code G10S1-PLUGIN-01} through {@code G10S1-PLUGIN-05}) across {@code Pass A}
 * ({@code 0} local inspections) and {@code Pass B} ({@code 26} local inspections).
 */
@NullMarked
public final class StvnGauntletV10Stage1PluginMetaDisjointParityTest extends BasePlatformTestCase {

    private static final Path SHARED_FIXTURES_METADATA_DIR =
        Paths.get("src", "test", "resources", "shared-fixtures", "syntax", "invalid", "metadata");

    private static final Pattern COMPLETE_SUFFIX_PATTERN =
        Pattern.compile("\\[stvn-(?:core|plugin):([A-Z0-9]+-[A-Z0-9_]+)\\]");

    private static final Pattern CONTRACT_ENTRY_PATTERN = Pattern.compile(
        "\\(\\s*#([A-Z0-9]+)\\s+#([A-Z0-9_]+)\\s+(#[A-Z]+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)"
    );

    private record ContractExpectation(
        String severityTag,
        String message,
        String canonicalCode,
        String symbolicId,
        int startOffset,
        int endOffset
    ) {}

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        LocalInspectionTool[] initTools = createAll26Inspections();
        myFixture.enableInspections(initTools);
        myFixture.disableInspections(initTools);
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

    private void assertZeroNoisyInformationHighlights(String label, List<HighlightInfo> allHighlights) {
        List<HighlightInfo> noisyInfo = allHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.INFORMATION
                && h.getDescription() != null
                && !h.getDescription().isEmpty())
            .toList();
        assertTrue(label + " must emit zero INFORMATION highlights with non-empty description: " + noisyInfo,
            noisyInfo.isEmpty());
    }

    private void assertSingleIntentionWithPreview(int offset, String label) {
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        List<IntentionAction> matches = myFixture.getAvailableIntentions().stream()
            .filter(a -> label.equals(a.getText()))
            .toList();
        assertEquals(
            "Expected strictly 1 '" + label + "' intention at offset " + offset + ", found: "
                + myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList(),
            1,
            matches.size()
        );
        IntentionAction action = matches.get(0);
        String preview = myFixture.getIntentionPreviewText(action);
        if (preview == null) {
            IntentionPreviewInfo previewInfo = action.generatePreview(getProject(), myFixture.getEditor(), myFixture.getFile());
            assertNotNull("Expected non-null IntentionPreviewInfo for '" + label + "' at offset " + offset, previewInfo);
            assertNotSame(
                "Expected non-EMPTY IntentionPreviewInfo for '" + label + "' at offset " + offset,
                IntentionPreviewInfo.EMPTY,
                previewInfo
            );
        } else {
            assertFalse("Expected non-empty DIFF preview for '" + label + "'", preview.isBlank());
        }
    }

    /**
     * Verifies disjoint keyword-pinned {@code E008} vs. value-pinned {@code W001}/{@code W002},
     * zero whitespace squiggly bleed, and silent {@code ""} {@code INFORMATION} reorder anchors
     * across Pass A and Pass B.
     */
    public void testSmokeReproductionDisjointSpansZeroWhitespaceBleedAndSilentInformationAnchors() {
        String smokeDoc = """
            {
              :meta {
                #domain "cryo_biobank"
                #name "vial_custody_manifest"
                #kind #BODY_INCLUDE
              }
              :defs {
                :DisorderedPort { #minIncl 1 #size 16 } :Int
              }
              :type :DisorderedPort
              :body 42
            }
            """;

        int domainKwStart = smokeDoc.indexOf("#domain");
        int domainKwEnd = domainKwStart + "#domain".length();
        int domainLitStart = smokeDoc.indexOf("\"cryo_biobank\"");
        int domainLitEnd = domainLitStart + "\"cryo_biobank\"".length();

        int nameKwStart = smokeDoc.indexOf("#name");
        int nameKwEnd = nameKwStart + "#name".length();
        int nameLitStart = smokeDoc.indexOf("\"vial_custody_manifest\"");
        int nameLitEnd = nameLitStart + "\"vial_custody_manifest\"".length();

        int kindFacetStart = smokeDoc.indexOf("#kind #BODY_INCLUDE");
        int kindFacetEnd = kindFacetStart + "#kind #BODY_INCLUDE".length();

        for (boolean enable26 : new boolean[]{false, true}) {
            String mode = enable26 ? "Pass B (26 inspections)" : "Pass A (0 inspections)";
            String expectedPrefix = enable26 ? "[stvn-plugin:" : "[stvn-core:";
            LocalInspectionTool[] all26 = createAll26Inspections();
            if (enable26) {
                myFixture.enableInspections(all26);
            }
            try {
                myFixture.configureByText("gauntlet_v10_stage1_envelope_meta_and_section_order.stvn", smokeDoc);
                List<HighlightInfo> allHighlights = myFixture.doHighlighting();
                assertZeroNoisyInformationHighlights(mode, allHighlights);

                List<HighlightInfo> diags = collectDiagnosticHighlights(allHighlights);
                assertNoDuplicateDiagnosticRanges(mode, diags);

                // 1. #domain keyword, indentation spaces, and separating spaces have ZERO squigglies
                int[] cleanOffsets = {
                    domainKwStart - 2,
                    domainKwStart,
                    domainKwEnd,
                    nameKwStart - 1,
                    nameKwEnd
                };
                for (int cleanOff : cleanOffsets) {
                    boolean anyDiagOverlap = diags.stream().anyMatch(h ->
                        h.getStartOffset() <= cleanOff && cleanOff < h.getEndOffset()
                    );
                    assertFalse(mode + " must have zero squiggly overlap at offset " + cleanOff + ": " + diags, anyDiagOverlap);
                }

                // 2. "cryo_biobank" has strictly W002 at [domainLitStart, domainLitEnd)
                List<HighlightInfo> domainLitDiags = diags.stream()
                    .filter(h -> h.getStartOffset() < domainLitEnd && h.getEndOffset() > domainLitStart)
                    .toList();
                assertEquals(mode + " expected 1 highlight on '\"cryo_biobank\"': " + domainLitDiags, 1, domainLitDiags.size());
                HighlightInfo w002 = domainLitDiags.get(0);
                assertEquals(HighlightSeverity.WARNING, w002.getSeverity());
                assertEquals(domainLitStart, w002.getStartOffset());
                assertEquals(domainLitEnd, w002.getEndOffset());
                assertTrue(w002.getDescription().contains(expectedPrefix + "W002-WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME]"));

                // 3. #name has strictly E008 at [nameKwStart, nameKwEnd)
                List<HighlightInfo> nameKwDiags = diags.stream()
                    .filter(h -> h.getStartOffset() < nameKwEnd && h.getEndOffset() > nameKwStart)
                    .toList();
                assertEquals(mode + " expected 1 highlight on '#name': " + nameKwDiags, 1, nameKwDiags.size());
                HighlightInfo e008Name = nameKwDiags.get(0);
                assertEquals(HighlightSeverity.ERROR, e008Name.getSeverity());
                assertEquals(nameKwStart, e008Name.getStartOffset());
                assertEquals(nameKwEnd, e008Name.getEndOffset());
                assertTrue(e008Name.getDescription().contains(expectedPrefix + "E008-ERR_FACET_ORDER_VIOLATION]"));

                // 4. "vial_custody_manifest" has strictly W001 at [nameLitStart, nameLitEnd)
                List<HighlightInfo> nameLitDiags = diags.stream()
                    .filter(h -> h.getStartOffset() < nameLitEnd && h.getEndOffset() > nameLitStart)
                    .toList();
                assertEquals(mode + " expected 1 highlight on '\"vial_custody_manifest\"': " + nameLitDiags, 1, nameLitDiags.size());
                HighlightInfo w001 = nameLitDiags.get(0);
                assertEquals(HighlightSeverity.WARNING, w001.getSeverity());
                assertEquals(nameLitStart, w001.getStartOffset());
                assertEquals(nameLitEnd, w001.getEndOffset());
                assertTrue(w001.getDescription().contains(expectedPrefix + "W001-WARN_DOCUMENT_NAME_MISMATCH]"));

                // 5. #kind #BODY_INCLUDE has E031 at [kindFacetStart, kindFacetEnd)
                assertTrue(
                    mode + " expected E031 on '#kind #BODY_INCLUDE' at [" + kindFacetStart + ", " + kindFacetEnd + "), actual=" + diags,
                    diags.stream().anyMatch(h ->
                        h.getSeverity() == HighlightSeverity.ERROR
                            && h.getStartOffset() == kindFacetStart
                            && h.getEndOffset() == kindFacetEnd
                            && h.getDescription().contains(expectedPrefix + "E031-ERR_DOCUMENT_KIND_MISMATCH]")
                    )
                );
            } finally {
                if (enable26) {
                    myFixture.disableInspections(all26);
                }
            }
        }
    }

    /**
     * Verifies 1:1 Pass A and Pass B {@code Alt+Enter} quick-fix parity and {@code count == 1}
     * deduplication across every {@code :meta} token.
     */
    public void testReorderAndStemQuickFixParityAndDeduplicationAcrossEveryMetaToken() {
        String smokeDoc = """
            {
              :meta {
                #domain "cryo_biobank"
                #name "vial_custody_manifest"
                #kind #BODY_INCLUDE
              }
              :type :Int
              :body 42
            }
            """;

        String reorderLabel = "Reorder ':meta' entries to canonical order (#name, #domain, #kind)";
        String matchMetaLabel = "Rename file to 'vial_custody_manifest.cryo_biobank.stvn_i' (match :meta)";
        String alignNameLabel = "Align '#name' with filename stem slot 0 ('gauntlet_v10_stage1_envelope_meta_and_section_order')";
        String renameNameLabel = "Rename file to 'vial_custody_manifest.stvn'";
        String includeDomainLabel = "Rename file to 'gauntlet_v10_stage1_envelope_meta_and_section_order.cryo_biobank.stvn'";
        String removeDomainLabel = "Remove '#domain' facet";

        int metaKwOff = smokeDoc.indexOf(":meta") + 2;
        int lbraceOff = smokeDoc.indexOf("{", metaKwOff);
        int domainKwOff = smokeDoc.indexOf("#domain") + 2;
        int domainLitOff = smokeDoc.indexOf("\"cryo_biobank\"") + 3;
        int nameKwOff = smokeDoc.indexOf("#name") + 2;
        int nameLitOff = smokeDoc.indexOf("\"vial_custody_manifest\"") + 3;
        int kindKwOff = smokeDoc.indexOf("#kind") + 2;
        int kindValOff = smokeDoc.indexOf("#BODY_INCLUDE") + 3;
        int rbraceOff = smokeDoc.indexOf("}", kindValOff);

        for (boolean enable26 : new boolean[]{false, true}) {
            LocalInspectionTool[] all26 = createAll26Inspections();
            if (enable26) {
                myFixture.enableInspections(all26);
            }
            try {
                myFixture.configureByText("gauntlet_v10_stage1_envelope_meta_and_section_order.stvn", smokeDoc);
                myFixture.doHighlighting();

                for (int off : new int[]{
                    metaKwOff, lbraceOff, domainKwOff, domainLitOff, nameKwOff, nameLitOff, kindKwOff, kindValOff, rbraceOff
                }) {
                    assertSingleIntentionWithPreview(off, reorderLabel);
                }

                // Out-of-order #name (carrying E008) exposes strictly Reorder ':meta' entries... without stem hijack
                myFixture.getEditor().getCaretModel().moveToOffset(nameKwOff);
                List<String> nameKwLabels = myFixture.getAvailableIntentions().stream()
                    .map(IntentionAction::getText)
                    .toList();
                assertTrue("Out-of-order #name keyword must not be hijacked by W001 stem fixes: " + nameKwLabels,
                    nameKwLabels.stream().noneMatch(t -> t.contains("Align '#name'") || t.contains("Rename file to")));

                // Disjoint "vial_custody_manifest" (carrying W001) exposes stem fixes + silent reorder (count == 1 each)
                assertSingleIntentionWithPreview(nameLitOff, matchMetaLabel);
                assertSingleIntentionWithPreview(nameLitOff, alignNameLabel);
                assertSingleIntentionWithPreview(nameLitOff, renameNameLabel);
                assertSingleIntentionWithPreview(nameLitOff, reorderLabel);

                // In-order #domain and "cryo_biobank" (carrying W002) both expose W002 stem fixes + reorder (count == 1 each)
                for (int domOff : new int[]{domainKwOff, domainLitOff}) {
                    assertSingleIntentionWithPreview(domOff, matchMetaLabel);
                    assertSingleIntentionWithPreview(domOff, includeDomainLabel);
                    assertSingleIntentionWithPreview(domOff, removeDomainLabel);
                    assertSingleIntentionWithPreview(domOff, reorderLabel);
                }
            } finally {
                if (enable26) {
                    myFixture.disableInspections(all26);
                }
            }
        }
    }

    /**
     * Verifies zero-whitespace adjacency ({@code kw.endOffset == val.startOffset}) in {@code :meta}
     * and unchanged full {@code MetadataEntry} spans on schema {@code MetadataMap} blocks.
     */
    public void testZeroWhitespaceAdjacencyAndSchemaMetadataMapBoundaryPreservation() {
        String compact = "{:meta{#kind#BODY#domain\"other_domain\"#name\"other_name\"}:defs{:Bad{#minIncl 1 #size 16}:Int}:type :Bad :body 1}";

        int domKwStart = compact.indexOf("#domain");
        int domKwEnd = domKwStart + "#domain".length();
        int domLitStart = compact.indexOf("\"other_domain\"");
        int domLitEnd = domLitStart + "\"other_domain\"".length();

        int nameKwStart = compact.indexOf("#name");
        int nameKwEnd = nameKwStart + "#name".length();
        int nameLitStart = compact.indexOf("\"other_name\"");
        int nameLitEnd = nameLitStart + "\"other_name\"".length();

        int sizeEntryStart = compact.indexOf("#size 16");
        int sizeEntryEnd = sizeEntryStart + "#size 16".length();

        assertEquals("Zero-whitespace #domain must touch string literal boundary", domKwEnd, domLitStart);
        assertEquals("Zero-whitespace #name must touch string literal boundary", nameKwEnd, nameLitStart);

        String metaReorderLabel = "Reorder ':meta' entries to canonical order (#name, #domain, #kind)";
        for (boolean enable26 : new boolean[]{false, true}) {
            LocalInspectionTool[] all26 = createAll26Inspections();
            if (enable26) {
                myFixture.enableInspections(all26);
            }
            try {
                myFixture.configureByText("actual_name.actual_domain.stvn", compact);
                List<HighlightInfo> diags = collectDiagnosticHighlights(myFixture.doHighlighting());
                assertNoDuplicateDiagnosticRanges(enable26 ? "Compact Pass B" : "Compact Pass A", diags);

                assertTrue(diags.stream().anyMatch(h ->
                    h.getSeverity() == HighlightSeverity.ERROR
                        && h.getStartOffset() == domKwStart && h.getEndOffset() == domKwEnd
                        && h.getDescription().contains("E008-ERR_FACET_ORDER_VIOLATION")));
                assertTrue(diags.stream().anyMatch(h ->
                    h.getSeverity() == HighlightSeverity.WARNING
                        && h.getStartOffset() == domLitStart && h.getEndOffset() == domLitEnd
                        && h.getDescription().contains("W001-WARN_DOCUMENT_NAME_MISMATCH")));
                assertTrue(diags.stream().anyMatch(h ->
                    h.getSeverity() == HighlightSeverity.ERROR
                        && h.getStartOffset() == nameKwStart && h.getEndOffset() == nameKwEnd
                        && h.getDescription().contains("E008-ERR_FACET_ORDER_VIOLATION")));
                assertTrue(diags.stream().anyMatch(h ->
                    h.getSeverity() == HighlightSeverity.WARNING
                        && h.getStartOffset() == nameLitStart && h.getEndOffset() == nameLitEnd
                        && h.getDescription().contains("W001-WARN_DOCUMENT_NAME_MISMATCH")));

                // Schema MetadataMap 5-tier spans pin uniformly to the facet keyword token in both Pass A and Pass B
                int expectedSizeEnd = sizeEntryStart + "#size".length();
                assertTrue("Expected #size 16 order error in " + diags, diags.stream().anyMatch(h ->
                    h.getSeverity() == HighlightSeverity.ERROR
                        && h.getStartOffset() == sizeEntryStart && h.getEndOffset() == expectedSizeEnd
                        && h.getDescription().contains("E008-ERR_FACET_ORDER_VIOLATION")));

                assertSingleIntentionWithPreview(domLitStart, metaReorderLabel);
                assertSingleIntentionWithPreview(nameLitStart, metaReorderLabel);
            } finally {
                if (enable26) {
                    myFixture.disableInspections(all26);
                }
            }
        }
    }

    /**
     * Verifies {@code MetaUnknownFacet} duplicate-vs-order parity on {@code #name 42} followed by
     * {@code #name "wrong_stem"}, bare {@code #name} boundary isolation, and in-flight {@code :meta}
     * code completion.
     */
    public void testMetaUnknownFacetDuplicateVsOrderAndCompletionParityWithCore() {
        String malformedThenDuplicate = """
            {
              :meta {
                #kind #BODY
                #name 42
                #name "wrong_stem"
              }
              :type :Int
              :body 7
            }
            """;

        for (boolean enable26 : new boolean[]{false, true}) {
            LocalInspectionTool[] all26 = createAll26Inspections();
            if (enable26) {
                myFixture.enableInspections(all26);
            }
            try {
                PsiFile psiFile = myFixture.configureByText("actual_stem.stvn", malformedThenDuplicate);
                MetaBlock metaBlock = PsiTreeUtil.findChildOfType(psiFile, MetaBlock.class);
                assertNotNull(metaBlock);
                assertEquals("MetaBlock must retain all 3 MetaElement children without aborting",
                    3, metaBlock.getMetaElementList().size());

                List<HighlightInfo> diags = collectDiagnosticHighlights(myFixture.doHighlighting());
                assertNoDuplicateDiagnosticRanges(enable26 ? "UnknownFacet Pass B" : "UnknownFacet Pass A", diags);

                assertTrue(diags.stream().anyMatch(h ->
                    "#name 42".equals(h.getText())
                        && h.getDescription().contains("E008-ERR_INVALID_METADATA_FACET")));
                assertTrue(diags.stream().anyMatch(h ->
                    "#name \"wrong_stem\"".equals(h.getText())
                        && h.getDescription().contains("E008-ERR_DUPLICATE_METADATA_FACET")));
                assertTrue("Preceding #name 42 must suppress secondary ERR_FACET_ORDER_VIOLATION and W001: " + diags,
                    diags.stream().noneMatch(h ->
                        h.getDescription().contains("ERR_FACET_ORDER_VIOLATION")
                            || h.getDescription().contains("WARN_DOCUMENT_NAME_MISMATCH")));
            } finally {
                if (enable26) {
                    myFixture.disableInspections(all26);
                }
            }
        }

        // Bare in-flight #name before #domain must not swallow #domain
        PsiFile bareNameFile = myFixture.configureByText("app_stem.app_dom.stvn", """
            {
              :meta {
                #name
                #domain "app_dom"
                #kind #BODY
              }
              :type :Int
              :body 1
            }
            """);
        MetaBlock bareBlock = PsiTreeUtil.findChildOfType(bareNameFile, MetaBlock.class);
        assertNotNull(bareBlock);
        assertEquals(3, bareBlock.getMetaElementList().size());
        assertNotNull(bareBlock.getMetaElementList().get(1).getMetaDomainFacet());

        // Verify in-flight :meta code completion across #kind, #domain, #name, and #<caret>
        myFixture.configureByText("comp_target.telemetry.stvn_i", """
            {
              :meta {
                #kind #<caret>
              }
              :type :Enum [ #ALPHA #BETA ]
              :body #ALPHA
            }
            """);
        LookupElement[] kindHashItems = myFixture.completeBasic();
        assertNotNull(kindHashItems);
        List<String> kindHashStrings = Arrays.stream(kindHashItems).map(LookupElement::getLookupString).toList();
        assertTrue(kindHashStrings.contains("#BODY_INCLUDE"));
        assertTrue(kindHashStrings.contains("#BODY"));
        assertFalse("Body enum variants must not leak into :meta #kind completion: " + kindHashStrings,
            kindHashStrings.contains("#ALPHA"));

        myFixture.configureByText("comp_target.telemetry.stvn_i", """
            {
              :meta {
                #domain <caret>
                #kind #BODY_INCLUDE
              }
            }
            """);
        LookupElement[] domainBareItems = myFixture.completeBasic();
        assertNotNull(domainBareItems);
        List<String> domainBareStrings = Arrays.stream(domainBareItems).map(LookupElement::getLookupString).toList();
        assertTrue(domainBareStrings.contains("telemetry"));

        myFixture.configureByText("comp_target.telemetry.stvn_i", """
            {
              :meta {
                #name <caret>
                #kind #BODY_INCLUDE
              }
            }
            """);
        LookupElement[] nameBareItems = myFixture.completeBasic();
        assertNotNull(nameBareItems);
        List<String> nameBareStrings = Arrays.stream(nameBareItems).map(LookupElement::getLookupString).toList();
        assertTrue(nameBareStrings.contains("\"comp_target\""));
    }

    /**
     * Verifies whitespace hover suppression, disjoint {@code KW_META_*} vs. {@code StringLiteral}
     * hover targets, Prelude context isolation, and dedicated {@code :meta} identity value cards.
     */
    public void testDisjointMetaHoverTargetsWhitespaceSuppressionAndPreludeIsolation() {
        String docText = """
            {
              :meta {
                #domain "cryo_biobank"
                #name "vial_custody_manifest"
                #kind #BODY_INCLUDE
              }
              :defs {
                :Port { #unsigned #size 16 } :Int
              }
              :type :Port
              :body 80
            }
            """;

        PsiFile file = myFixture.configureByText("gauntlet_v10_stage1_envelope_meta_and_section_order.stvn", docText);
        StvnDocumentationProvider provider = new StvnDocumentationProvider();

        // 1. Leading indentation and separating spaces inside :meta and MetadataMap return null
        int indentBeforeName = docText.indexOf("#name") - 1;
        int spaceInsideName = docText.indexOf("#name") + "#name".length();
        int spaceInsideDomain = docText.indexOf("#domain") + "#domain".length();
        int spaceInsideMetaMap = docText.indexOf("#unsigned") - 1;
        for (int wsOff : new int[]{indentBeforeName, spaceInsideName, spaceInsideDomain, spaceInsideMetaMap}) {
            PsiElement wsLeaf = file.findElementAt(wsOff);
            assertNotNull(wsLeaf);
            assertNull("Hover target on whitespace at offset " + wsOff + " must be null",
                provider.getCustomDocumentationElement(myFixture.getEditor(), file, wsLeaf, wsOff));
            assertNull("generateDoc on whitespace at offset " + wsOff + " must be null",
                provider.generateDoc(wsLeaf, wsLeaf));
        }

        // 2. #name, #domain, and #kind return exact keyword leaf [kw.start, kw.end) and Root Metadata Facet card
        int nameKwOff = docText.indexOf("#name") + 1;
        PsiElement nameLeaf = file.findElementAt(nameKwOff);
        assertNotNull(nameLeaf);
        PsiElement nameTarget = provider.getCustomDocumentationElement(myFixture.getEditor(), file, nameLeaf, nameKwOff);
        assertNotNull(nameTarget);
        assertEquals(StvnTypes.KW_META_NAME, nameTarget.getNode().getElementType());
        assertEquals("#name", nameTarget.getText());
        String nameKwDoc = provider.generateDoc(nameTarget, nameLeaf);
        assertNotNull(nameKwDoc);
        assertTrue(nameKwDoc.contains("<b>Root Metadata Facet:</b> <code>#name</code>"));
        assertTrue(nameKwDoc.contains(":org/stvnadore/prelude/meta/StvnPosixId64"));

        int domainKwOff = docText.indexOf("#domain") + 1;
        PsiElement domainLeaf = file.findElementAt(domainKwOff);
        assertNotNull(domainLeaf);
        PsiElement domainTarget = provider.getCustomDocumentationElement(myFixture.getEditor(), file, domainLeaf, domainKwOff);
        assertNotNull(domainTarget);
        assertEquals(StvnTypes.KW_META_DOMAIN, domainTarget.getNode().getElementType());
        assertEquals("#domain", domainTarget.getText());
        String domainKwDoc = provider.generateDoc(domainTarget, domainLeaf);
        assertNotNull(domainKwDoc);
        assertTrue(domainKwDoc.contains("<b>Root Metadata Facet:</b> <code>#domain</code>"));

        int kindKwOff = docText.indexOf("#kind") + 1;
        PsiElement kindLeaf = file.findElementAt(kindKwOff);
        assertNotNull(kindLeaf);
        PsiElement kindTarget = provider.getCustomDocumentationElement(myFixture.getEditor(), file, kindLeaf, kindKwOff);
        assertNotNull(kindTarget);
        assertEquals(StvnTypes.KW_META_KIND, kindTarget.getNode().getElementType());
        assertEquals("#kind", kindTarget.getText());

        int kindValOff = docText.indexOf("#BODY_INCLUDE") + 2;
        PsiElement kindValLeaf = file.findElementAt(kindValOff);
        assertNotNull(kindValLeaf);
        PsiElement kindValTarget = provider.getCustomDocumentationElement(myFixture.getEditor(), file, kindValLeaf, kindValOff);
        assertTrue(kindValTarget instanceof MetaKindValue);

        // 3. "vial_custody_manifest" and "cryo_biobank" return disjoint StringLiteral and dedicated Identity Value cards
        int nameLitOff = docText.indexOf("\"vial_custody_manifest\"") + 3;
        PsiElement nameLitLeaf = file.findElementAt(nameLitOff);
        assertNotNull(nameLitLeaf);
        PsiElement nameLitTarget = provider.getCustomDocumentationElement(myFixture.getEditor(), file, nameLitLeaf, nameLitOff);
        assertTrue("Expected StringLiteral hover target on #name value", nameLitTarget instanceof StringLiteral);
        assertEquals("\"vial_custody_manifest\"", nameLitTarget.getText());
        String nameLitDoc = provider.generateDoc(nameLitTarget, nameLitLeaf);
        assertNotNull(nameLitDoc);
        assertTrue("Unexpected nameLitDoc: " + nameLitDoc,
            nameLitDoc.contains("<b>Document Name Value:</b> <code>\"vial_custody_manifest\"</code>"));
        assertTrue(nameLitDoc.contains("<b>Character Length:</b> 21 / 64"));
        assertTrue(nameLitDoc.contains("<b>Filename Stem Slot 0:</b> Expected"));
        assertFalse("Identity value card must not render :defs Configurable Options (Unset)",
            nameLitDoc.contains("Configurable Options"));

        int domainLitOff = docText.indexOf("\"cryo_biobank\"") + 3;
        PsiElement domainLitLeaf = file.findElementAt(domainLitOff);
        assertNotNull(domainLitLeaf);
        PsiElement domainLitTarget = provider.getCustomDocumentationElement(myFixture.getEditor(), file, domainLitLeaf, domainLitOff);
        assertTrue("Expected StringLiteral hover target on #domain value", domainLitTarget instanceof StringLiteral);
        String domainLitDoc = provider.generateDoc(domainLitTarget, domainLitLeaf);
        assertNotNull(domainLitDoc);
        assertTrue("Unexpected domainLitDoc: " + domainLitDoc,
            domainLitDoc.contains("<b>Document Domain Value:</b> <code>\"cryo_biobank\"</code>"));
        assertTrue(domainLitDoc.contains("Omitted in 1-stem filename"));

        // 4. Verify reserved E038, invalid POSIX E008, matching stem, and duplicate states on Identity Value cards
        String edgeStates = """
            {
              :meta {
                #name "clean_stem"
                #name "bad/name"
                #domain "stvn_reserved_trap"
                #kind #BODY
              }
              :type :Int
              :body 1
            }
            """;
        PsiFile edgeFile = myFixture.configureByText("clean_stem.clean_dom.stvn", edgeStates);
        int matchOff = edgeStates.indexOf("\"clean_stem\"") + 2;
        PsiElement matchLeaf = edgeFile.findElementAt(matchOff);
        assertNotNull(matchLeaf);
        String matchDoc = provider.generateDoc(
            provider.getCustomDocumentationElement(myFixture.getEditor(), edgeFile, matchLeaf, matchOff),
            matchLeaf
        );
        assertNotNull(matchDoc);
        assertTrue(matchDoc.contains("Matches <code>clean_stem</code>"));

        int dupOff = edgeStates.indexOf("\"bad/name\"") + 2;
        PsiElement dupLeaf = edgeFile.findElementAt(dupOff);
        assertNotNull(dupLeaf);
        String dupDoc = provider.generateDoc(
            provider.getCustomDocumentationElement(myFixture.getEditor(), edgeFile, dupLeaf, dupOff),
            dupLeaf
        );
        assertNotNull(dupDoc);
        assertTrue(dupDoc.contains("Duplicate <code>#name</code> facet"));

        int resOff = edgeStates.indexOf("\"stvn_reserved_trap\"") + 2;
        PsiElement resLeaf = edgeFile.findElementAt(resOff);
        assertNotNull(resLeaf);
        String resDoc = provider.generateDoc(
            provider.getCustomDocumentationElement(myFixture.getEditor(), edgeFile, resLeaf, resOff),
            resLeaf
        );
        assertNotNull(resDoc);
        assertTrue(resDoc.contains("Prohibits reserved <code>stvn_</code> system domain prefix"));
    }

    /**
     * Verifies exact 1:1 Pass A and Pass B coordinate, code, symbolic ID, severity, and message
     * parity across all 5 Stage 1 mirrored {@code shared-fixtures} contract pairs.
     *
     * @throws IOException if mirrored shared-fixture files cannot be read
     */
    public void testMirroredStage1SharedFixturesExactPassAPassBContractParity() throws IOException {
        String[][] fixturePairs = {
            {
                "duplicate_meta_and_body_fault_isolation.core_test.stvn_i",
                "duplicate_meta_and_body_fault_isolation.contract.stvn_i"
            },
            {
                "gauntlet_v4_stage1_meta_multi_error_and_body.core_test.stvn_i",
                "gauntlet_v4_stage1_meta_multi_error_and_body.contract.stvn_i"
            },
            {
                "meta_order_domain_before_name_smoke.cryo_biobank.stvn_i",
                "meta_order_domain_before_name_smoke.contract.stvn_i"
            },
            {
                "meta_order_keyword_pinning_disjoint_w001.cryo_biobank.stvn",
                "meta_order_keyword_pinning_disjoint_w001.contract.stvn_i"
            },
            {
                "meta_order_keyword_pinning_disjoint_w002_1stem.stvn",
                "meta_order_keyword_pinning_disjoint_w002_1stem.contract.stvn_i"
            }
        };

        for (String[] pair : fixturePairs) {
            String sourceName = pair[0];
            String contractName = pair[1];
            String sourceText = Files.readString(SHARED_FIXTURES_METADATA_DIR.resolve(sourceName), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
            String contractText = Files.readString(SHARED_FIXTURES_METADATA_DIR.resolve(contractName), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
            List<ContractExpectation> expectations = parseContractExpectations(contractText);
            assertFalse("Expected non-empty contract in " + contractName, expectations.isEmpty());

            for (boolean enable26 : new boolean[]{false, true}) {
                String mode = (enable26 ? "Pass B " : "Pass A ") + sourceName;
                String expectedOrigin = enable26 ? "[stvn-plugin:" : "[stvn-core:";
                LocalInspectionTool[] all26 = createAll26Inspections();
                if (enable26) {
                    myFixture.enableInspections(all26);
                }
                try {
                    myFixture.configureByText(sourceName, sourceText);
                    List<HighlightInfo> all = myFixture.doHighlighting();
                    assertZeroNoisyInformationHighlights(mode, all);

                    List<HighlightInfo> diags = collectDiagnosticHighlights(all);
                    assertNoDuplicateDiagnosticRanges(mode, diags);
                    assertEquals(mode + " diagnostic count mismatch: " + diags, expectations.size(), diags.size());

                    for (ContractExpectation exp : expectations) {
                        boolean isWarn = "#WARNING".equals(exp.severityTag());
                        HighlightSeverity expectedSeverity = isWarn ? HighlightSeverity.WARNING : HighlightSeverity.ERROR;
                        String suffix = "[" + (enable26 && isLocallyGovernedInStage1(exp.symbolicId()) ? "stvn-plugin:" : "stvn-core:")
                            + exp.canonicalCode() + "-" + exp.symbolicId() + "]";
                        List<HighlightInfo> matched = diags.stream()
                            .filter(h -> h.getSeverity() == expectedSeverity
                                && h.getStartOffset() == exp.startOffset()
                                && h.getEndOffset() == exp.endOffset()
                                && h.getDescription() != null
                                && h.getDescription().contains(exp.message())
                                && h.getDescription().endsWith(suffix))
                            .toList();
                        assertEquals(
                            mode + " expected exact match at [" + exp.startOffset() + ", " + exp.endOffset() + ") for "
                                + suffix + " (origin hint " + expectedOrigin + "), actual=" + diags,
                            1,
                            matched.size()
                        );
                    }
                } finally {
                    if (enable26) {
                        myFixture.disableInspections(all26);
                    }
                }
            }
        }
    }

    private static boolean isLocallyGovernedInStage1(String symbolicId) {
        return !"ERR_META_POSITION_INVALID".equals(symbolicId)
            && !"ERR_CONSTRAINT_VIOLATION".equals(symbolicId);
    }

    private static List<ContractExpectation> parseContractExpectations(String contractText) {
        int bodyIdx = contractText.indexOf(":body");
        String bodySlice = bodyIdx >= 0 ? contractText.substring(bodyIdx) : contractText;
        List<ContractExpectation> list = new ArrayList<>();
        Matcher m = CONTRACT_ENTRY_PATTERN.matcher(bodySlice);
        while (m.find()) {
            String msg = m.group(8)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
            list.add(new ContractExpectation(
                m.group(3),
                msg,
                m.group(1),
                m.group(2),
                Integer.parseInt(m.group(6)),
                Integer.parseInt(m.group(7))
            ));
        }
        return list;
    }
}
