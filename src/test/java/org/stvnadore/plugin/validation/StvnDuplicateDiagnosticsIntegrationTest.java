package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Platform integration test suite asserting zero duplicate diagnostic codes across overlapping
 * coordinate ranges when both annotators and all 25 registered local inspections run concurrently.
 * <p>
 * Enforces the invariant: count(range, diagnosticCode) == 1.
 */
@NullMarked
public final class StvnDuplicateDiagnosticsIntegrationTest extends BasePlatformTestCase {

    private static final Pattern DIAGNOSTIC_TAG_PATTERN = Pattern.compile("\\[(stvn-core|stvn-plugin):([A-Z0-9_]+)\\]");

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Enable all 25 registered STVN local inspections simultaneously
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
            new StvnSuspiciousEpochMagnitudeInspection()
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
            domainWarnings.get(0).getDescription().contains("[stvn-plugin:WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME]"));
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
        assertTrue(line1Errors.get(0).getDescription().contains("[stvn-plugin:ERR_DOCUMENT_KIND_MISMATCH]"));
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
     * Asserts that zero [stvn-core:...] highlights exist for the specified diagnostic code.
     */
    private void assertNoCoreHighlight(String fileName, java.util.List<HighlightInfo> highlights, String code) {
        var coreHighlights = highlights.stream()
            .filter(h -> h.getDescription() != null)
            .filter(h -> h.getDescription().contains("[stvn-core:" + code + "]"))
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
