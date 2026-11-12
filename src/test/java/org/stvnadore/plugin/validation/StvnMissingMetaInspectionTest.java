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
        assertTrue(errors.get(0).getDescription().contains("Payload include document requires root :meta block [stvn-plugin:E031-ERR_DOCUMENT_KIND_MISMATCH]"));
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
     * Verifies that InsertMissingMetaBlockQuickFix defaults domain to "domain" when filename stem slot 1 is absent.
     */
    public void testQuickFixEmitsDefaultDomainWhenStemSlot1Absent() {
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
        assertTrue("Expected #domain \"domain\" when stem slot 1 absent", text.contains("#domain \"domain\""));
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
     * Verifies that existing :meta block missing #kind facet triggers error diagnostic pinned strictly to ':meta'.
     */
    public void testMetaBlockMissingKindFacetTriggersError() {
        var content = """
            {
              <caret>:meta {
                #name "incomplete_meta"
                #domain "core_test"
              }
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("incomplete_meta.core_test.stvn_i", content);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("requires #kind facet"))
            .toList();
        assertEquals("Expected 1 missing #kind facet error", 1, errors.size());
        assertEquals("Missing #kind diagnostic must pin strictly to 5-char ':meta' token", ":meta", errors.get(0).getText());
        assertEquals(
            "Root ':meta' section requires #kind facet matching file extension '.stvn_i' (expected '#BODY_INCLUDE') [stvn-plugin:E031-ERR_DOCUMENT_KIND_MISMATCH]",
            errors.get(0).getDescription()
        );

        var insertKind = myFixture.filterAvailableIntentions("Insert missing #kind facet");
        assertEquals("Expected InsertMissingMetaKindFacetQuickFix at ':meta'", 1, insertKind.size());
        String preview = myFixture.getIntentionPreviewText(insertKind.get(0));
        assertNotNull("InsertMissingMetaKindFacetQuickFix Intention Preview must render DIFF", preview);
        myFixture.launchAction(insertKind.get(0));

        var updated = myFixture.getFile().getText();
        int nameIdx = updated.indexOf("#name \"incomplete_meta\"");
        int domainIdx = updated.indexOf("#domain \"core_test\"");
        int kindIdx = updated.indexOf("#kind #BODY_INCLUDE");
        assertTrue(
            "Expected #kind #BODY_INCLUDE inserted at canonical Tier 3 after #name and #domain:\n" + updated,
            nameIdx >= 0 && domainIdx > nameIdx && kindIdx > domainIdx
        );
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
     * Asserts that primitives.stvn_d lacking :meta flags the missing :meta block on root '{'
     * even when the file contains references to :org/stvnadore/prelude/meta/* packages.
     */
    public void testMissingMetaOnDefinitionsSchemaWithPreludeMetaReference() {
        var content = """
            {
              // primitives.stvn_d
              :defs {
                :package :com/nyse/primitives {
                  :ExecutionPrice :org/stvnadore/prelude/meta/Currency
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
        assertFalse("Expected missing :meta error on line 1 '{' despite prelude meta reference", errors.isEmpty());
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

    /**
     * Verifies that missing :meta is pinned to line 1 '{' and provides quick-fix
     * even when the :defs block contains errors (such as unresolved types).
     */
    public void testMissingMetaReportedEvenWhenDefsContainsErrors() {
        var content = """
            {
              :defs {
                :InvalidField :NonExistentType999
              }
            }
            """;
        myFixture.configureByText("broken_defs.stvn_di", content);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Definitions schema requires root :meta block"))
            .toList();
        assertFalse("Expected missing :meta error on line 1 '{' despite errors in :defs", errors.isEmpty());
        assertEquals("{", errors.get(0).getText());

        var actions = myFixture.filterAvailableIntentions("Insert missing root :meta block");
        assertFalse("Expected InsertMissingMetaBlockQuickFix available despite errors in :defs", actions.isEmpty());
    }

    /**
     * Verifies that missing :meta is pinned to line 1 '{' on payload include documents (.stvn_i)
     * when :defs contains include statements.
     */
    public void testMissingMetaOnPayloadIncludeWithDefsEntry() {
        var content = """
            {
              :defs {
                :include [ "non_existent_module.stvn_d" ]
              }
              :body [ 10 20 30 ]
            }
            """;
        myFixture.configureByText("order_batch.stvn_i", content);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Payload include document requires root :meta block"))
            .toList();
        assertFalse("Expected missing :meta error on line 1 '{' for .stvn_i with :defs", errors.isEmpty());
        assertEquals("{", errors.get(0).getText());
    }

    /**
     * Verifies that when :meta is present, a syntax error in :defs (e.g. :Tuplex)
     * does NOT falsely trigger missing :meta inspection on line 1 '{'.
     */
    public void testMetaPreservedWhenDefsContainsSyntaxError() {
        var content = """
            {
              :meta {
                #kind #BODY_INCLUDE
              }
              :defs {
                :TradeBatch :Tuplex( :BatchId :ExecutionReport )
              }
              :type :TradeBatch
              :body ( 101 )
            }
            """;
        myFixture.configureByText("order_batch.stvn_i", content);
        var highlights = myFixture.doHighlighting();
        var missingMetaErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Payload include document requires root :meta block"))
            .toList();
        assertTrue("Line 1 '{' must NOT report missing :meta when :meta is written in file", missingMetaErrors.isEmpty());
    }

    /**
     * Verifies that configuring an empty (0-byte) .stvn_d file and executing inspection
     * produces zero IntelliJ SDK assertion errors or throwables (e.g. empty PSI element descriptor crash).
     */
    public void testEmptyFileProducesZeroAssertionErrors() {
        myFixture.configureByText("empty.stvn_d", "");
        var highlights = myFixture.doHighlighting();
        assertNotNull(highlights);
    }

    /**
     * Verifies that an explicitly empty :meta {} block defers to E039 (ERR_EMPTY_META_BLOCK) in
     * StvnMetadataFacetInspection and does not emit a false-positive E031 (ERR_DOCUMENT_KIND_MISMATCH)
     * from StvnMissingMetaInspection.
     */
    public void testEmptyMetaBlockDefersToE039WithoutEmittingFalseE031() {
        var content = """
            {
              :meta {}
              :defs {
                :BoundedPort { #unsigned #size 16 #minIncl 1024 #maxExcl 4096 } :Int
              }
              :type :BoundedPort
              :body 2048
            }
            """;
        myFixture.configureByText("empty_meta_block_with_body_error.core_test.stvn_i", content);
        var highlights = myFixture.doHighlighting();
        var e031Errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_DOCUMENT_KIND_MISMATCH"))
            .toList();
        assertTrue("Empty :meta {} must not emit false-positive E031 from StvnMissingMetaInspection: " + e031Errors,
            e031Errors.isEmpty());
    }

    /**
     * Verifies 1:1 parity with Core TC-ID-48b: typing an incomplete in-flight '#kind' facet inside ':meta'
     * suppresses secondary E031 cascades while still flagging genuine unknown facets like '#kindness' when '#kind' is omitted,
     * and emits 1:1 mismatch prose when '#kind' declares a wrong kind keyword.
     */
    public void testInFlightKindFacetSuppressesSecondaryE031CascadeWithoutMaskingKindnessPrefix() {
        var inFlightKind = """
            {
              :meta {
                #name "inflight_doc"
                #domain "app"
                #kind
              }
              :defs {
                :Port { #unsigned #size 16 #minIncl 1 } :Int
              }
              :type :Port
              :body 8080
            }
            """;
        myFixture.configureByText("inflight_doc.app.stvn_i", inFlightKind);
        var inFlightHighlights = myFixture.doHighlighting();
        var inFlightE031 = inFlightHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_DOCUMENT_KIND_MISMATCH"))
            .toList();
        assertTrue("Incomplete in-flight '#kind' inside ':meta' must not emit secondary E031: " + inFlightE031,
            inFlightE031.isEmpty());

        var unknownKindness = """
            {
              :meta {
                #name "inflight_doc"
                #domain "app"
                #kindness 1
              }
              :defs {
                :Port { #unsigned #size 16 #minIncl 1 } :Int
              }
              :type :Port
              :body 8080
            }
            """;
        myFixture.configureByText("inflight_doc.app.stvn_i", unknownKindness);
        var kindnessHighlights = myFixture.doHighlighting();
        var kindnessE031 = kindnessHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_DOCUMENT_KIND_MISMATCH"))
            .toList();
        assertEquals("Unknown '#kindness' facet must not falsely suppress missing '#kind' E031", 1, kindnessE031.size());
        assertEquals(":meta", kindnessE031.get(0).getText());

        var wrongKind = """
            {
              :meta {
                #name "inflight_doc"
                #domain "app"
                #kind #DEFS
              }
              :defs {
                :Port { #unsigned #size 16 } :Int
              }
            }
            """;
        myFixture.configureByText("inflight_doc.app.stvn", wrongKind);
        var wrongKindHighlights = myFixture.doHighlighting();
        var wrongKindE031 = wrongKindHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_DOCUMENT_KIND_MISMATCH"))
            .toList();
        assertEquals("Mismatched '#kind #DEFS' on .stvn must emit exactly 1 E031", 1, wrongKindE031.size());
        assertEquals("#kind #DEFS", wrongKindE031.get(0).getText());
        assertEquals(
            "Document kind '#DEFS' does not match file extension '.stvn' (expected '#BODY') [stvn-plugin:E031-ERR_DOCUMENT_KIND_MISMATCH]",
            wrongKindE031.get(0).getDescription()
        );
    }
}
