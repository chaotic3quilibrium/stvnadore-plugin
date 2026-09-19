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
              :type :Either( :Int32 :Uint32 )
              :body 4<caret>2
            }
            """
        );

        myFixture.doHighlighting();

        var intentions = myFixture.filterAvailableIntentions("Wrap with");
        assertEquals("Expected exactly two wrapping intention actions for :Either", 2, intentions.size());

        // Value-Oriented Programming (VOP) Right-First Invariant: R precedes L
        assertEquals("Wrap with #Right (-> :Uint32)", intentions.get(0).getText());
        assertEquals("Wrap with #Left (-> :Int32)", intentions.get(1).getText());

        // Apply primary quick-fix (#Right)
        myFixture.launchAction(intentions.get(0));

        myFixture.checkResult(
            """
            {
              :type :Either( :Int32 :Uint32 )
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
              :type :Either( :Int32 :Uint32 )
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
              :type :Either( :Int32 :Uint32 )
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
              :type :Union( :Int32 :Uint32 :String )
              :body 10<caret>0
            }
            """
        );

        myFixture.doHighlighting();

        var intentions = myFixture.filterAvailableIntentions("Wrap with");
        // Must match branch 1 (:Int32) and branch 2 (:Uint32), excluding branch 3 (:String)
        assertEquals("Expected exactly two compatible branch actions for integer literal", 2, intentions.size());
        assertEquals("Wrap with #1 (-> :Int32)", intentions.get(0).getText());
        assertEquals("Wrap with #2 (-> :Uint32)", intentions.get(1).getText());

        myFixture.launchAction(intentions.get(0));

        myFixture.checkResult(
            """
            {
              :type :Union( :Int32 :Uint32 :String )
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
                :Pair :Tuple( :Int32 :Either( :Int32 :String ) )
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
        assertEquals("Complete with #Left (-> :Int32)", intentions.get(1).getText());

        // Apply primary quick-fix (#Right)
        myFixture.launchAction(intentions.get(0));

        myFixture.checkResult(
            """
            {
              :defs {
                :Pair :Tuple( :Int32 :Either( :Int32 :String ) )
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

    public void testBareHashUnionCompleteBranchActions() {
        myFixture.configureByText(
            "bare_hash_union.stvn",
            """
            {
              :type :Union( :Int32 :Float64 :String )
              :body #<caret>
            }
            """
        );

        myFixture.doHighlighting();

        var intentions = myFixture.filterAvailableIntentions("Complete with");
        assertEquals("Expected three completion intention actions for 3-branch union on bare '#'", 3, intentions.size());
        assertEquals("Complete with #1 (-> :Int32)", intentions.get(0).getText());
        assertEquals("Complete with #2 (-> :Float64)", intentions.get(1).getText());
        assertEquals("Complete with #3 (-> :String)", intentions.get(2).getText());

        myFixture.launchAction(intentions.get(0));

        myFixture.checkResult(
            """
            {
              :type :Union( :Int32 :Float64 :String )
              :body #1
            }
            """
        );
    }
}

