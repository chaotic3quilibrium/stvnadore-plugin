package org.stvnadore.plugin.browser;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.JBIntSpinner;
import com.intellij.util.ui.JBUI;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.StvnBundle;
import org.stvnadore.plugin.settings.NamespaceEllipsisMode;
import org.stvnadore.plugin.settings.StvnConfigurable;
import org.stvnadore.plugin.settings.StvnSettings;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.ListCellRenderer;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.border.TitledBorder;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end IntelliJ Platform regression test suite verifying:
 * <ul>
 *   <li><b>Target 1:</b> Compact default-locked {@code Use Depth} column sizing, dual-{@code EmptyBorder}
 *       header inset deduplication, and zero-clipping sorted header width guarantees.</li>
 *   <li><b>Target 2:</b> Finder-style {@code MIDDLE} and {@code TRAILING} {@code U+2026} cell truncation across
 *       all columns, unlocked {@code minWidth} narrow-drag support, full-string hover tooltips, unconstrained
 *       right-sizing isolation, and raw-model action/filter invariance.</li>
 *   <li><b>Target 3:</b> {@code Settings | Languages & Frameworks | STVN} {@code Namespace Dependencies Browser}
 *       UI controls ({@code ComboBox<NamespaceEllipsisMode>} + dynamic {@code JBIntSpinner}), literal {@code "&"}
 *       bundle preservation, persistence round-trips, and {@code Math.clamp(val, 4, 32)} XML sanitization.</li>
 *   <li><b>Target 4:</b> Multi-column bounded water-filling width compression on {@code Alt+Shift+R} and
 *       {@code Alt+R} Stage 2 guaranteeing {@code 100%} {@code Use Depth} visibility on 38-row Prelude tables
 *       and natural toggle cycling with unconstrained {@code Alt+R} Stage 1.</li>
 * </ul>
 */
@NullMarked
public final class StvnNamespaceBrowserEllipsisCompactUseDepthAndWaterFillTest extends BasePlatformTestCase {

    private static final String SUBSEA_STAGE6_DOC = """
        {
          :meta {
            #name   "v11_stage06_wavelength_grid"
            #domain "subsea_telemetry"
            #kind   #BODY
          }
          :defs {
            :LaserState           :Enum[#OFF #COLD_STANDBY #WARMUP #LOCKED #DEGRADED]
            :PoweredLaserState    { #filterExcl [#OFF] } :LaserState
            :TelemetryActiveState { #filterIncl [#LOCKED #DEGRADED] } :PoweredLaserState
            :FiberCoreId          { #size 64 #minIncl 190.0 #maxIncl 196.5 } :Float
            #PRIMARY_CORE_THZ     :FiberCoreId 192.10
            :PilotToneHistory     { #minSize 1 #maxSize 8 } :Seq(:FiberCoreId)
            :RepeaterLaserBank    { #minSize 1 #maxSize 16 } :Seq(:TelemetryActiveState)
            :WavelengthGrid       :Map(:String :PilotToneHistory)
            :SubseaTelemetryFrame :Tuple(:RepeaterLaserBank :WavelengthGrid :FiberCoreId)
          }
          :type :SubseaTelemetryFrame
          :body (
            [#LOCKED #DEGRADED]
            {["ch_01" [#PRIMARY_CORE_THZ]]}
            #PRIMARY_CORE_THZ
          )
        }
        """;

    /**
     * Verifies compact {@code Use Depth} header sizing, horizontal border deduplication across columns {@code 0..4},
     * and zero header clipping when sorted ascending or descending.
     *
     * @throws Exception if async browser snapshot collection times out
     */
    public void testTarget1_CompactLockedUseDepthScavengesPaddingAndNeverClipsWhenSorted() throws Exception {
        PsiFile file = myFixture.configureByText("v11_stage06_wavelength_grid.subsea_telemetry.stvn", SUBSEA_STAGE6_DOC);
        var disposable = Disposer.newDisposable("Target1CompactUseDepthTest");
        try {
            var ui = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(), file, StvnNamespaceScope.DEFS, disposable
            );
            ui.waitForAnalysis(5, TimeUnit.SECONDS);

            JTableHeader header = ui.table().getTableHeader();
            assertNotNull(header);
            TableColumn depthCol = ui.table().getColumnModel().getColumn(StvnNamespaceTableModel.COL_USE_DEPTH);
            int exactDepthWidth = ui.columnManager().getExactUseDepthHeaderWidth();
            assertEquals(exactDepthWidth, depthCol.getWidth());

            // Verify normalized horizontal header insets: 8px/8px on columns 0..3, compact 6px/8px on column 4
            for (int col = 0; col <= 3; col++) {
                JLabel hdr = (JLabel) header.getDefaultRenderer().getTableCellRendererComponent(
                    ui.table(), ui.table().getColumnModel().getColumn(col).getHeaderValue(), false, false, -1, col
                );
                Insets ins = hdr.getInsets();
                assertEquals("Column " + col + " left header inset must equal JBUI.scale(8)", JBUI.scale(8), ins.left);
                assertEquals("Column " + col + " right header inset must equal JBUI.scale(8)", JBUI.scale(8), ins.right);
            }
            JLabel unsortedDepthHdr = (JLabel) header.getDefaultRenderer().getTableCellRendererComponent(
                ui.table(), depthCol.getHeaderValue(), false, false, -1, 4
            );
            Insets depthIns = unsortedDepthHdr.getInsets();
            assertEquals("Use Depth left header inset must equal compact JBUI.scale(6)", JBUI.scale(6), depthIns.left);
            assertEquals("Use Depth right header inset must equal JBUI.scale(8)", JBUI.scale(8), depthIns.right);
            assertTrue(unsortedDepthHdr.getPreferredSize().width <= exactDepthWidth);
            assertTrue(
                "Compact Use Depth width (" + exactDepthWidth + ") must stay lean without dead over-padding",
                exactDepthWidth <= JBUI.scale(116)
            );

            // Sort ascending and descending -> must fit cleanly inside exactDepthWidth with DualHeaderIcon and zero "..."
            for (SortOrder order : List.of(SortOrder.ASCENDING, SortOrder.DESCENDING)) {
                ui.table().getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(4, order)));
                JLabel sortedDepthHdr = (JLabel) header.getDefaultRenderer().getTableCellRendererComponent(
                    ui.table(), depthCol.getHeaderValue(), false, false, -1, 4
                );
                assertTrue(sortedDepthHdr.getIcon() instanceof StvnNamespaceColumnManager.DualHeaderIcon);
                assertTrue(
                    "Sorted (" + order + ") Use Depth preferred width (" + sortedDepthHdr.getPreferredSize().width
                        + ") must fit within exactDepthWidth (" + exactDepthWidth + ")",
                    sortedDepthHdr.getPreferredSize().width <= exactDepthWidth
                );
                assertEquals("Use Depth", sortedDepthHdr.getText());
            }
        } finally {
            Disposer.dispose(disposable);
        }
    }

    /**
     * Verifies multi-column manual narrow dragging, {@code MIDDLE} and {@code TRAILING} {@code U+2026} ellipses,
     * full-string hover tooltips, unconstrained right-size measurement isolation, and raw-model action/filter invariance.
     *
     * @throws Exception if async browser snapshot collection times out
     */
    public void testTarget2_MultiColumnNarrowDragMiddleAndTrailingEllipsesHoverTooltipsAndActionInvariance()
            throws Exception {
        var settings = StvnSettings.getInstance(getProject());
        var prevMode = settings.getState().browserEllipsisMode;
        int prevSuffix = settings.getState().browserPreservedSuffixChars;

        PsiFile file = myFixture.configureByText("v11_stage06_wavelength_grid.subsea_telemetry.stvn", SUBSEA_STAGE6_DOC);
        var disposable = Disposer.newDisposable("Target2EllipsisAndTooltipTest");
        try {
            settings.getState().browserEllipsisMode = NamespaceEllipsisMode.MIDDLE;
            settings.getState().browserPreservedSuffixChars = 12;

            var ui = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(), file, StvnNamespaceScope.DEFS, disposable
            );
            ui.waitForAnalysis(5, TimeUnit.SECONDS);

            // Enable Prelude (Alt+P) -> 38 rows including :SemVer and :org/stvnadore/prelude/meta/StvnDocumentMeta
            firePopupAction(ui.togglePreludeAction());
            assertEquals(38, ui.table().getRowCount());

            JTable table = ui.table();
            JTableHeader header = table.getTableHeader();
            ui.panel().setSize(new Dimension(920, 440));
            doLayoutRecursively(ui.panel());

            // Verify unlocked minWidth safeguard (JBUI.scale(56)) lets ALL 4 unlocked columns shrink down to 64..150px!
            int[] narrowWidths = {JBUI.scale(155), JBUI.scale(155), JBUI.scale(64), JBUI.scale(240)};
            for (int col = 0; col <= 3; col++) {
                TableColumn tc = table.getColumnModel().getColumn(col);
                assertEquals(JBUI.scale(56), tc.getMinWidth());
                tc.setPreferredWidth(narrowWidths[col]);
                tc.setWidth(narrowWidths[col]);
                assertEquals("Column " + col + " must accept narrow width without clamping to header width",
                    narrowWidths[col], tc.getWidth());
            }
            header.setSize(header.getPreferredSize().width, Math.max(28, header.getPreferredSize().height));
            header.doLayout();
            table.setSize(table.getPreferredSize());
            table.doLayout();

            // 1. Check MIDDLE ellipsis + tooltip on :org/stvnadore/prelude/meta/StvnDocumentMeta (Column 0)
            int docMetaRow = findVisualRowByName(table, ui.tableModel(), ":org/stvnadore/prelude/meta/StvnDocumentMeta");
            assertTrue(docMetaRow >= 0);
            JLabel docMetaCell = prepareCellLabel(table, docMetaRow, 0);
            assertTrue("Narrowed Name cell must render U+2026: " + docMetaCell.getText(),
                docMetaCell.getText().contains("\u2026"));
            assertFalse(docMetaCell.getText().contains("..."));
            assertTrue("MIDDLE mode must preserve 12-char suffix 'DocumentMeta': " + docMetaCell.getText(),
                docMetaCell.getText().endsWith("DocumentMeta"));
            assertEquals(":org/stvnadore/prelude/meta/StvnDocumentMeta", docMetaCell.getToolTipText());
            assertEquals(":org/stvnadore/prelude/meta/StvnDocumentMeta", hoverCellTooltip(table, docMetaRow, 0));

            // 2. Check MIDDLE ellipsis + tooltip on local Source filename (Column 1)
            int subseaFrameRow = findVisualRowByName(table, ui.tableModel(), ":SubseaTelemetryFrame");
            assertTrue(subseaFrameRow >= 0);
            JLabel sourceCell = prepareCellLabel(table, subseaFrameRow, 1);
            assertTrue("Narrowed Source cell must render U+2026: " + sourceCell.getText(),
                sourceCell.getText().contains("\u2026"));
            assertTrue("MIDDLE mode must preserve '.stvn' tail: " + sourceCell.getText(),
                sourceCell.getText().endsWith(".stvn"));
            assertEquals("v11_stage06_wavelength_grid.subsea_telemetry.stvn", sourceCell.getToolTipText());

            // 3. Check MIDDLE ellipsis + tooltip on :SemVer 251-char #regex (Column 3)
            int semVerRow = findVisualRowByName(table, ui.tableModel(), ":org/stvnadore/prelude/SemVer");
            assertTrue(semVerRow >= 0);
            String rawSemVerSchema = ui.tableModel().getItem(table.convertRowIndexToModel(semVerRow)).resolvedType();
            JLabel semVerCell = prepareCellLabel(table, semVerRow, 3);
            assertTrue(semVerCell.getText().contains("\u2026"));
            assertFalse(semVerCell.getText().contains("..."));
            assertTrue("MIDDLE mode on :SemVer must preserve '$\" } :String': " + semVerCell.getText(),
                semVerCell.getText().endsWith("$\" } :String"));
            assertEquals(rawSemVerSchema, semVerCell.getToolTipText());
            assertEquals(rawSemVerSchema, hoverCellTooltip(table, semVerRow, 3));

            // 4. Switch live to TRAILING mode -> preserves prefix only + "…", keeping full hover tooltip
            settings.getState().browserEllipsisMode = NamespaceEllipsisMode.TRAILING;
            JLabel semVerTrailing = prepareCellLabel(table, semVerRow, 3);
            assertTrue(semVerTrailing.getText().endsWith("\u2026"));
            assertFalse(semVerTrailing.getText().endsWith(":String"));
            assertEquals(rawSemVerSchema, semVerTrailing.getToolTipText());
            settings.getState().browserEllipsisMode = NamespaceEllipsisMode.MIDDLE;

            // 5. Verify untruncated short cell (:LaserState in Column 0 at 155px) has null tooltip and zero "…"
            int laserRow = findVisualRowByName(table, ui.tableModel(), ":LaserState");
            assertTrue(laserRow >= 0);
            JLabel laserNameCell = prepareCellLabel(table, laserRow, 0);
            assertEquals(":LaserState", laserNameCell.getText());
            assertNull("Non-truncated cell must set tooltip to null", laserNameCell.getToolTipText());
            assertNull(hoverCellTooltip(table, laserRow, 0));

            // 6. Action & Filter Invariance while cells are truncated with "…"
            var docMetaEntry = ui.tableModel().getItem(table.convertRowIndexToModel(docMetaRow));
            StvnNamespaceBrowserPopup.copyFqniToClipboard(getProject(), docMetaEntry);
            assertEquals(
                ":org/stvnadore/prelude/meta/StvnDocumentMeta",
                CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)
            );

            // SearchTextField query matching elided middle substring ("prelude/meta" hidden behind "…")
            ui.filterField().setText("prelude/meta");
            assertTrue("Filter must match raw untruncated strings even when elided visually", table.getRowCount() >= 2);
            ui.filterField().setText("");
            assertEquals(38, table.getRowCount());

            // Insert Reference (:use) and Brand Nominal Type on separate scratch copy verify raw symbol strings
            PsiFile actionDoc = myFixture.configureByText("action_invariance_check.stvn", """
                {
                  :defs {
                    :Existing :Int
                  }
                  :type :Existing
                  :body 1
                }
                """);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                StvnNamespaceBrowserPopup.insertUseReferenceInEditor(getProject(), actionDoc, docMetaEntry);
                StvnNamespaceBrowserPopup.insertNominalBrandInDefs(getProject(), actionDoc, docMetaEntry);
            });
            assertTrue(actionDoc.getText().contains(":use [ :org/stvnadore/prelude/meta { :StvnDocumentMeta :StvnDocumentMeta } ]"));
            assertTrue(actionDoc.getText().contains(":StvnDocumentMeta :org/stvnadore/prelude/meta/StvnDocumentMeta"));

            // 7. Right-size unconstrained isolation: pressing Alt+R Stage 1 expands narrowed columns to full natural width!
            firePopupAction(ui.rightSizeAllUnlockedAction());
            table.setSize(table.getPreferredSize());
            table.doLayout();
            assertTrue("Alt+R Stage 1 must expand Column 3 to full unconstrained :SemVer width (> 1200px)",
                table.getColumnModel().getColumn(3).getWidth() > 1200);
            JLabel expandedDocMeta = prepareCellLabel(table, docMetaRow, 0);
            assertEquals(":org/stvnadore/prelude/meta/StvnDocumentMeta", expandedDocMeta.getText());
            assertNull(expandedDocMeta.getToolTipText());

            // 8. Unlocking Use Depth also allows narrow shrinking with clean ellipsis
            var depthMenu = ui.columnManager().buildHeaderContextMenu(4);
            var lockItem = (JCheckBoxMenuItem) depthMenu.getComponent(0);
            assertTrue(lockItem.isSelected());
            lockItem.doClick();
            assertFalse(ui.columnManager().isViewColumnLocked(4));
            assertEquals(JBUI.scale(56), table.getColumnModel().getColumn(4).getMinWidth());
        } finally {
            settings.getState().browserEllipsisMode = prevMode;
            settings.getState().browserPreservedSuffixChars = prevSuffix;
            Disposer.dispose(disposable);
        }
    }

    /**
     * Verifies {@link StvnConfigurable} {@code Namespace Dependencies Browser} UI controls, literal {@code "&"}
     * bundle rendering, dynamic {@code JBIntSpinner} enable/disable binding, and {@code Math.clamp(val, 4, 32)}
     * XML deserialization safety.
     */
    public void testTarget3_StvnConfigurableBrowserSectionBundleAmpersandSpinnerEnablementAndXmlClamping() {
        // 1. Verify StvnBundle preserves literal '&' without stripping or injecting 0x1B mnemonic markers
        assertEquals(
            "Middle \u2014 Preserve Prefix & Suffix (Default)",
            StvnBundle.message("stvn.settings.browser.ellipsis.middle")
        );
        assertEquals(
            "Trailing \u2014 Preserve Prefix Only",
            StvnBundle.message("stvn.settings.browser.ellipsis.trailing")
        );

        var settings = StvnSettings.getInstance(getProject());
        var prevMode = settings.getState().browserEllipsisMode;
        int prevSuffix = settings.getState().browserPreservedSuffixChars;
        try {
            // 2. Verify loadState null & out-of-range Math.clamp(val, 4, 32) sanitization
            var corruptLow = new StvnSettings.State();
            corruptLow.browserEllipsisMode = null;
            corruptLow.browserPreservedSuffixChars = -42;
            settings.loadState(corruptLow);
            assertEquals(NamespaceEllipsisMode.MIDDLE, settings.getBrowserEllipsisMode());
            assertEquals(4, settings.getBrowserPreservedSuffixChars());

            var corruptHigh = new StvnSettings.State();
            corruptHigh.browserEllipsisMode = NamespaceEllipsisMode.TRAILING;
            corruptHigh.browserPreservedSuffixChars = 999;
            settings.loadState(corruptHigh);
            assertEquals(NamespaceEllipsisMode.TRAILING, settings.getBrowserEllipsisMode());
            assertEquals(32, settings.getBrowserPreservedSuffixChars());

            // Reset to clean defaults (MIDDLE, 12) before testing StvnConfigurable UI
            var defaultState = new StvnSettings.State();
            settings.loadState(defaultState);

            StvnConfigurable configurable = new StvnConfigurable(getProject());
            JComponent root = configurable.createComponent();
            assertNotNull(root);
            assertFalse(configurable.isModified());

            JPanel browserGroup = findTitledPanel(root, "Namespace Dependencies Browser");
            assertNotNull("Expected 'Namespace Dependencies Browser' titled group in StvnConfigurable", browserGroup);

            ComboBox<NamespaceEllipsisMode> combo = findEllipsisCombo(browserGroup);
            JBIntSpinner spinner = findIntSpinner(browserGroup);
            List<String> groupLabels = new ArrayList<>();
            collectLabels(browserGroup, groupLabels);

            assertNotNull(combo);
            assertNotNull(spinner);
            assertTrue(groupLabels.contains("Cell ellipsis placement:"));
            assertTrue(groupLabels.contains("Preserved suffix length (characters):"));
            assertEquals(NamespaceEllipsisMode.MIDDLE, combo.getSelectedItem());
            assertEquals(12, spinner.getNumber());
            assertEquals(4, spinner.getMin());
            assertEquals(32, spinner.getMax());
            assertTrue("Spinner must be enabled when MIDDLE is selected", spinner.isEnabled());

            // Verify ComboBox cell renderer renders exact localized display strings including '&'
            @SuppressWarnings("unchecked")
            ListCellRenderer<Object> renderer = (ListCellRenderer<Object>) combo.getRenderer();
            JList<Object> dummyList = new JList<>();
            String middleRenderedText = ((JLabel) renderer.getListCellRendererComponent(
                dummyList, NamespaceEllipsisMode.MIDDLE, 0, false, false
            )).getText();
            String trailingRenderedText = ((JLabel) renderer.getListCellRendererComponent(
                dummyList, NamespaceEllipsisMode.TRAILING, 1, false, false
            )).getText();
            assertEquals("Middle \u2014 Preserve Prefix & Suffix (Default)", middleRenderedText);
            assertEquals("Trailing \u2014 Preserve Prefix Only", trailingRenderedText);

            // Selecting TRAILING disables JBIntSpinner and marks configurable modified
            combo.setSelectedItem(NamespaceEllipsisMode.TRAILING);
            assertFalse("Spinner must disable dynamically when TRAILING is selected", spinner.isEnabled());
            assertTrue(configurable.isModified());

            // Selecting MIDDLE re-enables JBIntSpinner; changing spinner to 18 applies cleanly
            combo.setSelectedItem(NamespaceEllipsisMode.MIDDLE);
            assertTrue("Spinner must re-enable dynamically when MIDDLE is re-selected", spinner.isEnabled());
            spinner.setNumber(18);
            assertTrue(configurable.isModified());

            configurable.apply();
            assertFalse(configurable.isModified());
            assertEquals(NamespaceEllipsisMode.MIDDLE, settings.getBrowserEllipsisMode());
            assertEquals(18, settings.getBrowserPreservedSuffixChars());

            // Unsaved edits revert cleanly on reset(), including spinner enabled state
            combo.setSelectedItem(NamespaceEllipsisMode.TRAILING);
            spinner.setNumber(24);
            assertFalse(spinner.isEnabled());
            assertTrue(configurable.isModified());
            configurable.reset();
            assertFalse(configurable.isModified());
            assertEquals(NamespaceEllipsisMode.MIDDLE, combo.getSelectedItem());
            assertEquals(18, spinner.getNumber());
            assertTrue(spinner.isEnabled());

            configurable.disposeUIResources();
            assertFalse(configurable.isModified());
        } finally {
            settings.getState().browserEllipsisMode = prevMode;
            settings.getState().browserPreservedSuffixChars = prevSuffix;
        }
    }

    /**
     * Verifies {@code Alt+Shift+R} and {@code Alt+R} Stage 2 water-filling width compression on 38-row Prelude
     * tables across constrained monitors ({@code 1920x1080} and {@code 1366x768}), guaranteeing {@code 100%}
     * visibility of {@code Use Depth} inside {@code viewport.getExtentSize().width} and deterministic cycling
     * with unconstrained {@code Alt+R} Stage 1.
     *
     * @throws Exception if async browser snapshot collection times out
     */
    public void testTarget4_Prelude38RowWaterFillFitGuarantees100PercentUseDepthVisibilityAndCyclesWithAltR()
            throws Exception {
        PsiFile file = myFixture.configureByText("v11_stage06_wavelength_grid.subsea_telemetry.stvn", SUBSEA_STAGE6_DOC);
        var disposable = Disposer.newDisposable("Target4WaterFillWindowFitTest");
        try {
            var ui = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(), file, StvnNamespaceScope.DEFS, disposable
            );
            ui.waitForAnalysis(5, TimeUnit.SECONDS);
            firePopupAction(ui.togglePreludeAction());
            assertEquals(38, ui.table().getRowCount());

            JPanel outerPopupMock = new JPanel(new BorderLayout());
            outerPopupMock.setBorder(JBUI.Borders.empty(6, 6, 8, 6));
            JPanel mockCaptionBar = new JPanel();
            mockCaptionBar.setPreferredSize(new Dimension(400, JBUI.scale(44)));
            outerPopupMock.add(mockCaptionBar, BorderLayout.NORTH);
            outerPopupMock.add(ui.panel(), BorderLayout.CENTER);

            AtomicReference<Rectangle> windowBoundsRef = new AtomicReference<>(new Rectangle(220, 100, 920, 440));
            AtomicReference<Rectangle> usableScreenRef = new AtomicReference<>(new Rectangle(0, 0, 1920, 1080));

            Runnable layoutOuterPopup = () -> {
                Rectangle b = windowBoundsRef.get();
                outerPopupMock.setBounds(0, 0, b.width, b.height);
                doLayoutRecursively(outerPopupMock);
                JTableHeader th = ui.table().getTableHeader();
                if (th != null) {
                    th.setSize(
                        Math.max(th.getWidth(), th.getPreferredSize().width),
                        Math.max(28, th.getPreferredSize().height)
                    );
                    th.doLayout();
                }
                ui.table().setSize(ui.table().getPreferredSize());
                ui.table().doLayout();
            };
            layoutOuterPopup.run();

            ui.popupWindowBridgeRef().set(new StvnNamespaceBrowserPopup.PopupWindowBridge() {
                @Override
                public @Nullable Rectangle getCurrentBounds() {
                    return windowBoundsRef.get();
                }

                @Override
                public Rectangle getUsableScreenBounds(Point anchorScreenPoint) {
                    return usableScreenRef.get();
                }

                @Override
                public void applyBounds(Rectangle targetBounds) {
                    windowBoundsRef.set(targetBounds);
                    layoutOuterPopup.run();
                }
            });

            int naturalCol0 = ui.columnManager().computeRightSizedWidth(0);
            int naturalCol1 = ui.columnManager().computeRightSizedWidth(1);
            int naturalCol2 = ui.columnManager().computeRightSizedWidth(2);
            int naturalCol3 = ui.columnManager().computeRightSizedWidth(3);
            int lockedCol4 = ui.columnManager().getExactUseDepthHeaderWidth();

            // Sanity: unconstrained 38-row Prelude width exceeds 1920 - 48 = 1872px due to :SemVer's 251-char #regex
            assertTrue(
                "Unconstrained total width must exceed 1872px monitor ceiling",
                naturalCol0 + naturalCol1 + naturalCol2 + naturalCol3 + lockedCol4 > 1872
            );

            // 1. Invoke Alt+Shift+R on 1920x1080 -> water-fills Col 3 while keeping Cols 0..2 at 100% natural width
            //    and guaranteeing 100% of locked Use Depth sits inside viewport.getExtentSize().width!
            firePopupAction(ui.fitWindowToContentAction());

            JViewport viewport = ui.scrollPane().getViewport();
            Dimension extent1920 = viewport.getExtentSize();
            assertEquals("Column 0 must retain 100% natural width", naturalCol0, ui.table().getColumnModel().getColumn(0).getWidth());
            assertEquals("Column 1 must retain 100% natural width", naturalCol1, ui.table().getColumnModel().getColumn(1).getWidth());
            assertEquals("Column 2 must retain 100% natural width", naturalCol2, ui.table().getColumnModel().getColumn(2).getWidth());
            int cappedCol3 = ui.table().getColumnModel().getColumn(3).getWidth();
            assertTrue("Column 3 (:SemVer) must be water-fill-capped (< naturalCol3)", cappedCol3 < naturalCol3);
            assertTrue("Column 3 (:SemVer) must still receive generous remaining width (> 600px)", cappedCol3 > 600);
            assertEquals("Locked Use Depth width must remain untouched", lockedCol4, ui.table().getColumnModel().getColumn(4).getWidth());

            assertTrue(
                "Total fitted columns (" + ui.columnManager().computeTotalColumnsWidth()
                    + ") must fit 100% inside viewport extent width (" + extent1920.width + ")",
                ui.columnManager().computeTotalColumnsWidth() <= extent1920.width
            );
            Rectangle useDepthCellRect = ui.table().getCellRect(0, 4, true);
            assertTrue(
                "Right edge of Use Depth column (" + (useDepthCellRect.x + useDepthCellRect.width)
                    + ") must be 100% visible inside viewport (" + extent1920.width + ") with 0px clipping",
                useDepthCellRect.x + useDepthCellRect.width <= extent1920.width
            );

            // Capped :SemVer cell displays clean Finder-style middle ellipsis + full hover tooltip
            int semVerRow = findVisualRowByName(ui.table(), ui.tableModel(), ":org/stvnadore/prelude/SemVer");
            JLabel semVerLabel = prepareCellLabel(ui.table(), semVerRow, 3);
            assertTrue(semVerLabel.getText().contains("\u2026"));
            assertTrue(semVerLabel.getText().endsWith("$\" } :String"));
            assertNotNull(semVerLabel.getToolTipText());

            // 2. Toggle Cycle Verification:
            //    Alt+R Press 1 -> detects capped Col 3 < naturalCol3 and expands to full unconstrained width!
            assertFalse(ui.columnManager().areAllUnlockedColumnsRightSized());
            firePopupAction(ui.rightSizeAllUnlockedAction());
            assertTrue(ui.columnManager().areAllUnlockedColumnsRightSized());
            assertEquals(naturalCol3, ui.table().getColumnModel().getColumn(3).getWidth());

            //    Alt+R Press 2 -> detects all unlocked columns right-sized and snaps back to water-filled screen fit!
            firePopupAction(ui.rightSizeAllUnlockedAction());
            assertFalse(ui.columnManager().areAllUnlockedColumnsRightSized());
            assertTrue(ui.columnManager().computeTotalColumnsWidth() <= viewport.getExtentSize().width);
            Rectangle useDepthAfterCycle = ui.table().getCellRect(0, 4, true);
            assertTrue(useDepthAfterCycle.x + useDepthAfterCycle.width <= viewport.getExtentSize().width);

            // 3. Constrained 1100x768 laptop screen where >= 2 unlocked columns exceed uniform cap C:
            usableScreenRef.set(new Rectangle(0, 0, 1100, 768));
            windowBoundsRef.set(new Rectangle(100, 60, 820, 400));
            layoutOuterPopup.run();
            firePopupAction(ui.fitWindowToContentAction());

            Dimension extent1100 = viewport.getExtentSize();
            assertTrue(
                "Even on 1100x768 screen, water-filled columns (" + ui.columnManager().computeTotalColumnsWidth()
                    + ") must fit 100% inside viewport (" + extent1100.width + ")",
                ui.columnManager().computeTotalColumnsWidth() <= extent1100.width
            );
            Rectangle useDepth1100 = ui.table().getCellRect(0, 4, true);
            assertTrue(useDepth1100.x + useDepth1100.width <= extent1100.width);
        } finally {
            Disposer.dispose(disposable);
        }
    }

    private static JLabel prepareCellLabel(JTable table, int visualRow, int visualCol) {
        TableCellRenderer renderer = table.getCellRenderer(visualRow, visualCol);
        return (JLabel) table.prepareRenderer(renderer, visualRow, visualCol);
    }

    private static @Nullable String hoverCellTooltip(JTable table, int visualRow, int visualCol) {
        Rectangle rect = table.getCellRect(visualRow, visualCol, false);
        MouseEvent move = new MouseEvent(
            table,
            MouseEvent.MOUSE_MOVED,
            0L,
            0,
            rect.x + Math.max(2, rect.width / 2),
            rect.y + Math.max(2, rect.height / 2),
            0,
            false
        );
        return table.getToolTipText(move);
    }

    private static int findVisualRowByName(JTable table, StvnNamespaceTableModel model, String targetName) {
        for (int visualRow = 0; visualRow < table.getRowCount(); visualRow++) {
            int modelRow = table.convertRowIndexToModel(visualRow);
            if (targetName.equals(model.getItem(modelRow).name())) {
                return visualRow;
            }
        }
        return -1;
    }

    private static @Nullable JPanel findTitledPanel(Component root, String expectedTitle) {
        if (root instanceof JPanel panel && panel.getBorder() instanceof TitledBorder tb
                && expectedTitle.equals(tb.getTitle())) {
            return panel;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JPanel found = findTitledPanel(child, expectedTitle);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable ComboBox<NamespaceEllipsisMode> findEllipsisCombo(Component root) {
        if (root instanceof ComboBox<?> combo
                && combo.getItemCount() > 0
                && combo.getItemAt(0) instanceof NamespaceEllipsisMode) {
            return (ComboBox<NamespaceEllipsisMode>) combo;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                ComboBox<NamespaceEllipsisMode> found = findEllipsisCombo(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static @Nullable JBIntSpinner findIntSpinner(Component root) {
        if (root instanceof JBIntSpinner spinner) {
            return spinner;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JBIntSpinner found = findIntSpinner(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void collectLabels(Component root, List<String> labels) {
        if (root instanceof JLabel label && label.getText() != null) {
            labels.add(label.getText());
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectLabels(child, labels);
            }
        }
    }

    private static void firePopupAction(AnAction action) {
        AnActionEvent event = TestActionEvent.createTestEvent(action);
        action.update(event);
        if (event.getPresentation().isEnabled()) {
            action.actionPerformed(event);
        }
    }

    private static void doLayoutRecursively(Component component) {
        component.doLayout();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                doLayoutRecursively(child);
            }
            container.doLayout();
        }
    }
}
