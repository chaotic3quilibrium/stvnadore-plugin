package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform unit test verifying the document stem mismatch inspection (StvnStemMismatchInspection)
 * and its associated quick-fixes (IncludeDomainInFilenameQuickFix, RemoveMetaDomainQuickFix).
 */
@NullMarked
public final class StvnStemMismatchInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new StvnStemMismatchInspection());
    }

    /**
     * Verifies that when a single-stem file declares #domain, WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME is reported.
     */
    public void testDomainOmittedInFilenameReportsWarning() {
        var content = """
            {
              :meta {
                #name "json_example"
                #domain "place_holder"
                #kind #BODY_INCLUDE
              }
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("json_example.stvn_i", content);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME"))
            .toList();
        assertFalse("Expected domain omitted warning on single-stem file", warnings.isEmpty());
        assertTrue(warnings.get(0).getDescription().contains("Declared #domain 'place_holder' is omitted from filename stem slot 1"));
    }

    /**
     * Verifies that IncludeDomainInFilenameQuickFix renames the active file to include the declared domain in slot 1.
     */
    public void testIncludeDomainInFilenameQuickFixRenamesFile() {
        var content = """
            {
              :meta {
                #name "json_example"
                #domain "<caret>place_holder"
                #kind #BODY_INCLUDE
              }
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("json_example.stvn_i", content);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Rename file to 'json_example.place_holder.stvn_i'");
        assertFalse("Expected IncludeDomainInFilenameQuickFix intention", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        assertEquals("json_example.place_holder.stvn_i", myFixture.getFile().getName());
    }

    /**
     * Verifies that RemoveMetaDomainQuickFix deletes the #domain facet from the root :meta block.
     */
    public void testRemoveMetaDomainQuickFixDeletesDomainFacet() {
        var content = """
            {
              :meta {
                #name "json_example"
                #domain "<caret>place_holder"
                #kind #BODY_INCLUDE
              }
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("json_example.stvn_i", content);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Remove optional #domain attribute");
        assertFalse("Expected RemoveMetaDomainQuickFix intention", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var text = myFixture.getFile().getText();
        assertFalse("Expected #domain facet to be removed", text.contains("#domain"));
        assertTrue("Expected #name facet preserved", text.contains("#name \"json_example\""));
        assertTrue("Expected #kind facet preserved", text.contains("#kind #BODY_INCLUDE"));
    }

    /**
     * Verifies that RemoveMetaDomainQuickFix preserves canonical 4-space indent on child elements of :meta.
     */
    public void testRemoveMetaDomainQuickFixPreservesCanonical4SpaceIndent() {
        var content = """
            {
              :meta {
                #name "json_example"
                #domain "<caret>place_holder"
                #kind #BODY_INCLUDE
              }
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("json_example.stvn_i", content);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Remove optional #domain attribute");
        assertFalse("Expected RemoveMetaDomainQuickFix intention", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var text = myFixture.getFile().getText();
        assertFalse("Expected #domain facet to be removed", text.contains("#domain"));
        assertTrue("Expected #name facet indented by 4 spaces", text.contains("    #name \"json_example\""));
        assertTrue("Expected #kind facet indented by 4 spaces after #domain removal", text.contains("    #kind #BODY_INCLUDE"));
        assertFalse("Must NOT collapse indentation to 2 spaces", text.contains("\n  #kind #BODY_INCLUDE"));
    }

    /**
     * Verifies that when a two-slot stem file has a mismatched domain, WARN_DOCUMENT_NAME_MISMATCH is reported.
     */
    public void testMismatchedDomainReportsWarningAndRenamesFile() {
        var content = """
            {
              :meta {
                #name "json_example"
                #domain "<caret>custom"
                #kind #BODY_INCLUDE
              }
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("json_example.place_holder.stvn_i", content);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_DOCUMENT_NAME_MISMATCH"))
            .toList();
        assertFalse("Expected domain mismatch warning", warnings.isEmpty());

        var actions = myFixture.filterAvailableIntentions("Rename file to 'json_example.custom.stvn_i'");
        assertFalse("Expected IncludeDomainInFilenameQuickFix intention", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        assertEquals("json_example.custom.stvn_i", myFixture.getFile().getName());
    }

    /**
     * Verifies that when #name mismatches single-segment file stem, both Align and Rename quick-fixes are offered.
     */
    public void testNameMismatchReportsWarningAndOffersBothQuickFixes() {
        var content = """
            {
              :meta {
                #name "<caret>declared_name"
                #kind #BODY_INCLUDE
              }
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("original_stem.stvn_i", content);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_DOCUMENT_NAME_MISMATCH"))
            .toList();
        assertFalse("Expected name mismatch warning", warnings.isEmpty());

        var alignActions = myFixture.filterAvailableIntentions("Align #name with file stem");
        assertFalse("Expected AlignStemNameQuickFix intention", alignActions.isEmpty());

        var renameActions = myFixture.filterAvailableIntentions("Rename file to 'declared_name.stvn_i'");
        assertFalse("Expected RenameFileToDeclaredNameQuickFix intention", renameActions.isEmpty());
    }

    /**
     * Verifies that RenameFileToDeclaredNameQuickFix renames a multi-segment file while preserving slot 1 and extension.
     */
    public void testRenameFileToDeclaredNameQuickFixPreservesMultiSegmentStem() {
        var content = """
            {
              :meta {
                #name "<caret>rfc8259_json_substrat"
                #domain "json_schema"
                #kind #DEFS
              }
              :defs {}
            }
            """;
        myFixture.configureByText("rfc8259_json_substrate.json_schema.stvn_d", content);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Rename file to 'rfc8259_json_substrat.json_schema.stvn_d'");
        assertFalse("Expected RenameFileToDeclaredNameQuickFix intention preserving domain slot 1", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        assertEquals("rfc8259_json_substrat.json_schema.stvn_d", myFixture.getFile().getName());
    }
}
