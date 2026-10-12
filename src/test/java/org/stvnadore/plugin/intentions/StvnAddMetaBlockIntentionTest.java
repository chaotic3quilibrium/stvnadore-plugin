package org.stvnadore.plugin.intentions;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform unit tests for StvnAddMetaBlockIntention context action.
 */
@NullMarked
public final class StvnAddMetaBlockIntentionTest extends BasePlatformTestCase {

    /**
     * Verifies that Add root :meta header is available on primary .stvn files lacking :meta.
     */
    public void testIntentionAvailableOnPrimaryStvnWithoutMeta() {
        var content = """
            {
              :type :Int
              :body <caret>42
            }
            """;
        myFixture.configureByText("player_data.stvn", content);
        var actions = myFixture.filterAvailableIntentions("Add root :meta header");
        assertFalse("Expected 'Add root :meta header' intention on .stvn document", actions.isEmpty());
    }

    /**
     * Verifies that Add root :meta header is suppressed when :meta already exists.
     */
    public void testIntentionSuppressedWhenMetaAlreadyPresent() {
        var content = """
            {
              :meta {
                #name "player_data"
                #kind #BODY
              }
              :type :Int
              :body <caret>42
            }
            """;
        myFixture.configureByText("player_data.stvn", content);
        var actions = myFixture.filterAvailableIntentions("Add root :meta header");
        assertTrue("Expected 'Add root :meta header' intention suppressed when :meta present", actions.isEmpty());
    }

    /**
     * Verifies that invoking the intention inserts canonical header with slot 0 and slot 1.
     */
    public void testIntentionInsertsCanonicalHeaderWithSlot0AndSlot1() {
        var content = """
            {
              :type :Int
              :body <caret>42
            }
            """;
        myFixture.configureByText("account.billing.stvn", content);
        var actions = myFixture.filterAvailableIntentions("Add root :meta header");
        assertFalse(actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var text = myFixture.getFile().getText();
        assertTrue("Expected #name \"account\"", text.contains("#name \"account\""));
        assertTrue("Expected #domain \"billing\"", text.contains("#domain \"billing\""));
        assertTrue("Expected #kind #BODY", text.contains("#kind #BODY"));
    }

    /**
     * Verifies that invoking the intention on single-stem file omits #domain.
     */
    public void testIntentionOmitsDomainOnSingleStemFile() {
        var content = "{ :body <caret>1 }";
        myFixture.configureByText("simple.stvn", content);
        var actions = myFixture.filterAvailableIntentions("Add root :meta header");
        assertFalse(actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var text = myFixture.getFile().getText();
        assertTrue("Expected #name \"simple\"", text.contains("#name \"simple\""));
        assertFalse("Expected #domain omitted", text.contains("#domain"));
        assertTrue("Expected #kind #BODY", text.contains("#kind #BODY"));
    }
}
