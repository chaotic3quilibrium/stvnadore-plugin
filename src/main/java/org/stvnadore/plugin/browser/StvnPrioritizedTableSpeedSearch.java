package org.stvnadore.plugin.browser;

import com.intellij.ui.TableSpeedSearch;
import javax.swing.JTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Customized table speed search prioritizing Column 0 ({@code Name}) across all rows
 * before scanning secondary columns ({@code Upstream Target}, {@code Source},
 * {@code Resolved Type}, and {@code Use Depth} on 5-column namespace tables).
 */
@NullMarked
public class StvnPrioritizedTableSpeedSearch extends TableSpeedSearch {

    private static final int[] FIVE_COLUMN_SECONDARY_ORDER = {
        StvnNamespaceTableModel.COL_UPSTREAM_TARGET,
        StvnNamespaceTableModel.COL_SOURCE,
        StvnNamespaceTableModel.COL_RESOLVED_TYPE,
        StvnNamespaceTableModel.COL_USE_DEPTH
    };

    /**
     * Installs prioritized speed search on the specified table component.
     *
     * @param table the target table
     * @return the configured speed search instance
     */
    public static StvnPrioritizedTableSpeedSearch installOn(JTable table) {
        var search = new StvnPrioritizedTableSpeedSearch(table, null);
        search.setupListeners();
        return search;
    }

    /**
     * Internal constructor delegating to the primary platform TableSpeedSearch constructor.
     *
     * @param table the target table component
     * @param sig signature disambiguator
     */
    protected StvnPrioritizedTableSpeedSearch(JTable table, @Nullable Void sig) {
        super(table, null, (val, cell) -> val == null || val instanceof Boolean ? "" : val.toString());
    }

    /**
     * Finds the matching element index for the given search pattern.
     * Prioritizes Column 0 across all rows before scanning secondary metadata columns
     * in priority order ({@code Upstream Target -> Source -> Resolved Type -> Use Depth}).
     *
     * @param s the user search string
     * @return the matched element index, or {@code null} if no match exists
     */
    @Override
    protected @Nullable Object findElement(@NotNull String s) {
        int rowCount = myComponent.getRowCount();
        int colCount = myComponent.getColumnCount();
        if (rowCount == 0 || colCount == 0) {
            return null;
        }

        String pattern = s.trim();
        int selectedRow = myComponent.getSelectedRow();
        if (selectedRow < 0) {
            selectedRow = 0;
        }

        // Phase 1: Prioritize Column 0 (Name column) across all rows starting from selectedRow
        for (int i = 0; i < rowCount; i++) {
            int row = (selectedRow + i) % rowCount;
            int element = row * colCount;
            if (isMatchingElement(element, pattern)) {
                return element;
            }
        }

        // Phase 2: Scan secondary columns in priority order across all rows
        for (int col : resolveSecondaryColumnOrder(colCount)) {
            for (int i = 0; i < rowCount; i++) {
                int row = (selectedRow + i) % rowCount;
                int element = row * colCount + col;
                if (isMatchingElement(element, pattern)) {
                    return element;
                }
            }
        }

        return null;
    }

    /**
     * Locates the next matching element for keyboard navigation (Down Arrow).
     * Restricts navigation to the highest-priority column containing matches.
     *
     * @param s the user search string
     * @return the next matched element index, or {@code null}
     */
    @Override
    protected @Nullable Object findNextElement(@NotNull String s) {
        int rowCount = myComponent.getRowCount();
        int colCount = myComponent.getColumnCount();
        if (rowCount == 0 || colCount == 0) {
            return null;
        }

        String pattern = s.trim();
        int selectedRow = myComponent.getSelectedRow();
        if (selectedRow < 0) {
            selectedRow = 0;
        }

        int priorityCol = findHighestPriorityMatchingColumn(pattern);
        if (priorityCol >= 0) {
            for (int i = 1; i <= rowCount; i++) {
                int row = (selectedRow + i) % rowCount;
                int element = row * colCount + priorityCol;
                if (isMatchingElement(element, pattern)) {
                    return element;
                }
            }
        }
        return null;
    }

    /**
     * Locates the previous matching element for keyboard navigation (Up Arrow).
     * Restricts navigation to the highest-priority column containing matches.
     *
     * @param s the user search string
     * @return the previous matched element index, or {@code null}
     */
    @Override
    protected @Nullable Object findPreviousElement(@NotNull String s) {
        int rowCount = myComponent.getRowCount();
        int colCount = myComponent.getColumnCount();
        if (rowCount == 0 || colCount == 0) {
            return null;
        }

        String pattern = s.trim();
        int selectedRow = myComponent.getSelectedRow();
        if (selectedRow < 0) {
            selectedRow = 0;
        }

        int priorityCol = findHighestPriorityMatchingColumn(pattern);
        if (priorityCol >= 0) {
            for (int i = 1; i <= rowCount; i++) {
                int row = (selectedRow - i + rowCount) % rowCount;
                int element = row * colCount + priorityCol;
                if (isMatchingElement(element, pattern)) {
                    return element;
                }
            }
        }
        return null;
    }

    /**
     * Evaluates whether the given row matches the search pattern.
     * If Column 0 has a match anywhere in the table, row matching requires Column 0 to match.
     *
     * @param row visual row index
     * @param pattern search string
     * @return {@code true} if the row matches under column prioritization rules
     */
    @Override
    protected boolean isMatchingRow(int row, String pattern) {
        if (hasColumnMatch(0, pattern)) {
            return isMatchingElement(row * myComponent.getColumnCount(), pattern);
        }
        return super.isMatchingRow(row, pattern);
    }

    private int findHighestPriorityMatchingColumn(String pattern) {
        int colCount = myComponent.getColumnCount();
        if (colCount <= 0) {
            return -1;
        }
        if (hasColumnMatch(0, pattern)) {
            return 0;
        }
        for (int col : resolveSecondaryColumnOrder(colCount)) {
            if (hasColumnMatch(col, pattern)) {
                return col;
            }
        }
        return -1;
    }

    private boolean hasColumnMatch(int col, String pattern) {
        int rowCount = myComponent.getRowCount();
        int colCount = myComponent.getColumnCount();
        if (col < 0 || col >= colCount) {
            return false;
        }
        for (int r = 0; r < rowCount; r++) {
            if (isMatchingElement(r * colCount + col, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static int[] resolveSecondaryColumnOrder(int colCount) {
        if (colCount == 5) {
            return FIVE_COLUMN_SECONDARY_ORDER;
        }
        int[] cols = new int[Math.max(0, colCount - 1)];
        for (int i = 1; i < colCount; i++) {
            cols[i - 1] = i;
        }
        return cols;
    }
}
