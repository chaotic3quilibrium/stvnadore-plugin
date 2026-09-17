package org.stvnadore.plugin.browser;

import com.intellij.ui.TableSpeedSearch;
import javax.swing.JTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Customized table speed search prioritizing Column 0 (Name column) across all rows
 * before falling back to metadata columns (Source, Type, Use Depth).
 */
@NullMarked
public class StvnPrioritizedTableSpeedSearch extends TableSpeedSearch {

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
     * Internal constructor calling the non-deprecated platform constructor.
     *
     * @param table the target table component
     * @param sig signature disambiguator
     */
    protected StvnPrioritizedTableSpeedSearch(JTable table, @Nullable Void sig) {
        super(table, null, (val, cell) -> val == null || val instanceof Boolean ? "" : val.toString());
    }

    /**
     * Constructs an StvnPrioritizedTableSpeedSearch instance for the given table.
     *
     * @param table the target table component
     */
    @SuppressWarnings("deprecation")
    public StvnPrioritizedTableSpeedSearch(JTable table) {
        super(table);
    }

    /**
     * Finds the matching element index for the given search pattern.
     * Prioritizes Column 0 across all rows before scanning secondary metadata columns.
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
            int element = row * colCount; // Column 0 index
            if (isMatchingElement(element, pattern)) {
                return element;
            }
        }

        // Phase 2: If no match in Column 0, scan remaining metadata columns (1..colCount-1)
        for (int i = 0; i < rowCount; i++) {
            int row = (selectedRow + i) % rowCount;
            for (int col = 1; col < colCount; col++) {
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
     * Restricts navigation to Column 0 when Column 0 contains matches.
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
        int selectedCol = myComponent.getSelectedColumn();
        if (selectedRow < 0) selectedRow = 0;
        if (selectedCol < 0) selectedCol = 0;

        if (hasColumn0Match(pattern)) {
            // Cycle forward strictly through Column 0
            for (int i = 1; i <= rowCount; i++) {
                int row = (selectedRow + i) % rowCount;
                int element = row * colCount;
                if (isMatchingElement(element, pattern)) {
                    return element;
                }
            }
            return null;
        }

        // Fallback: cycle through metadata columns (col 1..colCount-1)
        int currentFlatIndex = selectedRow * colCount + selectedCol;
        int totalCells = rowCount * colCount;
        for (int i = 1; i <= totalCells; i++) {
            int flatIndex = (currentFlatIndex + i) % totalCells;
            int col = flatIndex % colCount;
            if (col != 0 && isMatchingElement(flatIndex, pattern)) {
                return flatIndex;
            }
        }

        return null;
    }

    /**
     * Locates the previous matching element for keyboard navigation (Up Arrow).
     * Restricts navigation to Column 0 when Column 0 contains matches.
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
        int selectedCol = myComponent.getSelectedColumn();
        if (selectedRow < 0) selectedRow = 0;
        if (selectedCol < 0) selectedCol = 0;

        if (hasColumn0Match(pattern)) {
            // Cycle backward strictly through Column 0
            for (int i = 1; i <= rowCount; i++) {
                int row = (selectedRow - i + rowCount) % rowCount;
                int element = row * colCount;
                if (isMatchingElement(element, pattern)) {
                    return element;
                }
            }
            return null;
        }

        // Fallback: cycle backward through metadata columns
        int currentFlatIndex = selectedRow * colCount + selectedCol;
        int totalCells = rowCount * colCount;
        for (int i = 1; i <= totalCells; i++) {
            int flatIndex = (currentFlatIndex - i + totalCells) % totalCells;
            int col = flatIndex % colCount;
            if (col != 0 && isMatchingElement(flatIndex, pattern)) {
                return flatIndex;
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
        if (hasColumn0Match(pattern)) {
            return isMatchingElement(row * myComponent.getColumnCount(), pattern);
        }
        return super.isMatchingRow(row, pattern);
    }

    private boolean hasColumn0Match(String pattern) {
        int rowCount = myComponent.getRowCount();
        int colCount = myComponent.getColumnCount();
        for (int r = 0; r < rowCount; r++) {
            if (isMatchingElement(r * colCount, pattern)) {
                return true;
            }
        }
        return false;
    }
}
