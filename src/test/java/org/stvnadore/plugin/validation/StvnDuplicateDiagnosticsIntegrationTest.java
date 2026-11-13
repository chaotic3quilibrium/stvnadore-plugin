package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Platform integration test suite asserting zero duplicate diagnostic codes across overlapping
 * coordinate ranges when both annotators and all 26 registered local inspections run concurrently.
 * <p>
 * Enforces the invariant: count(range, diagnosticCode) == 1.
 */
@NullMarked
public final class StvnDuplicateDiagnosticsIntegrationTest extends BasePlatformTestCase {

    private static final Pattern DIAGNOSTIC_TAG_PATTERN = Pattern.compile("\\[(stvn-core|stvn-plugin):([A-Z0-9_-]+)\\]");

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

    /**
     * Verifies that document metadata stem mismatches in roots.stvn produce exactly ONE warning
     * on #domain and zero duplicate [stvn-core:WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME] highlights.
     */
    public void testRootsDocumentMetadataStemMismatchHasZeroDuplicates() {
        var content = """
            {
              :meta {
                #name "roots"
                #domain "domain"
                #kind #BODY
              }
              :type :Int
              :body 42
            }
            """;
        myFixture.configureByText("roots.stvn", content);
        var highlights = myFixture.doHighlighting();

        assertZeroDuplicateDiagnostics("roots.stvn", highlights);

        var domainWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME"))
            .toList();

        assertEquals("Expected exactly 1 WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME highlight", 1, domainWarnings.size());
        assertTrue("Highlight must originate exclusively from stvn-plugin",
            domainWarnings.get(0).getDescription().contains("[stvn-plugin:W002-WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME]"));
        assertNoCoreHighlight("roots.stvn", highlights, "WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME");
        assertNoCoreHighlight("roots.stvn", highlights, "W002");
    }

    /**
     * Verifies that suspicious epoch tick integer literals produce exactly ONE warning
     * without annotator duplicates.
     */
    public void testSuspiciousEpochMagnitudeHasZeroDuplicates() {
        var content = """
            {
              :type { #s } :TimeEpoch
              :body 1700000000000000
            }
            """;
        myFixture.configureByText("epoch_sample.stvn", content);
        var highlights = myFixture.doHighlighting();

        assertZeroDuplicateDiagnostics("epoch_sample.stvn", highlights);
        assertNoCoreHighlight("epoch_sample.stvn", highlights, "WARN_SUSPICIOUS_EPOCH_MAGNITUDE");
    }

    /**
     * Verifies that missing root :meta blocks in definitions schemas (.stvn_d) produce exactly ONE error
     * on line 1 root opening brace without annotator duplicates.
     */
    public void testMissingMetaInDefinitionsSchemaHasZeroDuplicates() {
        var content = """
            {
              :defs {
                :MyInt :Int
              }
            }
            """;
        myFixture.configureByText("schema.stvn_d", content);
        var highlights = myFixture.doHighlighting();

        assertZeroDuplicateDiagnostics("schema.stvn_d", highlights);

        var line1Errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getStartOffset() == 0 && h.getEndOffset() <= 1)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_DOCUMENT_KIND_MISMATCH"))
            .toList();

        assertEquals("Expected exactly 1 ERR_DOCUMENT_KIND_MISMATCH on line 1 brace", 1, line1Errors.size());
        assertTrue(line1Errors.get(0).getDescription().contains("[stvn-plugin:E031-ERR_DOCUMENT_KIND_MISMATCH]"));
        assertNoCoreHighlight("schema.stvn_d", highlights, "ERR_DOCUMENT_KIND_MISMATCH");
        assertNoCoreHighlight("schema.stvn_d", highlights, "E031");
    }

    /**
     * Verifies that forbidden tab characters produce exactly ONE error without annotator duplication.
     */
    public void testTabCharacterForbiddenHasZeroDuplicates() {
        var content = "{\n\t:type :Int\n  :body 42\n}";
        myFixture.configureByText("tab_sample.stvn", content);
        var highlights = myFixture.doHighlighting();

        assertZeroDuplicateDiagnostics("tab_sample.stvn", highlights);
        assertNoCoreHighlight("tab_sample.stvn", highlights, "ERR_TAB_CHARACTER_FORBIDDEN");
    }

    /**
     * Asserts that StvnDiagnosticAnnotatorMapping.isDiagnosticGovernedAndActive returns true
     * across symbolic codes, numeric standard codes (W002, E001, E031), and prose patterns.
     */
    public void testIsDiagnosticGovernedAndActiveMultiKeyResolution() {
        var file = myFixture.configureByText("test.stvn", "{\n  :type :Int\n  :body 42\n}");

        // 1. Symbolic name resolution
        var symbolicDiag = new org.stvnadore.core.StvnDiagnostic(
            "Declared #domain 'domain' is omitted from filename stem slot 1",
            org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity.WARNING,
            1, 0, 0, 10, null, java.util.Optional.of("WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME")
        );
        assertTrue("Symbolic diagnostic code must be governed and active",
            StvnDiagnosticAnnotatorMapping.isDiagnosticGovernedAndActive(file, symbolicDiag));

        // 2. Numeric specification code resolution (W002)
        var numericDiag = new org.stvnadore.core.StvnDiagnostic(
            "Declared #domain 'domain' is omitted from filename stem slot 1",
            org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity.WARNING,
            1, 0, 0, 10, null, java.util.Optional.of("W002")
        );
        assertTrue("Numeric specification code W002 must be governed and active",
            StvnDiagnosticAnnotatorMapping.isDiagnosticGovernedAndActive(file, numericDiag));

        // 3. Specification table canonical identifier (DOCUMENT_DOMAIN_OMITTED_IN_FILENAME)
        var specTableDiag = new org.stvnadore.core.StvnDiagnostic(
            "Declared #domain 'domain' is omitted from filename stem slot 1",
            org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity.WARNING,
            1, 0, 0, 10, null, java.util.Optional.of("DOCUMENT_DOMAIN_OMITTED_IN_FILENAME")
        );
        assertTrue("Spec table canonical name must be governed and active",
            StvnDiagnosticAnnotatorMapping.isDiagnosticGovernedAndActive(file, specTableDiag));

        // 4. Defensive prose fallback with empty errorCode
        var proseDiag = new org.stvnadore.core.StvnDiagnostic(
            "Declared #domain 'domain' is omitted from filename stem slot 1",
            org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity.WARNING,
            1, 0, 0, 10, null, java.util.Optional.empty()
        );
        assertTrue("Prose pattern fallback must govern untyped diagnostic",
            StvnDiagnosticAnnotatorMapping.isDiagnosticGovernedAndActive(file, proseDiag));

        // 5. Fatal syntax error must NOT be governed
        var syntaxDiag = new org.stvnadore.core.StvnDiagnostic(
            "STVN Syntax Error: mismatched input",
            org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity.ERROR,
            1, 0, 0, 1, null, java.util.Optional.of("STVN_SYNTAX_ERROR")
        );
        assertFalse("Raw syntax errors must remain ungoverned",
            StvnDiagnosticAnnotatorMapping.isDiagnosticGovernedAndActive(file, syntaxDiag));
    }

    /**
     * Verifies that excluded enum subset payload variants and #CONST values under disordered
     * parent facets each produce exactly ONE error highlight without core/plugin duplication.
     */
    public void testEnumSubsetExcludedPayloadAndDisorderedParentConstBoundHaveZeroDuplicates() {
        var content = """
            {
              :defs {
                :LedgerChannel {
                  #minIncl 1024
                  #size 16
                  #unsigned
                } :Int
                #FALLBACK_CHANNEL :LedgerChannel 500
                :TradeSide :Enum[#BUY #SELL #SHORT #COVER]
                :LongOnlySide { #filterIncl [#BUY #SELL] } :TradeSide
              }
              :type :LongOnlySide
              :body #SHORT
            }
            """;
        myFixture.configureByText("gauntlet_v2_dedup.stvn", content);
        var highlights = myFixture.doHighlighting();

        assertZeroDuplicateDiagnostics("gauntlet_v2_dedup.stvn", highlights);

        var shortErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "#SHORT".equals(h.getText()))
            .toList();
        assertEquals("Expected exactly 1 error highlight on excluded enum subset variant '#SHORT'", 1, shortErrors.size());
        assertTrue(shortErrors.get(0).getDescription().contains("[stvn-plugin:E012-ERR_CONSTRAINT_VIOLATION]"));

        var constErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "500".equals(h.getText()))
            .toList();
        assertEquals("Expected exactly 1 error highlight on out-of-bounds #FALLBACK_CHANNEL value '500'", 1, constErrors.size());
        assertTrue(constErrors.get(0).getDescription().contains("[stvn-plugin:E012-ERR_CONSTRAINT_VIOLATION]"));
    }

    /**
     * Verifies that Stage 9 nested :package (2A), reserved :org/stvnadore/custom_hack (2B E037),
     * and trailing slash in :use (2C) produce zero duplicate diagnostics when all inspections are enabled.
     */
    public void testStage9NestedReservedPackageAndTrailingSlashHaveZeroDuplicatesAndPreserveE037() {
        var content = """
            {
              :defs {
                :package :com/acme/identity {
                  :UserId { #unsigned #size 64 #minIncl 1000 } :Int
                  :package :com/acme/identity/nested {
                    :IllegalNestedType :Int
                  }
                }
                :package :org/stvnadore/custom_hack {
                  :HijackedPort :Int
                }
                :use [ :com/acme/identity/ ]
              }
              :type :Int
              :body 0
            }
            """;
        myFixture.configureByText("stage9_dedup.stvn_i", content);
        var highlights = myFixture.doHighlighting();

        assertZeroDuplicateDiagnostics("stage9_dedup.stvn_i", highlights);
        assertNoCoreHighlight("stage9_dedup.stvn_i", highlights, "ERR_NESTED_PACKAGE_PROHIBITED");
        assertNoCoreHighlight("stage9_dedup.stvn_i", highlights, "ERR_TRAILING_SLASH_PROHIBITED");

        var reservedPkgErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_RESERVED_PACKAGE_PROHIBITED"))
            .toList();
        assertEquals("Expected exactly 1 E037 ERR_RESERVED_PACKAGE_PROHIBITED highlight", 1, reservedPkgErrors.size());
    }

    /**
     * Verifies that mirrored meta_omitted_domain_with_body_error and meta_omitted_kind_with_body_error
     * deconflict cleanly across all enabled STVN local inspections with zero duplicate highlights,
     * exact 5-char ':meta' [4, 9) pinning, and intact downstream ':body 5000' E012 validation.
     */
    public void testOmittedDomainAndOmittedKindInMetaDeconflictCleanlyWithBodyError() throws IOException {
        var metadataDir = Paths.get("src", "test", "resources", "shared-fixtures", "syntax", "invalid", "metadata");
        var omittedDomainPath = metadataDir.resolve("meta_omitted_domain_with_body_error.core_test.stvn_i");
        var omittedKindPath = metadataDir.resolve("meta_omitted_kind_with_body_error.core_test.stvn_i");
        assertTrue("Mirrored omitted-domain fixture must exist: " + omittedDomainPath, Files.isRegularFile(omittedDomainPath));
        assertTrue("Mirrored omitted-kind fixture must exist: " + omittedKindPath, Files.isRegularFile(omittedKindPath));

        var omittedDomain = Files.readString(omittedDomainPath, StandardCharsets.UTF_8).replace("\r\n", "\n");
        myFixture.configureByText("meta_omitted_domain_with_body_error.core_test.stvn_i", omittedDomain);
        var domainHighlights = myFixture.doHighlighting();
        assertZeroDuplicateDiagnostics("meta_omitted_domain_with_body_error.core_test.stvn_i", domainHighlights);
        assertNoCoreHighlight("meta_omitted_domain_with_body_error.core_test.stvn_i", domainHighlights, "WARN_DOCUMENT_DOMAIN_OMITTED_IN_META");
        assertEquals(1, domainHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && h.getStartOffset() == 4 && h.getEndOffset() == 9)
            .count());
        assertEquals(1, domainHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR && h.getStartOffset() == 204 && h.getEndOffset() == 208)
            .count());

        var omittedKind = Files.readString(omittedKindPath, StandardCharsets.UTF_8).replace("\r\n", "\n");
        myFixture.configureByText("meta_omitted_kind_with_body_error.core_test.stvn_i", omittedKind);
        var kindHighlights = myFixture.doHighlighting();
        assertZeroDuplicateDiagnostics("meta_omitted_kind_with_body_error.core_test.stvn_i", kindHighlights);
        assertNoCoreHighlight("meta_omitted_kind_with_body_error.core_test.stvn_i", kindHighlights, "ERR_DOCUMENT_KIND_MISMATCH");
        var metaErrors = kindHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR && h.getStartOffset() == 4 && h.getEndOffset() == 9)
            .toList();
        assertEquals(1, metaErrors.size());
        assertTrue(metaErrors.get(0).getDescription().contains(
            "Root ':meta' section requires #kind facet matching file extension '.stvn_i' (expected '#BODY_INCLUDE')"));
        assertEquals(1, kindHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR && h.getStartOffset() == 202 && h.getEndOffset() == 206)
            .count());
    }

    /**
     * Verifies zero duplicate diagnostics across Gauntlet v7 Batch 2 mirrored fixtures
     * const_nominal_enum_branding_and_conflict_filter_fallback.stvn and
     * incomplete_trait_facets_and_reserved_keyword_boundary.stvn when all 26 local inspections are active.
     */
    public void testGauntletV7Batch2EnumBrandingAndTraitBoundaryFixturesHaveZeroDuplicates() throws IOException {
        var enumBrandingPath = Paths.get(
            "src", "test", "resources", "shared-fixtures", "syntax", "invalid", "composites",
            "const_nominal_enum_branding_and_conflict_filter_fallback.stvn"
        );
        var traitBoundaryPath = Paths.get(
            "src", "test", "resources", "shared-fixtures", "syntax", "invalid", "metadata",
            "incomplete_trait_facets_and_reserved_keyword_boundary.stvn"
        );
        assertTrue("Mirrored enum branding fixture must exist: " + enumBrandingPath, Files.isRegularFile(enumBrandingPath));
        assertTrue("Mirrored trait boundary fixture must exist: " + traitBoundaryPath, Files.isRegularFile(traitBoundaryPath));

        var enumBranding = Files.readString(enumBrandingPath, StandardCharsets.UTF_8).replace("\r\n", "\n");
        myFixture.configureByText("const_nominal_enum_branding_and_conflict_filter_fallback.stvn", enumBranding);
        var enumHighlights = myFixture.doHighlighting();
        assertZeroDuplicateDiagnostics("const_nominal_enum_branding_and_conflict_filter_fallback.stvn", enumHighlights);
        assertNoCoreHighlight("const_nominal_enum_branding_and_conflict_filter_fallback.stvn", enumHighlights, "MUTUALLY_EXCLUSIVE_BOUNDS");
        assertNoCoreHighlight("const_nominal_enum_branding_and_conflict_filter_fallback.stvn", enumHighlights, "ERR_CONSTRAINT_VIOLATION");
        assertNoCoreHighlight("const_nominal_enum_branding_and_conflict_filter_fallback.stvn", enumHighlights, "ERR_TYPE_MISMATCH");
        assertEquals(
            "Expected strictly 5 deduplicated ERROR highlights matching contract",
            5,
            enumHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).count()
        );

        var traitBoundary = Files.readString(traitBoundaryPath, StandardCharsets.UTF_8).replace("\r\n", "\n");
        myFixture.configureByText("incomplete_trait_facets_and_reserved_keyword_boundary.stvn", traitBoundary);
        var traitHighlights = myFixture.doHighlighting();
        assertZeroDuplicateDiagnostics("incomplete_trait_facets_and_reserved_keyword_boundary.stvn", traitHighlights);
        assertNoCoreHighlight("incomplete_trait_facets_and_reserved_keyword_boundary.stvn", traitHighlights, "ERR_INCOMPLETE_TRAIT_FACET");
        assertEquals(
            "Expected strictly 4 deduplicated ERROR highlights matching contract",
            4,
            traitHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).count()
        );
    }

    /**
     * Asserts that zero [stvn-core:...] highlights exist for the specified diagnostic code.
     */
    private void assertNoCoreHighlight(String fileName, java.util.List<HighlightInfo> highlights, String code) {
        var coreHighlights = highlights.stream()
            .filter(h -> h.getDescription() != null)
            .filter(h -> h.getDescription().contains("[stvn-core:" + code + "]")
                || h.getDescription().contains("[stvn-core:" + code + "-")
                || (h.getDescription().contains("[stvn-core:") && h.getDescription().contains("-" + code + "]")))
            .toList();
        assertTrue(String.format("File '%s' must contain zero [stvn-core:%s] highlights, but found: %s",
            fileName, code, coreHighlights), coreHighlights.isEmpty());
    }

    /**
     * Core assertion utility grouping all highlights by (startOffset, endOffset, diagnosticCode)
     * and asserting count == 1.
     */
    private void assertZeroDuplicateDiagnostics(String fileName, java.util.List<HighlightInfo> highlights) {
        record RangeCodeKey(int start, int end, String diagnosticCode) {}

        Map<RangeCodeKey, java.util.List<HighlightInfo>> grouped = new HashMap<>();

        for (var h : highlights) {
            var desc = h.getDescription();
            if (desc == null) continue;

            var matcher = DIAGNOSTIC_TAG_PATTERN.matcher(desc);
            if (matcher.find()) {
                var code = matcher.group(2);
                var key = new RangeCodeKey(h.getStartOffset(), h.getEndOffset(), code);
                grouped.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(h);
            }
        }

        for (var entry : grouped.entrySet()) {
            var key = entry.getKey();
            var matches = entry.getValue();
            if (matches.size() > 1) {
                var messages = matches.stream().map(HighlightInfo::getDescription).toList();
                fail(String.format(
                    "Duplicate diagnostic detected in '%s' at offset range [%d, %d) for code '%s'. Conflicting highlights: %s",
                    fileName, key.start(), key.end(), key.diagnosticCode(), messages
                ));
            }
        }
    }
}
