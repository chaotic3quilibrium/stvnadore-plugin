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

    public void testStage5ConstantWithFilterEmitsSingleDeduplicatedE008AndRemoveInvalidFacetQuickFix() {
        myFixture.enableInspections(new StvnMetadataFacetInspection());
        var text = """
                {
                  :defs {
                    :Color :Enum [ #RED #GREEN #BLUE ]
                    #TRAP_CONST_WITH_FILTER { #filter<caret>Incl [ #RED ] } :Color #RED
                  }
                  :type :Color
                  :body #RED
                }
                """;
        myFixture.configureByText("stage5_constant_with_filter.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == com.intellij.lang.annotation.HighlightSeverity.ERROR)
            .toList();

        assertEquals("Expected strictly 1 deduplicated E008 error on #TRAP_CONST_WITH_FILTER: " + errors,
            1, errors.size());
        assertTrue(errors.get(0).getDescription().contains("E008-ERR_FILTER_ON_CONSTANT"));

        var actions = myFixture.filterAvailableIntentions("Remove invalid facet '#filterIncl'");
        assertFalse("Expected \"Remove invalid facet '#filterIncl'\" quick-fix on constant filter", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
                {
                  :defs {
                    :Color :Enum [ #RED #GREEN #BLUE ]
                    #TRAP_CONST_WITH_FILTER :Color #RED
                  }
                  :type :Color
                  :body #RED
                }
                """);
    }

    public void testStage5PoisonedEnumSubsetSuspendsBodySlots5And6WhileValidatingHealthySlots7And8WithE012() {
        var text = """
                {
                  :defs {
                    :BaseEnum :Enum [ #ALPHA #BETA #GAMMA #DELTA ]
                    :SubsetWithoutAlpha { #filterExcl [ #ALPHA ] } :BaseEnum
                    :TrapReintroduceExcludedVariant { #filterIncl [ #ALPHA #BETA ] } :SubsetWithoutAlpha
                    :TrapTotalExclusionEmptyDomain { #filterExcl [ #ALPHA #BETA #GAMMA #DELTA ] } :BaseEnum
                    :ValidSubsetBetaGamma { #filterIncl [ #BETA #GAMMA ] } :BaseEnum
                  }
                  :type :Tuple(
                    :TrapReintroduceExcludedVariant
                    :TrapTotalExclusionEmptyDomain
                    :ValidSubsetBetaGamma
                    :ValidSubsetBetaGamma
                  )
                  :body (
                    #BETA
                    #ALPHA
                    #BETA
                    #DELTA
                  )
                }
                """;
        myFixture.configureByText("stage5_poisoned_enum_subset_body.stvn", text);
        var highlights = myFixture.doHighlighting();
        var bodyOffset = text.indexOf(":body");
        var bodyErrors = highlights.stream()
            .filter(h -> h.getSeverity() == com.intellij.lang.annotation.HighlightSeverity.ERROR)
            .filter(h -> h.getStartOffset() >= bodyOffset)
            .toList();

        assertEquals("Expected strictly 1 error in :body (on healthy slot #DELTA only, zero cascade on poisoned slots): " + bodyErrors,
            1, bodyErrors.size());
        assertTrue("Expected E012 on #DELTA: " + bodyErrors.get(0).getDescription(),
            bodyErrors.get(0).getDescription().contains("E012-ERR_ENUM_SUBSET_PAYLOAD_MEMBERSHIP_VIOLATION"));
    }
}
