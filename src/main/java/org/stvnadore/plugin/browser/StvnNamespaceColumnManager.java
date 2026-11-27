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
import org.stvnadore.plugin.settings.NamespaceEllipsisMode;

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
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
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
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Spreadsheet-style column sizing, per-column width locking, divider double-click right-sizing,
 * sort-toggle deconfliction, and truncated-cell tooltip controller for {@link StvnNamespaceBrowserPopup}.
 */
@NullMarked
public final class StvnNamespaceColumnManager {

    static final int DIVIDER_HIT_TOLERANCE_PX = 4;
    static final int MAX_RIGHT_SIZED_COLUMN_WIDTH_PX = 4096;
    static final int MIN_WATERFILL_COLUMN_WIDTH_PX = 56;
    private static final int HEADER_ANTI_CLIP_GUARD_PX = 4;
    private static final int CELL_RIGHT_SIZE_GUARD_PX = 4;
    private static final int HEADER_LOCK_ICON_SIZE_PX = 11;
    private static final int HEADER_DUAL_ICON_GAP_PX = 2;
    private static final int HEADER_ICON_TEXT_GAP_PX = 3;
    private static final int USE_DEPTH_LEFT_INSET_PX = 6;
    private static final int CELL_HORIZONTAL_INSET_PX = 8;
    private static final int INITIAL_TARGET_VIEWPORT_WIDTH_PX = 900;

    private final JBTable table;
    private final StvnDividerGuardedRowSorter rowSorter;
    private final TableCellRenderer delegateHeaderRenderer;
    private final StvnNamespaceCellRenderer cellRenderer;
    private final Icon headerLockIcon;
    private final boolean[] lockedByModelColumn;
    private final int exactUseDepthHeaderWidth;
    private boolean userCustomizedWidths;
    private boolean initialContentWidthsApplied;
    private boolean measuringUnconstrainedPreferredWidth;
    private @Nullable Runnable fitWindowAction;
    private BooleanSupplier fitWindowEnabledSupplier = () -> true;
    private Supplier<StvnNamespaceScope> activeScopeSupplier = () -> StvnNamespaceScope.DEFS;
    private Supplier<NamespaceEllipsisMode> ellipsisModeSupplier = () -> NamespaceEllipsisMode.MIDDLE;
    private IntSupplier preservedSuffixLengthSupplier = () -> StvnCellTextEllipsizer.DEFAULT_SUFFIX_CODE_POINTS;

    StvnNamespaceColumnManager(JBTable table, JBScrollPane scrollPane, StvnNamespaceTableModel tableModel) {
        this.table = table;
        this.lockedByModelColumn = new boolean[tableModel.getColumnCount()];
        this.lockedByModelColumn[StvnNamespaceTableModel.COL_USE_DEPTH] = true;

        Icon rawLock = AllIcons.Ide.Readonly;
        float scaleFactor = rawLock.getIconWidth() > 0
            ? (float) JBUI.scale(HEADER_LOCK_ICON_SIZE_PX) / rawLock.getIconWidth()
            : 0.7f;
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

        this.cellRenderer = new StvnNamespaceCellRenderer();
        table.setDefaultRenderer(String.class, this.cellRenderer);
        table.setDefaultRenderer(Integer.class, this.cellRenderer);
        table.setAutoCreateColumnsFromModel(false);

        int useDepthViewCol = table.convertColumnIndexToView(StvnNamespaceTableModel.COL_USE_DEPTH);
        this.exactUseDepthHeaderWidth = computeExactUseDepthHeaderWidth(useDepthViewCol);

        initializeDefaultColumnWidths();
        installHeaderDividerAndContextMenuListeners(header);
    }

    void setActiveScopeSupplier(Supplier<StvnNamespaceScope> activeScopeSupplier) {
        this.activeScopeSupplier = activeScopeSupplier;
    }

    void setEllipsisSettingsSuppliers(
        Supplier<NamespaceEllipsisMode> ellipsisModeSupplier,
        IntSupplier preservedSuffixLengthSupplier
    ) {
        this.ellipsisModeSupplier = ellipsisModeSupplier;
        this.preservedSuffixLengthSupplier = preservedSuffixLengthSupplier;
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

    /**
     * Fits unlocked columns to {@code maxColsBudget} via bounded water-filling cap compression:
     * applies full unconstrained natural widths when {@code sum <= maxColsBudget}, or caps only the
     * widest unlocked column(s) to fit within {@code maxColsBudget} while preserving all locked columns.
     *
     * @param maxColsBudget maximum total horizontal pixel budget available across all visible columns
     */
    public void fitUnlockedColumnsToBudget(int maxColsBudget) {
        TableColumnModel cm = table.getColumnModel();
        int colCount = cm.getColumnCount();
        if (colCount <= 0) {
            return;
        }
        int[] naturalWidths = new int[colCount];
        boolean[] locked = new boolean[colCount];
        int[] minFloors = new int[colCount];
        for (int viewCol = 0; viewCol < colCount; viewCol++) {
            TableColumn col = cm.getColumn(viewCol);
            locked[viewCol] = isViewColumnLocked(viewCol);
            naturalWidths[viewCol] = locked[viewCol] ? col.getWidth() : computeRightSizedWidth(viewCol);
            minFloors[viewCol] = locked[viewCol] ? col.getWidth() : computeMinHeaderFloorWidth(viewCol);
        }
        int[] fittedWidths = StvnColumnWaterFillSolver.solveFittedColumnWidths(
            naturalWidths,
            locked,
            minFloors,
            JBUI.scale(MIN_WATERFILL_COLUMN_WIDTH_PX),
            maxColsBudget
        );
        boolean changed = false;
        for (int viewCol = 0; viewCol < colCount; viewCol++) {
            if (!locked[viewCol]) {
                TableColumn col = cm.getColumn(viewCol);
                applyUnlockedWidth(col, fittedWidths[viewCol], minFloors[viewCol]);
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
        int headerReq = measureHeaderWidth(viewCol, isModelColumnLocked(modelCol), true);
        int maxCellReq = 0;
        measuringUnconstrainedPreferredWidth = true;
        try {
            for (int row = 0; row < table.getRowCount(); row++) {
                TableCellRenderer renderer = table.getCellRenderer(row, viewCol);
                Component comp = table.prepareRenderer(renderer, row, viewCol);
                maxCellReq = Math.max(maxCellReq, comp.getPreferredSize().width);
            }
        } finally {
            measuringUnconstrainedPreferredWidth = false;
        }
        int floor = computeMinHeaderFloorWidth(viewCol);
        int paddedCellWidth = maxCellReq > 0 ? maxCellReq + JBUI.scale(CELL_RIGHT_SIZE_GUARD_PX) : 0;
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
        TableCellRenderer renderer = table.getCellRenderer(row, col);
        Component comp = table.prepareRenderer(renderer, row, col);
        return comp instanceof JComponent jc ? jc.getToolTipText() : null;
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
        int headerReq = measureHeaderWidth(viewCol, true, true);
        Component sampleComp = cellRenderer.getTableCellRendererComponent(table, 999, false, false, -1, viewCol);
        int sampleCellReq = sampleComp.getPreferredSize().width + JBUI.scale(HEADER_ANTI_CLIP_GUARD_PX);
        return Math.max(headerReq, sampleCellReq);
    }

    int computeMinHeaderFloorWidth(int viewCol) {
        return measureHeaderWidth(viewCol, false, false);
    }

    int measureHeaderWidth(int viewCol, boolean includeLockIcon, boolean includeSortReserve) {
        Object headerValue = table.getColumnModel().getColumn(viewCol).getHeaderValue();
        Component comp = delegateHeaderRenderer.getTableCellRendererComponent(
            table, headerValue, false, false, -1, viewCol
        );
        int modelCol = table.convertColumnIndexToModel(viewCol);
        boolean isUseDepth = modelCol == StvnNamespaceTableModel.COL_USE_DEPTH;
        Icon sortReserve = includeSortReserve ? createSortReserveIcon() : null;
        configureHeaderComponent(comp, isUseDepth, includeLockIcon, sortReserve);
        return comp.getPreferredSize().width + JBUI.scale(HEADER_ANTI_CLIP_GUARD_PX);
    }

    private void configureHeaderComponent(
        Component comp,
        boolean isUseDepth,
        boolean includeLockIcon,
        @Nullable Icon sortIcon
    ) {
        if (comp instanceof JComponent jc) {
            normalizeHeaderHorizontalBorder(jc, isUseDepth);
        }
        if (comp instanceof JLabel label) {
            Icon combined = composeHeaderIcons(includeLockIcon ? headerLockIcon : null, sortIcon);
            label.setHorizontalAlignment(isUseDepth ? SwingConstants.RIGHT : SwingConstants.LEFT);
            label.setHorizontalTextPosition(SwingConstants.LEADING);
            label.setIconTextGap(combined != null ? JBUI.scale(HEADER_ICON_TEXT_GAP_PX) : 0);
            label.setIcon(combined);
        }
    }

    private static void normalizeHeaderHorizontalBorder(JComponent comp, boolean isUseDepth) {
        Border border = comp.getBorder();
        int targetLeft = JBUI.scale(isUseDepth ? USE_DEPTH_LEFT_INSET_PX : CELL_HORIZONTAL_INSET_PX);
        int targetRight = JBUI.scale(CELL_HORIZONTAL_INSET_PX);
        if (border instanceof CompoundBorder cb) {
            Border outer = cb.getOutsideBorder();
            Border inner = cb.getInsideBorder();
            Insets outerIns = outer != null ? outer.getBorderInsets(comp) : JBUI.emptyInsets();
            Insets innerIns = inner != null ? inner.getBorderInsets(comp) : JBUI.emptyInsets();
            if (outer instanceof EmptyBorder && inner instanceof EmptyBorder) {
                comp.setBorder(JBUI.Borders.empty(
                    outerIns.top + innerIns.top,
                    targetLeft,
                    outerIns.bottom + innerIns.bottom,
                    targetRight
                ));
                return;
            }
            if (outer instanceof EmptyBorder && inner != null) {
                int extraLeft = Math.max(0, targetLeft - innerIns.left);
                int extraRight = Math.max(0, targetRight - innerIns.right);
                comp.setBorder(new CompoundBorder(
                    JBUI.Borders.empty(outerIns.top, extraLeft, outerIns.bottom, extraRight),
                    inner
                ));
                return;
            }
            if (inner instanceof EmptyBorder && outer != null) {
                int extraLeft = Math.max(0, targetLeft - outerIns.left);
                int extraRight = Math.max(0, targetRight - outerIns.right);
                comp.setBorder(new CompoundBorder(
                    outer,
                    JBUI.Borders.empty(innerIns.top, extraLeft, innerIns.bottom, extraRight)
                ));
                return;
            }
        } else if (border instanceof EmptyBorder eb) {
            Insets ins = eb.getBorderInsets(comp);
            comp.setBorder(JBUI.Borders.empty(ins.top, targetLeft, ins.bottom, targetRight));
        }
    }

    private Icon createSortReserveIcon() {
        Icon asc = UIManager.getIcon("Table.ascendingSortIcon");
        Icon desc = UIManager.getIcon("Table.descendingSortIcon");
        int actualSortW = Math.max(asc != null ? asc.getIconWidth() : 0, desc != null ? desc.getIconWidth() : 0);
        int w = actualSortW > 0 ? actualSortW : JBUI.scale(10);
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
        return new DualHeaderIcon(first, JBUI.scale(HEADER_DUAL_ICON_GAP_PX), second);
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
        int dragFloor = Math.min(minFloor, JBUI.scale(MIN_WATERFILL_COLUMN_WIDTH_PX));
        int safeFloor = Math.max(JBUI.scale(32), Math.min(width, Math.max(dragFloor, JBUI.scale(MIN_WATERFILL_COLUMN_WIDTH_PX))));
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
            Icon sortIcon = comp instanceof JLabel label ? label.getIcon() : null;
            configureHeaderComponent(comp, isUseDepth, isModelColumnLocked(modelCol), sortIcon);
            if (comp instanceof JComponent jc) {
                jc.setToolTipText(isUseDepth ? resolveUseDepthHeaderTooltip(activeScopeSupplier.get()) : null);
            }
            return comp;
        }
    }

    private final class StvnNamespaceCellRenderer extends DefaultTableCellRenderer {
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
            setBorder(JBUI.Borders.empty(0, CELL_HORIZONTAL_INSET_PX));

            String fullText = value == null ? "" : value.toString();
            if (measuringUnconstrainedPreferredWidth
                    || row < 0
                    || column < 0
                    || column >= tbl.getColumnCount()
                    || fullText.isEmpty()) {
                setText(fullText);
                setToolTipText(null);
                return comp;
            }

            int cellWidth = tbl.getCellRect(row, column, false).width;
            if (cellWidth <= 0) {
                cellWidth = tbl.getColumnModel().getColumn(column).getWidth() - tbl.getIntercellSpacing().width;
            }
            if (cellWidth <= 0) {
                setText(fullText);
                setToolTipText(null);
                return comp;
            }

            Insets insets = getInsets();
            int availableTextWidth = Math.max(0, cellWidth - insets.left - insets.right);
            FontMetrics fm = getFontMetrics(getFont());
            StvnCellTextEllipsizer.EllipsisResult ellipsized = StvnCellTextEllipsizer.ellipsize(
                fullText,
                availableTextWidth,
                fm::stringWidth,
                ellipsisModeSupplier.get(),
                preservedSuffixLengthSupplier.getAsInt()
            );
            setText(ellipsized.displayText());
            setToolTipText(ellipsized.truncated() ? fullText : null);
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
