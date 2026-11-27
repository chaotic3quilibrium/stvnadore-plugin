package org.stvnadore.plugin.browser;

import org.jspecify.annotations.NullMarked;

import java.util.Arrays;

/**
 * Pure-Java exact integer bounded water-filling width solver for {@code Fit Window to Rows & Columns}
 * ({@code Alt+Shift+R} and {@code Alt+R} Stage 2).
 */
@NullMarked
public final class StvnColumnWaterFillSolver {

    private StvnColumnWaterFillSolver() {
    }

    /**
     * Computes fitted column pixel widths across locked and unlocked columns for {@code maxColsBudget}.
     * <ul>
     *   <li>When {@code sum(naturalWidths) <= maxColsBudget} (or zero columns are unlocked), returns an exact
     *       copy of {@code naturalWidths}.</li>
     *   <li>When {@code sum(naturalWidths) > maxColsBudget} and {@code unlockedBudget >= unlockedCount * hardMinUnlockedWidth},
     *       applies bounded integer water-filling so unlocked columns narrower than the uniform cap {@code C} keep
     *       {@code 100%} of their natural width while wider unlocked columns share the cap {@code C} cleanly such
     *       that {@code sum(fitted) == maxColsBudget}.</li>
     * </ul>
     *
     * @param naturalWidths        unconstrained natural widths for unlocked columns and locked widths for locked columns
     * @param locked               per-column width-lock flags
     * @param minFloors            per-column preferred minimum header floors for unlocked columns
     * @param hardMinUnlockedWidth absolute minimum pixel floor for any unlocked column (for example {@code 56px})
     * @param maxColsBudget        maximum total horizontal viewport pixel budget across all columns
     * @return fitted per-column pixel widths
     */
    public static int[] solveFittedColumnWidths(
        int[] naturalWidths,
        boolean[] locked,
        int[] minFloors,
        int hardMinUnlockedWidth,
        int maxColsBudget
    ) {
        int n = naturalWidths.length;
        int[] result = Arrays.copyOf(naturalWidths, n);
        int totalNatural = 0;
        int lockedSum = 0;
        int unlockedCount = 0;
        for (int c = 0; c < n; c++) {
            totalNatural += naturalWidths[c];
            if (locked[c]) {
                lockedSum += naturalWidths[c];
            } else {
                unlockedCount++;
            }
        }
        if (unlockedCount == 0 || totalNatural <= maxColsBudget) {
            return result;
        }

        int safeHardMin = Math.max(1, hardMinUnlockedWidth);
        int unlockedBudget = maxColsBudget - lockedSum;
        int[] uCols = new int[unlockedCount];
        int uIdx = 0;
        int headerFloorSum = 0;
        int hardMinSum = 0;
        for (int c = 0; c < n; c++) {
            if (!locked[c]) {
                uCols[uIdx++] = c;
                headerFloorSum += Math.min(naturalWidths[c], Math.max(safeHardMin, minFloors[c]));
                hardMinSum += Math.min(naturalWidths[c], safeHardMin);
            }
        }

        if (unlockedBudget < hardMinSum) {
            for (int c : uCols) {
                result[c] = Math.min(naturalWidths[c], safeHardMin);
            }
            return result;
        }

        boolean honorHeaderFloors = unlockedBudget >= headerFloorSum;
        int[] effectiveFloors = new int[n];
        for (int c : uCols) {
            effectiveFloors[c] = honorHeaderFloors
                ? Math.min(naturalWidths[c], Math.max(safeHardMin, minFloors[c]))
                : Math.min(naturalWidths[c], safeHardMin);
        }

        boolean[] fixed = new boolean[n];
        int remainingBudget = unlockedBudget;
        int remainingCount = unlockedCount;

        while (remainingCount > 0) {
            int baseCap = remainingBudget / remainingCount;
            boolean settledAny = false;

            for (int c : uCols) {
                if (!fixed[c] && effectiveFloors[c] > baseCap) {
                    result[c] = effectiveFloors[c];
                    fixed[c] = true;
                    remainingBudget -= result[c];
                    remainingCount--;
                    settledAny = true;
                }
            }
            if (settledAny) {
                continue;
            }

            for (int c : uCols) {
                if (!fixed[c] && naturalWidths[c] <= baseCap) {
                    result[c] = naturalWidths[c];
                    fixed[c] = true;
                    remainingBudget -= result[c];
                    remainingCount--;
                    settledAny = true;
                }
            }
            if (settledAny) {
                continue;
            }

            int remainder = remainingBudget - (baseCap * remainingCount);
            Integer[] activeOrder = new Integer[remainingCount];
            int idx = 0;
            for (int c : uCols) {
                if (!fixed[c]) {
                    activeOrder[idx++] = c;
                }
            }
            Arrays.sort(activeOrder, (a, b) -> {
                int cmp = Integer.compare(naturalWidths[b], naturalWidths[a]);
                return cmp != 0 ? cmp : Integer.compare(a, b);
            });
            for (int i = 0; i < activeOrder.length; i++) {
                int colIdx = activeOrder[i];
                int candidate = baseCap + (i < remainder ? 1 : 0);
                result[colIdx] = Math.min(naturalWidths[colIdx], candidate);
                fixed[colIdx] = true;
            }
            break;
        }

        return result;
    }
}
