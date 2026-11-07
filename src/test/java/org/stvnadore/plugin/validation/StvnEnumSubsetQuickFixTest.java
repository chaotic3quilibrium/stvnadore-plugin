package org.stvnadore.plugin.validation;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform integration test for {@link StvnReorderEnumFilterVariantsQuickFix}.
 * Verifies in-place AST rewrites for out-of-order filter facet variants.
 */
@NullMarked
public final class StvnEnumSubsetQuickFixTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new StvnEnumSubsetInspection());
    }

    public void testReorderEnumFilterVariantsQuickFix() {
        var text = """
                {
                  :defs {
                    :Priority :Enum [ #LOW #MEDIUM #HIGH #CRITICAL ]
                    :InvalidOrder { #filterIncl [ #HIGH #MEDIUM ] } :Priority
                  }
                  :type :InvalidOrder
                  :body #HIGH
                }
                """;
        myFixture.configureByText("root_ordering_fix.stvn", text);
        var caretOffset = text.indexOf("[ #HIGH #MEDIUM ]") + 3;
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Sort variants to match root enum declaration order");
        assertFalse("Expected 'Sort variants to match root enum declaration order' quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
                {
                  :defs {
                    :Priority :Enum [ #LOW #MEDIUM #HIGH #CRITICAL ]
                    :InvalidOrder { #filterIncl [ #MEDIUM #HIGH ] } :Priority
                  }
                  :type :InvalidOrder
                  :body #HIGH
                }
                """);
    }

    public void testTransitiveReorderQuickFix() {
        var text = """
                {
                  :defs {
                    :Status :Enum [ #Pending #Active #Suspended #Deleted ]
                    :WorkingStatus { #filterExcl [ #Deleted ] } :Status
                    :ImmediateStatus { #filterIncl [ #Suspended #Active ] } :WorkingStatus
                  }
                  :type :ImmediateStatus
                  :body #Active
                }
                """;
        myFixture.configureByText("transitive_ordering_fix.stvn", text);
        var caretOffset = text.indexOf("[ #Suspended #Active ]") + 3;
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Sort variants to match root enum declaration order");
        assertFalse("Expected quick-fix in transitive chain", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
                {
                  :defs {
                    :Status :Enum [ #Pending #Active #Suspended #Deleted ]
                    :WorkingStatus { #filterExcl [ #Deleted ] } :Status
                    :ImmediateStatus { #filterIncl [ #Active #Suspended ] } :WorkingStatus
                  }
                  :type :ImmediateStatus
                  :body #Active
                }
                """);
    }

    public void testEnumSubsetExcludedPayloadVariantOffersReplaceWithExemplarQuickFix() {
        var text = """
                {
                  :defs {
                    :TradeSide :Enum[#BUY #SELL #SHORT #COVER]
                    :LongOnlySide { #filterIncl [#BUY #SELL] } :TradeSide
                  }
                  :type :LongOnlySide
                  :body #SH<caret>ORT
                }
                """;
        myFixture.configureByText("enum_subset_exemplar_fix.stvn", text);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Replace with exemplar value '#BUY'");
        assertFalse("Expected \"Replace with exemplar value '#BUY'\" quick-fix on #SHORT", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        assertTrue("Expected #SHORT to be replaced with #BUY",
            myFixture.getEditor().getDocument().getText().contains(":body #BUY"));
    }

    public void testDuplicateFilterVariantEmitsCoreAlignedErrorAndOffersRemoveDuplicateQuickFix() {
        var text = """
                {
                  :defs {
                    :Priority :Enum [ #LOW #MEDIUM #HIGH #CRITICAL ]
                    :DuplicateFilterTrap { #filterIncl [ #LOW #HIGH #<caret>LOW ] } :Priority
                  }
                  :type :DuplicateFilterTrap
                  :body #LOW
                }
                """;
        myFixture.configureByText("duplicate_filter_variant_fix.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == com.intellij.lang.annotation.HighlightSeverity.ERROR)
            .toList();

        assertEquals("Expected strictly 1 error on [ #LOW #HIGH #LOW ] (duplicate #LOW only, no false ordering error): " + errors,
            1, errors.size());
        assertEquals(
            "Enum subset violation (:DuplicateFilterTrap): duplicate variant '#LOW' in filter list [stvn-plugin:E008-ERR_FILTER_DUPLICATE_VARIANT]",
            errors.get(0).getDescription()
        );

        var actions = myFixture.filterAvailableIntentions("Remove duplicate variant '#LOW'");
        assertFalse("Expected \"Remove duplicate variant '#LOW'\" quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
                {
                  :defs {
                    :Priority :Enum [ #LOW #MEDIUM #HIGH #CRITICAL ]
                    :DuplicateFilterTrap { #filterIncl [ #LOW #HIGH ] } :Priority
                  }
                  :type :DuplicateFilterTrap
                  :body #LOW
                }
                """);
    }
}
