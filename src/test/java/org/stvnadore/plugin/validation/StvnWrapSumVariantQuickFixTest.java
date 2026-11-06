package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform test verifying right-first intention actions and quick-fixes for
 * ambiguous sum type inference errors (ERR_AMBIGUOUS_SUM_INFERENCE).
 */
@NullMarked
public final class StvnWrapSumVariantQuickFixTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/shared-fixtures";
    }

    public void testAmbiguousEitherRightFirstIntentionOrder() {
        myFixture.configureByText(
            "ambiguous_either.stvn",
            """
            {
              :type :Either( :Int :Int )
              :body 4<caret>2
            }
            """
        );

        myFixture.doHighlighting();

        var intentions = myFixture.filterAvailableIntentions("Wrap with");
        assertEquals("Expected exactly two wrapping intention actions for :Either", 2, intentions.size());

        // Value-Oriented Programming (VOP) Right-First Invariant: R precedes L
        assertEquals("Wrap with #Right (-> :Int)", intentions.get(0).getText());
        assertEquals("Wrap with #Left (-> :Int)", intentions.get(1).getText());

        // Apply primary quick-fix (#Right)
        myFixture.launchAction(intentions.get(0));

        myFixture.checkResult(
            """
            {
              :type :Either( :Int :Int )
              :body #Right 42
            }
            """
        );

        var highlights = myFixture.doHighlighting();
        var hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Explicitly tagged payload must eliminate all compile errors", hasErrors);
    }

    public void testAmbiguousEitherWrapLeftExecution() {
        myFixture.configureByText(
            "ambiguous_either_left.stvn",
            """
            {
              :type :Either( :Int :Int )
              :body 4<caret>2
            }
            """
        );

        myFixture.doHighlighting();

        var intentions = myFixture.filterAvailableIntentions("Wrap with #Left");
        assertFalse("Wrap with #Left intention must be available", intentions.isEmpty());

        myFixture.launchAction(intentions.get(0));

        myFixture.checkResult(
            """
            {
              :type :Either( :Int :Int )
              :body #Left 42
            }
            """
        );

        var highlights = myFixture.doHighlighting();
        var hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Explicitly tagged #Left payload must eliminate all compile errors", hasErrors);
    }

    public void testAmbiguousUnionFilteredIntentionActions() {
        myFixture.configureByText(
            "ambiguous_union.stvn",
            """
            {
              :type :Union( :Int :Int :String )
              :body 10<caret>0
            }
            """
        );

        myFixture.doHighlighting();

        var intentions = myFixture.filterAvailableIntentions("Wrap with");
        // Must match branch 1 (:Int) and branch 2 (:Int), excluding branch 3 (:String)
        assertEquals("Expected exactly two compatible branch actions for integer literal", 2, intentions.size());
        assertEquals("Wrap with #1 (-> :Int)", intentions.get(0).getText());
        assertEquals("Wrap with #2 (-> :Int)", intentions.get(1).getText());

        myFixture.launchAction(intentions.get(0));

        myFixture.checkResult(
            """
            {
              :type :Union( :Int :Int :String )
              :body #1 100
            }
            """
        );

        var highlights = myFixture.doHighlighting();
        var hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Explicitly tagged #1 payload must eliminate all compile errors", hasErrors);
    }

    public void testBareHashEitherCompleteRightFirstIntentionOrder() {
        myFixture.configureByText(
            "bare_hash_either.stvn",
            """
            {
              :defs {
                :Pair :Tuple( :Int :Either( :Int :String ) )
              }
              :type :Pair
              :body (
                1
                #<caret>
              )
            }
            """
        );

        myFixture.doHighlighting();

        var intentions = myFixture.filterAvailableIntentions("Complete with");
        assertEquals("Expected exactly two completion intention actions for :Either on bare '#'", 2, intentions.size());

        // Value-Oriented Programming (VOP) Right-First Invariant: R precedes L
        assertEquals("Complete with #Right (-> :String)", intentions.get(0).getText());
        assertEquals("Complete with #Left (-> :Int)", intentions.get(1).getText());

        // Apply primary quick-fix (#Right)
        myFixture.launchAction(intentions.get(0));

        myFixture.checkResult(
            """
            {
              :defs {
                :Pair :Tuple( :Int :Either( :Int :String ) )
              }
              :type :Pair
              :body (
                1
                #Right
              )
            }
            """
        );
    }

    public void testStage7EitherLeftMissingTagRuleEOffersWrapLeftAndRightExemplar() {
        myFixture.configureByText(
            "stage7_either_rule_e.stvn",
            """
            {
              :defs {
                :ErrorCode { #unsigned #size 16 } :Int
                :SuccessMsg { #minSize 1 #maxSize 128 } :String
                :RpcOutcome :Either(:ErrorCode :SuccessMsg)
              }
              :type :RpcOutcome
              :body 4<caret>04
            }
            """
        );

        myFixture.doHighlighting();

        var allFixes = myFixture.getAllQuickFixes();
        var labels = allFixes.stream().map(com.intellij.codeInsight.intention.IntentionAction::getText).toList();
        assertTrue(
            "Expected 'Wrap with #Left (-> :ErrorCode)' and 'Replace with exemplar value' on Rule E violation, found: " + labels,
            labels.contains("Wrap with #Left (-> :ErrorCode)")
                && labels.contains("Replace with exemplar value '#Right \"example\"'")
        );
        assertEquals(
            "Primary quick-fix for E023 must be 'Wrap with #Left (-> :ErrorCode)'",
            "Wrap with #Left (-> :ErrorCode)",
            allFixes.get(0).getText()
        );

        myFixture.launchAction(allFixes.get(0));
        assertTrue(
            "Launching Wrap with #Left must produce ':body #Left 404'",
            myFixture.getEditor().getDocument().getText().contains(":body #Left 404")
        );
    }

    public void testBareHashUnionCompleteBranchActions() {
        myFixture.configureByText(
            "bare_hash_union.stvn",
            """
            {
              :type :Union( :Int :Float :String )
              :body #<caret>
            }
            """
        );

        myFixture.doHighlighting();

        var intentions = myFixture.filterAvailableIntentions("Complete with");
        assertEquals("Expected three completion intention actions for 3-branch union on bare '#'", 3, intentions.size());
        assertEquals("Complete with #1 (-> :Int)", intentions.get(0).getText());
        assertEquals("Complete with #2 (-> :Float)", intentions.get(1).getText());
        assertEquals("Complete with #3 (-> :String)", intentions.get(2).getText());

        myFixture.launchAction(intentions.get(0));

        myFixture.checkResult(
            """
            {
              :type :Union( :Int :Float :String )
              :body #1
            }
            """
        );
    }
}

