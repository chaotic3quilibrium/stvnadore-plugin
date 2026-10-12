package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform unit test verifying the missing root metadata inspection (StvnMissingMetaInspection)
 * across definitions schemas (.stvn_d, .stvn_di) and modular payload documents (.stvn_i).
 */
@NullMarked
public final class StvnMissingMetaInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new StvnMissingMetaInspection());
    }

    /**
     * Verifies that omitting a root :meta block in a .stvn_i file triggers an error diagnostic.
     */
    public void testMissingMetaOnPayloadIncludeTriggersGenericError() {
        var content = """
            {
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("payload_example.stvn_i", content);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("requires root :meta block"))
            .toList();
        assertFalse("Expected missing :meta error on .stvn_i document", errors.isEmpty());
        assertTrue(errors.get(0).getDescription().contains("Payload include document requires root :meta block [stvn-plugin:ERR_DOCUMENT_KIND_MISMATCH]"));
    }

    /**
     * Verifies that InsertMissingMetaBlockQuickFix inserts a canonical :meta block with #kind #BODY_INCLUDE.
     */
    public void testQuickFixInsertsCanonicalMetaBlockIntoPayloadInclude() {
        var content = """
            {
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("json_example.json.stvn_i", content);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Insert missing root :meta block");
        assertFalse("Expected InsertMissingMetaBlockQuickFix intention", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var text = myFixture.getFile().getText();
        assertTrue("Expected #name \"json_example\"", text.contains("#name \"json_example\""));
        assertTrue("Expected #domain \"json\"", text.contains("#domain \"json\""));
        assertTrue("Expected #kind #BODY_INCLUDE", text.contains("#kind #BODY_INCLUDE"));
    }

    /**
     * Verifies that a valid .stvn_i file with a root :meta block produces zero kind mismatch errors.
     */
    public void testValidMetaOnPayloadIncludeProducesZeroErrors() {
        var content = """
            {
              :meta {
                #name "json_example"
                #domain "json"
                #kind #BODY_INCLUDE
              }
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("valid_example.stvn_i", content);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_DOCUMENT_KIND_MISMATCH"))
            .toList();
        assertTrue("Expected zero kind mismatch errors on valid .stvn_i", errors.isEmpty());
    }

    /**
     * Verifies that InsertMissingMetaBlockQuickFix omits domain when filename stem slot 1 is absent.
     */
    public void testQuickFixOmitsDomainWhenStemSlot1Absent() {
        var content = """
            {
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("json_example.stvn_i", content);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Insert missing root :meta block");
        assertFalse("Expected InsertMissingMetaBlockQuickFix intention", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var text = myFixture.getFile().getText();
        assertTrue("Expected #name \"json_example\"", text.contains("#name \"json_example\""));
        assertFalse("Expected #domain omitted when stem slot 1 absent", text.contains("#domain"));
        assertTrue("Expected #kind #BODY_INCLUDE", text.contains("#kind #BODY_INCLUDE"));
    }

    /**
     * Verifies that missing :meta error highlights anchor to opening brace when preceded by comments.
     */
    public void testMissingMetaAnchorsToOpeningBraceWhenPrecededByComments() {
        var content = """
            // @spec: SPEC-09-MODULES
            // Header comment with {brace in comment}
            {
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("commented_payload.stvn_i", content);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("requires root :meta block"))
            .toList();
        assertFalse("Expected missing :meta error", errors.isEmpty());
        var error = errors.get(0);
        assertEquals("{", error.getText());
    }

    /**
     * Verifies that existing :meta block missing #kind facet triggers error diagnostic.
     */
    public void testMetaBlockMissingKindFacetTriggersError() {
        var content = """
            {
              :meta {
                #name "example"
              }
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("incomplete_meta.stvn_i", content);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("requires #kind facet"))
            .toList();
        assertFalse("Expected missing #kind facet error", errors.isEmpty());
    }

    /**
     * Verifies that omitting a root :meta block in a definitions schema (.stvn_d) highlights root '{'.
     */
    public void testMissingMetaOnDefinitionsSchemaHighlightsRootBrace() {
        var content = """
            {
              :defs {
                :package :com/example {
                  :MyId :Int
                }
              }
            }
            """;
        myFixture.configureByText("primitives.stvn_d", content);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Definitions schema requires root :meta block"))
            .toList();
        assertFalse("Expected missing :meta error on .stvn_d document", errors.isEmpty());
        assertEquals("{", errors.get(0).getText());
    }

    /**
     * Verifies that omitting a root :meta block in a modular definitions schema (.stvn_di) highlights root '{'.
     */
    public void testMissingMetaOnModularDefsIncludeHighlightsRootBrace() {
        var content = """
            {
              :defs {
                :include [ "primitives.stvn_d" ]
              }
            }
            """;
        myFixture.configureByText("events.stvn_di", content);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Definitions schema requires root :meta block"))
            .toList();
        assertFalse("Expected missing :meta error on .stvn_di document", errors.isEmpty());
        assertEquals("{", errors.get(0).getText());
    }
}
