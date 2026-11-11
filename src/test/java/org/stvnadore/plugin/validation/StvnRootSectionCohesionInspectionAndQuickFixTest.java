package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.psi.TypeDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * End-to-end IntelliJ Platform regression suite verifying root-section cohesion, duplicate-section
 * cascade suppression, non-poisoning out-of-order section diagnostics, {@code Ctrl+Click} and hover
 * resolution above {@code :defs}, intention previews, quick-fixes, and four-mode {@code Ctrl+Alt+L}
 * auto-reordering across all 26 registered STVN local inspections.
 */
@NullMarked
public final class StvnRootSectionCohesionInspectionAndQuickFixTest extends BasePlatformTestCase {

    private static final Path SHARED_FIXTURES_ROOT = Paths.get("src", "test", "resources", "shared-fixtures");
    private static final Path STRUCTURE_INVALID_DIR = SHARED_FIXTURES_ROOT.resolve(Paths.get("syntax", "invalid", "structure"));
    private static final Path METADATA_INVALID_DIR = SHARED_FIXTURES_ROOT.resolve(Paths.get("syntax", "invalid", "metadata"));
    private static final Path FORMATTING_DIR = SHARED_FIXTURES_ROOT.resolve("formatting");

    private static final Pattern DIAGNOSTIC_TAG_PATTERN =
        Pattern.compile("\\[(stvn-core|stvn-plugin):([A-Z0-9_-]+)]");

    private static final Pattern CONTRACT_ENTRY_PATTERN = Pattern.compile(
        "\\(\\s*#(E\\d+|W\\d+)\\s+#([A-Z0-9_]+)\\s+#(ERROR|WARNING)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+\"([^\"]+)\"\\s*\\)"
    );

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Enable all 26 registered STVN local inspections simultaneously
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
            new StvnFloatPrecisionLossInspection(),
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
            new StvnSuspiciousEpochMagnitudeInspection()
        );
    }

    /**
     * Scenario A ({@code root_fluster.domain.stvn}): verifies strictly 16 diagnostics matching
     * {@code root_fluster.contract.stvn_i} 1:1 with all 26 local inspections enabled, zero duplicate
     * annotations, zero secondary inner cascades or {@code PW002} warnings on lines 6-24, and
     * {@code "Remove duplicate '<kw>' section"} attached on every secondary root section.
     */
    public void testScenarioARootFlusterExactSixteenDiagnosticsZeroDuplicatesAndZeroSecondaryCascades() throws IOException {
        String source = Files.readString(STRUCTURE_INVALID_DIR.resolve("root_fluster.domain.stvn"), StandardCharsets.UTF_8);
        String contractText = Files.readString(STRUCTURE_INVALID_DIR.resolve("root_fluster.contract.stvn_i"), StandardCharsets.UTF_8);
        List<ContractDiagnostic> expectedContract = parseContractDiagnostics(contractText);
        assertEquals("Contract fixture must declare 16 expected diagnostics", 16, expectedContract.size());

        myFixture.configureByText("root_fluster.domain.stvn", source);
        List<HighlightInfo> diagnostics = collectDiagnosticHighlights();

        assertZeroDuplicateDiagnostics("root_fluster.domain.stvn", diagnostics);
        assertEquals(
            "root_fluster.domain.stvn must emit strictly 16 diagnostics (0 warnings, 0 secondary cascades): "
                + describeHighlights(diagnostics),
            16,
            diagnostics.size()
        );

        for (HighlightInfo info : diagnostics) {
            assertEquals("All 16 diagnostics in root_fluster.domain.stvn must have ERROR severity",
                HighlightSeverity.ERROR, info.getSeverity());
            String desc = info.getDescription();
            assertNotNull(desc);
            assertFalse("Secondary ':Type :String' on line 21 must never trigger PW002 capacity warning",
                desc.contains("WARN_NOMINAL_STRING_UNADORNED") || desc.contains("PW002"));
            assertFalse("Secondary ':meta' on lines 15-19 must never trigger stem mismatch warning",
                desc.contains("WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME"));
        }

        // Verify 1:1 offset, symbolic ID, and message parity against root_fluster.contract.stvn_i
        for (ContractDiagnostic expected : expectedContract) {
            List<HighlightInfo> matching = diagnostics.stream()
                .filter(h -> h.getStartOffset() == expected.startOffset() && h.getEndOffset() == expected.endOffset())
                .filter(h -> h.getDescription() != null
                    && h.getDescription().contains(expected.symbolicId())
                    && h.getDescription().contains(expected.message()))
                .toList();
            assertEquals(
                "Expected exactly 1 highlight matching contract entry " + expected + "; actual=" + describeHighlights(diagnostics),
                1,
                matching.size()
            );

            if ("ERR_DUPLICATE_ROOT_SECTION".equals(expected.symbolicId())
                    || "ERR_META_POSITION_INVALID".equals(expected.symbolicId())) {
                String kw = source.substring(expected.startOffset(), expected.endOffset());
                myFixture.getEditor().getCaretModel().moveToOffset(expected.startOffset() + 1);
                var quickFixes = myFixture.filterAvailableIntentions("Remove duplicate '" + kw + "' section");
                assertFalse(
                    "Secondary section '" + kw + "' at offset " + expected.startOffset() + " must offer Remove duplicate quick-fix",
                    quickFixes.isEmpty()
                );
            }
        }
    }

    /**
     * Verifies Intention Preview ({@code getIntentionPreviewText}) and execution ({@code launchAction})
     * for {@code "Remove duplicate '<kw>' section"} across {@code :meta}, {@code :defs}, {@code :type},
     * and {@code :body}, confirming clean single-line deletion with zero orphaned blank lines.
     */
    public void testRemoveDuplicateSectionQuickFixAndIntentionPreviewAcrossAllFourSections() {
        var initial = """
            {
              :meta {
                #name "dup_cleanup"
                #domain "domain"
                #kind #BODY
              }
              :defs {
                :Port :Int
              }
              :type :Port
              :body 8080
              :meta {}
              :defs {}
              :type {}
              :body {}
            }
            """;
        myFixture.configureByText("dup_cleanup.domain.stvn", initial);

        String[] keywords = {":meta", ":defs", ":type", ":body"};
        for (String kw : keywords) {
            myFixture.doHighlighting();
            String currentText = myFixture.getFile().getText();
            int duplicateOffset = currentText.lastIndexOf(kw + " {}");
            assertTrue("Expected duplicate '" + kw + " {}' in buffer", duplicateOffset > 0);

            myFixture.getEditor().getCaretModel().moveToOffset(duplicateOffset + 1);
            var fix = myFixture.findSingleIntention("Remove duplicate '" + kw + "' section");
            String preview = myFixture.getIntentionPreviewText(fix);
            assertFalse("Intention preview must remove duplicate '" + kw + " {}'", preview.contains(kw + " {}"));
            assertFalse("Intention preview must not leave orphaned blank lines", preview.contains("\n\n"));

            myFixture.launchAction(fix);
            assertEquals("Physical buffer after quick-fix must match intention preview", preview, myFixture.getFile().getText());
        }

        myFixture.checkResult("""
            {
              :meta {
                #name "dup_cleanup"
                #domain "domain"
                #kind #BODY
              }
              :defs {
                :Port :Int
              }
              :type :Port
              :body 8080
            }
            """);

        List<HighlightInfo> postErrors = collectDiagnosticHighlights().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue("Document must have zero errors after removing all duplicate sections: " + describeHighlights(postErrors),
            postErrors.isEmpty());
    }

    /**
     * Scenario B ({@code root_ordering.stvn}): verifies the 4 contract diagnostics ({@code W002} + 3
     * misplaced-section errors) plus primary {@code :defs} {@code PW002} with zero duplicates, verifies
     * {@code Ctrl+Click} reference resolution on {@code :Type} in {@code :type :Type} (line 3) to
     * {@code :Type :String} on line 5, and verifies {@link StvnDocumentationProvider#generateDoc} renders
     * {@code :String} nominal schema documentation without false degraded-schema banners before reformatting.
     */
    public void testScenarioBRootOrderingDiagnosticsCtrlClickAndSemanticHoverBeforeReformat() throws IOException {
        String source = Files.readString(STRUCTURE_INVALID_DIR.resolve("root_ordering.stvn"), StandardCharsets.UTF_8);
        String contractText = Files.readString(STRUCTURE_INVALID_DIR.resolve("root_ordering.contract.stvn_i"), StandardCharsets.UTF_8);
        List<ContractDiagnostic> expectedContract = parseContractDiagnostics(contractText);
        assertEquals("Contract fixture must declare 4 expected core diagnostics", 4, expectedContract.size());

        var file = myFixture.configureByText("root_ordering.stvn", source);
        List<HighlightInfo> diagnostics = collectDiagnosticHighlights();
        assertZeroDuplicateDiagnostics("root_ordering.stvn", diagnostics);

        List<HighlightInfo> errors = diagnostics.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals(
            "root_ordering.stvn must emit strictly 3 misplaced-section ERRORs: " + describeHighlights(errors),
            3,
            errors.size()
        );

        for (ContractDiagnostic expected : expectedContract) {
            List<HighlightInfo> matching = diagnostics.stream()
                .filter(h -> h.getStartOffset() == expected.startOffset() && h.getEndOffset() == expected.endOffset())
                .filter(h -> h.getDescription() != null
                    && h.getDescription().contains(expected.symbolicId())
                    && h.getDescription().contains(expected.message()))
                .toList();
            assertEquals(
                "Expected exactly 1 highlight matching contract entry " + expected + "; actual=" + describeHighlights(diagnostics),
                1,
                matching.size()
            );
        }

        // Verify Ctrl+Click reference resolution on ':Type' in ':type :Type' (line 3, above ':defs' on line 4)
        int typeRefOffset = source.indexOf(":Type");
        assertTrue("':Type' reference must appear before ':defs'", typeRefOffset > 0 && typeRefOffset < source.indexOf(":defs"));
        myFixture.getEditor().getCaretModel().moveToOffset(typeRefOffset + 1);

        PsiElement resolved = myFixture.getReferenceAtCaretPositionWithAssertion().resolve();
        assertNotNull("Ctrl+Click on ':Type' above ':defs' must resolve before reformatting", resolved);
        TypeDefinition resolvedTypeDef = resolved instanceof TypeDefinition td
            ? td
            : PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class, false);
        assertNotNull("Resolved element must belong to TypeDefinition ':Type :String'", resolvedTypeDef);
        assertEquals(":Type :String", resolvedTypeDef.getText());
        assertEquals("Resolved TypeDefinition must reside inside ':defs' on line 5",
            source.indexOf(":Type :String"), resolvedTypeDef.getTextOffset());

        // Verify semantic hover documentation renders ':String' nominal schema without Degraded Schema Warning
        PsiElement sourceLeaf = file.findElementAt(typeRefOffset + 1);
        String hoverDoc = new StvnDocumentationProvider().generateDoc(resolved, sourceLeaf);
        assertNotNull("Hover documentation on ':Type' must be non-null before reformatting", hoverDoc);
        assertTrue("Hover documentation must include ':Type' and ':String': " + hoverDoc,
            hoverDoc.contains(":Type") && hoverDoc.contains(":String"));
        assertFalse("Misplaced section order must NOT mark ':Type' as a Degraded Schema: " + hoverDoc,
            hoverDoc.contains("Degraded Schema Warning"));
    }

    /**
     * Verifies Intention Preview and execution for
     * {@code "Reorder document sections to canonical order (:meta -> :defs -> :type -> :body)"}
     * on Quartet #13 ({@code 13_root_section_canonical_reordering.domain.stvn}) and
     * {@code root_ordering_with_body_error.domain.stvn}, confirming contiguous leading and same-line
     * trailing {@code //} comment migration while preserving Pass 3 {@code E012} on {@code 5000}.
     */
    public void testReorderRootSectionsQuickFixAndIntentionPreviewPreservesComments() throws IOException {
        String quartetInput = Files.readString(
            FORMATTING_DIR.resolve(Paths.get("input", "13_root_section_canonical_reordering.domain.stvn")),
            StandardCharsets.UTF_8
        );
        myFixture.configureByText("13_root_section_canonical_reordering.domain.stvn", quartetInput);
        myFixture.doHighlighting();

        int typeSectionOffset = quartetInput.indexOf(":type :Type");
        myFixture.getEditor().getCaretModel().moveToOffset(typeSectionOffset + 1);

        var reorderFix = myFixture.findSingleIntention(
            "Reorder document sections to canonical order (:meta -> :defs -> :type -> :body)"
        );
        String preview = myFixture.getIntentionPreviewText(reorderFix);

        int metaIdx = preview.indexOf("// Leading comment on :meta section\n  :meta {");
        int defsIdx = preview.indexOf("// Leading comment on :defs section\n  :defs {");
        int typeIdx = preview.indexOf("// Leading comment on :type section\n  :type :Type // Trailing comment on :type");
        int bodyIdx = preview.indexOf("// Leading comment on :body section\n  :body \"ok\" // Trailing comment on :body");
        assertTrue("Preview must migrate leading/trailing comments with :meta first", metaIdx > 0);
        assertTrue("Preview must place :defs after :meta", defsIdx > metaIdx);
        assertTrue("Preview must place :type after :defs", typeIdx > defsIdx);
        assertTrue("Preview must place :body after :type", bodyIdx > typeIdx);
        assertTrue("Preview must retain trailing comment on :meta", preview.contains("} // Trailing comment on :meta"));
        assertTrue("Preview must retain trailing comment on :defs", preview.contains("} // Trailing comment on :defs"));

        myFixture.launchAction(reorderFix);
        assertEquals("Launched quick-fix buffer must match intention preview", preview, myFixture.getFile().getText());

        // Now verify root_ordering_with_body_error.domain.stvn preserves Pass 3 E012 on 5000 before and after reordering
        String bodyErrorSource = Files.readString(
            STRUCTURE_INVALID_DIR.resolve("root_ordering_with_body_error.domain.stvn"),
            StandardCharsets.UTF_8
        );
        myFixture.configureByText("root_ordering_with_body_error.domain.stvn", bodyErrorSource);
        List<HighlightInfo> preHighlights = collectDiagnosticHighlights();
        assertTrue("Pre-reorder buffer must report Pass 3 E012 constraint error on 5000",
            preHighlights.stream().anyMatch(h -> "5000".equals(h.getText())
                && h.getDescription() != null
                && h.getDescription().contains("ERR_CONSTRAINT_VIOLATION")));

        myFixture.getEditor().getCaretModel().moveToOffset(bodyErrorSource.indexOf(":defs") + 1);
        var bodyErrorReorderFix = myFixture.findSingleIntention(
            "Reorder document sections to canonical order (:meta -> :defs -> :type -> :body)"
        );
        myFixture.launchAction(bodyErrorReorderFix);

        List<HighlightInfo> postErrors = collectDiagnosticHighlights().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("After reordering sections, strictly 1 ERROR (Pass 3 E012 on 5000) must remain: "
            + describeHighlights(postErrors), 1, postErrors.size());
        assertEquals("5000", postErrors.get(0).getText());
        assertTrue(postErrors.get(0).getDescription().contains("ERR_CONSTRAINT_VIOLATION"));
    }

    /**
     * Verifies {@code incomplete_type_with_facets_before_body.stvn} ({@code :type { #size 16 }\n  :body {}})
     * pins strictly 1 error ({@code ERR_MALFORMED_SECTION_STRUCTURE}) to {@code :type} with zero spill onto
     * {@code :body {}}, and invokes {@code "Insert schema type ':Int'"} to produce {@code :type { #size 16 } :Int}.
     */
    public void testIncompleteTypeWithFacetsBeforeBodyZeroSpillAndSchemaInsertionAfterMetadataMap() throws IOException {
        String source = Files.readString(
            STRUCTURE_INVALID_DIR.resolve("incomplete_type_with_facets_before_body.stvn"),
            StandardCharsets.UTF_8
        );
        myFixture.configureByText("incomplete_type_with_facets_before_body.stvn", source);
        List<HighlightInfo> errors = collectDiagnosticHighlights().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();

        assertEquals(
            "incomplete_type_with_facets_before_body.stvn must emit strictly 1 ERROR: " + describeHighlights(errors),
            1,
            errors.size()
        );
        HighlightInfo err = errors.get(0);
        assertEquals(":type", err.getText());
        assertTrue(err.getDescription().contains("ERR_MALFORMED_SECTION_STRUCTURE"));
        assertTrue(err.getDescription().contains("Incomplete ':type' section"));

        int bodyOffset = source.indexOf(":body");
        assertTrue("Error on ':type' must never spill onto ':body {}'", err.getEndOffset() <= bodyOffset);

        myFixture.getEditor().getCaretModel().moveToOffset(source.indexOf(":type") + 1);
        var insertSchemaFix = myFixture.findSingleIntention("Insert schema type ':Int'");
        myFixture.launchAction(insertSchemaFix);

        myFixture.checkResult("""
            {
              :type { #size 16 } :Int
              :body {}
            }
            """);
    }

    /**
     * Verifies context-sensitive empty {@code :meta {}} and empty {@code :defs {}} remedies:
     * <ul>
     *   <li>1-stem {@code empty_meta_optional_in_stvn.stvn} emits {@code "omit ':meta' or ..."} and offers {@code "Remove empty ':meta {}' section"}.</li>
     *   <li>2-stem {@code root_fluster.domain.stvn} omits {@code "omit ':meta'"} and omits the remove-{@code :meta} quick-fix.</li>
     *   <li>{@code .stvn_d} {@code empty_defs_mandatory_in_stvn_d.domain.stvn_d} emits {@code "definitions schema (.stvn_d) requires ..."} and offers {@code "Insert sample type definition in :defs"}.</li>
     * </ul>
     */
    public void testContextSensitiveEmptyMetaAndEmptyDefsRemedies() throws IOException {
        // 1. 1-stem optional :meta in .stvn
        String optionalMetaSource = Files.readString(
            METADATA_INVALID_DIR.resolve("empty_meta_optional_in_stvn.stvn"),
            StandardCharsets.UTF_8
        );
        myFixture.configureByText("empty_meta_optional_in_stvn.stvn", optionalMetaSource);
        List<HighlightInfo> optionalMetaErrors = collectDiagnosticHighlights().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals(1, optionalMetaErrors.size());
        assertTrue(optionalMetaErrors.get(0).getDescription().contains(
            "Empty ':meta {}' section is prohibited; omit ':meta' or declare document identity facets (#name, #domain, #kind)"
        ));

        myFixture.getEditor().getCaretModel().moveToOffset(optionalMetaSource.indexOf("{}") + 1);
        var removeMetaFix = myFixture.findSingleIntention("Remove empty ':meta {}' section");
        myFixture.launchAction(removeMetaFix);
        myFixture.checkResult("""
            {
              :type :Int
              :body 42
            }
            """);

        // 2. 2-stem mandatory :meta in root_fluster.domain.stvn
        String mandatoryMetaSource = Files.readString(
            STRUCTURE_INVALID_DIR.resolve("root_fluster.domain.stvn"),
            StandardCharsets.UTF_8
        );
        myFixture.configureByText("root_fluster.domain.stvn", mandatoryMetaSource);
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(mandatoryMetaSource.indexOf("{}") + 1);
        assertTrue("2-stem domain filename must NOT offer 'Remove empty :meta {} section'",
            myFixture.filterAvailableIntentions("Remove empty ':meta {}' section").isEmpty());
        assertFalse("2-stem domain filename must still offer 'Insert missing root :meta block'",
            myFixture.filterAvailableIntentions("Insert missing root :meta block").isEmpty());

        // 3. Mandatory :defs in .stvn_d
        String mandatoryDefsSource = Files.readString(
            METADATA_INVALID_DIR.resolve("empty_defs_mandatory_in_stvn_d.domain.stvn_d"),
            StandardCharsets.UTF_8
        );
        myFixture.configureByText("empty_defs_mandatory_in_stvn_d.domain.stvn_d", mandatoryDefsSource);
        List<HighlightInfo> mandatoryDefsErrors = collectDiagnosticHighlights().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals(1, mandatoryDefsErrors.size());
        assertTrue(mandatoryDefsErrors.get(0).getDescription().contains(
            "Empty ':defs {}' section is prohibited; definitions schema (.stvn_d) requires at least one type or constant definition"
        ));

        int emptyDefsBraceOffset = mandatoryDefsSource.indexOf(":defs {}") + ":defs ".length() + 1;
        myFixture.getEditor().getCaretModel().moveToOffset(emptyDefsBraceOffset);
        assertTrue("Definitions schema (.stvn_d) must NOT offer 'Remove empty :defs {} section'",
            myFixture.filterAvailableIntentions("Remove empty ':defs {}' section").isEmpty());
        var insertSampleTypeFix = myFixture.findSingleIntention("Insert sample type definition in :defs");
        myFixture.launchAction(insertSampleTypeFix);
        assertTrue(myFixture.getFile().getText().contains(":SampleType"));
    }

    /**
     * Executes {@code CodeStyleManager.reformat(file)} ({@code Ctrl+Alt+L}) on {@code root_ordering.stvn}
     * and Quartet #13 ({@code 13_root_section_canonical_reordering.domain.stvn}) across all four
     * {@link StvnSettings.ReformatDelegationMode} modes, asserting canonical {@code :meta -> :defs -> :type -> :body}
     * order, {@code #name -> #domain -> #kind} facet sorting, 4-space {@code :Type :String} indentation inside
     * {@code :defs}, comment preservation in authoring modes, and zero remaining order errors.
     */
    public void testReformatCodeAutoHealsRootOrderingAcrossAllFourDelegationModes() throws IOException {
        String rootOrderingInput = Files.readString(
            STRUCTURE_INVALID_DIR.resolve("root_ordering.stvn"),
            StandardCharsets.UTF_8
        );
        String quartetInput = Files.readString(
            FORMATTING_DIR.resolve(Paths.get("input", "13_root_section_canonical_reordering.domain.stvn")),
            StandardCharsets.UTF_8
        );
        String expectedQuartetSourcePretty = Files.readString(
            FORMATTING_DIR.resolve(Paths.get("source_pretty", "13_root_section_canonical_reordering.domain.stvn")),
            StandardCharsets.UTF_8
        );
        String expectedQuartetPretty = Files.readString(
            FORMATTING_DIR.resolve(Paths.get("pretty", "13_root_section_canonical_reordering.domain.stvn")),
            StandardCharsets.UTF_8
        );
        String expectedRootOrderingPretty = """
            {
              :meta {
                #name "root_ordering"
                #domain "domain"
                #kind #BODY
              }
              :defs {
                :Type :String
              }
              :type :Type
              :body ""
            }
            """;

        var settings = StvnSettings.getInstance(getProject());
        var projSettings = StvnProjectSettings.getInstance(getProject());
        var prevMode = settings.getState().reformatDelegationMode;
        boolean prevSuppress = projSettings.getState().suppressCanonicalizationWarning;

        try {
            projSettings.getState().suppressCanonicalizationWarning = true;

            for (var mode : StvnSettings.ReformatDelegationMode.values()) {
                settings.getState().reformatDelegationMode = mode;

                // 1. Reformat comment-free root_ordering.stvn -> identical canonical output in all 4 modes
                var rootOrderingFile = myFixture.configureByText("root_ordering.stvn", rootOrderingInput);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(rootOrderingFile);
                });
                assertEquals("Reformat mode " + mode + " on root_ordering.stvn must produce canonical order and 4-space :defs indent",
                    expectedRootOrderingPretty, rootOrderingFile.getText());

                List<HighlightInfo> postErrors = collectDiagnosticHighlights().stream()
                    .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                    .toList();
                assertTrue("Mode " + mode + " must eliminate all section-order ERRORs in root_ordering.stvn: "
                    + describeHighlights(postErrors), postErrors.isEmpty());

                // 2. Reformat Quartet #13 with leading & trailing comments + disordered :meta facets
                var quartetFile = myFixture.configureByText("13_root_section_canonical_reordering.domain.stvn", quartetInput);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(quartetFile);
                });

                String expectedQuartet = (mode == StvnSettings.ReformatDelegationMode.CORE_PRETTY_CANONICAL)
                    ? expectedQuartetPretty
                    : expectedQuartetSourcePretty;
                assertEquals("Reformat mode " + mode + " mismatch on Quartet #13",
                    expectedQuartet, quartetFile.getText());

                List<HighlightInfo> quartetPostErrors = collectDiagnosticHighlights().stream()
                    .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                    .toList();
                assertTrue("Mode " + mode + " must leave zero ERRORs on Quartet #13: "
                    + describeHighlights(quartetPostErrors), quartetPostErrors.isEmpty());
            }
        } finally {
            settings.getState().reformatDelegationMode = prevMode;
            projSettings.getState().suppressCanonicalizationWarning = prevSuppress;
            FileDocumentManager.getInstance().saveAllDocuments();
        }
    }

    /**
     * Verifies {@link ReorderRootSectionsQuickFix} is unavailable and {@code Ctrl+Alt+L} refuses to
     * reorder sections when duplicate root sections ({@code root_fluster.domain.stvn}) or incomplete
     * sections ({@code incomplete_type_with_facets_before_body.stvn}) are present.
     */
    public void testDuplicateOrIncompleteRootSectionsPreventUnsafeSectionReordering() throws IOException {
        // 1. Duplicate sections (root_fluster.domain.stvn)
        String flusterSource = Files.readString(
            STRUCTURE_INVALID_DIR.resolve("root_fluster.domain.stvn"),
            StandardCharsets.UTF_8
        );
        var flusterFile = myFixture.configureByText("root_fluster.domain.stvn", flusterSource);
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(flusterSource.lastIndexOf(":meta") + 1);
        assertTrue("Duplicate root sections must NOT offer ReorderRootSectionsQuickFix",
            myFixture.filterAvailableIntentions("Reorder document sections to canonical order").isEmpty());

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(flusterFile);
        });
        String flusterAfterReformat = flusterFile.getText();
        assertTrue("Ctrl+Alt+L must preserve earlier duplicate ':defs' sections ahead of trailing ':meta' without reordering",
            flusterAfterReformat.indexOf(":defs") < flusterAfterReformat.indexOf("#name \"root_fluster\""));

        // 2. Incomplete section out of order (:body before incomplete :type { #size 16 })
        String incompleteOutOfOrder = """
            {
              :body {}
              :type { #size 16 }
            }
            """;
        var incompleteFile = myFixture.configureByText("incomplete_out_of_order.stvn", incompleteOutOfOrder);
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(incompleteOutOfOrder.indexOf(":type") + 1);
        assertTrue("Incomplete ':type { #size 16 }' must NOT offer ReorderRootSectionsQuickFix",
            myFixture.filterAvailableIntentions("Reorder document sections to canonical order").isEmpty());

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(incompleteFile);
        });
        String incompleteAfterReformat = incompleteFile.getText();
        assertTrue("Ctrl+Alt+L must NOT reorder incomplete ':type { #size 16 }' ahead of ':body {}'",
            incompleteAfterReformat.indexOf(":body {}") < incompleteAfterReformat.indexOf(":type { #size 16 }"));
    }

    private List<HighlightInfo> collectDiagnosticHighlights() {
        return myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity().compareTo(HighlightSeverity.WEAK_WARNING) >= 0)
            .toList();
    }

    private static String describeHighlights(List<HighlightInfo> highlights) {
        return highlights.stream()
            .map(h -> String.format("%s@[%d,%d):'%s' -> %s",
                h.getSeverity().getName(), h.getStartOffset(), h.getEndOffset(), h.getText(), h.getDescription()))
            .toList()
            .toString();
    }

    private static void assertZeroDuplicateDiagnostics(String fileName, List<HighlightInfo> highlights) {
        record RangeCodeKey(int start, int end, String code) {}
        Map<RangeCodeKey, List<HighlightInfo>> grouped = new HashMap<>();
        for (HighlightInfo h : highlights) {
            String desc = h.getDescription();
            if (desc == null) {
                continue;
            }
            var matcher = DIAGNOSTIC_TAG_PATTERN.matcher(desc);
            if (matcher.find()) {
                var key = new RangeCodeKey(h.getStartOffset(), h.getEndOffset(), matcher.group(2));
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(h);
            }
        }
        for (var entry : grouped.entrySet()) {
            if (entry.getValue().size() > 1) {
                fail("Duplicate diagnostic in " + fileName + " at " + entry.getKey() + ": "
                    + describeHighlights(entry.getValue()));
            }
        }
    }

    private record ContractDiagnostic(
        String specCode,
        String symbolicId,
        String severity,
        int line,
        int column,
        int startOffset,
        int endOffset,
        String message
    ) {}

    private static List<ContractDiagnostic> parseContractDiagnostics(String contractText) {
        List<ContractDiagnostic> list = new ArrayList<>();
        var matcher = CONTRACT_ENTRY_PATTERN.matcher(contractText);
        while (matcher.find()) {
            list.add(new ContractDiagnostic(
                matcher.group(1),
                matcher.group(2),
                matcher.group(3),
                Integer.parseInt(matcher.group(4)),
                Integer.parseInt(matcher.group(5)),
                Integer.parseInt(matcher.group(6)),
                Integer.parseInt(matcher.group(7)),
                matcher.group(8)
            ));
        }
        return list;
    }
}
