package org.stvnadore.plugin.browser;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.ui.ComponentWithExpandableItems;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.JBUI;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.StvnBundle;

import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JTable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Spreadsheet-style column sizing, per-column width locking, divider double-click right-sizing,
 * sort-toggle deconfliction, and truncated-cell tooltip controller for {@link StvnNamespaceBrowserPopup}.
 */
@NullMarked
public final class StvnNamespaceColumnManager {

    static final int DIVIDER_HIT_TOLERANCE_PX = 4;
    static final int MAX_RIGHT_SIZED_COLUMN_WIDTH_PX = 4096;
    private static final int INITIAL_TARGET_VIEWPORT_WIDTH_PX = 900;

    private final JBTable table;
    private final StvnDividerGuardedRowSorter rowSorter;
    private final TableCellRenderer delegateHeaderRenderer;
    private final Icon headerLockIcon;
    private final boolean[] lockedByModelColumn;
    private final int exactUseDepthHeaderWidth;
    private boolean userCustomizedWidths;
    private boolean initialContentWidthsApplied;
    private @Nullable Runnable fitWindowAction;
    private BooleanSupplier fitWindowEnabledSupplier = () -> true;
    private Supplier<StvnNamespaceScope> activeScopeSupplier = () -> StvnNamespaceScope.DEFS;

    StvnNamespaceColumnManager(JBTable table, JBScrollPane scrollPane, StvnNamespaceTableModel tableModel) {
        this.table = table;
        this.lockedByModelColumn = new boolean[tableModel.getColumnCount()];
        this.lockedByModelColumn[StvnNamespaceTableModel.COL_USE_DEPTH] = true;

        Icon rawLock = AllIcons.Ide.Readonly;
        float scaleFactor = rawLock.getIconWidth() > 0 ? 12.0f / rawLock.getIconWidth() : 0.75f;
        this.headerLockIcon = IconUtil.scale(rawLock, null, scaleFactor);

        this.rowSorter = new StvnDividerGuardedRowSorter(tableModel);
        table.setRowSorter(rowSorter);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setFillsViewportHeight(true);
        table.setExpandableItemsEnabled(false);

        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getViewport().setBackground(table.getBackground());

        JTableHeader header = table.getTableHeader();
        if (header instanceof ComponentWithExpandableItems<?> expandableHeader) {
            expandableHeader.setExpandableItemsEnabled(false);
        }
        this.delegateHeaderRenderer = header.getDefaultRenderer();
        header.setDefaultRenderer(new StvnHeaderRenderer());

        var cellRenderer = new StvnNamespaceCellRenderer();
        table.setDefaultRenderer(String.class, cellRenderer);
        table.setDefaultRenderer(Integer.class, cellRenderer);
        table.setAutoCreateColumnsFromModel(false);

        int useDepthViewCol = table.convertColumnIndexToView(StvnNamespaceTableModel.COL_USE_DEPTH);
        this.exactUseDepthHeaderWidth = computeExactUseDepthHeaderWidth(useDepthViewCol);

        initializeDefaultColumnWidths();
        installHeaderDividerAndContextMenuListeners(header);
    }

    void setActiveScopeSupplier(Supplier<StvnNamespaceScope> activeScopeSupplier) {
        this.activeScopeSupplier = activeScopeSupplier;
    }

    int getExactUseDepthHeaderWidth() {
        return exactUseDepthHeaderWidth;
    }

    StvnDividerGuardedRowSorter getRowSorter() {
        return rowSorter;
    }

    boolean isModelColumnLocked(int modelCol) {
        return modelCol >= 0 && modelCol < lockedByModelColumn.length && lockedByModelColumn[modelCol];
    }

    /**
     * Returns whether the column at {@code viewCol} currently has its width locked.
     *
     * @param viewCol view column index
     * @return {@code true} if the column width is locked
     */
    public boolean isViewColumnLocked(int viewCol) {
        if (viewCol < 0 || viewCol >= table.getColumnCount()) {
            return false;
        }
        return isModelColumnLocked(table.convertColumnIndexToModel(viewCol));
    }

    /**
     * Binds the popup window-fit callback and readiness predicate invoked by the header context menu.
     *
     * @param fitWindowAction          callback fitting unlocked columns and popup window bounds
     * @param fitWindowEnabledSupplier readiness predicate returning {@code true} once async snapshot loading completes
     */
    public void setFitWindowAction(@Nullable Runnable fitWindowAction, BooleanSupplier fitWindowEnabledSupplier) {
        this.fitWindowAction = fitWindowAction;
        this.fitWindowEnabledSupplier = fitWindowEnabledSupplier;
    }

    /**
     * Returns whether at least one column in the table model is currently unlocked.
     *
     * @return {@code true} if any column is unlocked
     */
    public boolean hasAnyUnlockedColumn() {
        for (boolean locked : lockedByModelColumn) {
            if (!locked) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} when every unlocked column is already within {@code ±1px} of its target
     * right-sized pixel width (or when zero columns are unlocked).
     *
     * @return {@code true} if no unlocked column requires horizontal right-sizing
     */
    public boolean areAllUnlockedColumnsRightSized() {
        TableColumnModel cm = table.getColumnModel();
        for (int viewCol = 0; viewCol < cm.getColumnCount(); viewCol++) {
            if (!isViewColumnLocked(viewCol)) {
                TableColumn col = cm.getColumn(viewCol);
                int targetWidth = computeRightSizedWidth(viewCol);
                if (Math.abs(col.getWidth() - targetWidth) > 1) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Computes the authoritative total pixel width required across all visible columns in {@code table},
     * taking the maximum of summed {@link TableColumn#getWidth()}, {@link TableColumnModel#getTotalColumnWidth()},
     * and {@link JTable#getPreferredSize()}{@code .width}.
     *
     * @return total pixel width across all columns
     */
    public int computeTotalColumnsWidth() {
        TableColumnModel cm = table.getColumnModel();
        int summedWidths = 0;
        for (int viewCol = 0; viewCol < cm.getColumnCount(); viewCol++) {
            summedWidths += cm.getColumn(viewCol).getWidth();
        }
        return Math.max(table.getPreferredSize().width, Math.max(summedWidths, cm.getTotalColumnWidth()));
    }

    void setViewColumnLocked(int viewCol, boolean locked) {
        if (viewCol < 0 || viewCol >= table.getColumnCount()) {
            return;
        }
        int modelCol = table.convertColumnIndexToModel(viewCol);
        TableColumn col = table.getColumnModel().getColumn(viewCol);
        int currentWidth = col.getWidth() > 0 ? col.getWidth() : col.getPreferredWidth();
        lockedByModelColumn[modelCol] = locked;
        userCustomizedWidths = true;

        if (locked) {
            applyLockedWidth(col, currentWidth);
        } else {
            int floor = Math.min(currentWidth, computeMinHeaderFloorWidth(viewCol));
            applyUnlockedWidth(col, currentWidth, floor);
        }
        refreshTableAndHeaderLayout();
    }

    boolean rightSizeViewColumnIfUnlocked(int viewCol) {
        if (viewCol < 0 || viewCol >= table.getColumnCount() || isViewColumnLocked(viewCol)) {
            return false;
        }
        userCustomizedWidths = true;
        TableColumn col = table.getColumnModel().getColumn(viewCol);
        int targetWidth = computeRightSizedWidth(viewCol);
        col.setPreferredWidth(targetWidth);
        col.setWidth(targetWidth);
        refreshTableAndHeaderLayout();
        return true;
    }

    /**
     * Right-sizes every unlocked column in the table to fit its header and visible cell contents,
     * leaving all locked columns at their exact pixel widths.
     */
    public void rightSizeAllUnlockedColumns() {
        boolean changed = false;
        for (int viewCol = 0; viewCol < table.getColumnCount(); viewCol++) {
            if (!isViewColumnLocked(viewCol)) {
                TableColumn col = table.getColumnModel().getColumn(viewCol);
                int targetWidth = computeRightSizedWidth(viewCol);
                col.setPreferredWidth(targetWidth);
                col.setWidth(targetWidth);
                changed = true;
            }
        }
        if (changed) {
            userCustomizedWidths = true;
            refreshTableAndHeaderLayout();
        }
    }

    void applyInitialContentWidthsIfUnmodified() {
        if (userCustomizedWidths || initialContentWidthsApplied || table.getRowCount() == 0) {
            return;
        }
        initialContentWidthsApplied = true;
        int[] caps = {
            JBUI.scale(230),
            JBUI.scale(210),
            JBUI.scale(250),
            JBUI.scale(420)
        };
        int[] floors = {
            JBUI.scale(150),
            JBUI.scale(140),
            JBUI.scale(175),
            JBUI.scale(250)
        };
        int totalAssigned = 0;
        TableColumnModel cm = table.getColumnModel();
        for (int viewCol = 0; viewCol < cm.getColumnCount(); viewCol++) {
            int modelCol = table.convertColumnIndexToModel(viewCol);
            TableColumn col = cm.getColumn(viewCol);
            if (isModelColumnLocked(modelCol)) {
                totalAssigned += col.getWidth();
                continue;
            }
            int rightSized = computeRightSizedWidth(viewCol);
            int idx = Math.min(modelCol, caps.length - 1);
            int initialW = Math.max(floors[idx], Math.min(caps[idx], rightSized));
            col.setPreferredWidth(initialW);
            col.setWidth(initialW);
            totalAssigned += initialW;
        }
        int targetViewport = JBUI.scale(INITIAL_TARGET_VIEWPORT_WIDTH_PX);
        int resolvedTypeViewCol = table.convertColumnIndexToView(StvnNamespaceTableModel.COL_RESOLVED_TYPE);
        if (totalAssigned < targetViewport
                && resolvedTypeViewCol >= 0
                && !isModelColumnLocked(StvnNamespaceTableModel.COL_RESOLVED_TYPE)) {
            TableColumn resolvedCol = cm.getColumn(resolvedTypeViewCol);
            int expanded = resolvedCol.getWidth() + (targetViewport - totalAssigned);
            resolvedCol.setPreferredWidth(expanded);
            resolvedCol.setWidth(expanded);
        }
        refreshTableAndHeaderLayout();
    }

    void markInitialSnapshotComplete() {
        this.initialContentWidthsApplied = true;
    }

    int findDividerViewColumn(JTableHeader header, Point point) {
        int headerHeight = header.getHeight();
        if (point.y < 0 || (headerHeight > 0 && point.y > headerHeight)) {
            return -1;
        }
        int tolerance = JBUI.scale(DIVIDER_HIT_TOLERANCE_PX);
        int bestCol = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int viewCol = 0; viewCol < header.getColumnModel().getColumnCount(); viewCol++) {
            Rectangle rect = header.getHeaderRect(viewCol);
            int rightEdgeX = rect.x + rect.width;
            int dist = Math.abs(point.x - rightEdgeX);
            if (dist <= tolerance && dist < bestDist) {
                bestDist = dist;
                bestCol = viewCol;
            }
        }
        return bestCol;
    }

    int computeRightSizedWidth(int viewCol) {
        int modelCol = table.convertColumnIndexToModel(viewCol);
        int headerReq = computeRequiredHeaderWidth(viewCol, isModelColumnLocked(modelCol), true);
        int maxCellReq = 0;
        for (int row = 0; row < table.getRowCount(); row++) {
            TableCellRenderer renderer = table.getCellRenderer(row, viewCol);
            Component comp = table.prepareRenderer(renderer, row, viewCol);
            maxCellReq = Math.max(maxCellReq, comp.getPreferredSize().width);
        }
        int floor = computeMinHeaderFloorWidth(viewCol);
        int paddedCellWidth = maxCellReq > 0 ? maxCellReq + JBUI.scale(8) : 0;
        int raw = Math.max(floor, Math.max(headerReq, paddedCellWidth));
        return Math.min(JBUI.scale(MAX_RIGHT_SIZED_COLUMN_WIDTH_PX), raw);
    }

    /**
     * Resolves the dynamic scope-aware header tooltip when hovering the {@code Use Depth} column
     * header ({@link StvnNamespaceTableModel#COL_USE_DEPTH}), returning {@code null} for all other headers.
     *
     * @param headerPoint point in {@link JTableHeader} coordinates
     * @param activeScope active namespace browser scope ({@code :defs}, {@code :type}, or {@code :body})
     * @return localized STE tooltip for {@code Use Depth}, or {@code null}
     */
    public @Nullable String resolveHeaderTooltip(Point headerPoint, StvnNamespaceScope activeScope) {
        JTableHeader header = table.getTableHeader();
        if (header == null) {
            return null;
        }
        int viewCol = header.columnAtPoint(headerPoint);
        if (viewCol < 0 || viewCol >= table.getColumnCount()) {
            return null;
        }
        int modelCol = table.convertColumnIndexToModel(viewCol);
        if (modelCol != StvnNamespaceTableModel.COL_USE_DEPTH) {
            return null;
        }
        return resolveUseDepthHeaderTooltip(activeScope);
    }

    static String resolveUseDepthHeaderTooltip(StvnNamespaceScope activeScope) {
        return activeScope == StvnNamespaceScope.DEFS
            ? StvnBundle.message("stvn.browser.column.useDepth.tooltip.defs")
            : StvnBundle.message("stvn.browser.column.useDepth.tooltip.reachability");
    }

    /**
     * Builds the header right-click context menu for {@code viewCol}.
     *
     * @param viewCol view column index
     * @return constructed {@link JBPopupMenu}
     */
    public JBPopupMenu buildHeaderContextMenu(int viewCol) {
        var menu = new JBPopupMenu();
        boolean currentlyLocked = isViewColumnLocked(viewCol);

        var lockItem = new JCheckBoxMenuItem(
            StvnBundle.message("stvn.browser.header.menu.lockWidth"),
            currentlyLocked
        );
        lockItem.addActionListener(e -> setViewColumnLocked(viewCol, lockItem.isSelected()));
        menu.add(lockItem);

        var rightSizeSingle = new JMenuItem(StvnBundle.message("stvn.browser.header.menu.rightSizeColumn"));
        rightSizeSingle.setEnabled(!currentlyLocked);
        rightSizeSingle.addActionListener(e -> rightSizeViewColumnIfUnlocked(viewCol));
        menu.add(rightSizeSingle);

        var rightSizeAll = new JMenuItem(StvnBundle.message("stvn.browser.header.menu.rightSizeAllUnlocked"));
        rightSizeAll.setEnabled(hasAnyUnlockedColumn());
        rightSizeAll.addActionListener(e -> rightSizeAllUnlockedColumns());
        menu.add(rightSizeAll);

        var fitWindowItem = new JMenuItem(
            StvnBundle.message("stvn.browser.header.menu.fitWindowToContent").replace("\u001B", "")
        );
        fitWindowItem.setEnabled(fitWindowAction != null && fitWindowEnabledSupplier.getAsBoolean());
        fitWindowItem.addActionListener(e -> {
            if (fitWindowAction != null && fitWindowEnabledSupplier.getAsBoolean()) {
                fitWindowAction.run();
            }
        });
        menu.add(fitWindowItem);

        return menu;
    }

    @Nullable String resolveTruncatedCellTooltip(MouseEvent e) {
        Point p = e.getPoint();
        int row = table.rowAtPoint(p);
        int col = table.columnAtPoint(p);
        if (row < 0 || col < 0) {
            return null;
        }
        Object val = table.getValueAt(row, col);
        if (val == null) {
            return null;
        }
        String text = val.toString();
        if (text.isEmpty()) {
            return null;
        }
        TableCellRenderer renderer = table.getCellRenderer(row, col);
        Component comp = table.prepareRenderer(renderer, row, col);
        int availableWidth = table.getCellRect(row, col, false).width;
        return comp.getPreferredSize().width > availableWidth ? text : null;
    }

    private void initializeDefaultColumnWidths() {
        int wName = JBUI.scale(175);
        int wSource = JBUI.scale(160);
        int wUpstream = JBUI.scale(200);
        int targetTotal = JBUI.scale(INITIAL_TARGET_VIEWPORT_WIDTH_PX);
        int wResolved = Math.max(
            JBUI.scale(270),
            targetTotal - (wName + wSource + wUpstream + exactUseDepthHeaderWidth)
        );
        int[] initialUnlocked = {wName, wSource, wUpstream, wResolved};

        TableColumnModel cm = table.getColumnModel();
        for (int viewCol = 0; viewCol < cm.getColumnCount(); viewCol++) {
            int modelCol = table.convertColumnIndexToModel(viewCol);
            TableColumn col = cm.getColumn(viewCol);
            if (modelCol == StvnNamespaceTableModel.COL_USE_DEPTH) {
                applyLockedWidth(col, exactUseDepthHeaderWidth);
            } else {
                int floor = computeMinHeaderFloorWidth(viewCol);
                int width = Math.max(floor, initialUnlocked[Math.min(modelCol, initialUnlocked.length - 1)]);
                applyUnlockedWidth(col, width, floor);
            }
        }
    }

    private void installHeaderDividerAndContextMenuListeners(JTableHeader header) {
        table.getColumnModel().addColumnModelListener(new TableColumnModelListener() {
            @Override
            public void columnAdded(TableColumnModelEvent e) {
            }

            @Override
            public void columnRemoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnMoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnMarginChanged(ChangeEvent e) {
                if (header.getResizingColumn() != null) {
                    userCustomizedWidths = true;
                }
            }

            @Override
            public void columnSelectionChanged(ListSelectionEvent e) {
            }
        });

        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    int divCol = findDividerViewColumn(header, e.getPoint());
                    rowSorter.setSuppressSortToggle(divCol >= 0);
                    if (divCol >= 0 && !isViewColumnLocked(divCol)) {
                        userCustomizedWidths = true;
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && rowSorter.isSuppressSortToggle()) {
                    SwingUtilities.invokeLater(() -> rowSorter.setSuppressSortToggle(false));
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    try {
                        int divCol = findDividerViewColumn(header, e.getPoint());
                        if (divCol >= 0) {
                            e.consume();
                            if (e.getClickCount() == 2) {
                                rightSizeViewColumnIfUnlocked(divCol);
                            }
                        }
                    } finally {
                        rowSorter.setSuppressSortToggle(false);
                    }
                }
            }
        });

        header.addMouseListener(new PopupHandler() {
            @Override
            public void invokePopup(Component comp, int x, int y) {
                int viewCol = header.columnAtPoint(new Point(x, y));
                if (viewCol >= 0) {
                    buildHeaderContextMenu(viewCol).show(comp, x, y);
                }
            }
        });
    }

    private int computeExactUseDepthHeaderWidth(int viewCol) {
        int headerReq = computeRequiredHeaderWidth(viewCol, true, true);
        var sampleCell = new StvnNamespaceCellRenderer();
        Component sampleComp = sampleCell.getTableCellRendererComponent(table, 999, false, false, 0, viewCol);
        int sampleCellReq = sampleComp.getPreferredSize().width + JBUI.scale(6);
        return Math.max(headerReq, sampleCellReq);
    }

    int computeMinHeaderFloorWidth(int viewCol) {
        return computeRequiredHeaderWidth(viewCol, false, false);
    }

    int computeRequiredHeaderWidth(int viewCol, boolean includeLockIcon, boolean includeSortReserve) {
        Object headerValue = table.getColumnModel().getColumn(viewCol).getHeaderValue();
        Component comp = delegateHeaderRenderer.getTableCellRendererComponent(
            table, headerValue, false, false, -1, viewCol
        );
        int modelCol = table.convertColumnIndexToModel(viewCol);
        if (comp instanceof JLabel label) {
            Icon sortReserve = includeSortReserve ? createSortReserveIcon() : null;
            Icon combined = composeHeaderIcons(includeLockIcon ? headerLockIcon : null, sortReserve);
            label.setHorizontalAlignment(
                modelCol == StvnNamespaceTableModel.COL_USE_DEPTH ? SwingConstants.RIGHT : SwingConstants.LEFT
            );
            label.setHorizontalTextPosition(SwingConstants.LEADING);
            label.setIconTextGap(combined != null ? JBUI.scale(4) : 0);
            label.setIcon(combined);
        }
        return comp.getPreferredSize().width + JBUI.scale(6);
    }

    private Icon createSortReserveIcon() {
        Icon asc = UIManager.getIcon("Table.ascendingSortIcon");
        Icon desc = UIManager.getIcon("Table.descendingSortIcon");
        int arrowWidth = AllIcons.General.ArrowUp.getIconWidth();
        int w = Math.max(
            Math.max(JBUI.scale(12), arrowWidth),
            Math.max(asc != null ? asc.getIconWidth() : 0, desc != null ? desc.getIconWidth() : 0)
        );
        int h = Math.max(JBUI.scale(10), headerLockIcon.getIconHeight());
        return new EmptyReserveIcon(w, h);
    }

    private static @Nullable Icon composeHeaderIcons(@Nullable Icon first, @Nullable Icon second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return new DualHeaderIcon(first, JBUI.scale(3), second);
    }

    private static void applyLockedWidth(TableColumn col, int width) {
        col.setMinWidth(0);
        col.setMaxWidth(Integer.MAX_VALUE);
        col.setPreferredWidth(width);
        col.setWidth(width);
        col.setMinWidth(width);
        col.setMaxWidth(width);
        col.setResizable(false);
    }

    private static void applyUnlockedWidth(TableColumn col, int width, int minFloor) {
        int safeFloor = Math.max(JBUI.scale(32), Math.min(width, minFloor));
        col.setMinWidth(safeFloor);
        col.setMaxWidth(Integer.MAX_VALUE);
        col.setPreferredWidth(width);
        col.setWidth(width);
        col.setResizable(true);
    }

    private void refreshTableAndHeaderLayout() {
        JTableHeader header = table.getTableHeader();
        if (header != null) {
            header.revalidate();
            header.repaint();
        }
        table.revalidate();
        table.repaint();
    }

    static final class StvnDividerGuardedRowSorter extends TableRowSorter<StvnNamespaceTableModel> {
        private boolean suppressSortToggle;

        StvnDividerGuardedRowSorter(StvnNamespaceTableModel model) {
            super(model);
        }

        boolean isSuppressSortToggle() {
            return suppressSortToggle;
        }

        void setSuppressSortToggle(boolean suppressSortToggle) {
            this.suppressSortToggle = suppressSortToggle;
        }

        @Override
        public void toggleSortOrder(int column) {
            if (suppressSortToggle) {
                return;
            }
            super.toggleSortOrder(column);
        }
    }

    private final class StvnHeaderRenderer implements TableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            JTable tbl,
            @Nullable Object value,
            boolean isSelected,
            boolean hasFocus,
            int row,
            int column
        ) {
            Component comp = delegateHeaderRenderer.getTableCellRendererComponent(
                tbl, value, isSelected, hasFocus, row, column
            );
            int modelCol = tbl.convertColumnIndexToModel(column);
            boolean isUseDepth = modelCol == StvnNamespaceTableModel.COL_USE_DEPTH;
            if (comp instanceof JLabel label) {
                Icon sortIcon = label.getIcon();
                label.setHorizontalAlignment(isUseDepth ? SwingConstants.RIGHT : SwingConstants.LEFT);
                label.setHorizontalTextPosition(SwingConstants.LEADING);
                if (isModelColumnLocked(modelCol)) {
                    Icon combined = composeHeaderIcons(headerLockIcon, sortIcon);
                    label.setIconTextGap(combined != null ? JBUI.scale(4) : 0);
                    label.setIcon(combined);
                } else {
                    label.setIconTextGap(sortIcon != null ? JBUI.scale(4) : 0);
                    label.setIcon(sortIcon);
                }
            }
            if (comp instanceof JComponent jc) {
                jc.setToolTipText(isUseDepth ? resolveUseDepthHeaderTooltip(activeScopeSupplier.get()) : null);
            }
            return comp;
        }
    }

    private static final class StvnNamespaceCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            JTable tbl,
            @Nullable Object value,
            boolean isSelected,
            boolean hasFocus,
            int row,
            int column
        ) {
            Component comp = super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
            int modelCol = tbl.convertColumnIndexToModel(column);
            setHorizontalAlignment(
                modelCol == StvnNamespaceTableModel.COL_USE_DEPTH ? SwingConstants.RIGHT : SwingConstants.LEFT
            );
            setBorder(JBUI.Borders.empty(0, 8));
            return comp;
        }
    }

    record DualHeaderIcon(Icon left, int gap, Icon right) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            int totalH = getIconHeight();
            int leftY = y + Math.max(0, (totalH - left.getIconHeight()) / 2);
            int rightY = y + Math.max(0, (totalH - right.getIconHeight()) / 2);
            left.paintIcon(c, g, x, leftY);
            right.paintIcon(c, g, x + left.getIconWidth() + gap, rightY);
        }

        @Override
        public int getIconWidth() {
            return left.getIconWidth() + gap + right.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return Math.max(left.getIconHeight(), right.getIconHeight());
        }
    }

    private record EmptyReserveIcon(int width, int height) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return height;
        }
    }
}
