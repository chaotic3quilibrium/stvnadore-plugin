package org.stvnadore.plugin.browser;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.settings.NamespaceEllipsisMode;

import java.util.function.ToIntFunction;

/**
 * Pure-Java, Unicode-code-point-safe Finder-style middle and trailing single-glyph ellipsis
 * ({@code U+2026}) formatter for {@code STVN Namespace Dependencies} table cells.
 */
@NullMarked
public final class StvnCellTextEllipsizer {

    /** Single-glyph horizontal ellipsis ({@code U+2026}). */
    public static final String ELLIPSIS = "\u2026";

    /** Minimum prefix length in Unicode code points preserved in standard {@code MIDDLE} mode when width permits. */
    public static final int MIN_STANDARD_PREFIX_CODE_POINTS = 4;

    /** Default trailing suffix length in Unicode code points preserved in {@code MIDDLE} mode. */
    public static final int DEFAULT_SUFFIX_CODE_POINTS = 12;

    /** Minimum configurable suffix length in Unicode code points. */
    public static final int MIN_SUFFIX_CODE_POINTS = 4;

    /** Maximum configurable suffix length in Unicode code points. */
    public static final int MAX_SUFFIX_CODE_POINTS = 32;

    private StvnCellTextEllipsizer() {
    }

    /**
     * Immutable cell truncation outcome pairing the fitted display string with its truncation flag.
     *
     * @param displayText fitted string guaranteed to split zero UTF-16 surrogate pairs
     * @param truncated   {@code true} whenever {@code fullText} exceeded {@code availableTextWidth}
     */
    public record EllipsisResult(String displayText, boolean truncated) {
    }

    /**
     * Formats {@code fullText} to fit within {@code availableTextWidth} pixels according to {@code mode}
     * and {@code suffixCodePoints}, slicing strictly on Unicode Scalar Value boundaries.
     *
     * @param fullText           untruncated cell string
     * @param availableTextWidth interior cell width in pixels after subtracting horizontal border insets
     * @param widthMeasurer      pixel width function (for example {@code FontMetrics::stringWidth})
     * @param mode               {@link NamespaceEllipsisMode#MIDDLE} or {@link NamespaceEllipsisMode#TRAILING}
     * @param suffixCodePoints   desired trailing code points preserved in {@code MIDDLE} mode (clamped to {@code [4, 32]})
     * @return fitted {@link EllipsisResult}
     */
    public static EllipsisResult ellipsize(
        String fullText,
        int availableTextWidth,
        ToIntFunction<String> widthMeasurer,
        NamespaceEllipsisMode mode,
        int suffixCodePoints
    ) {
        if (fullText.isEmpty()) {
            return new EllipsisResult("", false);
        }
        int fullWidth = widthMeasurer.applyAsInt(fullText);
        if (fullWidth <= availableTextWidth) {
            return new EllipsisResult(fullText, false);
        }
        if (availableTextWidth <= 0) {
            return new EllipsisResult("", true);
        }
        int ellipsisWidth = widthMeasurer.applyAsInt(ELLIPSIS);
        if (availableTextWidth <= ellipsisWidth) {
            return new EllipsisResult(ELLIPSIS, true);
        }

        int[] cpOffsets = buildCodePointOffsets(fullText);
        int totalCp = cpOffsets.length - 1;
        if (totalCp <= 1) {
            return new EllipsisResult(ELLIPSIS, true);
        }

        if (mode == NamespaceEllipsisMode.TRAILING) {
            return ellipsizeTrailing(fullText, cpOffsets, totalCp, availableTextWidth, widthMeasurer);
        }
        int clampedSuffix = Math.clamp(suffixCodePoints, MIN_SUFFIX_CODE_POINTS, MAX_SUFFIX_CODE_POINTS);
        return ellipsizeMiddle(fullText, cpOffsets, totalCp, availableTextWidth, widthMeasurer, clampedSuffix);
    }

    private static EllipsisResult ellipsizeTrailing(
        String fullText,
        int[] cpOffsets,
        int totalCp,
        int availableTextWidth,
        ToIntFunction<String> widthMeasurer
    ) {
        String minCandidate = slicePrefix(fullText, cpOffsets, 1) + ELLIPSIS;
        if (widthMeasurer.applyAsInt(minCandidate) > availableTextWidth) {
            return new EllipsisResult(ELLIPSIS, true);
        }
        int lo = 1;
        int hi = totalCp - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            String candidate = slicePrefix(fullText, cpOffsets, mid) + ELLIPSIS;
            if (widthMeasurer.applyAsInt(candidate) <= availableTextWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return new EllipsisResult(slicePrefix(fullText, cpOffsets, lo) + ELLIPSIS, true);
    }

    private static EllipsisResult ellipsizeMiddle(
        String fullText,
        int[] cpOffsets,
        int totalCp,
        int availableTextWidth,
        ToIntFunction<String> widthMeasurer,
        int clampedSuffix
    ) {
        // Stage A: Standard Finder-style tail-preserving fill (prefix >= 4 + "…" + full target suffix)
        if (totalCp > MIN_STANDARD_PREFIX_CODE_POINTS + 1) {
            int targetS = Math.min(clampedSuffix, totalCp - MIN_STANDARD_PREFIX_CODE_POINTS - 1);
            String fixedSuffix = sliceSuffix(fullText, cpOffsets, targetS);
            String minPrefix = slicePrefix(fullText, cpOffsets, MIN_STANDARD_PREFIX_CODE_POINTS);
            if (widthMeasurer.applyAsInt(minPrefix + ELLIPSIS + fixedSuffix) <= availableTextWidth) {
                int lo = MIN_STANDARD_PREFIX_CODE_POINTS;
                int hi = totalCp - targetS - 1;
                while (lo < hi) {
                    int mid = (lo + hi + 1) >>> 1;
                    String candidate = slicePrefix(fullText, cpOffsets, mid) + ELLIPSIS + fixedSuffix;
                    if (widthMeasurer.applyAsInt(candidate) <= availableTextWidth) {
                        lo = mid;
                    } else {
                        hi = mid - 1;
                    }
                }
                return new EllipsisResult(slicePrefix(fullText, cpOffsets, lo) + ELLIPSIS + fixedSuffix, true);
            }

            // Stage B: Narrow-drag suffix degradation keeping 4-code-point prefix floor when 4 + "…" + 1 fits
            String oneCpSuffix = sliceSuffix(fullText, cpOffsets, 1);
            if (widthMeasurer.applyAsInt(minPrefix + ELLIPSIS + oneCpSuffix) <= availableTextWidth) {
                int loS = 1;
                int hiS = Math.max(1, targetS - 1);
                while (loS < hiS) {
                    int midS = (loS + hiS + 1) >>> 1;
                    String candidate = minPrefix + ELLIPSIS + sliceSuffix(fullText, cpOffsets, midS);
                    if (widthMeasurer.applyAsInt(candidate) <= availableTextWidth) {
                        loS = midS;
                    } else {
                        hiS = midS - 1;
                    }
                }
                String chosenSuffix = sliceSuffix(fullText, cpOffsets, loS);
                int bestP = MIN_STANDARD_PREFIX_CODE_POINTS;
                while (bestP + loS < totalCp - 1) {
                    String widerPrefix = slicePrefix(fullText, cpOffsets, bestP + 1) + ELLIPSIS + chosenSuffix;
                    if (widthMeasurer.applyAsInt(widerPrefix) <= availableTextWidth) {
                        bestP++;
                    } else {
                        break;
                    }
                }
                return new EllipsisResult(slicePrefix(fullText, cpOffsets, bestP) + ELLIPSIS + chosenSuffix, true);
            }
        }

        // Stage C: Ultra-narrow graceful degradation (prefix(p >= 1) + "…" + suffix(s >= 1), or "…" at minimum)
        if (totalCp >= 3) {
            String minBoth = slicePrefix(fullText, cpOffsets, 1) + ELLIPSIS + sliceSuffix(fullText, cpOffsets, 1);
            if (widthMeasurer.applyAsInt(minBoth) <= availableTextWidth) {
                int p = 1;
                int s = 1;
                while (p + s < totalCp - 1) {
                    boolean advanced = false;
                    if (p < MIN_STANDARD_PREFIX_CODE_POINTS) {
                        String tryPrefix = slicePrefix(fullText, cpOffsets, p + 1)
                            + ELLIPSIS
                            + sliceSuffix(fullText, cpOffsets, s);
                        if (widthMeasurer.applyAsInt(tryPrefix) <= availableTextWidth) {
                            p++;
                            advanced = true;
                        }
                    }
                    if (p + s < totalCp - 1 && s < clampedSuffix) {
                        String trySuffix = slicePrefix(fullText, cpOffsets, p)
                            + ELLIPSIS
                            + sliceSuffix(fullText, cpOffsets, s + 1);
                        if (widthMeasurer.applyAsInt(trySuffix) <= availableTextWidth) {
                            s++;
                            advanced = true;
                        }
                    }
                    if (!advanced) {
                        break;
                    }
                }
                return new EllipsisResult(
                    slicePrefix(fullText, cpOffsets, p) + ELLIPSIS + sliceSuffix(fullText, cpOffsets, s),
                    true
                );
            }
        }

        return new EllipsisResult(ELLIPSIS, true);
    }

    static int[] buildCodePointOffsets(String text) {
        int cpCount = text.codePointCount(0, text.length());
        int[] offsets = new int[cpCount + 1];
        int charIdx = 0;
        for (int i = 0; i < cpCount; i++) {
            offsets[i] = charIdx;
            charIdx += Character.charCount(text.codePointAt(charIdx));
        }
        offsets[cpCount] = text.length();
        return offsets;
    }

    private static String slicePrefix(String text, int[] cpOffsets, int prefixCodePoints) {
        return text.substring(0, cpOffsets[prefixCodePoints]);
    }

    private static String sliceSuffix(String text, int[] cpOffsets, int suffixCodePoints) {
        int totalCp = cpOffsets.length - 1;
        return text.substring(cpOffsets[totalCp - suffixCodePoints]);
    }
}
