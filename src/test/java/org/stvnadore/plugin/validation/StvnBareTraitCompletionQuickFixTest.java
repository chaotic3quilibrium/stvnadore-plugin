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
        myFixture.enableInspections(new StvnMetadataFacetInspection());
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

    public void testBarePreserveIndentEquatableComparablePreserveFollowingSiblingFacetsAndPreview() {
        myFixture.enableInspections(
            new StvnMetadataFacetInspection(),
            new StvnMetadataOrderInspection(),
            new StvnBooleanValidityInspection()
        );
        var source = """
            {
              :defs {
                #PRIMARY_SWITCH :Boolean #TRUE
                :SiblingPreserve { #maxSize 64 #preserveIndent } :String
                :BarePreserveFirst { #preserveIndent #regex "^[a-z]+$" } :String
                :BareEquatableWithMin { #equatable #minIncl 0 } :Float
                :BareComparableWithMin { #comparable #minIncl 0 } :Int
                :ConstBoolValid { #preserveIndent #PRIMARY_SWITCH } :String
                :InvalidIntArg { #preserveIndent 10 } :String
              }
              :type :ConstBoolValid
              :body #PRIMARY_SWITCH
            }
            """;
        var file = myFixture.configureByText("bare_traits_siblings.stvn", source);
        var text = file.getText();
        var highlights = myFixture.doHighlighting();

        // 1. Verify #preserveIndent 10 pins strictly to '10' (MetadataValue), not the entire entry
        int tenOffset = text.indexOf("10 }");
        var intArgError = highlights.stream()
            .filter(h -> h.getSeverity() == com.intellij.lang.annotation.HighlightSeverity.ERROR)
            .filter(h -> h.getStartOffset() == tenOffset && h.getEndOffset() == tenOffset + 2)
            .findFirst()
            .orElse(null);
        assertNotNull("Expected invalid trait argument error pinned strictly to '10': " + highlights, intArgError);

        // 2. Verify #PRIMARY_SWITCH in :body and #preserveIndent #PRIMARY_SWITCH produce zero boolean validity errors
        int bodySwitchOffset = text.lastIndexOf("#PRIMARY_SWITCH");
        boolean bodyBoolError = highlights.stream()
            .filter(h -> h.getSeverity() == com.intellij.lang.annotation.HighlightSeverity.ERROR)
            .anyMatch(h -> h.getStartOffset() == bodySwitchOffset
                && h.getDescription() != null
                && h.getDescription().contains("Invalid boolean literal"));
        assertFalse("Boolean #CONST reference '#PRIMARY_SWITCH' must never trigger StvnBooleanValidityInspection", bodyBoolError);

        // 3. Verify Intention Preview and quick-fix execution on bare '#preserveIndent' before '#regex'
        int barePreserveOffset = text.indexOf("#preserveIndent #regex");
        myFixture.getEditor().getCaretModel().moveToOffset(barePreserveOffset + 2);
        var fixActions = myFixture.filterAvailableIntentions("Complete trait with '#TRUE' (overrides parent :String)");
        assertFalse("Expected CompleteTraitFacetQuickFix on bare #preserveIndent before #regex", fixActions.isEmpty());

        String previewText = myFixture.getIntentionPreviewText(fixActions.get(0));
        assertTrue("Intention Preview must complete '#preserveIndent #TRUE' while preserving '#regex'",
            previewText.contains("{ #preserveIndent #TRUE #regex \"^[a-z]+$\" }"));

        myFixture.launchAction(fixActions.get(0));
        assertTrue("Physical launch must preserve sibling '#regex' facet",
            myFixture.getFile().getText().contains(":BarePreserveFirst { #preserveIndent #TRUE #regex \"^[a-z]+$\" } :String"));

        // 4. Verify CompleteTraitFacetQuickFix on bare '#equatable' and '#comparable' with following '#minIncl 0'
        String updated = myFixture.getFile().getText();
        myFixture.getEditor().getCaretModel().moveToOffset(updated.indexOf("#equatable #minIncl") + 2);
        myFixture.doHighlighting();
        var eqFixes = myFixture.filterAvailableIntentions("Complete trait with '#TRUE' (overrides parent :Float)");
        assertFalse("Expected predictive '#TRUE' fix for bare #equatable on continuous :Float", eqFixes.isEmpty());
        myFixture.launchAction(eqFixes.get(0));
        assertTrue(myFixture.getFile().getText().contains(":BareEquatableWithMin { #equatable #TRUE #minIncl 0 } :Float"));

        String updated2 = myFixture.getFile().getText();
        myFixture.getEditor().getCaretModel().moveToOffset(updated2.indexOf("#comparable #minIncl") + 2);
        myFixture.doHighlighting();
        var cmpFixes = myFixture.filterAvailableIntentions("Complete trait with '#FALSE' (overrides parent :Int)");
        assertFalse("Expected predictive '#FALSE' fix for bare #comparable on :Int", cmpFixes.isEmpty());
        myFixture.launchAction(cmpFixes.get(0));
        assertTrue(myFixture.getFile().getText().contains(":BareComparableWithMin { #comparable #FALSE #minIncl 0 } :Int"));
    }
}
