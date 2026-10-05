package org.stvnadore.plugin.validation;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform test suite verifying predictive context-aware quick-fix completion
 * on bare trait facets without secondary redundant facet warnings.
 */
@NullMarked
public final class StvnBareTraitCompletionQuickFixTest extends BasePlatformTestCase {

    public void testPredictiveFixOffersTrueWhenParentResolvesFalse() {
        var source = """
            {
              :defs {
                :BlockStringA { #preserveIndent #FALSE } :String
                :BlockStringB { #preserveIndent } :BlockStringA
              }
              :type :BlockStringB
              :body "content"
            }
            """;
        myFixture.configureByText("trait_repair_true.stvn", source);
        var caretOffset = source.indexOf("#preserveIndent }");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var expectedActionTitle = "Complete trait with '#TRUE' (overrides parent :BlockStringA)";
        var actions = myFixture.filterAvailableIntentions(expectedActionTitle);
        assertFalse("Predictive fix offering '#TRUE' must be available", actions.isEmpty());

        var redundantActions = myFixture.filterAvailableIntentions("Complete trait with '#FALSE'");
        assertTrue("Fix offering redundant '#FALSE' must NOT be available", redundantActions.isEmpty());

        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :BlockStringA { #preserveIndent #FALSE } :String
                :BlockStringB { #preserveIndent #TRUE } :BlockStringA
              }
              :type :BlockStringB
              :body "content"
            }
            """);

        // Confirm zero redundant facet override warnings remain post-repair
        var highlights = myFixture.doHighlighting();
        var redundantErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertTrue("Zero redundant facet override warnings must exist after repair", redundantErrors.isEmpty());
    }

    public void testPredictiveFixOffersFalseWhenParentResolvesTrue() {
        var source = """
            {
              :defs {
                :BlockStringA { #preserveIndent #TRUE } :String
                :BlockStringB { #preserveIndent } :BlockStringA
              }
              :type :BlockStringB
              :body "content"
            }
            """;
        myFixture.configureByText("trait_repair_false.stvn", source);
        var caretOffset = source.indexOf("#preserveIndent }");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var expectedActionTitle = "Complete trait with '#FALSE' (overrides parent :BlockStringA)";
        var actions = myFixture.filterAvailableIntentions(expectedActionTitle);
        assertFalse("Predictive fix offering '#FALSE' must be available", actions.isEmpty());

        var redundantActions = myFixture.filterAvailableIntentions("Complete trait with '#TRUE'");
        assertTrue("Fix offering redundant '#TRUE' must NOT be available", redundantActions.isEmpty());

        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :BlockStringA { #preserveIndent #TRUE } :String
                :BlockStringB { #preserveIndent #FALSE } :BlockStringA
              }
              :type :BlockStringB
              :body "content"
            }
            """);

        var highlights = myFixture.doHighlighting();
        var redundantErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertTrue("Zero redundant facet override warnings must exist after repair", redundantErrors.isEmpty());
    }

    public void testPredictiveFixOffersTrueWhenParentIsBaseString() {
        var source = """
            {
              :defs {
                :BlockStringB { #preserveIndent } :String
              }
              :type :BlockStringB
              :body "content"
            }
            """;
        myFixture.configureByText("trait_repair_base.stvn", source);
        var caretOffset = source.indexOf("#preserveIndent }");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var expectedActionTitle = "Complete trait with '#TRUE' (overrides parent :String)";
        var actions = myFixture.filterAvailableIntentions(expectedActionTitle);
        assertFalse("Predictive fix offering '#TRUE' for :String must be available", actions.isEmpty());

        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :BlockStringB { #preserveIndent #TRUE } :String
              }
              :type :BlockStringB
              :body "content"
            }
            """);
    }

    public void testPredictiveFixOffersDualWhenParentIsUnresolvable() {
        var source = """
            {
              :defs {
                :BlockStringB { #preserveIndent } :UnresolvableParentType
              }
              :type :BlockStringB
              :body "content"
            }
            """;
        myFixture.configureByText("trait_repair_fallback.stvn", source);
        var caretOffset = source.indexOf("#preserveIndent }");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var trueActions = myFixture.filterAvailableIntentions("Complete trait with '#TRUE'");
        var falseActions = myFixture.filterAvailableIntentions("Complete trait with '#FALSE'");
        assertFalse("Dual fallback '#TRUE' must be available", trueActions.isEmpty());
        assertFalse("Dual fallback '#FALSE' must be available", falseActions.isEmpty());
    }
}
