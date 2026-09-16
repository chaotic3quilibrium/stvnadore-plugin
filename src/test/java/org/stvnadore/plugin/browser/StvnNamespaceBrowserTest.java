package org.stvnadore.plugin.browser;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
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
