package org.stvnadore.plugin.browser;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.MnemonicHelper;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.ComponentWithExpandableItems;
import com.intellij.util.ui.JBUI;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.StvnBundle;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.validation.StvnBooleanValidityInspection;
import org.stvnadore.plugin.validation.StvnConstantRangeInspection;
import org.stvnadore.plugin.validation.StvnDegenerateCompositeInspection;
import org.stvnadore.plugin.validation.StvnDegradedSchemaInspection;
import org.stvnadore.plugin.validation.StvnDiscreteIntervalInspection;
import org.stvnadore.plugin.validation.StvnEnumSubsetInspection;
import org.stvnadore.plugin.validation.StvnExcessiveExactFloatPrecisionInspection;
import org.stvnadore.plugin.validation.StvnFencedStringInspection;
import org.stvnadore.plugin.validation.StvnFlatDocumentIncludeInspection;
import org.stvnadore.plugin.validation.StvnFloatPrecisionLossInspection;
import org.stvnadore.plugin.validation.StvnLhsReservedTypeInspection;
import org.stvnadore.plugin.validation.StvnMapStructuralInspection;
import org.stvnadore.plugin.validation.StvnMetadataFacetInspection;
import org.stvnadore.plugin.validation.StvnMetadataOrderInspection;
import org.stvnadore.plugin.validation.StvnMissingMetaInspection;
import org.stvnadore.plugin.validation.StvnNestedPackageInspection;
import org.stvnadore.plugin.validation.StvnRedundantFacetOverrideInspection;
import org.stvnadore.plugin.validation.StvnReorderEnumFilterVariantsQuickFix;
import org.stvnadore.plugin.validation.StvnStemMismatchInspection;
import org.stvnadore.plugin.validation.StvnStringCapacityInspection;
import org.stvnadore.plugin.validation.StvnStringCardinalityInspection;
import org.stvnadore.plugin.validation.StvnStringEscapeScrutinyInspection;
import org.stvnadore.plugin.validation.StvnSuspiciousEpochMagnitudeInspection;
import org.stvnadore.plugin.validation.StvnTabCharacterInspection;
import org.stvnadore.plugin.validation.StvnTemporalModeInspection;
import org.stvnadore.plugin.validation.StvnTrailingSlashInspection;
import org.stvnadore.plugin.validation.StvnUnresolvedTypeInspection;
import org.stvnadore.plugin.validation.StvnVariantStyleInspection;
import org.stvnadore.psi.VariantList;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end IntelliJ Platform regression test suite verifying:
 * <ul>
 *   <li><b>Target 1:</b> Two-Layer Exact Window Fitting ({@code Layer A} live outer-chrome delta +
 *       {@code JBUI.scale(6)} HiDPI cushion, paired with {@code Layer B} post-{@code validate()}
 *       closed-loop {@code viewport.getExtentSize()} shortfall and overlay-scrollbar correction) on
 *       9-row and 38-row ({@code Show Standard Library Prelude}) tables.</li>
 *   <li><b>Target 2:</b> Popup-scoped {@code Alt+P} ({@code ⌥P}) shortcut, visible shortcut hint,
 *       STE hover tooltip, async loading no-op guard, and {@code SearchTextField} consume guard.</li>
 *   <li><b>Target 3:</b> Native left-aligned {@code JBTable.JBTableHeader} column headers
 *       ({@code 0..3}), right-aligned {@code Use Depth} header ({@code 4}) with lock icon and
 *       scope-aware STE tooltip, and divider {@code packColumn} right-sizing delegation.</li>
 *   <li><b>Target 4:</b> Unified token-only zero-shift {@link StvnReorderEnumFilterVariantsQuickFix}
 *       preserving authored interior bracket padding, column alignment, and inline comments across
 *       {@code Pass A}, {@code Pass B}, and {@code IntentionPreviewInfo.DIFF}.</li>
 * </ul>
 */
@NullMarked
public final class StvnNamespaceBrowserWindowFitPreludeShortcutAndEnumSortPolishTest extends BasePlatformTestCase {

    private static final String SUBSEA_NINE_ROW_DOC = """
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

    private void enableAll27StvnInspections() {
        myFixture.enableInspections(
            new StvnVariantStyleInspection(),
            new StvnBooleanValidityInspection(),
            new StvnMapStructuralInspection(),
            new StvnDegradedSchemaInspection(),
            new StvnDegenerateCompositeInspection(),
            new StvnEnumSubsetInspection(),
            new StvnFencedStringInspection(),
            new StvnLhsReservedTypeInspection(),
            new StvnConstantRangeInspection(),
            new StvnFlatDocumentIncludeInspection(),
            new StvnNestedPackageInspection(),
            new StvnTrailingSlashInspection(),
            new StvnStringCapacityInspection(),
            new StvnExcessiveExactFloatPrecisionInspection(),
            new StvnMetadataFacetInspection(),
            new StvnStringCardinalityInspection(),
            new StvnDiscreteIntervalInspection(),
            new StvnTemporalModeInspection(),
            new StvnMetadataOrderInspection(),
            new StvnRedundantFacetOverrideInspection(),
            new StvnUnresolvedTypeInspection(),
            new StvnTabCharacterInspection(),
            new StvnMissingMetaInspection(),
            new StvnStemMismatchInspection(),
            new StvnSuspiciousEpochMagnitudeInspection(),
            new StvnFloatPrecisionLossInspection(),
            new StvnStringEscapeScrutinyInspection()
        );
    }

    /**
     * Verifies Two-Layer Exact Window Fitting across 9-row local scope, 38-row Prelude-enabled scope,
     * closed-loop {@code Layer B} viewport shortfall recovery, and vertical-overflow scrollbar width reservation.
     *
     * @throws Exception if async browser snapshot collection times out
     */
    public void testTarget1_TwoLayerLiveChromeClosedLoopViewportAndOverlayScrollbarWindowFittingOn9And38Rows()
            throws Exception {
        PsiFile file = myFixture.configureByText("v11_stage06_wavelength_grid.subsea_telemetry.stvn", SUBSEA_NINE_ROW_DOC);
        var disposable = Disposer.newDisposable("Target1WindowFitPolishTest");
        try {
            var ui = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(), file, StvnNamespaceScope.DEFS, disposable
            );
            ui.waitForAnalysis(5, TimeUnit.SECONDS);
            assertEquals("Expected 9 local :defs symbols", 9, ui.table().getRowCount());

            // Simulate AbstractPopup outer hierarchy: PopupBorder(6, 6, 8, 6) + 44px CaptionPanel ("STVN Namespace Dependencies")
            JPanel outerPopupMock = new JPanel(new BorderLayout());
            outerPopupMock.setBorder(JBUI.Borders.empty(6, 6, 8, 6));
            JPanel mockCaptionBar = new JPanel();
            mockCaptionBar.setPreferredSize(new Dimension(400, JBUI.scale(44)));
            outerPopupMock.add(mockCaptionBar, BorderLayout.NORTH);
            outerPopupMock.add(ui.panel(), BorderLayout.CENTER);

            AtomicReference<Rectangle> windowBoundsRef = new AtomicReference<>(new Rectangle(300, 120, 920, 440));
            AtomicReference<Rectangle> usableScreenRef = new AtomicReference<>(new Rectangle(0, 0, 2560, 1600));
            AtomicInteger applyCalls = new AtomicInteger();
            AtomicInteger hiddenExtraHeaderStealPx = new AtomicInteger(0);

            Runnable layoutOuterPopup = () -> {
                Rectangle b = windowBoundsRef.get();
                mockCaptionBar.setPreferredSize(new Dimension(400, JBUI.scale(44) + hiddenExtraHeaderStealPx.get()));
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
                    applyCalls.incrementAndGet();
                    layoutOuterPopup.run();
                }
            });

            // Shrink Column 3 so Alt+R Press 1 right-sizes columns only
            ui.table().getColumnModel().getColumn(3).setPreferredWidth(180);
            ui.table().getColumnModel().getColumn(3).setWidth(180);
            assertFalse(ui.columnManager().areAllUnlockedColumnsRightSized());

            firePopupAction(ui.rightSizeAllUnlockedAction());
            assertTrue(ui.columnManager().areAllUnlockedColumnsRightSized());
            assertEquals("Press 1 must right-size columns without resizing popup window", 0, applyCalls.get());

            // Alt+R Press 2 on 9-row table: viewport extent must fit 100% of all 9 rows and all 5 columns!
            firePopupAction(ui.rightSizeAllUnlockedAction());
            assertTrue("Press 2 must apply fitted window bounds", applyCalls.get() >= 1);
            JViewport viewport = ui.scrollPane().getViewport();
            Dimension extent9 = viewport.getExtentSize();
            assertTrue(
                "9-row fitted viewport width (" + extent9.width + ") must fit total columns ("
                    + ui.columnManager().computeTotalColumnsWidth() + ")",
                extent9.width >= ui.columnManager().computeTotalColumnsWidth()
            );
            assertTrue(
                "9-row fitted viewport height (" + extent9.height + ") must fit all 9 rows ("
                    + ui.computeRequiredRowsHeight() + ") without cutting off bottom rows",
                extent9.height >= ui.computeRequiredRowsHeight()
            );

            // Toggle Prelude ON -> 38 rows (9 local + 29 Prelude) on 2560x1600 monitor -> Alt+Shift+R fits all 38 rows!
            firePopupAction(ui.togglePreludeAction());
            assertEquals("Expected 38 total rows with Prelude enabled", 38, ui.table().getRowCount());
            firePopupAction(ui.fitWindowToContentAction());

            Dimension extent38 = viewport.getExtentSize();
            assertTrue(
                "38-row fitted viewport width (" + extent38.width + ") must fit total columns ("
                    + ui.columnManager().computeTotalColumnsWidth() + ")",
                extent38.width >= ui.columnManager().computeTotalColumnsWidth()
            );
            assertTrue(
                "38-row fitted viewport height (" + extent38.height + ") must fit all 38 rows ("
                    + ui.computeRequiredRowsHeight() + ") without ~70px shortfall",
                extent38.height >= ui.computeRequiredRowsHeight()
            );

            // Layer B Closed-Loop Shortfall Recovery: simulate unannounced 42px header steal inside applyBounds
            windowBoundsRef.set(new Rectangle(300, 120, 920, 440));
            hiddenExtraHeaderStealPx.set(0);
            layoutOuterPopup.run();
            hiddenExtraHeaderStealPx.set(42);
            int callsBeforeClosedLoop = applyCalls.get();
            firePopupAction(ui.fitWindowToContentAction());
            assertEquals(
                "Layer B must detect post-validate viewport shortfall and invoke applyBounds a second time",
                callsBeforeClosedLoop + 2,
                applyCalls.get()
            );
            Dimension extentRecovered = viewport.getExtentSize();
            assertTrue(
                "Closed-loop corrected viewport height (" + extentRecovered.height + ") must fit all 38 rows ("
                    + ui.computeRequiredRowsHeight() + ")",
                extentRecovered.height >= ui.computeRequiredRowsHeight()
            );

            // Genuine Vertical Overflow on constrained 2560x520 monitor (wide enough for 2141px columns, short vertically):
            // height clamps to availH while width reserves >= 16px for vScrollBar so Use Depth digits are never obscured!
            hiddenExtraHeaderStealPx.set(0);
            usableScreenRef.set(new Rectangle(0, 0, 2560, 520));
            windowBoundsRef.set(new Rectangle(200, 80, 820, 400));
            layoutOuterPopup.run();
            firePopupAction(ui.fitWindowToContentAction());

            Rectangle clampedOverflowBounds = windowBoundsRef.get();
            int expectedMaxAvailH = 520 - 2 * JBUI.scale(24);
            assertEquals("Height must clamp cleanly to availH on short monitor", expectedMaxAvailH, clampedOverflowBounds.height);
            Dimension extentOverflow = viewport.getExtentSize();
            assertTrue(
                "When vertical scrolling is active, viewport width (" + extentOverflow.width
                    + ") must still clear all columns (" + ui.columnManager().computeTotalColumnsWidth()
                    + ") so Use Depth digits are never obscured",
                extentOverflow.width >= ui.columnManager().computeTotalColumnsWidth()
            );
            assertTrue(
                "Outer popup width (" + clampedOverflowBounds.width + ") must reserve >= 16px scrollbar headroom beyond columns ("
                    + ui.columnManager().computeTotalColumnsWidth() + ")",
                clampedOverflowBounds.width >= ui.columnManager().computeTotalColumnsWidth() + JBUI.scale(16)
            );

            // Dual-axis clamping on 1400x520 monitor (narrower than 2141px columns and shorter than 38 rows)
            usableScreenRef.set(new Rectangle(0, 0, 1400, 520));
            windowBoundsRef.set(new Rectangle(200, 80, 820, 400));
            layoutOuterPopup.run();
            firePopupAction(ui.fitWindowToContentAction());
            Rectangle dualClampedBounds = windowBoundsRef.get();
            assertEquals(1400 - 2 * JBUI.scale(24), dualClampedBounds.width);
            assertEquals(expectedMaxAvailH, dualClampedBounds.height);
        } finally {
            Disposer.dispose(disposable);
        }
    }

    /**
     * Verifies popup-scoped {@code Alt+P} shortcut, visible {@code (Alt+P)} checkbox hint, STE hover tooltip,
     * pre-load no-op safety, and {@code SearchTextField} {@code 'p'}/{@code 'P'}/{@code 'π'} consume guards.
     *
     * @throws Exception if async browser snapshot collection times out
     */
    public void testTarget2_PopupScopedAltPShortcutVisibleHintSteTooltipLoadingGuardAndSearchTextFieldProtection()
            throws Exception {
        PsiFile file = myFixture.configureByText("prelude_shortcut_check.subsea_telemetry.stvn", SUBSEA_NINE_ROW_DOC);
        var disposable = Disposer.newDisposable("Target2AltPShortcutTest");
        try {
            var ui = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(), file, StvnNamespaceScope.DEFS, disposable
            );

            // Presentation stays enabled even during loading so Alt+P never leaks to IDE top-level menus
            assertTrue(isPopupActionPresentationEnabled(ui.togglePreludeAction()));

            ui.waitForAnalysis(5, TimeUnit.SECONDS);
            MnemonicHelper.init(ui.panel());

            String shortcutText = StvnNamespaceBrowserPopup.resolvePreludeShortcutText();
            assertFalse(shortcutText.isEmpty());
            assertEquals(
                "Show Standard Library Prelude (" + shortcutText + ")",
                ui.showPreludeCheckBox().getText()
            );
            assertEquals(
                "Show or hide implicit Standard Library Prelude definitions (" + shortcutText + ")",
                ui.showPreludeCheckBox().getToolTipText()
            );
            MouseEvent hoverCheckBox = new MouseEvent(
                ui.showPreludeCheckBox(), MouseEvent.MOUSE_MOVED, 0L, 0, 4, 4, 0, false
            );
            assertEquals(
                "Show or hide implicit Standard Library Prelude definitions (" + shortcutText + ")",
                ui.showPreludeCheckBox().getToolTipText(hoverCheckBox)
            );
            assertEquals("Mnemonic must stay 0 so Swing never double-toggles on Alt+P",
                0, ui.showPreludeCheckBox().getMnemonic());
            assertEquals(Boolean.TRUE, ui.showPreludeCheckBox().getClientProperty(MnemonicHelper.DISABLE_MNEMONIC_PROCESSING));
            assertEquals(Boolean.TRUE, ui.showPreludeCheckBox().getClientProperty("html.disable"));

            // Disabled-checkbox guard: if disabled (as during initial loading), Alt+P is a strict no-op
            ui.showPreludeCheckBox().setEnabled(false);
            firePopupAction(ui.togglePreludeAction());
            assertFalse(ui.showPreludeCheckBox().isSelected());
            ui.showPreludeCheckBox().setEnabled(true);

            // Toggle ON via DumbAwareAction (9 -> 38 rows) and OFF via InputMap/ActionMap (38 -> 9 rows)
            assertEquals(9, ui.tableModel().getRowCount());
            firePopupAction(ui.togglePreludeAction());
            assertTrue(ui.showPreludeCheckBox().isSelected());
            assertEquals(38, ui.tableModel().getRowCount());
            assertTrue(ui.fullScopeSummaryText().contains(", Prelude shown"));

            Object actionKey = ui.panel()
                .getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .get(StvnNamespaceBrowserPopup.PRELUDE_TOGGLE_KEYSTROKE);
            assertNotNull("Alt+P must be bound in WHEN_ANCESTOR_OF_FOCUSED_COMPONENT InputMap", actionKey);
            Action swingAltP = ui.panel().getActionMap().get(actionKey);
            assertNotNull(swingAltP);

            List<Component> focusOrigins = List.of(
                ui.filterField().getTextEditor(),
                ui.table(),
                ui.scopeButtons().get(StvnNamespaceScope.DEFS),
                ui.scopeButtons().get(StvnNamespaceScope.TYPE),
                ui.scopeButtons().get(StvnNamespaceScope.BODY),
                ui.showPreludeCheckBox()
            );
            boolean expectedSelected = true;
            for (Component origin : focusOrigins) {
                expectedSelected = !expectedSelected;
                swingAltP.actionPerformed(new ActionEvent(origin, ActionEvent.ACTION_PERFORMED, "altP"));
                assertEquals(
                    "Alt+P must toggle Prelude cleanly from focus origin " + origin.getClass().getSimpleName(),
                    expectedSelected,
                    ui.showPreludeCheckBox().isSelected()
                );
            }

            // SearchTextField Alt+P / Option+P ('p', 'P', '\u03C0', '\u220F') key-typed consume guard
            var filterEditor = ui.filterField().getTextEditor();
            filterEditor.setText(":LaserState");
            long now = System.currentTimeMillis();
            for (char ch : new char[]{'p', 'P', '\u03C0', '\u220F'}) {
                KeyEvent pressed = new KeyEvent(
                    filterEditor, KeyEvent.KEY_PRESSED, now, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_P, ch
                );
                for (KeyListener kl : filterEditor.getKeyListeners()) {
                    kl.keyPressed(pressed);
                }
                KeyEvent typed = new KeyEvent(
                    filterEditor, KeyEvent.KEY_TYPED, now + 1, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_UNDEFINED, ch
                );
                for (KeyListener kl : filterEditor.getKeyListeners()) {
                    kl.keyTyped(typed);
                }
                assertTrue("SearchTextField must consume Alt+P keyChar '" + ch + "'", typed.isConsumed());
            }
            assertEquals("Alt+P must never insert characters into SearchTextField", ":LaserState", filterEditor.getText());
        } finally {
            Disposer.dispose(disposable);
        }
    }

    /**
     * Verifies native {@code JBTable.JBTableHeader} subclassing, flush-left column headers ({@code 0..3}),
     * right-aligned {@code Use Depth} header ({@code 4}) with lock icon and scope-aware STE tooltip, and
     * native {@code packColumn} divider double-click delegation without toggle-shrinking or sort-order interference.
     *
     * @throws Exception if async browser snapshot collection times out
     */
    public void testTarget3_NativeJBTableHeaderLeftAlignmentUseDepthRightAlignmentDividerPackAndScopeTooltips()
            throws Exception {
        PsiFile file = myFixture.configureByText("header_alignment_check.subsea_telemetry.stvn", SUBSEA_NINE_ROW_DOC);
        var disposable = Disposer.newDisposable("Target3HeaderAlignmentTest");
        try {
            var ui = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(), file, StvnNamespaceScope.DEFS, disposable
            );
            ui.waitForAnalysis(5, TimeUnit.SECONDS);

            JTableHeader header = ui.table().getTableHeader();
            assertNotNull(header);
            assertEquals(
                "Table header must subclass native JBTable.JBTableHeader",
                "com.intellij.ui.table.JBTable$JBTableHeader",
                header.getClass().getSuperclass().getName()
            );
            assertTrue(header instanceof ComponentWithExpandableItems<?>);

            ui.panel().setSize(new Dimension(920, 440));
            doLayoutRecursively(ui.panel());
            header.setSize(header.getPreferredSize().width, Math.max(28, header.getPreferredSize().height));
            header.doLayout();
            ui.table().setSize(ui.table().getPreferredSize());
            ui.table().doLayout();

            // 1. Columns 0..3 headers MUST be SwingConstants.LEFT with LEADING text position and null tooltip
            for (int col = 0; col <= 3; col++) {
                Object hdrVal = ui.table().getColumnModel().getColumn(col).getHeaderValue();
                JLabel hdrComp = (JLabel) header.getDefaultRenderer()
                    .getTableCellRendererComponent(ui.table(), hdrVal, false, false, -1, col);
                assertEquals(
                    "Column " + col + " header must be SwingConstants.LEFT",
                    SwingConstants.LEFT,
                    hdrComp.getHorizontalAlignment()
                );
                assertEquals(SwingConstants.LEADING, hdrComp.getHorizontalTextPosition());
                assertNull("Columns 0..3 header component tooltip must be null", hdrComp.getToolTipText());

                Rectangle rect = header.getHeaderRect(col);
                MouseEvent move = new MouseEvent(
                    header, MouseEvent.MOUSE_MOVED, 0L, 0, rect.x + rect.width / 2, rect.height / 2, 0, false
                );
                assertNull("Columns 0..3 JBTableHeader tooltip must be null", header.getToolTipText(move));
            }

            // 2. Column 4 (Use Depth) header is SwingConstants.RIGHT with lock icon and dynamic scope-aware STE tooltip
            TableColumn depthCol = ui.table().getColumnModel().getColumn(4);
            JLabel depthHdrDefs = (JLabel) header.getDefaultRenderer()
                .getTableCellRendererComponent(ui.table(), depthCol.getHeaderValue(), false, false, -1, 4);
            assertEquals(SwingConstants.RIGHT, depthHdrDefs.getHorizontalAlignment());
            assertEquals(SwingConstants.LEADING, depthHdrDefs.getHorizontalTextPosition());
            assertNotNull(depthHdrDefs.getIcon());
            assertEquals(
                StvnBundle.message("stvn.browser.column.useDepth.tooltip.defs"),
                depthHdrDefs.getToolTipText()
            );

            Rectangle col4Rect = header.getHeaderRect(4);
            Point col4Center = new Point(col4Rect.x + col4Rect.width / 2, col4Rect.height / 2);
            assertEquals(
                StvnBundle.message("stvn.browser.column.useDepth.tooltip.defs"),
                header.getToolTipText(new MouseEvent(header, MouseEvent.MOUSE_MOVED, 0L, 0, col4Center.x, col4Center.y, 0, false))
            );

            ui.scopeButtons().get(StvnNamespaceScope.TYPE).doClick();
            JLabel depthHdrType = (JLabel) header.getDefaultRenderer()
                .getTableCellRendererComponent(ui.table(), depthCol.getHeaderValue(), false, false, -1, 4);
            assertEquals(
                StvnBundle.message("stvn.browser.column.useDepth.tooltip.reachability"),
                depthHdrType.getToolTipText()
            );
            assertEquals(
                StvnBundle.message("stvn.browser.column.useDepth.tooltip.reachability"),
                header.getToolTipText(new MouseEvent(header, MouseEvent.MOUSE_MOVED, 0L, 0, col4Center.x, col4Center.y, 0, false))
            );

            ui.scopeButtons().get(StvnNamespaceScope.DEFS).doClick();

            // 3. Sort Column 0 ascending -> stays SwingConstants.LEFT; sort Column 4 -> fits within exactUseDepthHeaderWidth
            ui.table().getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
            JLabel sortedCol0Hdr = (JLabel) header.getDefaultRenderer()
                .getTableCellRendererComponent(ui.table(), ui.table().getColumnModel().getColumn(0).getHeaderValue(), false, false, -1, 0);
            assertEquals(SwingConstants.LEFT, sortedCol0Hdr.getHorizontalAlignment());

            ui.table().getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(4, SortOrder.ASCENDING)));
            JLabel sortedCol4Hdr = (JLabel) header.getDefaultRenderer()
                .getTableCellRendererComponent(ui.table(), depthCol.getHeaderValue(), false, false, -1, 4);
            assertTrue(sortedCol4Hdr.getIcon() instanceof StvnNamespaceColumnManager.DualHeaderIcon);
            assertTrue(
                "Sorted Use Depth header preferred width (" + sortedCol4Hdr.getPreferredSize().width
                    + ") must fit within exactUseDepthHeaderWidth (" + ui.columnManager().getExactUseDepthHeaderWidth() + ")",
                sortedCol4Hdr.getPreferredSize().width <= ui.columnManager().getExactUseDepthHeaderWidth()
            );

            // 4. Native AWT dispatchEvent double-click on Column 3 right divider (routes through JBTableHeader.processMouseEvent -> packColumn)
            TableColumn col3 = ui.table().getColumnModel().getColumn(3);
            col3.setPreferredWidth(115);
            col3.setWidth(115);
            header.setSize(header.getPreferredSize().width, Math.max(28, header.getPreferredSize().height));
            header.doLayout();
            ui.table().setSize(ui.table().getPreferredSize());
            ui.table().doLayout();

            List<? extends RowSorter.SortKey> sortBefore = List.copyOf(ui.table().getRowSorter().getSortKeys());
            Rectangle narrowCol3Rect = header.getHeaderRect(3);
            Point col3DividerPt = new Point(narrowCol3Rect.x + narrowCol3Rect.width - 1, narrowCol3Rect.height / 2);
            dispatchAwtHeaderDoubleClick(header, col3DividerPt);

            int expandedCol3Width = col3.getWidth();
            assertTrue("Native JBTableHeader divider double-click must right-size Column 3 > 250px", expandedCol3Width > 250);
            assertEquals("Divider double-click must not alter sort keys", sortBefore, ui.table().getRowSorter().getSortKeys());

            // Repeat double-click on already right-sized Column 3 divider -> MUST stay at expandedCol3Width (never toggle-shrink!)
            header.setSize(header.getPreferredSize().width, Math.max(28, header.getPreferredSize().height));
            header.doLayout();
            ui.table().setSize(ui.table().getPreferredSize());
            ui.table().doLayout();
            Rectangle wideCol3Rect = header.getHeaderRect(3);
            Point wideCol3DividerPt = new Point(wideCol3Rect.x + wideCol3Rect.width - 1, wideCol3Rect.height / 2);
            dispatchAwtHeaderDoubleClick(header, wideCol3DividerPt);
            assertEquals(
                "Repeat divider double-click on JBTableHeader must keep right-sized width without toggle-shrinking",
                expandedCol3Width,
                col3.getWidth()
            );
        } finally {
            Disposer.dispose(disposable);
        }
    }

    /**
     * Verifies unified token-only zero-shift {@link StvnReorderEnumFilterVariantsQuickFix} across asymmetric
     * interior bracket padding ({@code [ #DEGRADED #LOCKED]}), symmetric padding, tight brackets, multi-space
     * separators, multi-line comment-bearing lists, and detached preview PSI copies across Pass A and Pass B.
     */
    public void testTarget4_ZeroShiftTokenOnlyEnumSubsetSortPreservesAuthoredBracketPaddingPassAAndPassB() {
        String asymmetricTemplate = """
            {
              :meta {
                #name "stage04_enum_padding"
                #kind #DEFS
              }
              :defs {
                :LaserState           :Enum[#OFF #COLD_STANDBY #WARMUP #LOCKED #DEGRADED]
                :PoweredLaserState    { #filterExcl [#OFF] } :LaserState
                :TelemetryActiveState { #filterIncl [ #DEGRADED #LOCKED] }  :PoweredLaserState // aligned col
                :SymmetricPadded      { #filterIncl [ #DEGRADED #LOCKED ] } :PoweredLaserState
                :MultiSpacePadded     { #filterIncl [  #WARMUP   #COLD_STANDBY  #LOCKED ] } :PoweredLaserState
                :TightCanonical       { #filterIncl [#DEGRADED #LOCKED] }   :PoweredLaserState
              }
            }
            """;

        for (boolean passB : List.of(false, true)) {
            if (passB) {
                enableAll27StvnInspections();
            }
            myFixture.configureByText("stage04_enum_padding.stvn_d", asymmetricTemplate);
            int beforeAlignedTargetCol = asymmetricTemplate.indexOf(":PoweredLaserState // aligned col");

            // 1. Asymmetric leading space: '[ #DEGRADED #LOCKED]' -> '[ #LOCKED #DEGRADED]' with exact 0-column shift!
            myFixture.getEditor().getCaretModel().moveToOffset(
                asymmetricTemplate.indexOf("[ #DEGRADED #LOCKED]") + 3
            );
            myFixture.doHighlighting();
            List<IntentionAction> fixes1 = myFixture.filterAvailableIntentions(
                "Sort variants to match root enum declaration order"
            );
            assertFalse("Expected sort quick-fix on asymmetric list (passB=" + passB + ")", fixes1.isEmpty());

            String expectedStep1 = asymmetricTemplate.replace("[ #DEGRADED #LOCKED]", "[ #LOCKED #DEGRADED]");
            assertEquals("Preview must preserve leading bracket space with 0 shift (passB=" + passB + ")",
                expectedStep1, myFixture.getIntentionPreviewText(fixes1.get(0)));

            myFixture.launchAction(fixes1.get(0));
            String afterStep1 = myFixture.getFile().getText();
            assertEquals(expectedStep1, afterStep1);
            assertEquals(asymmetricTemplate.length(), afterStep1.length());
            assertEquals(
                ":PoweredLaserState column alignment must remain 100% unchanged",
                beforeAlignedTargetCol,
                afterStep1.indexOf(":PoweredLaserState // aligned col")
            );

            // 2. Symmetric interior spaces: '[ #DEGRADED #LOCKED ]' -> '[ #LOCKED #DEGRADED ]'
            myFixture.getEditor().getCaretModel().moveToOffset(
                afterStep1.indexOf("[ #DEGRADED #LOCKED ]") + 3
            );
            myFixture.doHighlighting();
            List<IntentionAction> fixes2 = myFixture.filterAvailableIntentions(
                "Sort variants to match root enum declaration order"
            );
            assertFalse(fixes2.isEmpty());
            String expectedStep2 = afterStep1.replace("[ #DEGRADED #LOCKED ]", "[ #LOCKED #DEGRADED ]");
            assertEquals(expectedStep2, myFixture.getIntentionPreviewText(fixes2.get(0)));
            myFixture.launchAction(fixes2.get(0));
            assertEquals(expectedStep2, myFixture.getFile().getText());

            // 3. Multi-space interior separators: '[  #WARMUP   #COLD_STANDBY  #LOCKED ]' -> '[  #COLD_STANDBY   #WARMUP  #LOCKED ]'
            String current = myFixture.getFile().getText();
            myFixture.getEditor().getCaretModel().moveToOffset(
                current.indexOf("[  #WARMUP   #COLD_STANDBY  #LOCKED ]") + 4
            );
            myFixture.doHighlighting();
            List<IntentionAction> fixes3 = myFixture.filterAvailableIntentions(
                "Sort variants to match root enum declaration order"
            );
            assertFalse(fixes3.isEmpty());
            String expectedStep3 = current.replace(
                "[  #WARMUP   #COLD_STANDBY  #LOCKED ]",
                "[  #COLD_STANDBY   #WARMUP  #LOCKED ]"
            );
            assertEquals(expectedStep3, myFixture.getIntentionPreviewText(fixes3.get(0)));
            myFixture.launchAction(fixes3.get(0));
            assertEquals(expectedStep3, myFixture.getFile().getText());

            // 4. Tight canonical brackets: '[#DEGRADED #LOCKED]' -> '[#LOCKED #DEGRADED]'
            String current4 = myFixture.getFile().getText();
            myFixture.getEditor().getCaretModel().moveToOffset(
                current4.indexOf("[#DEGRADED #LOCKED]") + 2
            );
            myFixture.doHighlighting();
            List<IntentionAction> fixes4 = myFixture.filterAvailableIntentions(
                "Sort variants to match root enum declaration order"
            );
            assertFalse(fixes4.isEmpty());
            String expectedStep4 = current4.replace("[#DEGRADED #LOCKED]", "[#LOCKED #DEGRADED]");
            assertEquals(expectedStep4, myFixture.getIntentionPreviewText(fixes4.get(0)));
            myFixture.launchAction(fixes4.get(0));
            assertEquals(expectedStep4, myFixture.getFile().getText());
        }

        // 5. Detached non-physical PSI copy invocation (verifying LeafPsiElement.replaceWithText zero-shift path directly)
        PsiFile nonPhysicalCopy = PsiFileFactory.getInstance(getProject()).createFileFromText(
            "detached_preview.stvn_d",
            StvnLanguage.INSTANCE,
            "{ :defs { :Root :Enum[#A #B #C] :Sub { #filterIncl [ #C #A] } :Root } }",
            false,
            false
        );
        VariantList detachedList = PsiTreeUtil.findChildOfType(nonPhysicalCopy, VariantList.class);
        assertNotNull(detachedList);
        new StvnReorderEnumFilterVariantsQuickFix(detachedList)
            .invoke(getProject(), nonPhysicalCopy, null, detachedList, detachedList);
        assertEquals(
            "{ :defs { :Root :Enum[#A #B #C] :Sub { #filterIncl [ #A #C] } :Root } }",
            nonPhysicalCopy.getText()
        );
    }

    private static void dispatchAwtHeaderDoubleClick(JTableHeader header, Point point) {
        long now = System.currentTimeMillis();
        for (int c = 1; c <= 2; c++) {
            header.dispatchEvent(new MouseEvent(
                header, MouseEvent.MOUSE_PRESSED, now + c, InputEvent.BUTTON1_DOWN_MASK,
                point.x, point.y, c, false, MouseEvent.BUTTON1
            ));
            header.dispatchEvent(new MouseEvent(
                header, MouseEvent.MOUSE_RELEASED, now + c, 0,
                point.x, point.y, c, false, MouseEvent.BUTTON1
            ));
            header.dispatchEvent(new MouseEvent(
                header, MouseEvent.MOUSE_CLICKED, now + c, 0,
                point.x, point.y, c, false, MouseEvent.BUTTON1
            ));
        }
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
