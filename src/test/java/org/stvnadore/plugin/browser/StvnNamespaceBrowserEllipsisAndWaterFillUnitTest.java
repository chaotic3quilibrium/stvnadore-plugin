package org.stvnadore.plugin.browser;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.stvnadore.plugin.settings.NamespaceEllipsisMode;

import java.awt.Canvas;
import java.awt.Font;
import java.awt.FontMetrics;
import java.util.Arrays;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java headless {@code :unitTest} suite verifying {@link StvnCellTextEllipsizer},
 * {@link StvnColumnWaterFillSolver}, and {@link NamespaceEllipsisMode} without the IntelliJ sandbox.
 */
@NullMarked
public class StvnNamespaceBrowserEllipsisAndWaterFillUnitTest {

    private static final ToIntFunction<String> FIXED_7PX_PER_CP =
        s -> s.codePointCount(0, s.length()) * 7;

    @Test
    void testMiddleEllipsisPreserves12CharTailAndMaximizesLeftPrefixOnPreludeAndStage6Strings() {
        String docMeta = ":org/stvnadore/prelude/meta/StvnDocumentMeta";
        // Allow exactly 24 code points (24 * 7 = 168px): 11 prefix + 1 ellipsis + 12 suffix ("DocumentMeta")
        var resMeta = StvnCellTextEllipsizer.ellipsize(docMeta, 24 * 7, FIXED_7PX_PER_CP, NamespaceEllipsisMode.MIDDLE, 12);
        assertTrue(resMeta.truncated());
        assertEquals(":org/stvnad\u2026DocumentMeta", resMeta.displayText());
        assertFalse(resMeta.displayText().contains("..."));

        String stage6Source = "v11_stage06_wavelength_grid.subsea_telemetry.stvn";
        // Allow 24 code points: 11 prefix ("v11_stage06") + "…" + 12 suffix ("lemetry.stvn")
        var resSource = StvnCellTextEllipsizer.ellipsize(stage6Source, 24 * 7, FIXED_7PX_PER_CP, NamespaceEllipsisMode.MIDDLE, 12);
        assertTrue(resSource.truncated());
        assertEquals("v11_stage06\u2026lemetry.stvn", resSource.displayText());

        String semVerType = "{ #minSize 5 #maxSize 128 #regex \"^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
            + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
            + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$\" } :String";
        var resSemVer12 = StvnCellTextEllipsizer.ellipsize(semVerType, 56 * 7, FIXED_7PX_PER_CP, NamespaceEllipsisMode.MIDDLE, 12);
        assertTrue(resSemVer12.truncated());
        assertEquals("{ #minSize 5 #maxSize 128 #regex \"^(0|[1-9]\u2026$\" } :String", resSemVer12.displayText());
        assertEquals(56 * 7, FIXED_7PX_PER_CP.applyAsInt(resSemVer12.displayText()));

        // Untruncated fast path when full width fits
        int fullMetaWidth = FIXED_7PX_PER_CP.applyAsInt(docMeta);
        var resFull = StvnCellTextEllipsizer.ellipsize(docMeta, fullMetaWidth, FIXED_7PX_PER_CP, NamespaceEllipsisMode.MIDDLE, 12);
        assertFalse(resFull.truncated());
        assertEquals(docMeta, resFull.displayText());

        var resEmpty = StvnCellTextEllipsizer.ellipsize("", 50, FIXED_7PX_PER_CP, NamespaceEllipsisMode.MIDDLE, 12);
        assertFalse(resEmpty.truncated());
        assertEquals("", resEmpty.displayText());
    }

    @Test
    void testNarrowDragGracefulDegradationAndExtremeMinimumNeverClipsOrExceedsBudget() {
        String sample = ":org/stvnadore/prelude/meta/StvnDocumentMeta";
        FontMetrics awtFm = new Canvas().getFontMetrics(new Font(Font.DIALOG, Font.PLAIN, 12));

        for (ToIntFunction<String> measurer : Arrays.asList(FIXED_7PX_PER_CP, awtFm::stringWidth)) {
            int fullW = measurer.applyAsInt(sample);
            int ellipsisW = measurer.applyAsInt(StvnCellTextEllipsizer.ELLIPSIS);

            for (int budget = fullW + 10; budget >= 0; budget--) {
                var res = StvnCellTextEllipsizer.ellipsize(sample, budget, measurer, NamespaceEllipsisMode.MIDDLE, 12);
                assertFalse(res.displayText().contains("..."), "Must never emit 3-dot ASCII '...' at budget=" + budget);
                if (budget >= fullW) {
                    assertFalse(res.truncated());
                    assertEquals(sample, res.displayText());
                } else {
                    assertTrue(res.truncated());
                    if (budget == 0) {
                        assertEquals("", res.displayText());
                    } else if (budget <= ellipsisW) {
                        assertEquals("\u2026", res.displayText());
                    } else {
                        assertTrue(
                            measurer.applyAsInt(res.displayText()) <= budget,
                            "Display width (" + measurer.applyAsInt(res.displayText())
                                + ") exceeded budget (" + budget + ") for '" + res.displayText() + "'"
                        );
                        assertTrue(res.displayText().contains("\u2026"));
                    }
                }
            }
        }

        // Verify explicit Stage B (4-cp prefix floor ":org" preserved when 4 + "…" + 1..11 fits)
        // Budget = 10 code points (70px): ":org" (4) + "…" (1) + "ntMeta" (5? wait: 4+1+5 = 10 -> "tMeta" is 5!)
        var stageB = StvnCellTextEllipsizer.ellipsize(sample, 10 * 7, FIXED_7PX_PER_CP, NamespaceEllipsisMode.MIDDLE, 12);
        assertEquals(":org\u2026tMeta", stageB.displayText());

        // Verify explicit Stage C (ultra-narrow 4 code points = 28px -> 2 prefix + "…" + 1 suffix = ":o…a")
        var stageC = StvnCellTextEllipsizer.ellipsize(sample, 4 * 7, FIXED_7PX_PER_CP, NamespaceEllipsisMode.MIDDLE, 12);
        assertEquals(":o\u2026a", stageC.displayText());

        // Extreme minimum 2 code points (14px < 3*7=21px) -> "…"
        var extremeMin = StvnCellTextEllipsizer.ellipsize(sample, 2 * 7, FIXED_7PX_PER_CP, NamespaceEllipsisMode.MIDDLE, 12);
        assertEquals("\u2026", extremeMin.displayText());
    }

    @Test
    void testTrailingEllipsisAndConfigurableSuffixClamping() {
        String sample = ":org/stvnadore/prelude/meta/StvnDocumentMeta";

        var trailWide = StvnCellTextEllipsizer.ellipsize(sample, 15 * 7, FIXED_7PX_PER_CP, NamespaceEllipsisMode.TRAILING, 12);
        assertTrue(trailWide.truncated());
        assertEquals(":org/stvnadore\u2026", trailWide.displayText());

        var trailMinOne = StvnCellTextEllipsizer.ellipsize(sample, 2 * 7, FIXED_7PX_PER_CP, NamespaceEllipsisMode.TRAILING, 12);
        assertTrue(trailMinOne.truncated());
        assertEquals(":\u2026", trailMinOne.displayText());

        var trailExtreme = StvnCellTextEllipsizer.ellipsize(sample, 1 * 7, FIXED_7PX_PER_CP, NamespaceEllipsisMode.TRAILING, 12);
        assertTrue(trailExtreme.truncated());
        assertEquals("\u2026", trailExtreme.displayText());

        // Suffix clamping: suffixCodePoints = -5 clamps to 4 ("Meta")
        var clampedMin = StvnCellTextEllipsizer.ellipsize(sample, 12 * 7, FIXED_7PX_PER_CP, NamespaceEllipsisMode.MIDDLE, -5);
        assertEquals(":org/st\u2026Meta", clampedMin.displayText());

        // Suffix clamping: suffixCodePoints = 999 clamps to 32 ("ore/prelude/meta/StvnDocumentMeta" -> 32 code points)
        var clampedMax = StvnCellTextEllipsizer.ellipsize(sample, 38 * 7, FIXED_7PX_PER_CP, NamespaceEllipsisMode.MIDDLE, 999);
        assertTrue(clampedMax.truncated());
        assertEquals(38, clampedMax.displayText().codePointCount(0, clampedMax.displayText().length()));

        // Verify NamespaceEllipsisMode metadata without IntelliJ bundle runtime
        assertEquals("stvn.settings.browser.ellipsis.middle", NamespaceEllipsisMode.MIDDLE.getBundleKey());
        assertEquals("stvn.settings.browser.ellipsis.trailing", NamespaceEllipsisMode.TRAILING.getBundleKey());
        assertEquals("Middle \u2014 Preserve Prefix & Suffix (Default)", NamespaceEllipsisMode.MIDDLE.toString());
        assertEquals("Trailing \u2014 Preserve Prefix Only", NamespaceEllipsisMode.TRAILING.toString());
    }

    @Test
    void testUnicodeSupplementarySurrogatePairsNeverSplitAcrossPrefixOrSuffix() {
        // Every emoji / math symbol below is a 2-char UTF-16 surrogate pair (U+10000..U+10FFFF)
        String surrogateRich = ":\uD83D\uDE80\uD83E\uDDEA\uD835\uDD38\uD83C\uDF89_mid_\uD83D\uDE80\uD83E\uDDEA"
            + "\uD835\uDD38\uD83C\uDF89\uD83D\uDE80\uD83E\uDDEA\uD835\uDD38\uD83C\uDF89\uD83D\uDE80\uD83E\uDDEA"
            + "\uD835\uDD38\uD83C\uDF89";
        int maxW = FIXED_7PX_PER_CP.applyAsInt(surrogateRich);

        for (NamespaceEllipsisMode mode : NamespaceEllipsisMode.values()) {
            for (int suffixLen : new int[]{4, 8, 12, 20}) {
                for (int budget = 0; budget <= maxW + 7; budget += 3) {
                    var res = StvnCellTextEllipsizer.ellipsize(surrogateRich, budget, FIXED_7PX_PER_CP, mode, suffixLen);
                    assertZeroUnpairedSurrogates(res.displayText());
                }
            }
        }
    }

    @Test
    void testWaterFillSolverSingleWideMultiWideLockedAndExtremeNarrowBudgets() {
        int[] natural = {310, 330, 190, 1680, 102};
        boolean[] defaultLocked = {false, false, false, false, true};
        int[] floors = {65, 72, 115, 105, 102};

        // Case 1: Budget >= sum(natural) (2612) -> 100% unconstrained natural widths
        int[] unconstrained = StvnColumnWaterFillSolver.solveFittedColumnWidths(natural, defaultLocked, floors, 56, 2800);
        assertArrayEquals(natural, unconstrained);

        // Case 2A: Single wide column (:SemVer Resolved Type = 1680) on 1820px budget -> cols 0..2 untouched, col 3 = 888, sum == 1820
        int[] singleCapped = StvnColumnWaterFillSolver.solveFittedColumnWidths(natural, defaultLocked, floors, 56, 1820);
        assertArrayEquals(new int[]{310, 330, 190, 888, 102}, singleCapped);
        assertEquals(1820, Arrays.stream(singleCapped).sum());

        // Case 2B: Multi-column wide overflow with +1px remainder distribution on odd budget (1251px)
        int[] twoWide = {600, 180, 220, 900, 100};
        int[] multiCapped = StvnColumnWaterFillSolver.solveFittedColumnWidths(twoWide, defaultLocked, floors, 56, 1251);
        // Unlocked budget = 1151; cols 1 (180) and 2 (220) keep 100%; remaining 751 split across col 3 (900 -> 376) and col 0 (600 -> 375)
        assertArrayEquals(new int[]{375, 180, 220, 376, 100}, multiCapped);
        assertEquals(1251, Arrays.stream(multiCapped).sum());

        // Case 2C: User also locks Column 0 at 600px -> locked columns 0 and 4 stay untouched, remaining budget fills 1..3
        boolean[] twoLocked = {true, false, false, false, true};
        int[] withUserLock = StvnColumnWaterFillSolver.solveFittedColumnWidths(twoWide, twoLocked, floors, 56, 1400);
        assertEquals(600, withUserLock[0]);
        assertEquals(100, withUserLock[4]);
        assertEquals(180, withUserLock[1]);
        assertEquals(220, withUserLock[2]);
        assertEquals(300, withUserLock[3]);
        assertEquals(1400, Arrays.stream(withUserLock).sum());

        // Case 2D: Locked columns alone exceed budget -> unlocked columns clamp safely at hardMinUnlockedWidth (56px)
        boolean[] fourLocked = {true, true, true, false, true};
        int[] lockedOverflow = StvnColumnWaterFillSolver.solveFittedColumnWidths(twoWide, fourLocked, floors, 56, 900);
        assertEquals(56, lockedOverflow[3]);
        assertEquals(600, lockedOverflow[0]);
        assertEquals(100, lockedOverflow[4]);
    }

    private static void assertZeroUnpairedSurrogates(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                assertTrue(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)),
                    "High surrogate at index " + i + " was split from its low surrogate in: " + text);
                i++;
            } else {
                assertFalse(Character.isLowSurrogate(ch),
                    "Orphan low surrogate at index " + i + " in: " + text);
            }
        }
    }
}
