package org.stvnadore.plugin.browser;

import com.intellij.openapi.MnemonicHelper;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnBundle;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.ScrollPaneConstants;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive platform test suite verifying {@code STVN Namespace Dependencies} UX polish,
 * structured 3-row header layout, mnemonic-suffix-free {@code :defs} / {@code :type} / {@code :body}
 * radio buttons, popup-scoped {@code Alt+1..3} shortcuts, Google-Sheets-style column sizing and
 * width locking, divider double-click right-sizing without sort-toggle interference, and
 * shortest-path symbol-hop {@code Use Depth} across Formatting Probes 1, 2, and 3.
 */
@NullMarked
public final class StvnNamespaceBrowserUxAndDepthParityTest extends BasePlatformTestCase {

    public void testTarget1_StaticPopupTitleThreeRowHeaderAndHomonymousFilePathTooltips() throws Exception {
        String docAlpha = """
            {
              :meta {
                #name "config"
                #domain "finance"
                #kind #BODY
              }
              :defs {
                :AlphaId :Int
              }
              :type :AlphaId
              :body 10
            }
            """;
        String docBeta = """
            {
              :meta {
                #name "config"
                #domain "finance"
                #kind #BODY
              }
              :defs {
                :BetaId :String
              }
              :type :BetaId
              :body "beta"
            }
            """;

        PsiFile fileAlpha = myFixture.addFileToProject("alpha/config.finance.stvn", docAlpha);
        PsiFile fileBeta = myFixture.addFileToProject("beta/config.finance.stvn", docBeta);

        var disposable = Disposer.newDisposable("Target1HeaderTest");
        try {
            var uiAlpha = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(), fileAlpha, StvnNamespaceScope.DEFS, disposable
            );
            var uiBeta = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(), fileBeta, StvnNamespaceScope.DEFS, disposable
            );
            uiAlpha.waitForAnalysis(5, TimeUnit.SECONDS);
            uiBeta.waitForAnalysis(5, TimeUnit.SECONDS);

            // 1. Static popup caption strictly equals StvnBundle.message("stvn.browser.title")
            assertEquals("STVN Namespace Dependencies", StvnBundle.message("stvn.browser.title"));
            assertEquals("STVN Namespace Dependencies", uiAlpha.popupTitle());
            assertFalse(uiAlpha.popupTitle().contains("config.finance.stvn"));
            assertFalse(uiAlpha.popupTitle().contains("[:"));

            // 2. Homonymous filenames display bold "File:" + plain "config.finance.stvn" with folder-disambiguated tooltips
            assertEquals("File:", uiAlpha.filePrefixLabel().getText());
            assertEquals("config.finance.stvn", uiAlpha.fileLabel().getText());
            assertEquals("File: config.finance.stvn", uiAlpha.fullFileHeaderText());
            assertEquals("File: config.finance.stvn", uiBeta.fullFileHeaderText());
            assertEquals("alpha/config.finance.stvn", uiAlpha.fileLabel().getToolTipText());
            assertEquals("beta/config.finance.stvn", uiBeta.fileLabel().getToolTipText());

            // 3. Verify 3-row structured header layout inside BorderLayout.NORTH
            BorderLayout rootLayout = (BorderLayout) uiAlpha.panel().getLayout();
            Component northComp = rootLayout.getLayoutComponent(BorderLayout.NORTH);
            assertTrue(northComp instanceof JPanel);
            JPanel northPanel = (JPanel) northComp;
            // Row 1 (index 0), strut (1), Row 2 (index 2), strut (3), Row 3 (index 4)
            assertEquals(5, northPanel.getComponentCount());
            JPanel row1 = (JPanel) northPanel.getComponent(0);
            JPanel row2 = (JPanel) northPanel.getComponent(2);
            JPanel row3 = (JPanel) northPanel.getComponent(4);

            JPanel row1West = (JPanel) ((BorderLayout) row1.getLayout()).getLayoutComponent(BorderLayout.WEST);
            assertSame(uiAlpha.filePrefixLabel(), row1West.getComponent(0));
            assertSame(uiAlpha.fileLabel(), row1West.getComponent(2));
            assertSame(uiAlpha.showPreludeCheckBox(), ((BorderLayout) row1.getLayout()).getLayoutComponent(BorderLayout.EAST));

            JPanel row2West = (JPanel) ((BorderLayout) row2.getLayout()).getLayoutComponent(BorderLayout.WEST);
            assertSame(uiAlpha.scopeSummaryPrefixLabel(), row2West.getComponent(0));
            assertSame(uiAlpha.headerLabel(), row2West.getComponent(2));
            assertSame(uiAlpha.filterField(), ((BorderLayout) row3.getLayout()).getLayoutComponent(BorderLayout.CENTER));

            // 4. Switching scopes, filtering, and toggling Prelude never alter static title or File: row
            uiAlpha.scopeButtons().get(StvnNamespaceScope.TYPE).doClick();
            uiAlpha.filterField().setText(":AlphaId");
            uiAlpha.showPreludeCheckBox().doClick();
            assertEquals("STVN Namespace Dependencies", uiAlpha.popupTitle());
            assertEquals("STVN Namespace Dependencies", StvnNamespaceBrowserPopup.resolvePopupTitle());
            assertEquals("File: config.finance.stvn", uiAlpha.fullFileHeaderText());
            assertTrue(uiAlpha.fullScopeSummaryText().startsWith("Scope: :type \u2014 Root :type Schema Closure ("));
        } finally {
            Disposer.dispose(disposable);
        }
    }

    public void testTarget2_CleanRadioButtonsPopupShortcutsAndDefsKindGrayOut() throws Exception {
        String fullPayload = """
            {
              :meta {
                #name "full_payload"
                #domain "ops"
                #kind #BODY
              }
              :defs {
                :NodeCount :Int
                :DeadUnused :String
              }
              :type :NodeCount
              :body 42
            }
            """;
        // Malformed .stvn_d containing stray :type and :body — must still disable :type and :body!
        String malformedDefsFile = """
            {
              :meta {
                #name "malformed_defs"
                #domain "ops"
                #kind #DEFS
              }
              :defs {
                :package :ops/core {
                  :Metric :Int
                }
              }
              :type :Int
              :body 99
            }
            """;
        // .stvn file whose :meta explicitly declares #kind #DEFS_INCLUDE
        String metaDefsKindDoc = """
            {
              :meta {
                #name "meta_defs_kind"
                #domain "ops"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :SharedId :String
              }
            }
            """;

        PsiFile payloadPsi = myFixture.configureByText("full_payload.ops.stvn", fullPayload);
        PsiFile defsPsi = myFixture.configureByText("malformed_defs.ops.stvn_d", malformedDefsFile);
        PsiFile metaDefsPsi = myFixture.configureByText("meta_defs_kind.ops.stvn", metaDefsKindDoc);

        var disposable = Disposer.newDisposable("Target2ShortcutsAndTooltipsTest");
        try {
            // Part A: Enabled #BODY document — clean labels even after MnemonicHelper.init(panel) + Alt+1..3 switching
            var uiBody = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(), payloadPsi, StvnNamespaceScope.DEFS, disposable
            );
            uiBody.waitForAnalysis(5, TimeUnit.SECONDS);
            MnemonicHelper.init(uiBody.panel());

            JRadioButton defsBtn = uiBody.scopeButtons().get(StvnNamespaceScope.DEFS);
            JRadioButton typeBtn = uiBody.scopeButtons().get(StvnNamespaceScope.TYPE);
            JRadioButton bodyBtn = uiBody.scopeButtons().get(StvnNamespaceScope.BODY);

            assertEquals(":defs", defsBtn.getText());
            assertEquals(":type", typeBtn.getText());
            assertEquals(":body", bodyBtn.getText());
            assertEquals(0, defsBtn.getMnemonic());
            assertEquals(0, typeBtn.getMnemonic());
            assertEquals(0, bodyBtn.getMnemonic());

            assertEquals("Show all in-scope :defs symbols (Alt+1)", defsBtn.getToolTipText());
            assertEquals("Show root :type schema closure (Alt+2)", typeBtn.getToolTipText());
            assertEquals("Show payload and :type instantiated closure (Alt+3)", bodyBtn.getToolTipText());

            // Invoke Alt+2 -> :type, Alt+3 -> :body, Alt+1 -> :defs via popup-scoped DumbAwareActions
            firePopupAction(uiBody.scopeShortcutActions().get(StvnNamespaceScope.TYPE));
            assertEquals(StvnNamespaceScope.TYPE, uiBody.activeScopeRef().get());
            assertTrue(typeBtn.isSelected());
            assertEquals(1, uiBody.tableModel().getRowCount());

            firePopupAction(uiBody.scopeShortcutActions().get(StvnNamespaceScope.BODY));
            assertEquals(StvnNamespaceScope.BODY, uiBody.activeScopeRef().get());
            assertTrue(bodyBtn.isSelected());

            firePopupAction(uiBody.scopeShortcutActions().get(StvnNamespaceScope.DEFS));
            assertEquals(StvnNamespaceScope.DEFS, uiBody.activeScopeRef().get());
            assertTrue(defsBtn.isSelected());
            assertEquals(2, uiBody.tableModel().getRowCount());

            // Part B: .stvn_d module (even with stray :type / :body) + #kind #DEFS_INCLUDE — grayed out + strict no-op shortcuts
            for (PsiFile candidateDefs : List.of(defsPsi, metaDefsPsi)) {
                var uiDefs = StvnNamespaceBrowserPopup.createBrowserContent(
                    getProject(), candidateDefs, StvnNamespaceScope.BODY, disposable
                );
                uiDefs.waitForAnalysis(5, TimeUnit.SECONDS);
                MnemonicHelper.init(uiDefs.panel());

                JRadioButton dDefs = uiDefs.scopeButtons().get(StvnNamespaceScope.DEFS);
                JRadioButton dType = uiDefs.scopeButtons().get(StvnNamespaceScope.TYPE);
                JRadioButton dBody = uiDefs.scopeButtons().get(StvnNamespaceScope.BODY);

                assertEquals(StvnNamespaceScope.DEFS, uiDefs.activeScopeRef().get());
                assertTrue(dDefs.isEnabled());
                assertFalse(dType.isEnabled());
                assertFalse(dBody.isEnabled());

                // Verify Swing MouseEvent tooltip retrieval works on disabled JRadioButtons
                MouseEvent hoverType = new MouseEvent(dType, MouseEvent.MOUSE_MOVED, 0L, 0, 2, 2, 0, false);
                MouseEvent hoverBody = new MouseEvent(dBody, MouseEvent.MOUSE_MOVED, 0L, 0, 2, 2, 0, false);
                assertEquals(
                    "Unavailable: this document does not declare a root :type section.",
                    dType.getToolTipText(hoverType)
                );
                assertEquals(
                    "Unavailable: this document does not declare a root :body section.",
                    dBody.getToolTipText(hoverBody)
                );

                // Alt+2 and Alt+3 actions stay enabled on popup presentation (preventing IDE tool-window leak)
                // while acting as strict no-ops in actionPerformed!
                assertTrue(isPopupActionPresentationEnabled(uiDefs.scopeShortcutActions().get(StvnNamespaceScope.TYPE)));
                assertTrue(isPopupActionPresentationEnabled(uiDefs.scopeShortcutActions().get(StvnNamespaceScope.BODY)));

                firePopupAction(uiDefs.scopeShortcutActions().get(StvnNamespaceScope.TYPE));
                assertEquals(StvnNamespaceScope.DEFS, uiDefs.activeScopeRef().get());
                assertTrue(dDefs.isSelected());

                firePopupAction(uiDefs.scopeShortcutActions().get(StvnNamespaceScope.BODY));
                assertEquals(StvnNamespaceScope.DEFS, uiDefs.activeScopeRef().get());
                assertTrue(dDefs.isSelected());
            }
        } finally {
            Disposer.dispose(disposable);
        }
    }

    public void testTarget3_IndependentColumnSizingLockedUseDepthDividerDoubleClickAndHeaderLockMenu() throws Exception {
        Path probe3Path = resolveFormattingProbePath("fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn");
        String probe3Src = Files.readString(probe3Path);
        PsiFile file = myFixture.configureByText(
            "fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn",
            probe3Src
        );

        var disposable = Disposer.newDisposable("Target3ColumnSizingTest");
        try {
            var ui = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(), file, StvnNamespaceScope.DEFS, disposable
            );
            ui.waitForAnalysis(5, TimeUnit.SECONDS);

            var table = ui.table();
            var colMgr = ui.columnManager();
            JTableHeader header = table.getTableHeader();

            // Lay out components in headless environment so header rectangles and cell rectangles are non-empty
            ui.panel().setSize(new Dimension(920, 440));
            ui.panel().doLayout();
            header.setSize(header.getPreferredSize().width, Math.max(28, header.getPreferredSize().height));
            header.doLayout();
            table.setSize(table.getPreferredSize());
            table.doLayout();

            // 1. AUTO_RESIZE_OFF + autoCreateColumnsFromModel=false + HORIZONTAL_SCROLLBAR_AS_NEEDED
            assertEquals(JTable.AUTO_RESIZE_OFF, table.getAutoResizeMode());
            assertFalse(table.getAutoCreateColumnsFromModel());
            JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
            assertNotNull(scrollPane);
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, scrollPane.getHorizontalScrollBarPolicy());

            // 2. Requirement 3A: Default-locked Use Depth column at exactUseDepthHeaderWidth + right-aligned cells
            assertFalse(colMgr.isViewColumnLocked(0));
            assertFalse(colMgr.isViewColumnLocked(1));
            assertFalse(colMgr.isViewColumnLocked(2));
            assertFalse(colMgr.isViewColumnLocked(3));
            assertTrue(colMgr.isViewColumnLocked(4));

            TableColumn useDepthCol = table.getColumnModel().getColumn(4);
            int exactDepthWidth = colMgr.getExactUseDepthHeaderWidth();
            assertFalse(useDepthCol.getResizable());
            assertEquals(exactDepthWidth, useDepthCol.getWidth());
            assertEquals(exactDepthWidth, useDepthCol.getPreferredWidth());
            assertEquals(exactDepthWidth, useDepthCol.getMinWidth());
            assertEquals(exactDepthWidth, useDepthCol.getMaxWidth());

            TableCellRenderer depthCellRenderer = table.getCellRenderer(0, 4);
            JLabel depthCellComp = (JLabel) table.prepareRenderer(depthCellRenderer, 0, 4);
            assertEquals(SwingConstants.RIGHT, depthCellComp.getHorizontalAlignment());

            TableCellRenderer nameCellRenderer = table.getCellRenderer(0, 0);
            JLabel nameCellComp = (JLabel) table.prepareRenderer(nameCellRenderer, 0, 0);
            assertEquals(SwingConstants.LEFT, nameCellComp.getHorizontalAlignment());

            // Verify header renderer displays lock icon on Use Depth, and DualHeaderIcon without truncation when sorted!
            JLabel unsortedDepthHdr = (JLabel) header.getDefaultRenderer()
                .getTableCellRendererComponent(table, useDepthCol.getHeaderValue(), false, false, -1, 4);
            assertNotNull("Locked Use Depth header must display lock icon", unsortedDepthHdr.getIcon());
            assertTrue(unsortedDepthHdr.getPreferredSize().width <= exactDepthWidth);

            // Single-click center of Use Depth header body -> sorts ascending cleanly even while locked!
            Rectangle depthHdrRect = header.getHeaderRect(4);
            Point depthBodyCenter = new Point(depthHdrRect.x + depthHdrRect.width / 2, depthHdrRect.height / 2);
            assertEquals(-1, colMgr.findDividerViewColumn(header, depthBodyCenter));
            dispatchHeaderClicks(header, depthBodyCenter, 1);
            List<? extends RowSorter.SortKey> sortKeysAfterBodyClick = table.getRowSorter().getSortKeys();
            assertFalse(sortKeysAfterBodyClick.isEmpty());
            assertEquals(StvnNamespaceTableModel.COL_USE_DEPTH, sortKeysAfterBodyClick.get(0).getColumn());
            assertEquals(SortOrder.ASCENDING, sortKeysAfterBodyClick.get(0).getSortOrder());

            JLabel sortedDepthHdr = (JLabel) header.getDefaultRenderer()
                .getTableCellRendererComponent(table, useDepthCol.getHeaderValue(), false, false, -1, 4);
            assertTrue(
                "Sorted locked column header must compose both lock and sort icons",
                sortedDepthHdr.getIcon() instanceof StvnNamespaceColumnManager.DualHeaderIcon
            );
            assertTrue(
                "exactUseDepthHeaderWidth must fit 'Use Depth' + lock icon + sort arrow without clipping",
                sortedDepthHdr.getPreferredSize().width <= exactDepthWidth
            );

            // 3. Truncated-cell hover tooltip + Requirement 3B divider double-click right-sizing without sort toggle!
            TableColumn col0 = table.getColumnModel().getColumn(0);
            TableColumn col1 = table.getColumnModel().getColumn(1);
            TableColumn col2 = table.getColumnModel().getColumn(2);
            TableColumn col3 = table.getColumnModel().getColumn(3);

            // Shrink Column 3 (Resolved Type) manually so :IndexedOutcome's long :Union(...) truncates
            col3.setPreferredWidth(110);
            col3.setWidth(110);
            table.setSize(table.getPreferredSize());
            table.doLayout();
            header.setSize(header.getPreferredSize().width, Math.max(28, header.getPreferredSize().height));
            header.doLayout();

            int indexedOutcomeRow = findVisualRowByName(table, ui.tableModel(), ":IndexedOutcome");
            assertTrue(indexedOutcomeRow >= 0);
            Rectangle cellRectNarrow = table.getCellRect(indexedOutcomeRow, 3, false);
            MouseEvent hoverTruncated = new MouseEvent(
                table,
                MouseEvent.MOUSE_MOVED,
                0L,
                0,
                cellRectNarrow.x + 10,
                cellRectNarrow.y + cellRectNarrow.height / 2,
                0,
                false
            );
            String truncatedTooltip = table.getToolTipText(hoverTruncated);
            assertNotNull("Truncated Resolved Type cell must expose full-value hover tooltip", truncatedTooltip);
            assertTrue(truncatedTooltip.startsWith(":Union(:Int :String :Boolean :ChannelStatus :PacketHeaderTag"));

            int w0Before = col0.getWidth();
            int w1Before = col1.getWidth();
            int w2Before = col2.getWidth();
            int w4Before = useDepthCol.getWidth();
            List<? extends RowSorter.SortKey> sortKeysBeforeDividerDblClick = List.copyOf(table.getRowSorter().getSortKeys());

            // Double-click right border of Column 3 (within +/-4px tolerance)
            Rectangle col3HdrRect = header.getHeaderRect(3);
            Point col3RightDivider = new Point(col3HdrRect.x + col3HdrRect.width - 1, col3HdrRect.height / 2);
            assertEquals(3, colMgr.findDividerViewColumn(header, col3RightDivider));
            dispatchHeaderClicks(header, col3RightDivider, 2);

            // Column 3 expanded to fit full :Union(...) schema; neighbors 0, 1, 2, 4 unchanged by even 1px; sort order unchanged!
            assertTrue("Column 3 must expand on divider double-click", col3.getWidth() > 300);
            assertEquals(w0Before, col0.getWidth());
            assertEquals(w1Before, col1.getWidth());
            assertEquals(w2Before, col2.getWidth());
            assertEquals(w4Before, useDepthCol.getWidth());
            assertEquals(sortKeysBeforeDividerDblClick, table.getRowSorter().getSortKeys());

            // Now that Column 3 is right-sized, cell text fits completely -> hover tooltip becomes null!
            table.setSize(table.getPreferredSize());
            table.doLayout();
            Rectangle cellRectWide = table.getCellRect(indexedOutcomeRow, 3, false);
            MouseEvent hoverUntruncated = new MouseEvent(
                table,
                MouseEvent.MOUSE_MOVED,
                0L,
                0,
                cellRectWide.x + 10,
                cellRectWide.y + cellRectWide.height / 2,
                0,
                false
            );
            assertNull("Right-sized non-truncated cell must not show redundant tooltip", table.getToolTipText(hoverUntruncated));

            // Double-click right border of locked Column 4 (Use Depth) -> strict no-op (width & sort order unchanged)
            header.setSize(header.getPreferredSize().width, Math.max(28, header.getPreferredSize().height));
            header.doLayout();
            Rectangle col4HdrRect = header.getHeaderRect(4);
            Point col4RightDivider = new Point(col4HdrRect.x + col4HdrRect.width - 1, col4HdrRect.height / 2);
            assertEquals(4, colMgr.findDividerViewColumn(header, col4RightDivider));
            dispatchHeaderClicks(header, col4RightDivider, 2);
            assertEquals(exactDepthWidth, useDepthCol.getWidth());
            assertEquals(sortKeysBeforeDividerDblClick, table.getRowSorter().getSortKeys());

            // 4. Requirement 3C: Header right-click width lock context menu (Option A)
            var depthMenu = colMgr.buildHeaderContextMenu(4);
            var depthLockItem = (JCheckBoxMenuItem) depthMenu.getComponent(0);
            var depthRightSizeItem = (JMenuItem) depthMenu.getComponent(1);
            var depthRightSizeAllItem = (JMenuItem) depthMenu.getComponent(2);
            assertEquals("Lock Column Width", depthLockItem.getText());
            assertTrue(depthLockItem.isSelected());
            assertEquals("Right-Size Column", depthRightSizeItem.getText());
            assertFalse("Right-Size Column must be disabled when column is locked", depthRightSizeItem.isEnabled());
            assertEquals("Right-Size All Unlocked Columns", depthRightSizeAllItem.getText());
            assertTrue(depthRightSizeAllItem.isEnabled());

            // Lock Column 0 (Name) at custom width 195 via header context menu
            col0.setPreferredWidth(195);
            col0.setWidth(195);
            var col0Menu = colMgr.buildHeaderContextMenu(0);
            var col0LockItem = (JCheckBoxMenuItem) col0Menu.getComponent(0);
            assertFalse(col0LockItem.isSelected());
            col0LockItem.doClick(); // Locks Column 0 at 195px
            assertTrue(colMgr.isViewColumnLocked(0));
            assertFalse(col0.getResizable());
            assertEquals(195, col0.getWidth());
            assertEquals(195, col0.getMinWidth());
            assertEquals(195, col0.getMaxWidth());

            // Shrink unlocked Column 1 to 120px, then click "Right-Size All Unlocked Columns" -> Col 1 resizes, locked Col 0 & Col 4 stay untouched!
            col1.setPreferredWidth(120);
            col1.setWidth(120);
            depthRightSizeAllItem.doClick();
            assertEquals("Locked Column 0 must stay at 195px", 195, col0.getWidth());
            assertEquals("Locked Column 4 must stay at exactDepthWidth", exactDepthWidth, useDepthCol.getWidth());
            assertTrue("Unlocked Column 1 must right-size to fit long filename", col1.getWidth() > 120);

            int lockedCol0W = col0.getWidth();
            int rightSizedCol1W = col1.getWidth();
            int rightSizedCol3W = col3.getWidth();

            // 5. Verify updateView preserves widths and lock states across scope switches, filtering, and Prelude toggles!
            ui.scopeButtons().get(StvnNamespaceScope.TYPE).doClick();
            ui.scopeButtons().get(StvnNamespaceScope.BODY).doClick();
            ui.filterField().setText("PacketHeaderTag");
            ui.showPreludeCheckBox().doClick();
            ui.filterField().setText("");

            assertEquals(lockedCol0W, col0.getWidth());
            assertEquals(rightSizedCol1W, col1.getWidth());
            assertEquals(rightSizedCol3W, col3.getWidth());
            assertEquals(exactDepthWidth, useDepthCol.getWidth());
            assertTrue(colMgr.isViewColumnLocked(0));
            assertTrue(colMgr.isViewColumnLocked(4));

            // Unlock Column 4 via context menu -> becomes resizable, preserves width, and enables Right-Size Column
            var unlockDepthMenu = colMgr.buildHeaderContextMenu(4);
            var unlockDepthItem = (JCheckBoxMenuItem) unlockDepthMenu.getComponent(0);
            unlockDepthItem.doClick();
            assertFalse(colMgr.isViewColumnLocked(4));
            assertTrue(useDepthCol.getResizable());
            assertEquals(Integer.MAX_VALUE, useDepthCol.getMaxWidth());
            assertEquals(exactDepthWidth, useDepthCol.getWidth());
            assertTrue(((JMenuItem) colMgr.buildHeaderContextMenu(4).getComponent(1)).isEnabled());
        } finally {
            Disposer.dispose(disposable);
        }
    }

    public void testTarget4_SymbolHopUseDepthAcrossProbes1To3InlineRootTypeAndDiamonds() throws Exception {
        // 1. Formatting Probe 1: :ProbeSnapshot = 0, all 8 direct slot types = 1, :Flag & :RawGain = 2
        PsiFile probe1 = myFixture.configureByText(
            "fmt_probe_01_type_alias_alignment.core_test.stvn",
            Files.readString(resolveFormattingProbePath("fmt_probe_01_type_alias_alignment.core_test.stvn"))
        );
        for (StvnNamespaceScope scope : List.of(StvnNamespaceScope.TYPE, StvnNamespaceScope.BODY)) {
            var entries1 = StvnNamespaceSymbolCollector.collectSymbols(probe1, scope);
            assertSymbolDepth(entries1, ":ProbeSnapshot", 0);
            assertSymbolDepth(entries1, ":PosixCode", 1);
            assertSymbolDepth(entries1, ":RackSlot", 1);
            assertSymbolDepth(entries1, ":RailVoltage", 1);
            assertSymbolDepth(entries1, ":SampleEpoch", 1);
            assertSymbolDepth(entries1, ":ChannelMode", 1);
            assertSymbolDepth(entries1, ":OptionalFlag", 1);
            assertSymbolDepth(entries1, ":ReferenceGain", 1);
            assertSymbolDepth(entries1, ":BoundedGainHistory", 1);
            assertSymbolDepth(entries1, ":Flag", 2);
            assertSymbolDepth(entries1, ":RawGain", 2);
            assertTrue(entries1.stream().noneMatch(e -> e.useDepth() >= 3));
        }

        // 2. Formatting Probe 2: :ManifoldReport = 0, direct types & direct :body #CONSTs = 1, :LoopFlowLpm & #MAX_FLOW_LPM = 2
        PsiFile probe2 = myFixture.configureByText(
            "fmt_probe_02_constants_meta_use_and_maps.core_test.stvn",
            Files.readString(resolveFormattingProbePath("fmt_probe_02_constants_meta_use_and_maps.core_test.stvn"))
        );
        var type2 = StvnNamespaceSymbolCollector.collectSymbols(probe2, StvnNamespaceScope.TYPE);
        assertSymbolDepth(type2, ":ManifoldReport", 0);
        assertSymbolDepth(type2, ":ValveMode", 1);
        assertSymbolDepth(type2, ":LoopTempC", 1);
        assertSymbolDepth(type2, ":ZoneFlowMap", 1);
        assertSymbolDepth(type2, ":LoopFlowLpm", 2);

        var body2 = StvnNamespaceSymbolCollector.collectSymbols(probe2, StvnNamespaceScope.BODY);
        assertSymbolDepth(body2, ":ManifoldReport", 0);
        assertSymbolDepth(body2, ":ValveMode", 1);
        assertSymbolDepth(body2, ":LoopTempC", 1);
        assertSymbolDepth(body2, ":ZoneFlowMap", 1);
        assertSymbolDepth(body2, "#INIT_VALVE_MODE", 1);
        assertSymbolDepth(body2, "#STANDBY_TEMP_C", 1);
        assertSymbolDepth(body2, "#MAX_SAFE_PRESSURE_KPA", 1);
        assertSymbolDepth(body2, "#LOOP_ENABLED_DEFAULT", 1);
        assertSymbolDepth(body2, ":LoopFlowLpm", 2);
        assertSymbolDepth(body2, "#MAX_FLOW_LPM", 2);
        assertTrue(body2.stream().noneMatch(e -> e.useDepth() >= 3));

        // 3. Formatting Probe 3: :BatchPayload = 0, :TelemetryRow & :IndexedOutcome = 1, :PacketHeaderTag & :ChannelStatus = 2
        PsiFile probe3 = myFixture.configureByText(
            "fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn",
            Files.readString(resolveFormattingProbePath("fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn"))
        );
        for (StvnNamespaceScope scope : List.of(StvnNamespaceScope.TYPE, StvnNamespaceScope.BODY)) {
            var entries3 = StvnNamespaceSymbolCollector.collectSymbols(probe3, scope);
            assertEquals("Probe 3 must contain exact 5 nominal symbols", 5, entries3.size());
            assertSymbolDepth(entries3, ":BatchPayload", 0);
            assertSymbolDepth(entries3, ":TelemetryRow", 1);
            assertSymbolDepth(entries3, ":IndexedOutcome", 1);
            assertSymbolDepth(entries3, ":PacketHeaderTag", 2);
            assertSymbolDepth(entries3, ":ChannelStatus", 2);
            assertTrue("Zero phantom depth >= 3 entries permitted on Probe 3",
                entries3.stream().noneMatch(e -> e.useDepth() >= 3));
        }

        // 4. Inline root :type constructor without nominal wrapper + diamond shortest-path relaxation
        PsiFile inlineRootDoc = myFixture.configureByText("inline_root_and_diamond.stvn", """
            {
              :defs {
                :SubLeaf     :Int
                :SharedLeaf  :Tuple(:SubLeaf :String)
                :MidNominal  :SharedLeaf
                :type_helper :Boolean
              }
              :type :Tuple(:MidNominal :Seq(:Option(:Either(:String :SharedLeaf))))
              :body ((5 "a") [#Right (7 "b")])
            }
            """);
        var inlineTypeEntries = StvnNamespaceSymbolCollector.collectSymbols(inlineRootDoc, StvnNamespaceScope.TYPE);
        assertSymbolDepth(inlineTypeEntries, ":MidNominal", 1);
        // :SharedLeaf is reachable via :MidNominal (depth 2) AND directly via :Tuple(:Seq(:Option(:Either(...)))) (depth 1) -> shortest path is 1!
        assertSymbolDepth(inlineTypeEntries, ":SharedLeaf", 1);
        // :SubLeaf is 1 hop inside :SharedLeaf (depth 1 + 1 = 2, NOT stuck at 3!)
        assertSymbolDepth(inlineTypeEntries, ":SubLeaf", 2);
    }

    private static Path resolveFormattingProbePath(String fileName) {
        Path mirrored = Path.of("src/test/resources/shared-fixtures/formatting/input/" + fileName);
        if (Files.exists(mirrored)) {
            return mirrored;
        }
        return Path.of("../ij_stvnadore_core/shared-fixtures/formatting/input/" + fileName);
    }

    private static void assertSymbolDepth(List<StvnNamespaceSymbolEntry> entries, String symbolName, int expectedDepth) {
        StvnNamespaceSymbolEntry found = null;
        for (var e : entries) {
            if (symbolName.equals(e.name())) {
                found = e;
                break;
            }
        }
        assertNotNull("Expected symbol '" + symbolName + "' in " + entries, found);
        assertEquals("Unexpected Use Depth for '" + symbolName + "'", expectedDepth, found.useDepth());
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

    private static void firePopupAction(AnAction action) {
        AnActionEvent event = TestActionEvent.createTestEvent(action);
        action.update(event);
        if (event.getPresentation().isEnabled()) {
            action.actionPerformed(event);
        }
    }

    private static boolean isPopupActionPresentationEnabled(AnAction action) {
        AnActionEvent event = TestActionEvent.createTestEvent(action);
        action.update(event);
        return event.getPresentation().isEnabled();
    }

    private static void dispatchHeaderClicks(JTableHeader header, Point point, int clickCount) {
        long now = System.currentTimeMillis();
        for (int c = 1; c <= clickCount; c++) {
            MouseEvent press = new MouseEvent(
                header, MouseEvent.MOUSE_PRESSED, now + c, MouseEvent.BUTTON1_DOWN_MASK,
                point.x, point.y, c, false, MouseEvent.BUTTON1
            );
            for (MouseListener ml : header.getMouseListeners()) {
                ml.mousePressed(press);
            }
            MouseEvent release = new MouseEvent(
                header, MouseEvent.MOUSE_RELEASED, now + c, 0,
                point.x, point.y, c, false, MouseEvent.BUTTON1
            );
            for (MouseListener ml : header.getMouseListeners()) {
                ml.mouseReleased(release);
            }
            MouseEvent click = new MouseEvent(
                header, MouseEvent.MOUSE_CLICKED, now + c, 0,
                point.x, point.y, c, false, MouseEvent.BUTTON1
            );
            for (MouseListener ml : header.getMouseListeners()) {
                ml.mouseClicked(click);
            }
        }
    }
}
