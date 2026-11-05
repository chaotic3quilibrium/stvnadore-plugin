package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform integration test suite validating {@link StvnMetadataOrderInspection}
 * and {@link StvnReorderMetadataFacetsQuickFix}.
 * Verifies strict enforcement of the canonical 5-Tier Decimal Hierarchy (§ 6.1).
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
            .filter(h -> h.getDescription() != null && h.getDescription().contains("violates canonical 5-tier order"))
            .toList();
        assertFalse("Expected error on out-of-order facet '#unsigned'", errors.isEmpty());

        int offset = text.indexOf("#unsigned");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 5-tier order");
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
            .filter(h -> h.getDescription() != null && h.getDescription().contains("violates canonical 5-tier order"))
            .toList();
        assertEquals("Expected 1 error for interval lower bound after upper bound", 1, errors.size());

        int offset = text.indexOf("#minIncl");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 5-tier order");
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
                  #preserveIndent #TRUE
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
            .filter(h -> h.getDescription() != null && h.getDescription().contains("violates canonical 5-tier order"))
            .toList();
        assertFalse("Expected out-of-order errors on multi-tier disorder", errors.isEmpty());

        int offset = text.indexOf("#preserveIndent");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 5-tier order");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        // 5-Tier: Tier 1 (#minSize 4 #maxSize 16) < Tier 2 (#equatable #TRUE) < Tier 4 (#preserveIndent #TRUE #regex "^[A-Z]+$")
        assertTrue("Expected canonical 5-tier ordering in result:\n" + result,
            result.contains("#minSize 4 #maxSize 16 #equatable #TRUE #preserveIndent #TRUE #regex \"^[A-Z]+$\""));
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
            .filter(h -> h.getDescription() != null && h.getDescription().contains("violates canonical 5-tier order"))
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
            .filter(h -> h.getDescription() != null && h.getDescription().contains("violates canonical 5-tier order"))
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
            .filter(h -> h.getDescription() != null && h.getDescription().contains("violates canonical 5-tier order"))
            .toList();
        assertFalse("Expected out-of-order errors on disordered Port definition", errors.isEmpty());

        int offset = text.indexOf("#size");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 5-tier order");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected canonical order { #unsigned #size 16 #minIncl 1 #maxExcl 65536 } in reordered text:\n" + result,
            result.contains("{ #unsigned #size 16 #minIncl 1 #maxExcl 65536 }"));
    }

    public void testSizeBeforeEquatableIsValidFiveTierOrder() {
        var text = """
            {
              :defs {
                :ValidOrderFloat { #size 32 #equatable #TRUE } :Float
              }
              :type :ValidOrderFloat
              :body 1.0
            }
            """;
        myFixture.configureByText("valid_size_equatable.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("canonical 5-tier order"))
            .toList();
        assertTrue("Canonical 5-tier order { #size 32 #equatable #TRUE } must produce zero errors", errors.isEmpty());
    }

    public void testOutOfOrderMetaEntriesWarningAndQuickFixWithComments() {
        var text = """
            {
              :meta {
                #kind #DEFS_INCLUDE   // Kind comment
                #name "gauntlet_v2"   // Name comment
                #domain "core_test"   // Domain comment
              }
              :defs {
                :Port :Int
              }
            }
            """;
        myFixture.configureByText("gauntlet_v2.core_test.stvn_di", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("canonical order (#name, #domain, #kind)"))
            .toList();
        assertEquals("Expected 2 warnings on out-of-order :meta entries (#name and #domain after #kind)", 2, warnings.size());

        int offset = text.indexOf("#name");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder ':meta' entries to canonical order (#name, #domain, #kind)");
        assertFalse("Expected ':meta' reorder quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        var expectedMeta = """
              :meta {
                #name "gauntlet_v2"   // Name comment
                #domain "core_test"   // Domain comment
                #kind #DEFS_INCLUDE   // Kind comment
              }
            """.stripTrailing();
        assertTrue("Expected canonical :meta order with preserved trailing comments:\n" + result,
            result.contains(expectedMeta));
    }

    public void testMultilineMetadataMapReorderingPreservesTrailingComments() {
        var text = """
            {
              :defs {
                :LedgerChannel {
                  #minIncl 1024   // Tier 1.4
                  #size 16        // Tier 1.3
                  #unsigned       // Tier 1.1
                } :Int
              }
              :type :LedgerChannel
              :body 2048
            }
            """;
        myFixture.configureByText("multiline_facets_comments.stvn", text);
        myFixture.doHighlighting();

        int offset = text.indexOf("#size");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 5-tier order");
        assertFalse("Expected facet reorder quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        var expectedMap = """
                :LedgerChannel {
                  #unsigned       // Tier 1.1
                  #size 16        // Tier 1.3
                  #minIncl 1024   // Tier 1.4
                } :Int
            """.stripTrailing();
        assertTrue("Expected multi-line facet order with preserved trailing comments:\n" + result,
            result.contains(expectedMap));
    }
}
