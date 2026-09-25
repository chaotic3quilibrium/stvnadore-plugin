package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform integration test suite validating {@link StvnMetadataOrderInspection}
 * and {@link StvnReorderMetadataFacetsQuickFix}.
 * Verifies strict enforcement of the canonical 7-tier Semantic Category Order.
 */
@NullMarked
public final class StvnMetadataOrderInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new InspectionProfileEntry[]{new StvnMetadataOrderInspection()});
    }

    public void testDetectOutOfOrderFacetsAndApplyQuickFix() {
        var text = """
            {
              :defs {
                :BadOrder { #size 16 #unsigned } :Int
              }
              :type :BadOrder
              :body 42
            }
            """;
        myFixture.configureByText("out_of_order.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("out of canonical 7-tier order"))
            .toList();
        assertFalse("Expected error on out-of-order facet '#unsigned'", errors.isEmpty());

        int offset = text.indexOf("#unsigned");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 7-tier order");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected '#unsigned #size 16' in reordered text:\n" + result,
            result.contains("{ #unsigned #size 16 }"));
    }

    public void testIntervalLowerBeforeUpperOrdering() {
        var text = """
            {
              :defs {
                :IntervalDisorder { #maxExcl 100 #minIncl 0 } :Int
              }
              :type :IntervalDisorder
              :body 50
            }
            """;
        myFixture.configureByText("interval_disorder.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("out of canonical 7-tier order"))
            .toList();
        assertEquals("Expected 1 error for interval lower bound after upper bound", 1, errors.size());

        int offset = text.indexOf("#minIncl");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 7-tier order");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected '#minIncl 0 #maxExcl 100' in reordered text:\n" + result,
            result.contains("{ #minIncl 0 #maxExcl 100 }"));
    }

    public void testMultiTierCanonicalReordering() {
        var text = """
            {
              :defs {
                :ComplexType {
                  #regex "^[A-Z]+$"
                  #maxSize 16
                  #minSize 4
                  #preserveIndent
                  #equatable #TRUE
                } :String
              }
              :type :ComplexType
              :body "TEST"
            }
            """;
        myFixture.configureByText("multi_tier.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("out of canonical 7-tier order"))
            .toList();
        assertFalse("Expected out-of-order errors on multi-tier disorder", errors.isEmpty());

        int offset = text.indexOf("#preserveIndent");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 7-tier order");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        // Tier 1 (#preserveIndent #equatable #TRUE) < Tier 3 (#minSize 4 #maxSize 16) < Tier 5 (#regex "^[A-Z]+$")
        assertTrue("Expected canonical 7-tier ordering in result:\n" + result,
            result.contains("#preserveIndent #equatable #TRUE #minSize 4 #maxSize 16 #regex \"^[A-Z]+$\""));
    }

    public void testCompliantOrderProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :CanonicalTemporal { #unsigned #s #size 16 #minIncl 1 #maxExcl 100 } :TimeEpoch
              }
              :type :CanonicalTemporal
              :body 50
            }
            """;
        myFixture.configureByText("canonical_temporal.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("out of canonical 7-tier order"))
            .toList();
        assertTrue("Canonical ordering must produce 0 order violations", errors.isEmpty());
    }

    public void testCanonicalPortDeclarationOrder() {
        var text = """
            {
              :defs {
                :Port { #unsigned #size 16 #minIncl 1 #maxExcl 65536 } :Int
              }
              :type :Port
              :body 8080
            }
            """;
        myFixture.configureByText("canonical_port.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("out of canonical 7-tier order"))
            .toList();
        assertTrue("Canonical order { #unsigned #size 16 #minIncl 1 #maxExcl 65536 } must produce 0 order violations", errors.isEmpty());
    }

    public void testDisorderedPortQuickFixReordersToCanonicalSevenTier() {
        var text = """
            {
              :defs {
                :DisorderedPort { #maxExcl 65536 #size 16 #unsigned #minIncl 1 } :Int
              }
              :type :DisorderedPort
              :body 8080
            }
            """;
        myFixture.configureByText("disordered_port.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("out of canonical 7-tier order"))
            .toList();
        assertFalse("Expected out-of-order errors on disordered Port definition", errors.isEmpty());

        int offset = text.indexOf("#size");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 7-tier order");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected canonical order { #unsigned #size 16 #minIncl 1 #maxExcl 65536 } in reordered text:\n" + result,
            result.contains("{ #unsigned #size 16 #minIncl 1 #maxExcl 65536 }"));
    }
}
