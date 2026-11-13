package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

import java.util.List;

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

        var actions = myFixture.filterAvailableIntentions("Replace with '#BUY'");
        assertFalse("Expected \"Replace with '#BUY'\" quick-fix on #SHORT", actions.isEmpty());
        var sellActions = myFixture.filterAvailableIntentions("Replace with '#SELL'");
        assertFalse("Expected \"Replace with '#SELL'\" quick-fix on #SHORT", sellActions.isEmpty());
        assertTrue(
            "Generic exemplar label must be suppressed when per-variant fixes are offered",
            myFixture.filterAvailableIntentions("Replace with exemplar value '#BUY'").isEmpty()
        );
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
            bodyErrors.get(0).getDescription().contains("E012-ERR_CONSTRAINT_VIOLATION"));
    }

    public void testSafeguard2And3NominalEnumBrandingConstExemptionAndPerVariantQuickFixes() {
        var text = """
                {
                  :defs {
                    :SettlementState :Enum [ #CREATED #AUTHORIZED #CAPTURED #SETTLED #VOIDED #REFUNDED ]
                    :FinalizableState { #filterIncl [ #AUTHORIZED #CAPTURED #SETTLED ] } :SettlementState
                    :TrapConflictFilter { #filterIncl [ #CREATED #AUTHORIZED ] #filterExcl [ #AUTHORIZED ] } :SettlementState
                    #DEFAULT_FINAL_STATE :FinalizableState #AUTHORIZED
                    #TRAP_EXCLUDED_CONST :FinalizableState #CREATED
                    #TRAP_UNDECLARED_CONST :SettlementState #NOT_A_STATE
                    #BAD_BODY_CONST :SettlementState #CREATED
                    #UNBRANDED_CREATED_CONST :Enum [ #CREATED #AUTHORIZED #CAPTURED #SETTLED #VOIDED #REFUNDED ] #CREATED
                  }
                  :type :Tuple(:SettlementState :FinalizableState :FinalizableState :TrapConflictFilter :FinalizableState :FinalizableState)
                  :body ( #NOT_A_STATE #VOIDED #DEFAULT_FINAL_STATE #REFUNDED #BAD_BODY_CONST #UNBRANDED_CREATED_CONST )
                }
                """;
        myFixture.configureByText("safeguard2_3_enum_branding.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();

        // #DEFAULT_FINAL_STATE in :body must be exempt from StvnEnumSubsetInspection (zero error on slot 3)
        int defaultConstInBody = text.indexOf("#DEFAULT_FINAL_STATE", text.indexOf(":body"));
        assertTrue(
            "#DEFAULT_FINAL_STATE #CONST reference in :body must receive zero errors",
            errors.stream().noneMatch(h -> h.getStartOffset() == defaultConstInBody)
        );

        // Safeguard 2: nominal #BAD_BODY_CONST surfaces E027, and unbranded #UNBRANDED_CREATED_CONST surfaces E012
        // ("not permitted in enum subset") in StvnExternalAnnotator without being suppressed!
        int badConstInBody = text.indexOf("#BAD_BODY_CONST", text.indexOf(":body"));
        var badConstErrors = errors.stream()
            .filter(h -> h.getStartOffset() == badConstInBody)
            .toList();
        assertEquals(1, badConstErrors.size());
        assertTrue(badConstErrors.get(0).getDescription().contains("[stvn-core:E027-ERR_INCOMPATIBLE_NOMINAL_TYPE]"));

        int unbrandedConstInBody = text.indexOf("#UNBRANDED_CREATED_CONST", text.indexOf(":body"));
        var unbrandedConstErrors = errors.stream()
            .filter(h -> h.getStartOffset() == unbrandedConstInBody)
            .toList();
        assertEquals(
            "Safeguard 2: out-of-subset #UNBRANDED_CREATED_CONST reference in :body must surface [stvn-core:E028-ERR_TYPE_MISMATCH], found: " + unbrandedConstErrors,
            1,
            unbrandedConstErrors.size()
        );
        assertTrue(unbrandedConstErrors.get(0).getDescription().contains("[stvn-core:E028-ERR_TYPE_MISMATCH]"));

        // Direct Safeguard 2 verification on both E012 ("not permitted in enum subset") and E028 ("Invalid enum value for '")
        var syntheticE012Const = new org.stvnadore.core.StvnDiagnostic(
            "Invalid enum value: variant '#BAD_BODY_CONST' is not permitted in enum subset ':FinalizableState'; allowed variants: [#AUTHORIZED, #CAPTURED, #SETTLED]",
            org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity.ERROR,
            1, 1, badConstInBody, badConstInBody + "#BAD_BODY_CONST".length(),
            null,
            java.util.Optional.of("ERR_CONSTRAINT_VIOLATION")
        );
        var syntheticE028Const = new org.stvnadore.core.StvnDiagnostic(
            "Invalid enum value for ':FinalizableState': variant '#BAD_BODY_CONST' is not declared; allowed variants: [#AUTHORIZED, #CAPTURED, #SETTLED]",
            org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity.ERROR,
            1, 1, badConstInBody, badConstInBody + "#BAD_BODY_CONST".length(),
            null,
            java.util.Optional.of("ERR_TYPE_MISMATCH")
        );
        assertFalse(
            "Safeguard 2: E012 on declared #CONST reference must NOT be suppressed in StvnDiagnosticAnnotatorMapping",
            StvnDiagnosticAnnotatorMapping.isDiagnosticGovernedAndActive(myFixture.getFile(), syntheticE012Const)
        );
        assertFalse(
            "Safeguard 2: E028 on declared #CONST reference must NOT be suppressed in StvnDiagnosticAnnotatorMapping",
            StvnDiagnosticAnnotatorMapping.isDiagnosticGovernedAndActive(myFixture.getFile(), syntheticE028Const)
        );

        // Safeguard 3a: ConstantDefinition RHS (#TRAP_EXCLUDED_CONST :FinalizableState #CREATED) offers ordered per-variant fixes
        int trapExcludedOffset = text.indexOf("#CREATED", text.indexOf("#TRAP_EXCLUDED_CONST"));
        myFixture.getEditor().getCaretModel().moveToOffset(trapExcludedOffset + 2);
        List<String> excludedConstFixes = myFixture.getAvailableIntentions().stream()
            .map(IntentionAction::getText)
            .filter(t -> t.startsWith("Replace with "))
            .toList();
        assertEquals(
            "Safeguard 3: #TRAP_EXCLUDED_CONST must offer ordered FinalizableState variants with zero exemplar duplicates",
            List.of("Replace with '#AUTHORIZED'", "Replace with '#CAPTURED'", "Replace with '#SETTLED'"),
            excludedConstFixes
        );

        // Safeguard 3b: ConstantDefinition RHS (#TRAP_UNDECLARED_CONST :SettlementState #NOT_A_STATE) offers all 6 root variants
        // (computeFixesForValue preserves root declaration order; Alt+Enter places Priority.HIGH '#CREATED' first, then Priority.NORMAL peers)
        int trapUndeclaredOffset = text.indexOf("#NOT_A_STATE", text.indexOf("#TRAP_UNDECLARED_CONST"));
        var undeclaredConstVal = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            myFixture.getFile().findElementAt(trapUndeclaredOffset + 2),
            org.stvnadore.psi.Value.class,
            false
        );
        assertNotNull(undeclaredConstVal);
        assertEquals(
            "Safeguard 3: computeFixesForValue must preserve exact root declaration order on ConstantDefinition RHS",
            List.of(
                "Replace with '#CREATED'",
                "Replace with '#AUTHORIZED'",
                "Replace with '#CAPTURED'",
                "Replace with '#SETTLED'",
                "Replace with '#VOIDED'",
                "Replace with '#REFUNDED'"
            ),
            org.stvnadore.plugin.validation.quickfix.StvnPayloadValueQuickFixProvider.computeFixesForValue(
                undeclaredConstVal,
                org.stvnadore.core.validation.DiagnosticBag.ERR_TYPE_MISMATCH,
                "Invalid enum value for ':SettlementState'",
                java.time.Clock.systemDefaultZone()
            ).stream().map(com.intellij.codeInspection.LocalQuickFix::getName).toList()
        );

        myFixture.getEditor().getCaretModel().moveToOffset(trapUndeclaredOffset + 2);
        List<String> undeclaredConstFixes = myFixture.getAvailableIntentions().stream()
            .map(IntentionAction::getText)
            .filter(t -> t.startsWith("Replace with "))
            .toList();
        assertEquals(
            "Safeguard 3: Alt+Enter on #TRAP_UNDECLARED_CONST must surface '#CREATED' (Priority.HIGH) first and all 6 SettlementState variants",
            List.of(
                "Replace with '#CREATED'",
                "Replace with '#AUTHORIZED'",
                "Replace with '#CAPTURED'",
                "Replace with '#REFUNDED'",
                "Replace with '#SETTLED'",
                "Replace with '#VOIDED'"
            ),
            undeclaredConstFixes
        );

        // Safeguard 3c: :body slot #VOIDED offers ordered FinalizableState variants both when local inspection is enabled AND disabled
        int bodyVoidedOffset = text.indexOf("#VOIDED", text.indexOf(":body"));
        myFixture.getEditor().getCaretModel().moveToOffset(bodyVoidedOffset + 2);
        assertEquals(
            List.of("Replace with '#AUTHORIZED'", "Replace with '#CAPTURED'", "Replace with '#SETTLED'"),
            myFixture.getAvailableIntentions().stream()
                .map(IntentionAction::getText)
                .filter(t -> t.startsWith("Replace with "))
                .toList()
        );

        myFixture.disableInspections(new StvnEnumSubsetInspection());
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(bodyVoidedOffset + 2);
        assertEquals(
            "When StvnEnumSubsetInspection is disabled, StvnExternalAnnotator must attach identical per-variant fixes",
            List.of("Replace with '#AUTHORIZED'", "Replace with '#CAPTURED'", "Replace with '#SETTLED'"),
            myFixture.getAvailableIntentions().stream()
                .map(IntentionAction::getText)
                .filter(t -> t.startsWith("Replace with "))
                .toList()
        );
    }
}
