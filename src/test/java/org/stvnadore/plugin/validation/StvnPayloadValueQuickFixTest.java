package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * Platform integration test suite verifying smart temporal mode conversions, epoch timestamp
 * conversions, scalar bound clamping, and exemplar replacement quick-fixes in {@code :body} and
 * {@code #CONST} declarations.
 */
@NullMarked
public final class StvnPayloadValueQuickFixTest extends BasePlatformTestCase {

    public void testDateTimeZoneInOffsetSmartConversionAndCurrentExemplarInDeepTuple() {
        var text = """
            {
              :defs {
                :Port { #unsigned #size 16 #minIncl 1 #maxExcl 65536 } :Int
                :DeepFaultIsolationProbe :Union(
                  :String
                  :Tuple(
                    :Port
                    { #offset } :DateTime
                    { #unsigned #size 8 #maxExcl 100 } :Int
                  )
                )
                :FaultEnvelope :Option( :Either( :String :DeepFaultIsolationProbe ) )
              }
              :type :FaultEnvelope
              :body #Some #Right ( #2 ( 8080 "2026-11-03T20:45:00<caret>[America/Chicago]" 50 ) )
            }
            """;
        myFixture.configureByText("datetime_offset_fix.stvn", text);
        myFixture.doHighlighting();

        List<IntentionAction> intentions = myFixture.getAllQuickFixes();
        String expectedConvertLabel = "Convert to #offset :DateTime '\"2026-11-03T20:45:00-06:00\"'";
        IntentionAction convertFix = intentions.stream()
            .filter(a -> expectedConvertLabel.equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected smart conversion quick-fix '" + expectedConvertLabel + "', found: "
            + intentions.stream().map(IntentionAction::getText).toList(), convertFix);

        boolean hasCurrentExemplar = intentions.stream()
            .anyMatch(a -> "Replace with current #offset :DateTime".equals(a.getText()));
        assertTrue("Expected 'Replace with current #offset :DateTime' quick-fix", hasCurrentExemplar);

        myFixture.launchAction(convertFix);
        assertTrue(
            "Document must contain converted #offset literal '\"2026-11-03T20:45:00-06:00\"'",
            myFixture.getEditor().getDocument().getText().contains("\"2026-11-03T20:45:00-06:00\"")
        );
    }

    public void testDateTimeOffsetInZonedAndAuditedMismatchSmartConversions() {
        var zonedText = """
            {
              :defs {
                :ZonedTs { #zoned } :DateTime
              }
              :type :ZonedTs
              :body "2026-11-03T20:45:00-06:00<caret>[America/Chicago]"
            }
            """;
        myFixture.configureByText("datetime_zoned_fix.stvn", zonedText);
        myFixture.doHighlighting();

        String expectedZonedLabel = "Convert to #zoned :DateTime '\"2026-11-03T20:45:00[America/Chicago]\"'";
        IntentionAction zonedFix = myFixture.getAllQuickFixes().stream()
            .filter(a -> expectedZonedLabel.equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected '" + expectedZonedLabel + "'", zonedFix);
        myFixture.launchAction(zonedFix);
        assertTrue(
            myFixture.getEditor().getDocument().getText().contains("\"2026-11-03T20:45:00[America/Chicago]\"")
        );

        var auditedText = """
            {
              :defs {
                :AuditedTs { #audited } :DateTime
              }
              :type :AuditedTs
              :body "2026-11-03T20:45:00-04:00<caret>[America/Chicago]"
            }
            """;
        myFixture.configureByText("datetime_audited_fix.stvn", auditedText);
        myFixture.doHighlighting();

        String expectedAuditedLabel = "Convert to #audited :DateTime '\"2026-11-03T20:45:00-06:00[America/Chicago]\"'";
        IntentionAction auditedFix = myFixture.getAllQuickFixes().stream()
            .filter(a -> expectedAuditedLabel.equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected '" + expectedAuditedLabel + "'", auditedFix);
        myFixture.launchAction(auditedFix);
        assertTrue(
            myFixture.getEditor().getDocument().getText().contains("\"2026-11-03T20:45:00-06:00[America/Chicago]\"")
        );
    }

    public void testTimeEpochStringToTicksConversionAndLiveExemplarInDeepTuple() {
        var isoEpochText = """
            {
              :defs {
                :NanoTs { #ns } :TimeEpoch
              }
              :type :NanoTs
              :body "2026-11-03T20:45:00<caret>Z"
            }
            """;
        myFixture.configureByText("epoch_iso_convert.stvn", isoEpochText);
        myFixture.doHighlighting();

        String expectedEpochConvert = "Convert timestamp to #ns :TimeEpoch '1793738700000000000'";
        IntentionAction convertEpochFix = myFixture.getAllQuickFixes().stream()
            .filter(a -> expectedEpochConvert.equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected '" + expectedEpochConvert + "', found: "
            + myFixture.getAllQuickFixes().stream().map(IntentionAction::getText).toList(), convertEpochFix);
        myFixture.launchAction(convertEpochFix);
        assertTrue(
            myFixture.getEditor().getDocument().getText().contains(":body 1793738700000000000")
        );

        var unparseableEpochText = """
            {
              :defs {
                :DeepTelemetryMatrix :Union(
                  :String
                  :Tuple(
                    { #unsigned #size 16 #minIncl 1 #maxExcl 1000 } :Int
                    { #ns } :TimeEpoch
                    { #maxSize 4096 } :Set( :Tuple( { #unsigned #size 8 } :Int :Boolean ) )
                  )
                )
                :Envelope :Option( :Either( :String :DeepTelemetryMatrix ) )
              }
              :type :Envelope
              :body #Some #Right #2 ( 999 "not-an-<caret>epoch" [ (7 #TRUE) ] )
            }
            """;
        myFixture.configureByText("epoch_live_exemplar.stvn", unparseableEpochText);
        myFixture.doHighlighting();

        IntentionAction replaceEpochFix = myFixture.getAllQuickFixes().stream()
            .filter(a -> "Replace with current #ns :TimeEpoch".equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected 'Replace with current #ns :TimeEpoch'", replaceEpochFix);
        myFixture.launchAction(replaceEpochFix);
        assertFalse(
            "Unparseable string literal must be replaced with integer epoch ticks",
            myFixture.getEditor().getDocument().getText().contains("\"not-an-epoch\"")
        );
    }

    public void testIntegerBoundClampingAndExemplarReplacement() {
        var maxClampText = """
            {
              :defs {
                :BoundedPort { #unsigned #size 16 #minIncl 1 #maxExcl 1000 } :Int
              }
              :type :BoundedPort
              :body 99<caret>99
            }
            """;
        myFixture.configureByText("int_max_clamp.stvn", maxClampText);
        myFixture.doHighlighting();

        IntentionAction maxClampFix = myFixture.getAllQuickFixes().stream()
            .filter(a -> "Clamp value to maximum bound '999'".equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected \"Clamp value to maximum bound '999'\", found: "
            + myFixture.getAllQuickFixes().stream().map(IntentionAction::getText).toList(), maxClampFix);
        myFixture.launchAction(maxClampFix);
        assertTrue(myFixture.getEditor().getDocument().getText().contains(":body 999"));

        var minClampText = """
            {
              :defs {
                :BoundedPort { #unsigned #size 16 #minIncl 1 #maxExcl 1000 } :Int
              }
              :type :BoundedPort
              :body -<caret>5
            }
            """;
        myFixture.configureByText("int_min_clamp.stvn", minClampText);
        myFixture.doHighlighting();

        IntentionAction minClampFix = myFixture.getAllQuickFixes().stream()
            .filter(a -> "Clamp value to minimum bound '1'".equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected \"Clamp value to minimum bound '1'\"", minClampFix);
        myFixture.launchAction(minClampFix);
        assertTrue(myFixture.getEditor().getDocument().getText().contains(":body 1"));

        var constOverflowText = """
            {
              :defs {
                #MAX_BYTE { #unsigned #size 8 #maxExcl 100 } :Int 2<caret>50
              }
              :type :Int
              :body 0
            }
            """;
        myFixture.configureByText("const_clamp.stvn", constOverflowText);
        myFixture.enableInspections(new StvnConstantRangeInspection());
        myFixture.doHighlighting();

        IntentionAction constClampFix = myFixture.getAllQuickFixes().stream()
            .filter(a -> "Clamp value to maximum bound '99'".equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected \"Clamp value to maximum bound '99'\" on #CONST", constClampFix);
        myFixture.launchAction(constClampFix);
        assertTrue(myFixture.getEditor().getDocument().getText().contains("#MAX_BYTE { #unsigned #size 8 #maxExcl 100 } :Int 99"));
    }

    public void testFloatSlotIntegerLiteralOffersAppendPointZeroAsFirstQuickFix() {
        var text = """
            {
              :defs {
                :ExactLatencyMs { #exact #maxSize 8 #minIncl 0.0 #maxExcl 500.0 } :Float
              }
              :type :ExactLatencyMs
              :body 25<caret>0
            }
            """;
        myFixture.configureByText("float_int_append.stvn", text);
        myFixture.doHighlighting();

        List<IntentionAction> quickFixes = myFixture.getAllQuickFixes();
        String expectedAppendLabel = "Append '.0' to integer value '250.0'";
        IntentionAction appendFix = quickFixes.stream()
            .filter(a -> expectedAppendLabel.equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected '" + expectedAppendLabel + "', found: "
            + quickFixes.stream().map(IntentionAction::getText).toList(), appendFix);
        assertEquals("Expected Append '.0' quick-fix to be offered ahead of exemplar replacement",
            expectedAppendLabel, quickFixes.get(0).getText());

        myFixture.launchAction(appendFix);
        assertTrue("Expected integer 250 to be converted to 250.0",
            myFixture.getEditor().getDocument().getText().contains(":body 250.0"));
    }

    public void testExactFloatPrecisionOverflowOrdersRoundThenTruncateThenExemplar() {
        var text = """
            {
              :defs {
                :ExactPrice5Digits { #exact #maxSize 5 } :Float
              }
              :type :ExactPrice5Digits
              :body 123.<caret>456
            }
            """;
        myFixture.configureByText("exact_float_precision_priority.stvn", text);
        myFixture.doHighlighting();

        List<IntentionAction> quickFixes = myFixture.getAllQuickFixes();
        List<String> labels = quickFixes.stream().map(IntentionAction::getText).toList();
        assertTrue("Expected at least 3 quick-fixes on 123.456, found: " + labels, quickFixes.size() >= 3);
        assertEquals(
            "Index 0 must be Round quick-fix",
            "Round value to #maxSize 5 significant digits '123.46'",
            quickFixes.get(0).getText()
        );
        assertEquals(
            "Index 1 must be Truncate quick-fix",
            "Truncate value to #maxSize 5 significant digits '123.45'",
            quickFixes.get(1).getText()
        );
        assertEquals(
            "Index 2 must be Replace with exemplar quick-fix",
            "Replace with exemplar value '0.0'",
            quickFixes.get(2).getText()
        );

        myFixture.launchAction(quickFixes.get(0));
        assertTrue(
            "Expected 123.456 to be rounded to 123.46",
            myFixture.getEditor().getDocument().getText().contains(":body 123.46")
        );
    }

    public void testStage5SiblingQuickFixesAndHoverCardsResilientWhileSlot0HasProhibitedUnderscores() {
        var text = """
            {
              :defs {
                :Uint128Token { #unsigned #size 128 #minIncl 100000000000000000000 } :Int
                :ExactPrice5Digits { #exact #maxSize 5 } :Float
                :Stage5Payload :Tuple(
                  :Uint128Token
                  :Uint128Token
                  :ExactPrice5Digits
                  :ExactPrice5Digits
                )
              }
              :type :Stage5Payload
              :body (
                0x1000_0000_0000_0000_0001
                500
                123.456
                250
              )
            }
            """;
        myFixture.configureByText("stage5_sibling_resilience.stvn", text);
        myFixture.doHighlighting();

        var docProvider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();
        var file = myFixture.getFile();
        var editor = myFixture.getEditor();

        // 1. Slot 0: 0x1000_0000_0000_0000_0001
        int slot0Offset = text.indexOf("0x1000_0000_0000_0000_0001") + 2;
        editor.getCaretModel().moveToOffset(slot0Offset);
        var slot0Fixes = myFixture.filterAvailableIntentions(
            "Remove prohibited underscores from numeric literal '0x10000000000000000001'"
        );
        assertFalse("Slot 0 must offer prohibited underscore removal quick-fix", slot0Fixes.isEmpty());
        var slot0Elem = file.findElementAt(slot0Offset);
        assertNotNull(slot0Elem);
        var slot0DocElem = docProvider.getCustomDocumentationElement(editor, file, slot0Elem, slot0Offset);
        assertNotNull("Slot 0 documentation element must not be null while underscores are present", slot0DocElem);
        String slot0Doc = docProvider.generateDoc(slot0DocElem, slot0Elem);
        assertNotNull("Slot 0 hover card must not be null", slot0Doc);
        assertTrue(
            "Slot 0 hover card must display violation status:\n" + slot0Doc,
            slot0Doc.contains("In Violation") || slot0Doc.contains("Violated")
        );

        // 2. Slot 1: 500 (while Slot 0 still has prohibited underscores!)
        int slot1Offset = text.indexOf("500") + 1;
        editor.getCaretModel().moveToOffset(slot1Offset);
        var slot1Fixes = myFixture.filterAvailableIntentions(
            "Clamp value to minimum bound '100000000000000000000'"
        );
        assertFalse(
            "Slot 1 (500) must offer Clamp quick-fix even while Slot 0 has prohibited underscores",
            slot1Fixes.isEmpty()
        );
        var slot1Elem = file.findElementAt(slot1Offset);
        assertNotNull(slot1Elem);
        var slot1DocElem = docProvider.getCustomDocumentationElement(editor, file, slot1Elem, slot1Offset);
        assertNotNull("Slot 1 documentation element must not be null while Slot 0 has underscores", slot1DocElem);
        String slot1Doc = docProvider.generateDoc(slot1DocElem, slot1Elem);
        assertNotNull("Slot 1 hover card must not be null", slot1Doc);
        assertTrue(
            "Slot 1 hover card must display violation status:\n" + slot1Doc,
            slot1Doc.contains("In Violation") || slot1Doc.contains("Violated")
        );

        // 3. Slot 2: 123.456 (while Slot 0 still has prohibited underscores!)
        int slot2Offset = text.indexOf("123.456") + 2;
        editor.getCaretModel().moveToOffset(slot2Offset);
        var slot2RoundFixes = myFixture.filterAvailableIntentions(
            "Round value to #maxSize 5 significant digits '123.46'"
        );
        var slot2TruncateFixes = myFixture.filterAvailableIntentions(
            "Truncate value to #maxSize 5 significant digits '123.45'"
        );
        assertFalse(
            "Slot 2 (123.456) must offer Round quick-fix while Slot 0 has underscores",
            slot2RoundFixes.isEmpty()
        );
        assertFalse(
            "Slot 2 (123.456) must offer Truncate quick-fix while Slot 0 has underscores",
            slot2TruncateFixes.isEmpty()
        );
        var slot2Elem = file.findElementAt(slot2Offset);
        assertNotNull(slot2Elem);
        var slot2DocElem = docProvider.getCustomDocumentationElement(editor, file, slot2Elem, slot2Offset);
        assertNotNull("Slot 2 documentation element must not be null while Slot 0 has underscores", slot2DocElem);
        String slot2Doc = docProvider.generateDoc(slot2DocElem, slot2Elem);
        assertNotNull("Slot 2 hover card must not be null", slot2Doc);
        assertTrue(
            "Slot 2 hover card must display violation status:\n" + slot2Doc,
            slot2Doc.contains("In Violation") || slot2Doc.contains("Violated")
        );

        // 4. Slot 3: 250 (while Slot 0 still has prohibited underscores!)
        int slot3Offset = text.indexOf("250") + 1;
        editor.getCaretModel().moveToOffset(slot3Offset);
        var slot3AppendFixes = myFixture.filterAvailableIntentions(
            "Append '.0' to integer value '250.0'"
        );
        assertFalse(
            "Slot 3 (250) must offer Append '.0' quick-fix while Slot 0 has underscores",
            slot3AppendFixes.isEmpty()
        );
        var slot3Elem = file.findElementAt(slot3Offset);
        assertNotNull(slot3Elem);
        var slot3DocElem = docProvider.getCustomDocumentationElement(editor, file, slot3Elem, slot3Offset);
        assertNotNull("Slot 3 documentation element must not be null while Slot 0 has underscores", slot3DocElem);
        String slot3Doc = docProvider.generateDoc(slot3DocElem, slot3Elem);
        assertNotNull("Slot 3 hover card must not be null", slot3Doc);
        assertTrue(
            "Slot 3 hover card must display violation status:\n" + slot3Doc,
            slot3Doc.contains("In Violation") || slot3Doc.contains("Violated")
        );
    }

    public void testStage7OptionSomeScalarArityOneTupleUnwrapQuickFix() {
        var text = """
            {
              :defs {
                :OptionalCount :Option({ #unsigned #size 16 } :Int)
              }
              :type :OptionalCount
              :body #Some (<caret>42)
            }
            """;
        myFixture.configureByText("stage7_option_some_tuple.stvn", text);
        myFixture.doHighlighting();

        List<IntentionAction> quickFixes = myFixture.getAllQuickFixes();
        List<String> labels = quickFixes.stream().map(IntentionAction::getText).toList();
        assertTrue("Expected at least 2 quick-fixes on (42), found: " + labels, quickFixes.size() >= 2);
        assertEquals(
            "Primary quick-fix must unwrap parentheses around scalar value '42'",
            "Unwrap parentheses around scalar value '42'",
            quickFixes.get(0).getText()
        );
        assertEquals(
            "Secondary quick-fix must be exemplar replacement",
            "Replace with exemplar value '0'",
            quickFixes.get(1).getText()
        );

        myFixture.launchAction(quickFixes.get(0));
        assertTrue(
            "Unwrapping (42) must produce ':body #Some 42'",
            myFixture.getEditor().getDocument().getText().contains(":body #Some 42")
        );
    }

    public void testStage8StringLexicographicalBoundsProximityOrderedClampQuickFixes() {
        var upperViolationText = """
            {
              :defs {
                :BoundedLexCode {
                  #minSize 4
                  #maxSize 4
                  #minIncl "B000"
                  #maxIncl "M999"
                } :String
              }
              :type :BoundedLexCode
              :body "Z9<caret>99"
            }
            """;
        myFixture.configureByText("stage8_string_upper_clamp.stvn", upperViolationText);
        myFixture.doHighlighting();

        List<IntentionAction> upperFixes = myFixture.getAllQuickFixes();
        List<String> upperLabels = upperFixes.stream().map(IntentionAction::getText).toList();
        assertEquals(
            "Expected proximity-ordered maximum then minimum clamp fixes without duplicate exemplar fix",
            List.of(
                "Clamp value to maximum bound '\"M999\"'",
                "Clamp value to minimum bound '\"B000\"'"
            ),
            upperLabels
        );
        myFixture.launchAction(upperFixes.get(0));
        assertTrue(myFixture.getEditor().getDocument().getText().contains(":body \"M999\""));

        var lowerViolationText = """
            {
              :defs {
                :BoundedLexCode {
                  #minSize 4
                  #maxSize 4
                  #minIncl "B000"
                  #maxIncl "M999"
                } :String
              }
              :type :BoundedLexCode
              :body "A0<caret>00"
            }
            """;
        myFixture.configureByText("stage8_string_lower_clamp.stvn", lowerViolationText);
        myFixture.doHighlighting();

        List<IntentionAction> lowerFixes = myFixture.getAllQuickFixes();
        List<String> lowerLabels = lowerFixes.stream().map(IntentionAction::getText).toList();
        assertEquals(
            "Expected proximity-ordered minimum then maximum clamp fixes without duplicate exemplar fix",
            List.of(
                "Clamp value to minimum bound '\"B000\"'",
                "Clamp value to maximum bound '\"M999\"'"
            ),
            lowerLabels
        );
        myFixture.launchAction(lowerFixes.get(0));
        assertTrue(myFixture.getEditor().getDocument().getText().contains(":body \"B000\""));
    }

    public void testStage9CrossFileIncludeAliasIntegerBoundClampingAndExemplarQuickFixes() {
        myFixture.addFileToProject(
            "gauntlet_v2_shared_defs.stvn_di",
            """
            {
              :meta {
                #name "gauntlet_v2_shared_defs"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :package :com/acme/shared/ledger {
                  :LedgerChannel {
                    #unsigned
                    #size 16
                    #minIncl 1024
                  } :Int
                }
              }
            }
            """
        );
        var text = """
            {
              :meta {
                #name "stage9_imported_ledger"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [
                  "./gauntlet_v2_shared_defs.stvn_di"
                  { #strip }
                  {
                    :LedgerChannel :ImportedLedgerChannel
                  }
                ]
              }
              :type :ImportedLedgerChannel
              :body 5<caret>00
            }
            """;
        myFixture.configureByText("stage9_imported_ledger.stvn_i", text);
        myFixture.doHighlighting();

        List<IntentionAction> fixes = myFixture.getAllQuickFixes().stream()
            .filter(a -> !a.getText().startsWith("Open included module"))
            .toList();
        List<String> labels = fixes.stream().map(IntentionAction::getText).toList();
        assertTrue("Expected at least 2 quick-fixes on 500, found: " + labels, fixes.size() >= 2);
        assertEquals("Clamp value to minimum bound '1024'", fixes.get(0).getText());
        assertEquals("Replace with exemplar value '1024'", fixes.get(1).getText());

        myFixture.launchAction(fixes.get(0));
        assertTrue(myFixture.getEditor().getDocument().getText().contains(":body 1024"));
    }
}
