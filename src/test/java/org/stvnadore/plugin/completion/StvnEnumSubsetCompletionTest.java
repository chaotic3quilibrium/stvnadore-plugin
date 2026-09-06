package org.stvnadore.plugin.completion;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Dedicated test suite verifying caret completion behavior in enum subset contexts
 * and during filter facet authoring (#filterIncl / #filterExcl).
 */
@NullMarked
public final class StvnEnumSubsetCompletionTest extends BasePlatformTestCase {

    public void testEnumSubsetPayloadCompletion() {
        myFixture.configureByText(
            "subset_payload.stvn",
            """
            {
              :defs {
                :Environment :Enum [ #LOCAL #DEV #STAGING #CANARY #PROD ]
                :DeployEnv { #filterIncl [ #DEV #STAGING #CANARY ] } :Environment
              }
              :type :DeployEnv
              :body <caret>
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must not be null", elements);

        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must not be null", lookupStrings);

        // Allowed variants must be present
        assertTrue("Must suggest #DEV", lookupStrings.contains("#DEV"));
        assertTrue("Must suggest #STAGING", lookupStrings.contains("#STAGING"));
        assertTrue("Must suggest #CANARY", lookupStrings.contains("#CANARY"));

        // Excluded variants must NOT be suggested
        assertFalse("Must NOT suggest #LOCAL", lookupStrings.contains("#LOCAL"));
        assertFalse("Must NOT suggest #PROD", lookupStrings.contains("#PROD"));

        // Must preserve root declaration ordering
        int devIdx = lookupStrings.indexOf("#DEV");
        int stagingIdx = lookupStrings.indexOf("#STAGING");
        int canaryIdx = lookupStrings.indexOf("#CANARY");
        assertTrue("Root ordering violation: #DEV must precede #STAGING", devIdx < stagingIdx);
        assertTrue("Root ordering violation: #STAGING must precede #CANARY", stagingIdx < canaryIdx);
    }

    public void testTransitiveChainCompletion() {
        myFixture.configureByText(
            "transitive_chain.stvn",
            """
            {
              :defs {
                :TaskStatus :Enum [ #BACKLOG #TODO #IN_PROGRESS #CODE_REVIEW #TESTING #DONE #BLOCKED #CANCELLED ]
                :ActiveStatus { #filterExcl [ #BACKLOG #DONE #CANCELLED ] } :TaskStatus
                :WorkableStatus { #filterIncl [ #TODO #IN_PROGRESS #CODE_REVIEW #TESTING ] } :ActiveStatus
                :ExecutionStatus { #filterExcl [ #CODE_REVIEW #TESTING ] } :WorkableStatus
              }
              :type :ExecutionStatus
              :body <caret>
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull(elements);

        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);

        // Terminal chain variants
        assertTrue("Must suggest #TODO", lookupStrings.contains("#TODO"));
        assertTrue("Must suggest #IN_PROGRESS", lookupStrings.contains("#IN_PROGRESS"));

        // Excluded variants across all ancestor tiers
        assertFalse("Must NOT suggest #BACKLOG", lookupStrings.contains("#BACKLOG"));
        assertFalse("Must NOT suggest #DONE", lookupStrings.contains("#DONE"));
        assertFalse("Must NOT suggest #CANCELLED", lookupStrings.contains("#CANCELLED"));
        assertFalse("Must NOT suggest #BLOCKED", lookupStrings.contains("#BLOCKED"));
        assertFalse("Must NOT suggest #CODE_REVIEW", lookupStrings.contains("#CODE_REVIEW"));
        assertFalse("Must NOT suggest #TESTING", lookupStrings.contains("#TESTING"));
    }

    public void testFilterFacetAuthoringCompletion() {
        myFixture.configureByText(
            "facet_authoring.stvn",
            """
            {
              :defs {
                :Status :Enum [ #Pending #Active #Suspended #Deleted ]
                :WorkingStatus { #filterExcl [ #Deleted ] } :Status
                :ImmediateStatus { #filterIncl [ #Active <caret> ] } :WorkingStatus
              }
              :type :Boolean
              :body #TRUE
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull(elements);

        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);

        // Un-declared parent variants must be suggested
        assertTrue("Must suggest #Pending", lookupStrings.contains("#Pending"));
        assertTrue("Must suggest #Suspended", lookupStrings.contains("#Suspended"));

        // Already listed variants must NOT be suggested
        assertFalse("Must NOT suggest already declared #Active", lookupStrings.contains("#Active"));

        // Variants excluded by immediate parent must NOT be suggested
        assertFalse("Must NOT suggest excluded #Deleted", lookupStrings.contains("#Deleted"));
    }
}
