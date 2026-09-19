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
