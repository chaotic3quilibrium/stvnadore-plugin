package org.stvnadore.plugin.browser;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.speedSearch.SpeedSearchSupply;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.metadata.StvnFacetMetadata;
import org.stvnadore.plugin.metadata.StvnMetadataRegistry;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Platform regression test suite verifying:
 * <ul>
 *   <li><b>Target 1:</b> Canonical § 6.1 5-tier decimal facet ordering ({@code 1.1.1 -> 5.0}, specifically
 *       {@code #preserveIndent #TRUE} strictly before {@code #regex}) across {@link StvnSchemaFormatter}
 *       PSI/IR paths, {@code STVN Namespace Dependencies}, {@link StvnDocumentationProvider}, and
 *       {@link StvnMetadataRegistry}.</li>
 *   <li><b>Target 2:</b> Bold {@code File:} and {@code Scope:} header prefixes paired with regular-weight
 *       values, HTML-injection immunity ({@code html.disable = Boolean.TRUE}), and exact {@code x = 0}
 *       flush-left vertical alignment across Rows 1, 2, and 3.</li>
 *   <li><b>Target 3:</b> Popup-scoped {@code Alt+F} / {@code Ctrl+F} / {@code Meta+F} filter focus and
 *       {@code selectAll()} shortcuts, STE filter hover tooltip, single-insert {@code KEY_TYPED}
 *       type-anywhere auto-focus, {@code BackSpace} forwarding, {@code Up}/{@code Down}/{@code PageUp}/{@code PageDown}/{@code Enter}
 *       navigation, {@code TableSpeedSearch} deconfliction, and {@code Name -> Upstream Target -> Source -> Resolved Type}
 *       prioritized match ranking.</li>
 * </ul>
 */
@NullMarked
public final class StvnNamespaceBrowserTypographyFilterFocusAndFacetOrderTest extends BasePlatformTestCase {

    private static final String EXPECTED_PACKET_HEADER_TAG_TYPE =
        "{ #minSize 4 #maxSize 24 #minIncl \"PKT-0000\" #maxIncl \"PKT-ZZZZ\""
            + " #preserveIndent #TRUE #regex \"^PKT-[A-Z0-9-]+$\" } :String";

    public void testTarget1_CanonicalFiveTierFacetOrderAcrossProbe3PsiIrHoverAndRegistry() throws Exception {
        // 1. Verify both unordered input and canonical pretty Probe 3 render #preserveIndent strictly before #regex
        for (String subDir : List.of("input", "pretty")) {
            Path probePath = resolveFormattingFixturePath(subDir, "fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn");
            String probeSrc = Files.readString(probePath);
            PsiFile file = myFixture.configureByText("fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn", probeSrc);

            for (StvnNamespaceScope scope : StvnNamespaceScope.values()) {
                var entries = StvnNamespaceSymbolCollector.collectSymbols(file, scope);
                var packetEntry = findEntryByName(entries, ":PacketHeaderTag");
                assertNotNull("Missing :PacketHeaderTag in " + subDir + " / " + scope, packetEntry);
                assertEquals(
                    "Resolved Type must place #preserveIndent (4.3) before #regex (4.4) in " + subDir + " / " + scope,
                    EXPECTED_PACKET_HEADER_TAG_TYPE,
                    packetEntry.resolvedType()
                );
            }

            // Pure-IR StvnSchema formatting parity against canonical Probe 3
            if ("pretty".equals(subDir)) {
                var compiled = StvnCompiler.compileToResult(
                    probeSrc,
                    "fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn",
                    StvnParserConfig.DEFAULT
                );
                assertFalse("Probe 3 (pretty) must compile cleanly: " + compiled.diagnostics(), compiled.hasErrors());
                StvnDocument doc = compiled.document().orElseThrow();
                StvnSchema packetIr = doc.defs().nominalTypes().get(":PacketHeaderTag");
                assertNotNull(packetIr);
                assertEquals(EXPECTED_PACKET_HEADER_TAG_TYPE, StvnSchemaFormatter.formatCompactCanonicalIrSchema(packetIr));
                assertEquals(StvnVocabulary.TYPE_STRING, StvnSchemaFormatter.formatCleanIrSchema(packetIr));

                StvnSchema telemetryIr = doc.defs().nominalTypes().get(":TelemetryRow");
                assertNotNull(telemetryIr);
                assertEquals(
                    ":Tuple(:PacketHeaderTag { #unsigned #size 16 #maxExcl 4096 } :Int { #size 32 #minIncl 0.0 } :Float :ChannelStatus)",
                    StvnSchemaFormatter.formatCompactCanonicalIrSchema(telemetryIr)
                );
            }
        }

        // 2. Verify hover Zone 2 Effective Facets & Traits ordering (#ns Tier 1.2.1 before Tier 2/3; #preserveIndent before #regex)
        String hoverDocSrc = """
            {
              :meta {
                #name "hover_order_check"
                #domain "core_test"
                #kind #BODY
              }
              :defs {
                :OrderedStr { #minSize 4 #maxSize 24 #minIncl "PKT-0000" #maxIncl "PKT-ZZZZ" #preserveIndent #TRUE #regex "^PKT-[A-Z0-9-]+$" } :String
                :BoundedEpoch { #ns #equatable #FALSE #minIncl 0 #maxExcl 1000 } :TimeEpoch
              }
              :type :Tuple(:OrderedStr :BoundedEpoch)
              :body ("PKT-0001" 500)
            }
            """;
        PsiFile hoverFile = myFixture.configureByText("hover_order_check.core_test.stvn", hoverDocSrc);
        var docProvider = new StvnDocumentationProvider();

        int strOffset = hoverDocSrc.indexOf(":OrderedStr") + 2;
        var strElem = hoverFile.findElementAt(strOffset);
        assertNotNull(strElem);
        String strHover = docProvider.generateDoc(strElem.getParent().getParent(), strElem);
        assertNotNull(strHover);
        int preservePos = strHover.indexOf("<code>#preserveIndent</code>");
        int regexPos = strHover.indexOf("<code>#regex</code>");
        assertTrue("Hover table must include #preserveIndent and #regex", preservePos > 0 && regexPos > 0);
        assertTrue("Hover table must order #preserveIndent (4.3) ahead of #regex (4.4)", preservePos < regexPos);

        int epochOffset = hoverDocSrc.indexOf(":BoundedEpoch") + 2;
        var epochElem = hoverFile.findElementAt(epochOffset);
        assertNotNull(epochElem);
        String epochHover = docProvider.generateDoc(epochElem.getParent().getParent(), epochElem);
        assertNotNull(epochHover);
        assertFalse("Obsolete #scale tag must never appear in Zone 2 hover", epochHover.contains("<code>#scale</code>"));
        int nsPos = epochHover.indexOf("<code>#ns</code>");
        int eqPos = epochHover.indexOf("<code>#equatable</code>");
        int minPos = epochHover.indexOf("<code>#minIncl</code>");
        assertTrue("Epoch hover must rank #ns (1.2.1) < #equatable (2.1) < #minIncl (3.1):\n" + epochHover,
            nsPos > 0 && nsPos < eqPos && eqPos < minPos);

        // 3. Verify StvnMetadataRegistry canonical § 6.1 registration order
        List<String> stringFacets = StvnMetadataRegistry.getSupportedFacets(StvnVocabulary.TYPE_STRING)
            .stream().map(StvnFacetMetadata::facetTag).toList();
        assertEquals(
            List.of(
                StvnVocabulary.FACET_KW_MIN_SIZE,
                StvnVocabulary.FACET_KW_MAX_SIZE,
                StvnVocabulary.FACET_KW_EQUATABLE,
                StvnVocabulary.FACET_KW_COMPARABLE,
                StvnVocabulary.FACET_KW_MIN_INCL,
                StvnVocabulary.FACET_KW_MIN_EXCL,
                StvnVocabulary.FACET_KW_MAX_INCL,
                StvnVocabulary.FACET_KW_MAX_EXCL,
                StvnVocabulary.FACET_KW_PRESERVE_INDENT,
                StvnVocabulary.FACET_KW_REGEX
            ),
            stringFacets
        );
        List<String> enumFacets = StvnMetadataRegistry.getSupportedFacets(StvnVocabulary.TYPE_ENUM)
            .stream().map(StvnFacetMetadata::facetTag).toList();
        assertEquals(
            List.of(
                StvnVocabulary.FACET_KW_EQUATABLE,
                StvnVocabulary.FACET_KW_COMPARABLE,
                StvnVocabulary.FACET_KW_FILTER_INCL,
                StvnVocabulary.FACET_KW_FILTER_EXCL
            ),
            enumFacets
        );
    }

    public void testTarget2_BoldMetadataPrefixesPlainValuesZeroPixelFlushLeftMarginAndHtmlSafety() throws Exception {
        Path probe3Path = resolveFormattingFixturePath("pretty", "fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn");
        PsiFile file = myFixture.configureByText(
            "fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn",
            Files.readString(probe3Path)
        );

        var disposable = Disposer.newDisposable("Target2TypographyAndAlignmentTest");
        try {
            var ui = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(), file, StvnNamespaceScope.DEFS, disposable
            );
            ui.waitForAnalysis(5, TimeUnit.SECONDS);

            ui.panel().setSize(new Dimension(920, 440));
            doLayoutRecursively(ui.panel());

            // 1. Bold prefixes only; values, checkbox, and radio labels remain regular weight
            assertEquals("File:", ui.filePrefixLabel().getText());
            assertEquals("Scope:", ui.scopeSummaryPrefixLabel().getText());
            assertEquals("Scope:", ui.scopeSelectorPrefixLabel().getText());

            assertTrue("Row 1 'File:' prefix must be bold", (ui.filePrefixLabel().getFont().getStyle() & Font.BOLD) != 0);
            assertTrue("Row 2 'Scope:' prefix must be bold", (ui.scopeSummaryPrefixLabel().getFont().getStyle() & Font.BOLD) != 0);
            assertTrue("Row 3 'Scope:' prefix must be bold", (ui.scopeSelectorPrefixLabel().getFont().getStyle() & Font.BOLD) != 0);

            assertEquals("Row 1 filename value must remain regular weight", 0, ui.fileLabel().getFont().getStyle() & Font.BOLD);
            assertEquals("Row 2 summary value must remain regular weight", 0, ui.headerLabel().getFont().getStyle() & Font.BOLD);
            assertEquals("Prelude checkbox must remain regular weight", 0, ui.showPreludeCheckBox().getFont().getStyle() & Font.BOLD);
            for (JRadioButton radio : ui.scopeButtons().values()) {
                assertEquals("Radio button '" + radio.getText() + "' must remain regular weight",
                    0, radio.getFont().getStyle() & Font.BOLD);
            }

            // 2. Exact x = 0 flush-left pixel alignment across Rows 1, 2, and 3 inside northPanel
            JPanel northPanel = (JPanel) ((BorderLayout) ui.panel().getLayout()).getLayoutComponent(BorderLayout.NORTH);
            assertNotNull(northPanel);
            int xRow1 = SwingUtilities.convertPoint(ui.filePrefixLabel().getParent(), ui.filePrefixLabel().getLocation(), northPanel).x;
            int xRow2 = SwingUtilities.convertPoint(ui.scopeSummaryPrefixLabel().getParent(), ui.scopeSummaryPrefixLabel().getLocation(), northPanel).x;
            int xRow3 = SwingUtilities.convertPoint(ui.scopeSelectorPrefixLabel().getParent(), ui.scopeSelectorPrefixLabel().getLocation(), northPanel).x;

            assertEquals("Row 1 'File:' must start at x = 0 inside northPanel", 0, xRow1);
            assertEquals("Row 2 'Scope:' must start at x = 0 inside northPanel", 0, xRow2);
            assertEquals("Row 3 'Scope:' must start at x = 0 inside northPanel", 0, xRow3);

            // 3. HTML-injection immunity on arbitrary filenames containing <html>
            PsiFile htmlNamedFile = PsiFileFactory.getInstance(getProject()).createFileFromText(
                "<html><b>inject.stvn",
                StvnLanguage.INSTANCE,
                "{ :defs { :Safe :Int } :type :Safe :body 1 }"
            );
            var htmlUi = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(), htmlNamedFile, StvnNamespaceScope.DEFS, disposable
            );
            htmlUi.waitForAnalysis(5, TimeUnit.SECONDS);
            assertEquals(Boolean.TRUE, htmlUi.filePrefixLabel().getClientProperty("html.disable"));
            assertEquals(Boolean.TRUE, htmlUi.fileLabel().getClientProperty("html.disable"));
            assertEquals(Boolean.TRUE, htmlUi.scopeSummaryPrefixLabel().getClientProperty("html.disable"));
            assertEquals(Boolean.TRUE, htmlUi.headerLabel().getClientProperty("html.disable"));
            assertEquals(Boolean.TRUE, htmlUi.scopeSelectorPrefixLabel().getClientProperty("html.disable"));
            assertNull("BasicHTML renderer must never be instantiated on fileLabel", htmlUi.fileLabel().getClientProperty("html"));
            assertEquals("<html><b>inject.stvn", htmlUi.fileLabel().getText());
        } finally {
            Disposer.dispose(disposable);
        }
    }

    public void testTarget3_FilterShortcutsTooltipTypeAnywhereSingleInsertBackspaceNavigationAndPriorityRanking() throws Exception {
        Path probe3Path = resolveFormattingFixturePath("pretty", "fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn");
        PsiFile file = myFixture.configureByText(
            "fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn",
            Files.readString(probe3Path)
        );
        myFixture.openFileInEditor(file.getVirtualFile());

        var disposable = Disposer.newDisposable("Target3FilterFocusTest");
        try {
            var ui = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(), file, StvnNamespaceScope.TYPE, disposable
            );

            // 1. Before snapshot finishes, filterFocusAction presentation stays enabled so Alt+F / Ctrl+F never leak!
            assertTrue(isPopupActionPresentationEnabled(ui.filterFocusAction()));

            ui.waitForAnalysis(5, TimeUnit.SECONDS);
            ui.panel().setSize(new Dimension(920, 440));
            doLayoutRecursively(ui.panel());

            var editor = ui.filterField().getTextEditor();

            // 2. STE hover tooltip on both SearchTextField and inner JBTextField + zero TableSpeedSearch bubble
            assertEquals("Filter symbols by name, source, target, or type (Alt+F or Ctrl+F)", StvnNamespaceBrowserPopup.FILTER_FIELD_TOOLTIP);
            assertEquals(StvnNamespaceBrowserPopup.FILTER_FIELD_TOOLTIP, ui.filterField().getToolTipText());
            assertEquals(StvnNamespaceBrowserPopup.FILTER_FIELD_TOOLTIP, editor.getToolTipText());
            assertNull("TableSpeedSearch must not be installed on Namespace Browser JBTable",
                SpeedSearchSupply.getSupply(ui.table()));

            // 3. Alt+F, Ctrl+F, and Meta+F bindings registered on panel & selectAll() behavior
            var inputMap = ui.panel().getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
            KeyStroke altF = KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.ALT_DOWN_MASK);
            KeyStroke ctrlF = KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK);
            KeyStroke metaF = KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.META_DOWN_MASK);
            assertNotNull(inputMap.get(altF));
            assertNotNull(inputMap.get(ctrlF));
            assertNotNull(inputMap.get(metaF));

            ui.filterField().setText("Telemetry");
            editor.setCaretPosition(2);
            assertEquals(editor.getSelectionStart(), editor.getSelectionEnd());

            // Invoke Alt+F via InputMap/ActionMap -> selects all text so next typed character overwrites!
            Action focusAction = ui.panel().getActionMap().get(inputMap.get(altF));
            assertNotNull(focusAction);
            focusAction.actionPerformed(new ActionEvent(ui.panel(), ActionEvent.ACTION_PERFORMED, "altF"));
            assertEquals(0, editor.getSelectionStart());
            assertEquals("Telemetry".length(), editor.getSelectionEnd());

            // Invoke Ctrl+F via AnAction -> selects all text identically
            editor.setCaretPosition(0);
            firePopupAction(ui.filterFocusAction());
            assertEquals(0, editor.getSelectionStart());
            assertEquals("Telemetry".length(), editor.getSelectionEnd());

            // Typing ':' from JBTable while "Telemetry" is selected replaces selection strictly once -> ":"
            dispatchFullKeySequence(ui.table(), KeyEvent.VK_SEMICOLON, InputEvent.SHIFT_DOWN_MASK, ':');
            assertEquals(":", ui.filterField().getText());
            assertEquals(1, editor.getCaretPosition());

            // 4. Type-anywhere across JBTable, JRadioButton, and JBCheckBox (zero dropped or doubled characters!)
            ui.filterField().setText("");
            JRadioButton typeRadio = ui.scopeButtons().get(StvnNamespaceScope.TYPE);
            for (char ch : "PacketHeaderTag".toCharArray()) {
                Component origin = (ch == 'P' || ch == 'H') ? ui.table()
                    : (ch == 'a' ? typeRadio : ui.showPreludeCheckBox());
                int mods = Character.isUpperCase(ch) ? InputEvent.SHIFT_DOWN_MASK : 0;
                dispatchFullKeySequence(origin, KeyEvent.getExtendedKeyCodeForChar(ch), mods, ch);
            }
            assertEquals("PacketHeaderTag", ui.filterField().getText());
            assertEquals("PacketHeaderTag".length(), editor.getCaretPosition());

            // 5. Prioritized match ordering: in :type scope, :TelemetryRow (depth 1) & :IndexedOutcome (depth 1)
            // precede :PacketHeaderTag (depth 2) and contain :PacketHeaderTag in Resolved Type,
            // yet :PacketHeaderTag (Name match) MUST rank at row 0 and be selected!
            assertEquals(3, ui.tableModel().getRowCount());
            assertEquals(":PacketHeaderTag", ui.tableModel().getItem(0).name());
            assertEquals(0, ui.table().getSelectedRow());

            // 6. BackSpace on JBTable deletes trailing character in filterField ("PacketHeaderTag" -> "PacketHeaderTa")
            dispatchKeyPressedOnly(ui.table(), KeyEvent.VK_BACK_SPACE, 0);
            assertEquals("PacketHeaderTa", ui.filterField().getText());
            assertEquals("PacketHeaderTa".length(), editor.getCaretPosition());

            // 7. Space on showPreludeCheckBox does NOT insert ' ' into filterField
            dispatchFullKeySequence(ui.showPreludeCheckBox(), KeyEvent.VK_SPACE, 0, ' ');
            assertEquals("PacketHeaderTa", ui.filterField().getText());

            // 8. Up / Down / PageDown / PageUp & Enter inside filterField.getTextEditor()
            ui.filterField().setText("");
            assertEquals(5, ui.table().getRowCount());
            assertEquals(0, ui.table().getSelectedRow());

            dispatchKeyPressedOnly(editor, KeyEvent.VK_DOWN, 0);
            assertEquals(1, ui.table().getSelectedRow());

            dispatchKeyPressedOnly(editor, KeyEvent.VK_PAGE_DOWN, 0);
            assertEquals(4, ui.table().getSelectedRow());

            dispatchKeyPressedOnly(editor, KeyEvent.VK_UP, 0);
            assertEquals(3, ui.table().getSelectedRow());

            dispatchKeyPressedOnly(editor, KeyEvent.VK_PAGE_UP, 0);
            assertEquals(0, ui.table().getSelectedRow());

            // Select :PacketHeaderTag via filter and press Enter inside filterField.getTextEditor() -> navigates editor caret!
            ui.filterField().setText(":PacketHeaderTag");
            assertEquals(":PacketHeaderTag", ui.tableModel().getItem(ui.table().convertRowIndexToModel(ui.table().getSelectedRow())).name());
            AtomicInteger navCount = new AtomicInteger();
            Runnable baseNav = ui.navigationHandlerRef().get();
            ui.navigationHandlerRef().set(() -> {
                navCount.incrementAndGet();
                baseNav.run();
            });
            dispatchKeyPressedOnly(editor, KeyEvent.VK_ENTER, 0);
            assertEquals("Pressing Enter in filterField must invoke declaration navigation strictly once", 1, navCount.get());
            var activeEditor = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
            assertNotNull(activeEditor);
            int expectedDeclOffset = file.getText().indexOf(":PacketHeaderTag");
            assertEquals(expectedDeclOffset, activeEditor.getCaretModel().getOffset());

            // 9. Verify global 4-column priority order: Name -> Upstream Target -> Source -> Resolved Type
            List<StvnNamespaceSymbolEntry> priorityFixture = List.of(
                new StvnNamespaceSymbolEntry(":Alpha", "other.stvn", "\u2014", ":Tuple(:SharedNeedle :Int)", 0, null, false, 0, StvnNamespaceScope.DEFS),
                new StvnNamespaceSymbolEntry(":Beta", ":pkg/sharedneedle", "\u2014", ":Int", 0, null, false, 0, StvnNamespaceScope.DEFS),
                new StvnNamespaceSymbolEntry(":Gamma", ":pkg/core", ":pkg/core/SharedNeedle", ":Int", 1, null, false, 0, StvnNamespaceScope.DEFS),
                new StvnNamespaceSymbolEntry(":SharedNeedle", "local.stvn", "\u2014", ":String", 0, null, false, 0, StvnNamespaceScope.DEFS)
            );
            List<StvnNamespaceSymbolEntry> ranked = StvnNamespaceBrowserPopup.applyFilterQuery(priorityFixture, "sharedneedle");
            assertEquals(
                List.of(":SharedNeedle", ":Gamma", ":Beta", ":Alpha"),
                ranked.stream().map(StvnNamespaceSymbolEntry::name).toList()
            );
        } finally {
            Disposer.dispose(disposable);
        }
    }

    private static Path resolveFormattingFixturePath(String variantDir, String fileName) {
        Path mirrored = Path.of("src/test/resources/shared-fixtures/formatting/" + variantDir + "/" + fileName);
        if (Files.exists(mirrored)) {
            return mirrored;
        }
        return Path.of("../ij_stvnadore_core/shared-fixtures/formatting/" + variantDir + "/" + fileName);
    }

    private static StvnNamespaceSymbolEntry findEntryByName(List<StvnNamespaceSymbolEntry> entries, String name) {
        for (var e : entries) {
            if (name.equals(e.name())) {
                return e;
            }
        }
        return null;
    }

    private static void doLayoutRecursively(Component comp) {
        comp.doLayout();
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                doLayoutRecursively(child);
            }
            container.doLayout();
        }
    }

    private static void dispatchFullKeySequence(Component target, int keyCode, int modifiersEx, char keyChar) {
        long now = System.currentTimeMillis();
        KeyEvent pressed = new KeyEvent(target, KeyEvent.KEY_PRESSED, now, modifiersEx, keyCode, keyChar);
        for (KeyListener kl : target.getKeyListeners()) {
            kl.keyPressed(pressed);
        }
        KeyEvent typed = new KeyEvent(target, KeyEvent.KEY_TYPED, now + 1, modifiersEx, KeyEvent.VK_UNDEFINED, keyChar);
        for (KeyListener kl : target.getKeyListeners()) {
            kl.keyTyped(typed);
        }
        KeyEvent released = new KeyEvent(target, KeyEvent.KEY_RELEASED, now + 2, modifiersEx, keyCode, keyChar);
        for (KeyListener kl : target.getKeyListeners()) {
            kl.keyReleased(released);
        }
    }

    private static void dispatchKeyPressedOnly(Component target, int keyCode, int modifiersEx) {
        long now = System.currentTimeMillis();
        KeyEvent pressed = new KeyEvent(target, KeyEvent.KEY_PRESSED, now, modifiersEx, keyCode, KeyEvent.CHAR_UNDEFINED);
        for (KeyListener kl : target.getKeyListeners()) {
            kl.keyPressed(pressed);
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
}
