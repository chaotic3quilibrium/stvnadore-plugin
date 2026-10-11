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
            "Declared #domain 'place_holder' is omitted from filename stem slot 1 [stvn-plugin:WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME]",
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
            "Missing mandatory :meta block [stvn-core:ERR_DOCUMENT_KIND_MISMATCH]",
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
            "Definitions schema requires root :meta block [stvn-plugin:ERR_DOCUMENT_KIND_MISMATCH]",
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
            "Tab characters are forbidden under Rule STR-01 [stvn-plugin:ERR_TAB_CHARACTER_FORBIDDEN]",
            result
        );
    }

    @Test
    public void testFormatIdempotentGuard() {
        var formatted = "Already formatted message [stvn-plugin:ERR_FOO]";
        var result = StvnDiagnosticFormatter.formatPlugin(formatted, "ERR_BAR");
        assertEquals(formatted, result);

        var coreFormatted = "Already core formatted [stvn-core:ERR_CORE]";
        var coreResult = StvnDiagnosticFormatter.formatCore(coreFormatted, "ERR_ANOTHER");
        assertEquals(coreFormatted, coreResult);
    }

    @Test
    public void testFormatNullAndBlank() {
        assertEquals("[stvn-plugin:ERR_CODE]", StvnDiagnosticFormatter.formatPlugin(null, "ERR_CODE"));
        assertEquals("[stvn-plugin:ERR_CODE]", StvnDiagnosticFormatter.formatPlugin("   ", "ERR_CODE"));
        assertEquals("[stvn-core:ERR_CODE]", StvnDiagnosticFormatter.formatCore(null, "ERR_CODE"));
    }
}
