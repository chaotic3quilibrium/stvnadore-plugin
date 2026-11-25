package org.stvnadore.plugin.browser;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.table.JBTable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import javax.swing.JMenuItem;
import java.awt.datatransfer.DataFlavor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Automated platform test suite verifying the 3-scope {@code STVN Namespace Dependencies} dialog,
 * 5-column table schema ({@code Name | Source | Upstream Target | Resolved Type | Use Depth}),
 * zero-bracket-padded STVN 2.0.0 schema formatting, {@code MetadataMap} constant facet reachability,
 * STVN § 4.2 eviction, live scope switching, disabled-section STE tooltips, and declaration navigation.
 */
@NullMarked
public final class StvnNamespaceBrowserTest extends BasePlatformTestCase {

    private static final String MODULAR_SCHEMA_CONTENT = """
        {
          :meta {
            #name "browser_modular_schema"
            #domain "finance"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :package :com/example/core {
              :AccountStatus :Enum[#ACTIVE #SUSPENDED #CLOSED]
              :BaseEntity    :Tuple(:Int :String)
              #MIN_TX_AMOUNT :Int 10
            }
            :package :com/example/finance {
              :Transaction :Tuple(:Int :Float :String)
            }
          }
        }
        """;

    private static final String BROWSER_DOCUMENT_CONTENT = """
        {
          :meta {
            #name "browser_document"
            #domain "finance"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "browser_modular_schema.finance.stvn_di" ]
            :use [
              :com/example/core {
                :BaseEntity    :LocalEntity
                :AccountStatus :StatusAlias
                #MIN_TX_AMOUNT #LOCAL_MIN_TX
              }
            ]
            :use [ :com/example/finance { :Transaction :LocalTx } ]
            :use [ :org/stvnadore/prelude { :Port :Port } ]
            :BoundedAmount { #minIncl #LOCAL_MIN_TX } :Int
            :AppConfig     :Tuple(:String :Boolean)
            :DeepAlias     :LocalEntity
            #DEFAULT_PORT  :Port 8080
          }
          :type :Tuple(:LocalTx :StatusAlias :BoundedAmount :Port)
          :body (
            (1001 49.99 "Subscription")
            #ACTIVE
            250
            #DEFAULT_PORT
          )
        }
        """;

    public void testGutterIconsAttachedToSectionHeaders() {
        myFixture.addFileToProject("browser_modular_schema.finance.stvn_di", MODULAR_SCHEMA_CONTENT);
        var file = myFixture.configureByText("browser_document.finance.stvn_i", BROWSER_DOCUMENT_CONTENT);
        myFixture.doHighlighting();

        var markers = myFixture.findAllGutters();
        assertFalse(markers.isEmpty());

        var provider = new StvnSectionNavigationLineMarkerProvider();
        var defsToken = findTokenByText(file, ":defs");
        assertNotNull(defsToken);
        assertNotNull(provider.getLineMarkerInfo(defsToken));

        var typeToken = findTokenByText(file, ":type");
        assertNotNull(typeToken);
        assertNotNull(provider.getLineMarkerInfo(typeToken));

        var bodyToken = findTokenByText(file, ":body");
        assertNotNull(bodyToken);
        assertNotNull(provider.getLineMarkerInfo(bodyToken));
    }

    public void testFiveColumnSchemaAndDefsTypeBodyMathematicalClosuresOnFmtProbe02() throws Exception {
        Path probePath = Path.of("src/test/resources/shared-fixtures/formatting/input/fmt_probe_02_constants_meta_use_and_maps.core_test.stvn");
        if (!Files.exists(probePath)) {
            probePath = Path.of("../ij_stvnadore_core/shared-fixtures/formatting/input/fmt_probe_02_constants_meta_use_and_maps.core_test.stvn");
        }
        String probeSrc = Files.readString(probePath);
        PsiFile file = myFixture.configureByText("fmt_probe_02_constants_meta_use_and_maps.core_test.stvn", probeSrc);

        var defsEntries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);
        var model = new StvnNamespaceTableModel(defsEntries);
        assertEquals(5, model.getColumnCount());
        assertEquals("Name", model.getColumnName(0));
        assertEquals("Source", model.getColumnName(1));
        assertEquals("Upstream Target", model.getColumnName(2));
        assertEquals("Resolved Type", model.getColumnName(3));
        assertEquals("Use Depth", model.getColumnName(4));

        // 1. :defs scope: includes root declarations + :use projections; excludes private :cooling/manifold/* internals
        var manifoldReport = findEntryByName(defsEntries, ":ManifoldReport");
        assertNotNull(manifoldReport);
        assertEquals("fmt_probe_02_constants_meta_use_and_maps.core_test.stvn", manifoldReport.source());
        assertEquals("\u2014", manifoldReport.upstreamTarget());
        assertEquals(":Tuple(:ValveMode :LoopTempC :Int :Boolean :ZoneFlowMap)", manifoldReport.resolvedType());
        assertEquals(0, manifoldReport.useDepth());

        var valveMode = findEntryByName(defsEntries, ":ValveMode");
        assertNotNull(valveMode);
        assertEquals(":cooling/manifold", valveMode.source());
        assertEquals(":cooling/manifold/CoolantValveMode", valveMode.upstreamTarget());
        assertEquals(":Enum[#CLOSED #THROTTLED #OPEN]", valveMode.resolvedType());
        assertEquals(1, valveMode.useDepth());

        var initValve = findEntryByName(defsEntries, "#INIT_VALVE_MODE");
        assertNotNull(initValve);
        assertEquals(":cooling/manifold", initValve.source());
        assertEquals("#cooling/manifold/DEFAULT_VALVE_MODE", initValve.upstreamTarget());
        assertEquals(":ValveMode (-> :cooling/manifold/CoolantValveMode -> :Enum)", initValve.resolvedType());
        assertEquals(1, initValve.useDepth());

        assertNull("Un-imported raw :cooling/manifold/CoolantValveMode must not leak into root :defs scope",
            findEntryByName(defsEntries, ":cooling/manifold/CoolantValveMode"));
        assertNull("Un-imported raw #cooling/manifold/DEFAULT_VALVE_MODE must not leak into root :defs scope",
            findEntryByName(defsEntries, "#cooling/manifold/DEFAULT_VALVE_MODE"));

        // 2. :type scope: includes :ManifoldReport, :ValveMode, :LoopTempC, :ZoneFlowMap, :LoopFlowLpm; excludes :body-only #CONSTs!
        var typeEntries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.TYPE);
        assertNotNull(findEntryByName(typeEntries, ":ManifoldReport"));
        assertNotNull(findEntryByName(typeEntries, ":ValveMode"));
        assertNotNull(findEntryByName(typeEntries, ":LoopTempC"));
        assertNotNull(findEntryByName(typeEntries, ":ZoneFlowMap"));
        var typeLoopFlow = findEntryByName(typeEntries, ":LoopFlowLpm");
        assertNotNull(typeLoopFlow);
        assertEquals(2, typeLoopFlow.useDepth());
        assertEquals("{ #unsigned #size 16 #maxExcl 5000 } :Int", typeLoopFlow.resolvedType());
        assertNull("#INIT_VALVE_MODE used only in :body must be excluded from :type scope",
            findEntryByName(typeEntries, "#INIT_VALVE_MODE"));
        assertNull("#STANDBY_TEMP_C used only in :body must be excluded from :type scope",
            findEntryByName(typeEntries, "#STANDBY_TEMP_C"));
        assertNull("#MAX_FLOW_LPM used only in :body must be excluded from :type scope",
            findEntryByName(typeEntries, "#MAX_FLOW_LPM"));

        // 3. :body scope: includes :type closure + #INIT_VALVE_MODE, #STANDBY_TEMP_C, #MAX_FLOW_LPM; excludes #T / #THROTTLED literals!
        var bodyEntries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.BODY);
        assertNotNull(findEntryByName(bodyEntries, ":ManifoldReport"));
        assertNotNull(findEntryByName(bodyEntries, ":ValveMode"));
        assertNotNull(findEntryByName(bodyEntries, ":LoopTempC"));
        assertNotNull(findEntryByName(bodyEntries, ":ZoneFlowMap"));
        assertNotNull(findEntryByName(bodyEntries, ":LoopFlowLpm"));
        assertNotNull(findEntryByName(bodyEntries, "#INIT_VALVE_MODE"));
        assertNotNull(findEntryByName(bodyEntries, "#STANDBY_TEMP_C"));
        assertNotNull(findEntryByName(bodyEntries, "#MAX_FLOW_LPM"));
        assertNull("Boolean literal #T must not pollute :body symbol closure", findEntryByName(bodyEntries, "#T"));
        assertNull("Enum variant #THROTTLED must not pollute :body symbol closure", findEntryByName(bodyEntries, "#THROTTLED"));
        assertNull("Unprojected :cooling/manifold/CoolantValveMode must not duplicate :ValveMode in :body",
            findEntryByName(bodyEntries, ":cooling/manifold/CoolantValveMode"));
    }

    public void testNeutrinoEvictionAndTypeFacetConstClosureAndLiveScopeSwitcher() throws Exception {
        myFixture.addFileToProject("browser_modular_schema.finance.stvn_di", MODULAR_SCHEMA_CONTENT);
        var payloadFile = myFixture.configureByText("browser_document.finance.stvn_i", BROWSER_DOCUMENT_CONTENT);

        // Verify :defs, :type (including #LOCAL_MIN_TX in MetadataMap facet!), and :body (#DEFAULT_PORT added)
        var defs = StvnNamespaceSymbolCollector.collectSymbols(payloadFile, StvnNamespaceScope.DEFS);
        assertNotNull("Dead root type :AppConfig must be included in :defs scope", findEntryByName(defs, ":AppConfig"));
        var deepAlias = findEntryByName(defs, ":DeepAlias");
        assertNotNull(deepAlias);
        assertTrue(deepAlias.useDepth() >= 2);

        var typeClosure = StvnNamespaceSymbolCollector.collectSymbols(payloadFile, StvnNamespaceScope.TYPE);
        assertNotNull(findEntryByName(typeClosure, ":LocalTx"));
        assertNotNull(findEntryByName(typeClosure, ":StatusAlias"));
        assertNotNull(findEntryByName(typeClosure, ":BoundedAmount"));
        assertNotNull(findEntryByName(typeClosure, ":Port"));
        assertNotNull("Facet #CONST #LOCAL_MIN_TX in reachable MetadataMap must be included in :type closure",
            findEntryByName(typeClosure, "#LOCAL_MIN_TX"));
        assertNull("Unreferenced :AppConfig must be excluded from :type closure",
            findEntryByName(typeClosure, ":AppConfig"));
        assertNull(":body-only #DEFAULT_PORT must be excluded from :type closure",
            findEntryByName(typeClosure, "#DEFAULT_PORT"));

        var bodyClosure = StvnNamespaceSymbolCollector.collectSymbols(payloadFile, StvnNamespaceScope.BODY);
        assertNotNull(findEntryByName(bodyClosure, "#LOCAL_MIN_TX"));
        assertNotNull(":body #CONST #DEFAULT_PORT must be included in :body closure",
            findEntryByName(bodyClosure, "#DEFAULT_PORT"));
        assertNull("Dead :AppConfig must remain excluded in :body closure",
            findEntryByName(bodyClosure, ":AppConfig"));
        assertNull("Enum variant #ACTIVE must not appear in :body closure",
            findEntryByName(bodyClosure, "#ACTIVE"));

        // Verify live in-dialog scope switching (:defs -> :type -> :body), static title, 3-row header, and SearchTextField filter!
        var disposable = Disposer.newDisposable("LiveSwitcherTest");
        try {
            var ui = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(),
                payloadFile,
                StvnNamespaceScope.DEFS,
                disposable
            );
            ui.waitForAnalysis(5, TimeUnit.SECONDS);

            assertEquals("STVN Namespace Dependencies", ui.popupTitle());
            assertEquals("File: browser_document.finance.stvn_i", ui.fileLabel().getText());
            assertNotNull(ui.fileLabel().getToolTipText());
            assertTrue(ui.fileLabel().getToolTipText().contains("browser_document.finance.stvn_i"));

            var defsBtn = ui.scopeButtons().get(StvnNamespaceScope.DEFS);
            var typeBtn = ui.scopeButtons().get(StvnNamespaceScope.TYPE);
            var bodyBtn = ui.scopeButtons().get(StvnNamespaceScope.BODY);
            assertTrue(defsBtn.isEnabled());
            assertTrue(typeBtn.isEnabled());
            assertTrue(bodyBtn.isEnabled());
            assertEquals(":defs", defsBtn.getText());
            assertEquals(":type", typeBtn.getText());
            assertEquals(":body", bodyBtn.getText());
            assertEquals(0, defsBtn.getMnemonic());
            assertEquals(0, typeBtn.getMnemonic());
            assertEquals(0, bodyBtn.getMnemonic());
            assertEquals(StvnNamespaceBrowserPopup.ENABLED_DEFS_SCOPE_TOOLTIP, defsBtn.getToolTipText());
            assertEquals(StvnNamespaceBrowserPopup.ENABLED_TYPE_SCOPE_TOOLTIP, typeBtn.getToolTipText());
            assertEquals(StvnNamespaceBrowserPopup.ENABLED_BODY_SCOPE_TOOLTIP, bodyBtn.getToolTipText());
            assertEquals(defs.size(), ui.tableModel().getRowCount());

            // Switch live to :type
            typeBtn.doClick();
            assertEquals(StvnNamespaceScope.TYPE, ui.activeScopeRef().get());
            assertEquals(typeClosure.size(), ui.tableModel().getRowCount());
            assertTrue(ui.headerLabel().getText().contains(":type"));
            assertEquals("STVN Namespace Dependencies", StvnNamespaceBrowserPopup.resolvePopupTitle());

            // Switch live to :body
            bodyBtn.doClick();
            assertEquals(StvnNamespaceScope.BODY, ui.activeScopeRef().get());
            assertEquals(bodyClosure.size(), ui.tableModel().getRowCount());
            assertTrue(ui.headerLabel().getText().contains(":body"));
            assertEquals("STVN Namespace Dependencies", ui.popupTitle());

            // Filter live by "#DEFAULT_PORT"
            ui.filterField().setText("#DEFAULT_PORT");
            assertEquals(1, ui.tableModel().getRowCount());
            assertEquals("#DEFAULT_PORT", ui.tableModel().getItem(0).name());

            // Clear filter
            ui.filterField().setText("");
            assertEquals(bodyClosure.size(), ui.tableModel().getRowCount());
        } finally {
            Disposer.dispose(disposable);
        }
    }

    public void testDefsOnlyModuleDisablesTypeAndBodyScopesWithExplanatoryTooltips() throws Exception {
        var schemaFile = myFixture.configureByText("browser_modular_schema.finance.stvn_di", MODULAR_SCHEMA_CONTENT);
        var disposable = Disposer.newDisposable("DefsOnlySwitcherTest");
        try {
            var ui = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(),
                schemaFile,
                StvnNamespaceScope.BODY,
                disposable
            );
            ui.waitForAnalysis(5, TimeUnit.SECONDS);

            assertEquals("Must fall back cleanly to :defs when :type and :body are absent",
                StvnNamespaceScope.DEFS, ui.activeScopeRef().get());
            assertTrue(ui.scopeButtons().get(StvnNamespaceScope.DEFS).isEnabled());
            assertTrue(ui.scopeButtons().get(StvnNamespaceScope.DEFS).isSelected());

            var typeBtn = ui.scopeButtons().get(StvnNamespaceScope.TYPE);
            assertFalse(":type button must be disabled on #DEFS_INCLUDE module", typeBtn.isEnabled());
            assertEquals(StvnNamespaceBrowserPopup.DISABLED_TYPE_SCOPE_TOOLTIP, typeBtn.getToolTipText());

            var bodyBtn = ui.scopeButtons().get(StvnNamespaceScope.BODY);
            assertFalse(":body button must be disabled on #DEFS_INCLUDE module", bodyBtn.isEnabled());
            assertEquals(StvnNamespaceBrowserPopup.DISABLED_BODY_SCOPE_TOOLTIP, bodyBtn.getToolTipText());

            // Pure #DEFS_INCLUDE package catalog exports its canonical FQNIs at Use Depth = 0
            var items = ui.tableModel().getItems();
            var acctStatus = findEntryByName(items, ":com/example/core/AccountStatus");
            assertNotNull(acctStatus);
            assertEquals(":com/example/core", acctStatus.source());
            assertEquals("\u2014", acctStatus.upstreamTarget());
            assertEquals(":Enum[#ACTIVE #SUSPENDED #CLOSED]", acctStatus.resolvedType());
            assertEquals(0, acctStatus.useDepth());
        } finally {
            Disposer.dispose(disposable);
        }
    }

    public void testAliasedIncludeEvictsRawIdentifiersFromDefsScope() {
        var remoteSchema = """
            {
              :meta {
                #name "remote_node_schema"
                #domain "physics"
                #kind #DEFS
              }
              :defs {
                :package :neutrino/optics {
                  :Photoelectrons { #unsigned #size 32 } :Int
                  :DomHitTuple    :Tuple(:Photoelectrons :Int)
                  #CALIB_DOM_HIT  :DomHitTuple (42 100)
                }
                :package :neutrino/private_internal {
                  :InternalGain :Float
                }
              }
            }
            """;
        var importingDoc = """
            {
              :meta {
                #name "importing_doc"
                #domain "physics"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :include [ "remote_node_schema.physics.stvn_d" ]
                :use [
                  :neutrino/optics { #strip } {
                    :DomHitTuple :ArrayDomHit
                  }
                ]
              }
            }
            """;
        myFixture.addFileToProject("remote_node_schema.physics.stvn_d", remoteSchema);
        var file = myFixture.configureByText("importing_doc.physics.stvn_di", importingDoc);

        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);
        assertEquals(3, entries.size());

        var arrayDomHit = findEntryByName(entries, ":ArrayDomHit");
        assertNotNull(arrayDomHit);
        assertEquals(":neutrino/optics", arrayDomHit.source());
        assertEquals(":neutrino/optics/DomHitTuple", arrayDomHit.upstreamTarget());
        assertEquals(":Tuple(:Photoelectrons :Int)", arrayDomHit.resolvedType());
        assertEquals(1, arrayDomHit.useDepth());

        assertNull("Evicted :DomHitTuple must NOT appear in :defs scope (§ 4.2)",
            findEntryByName(entries, ":DomHitTuple"));
        assertNull("Un-imported :neutrino/private_internal/InternalGain must NOT appear in :defs scope",
            findEntryByName(entries, ":InternalGain"));
        assertNull(findEntryByName(entries, ":neutrino/private_internal/InternalGain"));

        var photo = findEntryByName(entries, ":Photoelectrons");
        assertNotNull(photo);
        assertEquals(":neutrino/optics", photo.source());
        assertEquals(":neutrino/optics/Photoelectrons", photo.upstreamTarget());
        assertEquals("{ #unsigned #size 32 } :Int", photo.resolvedType());
        assertEquals(1, photo.useDepth());

        var calib = findEntryByName(entries, "#CALIB_DOM_HIT");
        assertNotNull(calib);
        assertEquals(":neutrino/optics", calib.source());
        assertEquals("#neutrino/optics/CALIB_DOM_HIT", calib.upstreamTarget());
        assertEquals(":ArrayDomHit (-> :neutrino/optics/DomHitTuple -> :Tuple)", calib.resolvedType());
    }

    public void testPackagedModuleWithoutRootDefinitionsReturnsExactCanonicalFqnis() throws Exception {
        var rfcPath = Path.of("temp/examples/json/rfc8259_json_substrate.json_schema.stvn_d");
        if (!Files.exists(rfcPath)) {
            return;
        }
        var content = Files.readString(rfcPath);
        var file = myFixture.configureByText("rfc8259_json_substrate.stvn_d", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);

        assertEquals("Must contain exactly 8 declared symbols", 8, entries.size());
        for (var entry : entries) {
            assertTrue(entry.name().startsWith(":org/ietf/rfc8259/json/"));
            assertEquals(":org/ietf/rfc8259/json", entry.source());
            assertEquals("\u2014", entry.upstreamTarget());
            assertEquals(0, entry.useDepth());
        }
        assertNull(findEntryByName(entries, ":JsonNull"));
        assertNotNull(findEntryByName(entries, ":org/ietf/rfc8259/json/JsonNull"));
    }

    public void testMultiColumnSortingAndSpeedSearchAcrossFiveColumns() {
        var content = """
            {
              :defs {
                :package :org/example/finance {
                  :LocalTx :Tuple({ #size 64 } :Int :Float)
                }
                :use [ :org/example/finance { #strip } ]
                :LabelText { #maxSize 32 } :String
                :Envelope  :Tuple(:LocalTx :LabelText)
              }
              :type :Envelope
              :body ((1001 49.99) "ok")
            }
            """;
        var file = myFixture.configureByText("speed_search_five_col.stvn", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);
        var model = new StvnNamespaceTableModel(entries);
        var table = new JBTable(model);
        var speedSearch = StvnPrioritizedTableSpeedSearch.installOn(table);

        @SuppressWarnings("unchecked")
        var depthComparator = (Comparator<StvnNamespaceSymbolEntry>) model.getColumnInfos()[4].getComparator();
        assertNotNull(depthComparator);
        assertEquals(0, depthComparator.compare(entries.get(0), entries.get(0)));

        // Column 0 priority (:LocalTx matches both Name of :LocalTx and Resolved Type of :Envelope)
        speedSearch.findAndSelectElement(":LocalTx");
        assertEquals(0, table.getSelectedColumn());
        assertEquals(":LocalTx", model.getItem(table.convertRowIndexToModel(table.getSelectedRow())).name());

        // Fallback to Column 3 (Resolved Type) when Name/Source/Upstream do not match
        speedSearch.findAndSelectElement("#maxSize 32");
        assertEquals(3, table.getSelectedColumn());
        assertEquals(":LabelText", model.getItem(table.convertRowIndexToModel(table.getSelectedRow())).name());
    }

    public void testCaretScopeResolution() {
        myFixture.addFileToProject("browser_modular_schema.finance.stvn_di", MODULAR_SCHEMA_CONTENT);
        var file = myFixture.configureByText("browser_document.finance.stvn_i", BROWSER_DOCUMENT_CONTENT);

        assertEquals(StvnNamespaceScope.DEFS, StvnNamespaceScope.resolveFromElement(findTokenByText(file, ":defs")));
        assertEquals(StvnNamespaceScope.TYPE, StvnNamespaceScope.resolveFromElement(findTokenByText(file, ":type")));
        assertEquals(StvnNamespaceScope.BODY, StvnNamespaceScope.resolveFromElement(findTokenByText(file, ":body")));
        assertEquals(StvnNamespaceScope.DEFS, StvnNamespaceScope.resolveFromElement(file.getFirstChild()));
    }

    public void testTypeAndBodySubsetInvariants() {
        var content = """
            {
              :defs {
                :package :com/example/finance {
                  :LocalTx :Tuple(:Int :Float)
                }
                :use [ :com/example/finance { #strip } ]
                :A :String
                :DeadType :Boolean
                :T :Tuple(:LocalTx :A)
              }
              :type :T
              :body ((1001 49.99) "a")
            }
            """;
        var file = myFixture.configureByText("cascade_document.stvn", content);

        var defsNames = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS)
            .stream().map(StvnNamespaceSymbolEntry::name).collect(Collectors.toSet());
        var typeNames = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.TYPE)
            .stream().map(StvnNamespaceSymbolEntry::name).collect(Collectors.toSet());
        var bodyNames = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.BODY)
            .stream().map(StvnNamespaceSymbolEntry::name).collect(Collectors.toSet());

        assertTrue(defsNames.contains(":DeadType"));
        assertFalse(typeNames.contains(":DeadType"));
        assertFalse(bodyNames.contains(":DeadType"));
        assertEquals(typeNames, bodyNames);
        assertTrue(defsNames.containsAll(typeNames));
    }

    public void testCollectPreludeSymbolsAndPreludeStripToggle() throws Exception {
        var preludeEntries = StvnNamespaceSymbolCollector.collectPreludeSymbols(getProject());
        assertEquals(29, preludeEntries.size());

        var posixConst = findEntryByName(preludeEntries, "#org/stvnadore/prelude/POSIX_ID_REGEX");
        assertNotNull(posixConst);
        assertTrue(posixConst.isPrelude());
        assertEquals(":org/stvnadore/prelude", posixConst.source());
        assertEquals("\u2014", posixConst.upstreamTarget());
        assertEquals(":String", posixConst.resolvedType());
        assertEquals(0, posixConst.useDepth());

        var docKind = findEntryByName(preludeEntries, ":org/stvnadore/prelude/meta/StvnDocumentKind");
        assertNotNull(docKind);
        assertEquals(":Enum[#BODY #DEFS #BODY_INCLUDE #DEFS_INCLUDE]", docKind.resolvedType());

        var stripFile = myFixture.configureByText("prelude_strip.stvn", """
            {
              :defs {
                :use [ :org/stvnadore/prelude { #strip } ]
              }
            }
            """);
        var disposable = Disposer.newDisposable("PreludeStripToggle");
        try {
            var ui = StvnNamespaceBrowserPopup.createBrowserContent(getProject(), stripFile, StvnNamespaceScope.DEFS, disposable);
            ui.waitForAnalysis(5, TimeUnit.SECONDS);

            var initialEntries = ui.tableModel().getItems();
            var barePort = findEntryByName(initialEntries, ":Port");
            assertNotNull(barePort);
            assertEquals(":org/stvnadore/prelude", barePort.source());
            assertEquals(":org/stvnadore/prelude/Port", barePort.upstreamTarget());
            assertEquals("{ #unsigned #size 16 #minIncl 1 } :Int", barePort.resolvedType());
            assertEquals(1, barePort.useDepth());

            var bareRegex = findEntryByName(initialEntries, "#POSIX_ID_REGEX");
            assertNotNull(bareRegex);
            assertEquals("#org/stvnadore/prelude/POSIX_ID_REGEX", bareRegex.upstreamTarget());
            assertEquals(":String", bareRegex.resolvedType());

            assertNull(findEntryByName(initialEntries, ":org/stvnadore/prelude/Port"));

            ui.showPreludeCheckBox().doClick();
            var merged = ui.tableModel().getItems();
            assertNotNull(findEntryByName(merged, ":Port"));
            assertNotNull(findEntryByName(merged, ":org/stvnadore/prelude/Port"));
            assertNotNull(findEntryByName(merged, ":org/stvnadore/prelude/meta/StvnDocumentKind"));
        } finally {
            Disposer.dispose(disposable);
        }
    }

    public void testDocumentWithUndefinedTypeReferencesProducesZeroPhantomEntries() {
        var content = """
            {
              :defs {
                :Values :Tuple(
                  :TimeEpochNs
                  :TimeEpochUs
                  :org/stvnadore/prelude/TimeEpochNs
                  :org/stvnadore/prelude/DateTime
                  { #offset } :org/stvnadore/prelude/DateTime
                  { #ns } :org/stvnadore/prelude/TimeEpoch
                )
              }
              :type :Values
              :body (1 2 3 "2026-10-03" "2026-10-03" 1789241400)
            }
            """;
        var file = myFixture.configureByText("undefined_references.stvn", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.TYPE);

        assertEquals(1, entries.size());
        assertNotNull(findEntryByName(entries, ":Values"));
    }

    public void testIrSubstrateNormalizedPrefixSymbolCollection() throws Exception {
        var substratePath = Path.of("src/test/resources/shared-fixtures/syntax/valid/modules/stvn_ir_substrate.stvn_ir.stvn_d");
        if (!Files.exists(substratePath)) {
            substratePath = Path.of("../ij_stvnadore_core/shared-fixtures/syntax/valid/modules/stvn_ir_substrate.stvn_ir.stvn_d");
        }
        var substrateContent = Files.readString(substratePath);
        myFixture.addFileToProject("stvn_ir_substrate.stvn_ir.stvn_d", substrateContent);

        var content = """
            {
              :meta {
                #name "ir_test"
                #domain "stvn_ir"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "stvn_ir_substrate.stvn_ir.stvn_d" ]
                :use [ :org/stvnadore/ir { #strip } ]
              }
              :type :IrSnapshotDocument
              :body ({} #1 #TRUE)
            }
            """;
        var file = myFixture.configureByText("ir_test.stvn_ir.stvn_i", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);

        assertNotNull(findEntryByName(entries, ":IrTraitConstraints"));
        assertNotNull(findEntryByName(entries, ":IrRegisteredSchema"));
        assertNotNull(findEntryByName(entries, ":IrTypeRegistry"));
        assertNotNull(findEntryByName(entries, ":IrSnapshotDocument"));
    }

    public void testThreadSafetyContextMenuClipboardUseMergeAndCompilation() throws Exception {
        myFixture.addFileToProject("browser_modular_schema.finance.stvn_di", MODULAR_SCHEMA_CONTENT);
        var file = myFixture.configureByText("browser_document.finance.stvn_i", BROWSER_DOCUMENT_CONTENT);

        for (var scope : StvnNamespaceScope.values()) {
            var future = CompletableFuture.supplyAsync(() -> {
                assertFalse(ApplicationManager.getApplication().isReadAccessAllowed());
                return StvnNamespaceSymbolCollector.collectSymbols(file, scope);
            });
            assertFalse(future.get(5, TimeUnit.SECONDS).isEmpty());
        }

        // Context menu, Copy FQNI, and :use merge
        var constantEntry = new StvnNamespaceSymbolEntry(
            "#INIT_VALVE_MODE",
            ":cooling/manifold",
            "#cooling/manifold/DEFAULT_VALVE_MODE",
            ":ValveMode (-> :Enum)",
            1,
            null,
            false,
            0,
            StvnNamespaceScope.DEFS
        );
        StvnNamespaceBrowserPopup.copyFqniToClipboard(getProject(), constantEntry);
        assertEquals("#cooling/manifold/DEFAULT_VALVE_MODE",
            CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor));

        var constMenu = StvnNamespaceBrowserPopup.buildContextMenu(getProject(), file, constantEntry);
        var constItems = Arrays.stream(constMenu.getComponents())
            .filter(JMenuItem.class::isInstance)
            .map(c -> ((JMenuItem) c).getText())
            .toList();
        assertEquals(2, constItems.size());

        var mergeFile = myFixture.configureByText("merge_test.stvn", """
            {
              :defs {
                :use [ :org/stvnadore/prelude { :Port :Port } ]
              }
            }
            """);
        var regexEntry = new StvnNamespaceSymbolEntry(
            "#POSIX_ID_REGEX",
            ":org/stvnadore/prelude",
            "#org/stvnadore/prelude/POSIX_ID_REGEX",
            ":String",
            1,
            null,
            true,
            0,
            StvnNamespaceScope.DEFS
        );
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
            StvnNamespaceBrowserPopup.insertUseReferenceInEditor(getProject(), mergeFile, regexEntry)
        );
        assertTrue(mergeFile.getText().contains(":use [ :org/stvnadore/prelude { :Port :Port #POSIX_ID_REGEX #POSIX_ID_REGEX } ]"));

        // Verify inlined test fixtures compile with 0 errors
        var schemaResult = StvnCompiler.compileToResult(
            MODULAR_SCHEMA_CONTENT,
            "browser_modular_schema.finance.stvn_di",
            StvnParserConfig.DEFAULT
        );
        assertFalse("Modular schema must compile cleanly: " + schemaResult.diagnostics(), schemaResult.hasErrors());

        var tempDir = Files.createTempDirectory("stvn_browser_test");
        try {
            Files.writeString(tempDir.resolve("browser_modular_schema.finance.stvn_di"), MODULAR_SCHEMA_CONTENT);
            var docPath = tempDir.resolve("browser_document.finance.stvn_i");
            Files.writeString(docPath, BROWSER_DOCUMENT_CONTENT);
            var docResult = StvnCompiler.compileToResult(
                BROWSER_DOCUMENT_CONTENT,
                docPath.toString(),
                StvnParserConfig.DEFAULT
            );
            assertFalse("Modular payload must compile cleanly: " + docResult.diagnostics(), docResult.hasErrors());
        } finally {
            FileUtil.delete(tempDir.toFile());
        }
    }

    private static @Nullable PsiElement findTokenByText(PsiFile file, String text) {
        int idx = file.getText().indexOf(text);
        return idx >= 0 ? file.findElementAt(idx) : null;
    }

    private static @Nullable StvnNamespaceSymbolEntry findEntryByName(List<StvnNamespaceSymbolEntry> list, String name) {
        for (var entry : list) {
            if (entry.name().equals(name)) {
                return entry;
            }
        }
        return null;
    }
}
