package org.stvnadore.plugin.browser;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.table.JBTable;
import java.awt.datatransfer.DataFlavor;
import java.util.Arrays;
import java.util.List;
import javax.swing.JMenuItem;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.psi.StvnTypes;

/**
 * Automated platform test suite verifying the interactive namespace dependency browser,
 * gutter navigation line markers, symbol collection, depth calculations, sorting, and navigation.
 */
@NullMarked
public final class StvnNamespaceBrowserTest extends BasePlatformTestCase {

    private static final String MODULAR_SCHEMA_CONTENT = """
        {
          :meta {
            #name "browser_modular_schema"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :AccountStatus :Enum [ #ACTIVE #SUSPENDED #CLOSED ]
            :BaseEntity    :Tuple( :Int :String )
            :package :com/example/finance {
              :Transaction :Tuple( :Int :Float :String )
            }
          }
        }
        """;

    private static final String BROWSER_DOCUMENT_CONTENT = """
        {
          :meta {
            #name "browser_document"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [
              "browser_modular_schema.stvn_di" {
                :BaseEntity    :LocalEntity
                :AccountStatus :StatusAlias
              }
            ]
            :use [ :com/example/finance { :Transaction :LocalTx } ]
            :use [ :org/stvnadore/prelude { :Port :Port } ]
            :AppConfig   :Tuple( :String :Boolean )
            :DeepAlias   :LocalEntity
            #DefaultPort :Port 8080
          }
          :type :Tuple( :LocalTx :StatusAlias )
          :body (
            ( 1001 49.99 "Subscription" )
            #ACTIVE
          )
        }
        """;

    /**
     * Verifies that line marker providers attach gutter icons to :defs, :type, and :body headers.
     */
    public void testGutterIconsAttachedToSectionHeaders() {
        myFixture.addFileToProject("browser_modular_schema.stvn_di", MODULAR_SCHEMA_CONTENT);
        var file = myFixture.configureByText("browser_document.stvn_i", BROWSER_DOCUMENT_CONTENT);
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
        myFixture.addFileToProject("browser_modular_schema.stvn_di", MODULAR_SCHEMA_CONTENT);
        var file = myFixture.configureByText("browser_document.stvn_i", BROWSER_DOCUMENT_CONTENT);

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
        var rfcPath = java.nio.file.Path.of("temp/examples/json/rfc8259_json_substrate.json_schema.stvn_d");
        var content = java.nio.file.Files.readString(rfcPath);
        var file = myFixture.configureByText("rfc8259_json_substrate.stvn_d", content);
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
                :package :com/example/sample {
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
        assertNotNull(findEntryByName(entries, ":com/example/sample/PackagedItem"));
        assertNotNull(findEntryByName(entries, "#com/example/sample/PackagedPort"));
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
                :package :com/example/sample {
                  :PackagedItem :Int64
                }
                :use [ :com/example/sample { #strip } ]
              }
            }
            """;
        var file = myFixture.configureByText("use_strip.stvn", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);

        assertEquals("Must contain 2 symbols (1 canonical FQNI + 1 stripped alias)", 2, entries.size());
        var canonical = findEntryByName(entries, ":com/example/sample/PackagedItem");
        assertNotNull(canonical);
        assertEquals(0, canonical.useDepth());

        var alias = findEntryByName(entries, ":PackagedItem");
        assertNotNull(alias);
        assertEquals(1, alias.useDepth());
        assertEquals(":com/example/sample", alias.source());
    }

    /**
     * Verifies symbol filtering under :type scope strictly matches referenced schema symbols.
     */
    public void testTypeScopeFiltering() {
        myFixture.addFileToProject("browser_modular_schema.stvn_di", MODULAR_SCHEMA_CONTENT);
        var file = myFixture.configureByText("browser_document.stvn_i", BROWSER_DOCUMENT_CONTENT);

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
        myFixture.addFileToProject("browser_modular_schema.stvn_di", MODULAR_SCHEMA_CONTENT);
        var file = myFixture.configureByText("browser_document.stvn_i", BROWSER_DOCUMENT_CONTENT);

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
                :package :com/example/finance {
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
        assertEquals(":com/example/finance", localTx.source());
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
        myFixture.addFileToProject("browser_modular_schema.stvn_di", MODULAR_SCHEMA_CONTENT);
        var file = myFixture.configureByText("browser_document.stvn_i", BROWSER_DOCUMENT_CONTENT);

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
        myFixture.addFileToProject("browser_modular_schema.stvn_di", MODULAR_SCHEMA_CONTENT);
        var file = myFixture.configureByText("browser_document.stvn_i", BROWSER_DOCUMENT_CONTENT);

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
                :package :com/example/finance {
                  :LocalTx :Tuple( :Int :Float )
                }
                :use [ :com/example/finance { #strip } ]
                :A :String
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
                :package :org/example/finance {
                  :LocalTx :Tuple( :Int64 :Float64 )
                }
                :use [ :org/example/finance { #strip } ]
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
     * Verifies that calcified_audit.stvn under :defs scope produces exactly 12 symbols
     * (9 canonical declarations at Depth 0 and 3 root-level stripped aliases at Depth 1),
     * completely isolating package-local :use directives and generating zero bare phantom duplicates.
     */
    public void testCalcifiedAuditDefsScopeProducesExactTenSymbolsWithoutPackageLocalLeakage() throws Exception {
        var path = java.nio.file.Path.of("temp/examples/package_and_use/calcified_audit.stvn");
        var content = java.nio.file.Files.readString(path);
        var file = myFixture.configureByText("calcified_audit.stvn", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);

        assertEquals("Must contain exactly 12 symbols (9 canonical declarations + 3 stripped aliases)", 12, entries.size());

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

        var auditTsFqni = findEntryByName(entries, ":com/nyse/events/AuditTimestamp");
        assertNotNull(auditTsFqni);
        assertEquals(0, auditTsFqni.useDepth());
        assertEquals(":com/nyse/events", auditTsFqni.source());

        var auditFqni = findEntryByName(entries, ":com/nyse/events/AuditRecord");
        assertNotNull(auditFqni);
        assertEquals(0, auditFqni.useDepth());
        assertEquals(":com/nyse/events", auditFqni.source());

        // Root-level stripped aliases at Depth 1
        var agencyBare = findEntryByName(entries, ":AgencySide");
        assertNotNull("Root-level stripped alias :AgencySide must exist", agencyBare);
        assertEquals(1, agencyBare.useDepth());
        assertEquals(":com/nyse/events", agencyBare.source());

        var auditTsBare = findEntryByName(entries, ":AuditTimestamp");
        assertNotNull("Root-level stripped alias :AuditTimestamp must exist", auditTsBare);
        assertEquals(1, auditTsBare.useDepth());
        assertEquals(":com/nyse/events", auditTsBare.source());

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
     * Verifies that calcified_audit.stvn under :type scope produces contiguous depths (0, 1, 2)
     * without phantom hop skips, stamping :AuditRecord at Depth 0, direct tuple members at Depth 1,
     * and transitive sub-dependencies at Depth 2.
     */
    public void testCalcifiedAuditTypeScopeProducesContiguousDepthsWithoutPhantomHops() throws Exception {
        var path = java.nio.file.Path.of("temp/examples/package_and_use/calcified_audit.stvn");
        var content = java.nio.file.Files.readString(path);
        var file = myFixture.configureByText("calcified_audit.stvn", content);
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

        var dt = findEntryByName(entries, ":AuditTimestamp");
        assertNotNull(":AuditTimestamp must exist at Depth 1", dt);
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
     * Verifies that calcified_audit.stvn under :body scope produces exactly 9 symbols
     * (1 root contract at Depth 0 and 8 instantiated elements/variants at Depth 1).
     */
    public void testCalcifiedAuditBodyScopeProducesExactNineSymbols() throws Exception {
        var path = java.nio.file.Path.of("temp/examples/package_and_use/calcified_audit.stvn");
        var content = java.nio.file.Files.readString(path);
        var file = myFixture.configureByText("calcified_audit.stvn", content);
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

        var dt = findEntryByName(entries, ":AuditTimestamp");
        assertNotNull(":AuditTimestamp must exist at Depth 1", dt);
        assertEquals(1, dt.useDepth());

        var buy = findEntryByName(entries, "#BUY");
        assertNotNull("Variant keyword #BUY must exist at Depth 1", buy);
        assertEquals(1, buy.useDepth());
    }

    /**
     * Verifies that json_example.stvn under :defs scope collects exactly 8 stripped symbols at Use Depth = 1.
     */
    public void testJsonExampleDefsScopeCollectsEightStrippedSymbolsAtDepthOne() throws Exception {
        var rfcPath = java.nio.file.Path.of("temp/examples/json/rfc8259_json_substrate.json_schema.stvn_d");
        var rfcContent = java.nio.file.Files.readString(rfcPath);
        myFixture.addFileToProject("rfc8259_json_substrate.json_schema.stvn_d", rfcContent);

        var jsonPath = java.nio.file.Path.of("temp/examples/json/json_example.json.stvn_i");
        var jsonContent = java.nio.file.Files.readString(jsonPath);
        var file = myFixture.configureByText("json_example.json.stvn_i", jsonContent);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);

        assertEquals("Must collect exactly 8 stripped symbols in :defs scope", 8, entries.size());
        String[] expectedSymbols = {
            ":JsonNull", ":JsonBoolean", ":JsonNumberInteger", ":JsonNumberFloat",
            ":JsonString", ":JsonObject", ":JsonArray", ":JsonValue"
        };
        for (var expected : expectedSymbols) {
            var entry = findEntryByName(entries, expected);
            assertNotNull("Symbol " + expected + " must exist in :defs scope", entry);
            assertEquals(1, entry.useDepth());
            assertEquals(":org/ietf/rfc8259/json", entry.source());
        }
    }

    /**
     * Verifies that json_example.stvn under :type scope correctly renders all 8 symbols
     * (1 root union at Depth 0 + 7 constituent branches at Depth 1).
     */
    public void testJsonExampleTypeScopeMaintainsEightSymbols() throws Exception {
        var rfcPath = java.nio.file.Path.of("temp/examples/json/rfc8259_json_substrate.json_schema.stvn_d");
        var rfcContent = java.nio.file.Files.readString(rfcPath);
        myFixture.addFileToProject("rfc8259_json_substrate.json_schema.stvn_d", rfcContent);

        var jsonPath = java.nio.file.Path.of("temp/examples/json/json_example.json.stvn_i");
        var jsonContent = java.nio.file.Files.readString(jsonPath);
        var file = myFixture.configureByText("json_example.json.stvn_i", jsonContent);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.TYPE);

        assertEquals("Must contain exactly 8 symbols (1 root at Depth 0 + 7 branches at Depth 1)", 8, entries.size());
        var root = findEntryByName(entries, ":JsonValue");
        assertNotNull(root);
        assertEquals(0, root.useDepth());

        String[] branches = {
            ":JsonNull", ":JsonBoolean", ":JsonNumberInteger", ":JsonNumberFloat",
            ":JsonString", ":JsonObject", ":JsonArray"
        };
        for (var branch : branches) {
            var entry = findEntryByName(entries, branch);
            assertNotNull("Branch " + branch + " must exist in :type scope", entry);
            assertEquals(1, entry.useDepth());
        }
    }

    /**
     * Verifies that json_example.stvn under :body scope captures all instantiated nominal types
     * and variants across the JSON map payload.
     */
    public void testJsonExampleBodyScopeCapturesAllInferredNominalTypesAndVariants() throws Exception {
        var rfcPath = java.nio.file.Path.of("temp/examples/json/rfc8259_json_substrate.json_schema.stvn_d");
        var rfcContent = java.nio.file.Files.readString(rfcPath);
        myFixture.addFileToProject("rfc8259_json_substrate.json_schema.stvn_d", rfcContent);

        var jsonPath = java.nio.file.Path.of("temp/examples/json/json_example.json.stvn_i");
        var jsonContent = java.nio.file.Files.readString(jsonPath);
        var file = myFixture.configureByText("json_example.json.stvn_i", jsonContent);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.BODY);

        // Assert all instantiated nominal types are captured
        assertNotNull("Root contract :JsonValue must exist", findEntryByName(entries, ":JsonValue"));
        assertNotNull("Instantiated :JsonObject must exist", findEntryByName(entries, ":JsonObject"));
        assertNotNull("Instantiated :JsonString must exist", findEntryByName(entries, ":JsonString"));
        assertNotNull("Instantiated :JsonBoolean must exist", findEntryByName(entries, ":JsonBoolean"));
        assertNotNull("Instantiated :JsonNull must exist", findEntryByName(entries, ":JsonNull"));
        assertNotNull("Instantiated :JsonArray must exist", findEntryByName(entries, ":JsonArray"));
        assertNotNull("Instantiated :JsonNumberInteger must exist", findEntryByName(entries, ":JsonNumberInteger"));
        assertNotNull("Instantiated :JsonNumberFloat must exist", findEntryByName(entries, ":JsonNumberFloat"));

        // Assert variant keywords are captured
        assertNotNull("Variant keyword #NULL must exist", findEntryByName(entries, "#NULL"));
        assertNotNull("Variant keyword #TRUE must exist", findEntryByName(entries, "#TRUE"));
    }

    public void testNamespaceCollectorRetainsNominalTypesWithDigits() {
        var file = myFixture.configureByText(
            "nominal_digits.stvn",
            """
            {
              :defs {
                :Int32Custom :Int
                :Float64Custom :Float
                :String128Custom :String
              }
              :type :Int32Custom
              :body 100
            }
            """
        );

        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);
        assertFalse(entries.isEmpty());

        assertNotNull("Must collect :Int32Custom", findEntryByName(entries, ":Int32Custom"));
        assertNotNull("Must collect :Float64Custom", findEntryByName(entries, ":Float64Custom"));
        assertNotNull("Must collect :String128Custom", findEntryByName(entries, ":String128Custom"));
    }

    /**
     * Verifies that collectPreludeSymbols dynamically loads prelude symbols from stvnadore-core.
     */
    public void testCollectPreludeSymbols() {
        var preludeEntries = StvnNamespaceSymbolCollector.collectPreludeSymbols(getProject());
        assertFalse("Prelude entries must not be empty", preludeEntries.isEmpty());
        assertEquals("Prelude must contain exactly 29 symbols (21 types + 8 constants)", 29, preludeEntries.size());
        assertNotNull("Must contain :org/stvnadore/prelude/IPv4", findEntryByName(preludeEntries, ":org/stvnadore/prelude/IPv4"));
        assertNotNull("Must contain :org/stvnadore/prelude/Port", findEntryByName(preludeEntries, ":org/stvnadore/prelude/Port"));
        assertNotNull("Must contain :org/stvnadore/prelude/UuidLowerCase", findEntryByName(preludeEntries, ":org/stvnadore/prelude/UuidLowerCase"));
        assertNotNull("Must contain :org/stvnadore/prelude/UuidMixedCase", findEntryByName(preludeEntries, ":org/stvnadore/prelude/UuidMixedCase"));
        assertNotNull("Must contain :org/stvnadore/prelude/Sha256LowerCase", findEntryByName(preludeEntries, ":org/stvnadore/prelude/Sha256LowerCase"));
        assertNotNull("Must contain :org/stvnadore/prelude/Sha256MixedCase", findEntryByName(preludeEntries, ":org/stvnadore/prelude/Sha256MixedCase"));
        assertNotNull("Must contain :org/stvnadore/prelude/EmailLowerCase", findEntryByName(preludeEntries, ":org/stvnadore/prelude/EmailLowerCase"));
        assertNotNull("Must contain :org/stvnadore/prelude/EmailMixedCase", findEntryByName(preludeEntries, ":org/stvnadore/prelude/EmailMixedCase"));

        // Verify bare phantom names do not exist in prelude entries
        assertNull("Bare phantom :IPv4 must not exist in prelude entries", findEntryByName(preludeEntries, ":IPv4"));
        assertNull("Bare phantom :Port must not exist in prelude entries", findEntryByName(preludeEntries, ":Port"));
        assertNull("Bare phantom #POSIX_ID_REGEX must not exist in prelude entries", findEntryByName(preludeEntries, "#POSIX_ID_REGEX"));

        // Verify harvested prelude constants under canonical FQNIs
        assertNotNull("Must contain #org/stvnadore/prelude/UUID_LOWER_CASE_REGEX", findEntryByName(preludeEntries, "#org/stvnadore/prelude/UUID_LOWER_CASE_REGEX"));
        assertNotNull("Must contain #org/stvnadore/prelude/UUID_MIXED_CASE_REGEX", findEntryByName(preludeEntries, "#org/stvnadore/prelude/UUID_MIXED_CASE_REGEX"));
        assertNotNull("Must contain #org/stvnadore/prelude/SHA256_LOWER_CASE_REGEX", findEntryByName(preludeEntries, "#org/stvnadore/prelude/SHA256_LOWER_CASE_REGEX"));
        assertNotNull("Must contain #org/stvnadore/prelude/SHA256_MIXED_CASE_REGEX", findEntryByName(preludeEntries, "#org/stvnadore/prelude/SHA256_MIXED_CASE_REGEX"));
        assertNotNull("Must contain #org/stvnadore/prelude/EMAIL_LOWER_CASE_REGEX", findEntryByName(preludeEntries, "#org/stvnadore/prelude/EMAIL_LOWER_CASE_REGEX"));
        assertNotNull("Must contain #org/stvnadore/prelude/EMAIL_MIXED_CASE_REGEX", findEntryByName(preludeEntries, "#org/stvnadore/prelude/EMAIL_MIXED_CASE_REGEX"));
        assertNotNull("Must contain #org/stvnadore/prelude/POSIX_ID_REGEX", findEntryByName(preludeEntries, "#org/stvnadore/prelude/POSIX_ID_REGEX"));
        assertNotNull("Must contain #org/stvnadore/prelude/POLYGLOT_CODE_ID_REGEX", findEntryByName(preludeEntries, "#org/stvnadore/prelude/POLYGLOT_CODE_ID_REGEX"));

        var posixConst = findEntryByName(preludeEntries, "#org/stvnadore/prelude/POSIX_ID_REGEX");
        assertNotNull(posixConst);
        assertTrue("Constant entry must be marked prelude", posixConst.isPrelude());
        assertEquals(":org/stvnadore/prelude", posixConst.source());
        assertEquals(":String", posixConst.typeStructure());
        assertEquals(0, posixConst.useDepth());

        var ipv4Entry = findEntryByName(preludeEntries, ":org/stvnadore/prelude/IPv4");
        assertNotNull(ipv4Entry);
        assertTrue("Entry must be marked prelude", ipv4Entry.isPrelude());
        assertEquals(":org/stvnadore/prelude", ipv4Entry.source());

        var posixId64 = findEntryByName(preludeEntries, ":org/stvnadore/prelude/meta/StvnPosixId64");
        assertNotNull("Must contain :org/stvnadore/prelude/meta/StvnPosixId64", posixId64);
        assertTrue("Entry must be marked prelude", posixId64.isPrelude());
        assertEquals(":org/stvnadore/prelude/meta", posixId64.source());

        var docKind = findEntryByName(preludeEntries, ":org/stvnadore/prelude/meta/StvnDocumentKind");
        assertNotNull("Must contain :org/stvnadore/prelude/meta/StvnDocumentKind", docKind);
        assertEquals(":org/stvnadore/prelude/meta", docKind.source());
        assertEquals(":Enum [ #BODY #DEFS #BODY_INCLUDE #DEFS_INCLUDE ]", docKind.typeStructure());

        var docMeta = findEntryByName(preludeEntries, ":org/stvnadore/prelude/meta/StvnDocumentMeta");
        assertNotNull("Must contain :org/stvnadore/prelude/meta/StvnDocumentMeta", docMeta);
        assertEquals(":org/stvnadore/prelude/meta", docMeta.source());

        var catMeta = findEntryByName(preludeEntries, ":org/stvnadore/prelude/meta/StvnCatalogMeta");
        assertNotNull("Must contain :org/stvnadore/prelude/meta/StvnCatalogMeta", catMeta);
        assertEquals(":org/stvnadore/prelude/meta", catMeta.source());
    }

    /**
     * Verifies that when a local document declares :use [ :org/stvnadore/prelude { #strip } ],
     * the namespace browser table correctly contains both the Depth 1 bare aliases and the
     * Depth 0 canonical FQNI prelude symbols when Show Standard Library Prelude is active.
     */
    public void testDocumentWithPreludeStripDisplaysBothBareAliasesAndCanonicalFqnis() {
        var content = """
            {
              :defs {
                :use [ :org/stvnadore/prelude { #strip } ]
              }
            }
            """;
        var file = myFixture.configureByText("prelude_strip.stvn", content);
        var contentUi = StvnNamespaceBrowserPopup.createBrowserContent(getProject(), file, StvnNamespaceScope.DEFS);
        assertNotNull(contentUi);

        // Initially without prelude checkbox, contains only stripped aliases at Depth 1 (25 symbols)
        var initialEntries = contentUi.tableModel().getItems();
        var initialBarePort = findEntryByName(initialEntries, ":Port");
        assertNotNull("Bare alias :Port must exist at Depth 1", initialBarePort);
        assertEquals(1, initialBarePort.useDepth());
        assertEquals(":org/stvnadore/prelude", initialBarePort.source());
        assertEquals(":org/stvnadore/prelude/Port", initialBarePort.typeStructure());
        assertFalse(initialBarePort.isPrelude());

        var initialPosixRegex = findEntryByName(initialEntries, "#POSIX_ID_REGEX");
        assertNotNull("Bare alias #POSIX_ID_REGEX must exist at Depth 1", initialPosixRegex);
        assertEquals(1, initialPosixRegex.useDepth());
        assertEquals(":org/stvnadore/prelude", initialPosixRegex.source());
        assertEquals("#org/stvnadore/prelude/POSIX_ID_REGEX", initialPosixRegex.typeStructure());
        assertFalse(initialPosixRegex.isPrelude());

        assertNull("Canonical FQNI :org/stvnadore/prelude/Port must NOT exist before checkbox toggle",
            findEntryByName(initialEntries, ":org/stvnadore/prelude/Port"));

        // Toggle checkbox on
        contentUi.showPreludeCheckBox().doClick();
        var withPreludeEntries = contentUi.tableModel().getItems();

        // Both Depth 1 bare alias and Depth 0 canonical FQNI must now coexist harmoniously
        var barePort = findEntryByName(withPreludeEntries, ":Port");
        assertNotNull("Bare alias :Port must remain present at Depth 1", barePort);
        assertEquals(1, barePort.useDepth());
        assertFalse(barePort.isPrelude());

        var fqniPort = findEntryByName(withPreludeEntries, ":org/stvnadore/prelude/Port");
        assertNotNull("Canonical FQNI :org/stvnadore/prelude/Port must be present at Depth 0", fqniPort);
        assertEquals(0, fqniPort.useDepth());
        assertEquals(":org/stvnadore/prelude", fqniPort.source());
        assertTrue(fqniPort.isPrelude());

        var bareRegex = findEntryByName(withPreludeEntries, "#POSIX_ID_REGEX");
        assertNotNull("Bare alias #POSIX_ID_REGEX must remain present at Depth 1", bareRegex);
        assertEquals(1, bareRegex.useDepth());
        assertFalse(bareRegex.isPrelude());

        var fqniRegex = findEntryByName(withPreludeEntries, "#org/stvnadore/prelude/POSIX_ID_REGEX");
        assertNotNull("Canonical FQNI #org/stvnadore/prelude/POSIX_ID_REGEX must be present at Depth 0", fqniRegex);
        assertEquals(0, fqniRegex.useDepth());
        assertEquals(":org/stvnadore/prelude", fqniRegex.source());
        assertTrue(fqniRegex.isPrelude());

        var metaDocKind = findEntryByName(withPreludeEntries, ":org/stvnadore/prelude/meta/StvnDocumentKind");
        assertNotNull("Canonical FQNI :org/stvnadore/prelude/meta/StvnDocumentKind must be present at Depth 0", metaDocKind);
        assertEquals(0, metaDocKind.useDepth());
        assertEquals(":org/stvnadore/prelude/meta", metaDocKind.source());
        assertTrue(metaDocKind.isPrelude());
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

    /**
     * Verifies that unresolvable, broken, or undefined type references inside composite schemas
     * produce zero phantom entries in the Namespace Browser table.
     */
    public void testDocumentWithUndefinedTypeReferencesProducesZeroPhantomEntries() {
        var content = """
            {
              :defs {
                :Values :Tuple (
                  :TimeEpochNs
                  :TimeEpochUs
                  :org/stvnadore/prelude/TimeEpochNs
                  :org/stvnadore/prelude/DateTime
                  {#offset} :org/stvnadore/prelude/DateTime
                  {#ns} :org/stvnadore/prelude/TimeEpoch
                )
              }
              :type :Values
              :body ( 1 2 3 "2026-10-03" "2026-10-03" 1789241400 )
            }
            """;
        var file = myFixture.configureByText("undefined_references.stvn", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.TYPE);

        assertNotNull("Resolved root symbol (:Values) must be present", findEntryByName(entries, ":Values"));
        assertNull("Undefined :TimeEpochNs must not produce phantom entry", findEntryByName(entries, ":TimeEpochNs"));
        assertNull("Undefined :TimeEpochUs must not produce phantom entry", findEntryByName(entries, ":TimeEpochUs"));
        assertNull("Undefined :org/stvnadore/prelude/TimeEpochNs must not produce phantom entry", findEntryByName(entries, ":org/stvnadore/prelude/TimeEpochNs"));
        assertNull("Undefined :org/stvnadore/prelude/DateTime must not produce phantom entry", findEntryByName(entries, ":org/stvnadore/prelude/DateTime"));
        assertNull("Undefined :org/stvnadore/prelude/TimeEpoch must not produce phantom entry", findEntryByName(entries, ":org/stvnadore/prelude/TimeEpoch"));
        assertEquals("Only legitimate resolved symbols must be collected", 1, entries.size());
    }

    /**
     * Verifies that documents including stvn_ir_substrate.stvn_ir.stvn_d collect normalized
     * :Ir prefixed type names with zero un-prefixed duplicates.
     */
    public void testIrSubstrateNormalizedPrefixSymbolCollection() throws Exception {
        var substratePath = java.nio.file.Path.of("src/test/resources/shared-fixtures/syntax/valid/modules/stvn_ir_substrate.stvn_ir.stvn_d");
        var substrateContent = java.nio.file.Files.readString(substratePath);
        myFixture.addFileToProject("stvn_ir_substrate.stvn_ir.stvn_d", substrateContent);

        var content = """
            {
              :meta {
                #name "ir_test"
                #domain "stvn_ir"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "stvn_ir_substrate.stvn_ir.stvn_d" { #strip } ]
              }
              :type :IrSnapshotDocument
              :body ( {} #1 #TRUE )
            }
            """;
        var file = myFixture.configureByText("ir_test.stvn_ir.stvn_i", content);
        var entries = StvnNamespaceSymbolCollector.collectSymbols(file, StvnNamespaceScope.DEFS);

        assertNotNull("Normalized :IrTraitConstraints must exist", findEntryByName(entries, ":IrTraitConstraints"));
        assertNotNull("Normalized :IrRegisteredSchema must exist", findEntryByName(entries, ":IrRegisteredSchema"));
        assertNotNull("Normalized :IrTypeRegistry must exist", findEntryByName(entries, ":IrTypeRegistry"));
        assertNotNull("Normalized :IrSnapshotDocument must exist", findEntryByName(entries, ":IrSnapshotDocument"));
        assertNull("Un-prefixed :TraitConstraints must not exist", findEntryByName(entries, ":TraitConstraints"));
        assertNull("Un-prefixed :RegisteredSchema must not exist", findEntryByName(entries, ":RegisteredSchema"));
        assertNull("Un-prefixed :TypeRegistry must not exist", findEntryByName(entries, ":TypeRegistry"));
    }

    /**
     * Verifies that collectSymbols executes safely when invoked from an unprivileged thread
     * without a pre-existing ReadAction across all supported namespace scopes.
     */
    public void testCollectSymbolsThreadSafetyWithoutOuterReadAction() throws Exception {
        myFixture.addFileToProject("browser_modular_schema.stvn_di", MODULAR_SCHEMA_CONTENT);
        var file = myFixture.configureByText("browser_document.stvn_i", BROWSER_DOCUMENT_CONTENT);

        for (var scope : StvnNamespaceScope.values()) {
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                assertFalse("Worker thread must not hold read access initially",
                    com.intellij.openapi.application.ApplicationManager.getApplication().isReadAccessAllowed());
                return StvnNamespaceSymbolCollector.collectSymbols(file, scope);
            });
            var entries = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(entries);
            assertFalse("Entries must not be empty for scope: " + scope, entries.isEmpty());
        }
    }

    /**
     * Verifies that collectPreludeSymbols executes safely from an unprivileged thread
     * without a pre-existing ReadAction.
     */
    public void testCollectPreludeSymbolsThreadSafetyWithoutOuterReadAction() throws Exception {
        var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            assertFalse("Worker thread must not hold read access initially",
                com.intellij.openapi.application.ApplicationManager.getApplication().isReadAccessAllowed());
            return StvnNamespaceSymbolCollector.collectPreludeSymbols(getProject());
        });
        var entries = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(entries);
        assertFalse("Prelude entries must not be empty", entries.isEmpty());
    }

    /**
     * Verifies that toggling the prelude checkbox in the Namespace Browser popup
     * re-filters and updates the table model on the EDT without throwing read-action threading assertions.
     */
    public void testPopupPreludeCheckboxToggleWithoutOuterReadAction() {
        myFixture.addFileToProject("browser_modular_schema.stvn_di", MODULAR_SCHEMA_CONTENT);
        var file = myFixture.configureByText("browser_document.stvn_i", BROWSER_DOCUMENT_CONTENT);

        var content = StvnNamespaceBrowserPopup.createBrowserContent(getProject(), file, StvnNamespaceScope.DEFS);
        assertNotNull(content);
        var initialCount = content.tableModel().getItems().size();

        // Simulate user clicking checkbox to include prelude symbols
        content.showPreludeCheckBox().doClick();
        var withPreludeCount = content.tableModel().getItems().size();
        assertTrue("Enabling prelude must increase symbol count", withPreludeCount > initialCount);
        assertTrue(content.headerLabel().getText().contains(withPreludeCount + " symbols"));

        // Simulate user clicking checkbox again to exclude prelude symbols
        content.showPreludeCheckBox().doClick();
        var revertedCount = content.tableModel().getItems().size();
        assertEquals("Disabling prelude must revert symbol count", initialCount, revertedCount);
        assertTrue(content.headerLabel().getText().contains(revertedCount + " symbols"));
    }

    public void testInsertUseReferenceMergesIntoExistingAliasBlock() {
        var content = """
            {
              :defs {
                :use [ :org/stvnadore/prelude { :Port :Port } ]
              }
            }
            """;
        var file = myFixture.configureByText("merge_test.stvn", content);
        var entry = new StvnNamespaceSymbolEntry(
            "#POSIX_ID_REGEX",
            ":org/stvnadore/prelude",
            ":String",
            0,
            null,
            true,
            0,
            StvnNamespaceScope.DEFS
        );

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            StvnNamespaceBrowserPopup.insertUseReferenceInEditor(getProject(), file, entry);
        });

        var text = file.getText();
        assertTrue("Must merge #POSIX_ID_REGEX into existing alias block:\n" + text,
            text.contains(":use [ :org/stvnadore/prelude { :Port :Port #POSIX_ID_REGEX #POSIX_ID_REGEX } ]"));
        assertFalse("Must not create secondary :use statement:\n" + text,
            text.contains(":use [ :org/stvnadore/prelude { #POSIX_ID_REGEX #POSIX_ID_REGEX } ]\n    :use [ :org/stvnadore/prelude"));
    }

    public void testInsertUseReferenceUpgradesBareUseStatement() {
        var content = """
            {
              :defs {
                :use [ :org/stvnadore/prelude ]
              }
            }
            """;
        var file = myFixture.configureByText("bare_upgrade_test.stvn", content);
        var entry = new StvnNamespaceSymbolEntry(
            ":AsciiIdentifier",
            ":org/stvnadore/prelude",
            ":String",
            0,
            null,
            true,
            0,
            StvnNamespaceScope.DEFS
        );

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            StvnNamespaceBrowserPopup.insertUseReferenceInEditor(getProject(), file, entry);
        });

        var text = file.getText();
        assertTrue("Must append alias block to bare :use statement:\n" + text,
            text.contains(":use [ :org/stvnadore/prelude { :AsciiIdentifier :AsciiIdentifier } ]"));
    }

    public void testInsertUseReferenceNoOpWhenStripPresent() {
        var content = """
            {
              :defs {
                :use [ :org/stvnadore/prelude { #strip } ]
              }
            }
            """;
        var file = myFixture.configureByText("strip_noop_test.stvn", content);
        var entry = new StvnNamespaceSymbolEntry(
            "#POSIX_ID_REGEX",
            ":org/stvnadore/prelude",
            ":String",
            0,
            null,
            true,
            0,
            StvnNamespaceScope.DEFS
        );

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            StvnNamespaceBrowserPopup.insertUseReferenceInEditor(getProject(), file, entry);
        });

        var text = file.getText();
        assertEquals("File text must remain unmodified when #strip is present", content, text);
    }

    public void testInsertUseReferenceNoOpWhenAliasAlreadyPresent() {
        var content = """
            {
              :defs {
                :use [ :org/stvnadore/prelude { :Port :Port } ]
              }
            }
            """;
        var file = myFixture.configureByText("duplicate_noop_test.stvn", content);
        var entry = new StvnNamespaceSymbolEntry(
            ":Port",
            ":org/stvnadore/prelude",
            ":Int",
            0,
            null,
            true,
            0,
            StvnNamespaceScope.DEFS
        );

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            StvnNamespaceBrowserPopup.insertUseReferenceInEditor(getProject(), file, entry);
        });

        var text = file.getText();
        assertEquals("File text must remain unmodified when alias is already present", content, text);
    }

    /**
     * Verifies that context menu construction for constant rows suppresses
     * "Brand as New Nominal Type in :defs" and includes "Copy FQNI".
     */
    public void testContextMenuForConstantOmitsBrandAction() {
        var file = myFixture.configureByText("dummy.stvn", "{\n  :defs {}\n}\n");
        var constantEntry = new StvnNamespaceSymbolEntry(
            "#org/stvnadore/prelude/UUID_LOWER_CASE_REGEX",
            ":org/stvnadore/prelude",
            ":String",
            0,
            null,
            true,
            0,
            StvnNamespaceScope.DEFS
        );

        var menu = StvnNamespaceBrowserPopup.buildContextMenu(getProject(), file, constantEntry);
        var items = Arrays.stream(menu.getComponents())
            .filter(JMenuItem.class::isInstance)
            .map(c -> ((JMenuItem) c).getText())
            .toList();

        assertEquals("Constant menu must contain exactly 2 actions", 2, items.size());
        assertTrue("Must contain 'Insert Reference (:use)'", items.contains("Insert Reference (:use)"));
        assertTrue("Must contain 'Copy FQNI'", items.contains("Copy FQNI"));
        assertFalse("Must suppress 'Brand as New Nominal Type in :defs' for constants",
            items.contains("Brand as New Nominal Type in :defs"));
    }

    /**
     * Verifies that context menu construction for nominal type rows includes
     * "Brand as New Nominal Type in :defs" along with "Copy FQNI".
     */
    public void testContextMenuForNominalTypeIncludesBrandAction() {
        var file = myFixture.configureByText("dummy_type.stvn", "{\n  :defs {}\n}\n");
        var typeEntry = new StvnNamespaceSymbolEntry(
            ":org/stvnadore/prelude/Port",
            ":org/stvnadore/prelude",
            ":Int",
            0,
            null,
            true,
            0,
            StvnNamespaceScope.DEFS
        );

        var menu = StvnNamespaceBrowserPopup.buildContextMenu(getProject(), file, typeEntry);
        var items = Arrays.stream(menu.getComponents())
            .filter(JMenuItem.class::isInstance)
            .map(c -> ((JMenuItem) c).getText())
            .toList();

        assertEquals("Nominal type menu must contain exactly 3 actions", 3, items.size());
        assertTrue("Must contain 'Insert Reference (:use)'", items.contains("Insert Reference (:use)"));
        assertTrue("Must contain 'Copy FQNI'", items.contains("Copy FQNI"));
        assertTrue("Must include 'Brand as New Nominal Type in :defs' for nominal types",
            items.contains("Brand as New Nominal Type in :defs"));
    }

    /**
     * Verifies that invoking Copy FQNI on a constant entry places the exact canonical FQNI
     * string onto the system clipboard.
     */
    public void testCopyFqniToClipboardPlacesCanonicalConstantFqni() throws Exception {
        var entry = new StvnNamespaceSymbolEntry(
            "#org/stvnadore/prelude/UUID_LOWER_CASE_REGEX",
            ":org/stvnadore/prelude",
            ":String",
            0,
            null,
            true,
            0,
            StvnNamespaceScope.DEFS
        );

        StvnNamespaceBrowserPopup.copyFqniToClipboard(getProject(), entry);
        var clipText = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        assertEquals("#org/stvnadore/prelude/UUID_LOWER_CASE_REGEX", clipText);
    }

    /**
     * Verifies that invoking Copy FQNI on a nominal type entry places the exact canonical FQNI
     * string onto the system clipboard.
     */
    public void testCopyFqniToClipboardPlacesCanonicalTypeFqni() throws Exception {
        var entry = new StvnNamespaceSymbolEntry(
            ":org/stvnadore/prelude/Port",
            ":org/stvnadore/prelude",
            ":Int",
            0,
            null,
            true,
            0,
            StvnNamespaceScope.DEFS
        );

        StvnNamespaceBrowserPopup.copyFqniToClipboard(getProject(), entry);
        var clipText = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        assertEquals(":org/stvnadore/prelude/Port", clipText);
    }

    /**
     * Verifies that insertNominalBrandInDefs defensively rejects constant entries without mutating the file.
     */
    public void testInsertNominalBrandInDefsDefensivelyRejectsConstantEntry() {
        var initial = "{\n  :defs {}\n}\n";
        var file = myFixture.configureByText("guard_test.stvn", initial);
        var constantEntry = new StvnNamespaceSymbolEntry(
            "#POSIX_ID_REGEX",
            ":org/stvnadore/prelude",
            ":String",
            0,
            null,
            true,
            0,
            StvnNamespaceScope.DEFS
        );

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            StvnNamespaceBrowserPopup.insertNominalBrandInDefs(getProject(), file, constantEntry);
        });

        assertEquals("File text must remain unmodified when attempting to brand a constant",
            initial, file.getText());
    }

    /**
     * Verifies that both inlined STVN 2.0.0 test documents compile with zero errors via StvnCompiler,
     * guaranteeing specification conformance and preventing regression drift.
     */
    public void testInlinedFixturesCompileWithZeroErrors() throws Exception {
        var schemaResult = org.stvnadore.core.StvnCompiler.compileToResult(
            MODULAR_SCHEMA_CONTENT,
            "browser_modular_schema.stvn_di",
            org.stvnadore.core.StvnParserConfig.DEFAULT
        );
        assertFalse("Modular schema must compile cleanly: " + schemaResult.diagnostics(), schemaResult.hasErrors());

        var tempDir = java.nio.file.Files.createTempDirectory("stvn_browser_test");
        try {
            var schemaPath = tempDir.resolve("browser_modular_schema.stvn_di");
            java.nio.file.Files.writeString(schemaPath, MODULAR_SCHEMA_CONTENT);
            var docPath = tempDir.resolve("browser_document.stvn_i");
            java.nio.file.Files.writeString(docPath, BROWSER_DOCUMENT_CONTENT);

            var docResult = org.stvnadore.core.StvnCompiler.compileToResult(
                BROWSER_DOCUMENT_CONTENT,
                docPath.toString(),
                org.stvnadore.core.StvnParserConfig.DEFAULT
            );
            assertFalse("Modular payload must compile cleanly: " + docResult.diagnostics(), docResult.hasErrors());
        } finally {
            com.intellij.openapi.util.io.FileUtil.delete(tempDir.toFile());
        }
    }
}
