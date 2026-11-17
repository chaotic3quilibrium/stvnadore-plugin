package org.stvnadore.plugin.psi;

import junit.framework.TestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.actions.StvnProjectionStemUtil;
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

    public void testDecodeStringLiteralSupportsSolidusEscapeAndRejectsMalformedEscapesOrControlChars() {
        assertEquals(
            "https://example.com/api",
            StvnPsiLiteralEvaluator.decodeStringLiteral("\"https:\\/\\/example.com\\/api\"", false).orElseThrow()
        );
        assertEquals(
            "A\tB\n\"C\"\\D",
            StvnPsiLiteralEvaluator.decodeStringLiteral("\"\\u0041\\tB\\n\\\"C\\\"\\\\D\"", false).orElseThrow()
        );
        assertTrue(StvnPsiLiteralEvaluator.decodeStringLiteral("\"bad\\qescape\"", false).isEmpty());
        assertTrue(StvnPsiLiteralEvaluator.decodeStringLiteral("\"bad\\u12G4\"", false).isEmpty());
        assertTrue(StvnPsiLiteralEvaluator.decodeStringLiteral("\"raw\nnewline\"", false).isEmpty());
    }

    public void testShouldEmitFencedPrettySoundnessGuardsAndSafeFenceTags() {
        assertTrue(StvnPsiLiteralEvaluator.shouldEmitFencedPretty("^[a-z]+\\.[0-9]+$", false));
        assertTrue(StvnPsiLiteralEvaluator.shouldEmitFencedPretty("line1\nline2", false));
        assertTrue(StvnPsiLiteralEvaluator.shouldEmitFencedPretty("\t$(CC) -o app", false));
        assertTrue(StvnPsiLiteralEvaluator.shouldEmitFencedPretty("say \"hello\"", false));

        assertFalse(StvnPsiLiteralEvaluator.shouldEmitFencedPretty("plain_token", false));
        assertFalse(StvnPsiLiteralEvaluator.shouldEmitFencedPretty("line1\r\nline2", false));
        assertFalse(StvnPsiLiteralEvaluator.shouldEmitFencedPretty("bell\u0007\nnext", false));
        assertFalse(StvnPsiLiteralEvaluator.shouldEmitFencedPretty(" leading\\space", false));
        assertFalse(StvnPsiLiteralEvaluator.shouldEmitFencedPretty("alpha\n   \nbeta", false));

        assertTrue(StvnPsiLiteralEvaluator.shouldEmitFencedPretty(" leading\\space", true));
        assertTrue(StvnPsiLiteralEvaluator.shouldEmitFencedPretty("alpha\n   \nbeta", true));

        assertEquals("RAW", StvnPsiLiteralEvaluator.chooseSafeFenceTag("clean body", "RAW"));
        assertEquals("RAW_1", StvnPsiLiteralEvaluator.chooseSafeFenceTag("has [RAW]\"\"\" marker", "RAW"));
        assertEquals("RAW_2", StvnPsiLiteralEvaluator.chooseSafeFenceTag("has [RAW]\"\"\" and [RAW_1]\"\"\"", "RAW"));
        assertEquals("REGEX_1", StvnPsiLiteralEvaluator.chooseSafeFenceTag("^[REGEX]\"\"\"$", "REGEX"));

        assertTrue(StvnPsiLiteralEvaluator.isRegexConstantIdentifier("#SLUG_REGEX"));
        assertTrue(StvnPsiLiteralEvaluator.isRegexConstantIdentifier("#EMAILREGEX"));
        assertTrue(StvnPsiLiteralEvaluator.isRegexConstantIdentifier("#acme/net/HOST_REGEX"));
        assertFalse(StvnPsiLiteralEvaluator.isRegexConstantIdentifier("#BANNER_LINE"));
    }

    public void testDeriveDefsStemNameSupportsOneStemAndTwoStemFilenames() {
        assertEquals("foo.stvn_d", StvnProjectionStemUtil.deriveDefsStemName("foo.stvn_i"));
        assertEquals("foo.stvn_d", StvnProjectionStemUtil.deriveDefsStemName("foo.stvn_di"));
        assertEquals("foo.stvn_d", StvnProjectionStemUtil.deriveDefsStemName("foo.stvn"));
        assertEquals(
            "gauntlet_v7_stage10_modular_includes_and_flattening.core_test.stvn_d",
            StvnProjectionStemUtil.deriveDefsStemName(
                "gauntlet_v7_stage10_modular_includes_and_flattening.core_test.stvn_i"
            )
        );
        assertEquals("foo.stvn", StvnProjectionStemUtil.derivePayloadStemName("foo.stvn_i"));
        assertEquals("bar.dom.stvn", StvnProjectionStemUtil.derivePayloadStemName("bar.dom.stvn_i"));
    }
}
