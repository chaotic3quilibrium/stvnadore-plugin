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

        var actions = myFixture.filterAvailableIntentions("Remove '#domain' facet");
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

        var actions = myFixture.filterAvailableIntentions("Remove '#domain' facet");
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

        var alignActions = myFixture.filterAvailableIntentions("Align '#name' with filename stem slot 0 ('original_stem')");
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

    /**
     * Verifies that a two-stem filename omitting #domain in :meta emits WARN_DOCUMENT_DOMAIN_OMITTED_IN_META
     * pinned strictly to 5-char ':meta' and offers both InsertMetaDomainQuickFix and StvnOmitDomainQuickFix.
     */
    public void testTwoStemFilenameOmittingDomainInMetaEmitsWarningAndQuickFixes() {
        var content = """
            {
              <caret>:meta {
                #name "use_nested"
                #kind #BODY_INCLUDE
              }
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("use_nested.json.stvn_i", content);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_DOCUMENT_DOMAIN_OMITTED_IN_META"))
            .toList();
        assertEquals("Expected exactly 1 WARN_DOCUMENT_DOMAIN_OMITTED_IN_META warning", 1, warnings.size());
        assertEquals("Omitted #domain warning must pin strictly to 5-char ':meta' token", ":meta", warnings.get(0).getText());
        assertTrue(
            "Warning message must cite omitted domain 'json'",
            warnings.get(0).getDescription().contains("Filename stem slot 1 declares domain 'json', but :meta omits #domain \"json\"")
        );

        var insertActions = myFixture.filterAvailableIntentions("Insert '#domain \"json\"' in :meta");
        assertFalse("Expected InsertMetaDomainQuickFix intention", insertActions.isEmpty());

        var omitActions = myFixture.filterAvailableIntentions("Omit domain from filename (rename to 'use_nested.stvn_i')");
        assertFalse("Expected StvnOmitDomainQuickFix intention", omitActions.isEmpty());
        myFixture.getIntentionPreviewText(omitActions.get(0));
        assertEquals("Intention Preview must not rename physical file", "use_nested.json.stvn_i", myFixture.getFile().getName());
        assertNotSame(
            "StvnOmitDomainQuickFix.generatePreview must return non-empty rename preview",
            com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.EMPTY,
            omitActions.get(0).generatePreview(getProject(), myFixture.getEditor(), myFixture.getFile())
        );

        myFixture.launchAction(insertActions.get(0));
        var updatedText = myFixture.getFile().getText();
        int nameIdx = updatedText.indexOf("#name \"use_nested\"");
        int domainIdx = updatedText.indexOf("#domain \"json\"");
        int kindIdx = updatedText.indexOf("#kind #BODY_INCLUDE");
        assertTrue("Expected #domain \"json\" to be inserted in canonical 3-tier order between #name and #kind:\n" + updatedText,
            nameIdx >= 0 && domainIdx > nameIdx && kindIdx > domainIdx);
    }

    /**
     * Verifies that declaring a reserved "stvn_*" #domain in a user document emits E038 (ERROR) and W001 (WARNING),
     * suppresses illegal rename and 2-stem Remove '#domain' facet, and offers deduplicated STE-ordered quick-fixes.
     */
    public void testReservedDomainEmitsE038ErrorAndSuppressesIllegalRenameQuickFix() {
        var content = """
            {
              :meta {
                #name "gauntlet_v4_stage1_meta_multi_error_and_body"
                #domain "<caret>stvn_reserved_trap"
                #kind #BODY_INCLUDE
              }
              :type :Int
              :body 42
            }
            """;
        myFixture.configureByText("gauntlet_v4_stage1_meta_multi_error_and_body.core_test.stvn_i", content);
        var highlights = myFixture.doHighlighting();

        var e038Errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_RESERVED_DOMAIN_PROHIBITED"))
            .toList();
        assertEquals("Expected 1 E038 error on reserved 'stvn_reserved_trap' domain", 1, e038Errors.size());
        assertTrue(e038Errors.get(0).getDescription().contains(
            "Reserved system domain prohibited: User document cannot declare reserved system domain 'stvn_reserved_trap'"
        ));

        var w001Warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_DOCUMENT_NAME_MISMATCH"))
            .toList();
        assertEquals("Expected 1 W001 warning on mismatched stem slot 1", 1, w001Warnings.size());

        var illegalRename = myFixture.filterAvailableIntentions(
            "Rename file to 'gauntlet_v4_stage1_meta_multi_error_and_body.stvn_reserved_trap.stvn_i'"
        );
        assertTrue("Illegal rename to reserved '.stvn_reserved_trap' domain must be suppressed", illegalRename.isEmpty());

        var alignDomain = myFixture.filterAvailableIntentions("Align '#domain' with filename stem slot 1 ('core_test')");
        assertEquals("AlignDomainWithStemQuickFix must appear strictly once", 1, alignDomain.size());

        var removeDomain = myFixture.filterAvailableIntentions("Remove '#domain' facet");
        assertTrue("Remove '#domain' facet must be suppressed on 2-stem filenames", removeDomain.isEmpty());

        var stripPrefix = myFixture.filterAvailableIntentions("Remove reserved 'stvn_' prefix from '#domain'");
        assertEquals("StvnChangeReservedDomainQuickFix must appear strictly once", 1, stripPrefix.size());

        var allAvailableLabels = myFixture.getAvailableIntentions().stream()
            .map(com.intellij.codeInsight.intention.IntentionAction::getText)
            .toList();
        int alignIdx = allAvailableLabels.indexOf("Align '#domain' with filename stem slot 1 ('core_test')");
        int stripIdx = allAvailableLabels.indexOf("Remove reserved 'stvn_' prefix from '#domain'");
        assertTrue("Align '#domain' (TOP) must precede Remove reserved 'stvn_' prefix (HIGH): " + allAvailableLabels,
            alignIdx >= 0 && stripIdx > alignIdx);

        myFixture.launchAction(alignDomain.get(0));
        assertTrue("Expected #domain aligned to \"core_test\"",
            myFixture.getFile().getText().contains("#domain \"core_test\""));
    }

    /**
     * Verifies that on a single-stem file declaring a reserved "stvn_*" #domain, W002 suppresses the illegal rename quick-fix
     * and offers Remove '#domain' facet strictly once at both '#domain' and the string literal.
     */
    public void testSingleStemReservedDomainSuppressesRenameToStvnPrefix() {
        var content = """
            {
              :meta {
                #name "single_stem"
                #domain "<caret>stvn_internal"
                #kind #BODY_INCLUDE
              }
              :type :Int
              :body 42
            }
            """;
        myFixture.configureByText("single_stem.stvn_i", content);
        myFixture.doHighlighting();

        var illegalRename = myFixture.filterAvailableIntentions("Rename file to 'single_stem.stvn_internal.stvn_i'");
        assertTrue("Illegal rename to '.stvn_internal' on single-stem file must be suppressed", illegalRename.isEmpty());

        var removeAtLiteral = myFixture.filterAvailableIntentions("Remove '#domain' facet");
        assertEquals("Remove '#domain' facet must appear strictly once on 1-stem literal", 1, removeAtLiteral.size());

        var stripAtLiteral = myFixture.filterAvailableIntentions("Remove reserved 'stvn_' prefix from '#domain'");
        assertEquals("Remove reserved 'stvn_' prefix must appear strictly once on 1-stem literal", 1, stripAtLiteral.size());

        int kwOffset = myFixture.getFile().getText().indexOf("#domain");
        myFixture.getEditor().getCaretModel().moveToOffset(kwOffset + 2);
        var removeAtKeyword = myFixture.filterAvailableIntentions("Remove '#domain' facet");
        assertEquals("Remove '#domain' facet must appear strictly once on 1-stem '#domain' keyword", 1, removeAtKeyword.size());
    }

    /**
     * Verifies Gauntlet v6 Stage 1 ':meta' span isolation, zero whole-MetaBlock squiggly, zero comment underline,
     * zero cross-facet quick-fix bleed, deduplication, and clean Intention Preview across all co-located inspections.
     */
    public void testGauntletV6Stage1ZeroMetaBlockSquigglyZeroCrossFacetLeakAndZeroDuplicateFixes() {
        myFixture.enableInspections(
            new StvnMissingMetaInspection(),
            new StvnMetadataFacetInspection(),
            new StvnMetadataOrderInspection()
        );
        var content = """
            {
              :meta {
                #kind #BODY_INCLUDE

                // [ERROR E008 / ERR_FACET_ORDER_VIOLATION]: #domain (Tier 2) placed after #kind (Tier 3).
                // [ERROR E038 / ERR_RESERVED_DOMAIN_PROHIBITED]: User document declares reserved "stvn_*" prefix.
                // [WARN  W001 / WARN_DOCUMENT_NAME_MISMATCH]: "stvn_gate_trap" != filename stem[1] "core_test".
                // Verify Alt+Enter -> More actions... opens Intention Preview cleanly and NEVER offers rename to `.stvn_gate_trap.stvn_i`!
                #domain "stvn_gate_trap"

                // [ERROR E008 / ERR_FACET_ORDER_VIOLATION]: #name (Tier 1) placed after #kind (Tier 3).
                // [WARN  W001 / WARN_DOCUMENT_NAME_MISMATCH]: Does not match filename stem[0].
                #name "gauntlet_v6_stage1_wrong_stem_id"

                // [ERROR E008 / ERR_DUPLICATE_METADATA_FACET]: Duplicate #kind facet inside :meta (zero secondary order error).
                #kind #BODY_INCLUDE

                // [ERROR E008 / ERR_INVALID_METADATA_FACET]: Unknown facet inside closed :meta enclosure.
                #reviewer "mct_director"
              }
              :type :Int
              :body 42
            }
            """;
        myFixture.configureByText("gauntlet_v6_stage1_meta_identity_and_section_boundaries.core_test.stvn_i", content);
        var highlights = myFixture.doHighlighting();
        String fileText = myFixture.getFile().getText();

        var diagHighlights = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING
                || h.getSeverity() == HighlightSeverity.WEAK_WARNING
                || h.getSeverity() == HighlightSeverity.ERROR)
            .toList();

        for (var h : diagHighlights) {
            String spanText = fileText.substring(h.getStartOffset(), h.getEndOffset());
            assertFalse(
                "Zero diagnostic highlights in Stage 1 :meta may span multiple lines, found [" + spanText + "] for: " + h.getDescription(),
                spanText.contains("\n")
            );
            assertFalse(
                "Zero diagnostic highlights may underline '//' comments, found [" + spanText + "]",
                spanText.contains("//")
            );
            assertFalse(
                "Zero diagnostic highlights may cover '{' or '}' of :meta, found [" + spanText + "]",
                spanText.contains("{") || spanText.contains("}")
            );
        }

        var w001Warnings = diagHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING
                && h.getDescription() != null
                && h.getDescription().contains("WARN_DOCUMENT_NAME_MISMATCH"))
            .toList();
        assertEquals("Both #domain and #name W001 warnings must survive co-located E008 errors", 2, w001Warnings.size());

        // 1. Caret on ':meta {' opening brace: zero #name or #domain quick-fixes
        int lbraceOffset = fileText.indexOf(":meta {") + ":meta ".length();
        myFixture.getEditor().getCaretModel().moveToOffset(lbraceOffset);
        var braceIntentions = myFixture.getAvailableIntentions().stream()
            .map(com.intellij.codeInsight.intention.IntentionAction::getText)
            .toList();
        assertTrue(
            "No #name or #domain quick-fixes may bleed onto ':meta {' brace, found: " + braceIntentions,
            braceIntentions.stream().noneMatch(t -> t.contains("#name") || t.contains("#domain") || t.contains("Rename file to"))
        );

        // 2. Caret on "stvn_gate_trap": deduplicated #domain fixes only, zero #name bleed, zero 2-stem Remove #domain
        int domainLitOffset = fileText.indexOf("#domain \"stvn_gate_trap\"") + "#domain \"".length() + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(domainLitOffset);
        var domainIntentions = myFixture.getAvailableIntentions();
        var domainLabels = domainIntentions.stream()
            .map(com.intellij.codeInsight.intention.IntentionAction::getText)
            .toList();

        String alignDomainLabel = "Align '#domain' with filename stem slot 1 ('core_test')";
        String stripPrefixLabel = "Remove reserved 'stvn_' prefix from '#domain'";
        assertEquals("Align '#domain' must appear strictly once", 1,
            domainLabels.stream().filter(alignDomainLabel::equals).count());
        assertEquals("Remove reserved 'stvn_' prefix must appear strictly once", 1,
            domainLabels.stream().filter(stripPrefixLabel::equals).count());
        assertTrue("Align '#domain' must precede Remove reserved 'stvn_' prefix: " + domainLabels,
            domainLabels.indexOf(alignDomainLabel) < domainLabels.indexOf(stripPrefixLabel));
        assertTrue("Remove '#domain' facet must be suppressed on 2-stem Stage 1 file: " + domainLabels,
            domainLabels.stream().noneMatch(t -> t.contains("Remove '#domain' facet")));
        assertTrue("Illegal rename to '.stvn_gate_trap.' must never be offered: " + domainLabels,
            domainLabels.stream().noneMatch(t -> t.contains("stvn_gate_trap")));
        assertTrue("Zero #name quick-fixes may leak onto #domain line: " + domainLabels,
            domainLabels.stream().noneMatch(t -> t.contains("Align '#name'") || t.contains("gauntlet_v6_stage1_wrong_stem_id")));
        for (var action : domainIntentions) {
            if (action.getText().contains("#domain") || action.getText().contains(":meta")) {
                assertNotNull("Intention Preview DIFF must succeed cleanly for: " + action.getText(),
                    myFixture.getIntentionPreviewText(action));
            }
        }

        // 3. Caret on "gauntlet_v6_stage1_wrong_stem_id": #name fixes only, zero #domain bleed, clean rename preview
        int nameLitOffset = fileText.indexOf("#name \"gauntlet_v6_stage1_wrong_stem_id\"") + "#name \"".length() + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(nameLitOffset);
        var nameIntentions = myFixture.getAvailableIntentions();
        var nameLabels = nameIntentions.stream()
            .map(com.intellij.codeInsight.intention.IntentionAction::getText)
            .toList();

        String alignNameLabel = "Align '#name' with filename stem slot 0 ('gauntlet_v6_stage1_meta_identity_and_section_boundaries')";
        String renameFileLabel = "Rename file to 'gauntlet_v6_stage1_wrong_stem_id.core_test.stvn_i'";
        assertEquals("Align '#name' must appear strictly once", 1,
            nameLabels.stream().filter(alignNameLabel::equals).count());
        assertEquals("Rename file to declared #name must appear strictly once", 1,
            nameLabels.stream().filter(renameFileLabel::equals).count());
        assertTrue("Align '#name' (TOP) must precede Rename file (HIGH): " + nameLabels,
            nameLabels.indexOf(alignNameLabel) < nameLabels.indexOf(renameFileLabel));
        assertTrue("Zero #domain quick-fixes may leak onto #name line: " + nameLabels,
            nameLabels.stream().noneMatch(t ->
                t.contains("Align '#domain'")
                    || t.contains("stvn_gate_trap")
                    || t.contains("Remove reserved 'stvn_'")
                    || t.contains("Remove '#domain'")
            ));
        var renameAction = nameIntentions.stream().filter(a -> renameFileLabel.equals(a.getText())).findFirst().orElseThrow();
        myFixture.getIntentionPreviewText(renameAction);
        assertEquals(
            "Intention Preview must not rename physical file",
            "gauntlet_v6_stage1_meta_identity_and_section_boundaries.core_test.stvn_i",
            myFixture.getFile().getName()
        );
        var renamePreview = renameAction.generatePreview(getProject(), myFixture.getEditor(), myFixture.getFile());
        assertNotNull("Rename file Intention Preview must not be null", renamePreview);
        assertNotSame(
            "Rename file Intention Preview must return non-empty rename card",
            com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.EMPTY,
            renamePreview
        );
    }
}
