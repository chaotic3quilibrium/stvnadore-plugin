package org.stvnadore.plugin.validation;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Unit tests validating {@link StvnDiagnosticFormatter} attribution formatting and punctuation hygiene.
 */
public final class StvnDiagnosticFormatterTest {

    @Test
    public void testFormatPluginAppendsNominalTag() {
        var result = StvnDiagnosticFormatter.formatPlugin(
            "Declared #domain 'place_holder' is omitted from filename stem slot 1",
            StvnDiagnosticCodes.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME
        );
        assertEquals(
            "Declared #domain 'place_holder' is omitted from filename stem slot 1 [stvn-plugin:W002-WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME]",
            result
        );
    }

    @Test
    public void testFormatCoreAppendsNominalTag() {
        var result = StvnDiagnosticFormatter.formatCore(
            "Missing mandatory :meta block",
            "ERR_DOCUMENT_KIND_MISMATCH"
        );
        assertEquals(
            "Missing mandatory :meta block [stvn-core:E031-ERR_DOCUMENT_KIND_MISMATCH]",
            result
        );
    }

    @Test
    public void testFormatCoreWithExplicitCanonicalCode() {
        var result = StvnDiagnosticFormatter.formatCore(
            "Underscore separators are prohibited in numeric literals: '1_000'",
            "E002",
            "ERR_INVALID_NUMERIC_LITERAL"
        );
        assertEquals(
            "Underscore separators are prohibited in numeric literals: '1_000' [stvn-core:E002-ERR_INVALID_NUMERIC_LITERAL]",
            result
        );
    }

    @Test
    public void testFormatPluginAdvisoryWarningPrefix() {
        var result = StvnDiagnosticFormatter.formatPlugin(
            "Exact decimal float precision 50 exceeds recommended threshold 38",
            StvnDiagnosticCodes.WARN_EXCESSIVE_EXACT_PRECISION
        );
        assertEquals(
            "Exact decimal float precision 50 exceeds recommended threshold 38 [stvn-plugin:PW005-WARN_EXCESSIVE_EXACT_PRECISION]",
            result
        );
    }

    @Test
    public void testFormatStripsTrailingPeriod() {
        var result = StvnDiagnosticFormatter.formatPlugin(
            "Definitions schema requires root :meta block.",
            StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH
        );
        assertEquals(
            "Definitions schema requires root :meta block [stvn-plugin:E031-ERR_DOCUMENT_KIND_MISMATCH]",
            result
        );
    }

    @Test
    public void testFormatStripsTrailingPeriodWithWhitespace() {
        var result = StvnDiagnosticFormatter.formatPlugin(
            "Tab characters are forbidden under Rule STR-01.  ",
            StvnDiagnosticCodes.ERR_TAB_CHARACTER_FORBIDDEN
        );
        assertEquals(
            "Tab characters are forbidden under Rule STR-01 [stvn-plugin:E001-ERR_TAB_CHARACTER_FORBIDDEN]",
            result
        );
    }

    @Test
    public void testFormatIdempotentGuard() {
        var formatted = "Already formatted message [stvn-plugin:E001-ERR_FOO]";
        var result = StvnDiagnosticFormatter.formatPlugin(formatted, "ERR_BAR");
        assertEquals(formatted, result);

        var coreFormatted = "Already core formatted [stvn-core:E001-ERR_CORE]";
        var coreResult = StvnDiagnosticFormatter.formatCore(coreFormatted, "ERR_ANOTHER");
        assertEquals(coreFormatted, coreResult);
    }

    @Test
    public void testFormatNullAndBlank() {
        assertEquals("[stvn-plugin:E002-ERR_CODE]", StvnDiagnosticFormatter.formatPlugin(null, "ERR_CODE"));
        assertEquals("[stvn-plugin:E002-ERR_CODE]", StvnDiagnosticFormatter.formatPlugin("   ", "ERR_CODE"));
        assertEquals("[stvn-core:E002-ERR_CODE]", StvnDiagnosticFormatter.formatCore(null, "ERR_CODE"));
    }
}
