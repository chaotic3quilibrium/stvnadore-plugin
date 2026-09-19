package org.stvnadore.plugin.browser;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.table.JBTable;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.psi.StvnTypes;

/**
 * Automated platform test suite verifying the interactive namespace dependency browser,
 * gutter navigation line markers, symbol collection, depth calculations, sorting, and navigation.
 */
@NullMarked
public final class StvnNamespaceBrowserTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/shared-fixtures/browser";
    }

    /**
     * Verifies that line marker providers attach gutter icons to :defs, :type, and :body headers.
     */
    public void testGutterIconsAttachedToSectionHeaders() {
        var file = myFixture.configureByFile("browser_document.stvn");
        myFixture.doHighlighting();

        var markers = myFixture.findAllGutters();
        assertFalse(markers.isEmpty());

        var provider = new StvnSectionNavigationLineMarkerProvider();
        var defsToken = findTokenByText(file, ":defs");
        assertNotNull(defsToken);
        var defsMarker = provider.getLineMarkerInfo(defsToken);
        assertNotNull(defsMarker);

        var typeToken = findTokenByText(file, ":type");
        assertNotNull(typeToken);
        var typeMarker = provider.getLineMarkerInfo(typeToken);
        assertNotNull(typeMarker);

        var bodyToken = findTokenByText(file, ":body");
        assertNotNull(bodyToken);
        var bodyMarker = provider.getLineMarkerInfo(bodyToken);
        assertNotNull(bodyMarker);
    }

    /**
     * Verifies symbol collection under :defs scope, checking alias hop counts and imports.
     */
    public void testDefsScopeSymbolCollection() {
        myFixture.copyFileToProject("browser_modular_schema.stvn_inclf");
        var file = myFixture.configureByFile("browser_document.stvn");

        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);
        assertFalse(entries.isEmpty());

        var appConfig = findEntryByName(entries, ":AppConfig");
        assertNotNull(appConfig);
        assertEquals(0, appConfig.useDepth());
        assertEquals(file.getName(), appConfig.source());

        var deepAlias = findEntryByName(entries, ":DeepAlias");
        assertNotNull(deepAlias);
        assertTrue(deepAlias.useDepth() >= 2);

        var defaultPort = findEntryByName(entries, "#DefaultPort");
        assertNotNull(defaultPort);
        assertEquals(0, defaultPort.useDepth());

        var localTx = findEntryByName(entries, ":LocalTx");
        assertNotNull(localTx);
        assertEquals(1, localTx.useDepth());
    }

    /**
     * Verifies that a packaged module without root definitions returns exactly
     * its declared canonical FQNIs, producing zero bare phantom duplicates.
     */
    public void testPackagedModuleWithoutRootDefinitionsReturnsExactCanonicalFqnis() throws Exception {
        var rfcPath = java.nio.file.Path.of("temp/examples/json/rfc8259_json_substrate.stvn_inclf");
        var content = java.nio.file.Files.readString(rfcPath);
        var file = myFixture.configureByText("rfc8259_json_substrate.stvn_inclf", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);

        assertEquals("Must contain exactly 8 declared symbols", 8, entries.size());
        for (var entry : entries) {
            assertTrue("Symbol must be a canonical FQNI starting with package prefix: " + entry.name(),
                entry.name().startsWith(":org/ietf/rfc8259/json/"));
            assertEquals(":org/ietf/rfc8259/json", entry.source());
            assertEquals(0, entry.useDepth());
        }
        assertNull("Bare phantom symbol :JsonNull must not exist", findEntryByName(entries, ":JsonNull"));
        assertNotNull("Canonical FQNI must exist", findEntryByName(entries, ":org/ietf/rfc8259/json/JsonNull"));
    }

    /**
     * Verifies that a document with mixed declarations isolates root symbols bare
     * and packaged symbols strictly under FQNIs.
     */
    public void testMixedRootAndPackagedDefinitionsScopeIsolation() {
        var content = """
            {
              :defs {
                :package :org/stvnadore/sample {
                  :PackagedItem :Int64
                  #PackagedPort 9090
                }
                :RootItem :String32
                #RootPort 8080
              }
            }
            """;
        var file = myFixture.configureByText("mixed_scope.stvn", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);

        assertEquals("Must contain exactly 4 symbols (2 root + 2 packaged)", 4, entries.size());
        assertNotNull(findEntryByName(entries, ":RootItem"));
        assertNotNull(findEntryByName(entries, "#RootPort"));
        assertNotNull(findEntryByName(entries, ":org/stvnadore/sample/PackagedItem"));
        assertNotNull(findEntryByName(entries, ":org/stvnadore/sample/#PackagedPort"));
        assertNull(findEntryByName(entries, ":PackagedItem"));
        assertNull(findEntryByName(entries, "#PackagedPort"));
    }

    /**
     * Verifies that :use with #strip creates desugared bare aliases with incremented Use Depth.
     */
    public void testPackageImportWithStripProducesDesugaredAliasWithIncrementedDepth() {
        var content = """
            {
              :defs {
                :package :org/stvnadore/sample {
                  :PackagedItem :Int64
                }
                :use [ :org/stvnadore/sample { #strip } ]
              }
            }
            """;
        var file = myFixture.configureByText("use_strip.stvn", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);

        assertEquals("Must contain 2 symbols (1 canonical FQNI + 1 stripped alias)", 2, entries.size());
        var canonical = findEntryByName(entries, ":org/stvnadore/sample/PackagedItem");
        assertNotNull(canonical);
        assertEquals(0, canonical.useDepth());

        var alias = findEntryByName(entries, ":PackagedItem");
        assertNotNull(alias);
        assertEquals(1, alias.useDepth());
        assertEquals(":org/stvnadore/sample", alias.source());
    }

    /**
     * Verifies symbol filtering under :type scope strictly matches referenced schema symbols.
     */
    public void testTypeScopeFiltering() {
        myFixture.copyFileToProject("browser_modular_schema.stvn_inclf");
        var file = myFixture.configureByFile("browser_document.stvn");

        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.TYPE);
        assertFalse(entries.isEmpty());

        assertNotNull(findEntryByName(entries, ":LocalTx"));
        assertNotNull(findEntryByName(entries, ":StatusAlias"));
        assertNull(findEntryByName(entries, ":AppConfig"));
        assertNull(findEntryByName(entries, "#DefaultPort"));
    }

    /**
     * Verifies symbol filtering under :body scope strictly matches payload instantiated values.
     */
    public void testBodyScopeFiltering() {
        myFixture.copyFileToProject("browser_modular_schema.stvn_inclf");
        var file = myFixture.configureByFile("browser_document.stvn");

        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.BODY);
        assertFalse(entries.isEmpty());

        assertNotNull(findEntryByName(entries, "#ACTIVE"));
        assertNotNull(findEntryByName(entries, ":LocalTx"));
        assertNotNull(findEntryByName(entries, ":StatusAlias"));
        assertNull(findEntryByName(entries, ":AppConfig"));
    }

    /**
     * Verifies nominal shape resolution on composite payloads governed by :Tuple(:LocalTx :A).
     */
    public void testBodyScopeCompositePayloadNominalShapeResolution() {
        var content = """
            {
              :defs {
                :package :org/stvnadore/finance {
                  :LocalTx :Tuple( :Int64 :Float64 )
                }
                :A :String32
              }
              :type :Tuple( :LocalTx :A )
              :body (
                ( 1001 49.99 )
                "a"
              )
            }
            """;
        var file = myFixture.configureByText("composite_payload.stvn", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.BODY);

        var localTx = findEntryByName(entries, ":LocalTx");
        assertNotNull(localTx);
        assertEquals(":org/stvnadore/finance", localTx.source());
        assertEquals(":Tuple( :Int64 :Float64 )", localTx.typeStructure());
        assertEquals(1, localTx.useDepth());
        assertEquals(StvnNamespaceScope.BODY, localTx.scope());

        var aEntry = findEntryByName(entries, ":A");
        assertNotNull(aEntry);
        assertEquals("composite_payload.stvn", aEntry.source());
        assertEquals(":String32", aEntry.typeStructure());
        assertEquals(1, aEntry.useDepth());
        assertEquals(StvnNamespaceScope.BODY, aEntry.scope());
    }

    /**
     * Verifies multi-column sorting behavior in the table model.
     */
    public void testMultiColumnSorting() {
        myFixture.copyFileToProject("browser_modular_schema.stvn_inclf");
        var file = myFixture.configureByFile("browser_document.stvn");

        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);
        var model = new StvnNamespaceTableModel(entries);

        assertEquals(4, model.getColumnCount());
        assertEquals("Name", model.getColumnName(0));
        assertEquals("Root Namespaced Source", model.getColumnName(1));
        assertEquals("Type", model.getColumnName(2));
        assertEquals("Use Depth", model.getColumnName(3));

        @SuppressWarnings("unchecked")
        var depthComparator = (java.util.Comparator<StvnNamespaceSymbolEntry>) model.getColumnInfos()[3].getComparator();
        assertNotNull(depthComparator);
        var entry0 = entries.get(0);
        assertEquals(0, depthComparator.compare(entry0, entry0));
    }

    /**
     * Verifies resolution of enclosing scope based on caret offset.
     */
    public void testCaretScopeResolution() {
        myFixture.copyFileToProject("browser_modular_schema.stvn_inclf");
        var file = myFixture.configureByFile("browser_document.stvn");

        var defsToken = findTokenByText(file, ":defs");
        assertNotNull(defsToken);
        assertEquals(StvnNamespaceScope.DEFS, StvnNamespaceScope.resolveFromElement(defsToken));

        var typeToken = findTokenByText(file, ":type");
        assertNotNull(typeToken);
        assertEquals(StvnNamespaceScope.TYPE, StvnNamespaceScope.resolveFromElement(typeToken));

        var bodyToken = findTokenByText(file, ":body");
        assertNotNull(bodyToken);
        assertEquals(StvnNamespaceScope.BODY, StvnNamespaceScope.resolveFromElement(bodyToken));

        assertEquals(StvnNamespaceScope.DEFS, StvnNamespaceScope.resolveFromElement(file.getFirstChild()));
    }

    /**
     * Verifies that for an intermediate nominal type definition (:type :T where :T :Tuple(:LocalTx :A)),
     * :type scope returns transitive closure :T, :LocalTx, and :A satisfying the scope-narrowing cascade.
     */
    public void testTypeScopeIntermediateNominalDefinitionTransitiveCascade() {
        var content = """
            {
              :defs {
                :package :org/stvnadore/finance {
                  :LocalTx :Tuple( :Int64 :Float64 )
                }
                :use [ :org/stvnadore/finance { #strip } ]
                :A :String32
                :T :Tuple( :LocalTx :A )
              }
              :type :T
              :body (
                ( 1001 49.99 )
                "a"
              )
            }
            """;
        var file = myFixture.configureByText("cascade_document.stvn", content);

        var defsEntries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);
        var typeEntries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.TYPE);
        var bodyEntries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.BODY);

        // Verify :type scope transitive closure contains :T, :LocalTx, and :A
        assertNotNull("Root nominal type :T must be present in :type scope", findEntryByName(typeEntries, ":T"));
        assertNotNull("Transitively referenced :LocalTx must be present in :type scope", findEntryByName(typeEntries, ":LocalTx"));
        assertNotNull("Transitively referenced :A must be present in :type scope", findEntryByName(typeEntries, ":A"));

        // Verify Mathematical Scope-Narrowing Invariant: Symbols(:body) ⊆ Symbols(:type) ⊆ Symbols(:defs)
        var defsNames = defsEntries.stream().map(StvnNamespaceSymbolEntry::name).collect(java.util.stream.Collectors.toSet());
        var typeNames = typeEntries.stream().map(StvnNamespaceSymbolEntry::name).collect(java.util.stream.Collectors.toSet());
        var bodyNames = bodyEntries.stream().map(StvnNamespaceSymbolEntry::name).collect(java.util.stream.Collectors.toSet());

        assertTrue("Symbols(:body) must be subset of Symbols(:type)", typeNames.containsAll(bodyNames));
        assertTrue("Symbols(:type) must be subset of Symbols(:defs)", defsNames.containsAll(typeNames));
    }

    /**
     * Verifies that speed search prioritizes Column 0 (Name) over secondary metadata columns (Type).
     */
    public void testSpeedSearchPrioritizesNameColumnOverTypeMetadata() {
        var content = """
            {
              :defs {
                :package :org/stvnadore/finance {
                  :LocalTx :Tuple( :Int64 :Float64 )
                }
                :use [ :org/stvnadore/finance { #strip } ]
                :A :String32
                :T :Tuple( :LocalTx :A )
              }
              :type :T
              :body (
                ( 1001 49.99 )
                "a"
              )
            }
            """;
        var file = myFixture.configureByText("speed_search_priority.stvn", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);
        var model = new StvnNamespaceTableModel(entries);
        var table = new JBTable(model);
        var speedSearch = StvnPrioritizedTableSpeedSearch.installOn(table);

        // Execute search for :LocalTx
        speedSearch.findAndSelectElement(":LocalTx");

        int selectedVisualRow = table.getSelectedRow();
        int selectedCol = table.getSelectedColumn();
        assertTrue("A row must be selected", selectedVisualRow >= 0);
        assertEquals("Selected column must be Column 0 (Name)", 0, selectedCol);

        int modelRow = table.convertRowIndexToModel(selectedVisualRow);
        var selectedEntry = model.getItem(modelRow);
        assertEquals("Selected entry must be :LocalTx declaration, not :T", ":LocalTx", selectedEntry.name());
    }

    /**
     * Verifies that speed search cleanly falls back to metadata columns when Column 0 contains no match.
     */
    public void testSpeedSearchFallsBackToMetadataColumnsWhenNameDoesNotMatch() {
        var content = """
            {
              :defs {
                :Record :Tuple( :Int64 :Float64 )
              }
              :type :Record
              :body ( 100 20.5 )
            }
            """;
        var file = myFixture.configureByText("speed_search_fallback.stvn", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);
        var model = new StvnNamespaceTableModel(entries);
        var table = new JBTable(model);
        var speedSearch = StvnPrioritizedTableSpeedSearch.installOn(table);

        // Search for :Float64, which appears only in the Type column of :Record
        speedSearch.findAndSelectElement(":Float64");

        int selectedVisualRow = table.getSelectedRow();
        int selectedCol = table.getSelectedColumn();
        assertTrue("A row must be selected", selectedVisualRow >= 0);
        assertEquals("Selected column must fall back to Column 2 (Type)", 2, selectedCol);
    }

    /**
     * Verifies that calcified_audit.stvn_f under :defs scope produces exactly 10 symbols
     * (8 canonical declarations at Depth 0 and 2 root-level stripped aliases at Depth 1),
     * completely isolating package-local :use directives and generating zero bare phantom duplicates.
     */
    public void testCalcifiedAuditDefsScopeProducesExactTenSymbolsWithoutPackageLocalLeakage() throws Exception {
        var path = java.nio.file.Path.of("temp/examples/package_and_use/calcified_audit.stvn_f");
        var content = java.nio.file.Files.readString(path);
        var file = myFixture.configureByText("calcified_audit.stvn_f", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);

        assertEquals("Must contain exactly 10 symbols (8 canonical declarations + 2 stripped aliases)", 10, entries.size());

        // Canonical package declarations at Depth 0
        var micFqni = findEntryByName(entries, ":com/nyse/primitives/MicCode");
        assertNotNull(micFqni);
        assertEquals(0, micFqni.useDepth());
        assertEquals(":com/nyse/primitives", micFqni.source());

        var orderFqni = findEntryByName(entries, ":com/nyse/primitives/OrderId");
        assertNotNull(orderFqni);
        assertEquals(0, orderFqni.useDepth());

        var seqFqni = findEntryByName(entries, ":com/nyse/primitives/SequenceNumber");
        assertNotNull(seqFqni);
        assertEquals(0, seqFqni.useDepth());

        var qtyFqni = findEntryByName(entries, ":com/nyse/primitives/ShareQuantity");
        assertNotNull(qtyFqni);
        assertEquals(0, qtyFqni.useDepth());

        var priceFqni = findEntryByName(entries, ":com/nyse/primitives/ExecutionPrice");
        assertNotNull(priceFqni);
        assertEquals(0, priceFqni.useDepth());

        var sideFqni = findEntryByName(entries, ":com/nyse/primitives/OrderSide");
        assertNotNull(sideFqni);
        assertEquals(0, sideFqni.useDepth());

        var agencyFqni = findEntryByName(entries, ":com/nyse/events/AgencySide");
        assertNotNull(agencyFqni);
        assertEquals(0, agencyFqni.useDepth());
        assertEquals(":com/nyse/events", agencyFqni.source());

        var auditFqni = findEntryByName(entries, ":com/nyse/events/AuditRecord");
        assertNotNull(auditFqni);
        assertEquals(0, auditFqni.useDepth());
        assertEquals(":com/nyse/events", auditFqni.source());

        // Root-level stripped aliases at Depth 1
        var agencyBare = findEntryByName(entries, ":AgencySide");
        assertNotNull("Root-level stripped alias :AgencySide must exist", agencyBare);
        assertEquals(1, agencyBare.useDepth());
        assertEquals(":com/nyse/events", agencyBare.source());

        var auditBare = findEntryByName(entries, ":AuditRecord");
        assertNotNull("Root-level stripped alias :AuditRecord must exist", auditBare);
        assertEquals(1, auditBare.useDepth());
        assertEquals(":com/nyse/events", auditBare.source());

        // Ensure package-private shorthand aliases do not leak bare symbols
        assertNull("Package-local bare symbol :MicCode must not leak into defs scope", findEntryByName(entries, ":MicCode"));
        assertNull("Package-local bare symbol :OrderId must not leak into defs scope", findEntryByName(entries, ":OrderId"));
        assertNull("Package-local bare symbol :SequenceNumber must not leak into defs scope", findEntryByName(entries, ":SequenceNumber"));
        assertNull("Package-local bare symbol :ShareQuantity must not leak into defs scope", findEntryByName(entries, ":ShareQuantity"));
        assertNull("Package-local bare symbol :ExecutionPrice must not leak into defs scope", findEntryByName(entries, ":ExecutionPrice"));
        assertNull("Package-local bare symbol :OrderSide must not leak into defs scope", findEntryByName(entries, ":OrderSide"));
    }

    /**
     * Verifies that calcified_audit.stvn_f under :type scope produces contiguous depths (0, 1, 2)
     * without phantom hop skips, stamping :AuditRecord at Depth 0, direct tuple members at Depth 1,
     * and transitive sub-dependencies at Depth 2.
     */
    public void testCalcifiedAuditTypeScopeProducesContiguousDepthsWithoutPhantomHops() throws Exception {
        var path = java.nio.file.Path.of("temp/examples/package_and_use/calcified_audit.stvn_f");
        var content = java.nio.file.Files.readString(path);
        var file = myFixture.configureByText("calcified_audit.stvn_f", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.TYPE);

        assertEquals("Must contain exactly 10 symbols across depths 0, 1, 2", 10, entries.size());

        // Depth 0: Root contract identifier
        var audit = findEntryByName(entries, ":AuditRecord");
        assertNotNull("Root contract :AuditRecord must exist", audit);
        assertEquals(0, audit.useDepth());

        // Depth 1: 7 immediate tuple members
        var mic = findEntryByName(entries, ":MicCode");
        assertNotNull(":MicCode must exist at Depth 1", mic);
        assertEquals(1, mic.useDepth());

        var seq = findEntryByName(entries, ":SequenceNumber");
        assertNotNull(":SequenceNumber must exist at Depth 1", seq);
        assertEquals(1, seq.useDepth());

        var order = findEntryByName(entries, ":OrderId");
        assertNotNull(":OrderId must exist at Depth 1", order);
        assertEquals(1, order.useDepth());

        var agency = findEntryByName(entries, ":AgencySide");
        assertNotNull(":AgencySide must exist at Depth 1", agency);
        assertEquals(1, agency.useDepth());

        var qty = findEntryByName(entries, ":ShareQuantity");
        assertNotNull(":ShareQuantity must exist at Depth 1", qty);
        assertEquals(1, qty.useDepth());

        var price = findEntryByName(entries, ":ExecutionPrice");
        assertNotNull(":ExecutionPrice must exist at Depth 1", price);
        assertEquals(1, price.useDepth());

        var dt = findEntryByName(entries, ":org/stvnadore/prelude/DateTimeAudited");
        assertNotNull(":DateTimeAudited must exist at Depth 1", dt);
        assertEquals(1, dt.useDepth());

        // Depth 2: 2 transitive types
        var side = findEntryByName(entries, ":OrderSide");
        assertNotNull("Transitive type :OrderSide must exist at Depth 2", side);
        assertEquals(2, side.useDepth());

        var currency = findEntryByName(entries, ":org/stvnadore/prelude/Currency");
        if (currency == null) {
            currency = findEntryByName(entries, ":Currency");
        }
        assertNotNull("Transitive type :Currency must exist at Depth 2", currency);
        assertEquals(2, currency.useDepth());
    }

    /**
     * Verifies that calcified_audit.stvn_f under :body scope produces exactly 9 symbols
     * (1 root contract at Depth 0 and 8 instantiated elements/variants at Depth 1).
     */
    public void testCalcifiedAuditBodyScopeProducesExactNineSymbols() throws Exception {
        var path = java.nio.file.Path.of("temp/examples/package_and_use/calcified_audit.stvn_f");
        var content = java.nio.file.Files.readString(path);
        var file = myFixture.configureByText("calcified_audit.stvn_f", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.BODY);

        assertEquals("Must contain exactly 9 symbols (1 root at Depth 0 + 8 instantiated elements/variants at Depth 1)", 9, entries.size());

        // Depth 0: Root contract shape
        var audit = findEntryByName(entries, ":AuditRecord");
        assertNotNull("Root contract :AuditRecord must exist at Depth 0", audit);
        assertEquals(0, audit.useDepth());

        // Depth 1: 7 correlated tuple positional values + 1 variant keyword
        var mic = findEntryByName(entries, ":MicCode");
        assertNotNull(":MicCode must exist at Depth 1", mic);
        assertEquals(1, mic.useDepth());

        var seq = findEntryByName(entries, ":SequenceNumber");
        assertNotNull(":SequenceNumber must exist at Depth 1", seq);
        assertEquals(1, seq.useDepth());

        var order = findEntryByName(entries, ":OrderId");
        assertNotNull(":OrderId must exist at Depth 1", order);
        assertEquals(1, order.useDepth());

        var agency = findEntryByName(entries, ":AgencySide");
        assertNotNull(":AgencySide must exist at Depth 1", agency);
        assertEquals(1, agency.useDepth());

        var qty = findEntryByName(entries, ":ShareQuantity");
        assertNotNull(":ShareQuantity must exist at Depth 1", qty);
        assertEquals(1, qty.useDepth());

        var price = findEntryByName(entries, ":ExecutionPrice");
        assertNotNull(":ExecutionPrice must exist at Depth 1", price);
        assertEquals(1, price.useDepth());

        var dt = findEntryByName(entries, ":org/stvnadore/prelude/DateTimeAudited");
        assertNotNull(":DateTimeAudited must exist at Depth 1", dt);
        assertEquals(1, dt.useDepth());

        var buy = findEntryByName(entries, "#BUY");
        assertNotNull("Variant keyword #BUY must exist at Depth 1", buy);
        assertEquals(1, buy.useDepth());
    }

    private static com.intellij.psi.@Nullable PsiElement findTokenByText(com.intellij.psi.PsiFile file, String text) {
        var textRange = file.getText().indexOf(text);
        if (textRange >= 0) {
            return file.findElementAt(textRange);
        }
        return null;
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
