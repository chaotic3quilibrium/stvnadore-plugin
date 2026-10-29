package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;

/**
 * Platform integration test suite verifying diagnostic coordinate pinning,
 * root-cause diagnostic navigation intentions, and paired healing quick-fixes
 * for STVN trait violations (ERR_TRAIT_VIOLATION).
 */
@NullMarked
public final class StvnTraitViolationNavigationAndHealingTest extends BasePlatformTestCase {

    public void testExplicitOverrideNavigationAndHealingQuickFixes() {
        var text = """
            {
              :defs {
                :Values { #equatable #FALSE } :Tuple ( :Int :String )
              }
              :type :Set(:Values)
              :body [ ( 1 "alpha" ) ]
            }
            """;
        myFixture.configureByText("explicit_override.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter(h -> h.getDescription() != null && h.getDescription().contains("Set elements require types to be #equatable #TRUE"))
                .toList();
        assertFalse("Expected trait violation error on line 5 :Values", errors.isEmpty());

        int usageOffset = text.indexOf(":Set(:Values)") + ":Set(".length();
        myFixture.getEditor().getCaretModel().moveToOffset(usageOffset);

        // 1. Verify Navigation Intention
        var navIntentions = myFixture.filterAvailableIntentions("Navigate to explicit '#equatable #FALSE'");
        assertFalse("Expected 'Navigate to explicit #equatable #FALSE declaration on :Values' intention", navIntentions.isEmpty());

        // 2. Verify Paired Healing Quick-Fixes
        var removeFixes = myFixture.filterAvailableIntentions("Remove explicit '#equatable #FALSE'");
        assertFalse("Expected 'Remove explicit #equatable #FALSE override from :Values' fix", removeFixes.isEmpty());

        var changeFixes = myFixture.filterAvailableIntentions("Override '#equatable #TRUE' on :Values in :defs");
        assertTrue("ChangeNominalOverrideQuickFix must NOT be registered when underlying tuple is already equatable (BUG-FIX-02, W003)", changeFixes.isEmpty());

        var localFixes = myFixture.filterAvailableIntentions("Override '#equatable #TRUE' on :Set(:Values)");
        assertTrue("Local in-place override fix must NOT be registered per SPEC § 6.1 and § 9.1", localFixes.isEmpty());
    }

    public void testRemoveIncompatibleOverrideExcisesSoleMetadataMap() {
        var text = """
            {
              :defs {
                :Values { #equatable #FALSE } :Tuple ( :Int :String )
              }
              :type :Set(:Values)
              :body [ ( 1 "alpha" ) ]
            }
            """;
        myFixture.configureByText("remove_override.stvn", text);
        myFixture.doHighlighting();

        int usageOffset = text.indexOf(":Set(:Values)") + ":Set(".length();
        myFixture.getEditor().getCaretModel().moveToOffset(usageOffset);

        var removeFixes = myFixture.filterAvailableIntentions("Remove explicit '#equatable #FALSE'");
        assertFalse(removeFixes.isEmpty());
        myFixture.launchAction(removeFixes.get(0));

        var mutatedText = myFixture.getFile().getText();
        assertFalse("Must NOT contain #equatable #FALSE", mutatedText.contains("#equatable #FALSE"));
        assertFalse("Must NOT leave empty metadata block '{}' (SPEC § 3.5.1, E003)", mutatedText.contains("{}"));
        assertTrue("Must cleanly leave unadorned tuple declaration:\n" + mutatedText,
                mutatedText.contains(":Values :Tuple ( :Int :String )") || mutatedText.contains(":Values  :Tuple ( :Int :String )"));
    }

    public void testChangeNominalOverrideToTrueInDefs() {
        var text = """
            {
              :defs {
                :Values { #equatable #FALSE } :Tuple ( :Int :Float )
              }
              :type :Set(:Values)
              :body [ ( 1 3.14 ) ]
            }
            """;
        myFixture.configureByText("change_override.stvn", text);
        myFixture.doHighlighting();

        int usageOffset = text.indexOf(":Set(:Values)") + ":Set(".length();
        myFixture.getEditor().getCaretModel().moveToOffset(usageOffset);

        var changeFixes = myFixture.filterAvailableIntentions("Override '#equatable #TRUE' on :Values in :defs");
        assertFalse(changeFixes.isEmpty());
        myFixture.launchAction(changeFixes.get(0));

        var mutatedText = myFixture.getFile().getText();
        assertFalse("Must NOT contain #equatable #FALSE", mutatedText.contains("#equatable #FALSE"));
        assertTrue("Must contain #equatable #TRUE on :Values in :defs:\n" + mutatedText,
                mutatedText.contains(":Values { #equatable #TRUE } :Tuple ( :Int :Float )") || mutatedText.contains(":Values {#equatable #TRUE} :Tuple ( :Int :Float )"));
    }

    public void testInductiveConstituentNavigationAndJump() {
        var text = """
            {
              :defs {
                :Values :Tuple ( :Int :Float )
              }
              :type :Set(:Values)
              :body [ ( 1 3.14 ) ]
            }
            """;
        myFixture.configureByText("inductive_breakdown.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter(h -> h.getDescription() != null && h.getDescription().contains("Set elements require types to be #equatable #TRUE"))
                .toList();
        assertFalse("Expected trait violation error", errors.isEmpty());

        int usageOffset = text.indexOf(":Set(:Values)") + ":Set(".length();
        myFixture.getEditor().getCaretModel().moveToOffset(usageOffset);

        var jumpIntentions = myFixture.filterAvailableIntentions("Jump to non-equatable constituent");
        assertFalse("Expected jump intention for Field #2 (:Float)", jumpIntentions.isEmpty());
        assertTrue(jumpIntentions.get(0).getText().contains("Field #2: :Float"));

        myFixture.launchAction(jumpIntentions.get(0));
        int floatOffset = text.indexOf(":Float");
        assertEquals("Caret must navigate directly to offending constituent token in :defs",
                floatOffset, myFixture.getEditor().getCaretModel().getOffset());
    }

    public void testQuickDocumentationOriginAndConstituentHyperlinks() {
        var text = """
            {
              :defs {
                :Values { #equatable #FALSE } :Tuple ( :Int :String )
                :InductiveTuple :Tuple ( :Int :Float )
              }
              :type :Values
              :body ( 1 "hello" )
            }
            """;
        var psiFile = myFixture.configureByText("quick_doc_links.stvn", text);
        var provider = new StvnDocumentationProvider();

        // 1. Verify Origin Column Hyperlink on :Values
        int valuesOffset = text.indexOf(":Values");
        var elemValues = psiFile.findElementAt(valuesOffset);
        assertNotNull(elemValues);
        var docValues = provider.generateDoc(elemValues, elemValues);
        assertNotNull(docValues);
        assertTrue("Origin column must contain PSI hyperlink to TypeDefinition:\n" + docValues,
                docValues.contains("Declared on<br/><a href=\"psi_element://TypeDefinition/:Values\"><code>:Values</code></a>"));

        var targetElem = provider.getDocumentationElementForLink(myFixture.getPsiManager(), "TypeDefinition/:Values", elemValues);
        assertNotNull("Must resolve link TypeDefinition/:Values to target PSI element", targetElem);

        // 2. Verify Non-Conforming Constituents Section on :InductiveTuple
        int inductiveOffset = text.indexOf(":InductiveTuple");
        var elemInductive = psiFile.findElementAt(inductiveOffset);
        assertNotNull(elemInductive);
        var docInductive = provider.generateDoc(elemInductive, elemInductive);
        assertNotNull(docInductive);
        assertTrue("Doc must render Non-Conforming Constituents block:\n" + docInductive,
                docInductive.contains("<b>Non-Conforming Constituents:</b>"));
        assertTrue("Doc must render Field #2 link:\n" + docInductive,
                docInductive.contains("Field #2:"));

        int floatOffset = text.indexOf(":Float");
        var targetConstituent = provider.getDocumentationElementForLink(myFixture.getPsiManager(), "offset/" + floatOffset, elemInductive);
        assertNotNull("Must resolve link offset/" + floatOffset + " to :Float constituent element", targetConstituent);
    }

    public void testIntentionPreviewReturnsEmptyWithoutEdtAssertion() {
        var text = """
            {
              :defs {
                :Values { #equatable #FALSE } :Tuple ( :Int :String )
                :InductiveTuple :Tuple ( :Int :Float )
              }
              :type :Set(:Values)
              :body [ ( 1 "test" ) ]
            }
            """;
        myFixture.configureByText("preview_test.stvn", text);
        myFixture.doHighlighting();
        int usageOffset = text.indexOf(":Set(:Values)") + ":Set(".length();
        myFixture.getEditor().getCaretModel().moveToOffset(usageOffset);

        var navigateIntentions = myFixture.filterAvailableIntentions("Navigate to explicit '#equatable #FALSE'");
        assertFalse("Expected navigate intention to be available", navigateIntentions.isEmpty());
        var navAction = navigateIntentions.get(0);
        var navPreview = myFixture.getIntentionPreviewText(navAction);
        assertNull("Intention preview for pure navigation action must be EMPTY (null diff)", navPreview);

        var text2 = """
            {
              :defs {
                :InductiveTuple :Tuple ( :Int :Float )
              }
              :type :Set(:InductiveTuple)
              :body [ ( 1 3.14 ) ]
            }
            """;
        myFixture.configureByText("preview_jump_test.stvn", text2);
        myFixture.doHighlighting();
        int usageOffset2 = text2.indexOf(":Set(:InductiveTuple)") + ":Set(".length();
        myFixture.getEditor().getCaretModel().moveToOffset(usageOffset2);
        var jumpIntentions = myFixture.filterAvailableIntentions("Jump to non-equatable constituent");
        assertFalse("Expected jump intention to be available", jumpIntentions.isEmpty());
        var jumpAction = jumpIntentions.get(0);
        var jumpPreview = myFixture.getIntentionPreviewText(jumpAction);
        assertNull("Intention preview for jump action must be EMPTY (null diff)", jumpPreview);
    }

    public void testMultiSetSiblingClampingInType() {
        myFixture.addFileToProject("remote_nodes.network.stvn_d", """
            {
              :meta {
                #kind #DEFS
                #dialect #STVN_1_0
              }
              :defs {
                :RemoteNode :Tuple( :String { #unsigned #size 16 #minIncl 1 } :Int )
                :SensorSample :Tuple( :String :Float )
              }
            }
            """);

        var consumerText = """
            {
              :meta {
                #kind #INSTANCE
                #dialect #STVN_1_0
              }
              :defs {
                :include [ "remote_nodes.network.stvn_d" ]
              }
              :type :Tuple(
                :Set(:RemoteNode)
                :Set(:SensorSample)
              )
              :body (
                [ ( "node1" 8080 ) ]
                [ ( "sensor1" 98.6 ) ]
              )
            }
            """;
        myFixture.configureByText("cross_file_consumer.network.stvn_i", consumerText);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter(h -> h.getDescription() != null && h.getDescription().contains("Set elements require types to be #equatable #TRUE"))
                .toList();

        assertFalse("Expected trait violation error for non-equatable :SensorSample", errors.isEmpty());
        var error = errors.get(0);
        int sensorSampleOffset = consumerText.indexOf(":SensorSample");
        assertEquals("Diagnostic squiggle must pin strictly to :SensorSample on line 12 (BUG-CLAMP-01)",
                sensorSampleOffset, error.getStartOffset());
        assertEquals("Diagnostic squiggle length must match ':SensorSample'",
                ":SensorSample".length(), error.getEndOffset() - error.getStartOffset());
    }

    public void testMultiMapSiblingClampingInType() {
        var text = """
            {
              :type :Tuple(
                :Map( :String :Int )
                :Map( :Float :Int )
              )
              :body (
                { "key" 1 }
                { 3.14 2 }
              )
            }
            """;
        myFixture.configureByText("sibling_map_test.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .filter(h -> h.getDescription() != null && h.getDescription().contains("Map keys require types to be #equatable #TRUE"))
                .toList();

        assertFalse("Expected trait violation error for non-equatable map key :Float", errors.isEmpty());
        var error = errors.get(0);
        int floatKeyOffset = text.indexOf(":Float");
        assertEquals("Diagnostic squiggle must pin strictly to offending :Float map key (BUG-CLAMP-01)",
                floatKeyOffset, error.getStartOffset());
        assertEquals("Diagnostic squiggle length must match ':Float'",
                ":Float".length(), error.getEndOffset() - error.getStartOffset());
    }
}
