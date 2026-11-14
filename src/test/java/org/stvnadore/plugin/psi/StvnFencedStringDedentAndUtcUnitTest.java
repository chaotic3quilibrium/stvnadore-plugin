package org.stvnadore.plugin.psi;

import junit.framework.TestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.documentation.StvnTimeEpochFormatter;
import org.stvnadore.plugin.settings.StvnSettings;

import java.time.Instant;

/**
 * Pure-Java headless unit test suite verifying {@code Rule STR-04} fenced string decoding,
 * ASCII-space-only {@code trimCommonIndent} (preserving verbatim {@code \t} and trailing newlines),
 * canonical double-quoted string formatting, and {@link StvnTimeEpochFormatter#formatInstantHumanUtc}.
 */
@NullMarked
public final class StvnFencedStringDedentAndUtcUnitTest extends TestCase {

    public void testSingleLineInlineClosedFencedRegexDedentsCommonMargin() {
        String raw = "\"\"\"[REGEX]\n        ^[a-z0-9]+$[REGEX]\"\"\"";
        assertEquals("^[a-z0-9]+$", StvnPsiLiteralEvaluator.decodeStringLiteral(raw, false).orElseThrow());
        assertEquals("        ^[a-z0-9]+$", StvnPsiLiteralEvaluator.decodeStringLiteral(raw, true).orElseThrow());
    }

    public void testMultiLineFencedRegexAndPreludeEmailPatternDedent() {
        String rawEmailStyle = """
            \"\"\"[REGEX]
                    ^(?=.{1,64}@)[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*@
                    [^-][a-z0-9-]+(?:\\.[a-z0-9-]+)*(?:\\.[a-z]{2,})$[REGEX]\"\"\"""";
        String expectedDedented = "^(?=.{1,64}@)[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*@\n"
            + "[^-][a-z0-9-]+(?:\\.[a-z0-9-]+)*(?:\\.[a-z]{2,})$";
        assertEquals(expectedDedented, StvnPsiLiteralEvaluator.decodeStringLiteral(rawEmailStyle, false).orElseThrow());
    }

    public void testTrimCommonIndentCountsAsciiSpaceOnlyAndPreservesTabsVerbatim() {
        // Tab at column 0 stops margin calculation at 0 and remains intact
        String colZeroTab = "\tmake build\n    echo done";
        assertEquals("\tmake build\n    echo done", StvnPsiLiteralEvaluator.trimCommonIndent(colZeroTab));

        // Tab after 8 ASCII spaces strips the 8 spaces and preserves the leading tab
        String rawFencedTsv = "\"\"\"[TSV]\n        \tcpu\t95[TSV]\"\"\"";
        assertEquals("\tcpu\t95", StvnPsiLiteralEvaluator.decodeStringLiteral(rawFencedTsv, false).orElseThrow());
    }

    public void testTrailingNewlineBeforeClosingFenceIsPreserved() {
        String raw = "\"\"\"[RAW]\n  alpha\n  42\n[RAW]\"\"\"";
        assertEquals("alpha\n42\n", StvnPsiLiteralEvaluator.decodeStringLiteral(raw, false).orElseThrow());
        assertEquals("  alpha\n  42\n", StvnPsiLiteralEvaluator.decodeStringLiteral(raw, true).orElseThrow());
    }

    public void testCrlfAndBareCarriageReturnNormalization() {
        String rawCrlf = "\"\"\"[REGEX]\r\n    ^alpha$\r\n    ^beta$[REGEX]\"\"\"";
        assertEquals("^alpha$\n^beta$", StvnPsiLiteralEvaluator.decodeStringLiteral(rawCrlf, false).orElseThrow());

        String rawCr = "\"\"\"[REGEX]\r    ^gamma$[REGEX]\"\"\"";
        assertEquals("^gamma$", StvnPsiLiteralEvaluator.decodeStringLiteral(rawCr, false).orElseThrow());
    }

    public void testFormatCanonicalQuotedStringEscapesControlAndQuoteCharacters() {
        assertEquals("\"^[a-z]+\\\\.[0-9]+$\"", StvnPsiLiteralEvaluator.formatCanonicalQuotedString("^[a-z]+\\.[0-9]+$"));
        assertEquals("\"line1\\n\\t\\\"quoted\\\"\\u0007\"",
            StvnPsiLiteralEvaluator.formatCanonicalQuotedString("line1\n\t\"quoted\"\u0007"));
    }

    public void testFormatInstantHumanUtcSupports12Hour24HourAndCanonicalSubsecondDigits() {
        Instant baseSec = Instant.parse("2026-11-12T17:30:00Z");
        assertEquals(
            "Thursday, November 12, 2026, at 5:30:00 PM UTC",
            StvnTimeEpochFormatter.formatInstantHumanUtc(baseSec, StvnSettings.TimeEpochHourFormat.HOUR_12)
        );
        assertEquals(
            "Thursday, November 12, 2026, at 17:30:00 UTC",
            StvnTimeEpochFormatter.formatInstantHumanUtc(baseSec, StvnSettings.TimeEpochHourFormat.HOUR_24)
        );

        Instant millis = Instant.parse("2026-11-12T17:30:00.500Z");
        assertEquals(
            "Thursday, November 12, 2026, at 17:30:00.500 UTC",
            StvnTimeEpochFormatter.formatInstantHumanUtc(millis, StvnSettings.TimeEpochHourFormat.HOUR_24)
        );

        Instant micros = Instant.parse("2026-11-12T17:30:00.123456Z");
        assertEquals(
            "Thursday, November 12, 2026, at 17:30:00.123456 UTC",
            StvnTimeEpochFormatter.formatInstantHumanUtc(micros, StvnSettings.TimeEpochHourFormat.HOUR_24)
        );

        Instant nanos = Instant.parse("2026-11-12T17:30:00.123456789Z");
        assertEquals(
            "Thursday, November 12, 2026, at 5:30:00.123456789 PM UTC",
            StvnTimeEpochFormatter.formatInstantHumanUtc(nanos, StvnSettings.TimeEpochHourFormat.HOUR_12)
        );
    }
}
